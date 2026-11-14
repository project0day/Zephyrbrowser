package com.zephyr.browser

/** v1.7.3 shared content-type detection for downloads.
 *
 *  Servers frequently answer media requests with a generic binary type (or hand one
 *  over with an empty type), and URL file names often carry no extension at all
 *  (site.com/photo/12345). The old engine trusted those values, so photos landed in
 *  the Downloads panel under the Files chip with broken previews.
 *
 *  [resolve] merges three sources — the HTTP response header, magic bytes from the
 *  first 64 bytes of the body, and the file name extension — into one concrete type,
 *  with the generic values (empty, the any-type wildcard, octet-stream variants)
 *  treated as "unknown". [sniff] holds the magic-byte table and is reused by the
 *  Downloads panel to repair legacy database rows; [MIME_EXT] maps the sniffable
 *  types back to canonical extensions so extension-less names can be repaired. */
object MimeType {

    /** Mime values that carry no information about the real content. */
    private val GENERIC = setOf(
        "", "*/*", "application/octet-stream", "binary/octet-stream", "application/force-download")

    fun isGeneric(mime: String): Boolean = mime.trim().lowercase() in GENERIC

    /** Extension of a file NAME (dot-final segment, 1..5 alnum chars, lowercased).
     *  Deliberately NOT Urls.fileExtOf: that helper only accepts letters, so digital
     *  extensions (mp3, mp4, m4a, 3gp, 7z...) read as "no extension" — which would make
     *  the engine's name repair double-extend "song.mp3" into "song.mp3.mp3". */
    fun extOf(name: String): String? {
        val seg = name.substringBefore('#').substringBefore('?').substringAfterLast('/')
        val ext = seg.substringAfterLast('.', "").lowercase()
        return if (ext.length in 1..5 && ext.all { it.isLetterOrDigit() }) ext else null
    }

    /** Canonical extension per sniffable type — used to repair extension-less names. */
    val MIME_EXT: Map<String, String> = mapOf(
        "image/jpeg" to "jpg",
        "image/png" to "png",
        "image/gif" to "gif",
        "image/webp" to "webp",
        "image/bmp" to "bmp",
        "application/pdf" to "pdf",
        "video/mp4" to "mp4",
        "video/webm" to "webm",
        "audio/mpeg" to "mp3",
        "audio/ogg" to "ogg",
        "audio/flac" to "flac",
        "application/zip" to "zip",
        "application/vnd.rar" to "rar",
        "application/x-7z-compressed" to "7z",
        "application/vnd.android.package-archive" to "apk"
    )

    /** Picks the real content type. Priority: (a) a specific HTTP header value,
     *  (b) magic bytes of the body head, (c) a specific fallback (the request mime),
     *  (d) the file name extension. Never returns an empty value — the extension
     *  guess degrades to the any-type wildcard when nothing is known. */
    fun resolve(headerMime: String, fallbackMime: String, head: ByteArray, name: String): String {
        val h = headerMime.substringBefore(';').trim().lowercase()
        if (!isGeneric(h)) return h
        sniff(head, name)?.let { return it }
        val f = fallbackMime.substringBefore(';').trim().lowercase()
        if (!isGeneric(f)) return f
        return DownloadEngine.guessMime(name)
    }

    /** Magic-byte sniffing of the file head (the first 64 bytes are enough).
     *  Returns null when the signature is not recognized. */
    fun sniff(head: ByteArray, name: String): String? {
        if (head.isEmpty()) return null
        val ext = extOf(name) ?: ""
        return when {
            at(head, 0, 0xFF, 0xD8, 0xFF) -> "image/jpeg"                                  // JPEG
            at(head, 0, 0x89, 0x50, 0x4E, 0x47) -> "image/png"                            // PNG
            at(head, 0, 0x47, 0x49, 0x46, 0x38) -> "image/gif"                            // GIF8
            at(head, 0, 0x42, 0x4D) -> "image/bmp"                                         // BM
            at(head, 0, 0x52, 0x49, 0x46, 0x46) && at(head, 8, 0x57, 0x45, 0x42, 0x50) -> "image/webp"  // RIFF..WEBP
            at(head, 0, 0x25, 0x50, 0x44, 0x46) -> "application/pdf"                      // %PDF
            at(head, 0, 0x50, 0x4B, 0x03, 0x04) ->                                         // zip container
                if (ext == "apk") "application/vnd.android.package-archive" else "application/zip"
            at(head, 0, 0x52, 0x61, 0x72, 0x21) -> "application/vnd.rar"                  // Rar!
            at(head, 0, 0x37, 0x7A, 0xBC, 0xAF) -> "application/x-7z-compressed"          // 7z
            at(head, 4, 0x66, 0x74, 0x79, 0x70) ->                                         // ....ftyp
                if (ext == "m4a") "audio/mp4" else "video/mp4"
            at(head, 0, 0x1A, 0x45, 0xDF, 0xA3) ->                                         // EBML
                if (ext == "opus" || ext == "ogg") "audio/ogg" else "video/webm"
            at(head, 0, 0x4F, 0x67, 0x67, 0x53) -> "audio/ogg"                            // OggS
            at(head, 0, 0x49, 0x44, 0x33) -> "audio/mpeg"                                  // ID3
            at(head, 0, 0xFF, 0xFB) || at(head, 0, 0xFF, 0xF3) || at(head, 0, 0xFF, 0xF2) -> "audio/mpeg"
            at(head, 0, 0x66, 0x4C, 0x61, 0x43) -> "audio/flac"                           // fLaC
            else -> null
        }
    }

    /** True when head holds the given byte sequence at the given offset. */
    private fun at(head: ByteArray, offset: Int, vararg sig: Int): Boolean {
        if (head.size < offset + sig.size) return false
        for (i in sig.indices) {
            if ((head[offset + i].toInt() and 0xFF) != sig[i]) return false
        }
        return true
    }
}
