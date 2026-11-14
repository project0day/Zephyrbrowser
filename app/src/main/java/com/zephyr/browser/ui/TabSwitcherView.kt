package com.zephyr.browser.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.util.AttributeSet
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.zephyr.browser.FaviconLoader
import com.zephyr.browser.Prefs
import com.zephyr.browser.R
import com.zephyr.browser.TabManager

/**
 * Tab switcher (v1.4.1 premium redesign):
 * - NO segmented "Обычные/Приватные" bar — compact ICON tabs live in the HEADER row,
 *   centered, aligned with the «Вкладки» title (user request);
 * - cards are full-bleed page THUMBNAILS with the favicon + title + domain overlaid
 *   INSIDE the card on a bottom scrim (user request: "вся инфа внутри макета");
 * - fresh capture on open (MainActivity.captureAllThumbnails) so the current tab
 *   always shows a real screenshot; tabs without a preview get a branded
 *   favicon placeholder instead of the old house icon;
 * - swipe a card left/right to close it.
 */
class TabSwitcherView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    var onSelect: ((TabManager.Tab) -> Unit)? = null
    var onClose: ((TabManager.Tab) -> Unit)? = null
    var onNewTab: ((Boolean) -> Unit)? = null
    var onCloseAll: ((Boolean) -> Unit)? = null

    /** v1.7.4: back/exit button in the header — the user reported there was NO
     *  visible way out of the tabs screen (only the system back gesture);
     *  wired by MainActivity to closeTabSwitcher(). */
    var onBack: (() -> Unit)? = null

    /** v1.5.0: fired whenever the DISPLAYED section changes (regular ⇄ private) so the
     *  host activity can re-tint the system bars + toolbar to match (purple while the
     *  user browses the private section, even though the current tab is regular). */
    var onSectionChange: ((Boolean) -> Unit)? = null

    /** Which section is displayed (drives the whole palette — leak fix). */
    private var incognitoSection = false

    // v1.7.7: live search over tab titles/urls (empty = show everything)
    private var searchQuery = ""
    // v1.7.7: group names the user collapsed (header chevron)
    private val collapsedGroups = mutableSetOf<String>()

    // chrome
    private lateinit var hdrTitle: TextView
    private lateinit var hdrCount: TextView
    private lateinit var btnBack: ImageButton
    private lateinit var btnCloseAll: ImageButton
    private lateinit var tabRegular: LinearLayout
    private lateinit var tabPrivate: LinearLayout
    private lateinit var tabRegularIcon: ImageButton
    private lateinit var tabPrivateIcon: ImageButton
    private lateinit var tabRegularCount: TextView
    private lateinit var tabPrivateCount: TextView
    // v1.7.7: search row + create-group action row
    private lateinit var searchBox: LinearLayout
    private lateinit var searchInput: android.widget.EditText
    private lateinit var btnSearch: ImageButton
    private lateinit var groupActionRow: LinearLayout
    private lateinit var list: LinearLayout
    private lateinit var emptyView: LinearLayout
    private lateinit var fab: ImageButton

    init {
        setBackgroundColor(sectionBg(false))
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        }

        // ---- header: [back] [title+count] [regular icon | private icon] [close-all] ----
        root.addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(10), dp(8), dp(4))

            // v1.7.4: explicit BACK button at the very left — a visible exit from the
            // tabs screen that does not rely on the system back gesture.
            addView(ImageButton(context).apply {
                setImageResource(R.drawable.ic_back)
                background = circularRipple(0)
                setPadding(dp(10), dp(10), dp(10), dp(10))
                layoutParams = LinearLayout.LayoutParams(dp(42), dp(42))
                contentDescription = context.getString(R.string.pl_back)
                setOnClickListener { onBack?.invoke() }
            }.also { btnBack = it })

            // left: title + count
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                addView(TextView(context).apply {
                    text = context.getString(R.string.tabs)
                    textSize = 19f
                    typeface = Typeface.DEFAULT_BOLD
                }.also { hdrTitle = it })
                addView(TextView(context).apply {
                    text = ""
                    textSize = 12f
                    setPadding(0, dp(1), 0, 0)
                }.also { hdrCount = it })
            })

            // center: section ICONS (aligned with the title row, per user request)
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(6), 0, dp(6), 0)
                fun sectionTab(iconRes: Int): LinearLayout = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER
                    setPadding(dp(9), dp(7), dp(9), dp(7))
                }
                tabRegular = sectionTab(R.drawable.ic_tabs).also { tab ->
                    tabRegularIcon = ImageButton(context).apply {
                        setImageResource(R.drawable.ic_tabs)
                        background = null
                        setPadding(0, 0, 0, 0)
                        layoutParams = LinearLayout.LayoutParams(dp(22), dp(22))
                        isClickable = false
                        isFocusable = false
                    }
                    tab.addView(tabRegularIcon)
                    tabRegularCount = TextView(context).apply {
                        textSize = 11.5f
                        typeface = Typeface.DEFAULT_BOLD
                        layoutParams = LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                            marginStart = dp(5)
                        }
                    }
                    tab.addView(tabRegularCount)
                    tab.setOnClickListener { setSection(false) }
                }
                tabPrivate = sectionTab(R.drawable.ic_private).also { tab ->
                    tabPrivateIcon = ImageButton(context).apply {
                        setImageResource(R.drawable.ic_private)
                        background = null
                        setPadding(0, 0, 0, 0)
                        layoutParams = LinearLayout.LayoutParams(dp(22), dp(22))
                        isClickable = false
                        isFocusable = false
                    }
                    tab.addView(tabPrivateIcon)
                    tabPrivateCount = TextView(context).apply {
                        textSize = 11.5f
                        typeface = Typeface.DEFAULT_BOLD
                        layoutParams = LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                            marginStart = dp(5)
                        }
                    }
                    tab.addView(tabPrivateCount)
                    tab.setOnClickListener { setSection(true) }
                }
                addView(tabRegular)
                addView(tabPrivate)
            })

            // v1.7.7: SEARCH over open tabs (title/url) — toggles the search row
            addView(ImageButton(context).apply {
                setImageResource(R.drawable.ic_search)
                background = circularRipple(0)
                setPadding(dp(10), dp(10), dp(10), dp(10))
                layoutParams = LinearLayout.LayoutParams(dp(42), dp(42))
                contentDescription = context.getString(R.string.ts_search_tabs)
                setOnClickListener {
                    val show = searchBox.visibility != View.VISIBLE
                    searchBox.visibility = if (show) View.VISIBLE else View.GONE
                    if (show) searchInput.requestFocus() else {
                        searchInput.setText(""); searchQuery = ""
                        refresh()
                    }
                }
            }.also { btnSearch = it })

            // right: close all
            addView(ImageButton(context).apply {
                setImageResource(R.drawable.ic_delete)
                background = circularRipple(0)
                setPadding(dp(10), dp(10), dp(10), dp(10))
                layoutParams = LinearLayout.LayoutParams(dp(42), dp(42))
                contentDescription = context.getString(R.string.ts_close_all)
                setOnClickListener {
                    onCloseAll?.invoke(incognitoSection)
                }
            }.also { btnCloseAll = it })
        })

        // ---- v1.7.7: live search row (GONE until the header search icon is tapped) ----
        searchBox = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            setPadding(dp(14), dp(2), dp(14), dp(6))
            addView(android.widget.EditText(context).apply {
                hint = context.getString(R.string.ts_search_hint)
                setSingleLine(true)
                textSize = 14.5f
                background = com.zephyr.browser.settings.SettingsUi.rounded(context, dp(24))
                setPadding(dp(16), dp(10), dp(16), dp(10))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                addTextChangedListener(object : android.text.TextWatcher {
                    override fun beforeTextChanged(a: CharSequence?, x: Int, y: Int, z: Int) {}
                    override fun onTextChanged(a: CharSequence?, x: Int, y: Int, z: Int) {}
                    override fun afterTextChanged(e: android.text.Editable?) {
                        searchQuery = e?.toString()?.trim() ?: ""
                        refresh()
                    }
                })
            }.also { searchInput = it })
        }
        root.addView(searchBox)

        // ---- v1.7.7: «Создать группу» action row (regular section only) ----
        groupActionRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(14), dp(2), dp(14), dp(4))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14), dp(9), dp(14), dp(9))
                isClickable = true
                background = com.zephyr.browser.settings.SettingsUi.rounded(context, dp(20))
                setOnClickListener { showCreateGroupDialog() }
                addView(ImageView(context).apply {
                    setImageResource(R.drawable.ic_folder_add)
                    imageTintList = android.content.res.ColorStateList.valueOf(accentColor())
                    layoutParams = LinearLayout.LayoutParams(dp(18), dp(18))
                })
                addView(TextView(context).apply {
                    text = context.getString(R.string.tg_create)
                    textSize = 13.5f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(android.content.res.ColorStateList.valueOf(onSurfaceColor()))
                    setPadding(dp(8), 0, 0, 0)
                })
            })
        }
        root.addView(groupActionRow)

        // ---- scrollable card grid ----
        val scroll = ScrollView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
            isVerticalScrollBarEnabled = false
        }
        list = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(96))
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        scroll.addView(list)
        root.addView(scroll)

        // ---- centered empty state ----
        emptyView = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            visibility = View.GONE
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        }
        emptyView.addView(ImageView(context).apply {
            setImageResource(R.drawable.ic_tabs)
            layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
            alpha = 0.35f
        })
        emptyView.addView(TextView(context).apply {
            text = context.getString(R.string.ts_empty)
            textSize = 14f
            setPadding(0, dp(10), 0, 0)
            gravity = Gravity.CENTER
        })
        root.addView(emptyView)

        addView(root)

        // ---- FAB: new tab in the active section ----
        fab = ImageButton(context).apply {
            setImageResource(R.drawable.ic_add)
            setPadding(dp(15), dp(15), dp(15), dp(15))
            imageTintList = android.content.res.ColorStateList.valueOf(0xFFFFFFFF.toInt())
            elevation = dp(8).toFloat()
            contentDescription = context.getString(R.string.ts_new_tab)
            setOnClickListener { onNewTab?.invoke(incognitoSection) }
        }
        addView(fab, LayoutParams(dp(58), dp(58), Gravity.BOTTOM or Gravity.END).apply {
            marginEnd = dp(22); bottomMargin = dp(28)
        })
    }

    // ---------------- public API ----------------

    fun setSection(incognito: Boolean) {
        val changed = incognitoSection != incognito
        incognitoSection = incognito
        setBackgroundColor(sectionBg(incognito))
        if (changed) onSectionChange?.invoke(incognito)
        refresh()
    }

    /** Synced from MainActivity when the current tab mode changes. */
    fun setIncognito(incognito: Boolean) {
        setBackgroundColor(sectionBg(incognitoSection))
    }

    fun refresh() {
        val mgr = tabManager() ?: return
        var tabs = if (incognitoSection) mgr.privateTabs() else mgr.regularTabs()
        setBackgroundColor(sectionBg(incognitoSection))
        // v1.7.7: live search — filter cards by title/url before sectioning
        if (searchQuery.isNotEmpty()) {
            val q = searchQuery.lowercase()
            tabs = tabs.filter {
                it.title.lowercase().contains(q) ||
                    it.pageUrlSnapshot.lowercase().contains(q) ||
                    it.group.lowercase().contains(q)
            }
        }
        // v1.7.7: search row + create-group row visibility
        searchBox.visibility = if (searchBox.visibility == View.VISIBLE) View.VISIBLE else View.GONE
        groupActionRow.visibility =
            if (!incognitoSection && searchQuery.isEmpty()) View.VISIBLE else View.GONE
        list.removeAllViews()
        emptyView.visibility = if (tabs.isEmpty()) View.VISIBLE else View.GONE

        // v1.5.0 FIX 1: TRUE 2-column grid (was 42%-width cards in CENTER_HORIZONTAL rows —
        // a lone card in the last row floated in the middle of the screen and the whole
        // grid "wandered" left/right). Each card is exactly half of the row's content
        // width: (screen - 2*12dp list padding - 10dp gap) / 2; rows are left-aligned so
        // a single trailing card sits in the LEFT column, like Chrome.
        // v1.5.0 FIX 2: thumb height 0.72 → 0.62 of the card width (cards were too tall).
        // Grid math uses the view's own width when measured (window insets in landscape
        // can make displayMetrics.widthPixels wider than the actual content area).
        val sidePad = dp(12)
        val gap = dp(10)
        val hostW = if (width > 0) width else resources.displayMetrics.widthPixels
        val cardW = ((hostW - 2 * sidePad - gap) / 2)
            .coerceAtLeast(dp(96))
        val thumbH = (cardW * 0.62f).toInt()

        // v1.7.6 (user: «Группы вкладок — папки в переключателе»): ungrouped tabs
        // first, then one section per named group with a folder header. Long-press
        // a card to (re)assign its group; the header's × closes the whole group.
        fun renderGrid(group: List<TabManager.Tab>) {
            var row: LinearLayout? = null
            for ((i, tab) in group.withIndex()) {
                if (i % 2 == 0) {
                    row = LinearLayout(context).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.START   // FIX 1: left-aligned grid
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                            bottomMargin = dp(12)
                        }
                    }
                    list.addView(row)
                }
                val card = tabCard(tab, cardW, thumbH)
                card.setOnLongClickListener {
                    showGroupDialog(tab)
                    true
                }
                val wrap = swipeClose(card, tab)
                wrap.layoutParams = LinearLayout.LayoutParams(cardW, thumbH).apply {
                    if (i % 2 == 0) marginEnd = gap
                }
                row?.addView(wrap)
            }
        }

        // v1.7.7: PINNED tabs get their own first section (pin badge, never evicted,
        // kept by «close all» unless explicitly chosen); then ungrouped; then groups.
        val pinned = tabs.filter { it.pinned }
        val unpinned = tabs.filter { !it.pinned }
        val ungrouped = unpinned.filter { it.group.isEmpty() }
        val groups = linkedMapOf<String, MutableList<TabManager.Tab>>()
        for (t in unpinned) if (t.group.isNotEmpty()) groups.getOrPut(t.group) { mutableListOf() }.add(t)
        // empty-but-registered groups still show (v1.7.7 «Создать группу» button)
        for (g in mgr.allGroups()) if (!groups.containsKey(g)) groups[g] = mutableListOf()

        if (pinned.isNotEmpty()) {
            list.addView(sectionLabel(R.drawable.ic_pin, context.getString(R.string.ts_pinned), pinned.size, accentColor()))
            renderGrid(pinned)
        }
        if (ungrouped.isNotEmpty() || (groups.isEmpty() && pinned.isEmpty())) {
            if (!incognitoSection && ungrouped.isNotEmpty()) {
                list.addView(sectionLabel(R.drawable.ic_tabs, context.getString(R.string.ts_all_tabs), ungrouped.size, onSurfaceVariantColor()))
            }
            renderGrid(ungrouped)
        }
        for ((name, groupTabs) in groups) {
            val collapsed = name in collapsedGroups
            list.addView(groupHeader(name, groupTabs.size, collapsed) { groupTabs.toList() })
            if (!collapsed && groupTabs.isNotEmpty()) renderGrid(groupTabs)
        }

        styleChrome(tabs.size, mgr.regularTabs().size, mgr.privateTabs().size)
        styleFab()
        // v1.5.1 motion: cards cascade in as a row-major wave (only when actually on
        // screen; openTabSwitcher() already plays its own container pop-in).
        if (isShown) Anim.cascade(list, columns = 2)
    }

    /** v1.7.7: slim section label row (Закреплённые / Вкладки). */
    private fun sectionLabel(iconRes: Int, label: String, count: Int, tint: Int): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), dp(10), dp(2), dp(2))
        }
        row.addView(ImageView(context).apply {
            setImageResource(iconRes)
            imageTintList = android.content.res.ColorStateList.valueOf(tint)
            layoutParams = LinearLayout.LayoutParams(dp(16), dp(16)).apply { marginEnd = dp(7) }
        })
        row.addView(TextView(context).apply {
            text = label.uppercase()
            textSize = 11.5f
            letterSpacing = 0.09f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(android.content.res.ColorStateList.valueOf(tint))
        })
        row.addView(TextView(context).apply {
            text = count.toString()
            textSize = 11.5f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(8), 0, 0, 0)
            setTextColor(android.content.res.ColorStateList.valueOf(onSurfaceVariantColor()))
        })
        return row
    }

    /** v1.7.7: «Создать группу» — named-group dialog; groups may stay EMPTY until
     *  tabs are assigned (they render as an empty folder section).
     *  v1.7.8 (user: «группы-папки с иконками и цветами — выбор эмодзи/цвета для
     *  группы вкладок»): the dialog grew an EMOJI strip + a COLOR swatch row —
     *  the group badge in the switcher uses both. */
    private val GROUP_EMOJIS = listOf(
        "📁", "💼", "🎮", "🛒", "🎬", "🎵", "📚", "✈️", "🍔", "⚽",
        "💻", "🔬", "🎨", "💬", "📷", "🔥", "🌟", "❤️", "🌍", "🚗")
    private val GROUP_COLORS = listOf(
        0xFF10B981.toInt(), 0xFF14B8A6.toInt(), 0xFFF59E0B.toInt(), 0xFFF97316.toInt(),
        0xFFEF4444.toInt(), 0xFFEC4899.toInt(), 0xFF8B5CF6.toInt(), 0xFF64748B.toInt())

    private fun showGroupEditor(titleRes: Int, presetName: String, presetEmoji: String,
                                presetColor: Int, onSave: (String, String, Int) -> Unit) {
        val ctx = context
        var emoji = presetEmoji
        var color = presetColor
        val input = android.widget.EditText(ctx).apply {
            setText(presetName)
            hint = ctx.getString(R.string.tg_name_hint)
            setSingleLine(true)
            setPadding(dp(20), dp(12), dp(20), dp(4))
        }
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(4), dp(8), dp(4))
            addView(input)
            addView(TextView(ctx).apply {
                text = context.getString(R.string.tg_icon)
                textSize = 12f
                typeface = Typeface.DEFAULT_BOLD
                setPadding(dp(12), dp(10), dp(12), dp(4))
                setTextColor(onSurfaceColor())
            })
        }
        // emoji strip
        val emojiScroll = android.widget.HorizontalScrollView(ctx).apply {
            isHorizontalScrollBarEnabled = false
        }
        val emojiRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(8), dp(2), dp(8), dp(2))
        }
        val emojiCells = mutableListOf<TextView>()
        for (e in GROUP_EMOJIS) {
            val cell = TextView(ctx).apply {
                text = e
                textSize = 20f
                gravity = Gravity.CENTER
                setPadding(dp(2), dp(6), dp(2), dp(6))
                layoutParams = LinearLayout.LayoutParams(dp(42), dp(42))
                setOnClickListener {
                    emoji = e
                    emojiCells.forEach { it.background = null }
                    it.background = com.zephyr.browser.settings.SettingsUi.rounded(ctx, dp(12))
                }
            }
            if (e == presetEmoji) cell.background = com.zephyr.browser.settings.SettingsUi.rounded(ctx, dp(12))
            emojiCells.add(cell)
            emojiRow.addView(cell)
        }
        emojiScroll.addView(emojiRow)
        col.addView(emojiScroll)
        // color swatches — v1.7.10: the row got its own HorizontalScrollView like
        // the emoji strip above (the 8th swatch used to be clipped on narrow
        // screens with no way to reach it); swatches shrunk to 30dp with 8dp
        // gaps so all 8 colors usually fit without scrolling at all.
        col.addView(TextView(ctx).apply {
            text = context.getString(R.string.tg_color)
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(12), dp(8), dp(12), dp(4))
            setTextColor(onSurfaceColor())
        })
        val colorScroll = android.widget.HorizontalScrollView(ctx).apply {
            isHorizontalScrollBarEnabled = false
        }
        val colorRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(8), dp(2), dp(8), dp(8))
        }
        val swatches = mutableListOf<View>()
        for (c in GROUP_COLORS) {
            val swatch = View(ctx).apply {
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL
                    setColor(c)
                    if (c == presetColor) setStroke(dp(3), 0xFFFFFFFF.toInt())
                }
                layoutParams = LinearLayout.LayoutParams(dp(30), dp(30)).apply { marginEnd = dp(8) }
                setOnClickListener {
                    color = c
                    swatches.forEach { s ->
                        (s.background as? android.graphics.drawable.GradientDrawable)?.setStroke(0, 0)
                    }
                    (it.background as? android.graphics.drawable.GradientDrawable)?.setStroke(dp(3), 0xFFFFFFFF.toInt())
                }
            }
            swatches.add(swatch)
            colorRow.addView(swatch)
        }
        colorScroll.addView(colorRow)
        col.addView(colorScroll)
        com.google.android.material.dialog.MaterialAlertDialogBuilder(ctx)
            .setTitle(titleRes)
            .setView(col)
            .setPositiveButton(R.string.save) { _, _ ->
                val name = input.text.toString().trim().take(32)
                if (name.isNotEmpty()) onSave(name, emoji, color)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showCreateGroupDialog() {
        showGroupEditor(R.string.tg_create, "", "📁", GROUP_COLORS.first()) { name, emoji, color ->
            val mgr = tabManager() ?: return@showGroupEditor
            if (mgr.createGroup(name, emoji, color)) refresh()
            else android.widget.Toast.makeText(context, R.string.tg_exists,
                android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    /** v1.7.7: rename dialog for an existing group (v1.7.8: badge editable too). */
    private fun showRenameGroupDialog(old: String) {
        val mgr = tabManager() ?: return
        val meta = mgr.groupMeta[old]
        showGroupEditor(R.string.tg_rename, old, meta?.emoji ?: "📁",
            meta?.color ?: GROUP_COLORS.first()) { name, emoji, color ->
            if (mgr.renameGroup(old, name)) {
                collapsedGroups.remove(old)
                mgr.setGroupMeta(name, emoji, color)
                refresh()
            } else {
                android.widget.Toast.makeText(context, R.string.tg_exists,
                    android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** v1.7.7: ⋯ menu on a group header — rename / close / empty (tabs stay). */
    private fun showGroupMenu(name: String, count: Int, groupTabs: () -> List<TabManager.Tab>) {
        val mgr = tabManager() ?: return
        val options = listOf(
            context.getString(R.string.tg_rename),
            context.getString(R.string.tg_empty_group),
            context.getString(R.string.tg_close_group) + " (" + count + ")")
        com.google.android.material.dialog.MaterialAlertDialogBuilder(context)
            .setTitle(name)
            .setItems(options.toTypedArray()) { d, which ->
                when (which) {
                    0 -> showRenameGroupDialog(name)
                    1 -> { mgr.deleteGroup(name, closeTabs = false); refresh() }
                    2 -> com.google.android.material.dialog.MaterialAlertDialogBuilder(context)
                        .setTitle(name)
                        .setMessage(context.getString(R.string.tg_close_confirm, count))
                        .setPositiveButton(R.string.delete) { _, _ ->
                            mgr.deleteGroup(name, closeTabs = true)
                            refresh()
                        }
                        .setNegativeButton(R.string.cancel, null)
                        .show()
                }
                d.dismiss()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** v1.7.6: section header for a named tab group with a close-group button.
     *  v1.7.7: PREMIUM — accent folder badge, collapse chevron (tap header),
     *  ⋯ menu (rename / empty / close), count pill.
     *  v1.7.8: the badge shows the group's EMOJI on its chosen COLOR. */
    private fun groupHeader(name: String, count: Int, collapsed: Boolean, groupTabs: () -> List<TabManager.Tab>): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), dp(10), dp(2), dp(2))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        val meta = tabManager()?.groupMeta?.get(name)
        val badgeColor = meta?.color ?: 0xFF10B981.toInt()
        // emoji badge in the group's chosen color
        row.addView(LinearLayout(context).apply {
            gravity = Gravity.CENTER
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = dp(10).toFloat()
                setColor(badgeColor)
            }
            addView(TextView(context).apply {
                text = meta?.emoji ?: "📁"
                textSize = 14f
                gravity = Gravity.CENTER
            })
            layoutParams = LinearLayout.LayoutParams(dp(28), dp(28)).apply { marginEnd = dp(10) }
        })
        // group name
        row.addView(TextView(context).apply {
            text = name
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(onSurfaceColor())
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        // count pill
        row.addView(TextView(context).apply {
            text = count.toString()
            textSize = 11.5f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(dp(9), dp(3), dp(9), dp(3))
            background = com.zephyr.browser.settings.SettingsUi.rounded(context, dp(11))
            setTextColor(onSurfaceVariantColor())
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(4) }
        })
        // collapse chevron (tap the whole header toggles)
        val chevron = ImageView(context).apply {
            setImageResource(R.drawable.ic_chevron_down)
            imageTintList = android.content.res.ColorStateList.valueOf(onSurfaceVariantColor())
            layoutParams = LinearLayout.LayoutParams(dp(20), dp(20))
            rotation = if (collapsed) -90f else 0f
        }
        row.addView(chevron)
        // ⋯ menu
        row.addView(ImageButton(context).apply {
            setImageResource(R.drawable.ic_more_vert)
            background = circularRipple(0)
            setPadding(dp(7), dp(7), dp(7), dp(7))
            layoutParams = LinearLayout.LayoutParams(dp(34), dp(34))
            contentDescription = context.getString(R.string.tg_menu)
            imageTintList = android.content.res.ColorStateList.valueOf(onSurfaceVariantColor())
            setOnClickListener { showGroupMenu(name, count, groupTabs) }
        })
        // header tap → collapse/expand with a soft chevron rotation
        row.isClickable = true
        row.foreground = MenuSheets.ripple(context)
        row.setOnClickListener {
            if (name in collapsedGroups) collapsedGroups.remove(name) else collapsedGroups.add(name)
            refresh()
        }
        return row
    }

    /**
     * v1.7.7 PREMIUM long-press sheet (user: «сделай красивую систему групп»):
     *  - Закрепить / Открепить (pinned section + LRU immunity);
     *  - add to any EXISTING group (one tap, folder rows with counts);
     *  - Новая группа (name dialog);
     *  - Убрать из группы / Закрыть вкладку.
     */
    private fun showGroupDialog(tab: TabManager.Tab) {
        val mgr = tabManager() ?: return
        val sheet = com.google.android.material.bottomsheet.BottomSheetDialog(context)
        val col = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(14), dp(8), dp(24))
        }
        // header: favicon + title + domain
        col.addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(2), dp(12), dp(12))
            addView(ImageView(context).apply {
                setImageBitmap(FaviconLoader.load(context, tab.pageUrlSnapshot, tab.title))
                layoutParams = LinearLayout.LayoutParams(dp(30), dp(30))
            })
            addView(TextView(context).apply {
                text = tab.title.ifEmpty { context.getString(R.string.nav_home) }
                textSize = 15.5f
                typeface = Typeface.DEFAULT_BOLD
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                setTextColor(onSurfaceColor())
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = dp(12)
                }
            })
        })
        fun sRow(iconRes: Int, label: String, iconTint: Int = accentColor(), bold: Boolean = false, fn: () -> Unit) {
            col.addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(13), dp(12), dp(13))
                isClickable = true
                background = MenuSheets.ripple(context)
                setOnClickListener { sheet.dismiss(); fn() }
                addView(ImageView(context).apply {
                    setImageResource(iconRes)
                    imageTintList = android.content.res.ColorStateList.valueOf(iconTint)
                    layoutParams = LinearLayout.LayoutParams(dp(21), dp(21))
                })
                addView(TextView(context).apply {
                    text = label
                    textSize = 15f
                    typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                    setPadding(dp(14), 0, 0, 0)
                    setTextColor(onSurfaceColor())
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                })
                if (label == tab.group && tab.group.isNotEmpty()) {
                    addView(ImageView(context).apply {
                        setImageResource(R.drawable.ic_check)
                        imageTintList = android.content.res.ColorStateList.valueOf(accentColor())
                        layoutParams = LinearLayout.LayoutParams(dp(18), dp(18))
                    })
                }
            })
        }
        // pin toggle
        sRow(if (tab.pinned) R.drawable.ic_close else R.drawable.ic_pin,
            context.getString(if (tab.pinned) R.string.ts_unpin else R.string.ts_pin),
            iconTint = if (tab.pinned) onSurfaceVariantColor() else accentColor(),
            bold = tab.pinned) {
            tab.pinned = !tab.pinned
            refresh()
        }
        // divider + group section
        col.addView(View(context).apply {
            setBackgroundColor((onSurfaceVariantColor() and 0x00FFFFFF) or 0x26000000)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1).apply {
                setMargins(dp(12), dp(6), dp(12), dp(6))
            }
        })
        val groups = mgr.allGroups()
        if (groups.isNotEmpty()) {
            col.addView(TextView(context).apply {
                text = context.getString(R.string.tg_add_to)
                textSize = 11.5f
                letterSpacing = 0.07f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(onSurfaceVariantColor())
                setPadding(dp(14), dp(2), dp(14), dp(2))
            })
            for (g in groups) {
                val cnt = mgr.tabs.count { it.group == g }
                sRow(R.drawable.ic_folder, "$g  ·  $cnt") {
                    tab.group = g
                    collapsedGroups.remove(g)
                    refresh()
                }
            }
        }
        sRow(R.drawable.ic_folder_add, context.getString(R.string.tg_new_group), bold = true) {
            // v1.7.8: the long-press "new group" path now opens the same premium
            // emoji+color editor instead of a bare name input
            showGroupEditor(R.string.tg_new_group, "", "📁", GROUP_COLORS.first()) { name, emoji, color ->
                if (mgr.createGroup(name, emoji, color)) {
                    tab.group = name
                    refresh()
                }
            }
        }
        if (tab.group.isNotEmpty()) {
            sRow(R.drawable.ic_close, context.getString(R.string.tg_remove), iconTint = onSurfaceVariantColor()) {
                tab.group = ""
                refresh()
            }
        }
        sRow(R.drawable.ic_delete, context.getString(R.string.tg_close_tab), iconTint = 0xFFE5484D.toInt(), bold = true) {
            onClose?.invoke(tab)
            refresh()
        }
        sheet.setContentView(col)
        sheet.show()
    }

    /** Horizontal swipe (either direction) closes the tab card. */
    private fun swipeClose(card: View, tab: TabManager.Tab): View {
        val wrap = object : FrameLayout(context) {
            var startX = 0f
            var startTrans = 0f
            var dragging = false
            override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        startX = e.x
                        startTrans = card.translationX
                        dragging = false
                    }
                    MotionEvent.ACTION_MOVE -> {
                        if (kotlin.math.abs(e.x - startX) > 14f * resources.displayMetrics.density) {
                            dragging = true
                            parent.requestDisallowInterceptTouchEvent(true)
                            return true
                        }
                    }
                }
                return false
            }
            override fun onTouchEvent(e: MotionEvent): Boolean {
                when (e.actionMasked) {
                    MotionEvent.ACTION_MOVE -> {
                        card.translationX = startTrans + (e.x - startX)
                        card.alpha = (1f - kotlin.math.abs(card.translationX) / width).coerceIn(0.35f, 1f)
                        return true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        if (dragging) {
                            if (kotlin.math.abs(card.translationX) > width * 0.4f) {
                                card.animate().translationX(
                                    (if (card.translationX > 0) width.toFloat() else -width.toFloat()) * 1.2f)
                                    .alpha(0f).setDuration(180)
                                    .withEndAction { onClose?.invoke(tab) }.start()
                            } else {
                                card.animate().translationX(0f).alpha(1f).setDuration(160).start()
                            }
                        }
                        return dragging
                    }
                }
                return false
            }
        }
        wrap.addView(card, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        return wrap
    }

    // ---------------- card ----------------

    private fun tabCard(tab: TabManager.Tab, cardW: Int, thumbH: Int): View {
        val isCurrent = tabManager()?.current?.id == tab.id
        val inc = incognitoSection
        val shot: Bitmap? = tab.thumbnail

        val card = FrameLayout(context).apply {
            layoutParams = FrameLayout.LayoutParams(cardW, thumbH)
            elevation = dp(3).toFloat()
            isClickable = true
            setOnClickListener { onSelect?.invoke(tab) }
        }

        // full-bleed background: rounded + (thumbnail | branded placeholder | home mock)
        // v1.5.0 FIX 3: home-tab cards sit on the plain theme SURFACE so the mock's
        // surfaceVariant tiles are visible (incognito: the #241A3D surface family).
        val bg = GradientDrawable().apply {
            cornerRadius = dp(18).toFloat()
            setColor(if (tab.isHome) {
                if (inc) 0xFF241A3D.toInt() else surfaceColor()
            } else {
                if (inc) 0xFF2A1F47.toInt() else cardSurface()
            })
        }
        card.background = bg
        card.clipToOutline = true
        // v1.7.41 (user: «добавь тонкий контур макетов во вкладках»); v1.7.43
        // FIX (user: «контуры появляются и тут же пропадают на лету»): the
        // stroke was on the BACKGROUND, so the screenshot — a MATCH_PARENT
        // child — painted straight over it the moment it loaded. That is why it
        // flashed only while the placeholder was up. A foreground outline is
        // drawn after the children, so it survives.
        card.foreground = GradientDrawable().apply {
            cornerRadius = dp(18).toFloat()
            setColor(0x00000000)
            setStroke(
                dp(1).coerceAtLeast(1),
                if (inc) 0x40FFFFFF
                else (themeColor(
                    com.google.android.material.R.attr.colorOutline, 0xFF9AA0A6.toInt()
                ) and 0x00FFFFFF) or 0x66000000
            )
        }

        if (shot != null && !shot.isRecycled) {
            // v1.5.1 PRIVACY: the PRIVATE section never shows a readable screenshot —
            // the capture is downscale/upscale-blurred (works on every API level, no
            // RenderEffect needed) and a branded incognito badge sits on top.
            val shown = if (inc) blurredShot(shot) else shot
            card.addView(ImageView(context).apply {
                setImageBitmap(shown)
                scaleType = ImageView.ScaleType.CENTER_CROP
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            })
            if (inc) {
                // dark scrim over the blurred teaser + centered unique badge
                card.addView(View(context).apply {
                    setBackgroundColor(0x66170F26.toInt())
                    layoutParams = FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
                })
                card.addView(incognitoBadge())
            }
        } else {
            // v1.4.2 FIX 4: evicted/restarted tabs — pull the PERSISTED preview from disk
            // asynchronously; until it lands (or if none) show the branded placeholder,
            // or — v1.5.0 FIX 3 — the branded HOME MOCK for home tabs.
            val asyncShot = ImageView(context).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                visibility = View.GONE
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            }
            card.addView(asyncShot)
            val placeholder: View = if (tab.isHome) homeMock(inc, cardW) else faviconPlaceholder(tab, inc)
            card.addView(placeholder)
            val mgr = tabManager()
            if (mgr != null) mgr.loadPersistedThumbnail(tab) { bmp ->
                // the switcher may have been rebuilt/closed meanwhile — only touch a card
                // that is still attached and whose tab has no newer in-memory preview
                if (bmp != null && !bmp.isRecycled && card.isAttachedToWindow &&
                    (tab.thumbnail == null || tab.thumbnail?.isRecycled == true)) {
                    asyncShot.setImageBitmap(bmp)
                    asyncShot.visibility = View.VISIBLE
                    placeholder.visibility = View.GONE
                }
            }
        }

        // bottom scrim with favicon + title INSIDE the card
        card.addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                orientation = GradientDrawable.Orientation.TOP_BOTTOM
                colors = intArrayOf(0x00000000.toInt(), 0xB3000000.toInt())
                cornerRadius = 0f
            }
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM)
            val caption = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(9), dp(7), dp(9), dp(7))
            }
            caption.addView(ImageView(context).apply {
                layoutParams = LinearLayout.LayoutParams(dp(15), dp(15))
                scaleType = ImageView.ScaleType.FIT_CENTER
            }.also { iv ->
                if (tab.isHome) {
                    // v1.7.5: home cards carry the Zephyr mark — never a leftover
                    // site favicon (user: «внизу почему-то логотип Википедии»).
                    iv.setImageBitmap(zephyrMark())
                } else if (shot != null && !shot.isRecycled) {
                    // over a real thumbnail → small bright favicon
                    iv.setImageBitmap(FaviconLoader.load(context, tab.pageUrlSnapshot, tab.title))
                } else iv.visibility = View.GONE
            })
            caption.addView(TextView(context).apply {
                text = if (tab.isHome) context.getString(R.string.nav_home)
                else tab.title.ifEmpty { tab.pageUrlSnapshot }
                textSize = 11.5f
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                setTextColor(0xFFFFFFFF.toInt())
                typeface = if (isCurrent) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                setPadding(dp(5), 0, 0, 0)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(caption)
        })

        // v1.7.7: PIN badge over the thumbnail (top-left, inside)
        if (tab.pinned) {
            card.addView(ImageView(context).apply {
                setImageResource(R.drawable.ic_pin)
                setPadding(dp(5), dp(5), dp(5), dp(5))
                layoutParams = FrameLayout.LayoutParams(dp(28), dp(28), Gravity.TOP or Gravity.START).apply {
                    topMargin = dp(6); leftMargin = dp(6)
                }
                imageTintList = android.content.res.ColorStateList.valueOf(0xFFFFFFFF.toInt())
                elevation = dp(4).toFloat()
                contentDescription = context.getString(R.string.ts_pinned)
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(if (inc) 0xCC3B2B5E.toInt() else 0x8A000000.toInt())
                }
            })
        }

        // close X over the thumbnail (top-right, inside)
        card.addView(ImageButton(context).apply {
            setImageResource(R.drawable.ic_close)
            setPadding(dp(5), dp(5), dp(5), dp(5))
            layoutParams = FrameLayout.LayoutParams(dp(30), dp(30), Gravity.TOP or Gravity.END).apply {
                topMargin = dp(6); marginEnd = dp(6)
            }
            imageTintList = android.content.res.ColorStateList.valueOf(0xFFFFFFFF.toInt())
            elevation = dp(4).toFloat()
            contentDescription = context.getString(R.string.close)
            setOnClickListener { onClose?.invoke(tab) }
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(if (inc) 0xCC3B2B5E.toInt() else 0x8A000000.toInt())
            }
            try {
                foreground = RippleDrawable(
                    android.content.res.ColorStateList.valueOf(0x33FFFFFF), null, null)
            } catch (_: Exception) {}
        })

        // current tab marker: accent stroke (v1.5.0: honors the user's custom accent)
        if (isCurrent) {
            (card.background as GradientDrawable).setStroke(dp(2).coerceAtLeast(2),
                if (inc) 0xFFA78BFA.toInt() else Prefs.accentOrDefault(accentColor()))
        }
        return card
    }

    // ---------------- card content placeholders ----------------

    /**
     * v1.5.1: heavy pixel-blur of a screenshot — downscale to ~18px and upscale back
     * with bilinear filtering (classic trick, identical visual result to a Gaussian
     * blur at card size, works on API 24+ without RenderEffect).
     */
    private fun blurredShot(src: Bitmap): Bitmap = try {
        val w = 18
        val h = (src.height * (w.toFloat() / src.width)).toInt().coerceAtLeast(1)
        val small = Bitmap.createScaledBitmap(src, w, h, true)
        Bitmap.createScaledBitmap(small, src.width, src.height, true)
    } catch (_: Exception) { src }

    /**
     * v1.5.1: the unique Zephyr incognito badge drawn over blurred private previews —
     * a violet gradient disc with the hat-and-glasses mark and a soft glow ring.
     */
    private fun incognitoBadge(): View = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.CENTER)
        addView(android.widget.FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(74), dp(74))
            // soft glow ring
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0x2E9B8AF8.toInt())
            }
            addView(LinearLayout(context).apply {
                gravity = Gravity.CENTER
                layoutParams = android.widget.FrameLayout.LayoutParams(
                    android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                    android.widget.FrameLayout.LayoutParams.MATCH_PARENT).apply {
                    leftMargin = dp(5); topMargin = dp(5); rightMargin = dp(5); bottomMargin = dp(5)
                }
                background = GradientDrawable(
                    GradientDrawable.Orientation.TL_BR,
                    intArrayOf(0xFF7C5CE0.toInt(), 0xFF4A3488.toInt(), 0xFF2E1E4F.toInt())
                ).apply { shape = GradientDrawable.OVAL }
                clipToOutline = true
                addView(ImageView(context).apply {
                    setImageResource(R.drawable.ic_private)
                    imageTintList = android.content.res.ColorStateList.valueOf(0xFFFFFFFF.toInt())
                    layoutParams = LinearLayout.LayoutParams(dp(36), dp(36))
                    scaleType = ImageView.ScaleType.FIT_CENTER
                })
            })
        })
        addView(TextView(context).apply {
            text = context.getString(R.string.ts_private_badge)
            textSize = 11f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(0xFFEDE7FB.toInt())
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(8), dp(10), dp(4))
        })
    }

    /**
     * v1.5.0 FIX 3: branded HOME MOCK rendered inside a home-tab card when no live
     * screenshot exists (the CURRENT home tab gets a real capture — see
     * MainActivity.openTabSwitcher / TabManager.captureHomeThumbnail).
     * A search pill up top + a 2×3 grid of rounded quick-access tiles — reads as the
     * Zephyr home page at thumbnail size. The bottom scrim caption draws on top of it.
     */
    private fun homeMock(inc: Boolean, cardW: Int): View {
        // v1.7.5 (user: «у главной сплошной фон — сделай отрисовку пунктов»): the
        // mock now READS as the real home — search pill up top, round Zephyr mark,
        // a row of recent-site circles and two recent-item bars (the user's home
        // shows the recents section).
        val tileFill = if (inc) 0xCC3B2B5E.toInt()
        else (cardSurface() and 0x00FFFFFF) or (0x80 shl 24)
        val pillFill = if (inc) 0xFF3B2B5E.toInt()
        else (onSurfaceColor() and 0x00FFFFFF) or (0x1F000000).toInt()
        val accent = if (inc) 0xFFA78BFA.toInt() else accentColor()
        val markFill = (accent and 0x00FFFFFF) or (0x33 shl 24)

        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(14), dp(16), dp(14), dp(36))
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)

            // search pill (~62% of the card width, centered)
            addView(View(context).apply {
                layoutParams = LinearLayout.LayoutParams((cardW * 0.62f).toInt(), dp(18)).apply {
                    bottomMargin = dp(14)
                }
                background = GradientDrawable().apply {
                    cornerRadius = dp(9).toFloat()
                    setColor(pillFill)
                }
            })

            // round Zephyr mark ("Z" in an accent-tinted circle)
            addView(LinearLayout(context).apply {
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(dp(46), dp(46)).apply {
                    bottomMargin = dp(14)
                }
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(markFill)
                }
                addView(TextView(context).apply {
                    text = "Z"
                    textSize = 21f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(accent)
                    gravity = Gravity.CENTER
                })
            })

            // recent-sites row: 4 equal circles with gutters
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    bottomMargin = dp(10)
                }
                for (i in 0 until 4) {
                    addView(View(context).apply {
                        layoutParams = LinearLayout.LayoutParams(0, dp(30), 1f).apply {
                            marginStart = dp(4); marginEnd = dp(4)
                        }
                        background = GradientDrawable().apply {
                            shape = GradientDrawable.OVAL
                            setColor(tileFill)
                        }
                    })
                }
            })

            // two "recent item" bars (site + title placeholders)
            for (i in 0 until 2) {
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, dp(22)).apply {
                        topMargin = dp(5); bottomMargin = dp(5)
                    }
                    addView(View(context).apply {
                        layoutParams = LinearLayout.LayoutParams(dp(16), dp(16))
                        background = GradientDrawable().apply {
                            shape = GradientDrawable.OVAL
                            setColor(tileFill)
                        }
                    })
                    addView(View(context).apply {
                        layoutParams = LinearLayout.LayoutParams(0, dp(12), 1f).apply {
                            marginStart = dp(8)
                        }
                        background = GradientDrawable().apply {
                            cornerRadius = dp(6).toFloat()
                            setColor(tileFill)
                        }
                    })
                })
            }
        }
    }

    /** v1.7.5: tiny branded bitmap for the home card caption / placeholders. */
    private fun zephyrMark(): Bitmap {
        val s = 40
        val bmp = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        p.color = accentColor()
        c.drawCircle(s / 2f, s / 2f, s / 2f, p)
        p.color = 0xFFFFFFFF.toInt()
        p.textSize = s * 0.55f
        p.textAlign = android.graphics.Paint.Align.CENTER
        val fm = p.fontMetrics
        c.drawText("Z", s / 2f, s / 2f - (fm.ascent + fm.descent) / 2f, p)
        return bmp
    }

    /** Branded favicon placeholder for non-home tabs without a preview. */
    private fun faviconPlaceholder(tab: TabManager.Tab, inc: Boolean): View {
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            val iv = ImageView(context).apply {
                layoutParams = LinearLayout.LayoutParams(dp(52), dp(52))
                scaleType = ImageView.ScaleType.FIT_CENTER
            }
            iv.setImageBitmap(FaviconLoader.load(context, tab.pageUrlSnapshot, tab.title))
            addView(iv)
            addView(TextView(context).apply {
                text = if (tab.isHome) context.getString(R.string.nav_home)
                else com.zephyr.browser.Urls.domainOf(tab.pageUrlSnapshot).ifEmpty { tab.title }
                textSize = 11.5f
                maxLines = 1
                gravity = Gravity.CENTER
                setPadding(dp(6), dp(8), dp(6), 0)
                setTextColor(if (inc) 0xFFB7A7DA.toInt() else onSurfaceVariantColor())
            })
        }
    }

    // ---------------- chrome styling ----------------

    private fun styleChrome(shown: Int, regularCount: Int, privateCount: Int) {
        val inc = incognitoSection
        val headerFg = if (inc) 0xFFEDE7FB.toInt() else onSurfaceColor()
        val sub = if (inc) 0xFFB7A7DA.toInt() else onSurfaceVariantColor()
        val accent = if (inc) 0xFFA78BFA.toInt() else accentColor()
        val selBg = if (inc) 0xFF3B2B5E.toInt() else 0x1A8AB4F8
        val selFg = if (inc) 0xFFEDE7FB.toInt() else accent
        val fg = if (inc) 0xFFB7A7DA.toInt() else sub

        hdrTitle.setTextColor(headerFg)
        hdrCount.text = context.getString(R.string.ts_count_fmt, shown)
        hdrCount.setTextColor(sub)
        // v1.7.4: the header back arrow follows the section palette like the title
        btnBack.imageTintList = android.content.res.ColorStateList.valueOf(headerFg)
        btnCloseAll.imageTintList = android.content.res.ColorStateList.valueOf(sub)
        // v1.7.7: search + create-group controls follow the section palette
        btnSearch.imageTintList = android.content.res.ColorStateList.valueOf(sub)
        (groupActionRow.getChildAt(0) as? LinearLayout)?.let { pill ->
            (pill.getChildAt(0) as? ImageView)?.imageTintList =
                android.content.res.ColorStateList.valueOf(accent)
            (pill.getChildAt(1) as? TextView)?.setTextColor(headerFg)
            pill.background = com.zephyr.browser.settings.SettingsUi.rounded(context, dp(20))
        }

        fun styleTab(t: LinearLayout, icon: ImageButton, count: TextView, selected: Boolean) {
            t.background = GradientDrawable().apply {
                cornerRadius = dp(18).toFloat()
                setColor(if (selected) selBg else 0x00000000)
            }
            icon.imageTintList = android.content.res.ColorStateList.valueOf(if (selected) selFg else fg)
            count.setTextColor(if (selected) selFg else fg)
            count.typeface = if (selected) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        }
        styleTab(tabRegular, tabRegularIcon, tabRegularCount, !inc)
        styleTab(tabPrivate, tabPrivateIcon, tabPrivateCount, inc)
        // v1.5.0: section switch accessibility labels
        tabRegular.contentDescription = context.getString(R.string.ts_section_regular)
        tabPrivate.contentDescription = context.getString(R.string.ts_section_private)
        tabRegularCount.text = regularCount.toString()
        tabPrivateCount.text = privateCount.toString()

        // empty state tint
        (emptyView.getChildAt(1) as? TextView)?.setTextColor(sub)
        (emptyView.getChildAt(0) as? ImageView)?.imageTintList =
            android.content.res.ColorStateList.valueOf(if (inc) 0xFFB7A7DA.toInt() else sub)
    }

    private fun styleFab() {
        fab.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            orientation = GradientDrawable.Orientation.TL_BR
            // v1.5.0: custom accent (Prefs) drives the regular-section FAB gradient
            colors = if (incognitoSection) intArrayOf(0xFF6D28D9.toInt(), 0xFFA78BFA.toInt())
            else {
                val acc = Prefs.accentOrDefault(accentColor())
                intArrayOf(acc, androidx.core.graphics.ColorUtils.blendARGB(acc, 0xFFFFFFFF.toInt(), 0.30f))
            }
        }
    }

    // ---------------- palette ----------------

    private fun sectionBg(incognito: Boolean): Int =
        if (incognito) 0xFF1B1230.toInt() else themeColor(android.R.attr.windowBackground, 0xFFF7F8FA.toInt())

    private fun cardSurface(): Int =
        themeColor(com.google.android.material.R.attr.colorSurfaceVariant, 0xFFEFF1F3.toInt())

    private fun surfaceColor(): Int =
        themeColor(com.google.android.material.R.attr.colorSurface, 0xFFFFFFFF.toInt())

    private fun accentColor(): Int = themeColor(R.attr.colorPrimary, 0xFF1A73E8.toInt())

    private fun circularRipple(radiusDp: Int): android.graphics.drawable.Drawable? = try {
        val mask = GradientDrawable().apply {
            if (radiusDp > 0) cornerRadius = dp(radiusDp).toFloat()
            else shape = GradientDrawable.OVAL
            setColor(0xFFFFFFFF.toInt())
        }
        RippleDrawable(android.content.res.ColorStateList.valueOf(0x1F888888.toInt() or 0xFF000000.toInt()), null, mask)
    } catch (_: Exception) { null }

    private fun tabManager(): TabManager? = try {
        (context as? com.zephyr.browser.MainActivity)?.tabs
    } catch (_: Exception) { null }

    private fun onSurfaceColor(): Int = themeColor(R.attr.colorOnSurface, 0xFF202124.toInt())
    private fun onSurfaceVariantColor(): Int = themeColor(R.attr.colorOnSurfaceVariant, 0xFF5F6368.toInt())

    private fun themeColor(attr: Int, fallback: Int): Int = try {
        val ta = context.theme.obtainStyledAttributes(intArrayOf(attr))
        val c = ta.getColor(0, fallback)
        ta.recycle()
        c
    } catch (_: Exception) { fallback }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()
}
