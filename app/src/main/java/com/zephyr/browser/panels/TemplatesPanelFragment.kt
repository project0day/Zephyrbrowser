package com.zephyr.browser.panels

import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.zephyr.browser.R
import com.zephyr.browser.ai.AiAssistantSheet
import com.zephyr.browser.ai.AiBusiness
import com.zephyr.browser.ai.AiTemplateGroups
import com.zephyr.browser.ai.AiTemplates
import com.zephyr.browser.ai.AiTools
import com.zephyr.browser.ai.TemplatePreview

/**
 * v1.7.73 rewrite (user: «я бы хотел видеть визуально миниатюрно, как вкладки у
 * хрома… а тут абсолютно непонятно и ничего нету»).
 *
 * The first version was a list of names, and the user was right that it fails
 * the moment there is more than one template: a name does not tell you what the
 * document looks like, and a folder called "Коммерция" does not tell you which
 * of the two quotes inside is the one for stonework.
 *
 * So the screen became a board of THUMBNAILS grouped into coloured sections —
 * the same reason the tab switcher shows pages and not URLs.
 *
 * The second failure was worse and is fixed here too. Tapping a template used
 * to open the chat with a message listing twenty-eight placeholder names, which
 * asked the user to speak the template's internal language. What the user
 * actually has is an email from a client. So the tap now asks for exactly that,
 * in their words, and the field list goes to the ASSISTANT — which is the party
 * that can turn "поменять дверь на первом и втором этаже, 600 евро" into rows
 * of a quote.
 */
class TemplatesPanelFragment : Fragment() {

    private lateinit var board: LinearLayout
    private var bizSummary: TextView? = null
    private var sigSummary: TextView? = null

    /**
     * Importing an existing form.
     *
     * The picker is opened for any type rather than a filtered list: a user's
     * .docx often arrives with a vendor-specific MIME type, and a filter that
     * hides the file they are looking at is worse than one extra check here.
     */
    private val importPicker = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) importTemplate(uri) }

    private fun importTemplate(uri: android.net.Uri) {
        val ctx = context ?: return
        val display = queryName(uri)
        val ext = display.substringAfterLast('.', "").lowercase()
        val kind = when (ext) {
            "html", "htm" -> AiTemplates.KIND_DOC
            "docx" -> AiTemplates.KIND_DOCX
            "pdf" -> AiTemplates.KIND_PDF
            else -> {
                Toast.makeText(ctx, R.string.tpl_import_bad, Toast.LENGTH_LONG).show()
                return
            }
        }

        val nameField = EditText(ctx).apply {
            setText(display.substringBeforeLast('.', display))
            setSingleLine(true)
        }
        val groups = (AiTemplateGroups.all().map { it.name } + AiTemplates.CATEGORIES)
            .distinctBy { it.lowercase() }
        var group = groups.firstOrNull() ?: AiTemplates.CATEGORIES[0]
        val groupBtn = TextView(ctx).apply {
            text = AiTemplateGroups.get(group).emoji + "  " + group
            textSize = 14f
            setTextColor(PanelUi.accent(ctx))
            setPadding(0, dp(12), 0, dp(4))
            isClickable = true
            setOnClickListener {
                MaterialAlertDialogBuilder(ctx)
                    .setTitle(R.string.tpl_move)
                    .setItems(groups.map { AiTemplateGroups.get(it).emoji + "  " + it }.toTypedArray()) { _, w ->
                        group = groups[w]
                        text = AiTemplateGroups.get(group).emoji + "  " + group
                    }
                    .show()
            }
        }

        MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.tpl_import)
            .setMessage(if (kind == AiTemplates.KIND_DOCX) getString(R.string.tpl_import_hint_docx) else null)
            .setView(LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(22), dp(6), dp(22), dp(6))
                addView(TextView(ctx).apply {
                    setText(R.string.tpl_import_name)
                    textSize = 11.5f
                    alpha = 0.7f
                })
                addView(nameField)
                addView(groupBtn)
            })
            .setPositiveButton(R.string.save) { _, _ ->
                val ok = try {
                    ctx.contentResolver.openInputStream(uri)?.use { input ->
                        AiTemplates.import(
                            nameField.text.toString(), group, kind, input,
                            if (kind == AiTemplates.KIND_DOC) "html" else ext, ""
                        ) != null
                    } ?: false
                } catch (_: Throwable) { false }
                Toast.makeText(
                    ctx,
                    if (ok) getString(R.string.tpl_import_done, nameField.text.toString())
                    else getString(R.string.tpl_import_fail),
                    Toast.LENGTH_LONG
                ).show()
                render()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun queryName(uri: android.net.Uri): String {
        val ctx = context ?: return "template"
        try {
            ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
                val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && c.moveToFirst()) return c.getString(idx) ?: "template"
            }
        } catch (_: Throwable) {}
        return uri.lastPathSegment ?: "template"
    }

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        val ctx = requireContext()
        val root = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }

        // v1.7.95 (F-016): the concept here is the least obvious in the app —
        // a "template" is not a file you open, it is a document the assistant
        // fills in for you. Dismissible, so it costs nothing after the first read.
        PanelUi.hintBanner(ctx, "templates_hint", R.drawable.ic_file,
            ctx.getString(R.string.tpl_hint_title), ctx.getString(R.string.tpl_hint_body))
            ?.let { root.addView(it) }

        // ── my details + signature ────────────────────────────────────────────
        val bizRow = PanelUi.cardRow(
            ctx,
            iconRes = R.drawable.ic_user,
            title = getString(R.string.biz_title),
            subtitle = bizLine(),
            onClick = { editBusiness() }
        ).apply {
            layoutParams = (layoutParams as LinearLayout.LayoutParams).apply {
                setMargins(dp(10), dp(10), dp(10), dp(2))
            }
        }
        bizSummary = (0 until bizRow.childCount)
            .mapNotNull { bizRow.getChildAt(it) as? LinearLayout }
            .firstOrNull { it.orientation == LinearLayout.VERTICAL && it.childCount >= 2 }
            ?.getChildAt(1) as? TextView
        root.addView(bizRow)

        // v1.7.74 (user: «кнопка добавить подпись не работает»). It was the last
        // line of a long scrolling dialog, where it was easy to miss and easy to
        // mis-tap. A signature is used as often as the details themselves, so it
        // gets its own row here instead of hiding inside another screen.
        val sigRow = PanelUi.cardRow(
            ctx,
            iconRes = R.drawable.ic_edit,
            title = getString(R.string.sig_title),
            subtitle = sigLine(),
            onClick = {
                SignaturePad.show(requireActivity()) {
                    // v1.7.75 (user: «подписал, нажал создать — надо выйти и
                    // зайти снова, и тогда „не задана" меняется»). render()
                    // rebuilds the board only; this row is built once, so it
                    // has to be told.
                    sigSummary?.text = sigLine()
                    bizSummary?.text = bizLine()
                }
            }
        ).apply {
            layoutParams = (layoutParams as LinearLayout.LayoutParams).apply {
                setMargins(dp(10), dp(2), dp(10), dp(2))
            }
        }
        sigSummary = (0 until sigRow.childCount)
            .mapNotNull { sigRow.getChildAt(it) as? LinearLayout }
            .firstOrNull { it.orientation == LinearLayout.VERTICAL && it.childCount >= 2 }
            ?.getChildAt(1) as? TextView
        root.addView(sigRow)

        // v1.7.73 (user: «многие уже имеют свои шаблоны и не хотят создавать
        // другие») — adopt an existing form instead of rebuilding it
        root.addView(PanelUi.cardRow(
            ctx,
            iconRes = R.drawable.ic_folder_add,
            title = getString(R.string.tpl_import),
            subtitle = getString(R.string.tpl_import_sub),
            onClick = { importPicker.launch(arrayOf("*/*")) }
        ).apply {
            layoutParams = (layoutParams as LinearLayout.LayoutParams).apply {
                setMargins(dp(10), dp(2), dp(10), dp(2))
            }
        })

        board = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(6), dp(2), dp(6), dp(24))
        }
        root.addView(ScrollView(ctx).apply {
            isFillViewport = true
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
            addView(board)
        })

        render()
        return root
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun sigLine(): String =
        if (AiBusiness.hasSignature(requireContext())) getString(R.string.sig_present)
        else getString(R.string.sig_absent)

    private fun bizLine(): String =
        if (AiBusiness.isEmpty()) getString(R.string.biz_empty)
        else {
            val head = AiBusiness.all().entries.take(2).joinToString(" · ") { it.value }
            val sign = if (AiBusiness.hasSignature(requireContext()))
                "  ✍ " + getString(R.string.sig_present) else ""
            head + sign
        }

    // ---------------- board ----------------

    private fun render() {
        val ctx = context ?: return
        board.removeAllViews()
        val all = AiTemplates.all()

        if (all.isEmpty()) {
            board.addView(PanelUi.emptyState(ctx, R.drawable.ic_file,
                R.string.empty_templates_title, R.string.empty_templates_hint))
            board.addView(TextView(ctx).apply {
                setText(R.string.tpl_empty_how)
                textSize = 13f
                gravity = Gravity.CENTER
                alpha = 0.8f
                setPadding(dp(22), 0, dp(22), dp(20))
                setTextColor(PanelUi.themedColor(ctx, R.attr.colorOnSurfaceVariant))
            })
            return
        }

        for ((folder, items) in all.groupBy { it.category }) {
            board.addView(groupHeader(folder, items.size))
            // two thumbnails per row, like a tab grid
            var row: LinearLayout? = null
            for ((idx, t) in items.withIndex()) {
                if (idx % 2 == 0) {
                    row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
                    board.addView(row)
                }
                row?.addView(tile(t))
            }
            // an odd count would otherwise stretch the last tile to full width
            if (items.size % 2 == 1) row?.addView(View(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
            })
        }
    }

    private fun groupHeader(folder: String, count: Int): View {
        val ctx = requireContext()
        val g = AiTemplateGroups.get(folder)
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(16), dp(10), dp(6))
            isClickable = true
            foreground = PanelUi.ripple(ctx)
            addView(TextView(ctx).apply {
                text = g.emoji
                textSize = 17f
                setPadding(0, 0, dp(8), 0)
            })
            addView(TextView(ctx).apply {
                text = g.name
                textSize = 14f
                typeface = Typeface.DEFAULT_BOLD
                letterSpacing = 0.03f
                setTextColor(g.color)
                layoutParams = LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(TextView(ctx).apply {
                text = count.toString()
                textSize = 12f
                setTextColor(0xFFFFFFFF.toInt())
                gravity = Gravity.CENTER
                setPadding(dp(8), dp(2), dp(8), dp(2))
                background = GradientDrawable().apply {
                    cornerRadius = dp(10).toFloat()
                    setColor(g.color)
                }
            })
            setOnClickListener { editGroup(folder) }
        }
    }

    /** A card the size of a tab thumbnail: the document, then its name. */
    private fun tile(t: AiTemplates.Template): View {
        val ctx = requireContext()
        val g = AiTemplateGroups.get(t.category)

        val thumb = FrameLayout(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(160)
            )
            background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(PanelUi.themedColor(ctx, R.attr.colorSurfaceVariant, 0xFFEFF1F3.toInt()))
            }
            clipToOutline = true
        }
        val image = ImageView(ctx).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
            )
            scaleType = ImageView.ScaleType.CENTER_CROP
            // the top of a document is the part that identifies it
            setPadding(0, 0, 0, 0)
        }
        thumb.addView(image)
        // placeholder until (or unless) the thumbnail renders
        val glyph = TextView(ctx).apply {
            text = if (t.isApp) "💡" else g.emoji
            textSize = 30f
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        thumb.addView(glyph)

        TemplatePreview.cached(t)?.let { image.setImageBitmap(it); glyph.visibility = View.GONE }
            ?: activity?.let { act ->
                TemplatePreview.ensure(act, t) { bmp ->
                    if (isAdded) { image.setImageBitmap(bmp); glyph.visibility = View.GONE }
                }
            }

        return LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                .apply { setMargins(dp(6), dp(4), dp(6), dp(10)) }
            isClickable = true
            addView(thumb)
            addView(TextView(ctx).apply {
                text = t.name
                textSize = 13.5f
                typeface = Typeface.DEFAULT_BOLD
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(dp(3), dp(7), dp(3), 0)
                setTextColor(PanelUi.themedColor(ctx, R.attr.colorOnSurface))
            })
            addView(TextView(ctx).apply {
                text = if (t.note.isNotBlank()) t.note
                else if (t.isApp) getString(R.string.tpl_kind_app) else getString(R.string.tpl_kind_doc)
                textSize = 11.5f
                maxLines = 2
                setPadding(dp(3), dp(1), dp(3), 0)
                setTextColor(PanelUi.themedColor(ctx, R.attr.colorOnSurfaceVariant))
            })
            setOnClickListener { use(t) }
            setOnLongClickListener { menu(t); true }
        }
    }

    // ---------------- running a template ----------------

    /**
     * Ask the one question the user can actually answer.
     *
     * The old prompt handed them the template's field names. But what a person
     * has in front of them is an email or a phone call — so the box asks for
     * that, and the assistant does the translating. The template's fields go
     * into the request as a note to the assistant, not as a demand on the user.
     */
    private fun use(t: AiTemplates.Template) {
        val ctx = context ?: return
        val main = AiTools.host
        if (main == null) {
            Toast.makeText(ctx, R.string.wf_no_browser, Toast.LENGTH_SHORT).show()
            return
        }
        if (t.isApp) { runApp(t); return }

        val field = EditText(ctx).apply {
            setHint(R.string.tpl_ask_hint)
            setSingleLine(false)
            minLines = 4
            gravity = Gravity.TOP
            textSize = 14.5f
        }
        val sign = android.widget.CheckBox(ctx).apply {
            setText(R.string.tpl_ask_sign)
            isChecked = AiBusiness.hasSignature(ctx)
            isEnabled = AiBusiness.hasSignature(ctx)
        }
        MaterialAlertDialogBuilder(ctx)
            .setTitle(getString(R.string.tpl_ask_title, t.name))
            .setMessage(R.string.tpl_ask_sub)
            .setView(LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(22), dp(6), dp(22), dp(6))
                addView(field)
                addView(sign)
            })
            .setPositiveButton(R.string.tpl_ask_go) { _, _ ->
                val said = field.text.toString().trim()
                // v1.7.74 (user: «и плюс тот шаблон тоже отправился и появился в
                // бабле»): the field list was moved off the user and into the
                // request — but the request IS the message they see. So it says
                // only what a person would say. The assistant learns the fields
                // from use_template, which already reports what is missing.
                val request = buildString {
                    append("Подготовь документ по шаблону «").append(t.name).append("». ")
                    // the tool needs the flag; the sentence is what the user sees
                    if (sign.isChecked) append("Вызови use_template с sign: true. ")
                    append("\n\n").append(said.ifBlank { "—" })
                }
                activity?.finish()
                AiAssistantSheet.askAbout(main, request, ask = false)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun runApp(t: AiTemplates.Template) {
        val main = AiTools.host ?: return
        activity?.finish()
        AiAssistantSheet.askAbout(
            main, "Открой мини-приложение «" + t.name + "» через use_template.", ask = false
        )
    }

    private fun menu(t: AiTemplates.Template) {
        val ctx = context ?: return
        val actions = ArrayList<Pair<String, () -> Unit>>()
        actions.add(getString(R.string.tpl_use) to { use(t) })
        actions.add(getString(R.string.tpl_rename) to { rename(t) })
        actions.add(getString(R.string.tpl_move) to { move(t) })
        actions.add(getString(R.string.tpl_refresh) to {
            TemplatePreview.invalidate(t); render()
        })
        actions.add(getString(R.string.ws_view) to { showSource(t) })
        actions.add(getString(R.string.delete) to { AiTemplates.remove(t.id); render() })
        MaterialAlertDialogBuilder(ctx)
            .setTitle(t.name)
            .setItems(actions.map { it.first }.toTypedArray()) { _, w -> actions[w].second() }
            .show()
    }

    private fun rename(t: AiTemplates.Template) {
        val ctx = context ?: return
        val field = EditText(ctx).apply { setText(t.name); setSingleLine(true) }
        MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.tpl_rename)
            .setView(LinearLayout(ctx).apply {
                setPadding(dp(22), dp(8), dp(22), dp(8))
                addView(field, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            })
            .setPositiveButton(R.string.save) { _, _ ->
                val html = try { t.file.readText() } catch (_: Throwable) { return@setPositiveButton }
                AiTemplates.put(field.text.toString(), t.category, t.kind, html, t.fields, t.note)
                if (!field.text.toString().equals(t.name, true)) AiTemplates.remove(t.id)
                render()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun move(t: AiTemplates.Template) {
        val ctx = context ?: return
        val groups = (AiTemplateGroups.all().map { it.name } + AiTemplates.CATEGORIES)
            .distinctBy { it.lowercase() }
        val labels = groups.map { AiTemplateGroups.get(it).emoji + "  " + it }
            .toMutableList()
        labels.add(getString(R.string.tpl_group_new))
        MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.tpl_move)
            .setItems(labels.toTypedArray()) { _, w ->
                val html = try { t.file.readText() } catch (_: Throwable) { return@setItems }
                if (w == groups.size) {
                    askNewGroup { name ->
                        AiTemplates.put(t.name, name, t.kind, html, t.fields, t.note); render()
                    }
                } else {
                    AiTemplates.put(t.name, groups[w], t.kind, html, t.fields, t.note)
                    render()
                }
            }
            .show()
    }

    private fun askNewGroup(onName: (String) -> Unit) {
        val ctx = context ?: return
        val field = EditText(ctx).apply { setHint(R.string.tpl_group_hint); setSingleLine(true) }
        MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.tpl_group_new)
            .setView(LinearLayout(ctx).apply {
                setPadding(dp(22), dp(8), dp(22), dp(8))
                addView(field, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            })
            .setPositiveButton(R.string.ok) { _, _ ->
                val n = field.text.toString().trim()
                if (n.isNotEmpty()) onName(n)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // ---------------- group appearance ----------------

    private fun editGroup(folder: String) {
        val ctx = context ?: return
        val g = AiTemplateGroups.get(folder)
        var emoji = g.emoji
        var colorId = AiTemplateGroups.COLORS.firstOrNull { it.second == g.color }?.first
            ?: AiTemplateGroups.COLORS[0].first

        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(10), dp(20), dp(6))
        }
        box.addView(TextView(ctx).apply {
            setText(R.string.tpl_group_emoji)
            textSize = 12f
            alpha = 0.75f
        })
        val emojiRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        val emojiViews = ArrayList<TextView>()
        // sixteen glyphs wrap into two scrollable rows rather than shrinking
        val emojiWrap = android.widget.HorizontalScrollView(ctx).apply {
            isHorizontalScrollBarEnabled = false
            addView(emojiRow)
        }
        for (e in AiTemplateGroups.EMOJI) {
            val tv = TextView(ctx).apply {
                text = e
                textSize = 22f
                gravity = Gravity.CENTER
                setPadding(dp(7), dp(6), dp(7), dp(6))
                isClickable = true
                setOnClickListener {
                    emoji = e
                    for (v in emojiViews) v.alpha = if (v.text == emoji) 1f else 0.45f
                }
            }
            tv.alpha = if (e == emoji) 1f else 0.45f
            emojiViews.add(tv)
            emojiRow.addView(tv)
        }
        box.addView(emojiWrap)

        box.addView(TextView(ctx).apply {
            setText(R.string.tpl_group_color)
            textSize = 12f
            alpha = 0.75f
            setPadding(0, dp(12), 0, dp(4))
        })
        val dots = ArrayList<TextView>()
        box.addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            for ((id, color) in AiTemplateGroups.COLORS) {
                val dot = TextView(ctx).apply {
                    text = if (id == colorId) "✓" else ""
                    textSize = 15f
                    gravity = Gravity.CENTER
                    setTextColor(0xFFFFFFFF.toInt())
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(color)
                    }
                    layoutParams = LinearLayout.LayoutParams(dp(34), dp(34))
                        .apply { marginEnd = dp(9) }
                    isClickable = true
                    setOnClickListener {
                        colorId = id
                        for ((i, d) in dots.withIndex()) {
                            d.text = if (AiTemplateGroups.COLORS[i].first == colorId) "✓" else ""
                        }
                    }
                }
                dots.add(dot)
                addView(dot)
            }
        })

        MaterialAlertDialogBuilder(ctx)
            .setTitle(getString(R.string.tpl_group_title, folder))
            .setView(ScrollView(ctx).apply { addView(box) })
            .setPositiveButton(R.string.save) { _, _ ->
                AiTemplateGroups.set(folder, emoji, colorId)
                render()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showSource(t: AiTemplates.Template) {
        val ctx = context ?: return
        val text = try { t.file.readText() } catch (_: Throwable) { return }
        val body = TextView(ctx).apply {
            this.text = text
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
            setPadding(dp(14), dp(10), dp(14), dp(10))
        }
        MaterialAlertDialogBuilder(ctx)
            .setTitle(t.name)
            .setView(ScrollView(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    (ctx.resources.displayMetrics.heightPixels * 0.7f).toInt()
                )
                addView(android.widget.HorizontalScrollView(ctx).apply { addView(body) })
            })
            .setPositiveButton(R.string.ok, null)
            .show()
    }

    // ---------------- requisites ----------------

    /**
     * v1.7.72 FIX (user: «заполнял всё, но кнопок нету — как сохранять?»).
     *
     * Twelve fields in a ScrollView make an AlertDialog grow until its own
     * button bar is pushed off the screen. The height is capped, the fields
     * scroll, and the buttons sit outside the scrolling area.
     */
    private fun editBusiness() {
        val ctx = context ?: return
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(4), dp(22), dp(8))
        }
        val inputs = LinkedHashMap<String, EditText>()
        for ((key, labelRes) in AiBusiness.FIELDS) {
            box.addView(TextView(ctx).apply {
                text = getString(labelRes)
                textSize = 11.5f
                alpha = 0.7f
                setPadding(0, dp(8), 0, 0)
            })
            val et = EditText(ctx).apply {
                setText(AiBusiness.get(key))
                setSingleLine(key != "address" && key != "terms")
                textSize = 14f
            }
            inputs[key] = et
            box.addView(et)
        }

        // signature lives with the details it is signed alongside
        box.addView(TextView(ctx).apply {
            setText(R.string.sig_title)
            textSize = 11.5f
            alpha = 0.7f
            setPadding(0, dp(16), 0, dp(4))
        })
        val sigState = TextView(ctx).apply {
            text = sigLine()
            textSize = 13.5f
        }
        box.addView(sigState)
        box.addView(TextView(ctx).apply {
            setText(R.string.sig_draw)
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(PanelUi.accent(ctx))
            setPadding(0, dp(8), 0, dp(4))
            isClickable = true
            setOnClickListener {
                SignaturePad.show(requireActivity()) {
                    sigState.text = sigLine()
                    sigSummary?.text = sigLine()
                    bizSummary?.text = bizLine()
                }
            }
        })

        box.addView(TextView(ctx).apply {
            text = getString(
                R.string.biz_next_fmt,
                AiBusiness.peekNumber("devis"), AiBusiness.peekNumber("facture")
            )
            textSize = 11.5f
            alpha = 0.7f
            setPadding(0, dp(14), 0, 0)
        })

        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (ctx.resources.displayMetrics.heightPixels * 0.82f).toInt()
            )
            addView(TextView(ctx).apply {
                setText(R.string.biz_title)
                textSize = 19f
                typeface = Typeface.DEFAULT_BOLD
                setPadding(dp(22), dp(18), dp(22), dp(4))
            })
            addView(TextView(ctx).apply {
                setText(R.string.biz_sub)
                textSize = 12.5f
                alpha = 0.75f
                setPadding(dp(22), 0, dp(22), dp(6))
            })
            addView(ScrollView(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
                addView(box)
            })
        }

        val dialog = MaterialAlertDialogBuilder(ctx).setView(root).create()

        fun btn(labelRes: Int, bold: Boolean, onTap: () -> Unit) = TextView(ctx).apply {
            setText(labelRes)
            textSize = 15f
            gravity = Gravity.CENTER
            typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            setTextColor(if (bold) PanelUi.accent(ctx)
            else PanelUi.themedColor(ctx, R.attr.colorOnSurfaceVariant))
            setPadding(dp(8), dp(14), dp(8), dp(14))
            isClickable = true
            background = PanelUi.ripple(ctx)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { onTap() }
        }

        root.addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(12), 0, dp(12), dp(10))
            addView(btn(R.string.cancel, false) { dialog.dismiss() })
            addView(btn(R.string.save, true) {
                for ((k, et) in inputs) AiBusiness.set(k, et.text.toString())
                dialog.dismiss()
                bizSummary?.text = bizLine()
                Toast.makeText(ctx, R.string.biz_saved, Toast.LENGTH_SHORT).show()
            })
        })

        dialog.show()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()
}
