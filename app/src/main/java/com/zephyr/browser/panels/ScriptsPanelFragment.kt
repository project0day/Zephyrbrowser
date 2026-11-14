package com.zephyr.browser.panels

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.zephyr.browser.R
import com.zephyr.browser.db.AppDb
import com.zephyr.browser.db.ScriptEntity
import com.zephyr.browser.userscripts.ScriptInjector
import com.zephyr.browser.userscripts.UserScriptMeta
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * User scripts panel, v1.7.2 rewrite (Via-browser style). THE key change: scripts
 * are no longer just stored — they are parsed (userscripts.UserScriptMeta),
 * matched per-page and INJECTED by ScriptInjector from MainActivity.onPageStarted
 * and onPageFinished. This panel manages them:
 *  - list rows: name from the metadata header (fallback: table name), domain
 *    pattern summary, run-at badge, enable toggle, an overflow menu;
 *  - editor dialog: name + legacy domain restriction + a big monospace code
 *    field (also reachable through a row tap);
 *  - import of .user.js files with the Tampermonkey metadata header parsed and
 *    the name prefilled;
 *  - empty state with a format hint and a one-tap example script;
 *  - every mutation calls ScriptInjector.refresh() so the injection cache
 *    picks it up immediately.
 */
class ScriptsPanelFragment : Fragment() {

    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var items: List<ScriptEntity> = emptyList()
    private lateinit var list: LinearLayout

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val ctx = requireContext()
        val root = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }

        // top actions: + Add script (premium filled pill), import .user.js, export JSON, catalog
        // v1.7.4 (user: «скругли углы кнопки Добавить скрипт»): the plain android
        // Button became an accent-filled rounded pill with a leading + icon.
        val actions = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(10), dp(6), dp(2))
        }
        val addBtn = LinearLayout(ctx).apply {
            gravity = Gravity.CENTER
            setPadding(dp(14), dp(10), dp(14), dp(10))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            background = GradientDrawable().apply {
                setColor(PanelUi.accent(ctx))
                cornerRadius = dp(16).toFloat()
            }
            isClickable = true
            isFocusable = true
            foreground = PanelUi.ripple(ctx)
            clipToOutline = true
            addView(ImageView(ctx).apply {
                setImageResource(R.drawable.ic_add)
                layoutParams = LinearLayout.LayoutParams(dp(16), dp(16))
                imageTintList = ColorStateList.valueOf(0xFFFFFFFF.toInt())
            })
            addView(TextView(ctx).apply {
                text = getString(R.string.scr_add)
                textSize = 13.5f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(0xFFFFFFFF.toInt())
                setPadding(dp(6), 0, 0, 0)
                maxLines = 1
            })
            setOnClickListener { showAddChooser() }
        }
        actions.addView(addBtn)
        fun ib(icon: Int, desc: Int, onClick: () -> Unit) = ImageButton(ctx).apply {
            setImageResource(icon)
            background = PanelUi.ripple(ctx)
            setPadding(dp(10), dp(10), dp(10), dp(10))
            layoutParams = LinearLayout.LayoutParams(dp(46), dp(46))
            imageTintList = ColorStateList.valueOf(
                PanelUi.themedColor(ctx, R.attr.colorOnSurfaceVariant))
            contentDescription = getString(desc)
            setOnClickListener { onClick() }
        }
        actions.addView(ib(R.drawable.ic_upload, R.string.scr_import_us) { pickImport() })
        actions.addView(ib(R.drawable.ic_download, R.string.scr_export) { exportJson() })
        // v1.7.5 (user: «убери кнопку windows с предустановкой — работает топорно»):
        // the preset CATALOG button is gone. "Добавить скрипт" now opens a chooser:
        // new / from file / BY LINK.
        root.addView(actions)

        // v1.7.8 (user: «сделай пояснения — можно ли скрипты параллельно запускать
        // с блокировщиком или это отдельно»): HOW-IT-WORKS banner, dismissable.
        PanelUi.hintBanner(ctx, "scripts_hint", R.drawable.ic_script,
            ctx.getString(R.string.scr_hint_title), ctx.getString(R.string.scr_hint_body))
            ?.let { root.addView(it) }

        val scroll = ScrollView(ctx)
        list = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(list)
        root.addView(scroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        return root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        reload()
    }


    // ------------------------------------------------------------------
    // v1.7.5: ADD BY URL (user: «пресеты убери — сделай возможность добавить
    // по ссылке»). The built-in preset catalog was REMOVED entirely; the Add
    // button offers: blank editor / .user.js file / download from a URL.
    // ------------------------------------------------------------------

    private fun showAddChooser() {
        val ctx = context ?: return
        val options = arrayOf(
            getString(R.string.scr_add_new),
            getString(R.string.scr_add_file),
            getString(R.string.scr_add_url))
        val icons = intArrayOf(R.drawable.ic_edit, R.drawable.ic_upload, R.drawable.ic_globe)
        val actions = arrayOf(
            { editDialog(null) },
            { pickImport() },
            { addByUrlDialog() })
        val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val dialog = MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.scr_add)
            .setView(col)
            .setNegativeButton(R.string.cancel, null)
            .create()
        for ((idx, label) in options.withIndex()) {
            col.addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(18), dp(14), dp(18), dp(14))
                isClickable = true
                foreground = PanelUi.ripple(ctx)
                setOnClickListener {
                    dialog.dismiss()
                    actions[idx]()
                }
                addView(android.widget.ImageView(ctx).apply {
                    setImageResource(icons[idx])
                    layoutParams = LinearLayout.LayoutParams(dp(20), dp(20))
                    imageTintList = ColorStateList.valueOf(PanelUi.accent(ctx))
                })
                addView(TextView(ctx).apply {
                    text = label
                    textSize = 15f
                    setPadding(dp(14), 0, 0, 0)
                    setTextColor(PanelUi.themedColor(ctx, R.attr.colorOnSurface))
                })
            })
        }
        dialog.show()
    }

    /** v1.7.5: download a .user.js from a URL and open it in the review editor. */
    private fun addByUrlDialog() {
        val ctx = context ?: return
        val input = PanelUi.dialogField(ctx, getString(R.string.scr_add_url_hint), "https://").apply {
            setSingleLine(true)
        }
        MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.scr_add_url)
            .setView(PanelUi.fieldWrap(input))
            .setPositiveButton(R.string.scr_add_url_load) { _, _ ->
                val url = input.text.toString().trim()
                if (url.startsWith("http")) fetchScriptFromUrl(url)
                else Toast.makeText(ctx, R.string.scr_add_url_bad, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun fetchScriptFromUrl(url: String) {
        val ctx = context ?: return
        Toast.makeText(ctx, R.string.scr_add_url_loading, Toast.LENGTH_SHORT).show()
        io.launch {
            var text = ""
            var ok = false
            try {
                val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                conn.connectTimeout = 15000
                conn.readTimeout = 20000
                conn.instanceFollowRedirects = true
                conn.setRequestProperty("User-Agent",
                    "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 Chrome/124 Mobile Safari/537.36")
                if (conn.responseCode in 200..299) {
                    text = conn.inputStream.bufferedReader(Charsets.UTF_8).use {
                        it.readText().take(512 * 1024)   // sane cap: scripts are small
                    }
                    ok = text.isNotBlank()
                }
                try { conn.disconnect() } catch (_: Exception) {}
            } catch (_: Exception) {}
            withContext(Dispatchers.Main) {
                if (!isAdded) return@withContext
                if (!ok) {
                    Toast.makeText(ctx, R.string.scr_add_url_failed, Toast.LENGTH_SHORT).show()
                } else {
                    val meta = try { UserScriptMeta.parse(text) } catch (_: Exception) { null }
                    val nameHint = meta?.name?.takeIf { it.isNotBlank() }
                        ?: url.substringAfterLast('/').substringBefore('.')
                    editDialog(null, presetCode = text, presetName = nameHint)
                }
            }
        }
    }

    private fun reload() {
        val ctx = context ?: return
        ScriptInjector.ensureLoaded(ctx.applicationContext)
        io.launch {
            // ALL scripts (not only enabled): a disabled script must stay visible
            // so it can be re-enabled — the v1.7.1 panel dropped it from the list.
            items = try { AppDb.get(ctx.applicationContext).scriptDao().all().first() }
                catch (_: Exception) { emptyList() }
            withContext(Dispatchers.Main) { rebuild() }
        }
    }

    private fun rebuild() {
        val ctx = context ?: return
        list.removeAllViews()
        if (items.isEmpty()) {
            list.addView(emptyState(ctx))
            return
        }
        for (s in items) list.addView(scriptRow(s))
    }

    // ------------------------------------------------------------------
    // rows
    // ------------------------------------------------------------------

    private fun scriptRow(s: ScriptEntity): View {
        val ctx = requireContext()
        val meta = try { UserScriptMeta.parse(s.code) } catch (_: Exception) { null }
        val title = meta?.name?.takeIf { it.isNotBlank() } ?: s.name.ifEmpty { "script" }
        val row = PanelUi.cardRow(
            ctx,
            iconRes = R.drawable.ic_script,
            title = title,
            subtitle = summaryFor(s, meta),
            trailing = trailingViews(s, meta),
            onClick = { editDialog(s) }
        )
        row.setOnLongClickListener {
            entryMenu(row, s)
            true
        }
        return row
    }

    /** run-at badge + enable toggle + overflow menu, trailing in the row. */
    private fun trailingViews(s: ScriptEntity, meta: UserScriptMeta?): View {
        val ctx = requireContext()
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        box.addView(TextView(ctx).apply {
            text = shortRunAt(meta?.runAt ?: UserScriptMeta.RUN_AT_IDLE)
            textSize = 10f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(PanelUi.themedColor(ctx, R.attr.colorOnSurfaceVariant))
            background = GradientDrawable().apply {
                cornerRadius = dp(8).toFloat()
                setColor(PanelUi.themedColor(ctx, R.attr.colorSurfaceVariant))
            }
            setPadding(dp(7), dp(2), dp(7), dp(2))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = dp(8) }
        })
        box.addView(Switch(ctx).apply {
            isChecked = s.enabled
            // the row itself opens the editor, so the switch must handle its own taps
            isClickable = true
            isFocusable = true
            setOnCheckedChangeListener { _, checked ->
                val appCtx = context?.applicationContext ?: return@setOnCheckedChangeListener
                io.launch {
                    try { AppDb.get(appCtx).scriptDao().setEnabled(s.id, checked) } catch (_: Exception) {}
                    ScriptInjector.refresh()
                }
            }
        })
        box.addView(ImageButton(ctx).apply {
            setImageResource(R.drawable.ic_more_vert)
            background = PanelUi.ripple(ctx)
            setPadding(dp(7), dp(7), dp(7), dp(7))
            imageTintList = ColorStateList.valueOf(
                PanelUi.themedColor(ctx, R.attr.colorOnSurfaceVariant))
            contentDescription = getString(R.string.scr_more)
            setOnClickListener { entryMenu(this, s) }
        })
        return box
    }

    /** First header pattern (or the legacy domains column) + a "+N" counter. */
    private fun summaryFor(s: ScriptEntity, meta: UserScriptMeta?): String {
        val pats = (meta?.matches ?: emptyList()) + (meta?.includes ?: emptyList())
        val shown = if (pats.isNotEmpty()) pats else listOf(s.domains)
        val first = shown.firstOrNull()?.takeIf { it.isNotBlank() } ?: "*"
        val extra = if (shown.size > 1) " +" + (shown.size - 1) else ""
        return first.take(36) + extra
    }

    /** Localized run-at badge (document-start / document-end / document-idle). */
    private fun shortRunAt(runAt: String): String = when (runAt) {
        UserScriptMeta.RUN_AT_START -> getString(R.string.scr_runat_start)
        UserScriptMeta.RUN_AT_END -> getString(R.string.scr_runat_end)
        else -> getString(R.string.scr_runat_idle)
    }

    private fun entryMenu(anchor: View, s: ScriptEntity) {
        val ctx = context ?: return
        val menu = PopupMenu(ctx, anchor)
        menu.menu.add(0, 1, 0, getString(R.string.bm_edit))
        menu.menu.add(0, 2, 0, getString(R.string.delete))
        menu.setOnMenuItemClickListener { mi ->
            when (mi.itemId) {
                1 -> editDialog(s)
                2 -> deleteScript(s)
            }
            true
        }
        menu.show()
    }

    private fun deleteScript(s: ScriptEntity) {
        val ctx = context ?: return
        MaterialAlertDialogBuilder(ctx)
            .setMessage(R.string.scr_delete_confirm)
            .setPositiveButton(R.string.delete) { _, _ ->
                val appCtx = ctx.applicationContext
                io.launch {
                    try { AppDb.get(appCtx).scriptDao().delete(s) } catch (_: Exception) {}
                    ScriptInjector.refresh()
                    withContext(Dispatchers.Main) { reload() }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // ------------------------------------------------------------------
    // empty state
    // ------------------------------------------------------------------

    private fun emptyState(ctx: android.content.Context): View {
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24), dp(56), dp(24), dp(40))
        }
        box.addView(ImageView(ctx).apply {
            setImageResource(R.drawable.ic_script)
            imageTintList = ColorStateList.valueOf(
                PanelUi.themedColor(ctx, R.attr.colorOnSurfaceVariant))
            layoutParams = LinearLayout.LayoutParams(dp(64), dp(64)).apply { bottomMargin = dp(14) }
        })
        box.addView(TextView(ctx).apply {
            setText(R.string.scr_empty)
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(PanelUi.themedColor(ctx, R.attr.colorOnSurface))
        })
        box.addView(TextView(ctx).apply {
            setText(R.string.scr_hint)
            textSize = 13f
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, dp(18))
            setTextColor(PanelUi.themedColor(ctx, R.attr.colorOnSurfaceVariant))
        })
        box.addView(Button(ctx).apply {
            text = getString(R.string.scr_example_btn)
            isAllCaps = false
            setOnClickListener { editDialog(null, presetCode = EXAMPLE_SCRIPT, presetName = null) }
        })
        return box
    }

    // ------------------------------------------------------------------
    // editor
    // ------------------------------------------------------------------

    /**
     * Editor dialog. [presetCode] and [presetName] prefill the form (used by the
     * .user.js import and the example button); [existing] is the row being edited.
     */
    private fun editDialog(existing: ScriptEntity?, presetCode: String? = null, presetName: String? = null) {
        val ctx = context ?: return
        val name = PanelUi.dialogField(ctx, getString(R.string.scr_name),
            existing?.name ?: presetName ?: "")
        val domains = PanelUi.dialogField(ctx, getString(R.string.scr_domains),
            existing?.domains ?: "*")
        val code = EditText(ctx).apply {
            hint = getString(R.string.scr_code)
            typeface = Typeface.MONOSPACE
            setTextSize(12f)
            setSingleLine(false)
            gravity = Gravity.TOP or Gravity.START
            setMinLines(10)
            setMinHeight(dp(320))
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = PanelUi.searchFieldBg(ctx)
            setText(existing?.code ?: presetCode ?: "")
        }
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(4), dp(8), dp(4))
            addView(PanelUi.fieldWrap(name))
            addView(PanelUi.fieldWrap(domains))
        }
        box.addView(code, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(10) })

        MaterialAlertDialogBuilder(ctx)
            .setTitle(if (existing == null) R.string.scr_new else R.string.bm_edit)
            .setView(ScrollView(ctx).apply { addView(box) })
            .setPositiveButton(R.string.save) { _, _ ->
                saveScript(existing, name.text.toString(), domains.text.toString(), code.text.toString())
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun saveScript(existing: ScriptEntity?, name: String, domains: String, code: String) {
        val ctx = context ?: return
        // name priority: typed name, then the header name, then a fallback
        val meta = try { UserScriptMeta.parse(code) } catch (_: Exception) { null }
        val n = name.trim().ifEmpty {
            meta?.name?.takeIf { it.isNotBlank() } ?: "script"
        }
        val d = domains.trim().ifEmpty { "*" }
        val position = existing?.position ?: (items.maxOfOrNull { it.position }?.plus(1) ?: 0)
        io.launch {
            try {
                val db = AppDb.get(ctx.applicationContext)
                if (existing != null) db.scriptDao().delete(existing)
                db.scriptDao().insert(ScriptEntity(
                    name = n, code = code, domains = d,
                    enabled = existing?.enabled ?: true,   // edits keep the toggle state
                    position = position))
                ScriptInjector.refresh()
            } catch (_: Exception) {}
            withContext(Dispatchers.Main) {
                Toast.makeText(ctx, R.string.saved, Toast.LENGTH_SHORT).show()
                reload()
            }
        }
    }

    // ------------------------------------------------------------------
    // import + export
    // ------------------------------------------------------------------

    private fun pickImport() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf(
                "text/javascript", "application/javascript", "text/plain", "application/octet-stream"))
        }
        try {
            startActivityForResult(i, 701)
        } catch (_: Exception) {
            // very old pickers without the document provider
            try {
                startActivityForResult(Intent(Intent.ACTION_GET_CONTENT).apply { type = "*/*" }, 701)
            } catch (_: Exception) {}
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 701 && resultCode == android.app.Activity.RESULT_OK) {
            data?.data?.let { importUserJs(it) }
        }
    }

    private fun importUserJs(uri: Uri) {
        val ctx = context ?: return
        io.launch {
            var text = ""
            var ok = true
            try {
                text = ctx.contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)
                    ?.use { it.readText() } ?: ""
            } catch (_: Exception) { ok = false }
            withContext(Dispatchers.Main) {
                if (!ok || text.isBlank()) {
                    Toast.makeText(ctx, R.string.scr_import_failed, Toast.LENGTH_SHORT).show()
                } else {
                    // parse the metadata header and prefill the editor — the user
                    // reviews the code and presses Save
                    val meta = try { UserScriptMeta.parse(text) } catch (_: Exception) { null }
                    editDialog(null,
                        presetCode = text,
                        presetName = meta?.name?.takeIf { it.isNotBlank() } ?: uri.lastPathSegment)
                }
            }
        }
    }

    private fun exportJson() {
        val ctx = context ?: return
        io.launch {
            // v1.7.2: export ALL scripts (the v1.7.1 export silently skipped disabled ones)
            val all = try { AppDb.get(ctx.applicationContext).scriptDao().all().first() }
                catch (_: Exception) { emptyList() }
            val arr = JSONArray()
            for (s in all) arr.put(JSONObject().apply {
                put("name", s.name); put("code", s.code)
                put("domains", s.domains); put("enabled", s.enabled)
            })
            val dir = File(ctx.getExternalFilesDir(android.os.Environment.DIRECTORY_DOCUMENTS)
                ?: ctx.filesDir, "export").apply { mkdirs() }
            val f = File(dir, "zephyr-scripts.json")
            f.writeText(arr.toString(2))
            withContext(Dispatchers.Main) {
                Toast.makeText(ctx, getString(R.string.saved) + ": " + f.name, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

    companion object {
        /**
         * Example userscript inserted by the empty-state button — a complete,
         * VALID Tampermonkey-style metadata header plus a trivial body. The
         * match rule is deliberately example.com: deterministic to verify (open
         * example.com — the background turns dark) while never touching a real
         * site by accident. Exactly the minimal format the engine expects.
         */
        private const val EXAMPLE_SCRIPT = """
// ==UserScript==
// @name         Тёмный фон для example.com
// @match        https://example.com/*
// @run-at       document-end
// @version      1.0
// @description  Пример: тёмный фон на сайте example.com
// ==/UserScript==
(function(){
  document.body.style.background = '#222';
})();
"""
    }
}
