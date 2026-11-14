package com.zephyr.browser.panels

import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ScrollView
import androidx.fragment.app.Fragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.zephyr.browser.R
import com.zephyr.browser.db.AppDb
import com.zephyr.browser.db.AutofillEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first
import org.json.JSONObject

/** Autofill profiles panel: label + JSON fields CRUD. */
class AutofillPanelFragment : Fragment() {

    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var items: List<AutofillEntity> = emptyList()
    private lateinit var list: LinearLayout

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        val ctx = requireContext()
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT)
        }
        root.addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(dp(8), dp(4), dp(8), 0)
            addView(android.widget.ImageButton(ctx).apply {
                setImageResource(R.drawable.ic_add)
                background = PanelUi.ripple(ctx)
                setPadding(dp(10), dp(10), dp(10), dp(10))
                layoutParams = LinearLayout.LayoutParams(dp(42), dp(42))
                imageTintList = android.content.res.ColorStateList.valueOf(
                    PanelUi.themedColor(ctx, R.attr.colorOnSurfaceVariant))
                contentDescription = getString(R.string.af_new_profile)
                setOnClickListener { editDialog(null) }
            })
        })
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

    private fun reload() {
        val ctx = context ?: return
        io.launch {
            items = try { AppDb.get(ctx.applicationContext).autofillDao().all().first() } catch (_: Exception) { emptyList() }
            withContext(Dispatchers.Main) { rebuild() }
        }
    }

    private fun rebuild() {
        val ctx = context ?: return
        list.removeAllViews()

        // v1.5.1: explains HOW profile autofill works (user question: "как работает
        // автозаполнение?") — profiles are offered when an identity form is detected.
        list.addView(PanelUi.emptyView(ctx, R.string.af_how_it_works))

        if (items.isEmpty()) {
            list.addView(PanelUi.emptyState(ctx, R.drawable.ic_user,
                R.string.empty_autofill_title, R.string.empty_autofill_hint))
            return
        }
        for (a in items) {
            val summary = try {
                val o = JSONObject(a.fields)
                o.keys().asSequence().take(3).joinToString(", ")
            } catch (_: Exception) { "" }
            // v1.5.1 FIX: rows were dead (only long-press worked) — a tap now opens
            // the editor directly (same discoverability fix as the bookmarks panel).
            list.addView(PanelUi.cardRow(ctx, iconRes = R.drawable.ic_user, title = a.label, subtitle = summary) {
                editDialog(a)
            }.apply {
                setOnLongClickListener { entryMenu(a); true }
            })
        }
    }

    private fun entryMenu(a: AutofillEntity) {
        val ctx = context ?: return
        val menu = PopupMenu(ctx, list)
        menu.menu.add(0, 1, 0, getString(R.string.bm_edit))
        menu.menu.add(0, 2, 0, getString(R.string.delete))
        menu.setOnMenuItemClickListener { mi ->
            when (mi.itemId) {
                1 -> editDialog(a)
                2 -> io.launch {
                    try { AppDb.get(ctx.applicationContext).autofillDao().delete(a) } catch (_: Exception) {}
                    withContext(Dispatchers.Main) { reload() }
                }
            }
            true
        }
        menu.show()
    }

    /**
     * v1.7.58 (user: «окно топорное — именно пункты с `=`; неужели нельзя
     * обычные поля ввода: имя, email, телефон, адрес, индекс, город, страна»).
     *
     * The profile was edited as raw `key=value` lines, which asked the user to
     * know the storage format to fill in their own address. The shape is the
     * same underneath — a JSON object keyed by field name, which is what the
     * page-filling code looks up — but now each common registration field is a
     * labelled input with the right keyboard.
     *
     * [EXTRA] stays for anything a site asks for that is not on the list, in
     * the old syntax, so nothing that used to be storable stops being storable.
     */
    private val STANDARD: List<Triple<String, Int, Int>> = listOf(
        Triple("name", R.string.af_f_name, android.text.InputType.TYPE_TEXT_VARIATION_PERSON_NAME),
        Triple("first_name", R.string.af_f_first, android.text.InputType.TYPE_TEXT_VARIATION_PERSON_NAME),
        Triple("last_name", R.string.af_f_last, android.text.InputType.TYPE_TEXT_VARIATION_PERSON_NAME),
        Triple("email", R.string.af_f_email, android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS),
        Triple("phone", R.string.af_f_phone, android.text.InputType.TYPE_CLASS_PHONE),
        Triple("address", R.string.af_f_address, android.text.InputType.TYPE_TEXT_VARIATION_POSTAL_ADDRESS),
        Triple("address2", R.string.af_f_address2, android.text.InputType.TYPE_TEXT_VARIATION_POSTAL_ADDRESS),
        Triple("city", R.string.af_f_city, android.text.InputType.TYPE_CLASS_TEXT),
        Triple("postal_code", R.string.af_f_postal, android.text.InputType.TYPE_CLASS_TEXT),
        Triple("state", R.string.af_f_state, android.text.InputType.TYPE_CLASS_TEXT),
        Triple("country", R.string.af_f_country, android.text.InputType.TYPE_CLASS_TEXT),
        Triple("company", R.string.af_f_company, android.text.InputType.TYPE_CLASS_TEXT),
        Triple("birthday", R.string.af_f_birthday, android.text.InputType.TYPE_CLASS_TEXT),
        Triple("username", R.string.af_f_username, android.text.InputType.TYPE_CLASS_TEXT),
        Triple("website", R.string.af_f_website, android.text.InputType.TYPE_TEXT_VARIATION_URI)
    )

    private fun editDialog(existing: AutofillEntity?) {
        val ctx = context ?: return
        val stored = try { JSONObject(existing?.fields ?: "{}") } catch (_: Exception) { JSONObject() }

        val label = PanelUi.dialogField(ctx, getString(R.string.af_label), existing?.label ?: "")
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(4), dp(8), dp(4))
            addView(PanelUi.fieldWrap(label))
        }

        val inputs = LinkedHashMap<String, com.google.android.material.textfield.TextInputEditText>()
        for ((key, labelRes, inputType) in STANDARD) {
            val f = PanelUi.dialogField(ctx, getString(labelRes), stored.optString(key))
            f.inputType = android.text.InputType.TYPE_CLASS_TEXT or inputType
            if (inputType == android.text.InputType.TYPE_CLASS_PHONE) f.inputType = inputType
            inputs[key] = f
            box.addView(PanelUi.fieldWrap(f))
        }

        // whatever the profile already held that is not a standard field
        val extraText = stored.keys().asSequence()
            .filter { k -> STANDARD.none { it.first == k } }
            .joinToString("\n") { k -> "$k=" + stored.optString(k) }
        val extra = PanelUi.dialogField(ctx, getString(R.string.af_f_extra), extraText).apply {
            setSingleLine(false)
            minLines = 2
            gravity = Gravity.TOP
        }
        box.addView(PanelUi.fieldWrap(extra))
        box.addView(android.widget.TextView(ctx).apply {
            setText(R.string.af_extra_hint)
            textSize = 11.5f
            alpha = 0.65f
            setPadding(dp(4), dp(2), dp(4), dp(4))
        })

        MaterialAlertDialogBuilder(ctx)
            .setTitle(if (existing == null) R.string.af_new_profile else R.string.bm_edit)
            .setView(ScrollView(ctx).apply { addView(box) })
            .setPositiveButton(R.string.save) { _, _ ->
                val json = JSONObject()
                for ((key, field) in inputs) {
                    val v = field.text.toString().trim()
                    // empty fields are not stored: a profile full of blanks
                    // would have the filler overwrite real values with nothing
                    if (v.isNotEmpty()) json.put(key, v)
                }
                for (line in extra.text.toString().lines()) {
                    val k = line.substringBefore('=').trim()
                    val v = line.substringAfter('=', "").trim()
                    if (k.isNotEmpty()) json.put(k, v)
                }
                io.launch {
                    try {
                        val db = AppDb.get(ctx.applicationContext)
                        if (existing != null) db.autofillDao().delete(existing)
                        db.autofillDao().insert(AutofillEntity(
                            label = label.text.toString().trim().ifEmpty {
                                json.optString("name").ifBlank { json.optString("email") }.ifBlank { "profile" }
                            },
                            fields = json.toString()
                        ))
                    } catch (_: Exception) {}
                    withContext(Dispatchers.Main) { reload() }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()
}
