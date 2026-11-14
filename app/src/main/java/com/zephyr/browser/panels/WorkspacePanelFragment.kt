package com.zephyr.browser.panels

import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.zephyr.browser.R
import com.zephyr.browser.ai.AiWorkspace
import com.zephyr.browser.ai.AiWriteReview
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * v1.7.57 «Проекты» — the assistant's workspace, with a way in.
 *
 * Until now the assistant could create a whole project and the only way to see
 * it was to go hunting in a file manager. That is half a feature. This is the
 * other half: browse Documents/Zephyr, read what was written, zip it, share it.
 *
 * It also carries the piece the review sheet could not: a summary of what the
 * LAST answer changed, and one button to put it all back. The review asks
 * per-file, before the fact; this answers "actually, undo all of that", after.
 */
class WorkspacePanelFragment : Fragment() {

    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var dir: File = AiWorkspace.root()
    private lateinit var crumb: TextView
    private lateinit var list: LinearLayout
    private lateinit var undoCard: LinearLayout

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        val ctx = requireContext()
        val root = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }

        // v1.7.95 (F-016): "projects" means files the assistant made, which is
        // not something a first-time reader would guess from the name.
        PanelUi.hintBanner(ctx, "workspace_hint", R.drawable.ic_folder,
            ctx.getString(R.string.ws_hint_title), ctx.getString(R.string.ws_hint_body))
            ?.let { root.addView(it) }

        // ── last answer's changes, with an undo ───────────────────────────────
        undoCard = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = GradientDrawable().apply {
                cornerRadius = dp(16).toFloat()
                setColor(0x2210B981)
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(dp(12), dp(10), dp(12), 0) }
            visibility = View.GONE
        }
        root.addView(undoCard)

        // ── breadcrumb + up ───────────────────────────────────────────────────
        val bar = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(4))
        }
        // v1.7.75 (user: «там две кнопки назад»). The screen already sits under
        // the panel toolbar's back arrow, so a second one right below it made
        // "назад" ambiguous — one closed the screen, the other went up a folder,
        // and nothing said which. The path itself is the way up now, and the
        // system back button goes up before it closes.
        crumb = TextView(ctx).apply {
            textSize = 13f
            maxLines = 1
            setPadding(dp(6), dp(6), dp(6), dp(6))
            isClickable = true
            background = PanelUi.ripple(ctx)
            setTextColor(PanelUi.accent(ctx))
            setOnClickListener { goUp() }
        }
        bar.addView(HorizontalScrollView(ctx).apply {
            isHorizontalScrollBarEnabled = false
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            addView(crumb)
        })
        bar.addView(ImageView(ctx).apply {
            setImageResource(R.drawable.ic_file)
            layoutParams = LinearLayout.LayoutParams(dp(38), dp(38))
            setPadding(dp(9), dp(9), dp(9), dp(9))
            imageTintList = ColorStateList.valueOf(PanelUi.accent(ctx))
            isClickable = true
            foreground = PanelUi.ripple(ctx)
            contentDescription = getString(R.string.ws_zip)
            setOnClickListener { zipCurrent() }
        })
        root.addView(bar)

        list = PanelUi.listColumn(ctx)
        root.addView(ScrollView(ctx).apply {
            isFillViewport = true
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
            addView(list)
        })

        // system back climbs out of a subfolder first, and only then closes
        requireActivity().onBackPressedDispatcher.addCallback(
            viewLifecycleOwner,
            object : androidx.activity.OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (dir.absolutePath == AiWorkspace.rootPath()) {
                        isEnabled = false
                        requireActivity().onBackPressedDispatcher.onBackPressed()
                    } else goUp()
                }
            }
        )

        render()
        return root
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    // ---------------- listing ----------------

    private fun goUp() {
        val rootPath = AiWorkspace.rootPath()
        if (dir.absolutePath == rootPath) { activity?.finish(); return }
        dir = dir.parentFile ?: AiWorkspace.root()
        render()
    }

    private fun render() {
        val ctx = context ?: return
        renderUndo(ctx)

        // at the root there is nowhere to go up to, so it does not pretend
        val rel = dir.absolutePath.removePrefix(File(AiWorkspace.rootPath()).parent ?: "")
            .ifBlank { dir.absolutePath }
        crumb.text = if (dir.absolutePath == AiWorkspace.rootPath()) rel else "↑  " + rel
        crumb.isClickable = dir.absolutePath != AiWorkspace.rootPath()

        list.removeAllViews()
        val kids = try {
            dir.listFiles()?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
        } catch (_: Throwable) { null } ?: emptyList()

        if (kids.isEmpty()) {
            list.addView(PanelUi.emptyState(ctx, R.drawable.ic_folder,
                R.string.empty_workspace_title, R.string.empty_workspace_hint))
            return
        }
        for (f in kids) list.addView(entry(f))
    }

    private fun renderUndo(ctx: android.content.Context) {
        val changes = AiWriteReview.sessionChanges()
        undoCard.removeAllViews()
        if (changes.isEmpty()) { undoCard.visibility = View.GONE; return }
        undoCard.visibility = View.VISIBLE
        val added = changes.sumOf { it.added }
        val removed = changes.sumOf { it.removed }
        undoCard.addView(TextView(ctx).apply {
            text = getString(R.string.ws_last_fmt, changes.size) + "   +" + added +
                (if (removed > 0) "  −$removed" else "")
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(PanelUi.themedColor(ctx, R.attr.colorOnSurface))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        undoCard.addView(TextView(ctx).apply {
            setText(R.string.ws_undo)
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(0xFFEF4444.toInt())
            setPadding(dp(12), dp(6), dp(6), dp(6))
            isClickable = true
            foreground = PanelUi.ripple(ctx)
            setOnClickListener {
                MaterialAlertDialogBuilder(ctx)
                    .setTitle(R.string.ws_undo)
                    .setMessage(getString(R.string.ws_undo_msg_fmt, changes.size))
                    .setPositiveButton(R.string.ok) { _, _ ->
                        val n = AiWriteReview.revertSession()
                        Toast.makeText(ctx, getString(R.string.ws_undo_done_fmt, n), Toast.LENGTH_SHORT).show()
                        render()
                    }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
            }
        })
    }

    /**
     * v1.7.74 (user: «они все в список файлы и папки — неудобно ничего»).
     *
     * A row of names is fine for a folder of source files and useless for a
     * folder of documents: the thing that identifies a quote is what it looks
     * like. So anything that CAN be shown is shown — a PDF's first page, an
     * image itself — and everything else keeps the compact row it deserves.
     */
    private fun entry(f: File): View {
        val ctx = requireContext()
        val isDir = f.isDirectory
        val sub = if (isDir) {
            val n = try { f.listFiles()?.size ?: 0 } catch (_: Throwable) { 0 }
            getString(R.string.ws_items_fmt, n)
        } else AiWorkspace.human(f.length())

        val ext = f.extension.lowercase()
        val showable = !isDir && (ext == "pdf" || ext in setOf("jpg", "jpeg", "png", "webp"))

        val row = PanelUi.cardRow(
            ctx,
            leading = if (showable) thumbFor(f, ext) else null,
            iconRes = if (showable) null else if (isDir) R.drawable.ic_folder else R.drawable.ic_file,
            title = f.name,
            subtitle = sub,
            onLongClick = { fileMenu(f) },
            onClick = { if (isDir) { dir = f; render() } else viewFile(f) }
        )
        return row
    }

    /** A 46dp preview: rendered for PDFs, decoded for images. */
    private fun thumbFor(f: File, ext: String): View {
        val ctx = requireContext()
        val frame = PanelUi.thumbFrame(ctx, 46, 10)
        val iv = ImageView(ctx).apply {
            layoutParams = android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT
            )
            scaleType = ImageView.ScaleType.CENTER_CROP
        }
        frame.addView(iv)
        io.launch {
            val bmp = try {
                if (ext == "pdf") {
                    com.zephyr.browser.ai.AiPdf.pageImage(f, 1, maxWidth = 200)?.let { data ->
                        val raw = android.util.Base64.decode(
                            data.substringAfter("base64,"), android.util.Base64.NO_WRAP
                        )
                        android.graphics.BitmapFactory.decodeByteArray(raw, 0, raw.size)
                    }
                } else {
                    // a full-size photo would be megabytes for a 46dp square
                    val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = 8 }
                    android.graphics.BitmapFactory.decodeFile(f.absolutePath, opts)
                }
            } catch (_: Throwable) { null }
            if (bmp != null) withContext(Dispatchers.Main) {
                if (isAdded) iv.setImageBitmap(bmp)
            }
        }
        return frame
    }

    // ---------------- file actions ----------------

    private fun fileMenu(f: File) {
        val ctx = context ?: return
        val actions = ArrayList<Pair<String, () -> Unit>>()
        if (!f.isDirectory) actions.add(getString(R.string.ws_view) to { viewFile(f) })
        actions.add(getString(R.string.ws_zip) to { zip(f) })
        actions.add(getString(R.string.ws_share) to { share(f) })
        actions.add(getString(R.string.ws_rename) to { rename(f) })
        actions.add(getString(R.string.delete) to { confirmDelete(f) })
        MaterialAlertDialogBuilder(ctx)
            .setTitle(f.name)
            .setItems(actions.map { it.first }.toTypedArray()) { _, w -> actions[w].second() }
            .show()
    }

    /**
     * Read a file into a monospace viewer.
     *
     * Binary content is refused rather than rendered as mojibake, and the read
     * runs off the UI thread — a source file is small, but this is also the
     * screen someone opens on a 5 MB log.
     */
    private fun viewFile(f: File) {
        val ctx = context ?: return
        // v1.7.74 (user: «при нажатии на pdf выходит „это не текстовый файл"»).
        // It is not text, but it is perfectly viewable — and a document the
        // assistant just produced is the one file a person most wants to open.
        val ext = f.extension.lowercase()
        if (ext == "pdf") { PdfViewer.show(requireActivity(), f); return }
        if (ext in setOf("jpg", "jpeg", "png", "webp", "gif")) { openExternally(f); return }
        if (ext in setOf("docx", "xlsx", "pptx", "doc", "xls", "zip", "apk")) {
            openExternally(f); return
        }
        if (f.length() > 2L * 1024 * 1024) {
            Toast.makeText(ctx, R.string.ws_too_big, Toast.LENGTH_SHORT).show()
            return
        }
        io.launch {
            val text = try {
                val bytes = f.readBytes()
                val s = String(bytes, Charsets.UTF_8)
                val ctrl = s.take(2000).count { it.code < 9 || (it.code in 14..31) }
                if (ctrl > 40) null else s
            } catch (_: Throwable) { null }
            withContext(Dispatchers.Main) {
                if (!isAdded) return@withContext
                if (text == null) {
                    Toast.makeText(requireContext(), R.string.ws_binary, Toast.LENGTH_SHORT).show()
                    return@withContext
                }
                showText(f.name, text)
            }
        }
    }

    private fun showText(name: String, text: String) {
        val ctx = context ?: return
        val body = TextView(ctx).apply {
            this.text = text
            textSize = 11.5f
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
            setPadding(dp(14), dp(10), dp(14), dp(10))
        }
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (ctx.resources.displayMetrics.heightPixels * 0.78f).toInt()
            )
            addView(TextView(ctx).apply {
                this.text = name
                textSize = 17f
                typeface = Typeface.DEFAULT_BOLD
                setPadding(dp(20), dp(18), dp(20), dp(8))
            })
            // code does not wrap — it scrolls, in both directions
            addView(ScrollView(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
                addView(HorizontalScrollView(ctx).apply { addView(body) })
            })
        }
        MaterialAlertDialogBuilder(ctx)
            .setView(root)
            .setPositiveButton(R.string.ok, null)
            .show()
    }

    /** Hand a file to whatever app on the phone knows the format. */
    private fun openExternally(f: File) {
        val ctx = context ?: return
        try {
            val uri = androidx.core.content.FileProvider.getUriForFile(
                ctx, ctx.packageName + ".fileprovider", f
            )
            val mime = android.webkit.MimeTypeMap.getSingleton()
                .getMimeTypeFromExtension(f.extension.lowercase()) ?: "*/*"
            startActivity(
                android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, mime)
                    addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            )
        } catch (_: Throwable) {
            Toast.makeText(ctx, R.string.ws_no_app, Toast.LENGTH_SHORT).show()
        }
    }

    private fun zipCurrent() = zip(dir)

    private fun zip(f: File) {
        val ctx = context ?: return
        io.launch {
            val msg = try {
                val z = AiWorkspace.zip(f, null)
                AiWorkspace.index(ctx.applicationContext, z.file)
                getString(R.string.ws_zipped_fmt, z.file.name, z.entries)
            } catch (t: Throwable) {
                (t.localizedMessage ?: t.javaClass.simpleName)
            }
            withContext(Dispatchers.Main) {
                if (!isAdded) return@withContext
                Toast.makeText(requireContext(), msg, Toast.LENGTH_LONG).show()
                render()
            }
        }
    }

    private fun share(f: File) {
        val ctx = context ?: return
        if (f.isDirectory) {
            Toast.makeText(ctx, R.string.ws_share_dir, Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val uri = androidx.core.content.FileProvider.getUriForFile(
                ctx, ctx.packageName + ".fileprovider", f
            )
            startActivity(
                android.content.Intent.createChooser(
                    android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                        type = "*/*"
                        putExtra(android.content.Intent.EXTRA_STREAM, uri)
                        addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    },
                    getString(R.string.ws_share)
                )
            )
        } catch (_: Throwable) {
            Toast.makeText(ctx, R.string.ws_share_fail, Toast.LENGTH_SHORT).show()
        }
    }

    private fun rename(f: File) {
        val ctx = context ?: return
        val field = android.widget.EditText(ctx).apply {
            setText(f.name)
            setSingleLine(true)
        }
        MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.ws_rename)
            .setView(LinearLayout(ctx).apply {
                setPadding(dp(22), dp(8), dp(22), dp(8))
                addView(field, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            })
            .setPositiveButton(R.string.save) { _, _ ->
                val name = field.text.toString().trim()
                    .replace(Regex("""[\\/:*?"<>|]"""), "_")
                if (name.isBlank()) return@setPositiveButton
                try { f.renameTo(File(f.parentFile, name)) } catch (_: Throwable) {}
                render()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun confirmDelete(f: File) {
        val ctx = context ?: return
        MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.delete)
            .setMessage(f.absolutePath)
            .setPositiveButton(R.string.delete) { _, _ ->
                io.launch {
                    AiWorkspace.delete(f)
                    withContext(Dispatchers.Main) { if (isAdded) render() }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()
}
