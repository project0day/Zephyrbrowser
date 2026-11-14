package com.zephyr.browser.panels

import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.zephyr.browser.FaviconLoader
import com.zephyr.browser.MainActivity
import com.zephyr.browser.Prefs
import com.zephyr.browser.R
import com.zephyr.browser.Urls
import com.zephyr.browser.db.AppDb
import com.zephyr.browser.db.BookmarkEntity
import com.zephyr.browser.db.BookmarkFolderEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Bookmarks panel — v1.5.1 rework.
 *  - private group REMOVED (the "add to private" checkbox never worked; legacy private
 *    rows are auto-migrated into the regular list once on load);
 *  - pretty premium rows: rounded favicon tile + title + domain, correct spacing;
 *  - LIST / GRID display modes (persisted), live SEARCH, folders, import/export.
 *  - TAP opens the bookmark in a new browser tab (v1.5.1 root fix for "tap does not
 *    open": the old immediate finish() raced the singleTask relaunch and could swallow
 *    the ACTION_VIEW intent — now the panel closes only after the launch settles).
 */
class BookmarksPanelFragment : Fragment() {

    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val main = CoroutineScope(Dispatchers.Main)

    private var currentFolder = 0L
    private var folderName = ""
    private var items: List<BookmarkEntity> = emptyList()
    private var folders: List<BookmarkFolderEntity> = emptyList()
    private var sortAlpha = false
    private var gridMode = false
    private var query = ""

    private lateinit var list: LinearLayout
    private lateinit var searchInput: EditText

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        val ctx = requireContext()
        gridMode = Prefs.bmViewMode == 1
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT)
        }

        // ---- search + view-mode + tools row ----
        val top = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(6), dp(8), dp(2))
        }
        searchInput = EditText(ctx).apply {
            hint = getString(R.string.bm_search_hint)
            textSize = 13.5f
            maxLines = 1
            setSingleLine(true)
            background = PanelUi.searchFieldBg(ctx)
            setPadding(dp(14), dp(9), dp(14), dp(9))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(4)
            }
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(p0: CharSequence?, p1: Int, p2: Int, p3: Int) {}
                override fun onTextChanged(p0: CharSequence?, p1: Int, p2: Int, p3: Int) {}
                override fun afterTextChanged(e: Editable?) {
                    query = e?.toString()?.trim() ?: ""
                    rebuild()
                }
            })
        }
        top.addView(searchInput)
        fun ib(icon: Int, desc: Int, onClick: () -> Unit) = ImageButton(ctx).apply {
            setImageResource(icon)
            background = PanelUi.ripple(ctx)
            setPadding(dp(9), dp(9), dp(9), dp(9))
            layoutParams = LinearLayout.LayoutParams(dp(40), dp(40))
            imageTintList = android.content.res.ColorStateList.valueOf(
                PanelUi.themedColor(ctx, R.attr.colorOnSurfaceVariant))
            contentDescription = getString(desc)
            setOnClickListener { onClick() }
        }
        // list <-> grid toggle
        top.addView(ib(if (gridMode) R.drawable.ic_list else R.drawable.ic_grid, R.string.bm_view_mode) {
            gridMode = !gridMode
            Prefs.setBmViewMode(if (gridMode) 1 else 0)
            rebuild()
            Toast.makeText(ctx, getString(if (gridMode) R.string.bm_view_grid else R.string.bm_view_list), Toast.LENGTH_SHORT).show()
        })
        // sort
        top.addView(ib(R.drawable.ic_sort, R.string.bm_sort_alpha) {
            sortAlpha = !sortAlpha; reload()
            Toast.makeText(ctx, if (sortAlpha) R.string.bm_sort_alpha else R.string.bm_sort_date, Toast.LENGTH_SHORT).show()
        })
        // more tools (import / export / new folder) — anchored on the button itself
        val toolsBtn = ib(R.drawable.ic_more_vert, R.string.bm_tools) { }
        toolsBtn.setOnClickListener { v -> toolsMenu(v) }
        top.addView(toolsBtn)
        root.addView(top)

        val scroll = ScrollView(ctx)
        list = PanelUi.listColumn(ctx)
        scroll.addView(list)
        root.addView(scroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        return root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        reload()
    }

    private fun toolsMenu(anchor: View) {
        val ctx = context ?: return
        val menu = PopupMenu(ctx, anchor)
        menu.menu.add(0, 1, 0, getString(R.string.bm_new_folder))
        menu.menu.add(0, 2, 0, getString(R.string.bm_import_html))
        menu.menu.add(0, 3, 0, getString(R.string.bm_export_html))
        menu.setOnMenuItemClickListener { mi ->
            when (mi.itemId) {
                1 -> newFolderDialog()
                2 -> pickImport()
                3 -> exportHtml()
            }
            true
        }
        try { menu.show() } catch (_: Exception) {}
    }

    private fun reload() {
        val ctx = context ?: return
        io.launch {
            val db = AppDb.get(ctx.applicationContext)
            val all = try { db.bookmarkDao().allOnce() } catch (_: Exception) { emptyList() }
            // v1.5.1: one-time auto-migration — legacy "private" bookmarks (the group is
            // gone) fold into the regular list so nothing saved earlier is lost.
            for (b in all.filter { it.incognito }) {
                try { db.bookmarkDao().delete(b); db.bookmarkDao().insert(b.copy(incognito = false)) } catch (_: Exception) {}
            }
            val fs = try { db.bookmarkFolderDao().all() } catch (_: Exception) { emptyList() }
            folders = fs
            val group = if (all.any { it.incognito }) {
                try { db.bookmarkDao().allOnce().filter { !it.incognito } } catch (_: Exception) { all.filter { !it.incognito } }
            } else all.filter { !it.incognito }
            val inFolder = group.filter { it.folderId == currentFolder }
            items = if (sortAlpha) inFolder.sortedBy { it.title.lowercase() } else inFolder.sortedBy { it.createdAt }
            withContext(Dispatchers.Main) { rebuild() }
        }
    }

    private fun rebuild() {
        val ctx = context ?: return
        list.removeAllViews()

        // folder chips row (only in root)
        if (currentFolder == 0L && folders.isNotEmpty()) {
            val chips = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(dp(2), dp(4), dp(2), dp(4))
            }
            for (f in folders) {
                chips.addView(TextView(ctx).apply {
                    text = "📁 ${f.name}"
                    textSize = 12f
                    setPadding(dp(12), dp(7), dp(12), dp(7))
                    background = folderChipBg()
                    setOnClickListener { currentFolder = f.id; folderName = f.name; reload() }
                    setOnLongClickListener {
                        folderMenu(f); true
                    }
                })
            }
            list.addView(HorizontalChips(ctx, chips))
        }

        if (currentFolder != 0L) {
            // up row
            list.addView(PanelUi.entryRow(ctx, R.drawable.ic_folder, "..", getString(R.string.bm_folder_root)) {
                currentFolder = 0; folderName = ""; reload()
            })
        }

        val visible = if (query.isEmpty()) items else items.filter {
            it.title.contains(query, ignoreCase = true) || it.url.contains(query, ignoreCase = true)
        }

        if (visible.isEmpty()) {
            list.addView(PanelUi.emptyView(ctx,
                if (query.isNotEmpty()) R.string.bm_search_empty else R.string.bm_empty_hint))
        }

        if (gridMode) for (pair in visible.chunked(2)) {
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.START
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    bottomMargin = dp(12)
                }
            }
            for (b in pair) {
                val card = gridCard(b)
                card.layoutParams = LinearLayout.LayoutParams(0, dp(168), 1f).apply {
                    marginEnd = dp(5)
                }
                row.addView(card)
            }
            if (pair.size == 1) row.addView(View(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(0, dp(1), 1f)
            })
            list.addView(row)
        }
        else for (b in visible) {
            list.addView(listRow(b))
        }
        // v1.5.1: gentle cascade entrance of the refreshed list
        com.zephyr.browser.ui.Anim.stagger(list)
    }

    // ---------------- two display modes ----------------

    /** Premium LIST row: rounded favicon tile (mini preview) + title + domain + menu. */
    private fun listRow(b: BookmarkEntity): View {
        val ctx = requireContext()
        val tile = LinearLayout(ctx).apply {
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(PanelUi.themedColor(ctx, R.attr.colorSurfaceVariant, 0xFFF1F3F4.toInt()))
            }
            layoutParams = LinearLayout.LayoutParams(dp(46), dp(46)).apply {
                marginEnd = dp(14)
            }
            clipToOutline = true
            addView(ImageView(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(dp(26), dp(26))
                scaleType = ImageView.ScaleType.FIT_CENTER
            }.also { it.setImageBitmap(FaviconLoader.load(ctx, b.url, b.title)) })
        }
        val moreBtn = ImageButton(ctx).apply {
            setImageResource(R.drawable.ic_more_vert)
            background = PanelUi.ripple(ctx)
            setPadding(dp(8), dp(8), dp(8), dp(8))
            layoutParams = LinearLayout.LayoutParams(dp(38), dp(38))
            imageTintList = android.content.res.ColorStateList.valueOf(
                PanelUi.themedColor(ctx, R.attr.colorOnSurfaceVariant))
            contentDescription = getString(R.string.bm_edit)
            setOnClickListener { itemMenuAnchor(b, this) }
        }
        val row = PanelUi.cardRow(
            ctx,
            leading = tile,
            title = b.title.ifEmpty { Urls.domainOf(b.url) },
            subtitle = Urls.domainOf(b.url).ifEmpty { b.url },
            trailing = moreBtn
        ) {
            openInNewTab(b.url)
        }
        row.setOnLongClickListener { itemMenuAnchor(b, moreBtn); true }
        // v1.5.1 FIX: proper bottom spacing between cards (the swipeable wrapper used to
        // swallow cardRow's margin → borders visually touched each other).
        val wrap = PanelUi.swipeable(ctx, row) {
            io.launch {
                try { AppDb.get(ctx.applicationContext).bookmarkDao().delete(b) } catch (_: Exception) {}
                withContext(Dispatchers.Main) { reload() }
            }
        }
        wrap.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(10)
        }
        return wrap
    }

    /** Premium GRID card: big favicon preview block + title + domain. */
    private fun gridCard(b: BookmarkEntity): View {
        val ctx = requireContext()
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(10), dp(10), dp(8))
            background = PanelUi.cardBg(ctx, radiusDp = 18)
            clipToOutline = true
            isClickable = true
            isFocusable = true
            foreground = PanelUi.ripple(ctx)
            setOnClickListener { openInNewTab(b.url) }
            setOnLongClickListener { itemMenuAnchor(b, null); true }
        }
        // preview block (fixed height, favicon centered) — like a mini screenshot
        val preview = android.widget.FrameLayout(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f).apply {
                bottomMargin = dp(8)
            }
            background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(PanelUi.themedColor(ctx, R.attr.colorSurfaceVariant, 0xFFF1F3F4.toInt()))
            }
            clipToOutline = true
            addView(ImageView(ctx).apply {
                layoutParams = android.widget.FrameLayout.LayoutParams(dp(40), dp(40), Gravity.CENTER)
                scaleType = ImageView.ScaleType.FIT_CENTER
            }.also { it.setImageBitmap(FaviconLoader.load(ctx, b.url, b.title)) })
        }
        card.addView(preview)
        card.addView(TextView(ctx).apply {
            text = b.title.ifEmpty { Urls.domainOf(b.url) }
            textSize = 12.5f
            maxLines = 2
            minLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            setTextColor(PanelUi.themedColor(ctx, R.attr.colorOnSurface))
            setLineSpacing(dp(2).toFloat(), 1f)
        })
        card.addView(TextView(ctx).apply {
            text = Urls.domainOf(b.url).ifEmpty { b.url }
            textSize = 10f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
            setPadding(0, dp(2), 0, 0)
            setTextColor(PanelUi.themedColor(ctx, R.attr.colorOnSurfaceVariant))
        })
        return card
    }

    private fun HorizontalChips(ctx: android.content.Context, inner: LinearLayout): View {
        val hs = android.widget.HorizontalScrollView(ctx)
        hs.addView(inner)
        return hs
    }

    private fun folderChipBg(): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(16).toFloat()
        setColor(PanelUi.themedColor(requireContext(), R.attr.colorSurfaceVariant))
        setStroke(dp(1), PanelUi.themedColor(requireContext(), R.attr.colorOutline))
    }

    // ---------------- actions ----------------

    /**
     * v1.5.1 ROOT FIX ("tap on bookmark did nothing"): the old flow called finish()
     * synchronously right after startActivity() with CLEAR_TOP against the singleTask
     * MainActivity — the panel teardown raced the relaunch and could swallow the
     * ACTION_VIEW intent. Now: REORDER_TO_FRONT (no destructive clear) + the panel
     * closes itself 260 ms later, after the launch has settled.
     * v1.7.20: PLUS the PendingOpen backup channel — some shells still lose the
     * onNewIntent delivery; MainActivity.onResume() now self-heals (see PendingOpen).
     * v1.7.21 PRIMARY FIX: the URL is handed STRAIGHT into the live MainActivity
     * (same process — PendingOpen.deliver()); when that succeeds the intent below
     * is only a payload-free bring-to-front signal, so NO shell can lose the URL
     * anymore. The full ACTION_VIEW intent is kept as the fallback for the case
     * where MainActivity is not alive (sink == null).
     */
    private fun openInNewTab(url: String) {
        val ctx = context ?: return
        com.zephyr.browser.PendingOpen.put(url, true)
        val delivered = com.zephyr.browser.PendingOpen.deliver(url, true)
        startActivity(Intent(ctx, MainActivity::class.java).apply {
            if (!delivered) {
                action = Intent.ACTION_VIEW
                data = Uri.parse(url)
                putExtra("zephyr.newtab", true)
            }
            addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        })
        // v1.7.22: capture the host NOW — the lambda must close the panel even if
        // the fragment got detached meanwhile (a stuck-open panel reads to the
        // user as «закладка не открылась»)
        val host = activity
        Handler(Looper.getMainLooper()).postDelayed({
            try { host?.finish() } catch (_: Exception) {}
        }, 260)
    }

    private fun itemMenuAnchor(b: BookmarkEntity, anchor: View?) {
        val ctx = context ?: return
        val menu = PopupMenu(ctx, anchor ?: list)
        menu.menu.add(0, 1, 0, getString(R.string.bm_open_new_tab))
        menu.menu.add(0, 2, 0, getString(R.string.bm_open_current))
        menu.menu.add(0, 3, 0, getString(R.string.bm_edit))
        menu.menu.add(0, 4, 0, getString(R.string.bm_move_to))
        menu.menu.add(0, 5, 0, getString(R.string.delete))
        menu.setOnMenuItemClickListener { mi ->
            when (mi.itemId) {
                1 -> openInNewTab(b.url)
                2 -> {
                    val ctx2 = context ?: return@setOnMenuItemClickListener true
                    com.zephyr.browser.PendingOpen.put(b.url, false)
                    // v1.7.21: same in-process-first delivery as openInNewTab()
                    val delivered = com.zephyr.browser.PendingOpen.deliver(b.url, false)
                    startActivity(Intent(ctx2, MainActivity::class.java).apply {
                        if (!delivered) {
                            action = Intent.ACTION_VIEW
                            data = Uri.parse(b.url)
                            putExtra("zephyr.newtab", false)
                        }
                        addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    })
                    val host2 = activity
                    Handler(Looper.getMainLooper()).postDelayed({
                        try { host2?.finish() } catch (_: Exception) {}
                    }, 260)
                }
                3 -> editDialog(b)
                4 -> moveDialog(b)
                5 -> io.launch {
                    try { AppDb.get(ctx.applicationContext).bookmarkDao().delete(b) } catch (_: Exception) {}
                    withContext(Dispatchers.Main) { reload() }
                }
            }
            true
        }
        try { menu.show() } catch (_: Exception) {}
    }

    private fun folderMenu(f: BookmarkFolderEntity) {
        val ctx = context ?: return
        MaterialAlertDialogBuilder(ctx)
            .setTitle(f.name)
            .setItems(arrayOf(getString(R.string.bm_rename), getString(R.string.delete))) { _, which ->
                if (which == 0) renameFolderDialog(f)
                else io.launch {
                    try {
                        val db = AppDb.get(ctx.applicationContext)
                        db.bookmarkFolderDao().detachChildren(f.id)
                        db.bookmarkFolderDao().delete(f)
                    } catch (_: Exception) {}
                    withContext(Dispatchers.Main) { reload() }
                }
            }
            .show()
    }

    private fun editDialog(b: BookmarkEntity) {
        val ctx = context ?: return
        val titleEdit = PanelUi.dialogField(ctx, getString(R.string.bm_title), b.title)
        val urlEdit = PanelUi.dialogField(ctx, getString(R.string.bm_url), b.url)
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(4), dp(8), dp(4))
            addView(PanelUi.fieldWrap(titleEdit))
            addView(PanelUi.fieldWrap(urlEdit))
        }
        MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.bm_edit)
            .setView(box)
            .setPositiveButton(R.string.save) { _, _ ->
                io.launch {
                    try {
                        val db = AppDb.get(ctx.applicationContext)
                        db.bookmarkDao().delete(b)
                        db.bookmarkDao().insert(b.copy(
                            title = titleEdit.text.toString().trim().ifEmpty { b.title },
                            url = urlEdit.text.toString().trim().ifEmpty { b.url }))
                    } catch (_: Exception) {}
                    withContext(Dispatchers.Main) { reload() }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun moveDialog(b: BookmarkEntity) {
        val ctx = context ?: return
        val names = mutableListOf(getString(R.string.bm_folder_root))
        val ids = mutableListOf(0L)
        for (f in folders) { names.add(f.name); ids.add(f.id) }
        MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.bm_move_to)
            .setItems(names.toTypedArray()) { _, which ->
                io.launch {
                    try {
                        val db = AppDb.get(ctx.applicationContext)
                        db.bookmarkDao().delete(b)
                        db.bookmarkDao().insert(b.copy(folderId = ids[which]))
                    } catch (_: Exception) {}
                    withContext(Dispatchers.Main) { reload() }
                }
            }
            .show()
    }

    private fun newFolderDialog() {
        val ctx = context ?: return
        val nameEdit = PanelUi.dialogField(ctx, getString(R.string.bm_folder_name))
        MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.bm_new_folder)
            .setView(PanelUi.fieldWrap(nameEdit))
            .setPositiveButton(R.string.save) { _, _ ->
                val n = nameEdit.text.toString().trim()
                if (n.isNotEmpty()) io.launch {
                    try { AppDb.get(ctx.applicationContext).bookmarkFolderDao().insert(BookmarkFolderEntity(name = n)) } catch (_: Exception) {}
                    withContext(Dispatchers.Main) { reload() }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun renameFolderDialog(f: BookmarkFolderEntity) {
        val ctx = context ?: return
        val nameEdit = PanelUi.dialogField(ctx, getString(R.string.bm_folder_name), f.name)
        MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.bm_rename)
            .setView(PanelUi.fieldWrap(nameEdit))
            .setPositiveButton(R.string.save) { _, _ ->
                val n = nameEdit.text.toString().trim()
                if (n.isNotEmpty()) io.launch {
                    try {
                        val db = AppDb.get(ctx.applicationContext)
                        db.bookmarkFolderDao().delete(f)
                        db.bookmarkFolderDao().insert(f.copy(name = n))
                    } catch (_: Exception) {}
                    withContext(Dispatchers.Main) { reload() }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // ---------------- HTML import / export (Netscape format) ----------------

    private fun pickImport() {
        val i = Intent(Intent.ACTION_GET_CONTENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "text/html"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("text/html", "application/xhtml+xml", "text/plain"))
        }
        try { startActivityForResult(i, PanelsActivity.RC_IMPORT) } catch (_: Exception) {}
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == PanelsActivity.RC_IMPORT && resultCode == android.app.Activity.RESULT_OK) {
            data?.data?.let { importHtml(it) }
        }
    }

    private fun importHtml(uri: Uri) {
        val ctx = context ?: return
        io.launch {
            val parsed = try {
                val text = ctx.contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
                parseNetscapeBookmarks(text)
            } catch (_: Exception) { emptyList<BookmarkEntity>() }
            val db = AppDb.get(ctx.applicationContext)
            for (b in parsed) {
                try { db.bookmarkDao().insert(b) } catch (_: Exception) {}
            }
            withContext(Dispatchers.Main) {
                Toast.makeText(ctx, getString(R.string.bm_imported, parsed.size), Toast.LENGTH_SHORT).show()
                reload()
            }
        }
    }

    private fun exportHtml() {
        val ctx = context ?: return
        io.launch {
            val db = AppDb.get(ctx.applicationContext)
            val all = try { db.bookmarkDao().allOnce() } catch (_: Exception) { emptyList() }
            val sb = StringBuilder()
            sb.append("<!DOCTYPE NETSCAPE-Bookmark-file-1>\n<!-- Zephyr export -->\n")
            sb.append("<META HTTP-EQUIV=\"Content-Type\" CONTENT=\"text/html; charset=UTF-8\">\n")
            sb.append("<TITLE>Bookmarks</TITLE>\n<H1>Bookmarks</H1>\n<DL><p>\n")
            for (b in all) {
                val t = escapeHtml(b.title)
                val u = escapeHtml(b.url)
                val dt = b.createdAt / 1000
                sb.append("    <DT><A HREF=\"$u\" ADD_DATE=\"$dt\">$t</A>\n")
            }
            sb.append("</DL><p>\n")
            val dir = File(ctx.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS) ?: ctx.filesDir, "export").apply { mkdirs() }
            val f = File(dir, "zephyr-bookmarks.html")
            f.writeText(sb.toString())
            withContext(Dispatchers.Main) {
                Toast.makeText(ctx, getString(R.string.bm_exported, f.name), Toast.LENGTH_LONG).show()
                // share it right away
                val uri = androidx.core.content.FileProvider.getUriForFile(ctx, ctx.packageName + ".fileprovider", f)
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "text/html"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                try { startActivity(Intent.createChooser(send, getString(R.string.bm_export_html))) } catch (_: Exception) {}
            }
        }
    }

    private fun escapeHtml(s: String): String = s
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private fun parseNetscapeBookmarks(text: String): List<BookmarkEntity> {
        val out = mutableListOf<BookmarkEntity>()
        // match <A HREF="..." ...>title</A>
        val re = Regex("<a\\s+[^>]*href\\s*=\\s*\"([^\"]+)\"[^>]*>(.*?)</a>", RegexOption.IGNORE_CASE)
        for (m in re.findAll(text)) {
            val url = m.groupValues[1]
            val title = m.groupValues[2].replace(Regex("<[^>]+>"), "").trim()
            if (url.startsWith("http")) out.add(BookmarkEntity(title = title.ifEmpty { url }, url = url))
            if (out.size > 2000) break
        }
        return out
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()
}
