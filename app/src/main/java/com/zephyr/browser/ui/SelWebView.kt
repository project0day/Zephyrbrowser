package com.zephyr.browser.ui

import android.content.Context
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.webkit.WebView
import com.zephyr.browser.R

/**
 * v1.7.15: text-selection ActionMode with extra actions (user: «можно ещё
 * добавить в браузере при выделении куска текста дополнительные функции:
 * перевод выбранного итд, разные функции»).
 *
 * A WebView subclass that WRAPS (never replaces) every startActionMode
 * callback and appends four items to the system selection menu:
 * «Перевести» / «Найти» / «Поделиться» / «Вслух» — the system items
 * (Copy/Share/Web Search…) stay untouched and keep working. On a tap the
 * mode finishes, the selection is extracted with [SEL_JS] (a DOM selection
 * or an input/textarea range — inputs need their own branch) and handed to
 * [handler] as (actionId, text); actionId 0 signals "selection came back
 * empty" so native can toast instead of acting on garbage.
 *
 * TabManager.createWebView builds every page WebView through this class;
 * the handler routes into MainActivity.onSelectionAction.
 *
 * v1.7.16 (user: «меню копировать итд появляется высоко, не возле
 * выделенного… и там два пункта перевести — какой дублирующий убери»):
 * - [Wrap] now extends ActionMode.Callback2 — for TYPE_FLOATING modes the
 *   system pins the floating toolbar to the selection rect via
 *   onGetContentRect(); the old plain-Callback wrapper HID the WebView's
 *   own Callback2, so the default rect (top of the view) put the menu
 *   «слишком высоко». The rect is now delegated to the original callback
 *   when it is a Callback2, else the super default.
 * - The system text classifier already contributes its own localized
 *   actions («Перевести», «Поделиться»…), which the blind menu.add used
 *   to DUPLICATE; items are added via addUnique (skip on title collision)
 *   and the ACT_* ids moved to 0x7A15xxxx — far outside the system's
 *   range, so click routing can never cross.
 */
class SelWebView(ctx: Context, private val handler: ((Int, String) -> Unit)?) : WebView(ctx) {
    companion object {
        // v1.7.16: collision-proof ids — the old 1..4 could collide with
        // system text-classifier menu item ids; 0x7A15xxxx is far outside
        // any range the framework uses.
        const val ACT_TRANSLATE = 0x7A150001
        const val ACT_SEARCH = 0x7A150002
        const val ACT_SHARE = 0x7A150003
        const val ACT_TTS = 0x7A150004
        /** v1.7.48: hand the selection straight to the assistant. */
        const val ACT_AI = 0x7A150005
        const val ACT_AI_EXPLAIN = 0x7A150006

        /** selection extraction JS: selection OR input/textarea range
         *  (inputs need their own branch — window.getSelection() is empty
         *  while an EditText-like field holds the real range). */
        private const val SEL_JS = "(function(){try{var s=window.getSelection?String(window.getSelection()):'';if(s&&s.length)return s;var a=document.activeElement;if(a&&(a.tagName==='INPUT'||a.tagName==='TEXTAREA')&&a.selectionStart!=null&&a.selectionEnd!=null){return String(a.value).substring(a.selectionStart,a.selectionEnd);}return '';}catch(e){return ''}})()"
    }

    private inner class Wrap(private val orig: ActionMode.Callback?) : ActionMode.Callback2() {
        override fun onCreateActionMode(mode: ActionMode?, menu: Menu?): Boolean {
            val r = orig?.onCreateActionMode(mode, menu) ?: true
            try {
                // v1.7.16 (user: «два пункта перевести»): the system text
                // classifier already contributes its own localized actions
                // («Перевести», «Поделиться»…) — skip any of ours whose title
                // collides, and use IDs far outside the system's range so
                // routing can never cross.
                fun addUnique(id: Int, titleRes: Int) {
                    if (menu == null) return
                    val title = try { context.getString(titleRes) } catch (_: Exception) { return }
                    val dup = (0 until menu.size()).any { i ->
                        menu.getItem(i)?.title?.toString()?.trim()?.equals(title.trim(), ignoreCase = true) == true
                    }
                    if (!dup) menu.add(0, id, 100 + id, title)
                }
                addUnique(ACT_TRANSLATE, R.string.sel_translate)
                addUnique(ACT_SEARCH, R.string.sel_search)
                addUnique(ACT_SHARE, R.string.sel_share)
                addUnique(ACT_TTS, R.string.sel_tts)
                // v1.7.48 (user: «быстрые команды на выделенный текст»): the
                // bridge between the page and the assistant that was missing
                addUnique(ACT_AI_EXPLAIN, R.string.sel_explain_ai)
                addUnique(ACT_AI, R.string.sel_ask_ai)
            } catch (_: Exception) {}
            return r
        }
        override fun onPrepareActionMode(mode: ActionMode?, menu: Menu?): Boolean =
            orig?.onPrepareActionMode(mode, menu) ?: true
        override fun onActionItemClicked(mode: ActionMode?, item: MenuItem?): Boolean {
            val id = item?.itemId ?: return orig?.onActionItemClicked(mode, item) ?: false
            if (id == ACT_TRANSLATE || id == ACT_SEARCH || id == ACT_SHARE || id == ACT_TTS ||
                id == ACT_AI || id == ACT_AI_EXPLAIN
            ) {
                try { mode?.finish() } catch (_: Exception) {}
                evaluateJavascript(SEL_JS) { v -> deliver(id, v) }
                return true
            }
            return orig?.onActionItemClicked(mode, item) ?: false
        }
        override fun onDestroyActionMode(mode: ActionMode?) { orig?.onDestroyActionMode(mode) }
        // v1.7.16 (user: «меню появляется высоко, не возле выделенного»): the
        // floating toolbar pins to the selection rect via Callback2 — delegate
        // to the WebView's own callback when it is one, else the default rect.
        override fun onGetContentRect(mode: ActionMode?, view: View?, outRect: android.graphics.Rect?) {
            val o = orig
            if (o is ActionMode.Callback2 && outRect != null) o.onGetContentRect(mode, view, outRect)
            else super.onGetContentRect(mode, view, outRect)
        }
    }

    private fun deliver(id: Int, raw: String?) {
        val text = decodeJsString(raw).trim()
        if (text.isEmpty()) { post { handler?.invoke(0, "") }; return }   // 0 = empty signal
        post { handler?.invoke(id, text) }
    }

    /** evaluateJavascript returns a JSON-encoded string (or "null") — decode with org.json. */
    private fun decodeJsString(raw: String?): String = try {
        if (raw.isNullOrEmpty() || raw == "null") ""
        else org.json.JSONTokener(raw).nextValue() as? String ?: ""
    } catch (_: Exception) { raw?.removeSurrounding("\"") ?: "" }

    override fun startActionMode(callback: ActionMode.Callback?, type: Int): ActionMode? =
        super.startActionMode(Wrap(callback), type)

    @Deprecated("Deprecated in Java")
    @Suppress("DEPRECATION")
    override fun startActionMode(callback: ActionMode.Callback?): ActionMode? =
        super.startActionMode(Wrap(callback))
}
