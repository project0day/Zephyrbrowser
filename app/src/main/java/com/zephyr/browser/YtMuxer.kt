package com.zephyr.browser

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteBuffer

/**
 * v1.7.9 (user: «Найденные видео — опция хорошая, но ничего не парсится»):
 * merges the two ADAPTIVE YouTube streams — a video-only file and an
 * audio-only file — into a single playable .mp4.
 *
 * DownloadEngine.enqueueMuxDownload fetches both streams into cacheDir temp
 * files, [muxToTemp] interleaves them by presentation time
 * (MediaExtractor × 2 → MediaMuxer), and the engine streams the result through
 * its normal storage routing (SAF folder / MediaStore Movies / app dir).
 *
 * NOTE: MediaMuxer can only write to a real file path / FileDescriptor — never
 * an OutputStream — so the merge always happens into a cacheDir temp file
 * which the caller then streams to the final target.
 */
object YtMuxer {

    /**
     * Merges [videoFile] + [audioFile] into a fresh temp .mp4 next to them
     * (cacheDir). Returns the finished file, or null on ANY failure (missing
     * track, unreadable input, unsupported codec, muxer error) — the caller
     * then falls back to storing the video-only stream.
     */
    fun muxToTemp(videoFile: File, audioFile: File): File? {
        var out: File? = null
        return try {
            val dir = videoFile.parentFile
            if (dir == null || !dir.isDirectory) return null
            out = File(dir, "mux_out_${System.currentTimeMillis()}.mp4")
            mux(videoFile, audioFile, out)
            if (out.length() > 0L) out else null
        } catch (_: Exception) {
            try { out?.delete() } catch (_: Exception) {}
            null
        }
    }

    /**
     * Core merge: selects the FIRST video track of [videoFile] and the FIRST
     * audio track of [audioFile], then copies the samples into [outFile]
     * ordered by presentationTimeUs (the standard two-extractor interleave).
     * Throws on any failure; releases extractors/muxer in [finally].
     */
    fun mux(videoFile: File, audioFile: File, outFile: File) {
        val vExt = MediaExtractor()
        val aExt = MediaExtractor()
        var muxer: MediaMuxer? = null
        try {
            vExt.setDataSource(videoFile.absolutePath)
            aExt.setDataSource(audioFile.absolutePath)

            var vFormat: MediaFormat? = null
            for (i in 0 until vExt.trackCount) {
                val f = vExt.getTrackFormat(i)
                if ((f.getString(MediaFormat.KEY_MIME) ?: "").startsWith("video/")) {
                    vFormat = f
                    vExt.selectTrack(i)
                    break
                }
            }
            var aFormat: MediaFormat? = null
            for (i in 0 until aExt.trackCount) {
                val f = aExt.getTrackFormat(i)
                if ((f.getString(MediaFormat.KEY_MIME) ?: "").startsWith("audio/")) {
                    aFormat = f
                    aExt.selectTrack(i)
                    break
                }
            }
            val vf = vFormat ?: throw IllegalStateException("no video track")
            val af = aFormat ?: throw IllegalStateException("no audio track")
            // both streams' timestamps start near 0 — a plain merge is in sync
            vExt.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
            aExt.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC)

            muxer = MediaMuxer(outFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val vIdx = muxer.addTrack(vf)
            val aIdx = muxer.addTrack(af)
            if (vf.containsKey(MediaFormat.KEY_ROTATION)) {
                try { muxer.setOrientationHint(vf.getInteger(MediaFormat.KEY_ROTATION)) } catch (_: Exception) {}
            }
            muxer.start()

            val vBuf = ByteBuffer.allocateDirect(1 shl 20)   // ~1 MB per stream
            val aBuf = ByteBuffer.allocateDirect(1 shl 20)
            val vInfo = MediaCodec.BufferInfo()
            val aInfo = MediaCodec.BufferInfo()
            var vDone = false
            var aDone = false
            while (!vDone || !aDone) {
                val vTime = if (vDone) Long.MAX_VALUE else vExt.sampleTime
                val aTime = if (aDone) Long.MAX_VALUE else aExt.sampleTime
                if (vTime <= aTime) {
                    vInfo.offset = 0
                    vInfo.size = vExt.readSampleData(vBuf, 0)
                    if (vInfo.size < 0) { vDone = true; continue }
                    vInfo.presentationTimeUs = vExt.sampleTime
                    vInfo.flags = if (vExt.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                    muxer.writeSampleData(vIdx, vBuf, vInfo)
                    vExt.advance()
                } else {
                    aInfo.offset = 0
                    aInfo.size = aExt.readSampleData(aBuf, 0)
                    if (aInfo.size < 0) { aDone = true; continue }
                    aInfo.presentationTimeUs = aExt.sampleTime
                    aInfo.flags = if (aExt.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                    muxer.writeSampleData(aIdx, aBuf, aInfo)
                    aExt.advance()
                }
            }
            muxer.stop()          // success — write the index (moov) atom
        } finally {
            try { muxer?.release() } catch (_: Exception) {}
            try { vExt.release() } catch (_: Exception) {}
            try { aExt.release() } catch (_: Exception) {}
        }
    }
}
