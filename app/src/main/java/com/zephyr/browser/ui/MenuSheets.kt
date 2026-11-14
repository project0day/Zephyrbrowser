package com.zephyr.browser.ui

import android.annotation.SuppressLint
import android.app.Dialog
import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.speech.tts.TextToSpeech
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.zephyr.browser.AdBlockManager
import com.zephyr.browser.MainActivity
import com.zephyr.browser.Prefs
import com.zephyr.browser.R
import com.zephyr.browser.Scripts
import com.zephyr.browser.Urls
import com.zephyr.browser.db.AppDb
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * All bottom-sheet menus: main menu (gradient ad-block banner), rich long-press context menu
 * (with icons), site-info sheet with per-site settings, fullscreen reader.
 *
 * Sheet insets: every sheet content root applies window insets (status bar top + nav bar bottom)
 * as padding and is height-capped, so rows NEVER slip under the status bar at any drag state
 * and never get clipped at half-expanded (fixes the v1.2.1 drag bug).
 */
object MenuSheets {

    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private const val APPEARANCES = 6

    // =====================================================================
    // Main menu
    // =====================================================================

    fun showMainMenu(activity: MainActivity) {
        // v1.4.2 FIX 2: NO outer elevation shadow at all (user request — the popup must sit
        // flat on the page). Depth is suggested by two THIN inner gradients drawn inside
        // the rounded card: a soft light line along the top (#26FFFFFF → transparent) and
        // a soft shade line along the bottom (transparent → #14000000).
        val cornerDp = dp(activity, 20)
        fun cardBg() = GradientDrawable().apply {
            // v1.7.23 (user: «попап меню во всех темах… сливается с фоном»): the ⋮ menu
            // on the main screen is THIS PopupWindow — and it used to be painted with
            // zBackground, the SAME attr as the screen behind it, so it merged into the
            // page 1:1 in every theme. v1.7.22 lightened only the small system popups.
            // Now the main menu takes zPopupBg — the theme's own color lightened ~3.5%,
            // same hue, NO outline stroke — in every theme.
            setColor(themedColor(activity, R.attr.zPopupBg))
            cornerRadius = cornerDp.toFloat()
        }
        val root = android.widget.FrameLayout(activity).apply {
            background = cardBg()
            clipToOutline = true   // children can no longer poke over the rounded corners
        }
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
        }

        val maxH = (activity.resources.displayMetrics.heightPixels - dp(activity, 130)).coerceAtLeast(dp(activity, 200))
        val scroll = object : ScrollView(activity) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                val limited = MeasureSpec.makeMeasureSpec(maxH, MeasureSpec.AT_MOST)
                super.onMeasure(widthMeasureSpec, limited)
            }
        }.apply {
            // v1.5.0 FIX (bug 9): vertical scrollbar DISABLED — ScrollView reserves space for
            // its scrollbar, which made the popup wider than its content with a dead gap on
            // the right (right of the anchor button). No scrollbar → no reserved inset.
            isVerticalScrollBarEnabled = false
            // 276 exactly matches the PopupWindow width below (was dp(280) inside a
            // WRAP_CONTENT popup measured WITH the scrollbar reserve → extra width).
            layoutParams = LinearLayout.LayoutParams(
                dp(activity, 276), ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        val col = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(activity, 8), 0, dp(activity, 8))
        }
        scroll.addView(col)
        content.addView(scroll)
        root.addView(content, android.widget.FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // --- v1.4.2 FIX 2: the two soft inner light/shade lines (non-interactive overlays) ---
        root.addView(View(activity).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(0x26FFFFFF.toInt(), 0x00FFFFFF.toInt())
            ).apply { cornerRadius = cornerDp.toFloat() }
            isClickable = false
        }, android.widget.FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(activity, 26), Gravity.TOP))
        root.addView(View(activity).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(0x00000000.toInt(), 0x14000000.toInt())
            ).apply { cornerRadius = cornerDp.toFloat() }
            isClickable = false
        }, android.widget.FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(activity, 22), Gravity.BOTTOM))

        val popup = android.widget.PopupWindow(activity).apply {
            contentView = root
            // v1.5.0 FIX (bug 9): EXPLICIT width (was WRAP_CONTENT — the measure pass
            // included the ScrollView's scrollbar reserve, so the card looked too wide).
            width = dp(activity, 276)
            height = ViewGroup.LayoutParams.WRAP_CONTENT
            isFocusable = true
            // v1.4.2 FIX 2: elevation explicitly ZERO — no outer shadow under any theme
            elevation = 0f
            // rounded drawable → outside-tap dismissal still works, no shadow drawn
            setBackgroundDrawable(cardBg())
        }
        val dismiss: () -> Unit = {
            // v1.5.1: graceful EXIT — the card scales/fades back into the anchor corner
            // BEFORE the actual dismissal, instead of vanishing in one frame (was the
            // abrupt "clunky" dismissal). Outside-tap dismissals stay instant.
            root.animate().cancel()
            root.animate().alpha(0f).scaleX(0.96f).scaleY(0.96f)
                .setDuration(130)
                .setInterpolator(android.view.animation.DecelerateInterpolator(1.4f))
                .withEndAction { popup.dismiss() }
                .start()
        }

        val tab = activity.tabs.current

        // ---- action grid: 1.2.1 reference — colored rounded tiles with white icons ----
        // v1.5.1: tile order (and which tiles are shown) comes from the user's saved
        // order (Settings → Меню) — fully customizable.
        // v1.7.0 UNIFIED TILES ("почему на горизонтальный ряд я не могу поставить
        // опцию перевести страницу?"): the horizontal row now accepts ANY menu
        // action — the full id set of the vertical rows (+ the two tab actions +
        // DNS). ONE shared spec map with ONE entry per id; history/downloads/
        // bookmarks keep the same panel-opening action in both sections.
        fun openSettingsScreenId(screen: String) = openSettingsScreen(activity, screen)
        val actionSpecs: Map<String, Triple<Int, Int, () -> Unit>> = mapOf(
            // tab actions (tile-born)
            "newtab" to Triple(R.drawable.ic_add, R.string.menu_new_tab) { activity.tabs.newTab(Urls.DEFAULT_HOME, false) },
            "private" to Triple(R.drawable.ic_private, R.string.menu_new_private) { activity.tabs.newTab(Urls.DEFAULT_HOME, true) },
            // v1.6.0 core rows — as a tile they simply run the action
            // ("desktop" loses its Switch and just toggles — acceptable as tile)
            "passwords" to Triple(R.drawable.ic_key, R.string.menu_passwords) { activity.openPanel("passwords") },
            "scripts" to Triple(R.drawable.ic_script, R.string.menu_scripts) { activity.openPanel("scripts") },
            // v1.7.54: Дела — календарь с напоминаниями
            "tasks" to Triple(R.drawable.ic_calendar, R.string.panel_tasks) { activity.openPanel("tasks") },
            "workspace" to Triple(R.drawable.ic_folder_open, R.string.panel_workspace) { activity.openPanel("workspace") },
            "workflows" to Triple(R.drawable.ic_script, R.string.panel_workflows) { activity.openPanel("workflows") },
            "templates" to Triple(R.drawable.ic_file, R.string.panel_templates) { activity.openPanel("templates") },
            "autofill" to Triple(R.drawable.ic_user, R.string.menu_autofill) { activity.openPanel("autofill") },
            "payments" to Triple(R.drawable.ic_desktop, R.string.menu_payments) { activity.openPanel("payments") },
            "desktop" to Triple(R.drawable.ic_desktop, R.string.menu_desktop) { activity.toggleDesktopMode() },
            "find" to Triple(R.drawable.ic_search, R.string.menu_find) { activity.showFindBar() },
            "addbookmark" to Triple(R.drawable.ic_star_add, R.string.menu_add_bookmark) { activity.addBookmark() },
            // v1.7.3: "add to quick access" — home-screen quick tile from the site menu.
            "addquick" to Triple(R.drawable.ic_grid, R.string.menu_add_quick) { activity.addToQuickAccess() },
            "translate" to Triple(R.drawable.ic_translate, R.string.menu_translate) { activity.translateCurrent() },
            "reader" to Triple(R.drawable.ic_reader, R.string.menu_reader) { activity.openReader() },
            "addhomescreen" to Triple(R.drawable.ic_add_homescreen, R.string.menu_add_home) { activity.addToHomeScreen() },
            "share" to Triple(R.drawable.ic_share, R.string.menu_share) { activity.shareCurrent() },
            "copylink" to Triple(R.drawable.ic_copy, R.string.menu_copy_link) { activity.copyCurrentLink() },
            "external" to Triple(R.drawable.ic_external, R.string.menu_open_external) { activity.openInExternalBrowser() },
            "clearsite" to Triple(R.drawable.ic_delete, R.string.menu_clear_site) { activity.clearSiteDataCurrent() },
            "closeall" to Triple(R.drawable.ic_close, R.string.ts_close_all) { confirmCloseAll(activity) },
            "appearance" to Triple(R.drawable.ic_palette, R.string.menu_page_appearance) { showAppearancePicker(activity) },
            "settings" to Triple(R.drawable.ic_settings, R.string.menu_settings) { openSettingsScreen(activity, null) },
            // v1.6.0 extended rows
            "vpn" to Triple(R.drawable.ic_vpn, R.string.menu_vpn) { openSettingsScreenId("proxy") },
            "adblock" to Triple(R.drawable.ic_block, R.string.menu_adblock) { openSettingsScreenId("adblock") },
            "privacy" to Triple(R.drawable.ic_shield, R.string.menu_privacy) { openSettingsScreenId("privacy") },
            "site" to Triple(R.drawable.ic_website, R.string.menu_site_settings) { openSettingsScreenId("site") },
            "history" to Triple(R.drawable.ic_history, R.string.menu_history) { activity.openPanel("history") },
            "downloads" to Triple(R.drawable.ic_download, R.string.menu_downloads) { activity.openPanel("downloads") },
            "bookmarks" to Triple(R.drawable.ic_bookmark, R.string.menu_bookmarks) { activity.openPanel("bookmarks") },
            "ai" to Triple(R.drawable.ic_ai, R.string.ai_chat) { activity.openAiChat(com.zephyr.browser.Prefs.engineId) },
            // v1.7.76: photograph something and tell the assistant what to do with it
            "camai" to Triple(R.drawable.ic_camera, R.string.cam_title) { activity.openAiCamera() },
            "voice" to Triple(R.drawable.ic_mic, R.string.voice_search) { activity.startVoiceSearch() },
            "imagesearch" to Triple(R.drawable.ic_camera, R.string.image_search) { activity.openImageSearch(com.zephyr.browser.Prefs.engineId) },
            "source" to Triple(R.drawable.ic_script, R.string.page_source) { activity.showPageSource() },
            "screenshot" to Triple(R.drawable.ic_camera, R.string.menu_screenshot) { activity.captureScreenshot() },
            "pdf" to Triple(R.drawable.ic_download, R.string.menu_save_pdf) { activity.saveAsPdf() },
            // v1.7.0 new: built-in DNS settings screen (deep-linked by id, like "vpn"→proxy)
            "dns" to Triple(R.drawable.ic_globe, R.string.menu_dns) { openSettingsScreenId("dns") },
            // v1.7.56: burn and the offline pair were rows only — now they can sit
            // on the horizontal tile strip too, which is where a panic button belongs
            "burn" to Triple(R.drawable.ic_fire, R.string.burn_title) { activity.burnEverything() },
            "saveoffline" to Triple(R.drawable.ic_download, R.string.menu_save_offline) { activity.saveOffline() },
            "offline" to Triple(R.drawable.ic_reader, R.string.set_offline) { activity.openPanel("offline") },
            // v1.7.7: files app / QR scanner / site styles — pinnable like every other action
            "files" to Triple(R.drawable.ic_folder_open, R.string.files_title) {
                // v1.7.12: mark the launch attempt — if FilesActivity dies before its
                // first line (class load / super.onCreate), MainActivity's resume check
                // surfaces the crash report instead of a silent bounce back.
                try { java.io.File(activity.filesDir, "files_launch_probe.txt")
                    .writeText(System.currentTimeMillis().toString()) } catch (_: Throwable) {}
                activity.startActivity(android.content.Intent(activity, com.zephyr.browser.files.FilesActivity::class.java))
                @Suppress("DEPRECATION")
                activity.overridePendingTransition(R.anim.panel_in, R.anim.hold)
            },
            "qr" to Triple(R.drawable.ic_qr, R.string.qr_title) {
                activity.startActivity(android.content.Intent(activity, com.zephyr.browser.ui.QrScanActivity::class.java))
                @Suppress("DEPRECATION")
                activity.overridePendingTransition(R.anim.panel_in, R.anim.hold)
            },
            "styles" to Triple(R.drawable.ic_palette, R.string.menu_styles) { activity.openSiteStyles() },
            // v1.7.8: the video sniffer entry — no more floating pill, the sheet
            // lives here (user: «поставь в попап функцию с возможностью убрать сниффер»)
            "video" to Triple(R.drawable.ic_video, R.string.sniff_menu) { activity.showVideoSheet() },
            // v1.7.17: floating player — shrink the CURRENT tab's live WebView
            // into a draggable mini window and keep browsing (music/video keeps
            // playing); the row is injected into the saved order below so it is
            // visible by default right after the sniffer row.
            "floating" to Triple(R.drawable.ic_float_player, R.string.fl_menu) { activity.floatCurrentTab() }
        )
        /** 1.2.1-style quick action tile: colored circular badge + white icon + label.
         *  [fixedWidth] = true → fixed 54dp cell (scrollable >5 strip); false → equal
         *  weight cell (the classic ≤5 single row). Visual style is identical. */
        fun tileTo(row: LinearLayout, iconRes: Int, labelRes: Int, onClick: () -> Unit, fixedWidth: Boolean = false) {
            val item = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(activity, 2), dp(activity, 8), dp(activity, 2), dp(activity, 8))
                layoutParams = LinearLayout.LayoutParams(
                    if (fixedWidth) dp(activity, 54) else 0,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    if (fixedWidth) 0f else 1f)
                isClickable = true
                foreground = ripple(activity)
                setOnClickListener { dismiss(); onClick() }
            }
            item.addView(LinearLayout(activity).apply {
                gravity = Gravity.CENTER
                background = GradientDrawable().apply {
                    setColor(if (tab?.incognito == true) 0xFF6E5A96.toInt() else themedColor(activity, R.attr.colorPrimary))
                    shape = GradientDrawable.OVAL
                }
                layoutParams = LinearLayout.LayoutParams(dp(activity, 44), dp(activity, 44))
                addView(ImageView(activity).apply {
                    setImageResource(iconRes)
                    layoutParams = LinearLayout.LayoutParams(dp(activity, 22), dp(activity, 22))
                    imageTintList = android.content.res.ColorStateList.valueOf(0xFFFFFFFF.toInt())
                })
            })
            item.addView(TextView(activity).apply {
                text = activity.getString(labelRes)
                textSize = 10f
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                gravity = Gravity.CENTER
                setTextColor(themedColor(activity, R.attr.colorOnSurface))
                setPadding(0, dp(activity, 4), 0, 0)
            })
            row.addView(item)
        }
        // unknown ids in the saved order keep being filtered (specs[id]?.let pattern)
        val tileOrder = Prefs.menuTilesOrder.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        val visibleTiles = tileOrder.mapNotNull { id -> actionSpecs[id]?.let { id to it } }
        if (visibleTiles.size <= 5) {
            // classic look: ONE equal-weight row of up to 5 tiles (unchanged style)
            val r1 = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(dp(activity, 8), dp(activity, 4), dp(activity, 8), dp(activity, 4))
            }
            col.addView(r1)
            for ((_, spec) in visibleTiles) tileTo(r1, spec.first, spec.second, spec.third)
        } else {
            // v1.7.0: more than 5 pinned tiles → the strip SCROLLS horizontally.
            // Cells switch to fixed 54dp width so the content width is deterministic
            // and overflows into the scroll; the HorizontalScrollView itself stays
            // MATCH_PARENT inside the fixed 276dp popup (scrollbar disabled → no
            // width reserve → the popup NEVER grows wider, mirroring the v1.5.0
            // vertical-scrollbar fix). Tile visuals are pixel-identical.
            val strip = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(dp(activity, 8), dp(activity, 4), dp(activity, 8), dp(activity, 4))
            }
            for ((_, spec) in visibleTiles) tileTo(strip, spec.first, spec.second, spec.third, fixedWidth = true)
            val hsv = HorizontalScrollView(activity).apply {
                isHorizontalScrollBarEnabled = false
                isVerticalScrollBarEnabled = false
                addView(strip, ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            }
            col.addView(hsv, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }

        // separator
        col.addView(View(activity).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1).apply {
                setMargins(0, dp(activity, 4), 0, dp(activity, 4))
            }
            setBackgroundColor(themedColor(activity, R.attr.colorOutline))
            alpha = 0.5f
        })

        // ---- full option list (1.2.1 parity; v1.5.1: order + visibility from settings) ----
        fun desktopSwitchRow(): View {
            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(activity, 16), dp(activity, 12), dp(activity, 16), dp(activity, 12))
                isClickable = true
                foreground = ripple(activity)
                setOnClickListener { dismiss(); activity.toggleDesktopMode() }
            }
            row.addView(ImageView(activity).apply {
                setImageResource(R.drawable.ic_desktop)
                layoutParams = LinearLayout.LayoutParams(dp(activity, 22), dp(activity, 22))
                imageTintList = android.content.res.ColorStateList.valueOf(themedColor(activity, R.attr.colorOnSurfaceVariant))
            })
            row.addView(TextView(activity).apply {
                text = activity.getString(R.string.menu_desktop)
                textSize = 14f
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = dp(activity, 16)
                }
                setTextColor(themedColor(activity, R.attr.colorOnSurface))
            })
            row.addView(Switch(activity).apply {
                isChecked = tab?.desktop == true
                isClickable = false
                isFocusable = false
            })
            return row
        }
        val rowSpecs: Map<String, () -> View> = mapOf(
            "passwords" to { menuRow(activity, dismiss, R.drawable.ic_key, R.string.menu_passwords) { activity.openPanel("passwords") } },
            "scripts" to { menuRow(activity, dismiss, R.drawable.ic_script, R.string.menu_scripts) { activity.openPanel("scripts") } },
            "tasks" to { menuRow(activity, dismiss, R.drawable.ic_calendar, R.string.menu_tasks) { activity.openPanel("tasks") } },
            "workspace" to { menuRow(activity, dismiss, R.drawable.ic_folder_open, R.string.panel_workspace) { activity.openPanel("workspace") } },
            "workflows" to { menuRow(activity, dismiss, R.drawable.ic_script, R.string.panel_workflows) { activity.openPanel("workflows") } },
            "templates" to { menuRow(activity, dismiss, R.drawable.ic_file, R.string.panel_templates) { activity.openPanel("templates") } },
            "autofill" to { menuRow(activity, dismiss, R.drawable.ic_user, R.string.menu_autofill) { activity.openPanel("autofill") } },
            "payments" to { menuRow(activity, dismiss, R.drawable.ic_desktop, R.string.menu_payments) { activity.openPanel("payments") } },
            "desktop" to { desktopSwitchRow() },
            "find" to { menuRow(activity, dismiss, R.drawable.ic_search, R.string.menu_find) { activity.showFindBar() } },
            "addbookmark" to { menuRow(activity, dismiss, R.drawable.ic_star_add, R.string.menu_add_bookmark) { activity.addBookmark() } },
            // v1.7.3: "add to quick access" row (same action as the tile above).
            "addquick" to { menuRow(activity, dismiss, R.drawable.ic_grid, R.string.menu_add_quick) { activity.addToQuickAccess() } },
            "translate" to { menuRow(activity, dismiss, R.drawable.ic_translate, R.string.menu_translate) { activity.translateCurrent() } },
            "reader" to { menuRow(activity, dismiss, R.drawable.ic_reader, R.string.menu_reader) { activity.openReader() } },
            // v1.7.6: offline reading — save the current page as an archive, and the
            // saved-pages list as its own row (also in Settings → Данные и панели).
            "saveoffline" to { menuRow(activity, dismiss, R.drawable.ic_download, R.string.menu_save_offline) { activity.saveOffline() } },
            "offline" to { menuRow(activity, dismiss, R.drawable.ic_reader, R.string.set_offline) { activity.openPanel("offline") } },
            "addhomescreen" to { menuRow(activity, dismiss, R.drawable.ic_add_homescreen, R.string.menu_add_home) { activity.addToHomeScreen() } },
            "share" to { menuRow(activity, dismiss, R.drawable.ic_share, R.string.menu_share) { activity.shareCurrent() } },
            "copylink" to { menuRow(activity, dismiss, R.drawable.ic_copy, R.string.menu_copy_link) { activity.copyCurrentLink() } },
            "external" to { menuRow(activity, dismiss, R.drawable.ic_external, R.string.menu_open_external) { activity.openInExternalBrowser() } },
            "clearsite" to { menuRow(activity, dismiss, R.drawable.ic_delete, R.string.menu_clear_site) { activity.clearSiteDataCurrent() } },
            "closeall" to { menuRow(activity, dismiss, R.drawable.ic_close, R.string.ts_close_all) { confirmCloseAll(activity) } },
            "appearance" to { menuRow(activity, dismiss, R.drawable.ic_palette, R.string.menu_page_appearance) { showAppearancePicker(activity) } },
            // v1.7.8: video-from-page row (only useful on a real page, still fine anywhere)
            "video" to { menuRow(activity, dismiss, R.drawable.ic_video, R.string.sniff_menu) { activity.showVideoSheet() } },
            "camai" to { menuRow(activity, dismiss, R.drawable.ic_camera, R.string.cam_title) { activity.openAiCamera() } },
            // v1.7.17: floating-player row — toggle the current tab into the mini
            // window / back (see floatCurrentTab); armored there, toast if no media.
            "floating" to { menuRow(activity, dismiss, R.drawable.ic_float_player, R.string.fl_menu) { activity.floatCurrentTab() } },
            "settings" to { menuRow(activity, dismiss, R.drawable.ic_settings, R.string.menu_settings) {
                openSettingsScreen(activity, null)
            } }
        )

        // ---- v1.6.0: the extended row set — every browser capability the user asked
        // for ("в вертикальных пунктах нет VPN, прокси и других опций браузера") is now
        // a menu row available in the Меню кастомизации screen.
        // (openSettingsScreenId is defined above, next to actionSpecs.)
        val extraRowSpecs: Map<String, () -> View> = mapOf(
            "vpn" to { menuRow(activity, dismiss, R.drawable.ic_vpn, R.string.menu_vpn) { openSettingsScreenId("proxy") } },
            // v1.7.0: built-in DNS settings screen as a row (may ALSO be a tile — the
            // user's choice; ids may deliberately appear in both sections)
            "dns" to { menuRow(activity, dismiss, R.drawable.ic_globe, R.string.menu_dns) { openSettingsScreenId("dns") } },
            "adblock" to { menuRow(activity, dismiss, R.drawable.ic_block, R.string.menu_adblock) { openSettingsScreenId("adblock") } },
            "privacy" to { menuRow(activity, dismiss, R.drawable.ic_shield, R.string.menu_privacy) { openSettingsScreenId("privacy") } },
            "site" to { menuRow(activity, dismiss, R.drawable.ic_website, R.string.menu_site_settings) { openSettingsScreenId("site") } },
            "history" to { menuRow(activity, dismiss, R.drawable.ic_history, R.string.menu_history) { activity.openPanel("history") } },
            "downloads" to { menuRow(activity, dismiss, R.drawable.ic_download, R.string.menu_downloads) { activity.openPanel("downloads") } },
            "bookmarks" to { menuRow(activity, dismiss, R.drawable.ic_bookmark, R.string.menu_bookmarks) { activity.openPanel("bookmarks") } },
            "ai" to { menuRow(activity, dismiss, R.drawable.ic_ai, R.string.ai_chat) { activity.openAiChat(com.zephyr.browser.Prefs.engineId) } },
            "voice" to { menuRow(activity, dismiss, R.drawable.ic_mic, R.string.voice_search) { activity.startVoiceSearch() } },
            "imagesearch" to { menuRow(activity, dismiss, R.drawable.ic_camera, R.string.image_search) { activity.openImageSearch(com.zephyr.browser.Prefs.engineId) } },
            "source" to { menuRow(activity, dismiss, R.drawable.ic_script, R.string.page_source) { activity.showPageSource() } },
            "screenshot" to { menuRow(activity, dismiss, R.drawable.ic_camera, R.string.menu_screenshot) { activity.captureScreenshot() } },
            "pdf" to { menuRow(activity, dismiss, R.drawable.ic_download, R.string.menu_save_pdf) { activity.saveAsPdf() } },
            // v1.7.38: DuckDuckGo-style fire button
            "burn" to { menuRow(activity, dismiss, R.drawable.ic_fire, R.string.burn_title) { activity.burnEverything() } }
        )
        val allRowSpecs = rowSpecs + extraRowSpecs
        // v1.7.17: one-time — surface the «Плавающий плеер» row for every install
        // whose saved order predates it, right next to the video-sniffer row (the
        // v1.7.8 "video" row shipped through a Prefs.kt migration; Prefs.kt is
        // coordinator-owned this round, so the injection lives here instead).
        // Flag-guarded through the persisted hints set, mirroring the flag-guarded
        // v1.7.6 offline migration: strictly one shot, so a user who later removes
        // the row in Settings → Меню keeps it removed.
        var rowOrder = Prefs.menuRowsOrder.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        if (!Prefs.isHintDismissed("menu_v1717_floating")) {
            if (!rowOrder.contains("floating")) {
                val order = rowOrder.toMutableList()
                when {
                    order.contains("video") -> order.add(order.indexOf("video") + 1, "floating")
                    order.contains("settings") -> order.add(order.indexOf("settings"), "floating")
                    else -> order.add("floating")
                }
                Prefs.setMenuRowsOrder(order.joinToString(","))
                rowOrder = order
            }
            Prefs.dismissHint("menu_v1717_floating")
        }
        // v1.7.38: one-time — surface the fire button for existing installs
        if (!Prefs.isHintDismissed("menu_v1738_burn")) {
            if (!rowOrder.contains("burn")) {
                val order = rowOrder.toMutableList()
                if (order.contains("clearsite")) order.add(order.indexOf("clearsite"), "burn")
                else order.add("burn")
                Prefs.setMenuRowsOrder(order.joinToString(","))
                rowOrder = order
            }
            Prefs.dismissHint("menu_v1738_burn")
        }
        // v1.7.29: the saved order is already grouped by meaning (see the
        // default in Prefs.menuRowsOrder); v1.7.30 dropped the printed group
        // captions — the user found them clunky («убери! это топорно»).
        for (id in rowOrder) {
            allRowSpecs[id]?.let { col.addView(it()) }
        }

        val anchor = activity.findViewById<View>(R.id.btnMenu)
        // v1.7.32 (user: «при вызове меню оно вниз открывается под панель
        // навигации, а должно вверх»): with the search bar moved to the BOTTOM
        // the menu button sits at the bottom of the screen, and a plain
        // showAsDropDown() still dropped the card downwards — straight under the
        // gesture bar. Measure what is actually available and flip upwards when
        // the space below is not enough.
        var openedUpwards = false
        if (anchor != null) {
            val loc = IntArray(2)
            anchor.getLocationOnScreen(loc)
            val screenH = activity.resources.displayMetrics.heightPixels
            val below = screenH - (loc[1] + anchor.height)
            val above = loc[1]

            root.measure(
                View.MeasureSpec.makeMeasureSpec(dp(activity, 276), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            )
            val needed = root.measuredHeight + dp(activity, 16)

            if (below < needed && above > below) {
                openedUpwards = true
                val height = minOf(needed, above - dp(activity, 8))
                popup.height = height
                // negative yoff lifts the card fully above the button
                popup.showAsDropDown(anchor, 0, -(height + anchor.height + dp(activity, 4)), Gravity.END)
            } else {
                popup.showAsDropDown(anchor, 0, dp(activity, 4), Gravity.END)
            }
        } else {
            popup.showAtLocation(activity.window.decorView, Gravity.TOP or Gravity.END, dp(activity, 8), dp(activity, 54))
        }
        // v1.5.1: layered entrance — the card grows out of the anchor corner, then the
        // rows cascade in (material-emphasized-decelerate feel, much smoother than the
        // old single pop).
        root.post {
            root.pivotX = root.width.toFloat()
            // grow from the corner the card is actually anchored to
            root.pivotY = if (openedUpwards) root.height.toFloat() else 0f
            Anim.popIn(root, 200, 0, 0.94f)
            Anim.stagger(col, stepMs = 16, duration = 200)
        }
    }

    /** v1.6.0: deep-link into a Settings screen (null = root) with the slide-up transition. */
    private fun openSettingsScreen(activity: MainActivity, screen: String?) {
        activity.startActivity(android.content.Intent(activity, com.zephyr.browser.settings.SettingsActivity::class.java).apply {
            if (screen != null) putExtra(com.zephyr.browser.settings.SettingsActivity.EXTRA_SCREEN, screen)
        })
        // v1.5.0: smooth slide-up transition into Settings instead of a hard cut
        @Suppress("DEPRECATION")
        activity.overridePendingTransition(R.anim.panel_in, R.anim.hold)
    }

    /** v1.5.1: "Close all" now asks first — closing every tab in the section is a
     *  destructive action (a misclick used to wipe the whole session instantly). */
    private fun confirmCloseAll(activity: MainActivity) {
        val inc = activity.tabs.current?.incognito == true
        val count = if (inc) activity.tabs.privateTabs().size else activity.tabs.regularTabs().size
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.ts_close_all)
            .setMessage(activity.getString(R.string.ts_close_all_confirm, count))
            .setPositiveButton(R.string.delete) { _, _ ->
                val closing = if (inc) activity.tabs.privateTabs() else activity.tabs.regularTabs()
                for (t in closing.toList()) activity.tabs.closeTab(t, selectReplacement = false)
                if (activity.tabs.current == null) activity.tabs.newTab(Urls.DEFAULT_HOME, com.zephyr.browser.Prefs.alwaysIncognito)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** List row that auto-dismisses the sheet before running the action. */
    private fun menuRow(activity: MainActivity, dismiss: () -> Unit, iconRes: Int, labelRes: Int, onClick: () -> Unit): View =
        row(activity, iconRes, labelRes) {
            dismiss(); onClick()
        }

    /** Page appearance picker (light / dark / sepia / gray / contrast).
     *  v1.7.5: delegates to MainActivity — the picker is now PER-SITE (the choice is
     *  stored for the current domain; "как в настройках" resets to the global). */
    fun showAppearancePicker(activity: MainActivity) {
        activity.showAppearancePicker()
    }

    // =====================================================================
    // Context menu (long-press) — rich, with icons
    // =====================================================================

    /** Pending link URL for image-anchor hits (set via requestFocusNodeHref). */
    private var pendingImageLink: String? = null

    fun showContextMenu(
        activity: MainActivity,
        url: String,
        isImage: Boolean,
        isLink: Boolean,
        action: (String, String, String?) -> Unit
    ) {
        val items = mutableListOf<Triple<Int, String, () -> Unit>>()
        val link = pendingImageLink
        if (isLink && !isImage) {
            items.add(Triple(R.drawable.ic_open_new, activity.getString(R.string.ctx_open)) { action("open", url, link) })
            items.add(Triple(R.drawable.ic_tabs, activity.getString(R.string.ctx_open_new)) { action("open_new", url, link) })
            items.add(Triple(R.drawable.ic_open_bg, activity.getString(R.string.ctx_open_bg)) { action("open_bg", url, link) })
            items.add(Triple(R.drawable.ic_private, activity.getString(R.string.ctx_open_private)) { action("open_private", url, link) })
            items.add(Triple(R.drawable.ic_download, activity.getString(R.string.ctx_download)) { action("download", url, link) })
            items.add(Triple(R.drawable.ic_copy, activity.getString(R.string.ctx_copy)) { action("copy", url, link) })
            items.add(Triple(R.drawable.ic_share, activity.getString(R.string.ctx_share)) { action("share", url, link) })
            // v1.7.57: a link you cannot deal with now becomes a reminder that
            // opens it later — the alarm side already existed, this is the way in
            items.add(Triple(R.drawable.ic_calendar, activity.getString(R.string.ctx_remind)) { action("remind", url, link) })
        } else if (isImage && !isLink) {
            items.add(Triple(R.drawable.ic_image, activity.getString(R.string.ctx_open_image)) { action("open", url, null) })
            items.add(Triple(R.drawable.ic_widget_camera, activity.getString(R.string.ctx_search_image)) { action("search_image", url, null) })
            items.add(Triple(R.drawable.ic_download, activity.getString(R.string.ctx_save_image)) { action("download", url, null) })
            items.add(Triple(R.drawable.ic_copy, activity.getString(R.string.ctx_copy)) { action("copy", url, null) })
            items.add(Triple(R.drawable.ic_share, activity.getString(R.string.ctx_share_image)) { action("share", url, null) })
        } else if (isImage && isLink) {
            items.add(Triple(R.drawable.ic_open_new, activity.getString(R.string.ctx_open)) { action("open", link ?: url, null) })
            items.add(Triple(R.drawable.ic_tabs, activity.getString(R.string.ctx_open_new)) { action("open_new", link ?: url, null) })
            items.add(Triple(R.drawable.ic_private, activity.getString(R.string.ctx_open_private)) { action("open_private", link ?: url, null) })
            items.add(Triple(R.drawable.ic_download, activity.getString(R.string.ctx_download)) { action("download", link ?: url, null) })
            items.add(Triple(R.drawable.ic_copy, activity.getString(R.string.ctx_copy_link)) { action("copy_link", url, link) })
            items.add(Triple(R.drawable.ic_image, activity.getString(R.string.ctx_open_image)) { action("open", url, null) })
            items.add(Triple(R.drawable.ic_widget_camera, activity.getString(R.string.ctx_search_image)) { action("search_image", url, null) })
            items.add(Triple(R.drawable.ic_download, activity.getString(R.string.ctx_save_image)) { action("download", url, null) })
            items.add(Triple(R.drawable.ic_copy, activity.getString(R.string.ctx_copy)) { action("copy", url, null) })
            items.add(Triple(R.drawable.ic_share, activity.getString(R.string.ctx_share)) { action("share", url, link) })
        }
        pendingImageLink = null
        if (items.isEmpty()) return

        val sheet = BottomSheetDialog(activity)
        sheet.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        sheet.behavior.skipCollapsed = true

        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            fitsSystemWindows = true
        }
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(root) { v, ins ->
            v.setPadding(v.paddingLeft, ins.systemWindowInsetTop + v.paddingTop,
                v.paddingRight, ins.systemWindowInsetBottom + v.paddingBottom)
            androidx.core.view.ViewCompat.onApplyWindowInsets(v, ins)
        }
        val scroll = ScrollView(activity)
        val col = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(activity, 8), dp(activity, 8), dp(activity, 8), dp(activity, 8))
        }
        scroll.addView(col)
        root.addView(scroll)

        // preview line (the URL) — Chrome shows a short header
        col.addView(TextView(activity).apply {
            text = url
            textSize = 12f
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.MIDDLE
            setPadding(dp(activity, 14), dp(activity, 10), dp(activity, 14), dp(activity, 10))
            setTextColor(themedColor(activity, R.attr.colorOnSurfaceVariant))
        })

        for ((iconRes, label, act) in items) {
            col.addView(ctxRow(activity, iconRes, label) {
                sheet.dismiss()
                act()
            })
        }
        sheet.setContentView(root)
        sheet.show()
    }

    /** Store link URL arriving asynchronously for an image-anchor hit. */
    fun updateLinkForImageMenu(activity: MainActivity, link: String) {
        pendingImageLink = link
    }

    private fun ctxRow(activity: Context, iconRes: Int, label: String, onClick: () -> Unit): View {
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(activity, 14), dp(activity, 13), dp(activity, 14), dp(activity, 13))
            isClickable = true
            foreground = ripple(activity)
            setOnClickListener { onClick() }
        }
        row.addView(ImageView(activity).apply {
            setImageResource(iconRes)
            layoutParams = LinearLayout.LayoutParams(dp(activity, 20), dp(activity, 20))
            imageTintList = android.content.res.ColorStateList.valueOf(themedColor(activity, R.attr.colorPrimary))
        })
        row.addView(TextView(activity).apply {
            text = label
            textSize = 14f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(activity, 16)
            }
        })
        return row
    }

    // =====================================================================
    // Site info sheet
    // =====================================================================

    fun showSiteInfo(activity: MainActivity, url: String) {
        val host = Urls.domainOf(url)
        if (host.isEmpty()) return
        val secure = url.startsWith("https://")

        val sheet = BottomSheetDialog(activity)
        sheet.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        sheet.behavior.skipCollapsed = true

        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            fitsSystemWindows = true
        }
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(root) { v, ins ->
            v.setPadding(v.paddingLeft, ins.systemWindowInsetTop + v.paddingTop,
                v.paddingRight, ins.systemWindowInsetBottom + v.paddingBottom)
            androidx.core.view.ViewCompat.onApplyWindowInsets(v, ins)
        }
        val scroll = ScrollView(activity)
        val col = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(activity, 10), dp(activity, 10), dp(activity, 10), dp(activity, 14))
        }
        scroll.addView(col)
        root.addView(scroll)

        // header: lock + host + security state
        val header = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(activity, 12), dp(activity, 12), dp(activity, 12), dp(activity, 12))
        }
        header.addView(ImageView(activity).apply {
            setImageResource(if (secure) R.drawable.ic_lock else R.drawable.ic_lock_open)
            layoutParams = LinearLayout.LayoutParams(dp(activity, 22), dp(activity, 22))
            imageTintList = android.content.res.ColorStateList.valueOf(
                if (secure) 0xFF0E9F6E.toInt() else 0xFFD93025.toInt())
        })
        header.addView(LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(activity, 12)
            }
            addView(TextView(activity).apply {
                text = host
                textSize = 16f
                typeface = Typeface.DEFAULT_BOLD
            })
            addView(TextView(activity).apply {
                text = activity.getString(if (secure) R.string.site_secure else R.string.site_insecure)
                textSize = 12f
                setTextColor(themedColor(activity, R.attr.colorOnSurfaceVariant))
            })
        })
        col.addView(header)

        // per-site settings (loaded async)
        val siteCol = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
        }
        col.addView(siteCol)

        // actions
        col.addView(row(activity, R.drawable.ic_copy, R.string.site_copy_url) {
            sheet.dismiss()
            val cm = activity.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            cm.setPrimaryClip(android.content.ClipData.newPlainText("Zephyr", url))
            Toast.makeText(activity, R.string.copied, Toast.LENGTH_SHORT).show()
        })
        col.addView(row(activity, R.drawable.ic_share, R.string.site_share) {
            sheet.dismiss()
            activity.shareLink(url, host)
        })
        col.addView(row(activity, R.drawable.ic_delete, R.string.site_clear_data) {
            sheet.dismiss()
            activity.clearSiteData(url)
        })

        sheet.setContentView(root)
        sheet.show()

        // async: load per-domain settings rows
        io.launch {
            val db = AppDb.get(activity.applicationContext)
            val ds = try { db.domainSettingsDao().byDomain(host) } catch (_: Exception) { null }
            withContext(Dispatchers.Main) {
                fun toggleRow(labelRes: Int, current: Boolean?, apply: (Boolean?) -> Unit): View {
                    val r = LinearLayout(activity).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        setPadding(dp(activity, 12), dp(activity, 11), dp(activity, 12), dp(activity, 11))
                    }
                    r.addView(TextView(activity).apply {
                        text = activity.getString(labelRes)
                        textSize = 14f
                        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    })
                    r.addView(Switch(activity).apply {
                        isChecked = current ?: run {
                            // global default per pref
                            when (labelRes) {
                                R.string.site_js -> Prefs.jsEnabled
                                R.string.site_cookies -> Prefs.cookiesEnabled
                                R.string.site_images -> Prefs.imagesEnabled
                                R.string.site_popups -> Prefs.popupsEnabled
                                R.string.site_desktop -> Prefs.desktopDefault
                                else -> false
                            }
                        }
                        setOnCheckedChangeListener { _, checked -> apply(checked) }
                    })
                    return r
                }
                siteCol.addView(TextView(activity).apply {
                    text = activity.getString(R.string.site_settings_title)
                    textSize = 12f
                    setPadding(dp(activity, 12), dp(activity, 10), dp(activity, 12), dp(activity, 4))
                    setTextColor(themedColor(activity, R.attr.colorPrimary))
                })
                fun saveSettings(newDs: com.zephyr.browser.db.DomainSettingsEntity?) {
                    io.launch {
                        try {
                            if (newDs == null) db.domainSettingsDao().delete(
                                com.zephyr.browser.db.DomainSettingsEntity(domain = host))
                            else db.domainSettingsDao().insert(newDs)
                        } catch (_: Exception) {}
                    }
                }
                siteCol.addView(toggleRow(R.string.site_js, ds?.javaScript) { v ->
                    saveSettings((ds ?: com.zephyr.browser.db.DomainSettingsEntity(host)).copy(javaScript = v))
                })
                siteCol.addView(toggleRow(R.string.site_cookies, ds?.cookies) { v ->
                    saveSettings((ds ?: com.zephyr.browser.db.DomainSettingsEntity(host)).copy(cookies = v))
                })
                siteCol.addView(toggleRow(R.string.site_images, ds?.images) { v ->
                    saveSettings((ds ?: com.zephyr.browser.db.DomainSettingsEntity(host)).copy(images = v))
                })
                siteCol.addView(toggleRow(R.string.site_popups, ds?.popups) { v ->
                    saveSettings((ds ?: com.zephyr.browser.db.DomainSettingsEntity(host)).copy(popups = v))
                })
                siteCol.addView(toggleRow(R.string.site_desktop, ds?.desktop) { v ->
                    saveSettings((ds ?: com.zephyr.browser.db.DomainSettingsEntity(host)).copy(desktop = v))
                })
            }
        }
    }

    // =====================================================================
    // Reader
    // =====================================================================

    @SuppressLint("SetJavaScriptEnabled")
    fun showReader(activity: MainActivity, json: JSONObject) {
        val title = json.optString("title", "")
        val byline = json.optString("byline", "")
        val site = json.optString("siteName", "")
        val html = json.optString("html", "")
        val dialog = Dialog(activity)
        dialog.window?.let { w ->
            w.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
            w.setBackgroundDrawableResource(android.R.color.black)
        }
        val root = FrameLayout(activity).apply { setBackgroundColor(0xFF151023.toInt()) }
        val webView = WebView(activity).apply {
            settings.javaScriptEnabled = false
            setBackgroundColor(0)
        }
        root.addView(webView, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        // top bar: close, title, A-/A+, listen
        var fontSize = 17
        val bar = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(0xE61B1230.toInt())
            setPadding(dp(activity, 6), dp(activity, 6), dp(activity, 6), dp(activity, 6))
            elevation = dp(activity, 8).toFloat()
        }
        fun barBtn(iconRes: Int, desc: String, onClick: () -> Unit): ImageButton {
            return ImageButton(activity).apply {
                setImageResource(iconRes)
                background = ripple(activity)
                setPadding(dp(activity, 10), dp(activity, 10), dp(activity, 10), dp(activity, 10))
                layoutParams = LinearLayout.LayoutParams(dp(activity, 42), dp(activity, 42))
                imageTintList = android.content.res.ColorStateList.valueOf(0xFFEDE7FB.toInt())
                contentDescription = desc
                setOnClickListener { onClick() }
            }
        }
        val titleView = TextView(activity).apply {
            text = title.ifEmpty { site }
            textSize = 13f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setTextColor(0xFFEDE7FB.toInt())
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(activity, 6); marginEnd = dp(activity, 6)
            }
        }
        var tts: TextToSpeech? = null
        var speaking = false
        val plainText = html.replace(Regex("<[^>]+>"), " ").replace("&nbsp;", " ")
            .replace(Regex("\\s+"), " ").trim()
        bar.addView(barBtn(R.drawable.ic_close, activity.getString(R.string.close)) { dialog.dismiss() })
        bar.addView(titleView)
        bar.addView(barBtn(R.drawable.ic_arrow_down, activity.getString(R.string.reader_font_down)) {
            fontSize = (fontSize - 1).coerceAtLeast(12); renderReader(webView, title, byline, site, html, fontSize)
        })
        bar.addView(barBtn(R.drawable.ic_arrow_up, activity.getString(R.string.reader_font_up)) {
            fontSize = (fontSize + 1).coerceAtMost(30); renderReader(webView, title, byline, site, html, fontSize)
        })
        // v1.7.7: save the extracted article into the Reader LIBRARY (offline, styled)
        bar.addView(barBtn(R.drawable.ic_download_done, activity.getString(R.string.reader_save_lib)) {
            activity.saveReaderArticle(json)
        })
        bar.addView(barBtn(R.drawable.ic_speaker, activity.getString(R.string.reader_listen)) {
            if (speaking) {
                tts?.stop(); speaking = false
            } else {
                tts = TextToSpeech(activity) { status ->
                    if (status == TextToSpeech.SUCCESS) {
                        try {
                            tts?.language = java.util.Locale(java.util.Locale.getDefault().language)
                            tts?.speak(plainText, TextToSpeech.QUEUE_FLUSH, null, "zreader")
                            speaking = true
                        } catch (_: Exception) {}
                    } else {
                        Toast.makeText(activity, R.string.reader_tts_fail, Toast.LENGTH_SHORT).show()
                    }
                }
            }
        })
        root.addView(bar, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP))
        dialog.setContentView(root)
        dialog.setOnDismissListener { tts?.stop(); tts?.shutdown() }
        dialog.show()
        renderReader(webView, title, byline, site, html, fontSize)
    }

    private fun renderReader(wv: WebView, title: String, byline: String, site: String, html: String, fontSp: Int) {
        val page = """
        <!DOCTYPE html><html><head><meta charset="utf-8">
        <meta name="viewport" content="width=device-width, initial-scale=1">
        <style>
          body{background:#151023;color:#EDE7FB;font-family:Georgia,'Times New Roman',serif;
               margin:0 auto;padding:56px 18px 40px;max-width:720px;font-size:${fontSp}px;line-height:1.62;}
          h1{font-size:1.5em;line-height:1.3;margin:0 0 6px;color:#FFFFFF;}
          .meta{color:#B7A7DA;font-size:0.8em;margin-bottom:24px;}
          .meta b{color:#9F7BEA;}
          img{max-width:100%;height:auto;border-radius:8px;margin:10px 0;}
          a{color:#9F7BEA;text-decoration:none;}
          p{margin:0 0 1em;text-align:justify;}
          blockquote{border-left:3px solid #6E5A96;margin:1em 0;padding:2px 14px;color:#B7A7DA;}
          pre{background:#241A3D;border-radius:8px;padding:12px;overflow-x:auto;font-size:0.85em;}
          h2,h3{color:#FFFFFF;line-height:1.35;}
        </style></head><body>
        <h1>${android.text.TextUtils.htmlEncode(title)}</h1>
        <div class="meta">${android.text.TextUtils.htmlEncode(site)}${if (byline.isNotEmpty()) " · <b>${android.text.TextUtils.htmlEncode(byline)}</b>" else ""}</div>
        $html
        </body></html>
        """.trimIndent()
        wv.loadDataWithBaseURL(null, page, "text/html", "utf-8", null)
    }

    // =====================================================================
    // =====================================================================
    // v1.7.7: Stylus-like site CSS editor
    // =====================================================================

    /** v1.7.7 (user: «стили сайтов — свой CSS на домен, обмен по ссылкой»):
     *  monospace editor for the domain's user CSS with live apply, enable switch,
     *  delete, and SHARE (a zephyr-style:// link another Zephyr can open). */
    fun showSiteStyleEditor(activity: MainActivity, domain: String, presetCss: String) {
        val sheet = BottomSheetDialog(activity)
        val col = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(activity, 18), dp(activity, 12), dp(activity, 18), dp(activity, 30))
        }
        col.addView(TextView(activity).apply {
            text = activity.getString(R.string.styles_title, domain)
            textSize = 16.5f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(android.content.res.ColorStateList.valueOf(themedColor(activity, R.attr.colorOnSurface)))
            setPadding(dp(activity, 4), 0, dp(activity, 4), dp(activity, 4))
        })
        col.addView(TextView(activity).apply {
            text = activity.getString(R.string.styles_hint)
            textSize = 12.5f
            setTextColor(android.content.res.ColorStateList.valueOf(themedColor(activity, R.attr.colorOnSurfaceVariant)))
            setPadding(dp(activity, 4), 0, dp(activity, 4), dp(activity, 10))
        })
        val input = android.widget.EditText(activity).apply {
            setText(presetCss)
            hint = "body { font-size: 17px !important; }"
            typeface = android.graphics.Typeface.MONOSPACE
            textSize = 13f
            minLines = 8
            gravity = Gravity.TOP
            background = com.zephyr.browser.settings.SettingsUi.rounded(activity, dp(activity, 14))
            setPadding(dp(activity, 12), dp(activity, 10), dp(activity, 12), dp(activity, 10))
        }
        col.addView(input)

        val actions = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            setPadding(0, dp(activity, 12), 0, 0)
        }
        fun pillBtn(label: String, filled: Boolean, onClick: () -> Unit): TextView {
            return TextView(activity).apply {
                text = label
                textSize = 14f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                setPadding(dp(activity, 16), dp(activity, 10), dp(activity, 16), dp(activity, 10))
                background = if (filled) com.zephyr.browser.settings.SettingsUi.roundedFill(activity, dp(activity, 20))
                else com.zephyr.browser.settings.SettingsUi.rounded(activity, dp(activity, 20))
                setTextColor(android.content.res.ColorStateList.valueOf(
                    themedColor(activity, if (filled) R.attr.colorOnPrimary else R.attr.colorOnSurface)))
                isClickable = true
                setOnClickListener { onClick() }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    marginStart = dp(activity, 8)
                }
            }
        }
        actions.addView(pillBtn(activity.getString(R.string.delete), filled = false) {
            sheet.dismiss()
            activity.saveSiteStyle(domain, "", true, apply = true)
            Toast.makeText(activity, R.string.deleted, Toast.LENGTH_SHORT).show()
        })
        actions.addView(pillBtn(activity.getString(R.string.styles_share), filled = false) {
            val css = input.text.toString()
            val b64 = android.util.Base64.encodeToString(css.toByteArray(),
                android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP)
            val link = "zephyr-style://import?d=" + android.net.Uri.encode(domain) + "&c=" + b64
            try {
                activity.startActivity(android.content.Intent.createChooser(android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(android.content.Intent.EXTRA_TEXT, link)
                }, activity.getString(R.string.share_via)))
            } catch (_: Exception) {}
        })
        actions.addView(pillBtn(activity.getString(R.string.save_apply), filled = true) {
            sheet.dismiss()
            activity.saveSiteStyle(domain, input.text.toString(), true, apply = true)
            Toast.makeText(activity, R.string.saved, Toast.LENGTH_SHORT).show()
        })
        col.addView(actions)
        sheet.setContentView(col)
        sheet.show()
    }

    // =====================================================================
    // v1.7.7: premium appearance picker (scope chips + theme preview cards)
    // =====================================================================

    /** v1.7.7 (user: «на википедии — окно „выбор действует только для сайта“ с одной
     *  кнопкой отмена — что это?») — the OLD per-site dialog confused the user. The
     *  new picker is a premium bottom sheet: a 3×2 grid of THEME PREVIEW CARDS and
     *  an explicit SCOPE chip row (Только этот сайт / Все сайты), so one glance
     *  answers both questions: what changes and where it applies. */
    fun showAppearancePickerSheet(activity: MainActivity) {
        val tab = activity.tabs.current
        val liveUrl = tab?.webView?.url ?: tab?.pageUrlSnapshot ?: ""
        val onSite = tab != null && liveUrl.isNotEmpty() && liveUrl != Urls.DEFAULT_HOME && tab.isHome.not()
        val host = if (onSite) Urls.domainOf(liveUrl).removePrefix("www.") else ""

        val sheet = BottomSheetDialog(activity)
        val col = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(activity, 18), dp(activity, 12), dp(activity, 18), dp(activity, 30))
        }
        col.addView(TextView(activity).apply {
            text = activity.getString(R.string.menu_page_appearance)
            textSize = 17f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(android.content.res.ColorStateList.valueOf(themedColor(activity, R.attr.colorOnSurface)))
            setPadding(dp(activity, 4), 0, dp(activity, 4), dp(activity, 4))
        })

        // ---- scope chips (site section only) ----
        var scopeSite = onSite
        val scopeRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(activity, 8), 0, dp(activity, 10))
            visibility = if (onSite) View.VISIBLE else View.GONE
        }
        fun chip(label: String, selected: Boolean, onClick: () -> Unit): TextView {
            return TextView(activity).apply {
                text = label
                textSize = 13f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                maxLines = 1
                setPadding(dp(activity, 14), dp(activity, 8), dp(activity, 14), dp(activity, 8))
                background = if (selected) com.zephyr.browser.settings.SettingsUi.roundedFill(activity, dp(activity, 18))
                else com.zephyr.browser.settings.SettingsUi.rounded(activity, dp(activity, 18))
                setTextColor(android.content.res.ColorStateList.valueOf(
                    themedColor(activity, if (selected) R.attr.colorOnPrimary else R.attr.colorOnSurfaceVariant)))
                isClickable = true
                setOnClickListener { onClick() }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    marginEnd = dp(activity, 8)
                }
            }
        }
        val chipSiteHolder = LinearLayout(activity)
        val chipAllHolder = LinearLayout(activity)
        fun renderScope() {
            chipSiteHolder.removeAllViews()
            chipAllHolder.removeAllViews()
            chipSiteHolder.addView(chip(activity.getString(R.string.appearance_scope_site, host), scopeSite) {
                scopeSite = true; renderScope()
            })
            chipAllHolder.addView(chip(activity.getString(R.string.appearance_scope_all), !scopeSite) {
                scopeSite = false; renderScope()
            })
        }
        scopeRow.addView(chipSiteHolder)
        scopeRow.addView(chipAllHolder)
        col.addView(scopeRow)
        if (onSite) renderScope()

        // ---- 3×2 preview grid ----
        val names = listOf(
            R.string.appearance_system, R.string.appearance_light, R.string.appearance_dark,
            R.string.appearance_sepia, R.string.appearance_gray, R.string.appearance_contrast)
        val previews = listOf(0xFFE8EAED.toInt(), 0xFFFFFFFF.toInt(), 0xFF16181D.toInt(),
            0xFFF3E7D3.toInt(), 0xFFB9BEC6.toInt(), 0xFF000000.toInt())
        val lineColors = listOf(0xFF9AA0A6.toInt(), 0xFFC4C7C5.toInt(), 0xFF5B616E.toInt(),
            0xFFC4B49A.toInt(), 0xFFE7EAEE.toInt(), 0xFF3B3F45.toInt())
        val grid = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        val cards = mutableListOf<LinearLayout>()
        fun currentSelection(): Int {
            return if (scopeSite) {
                activity.tabs.domainSettingFor(host)?.appearance ?: -1   // -1 = no card selected
            } else {
                com.zephyr.browser.Prefs.pageAppearance.coerceIn(0, 5)
            }
        }
        fun renderCards() {
            grid.removeAllViews()
            cards.clear()
            val sel = currentSelection()
            var row: LinearLayout? = null
            for ((i, nameRes) in names.withIndex()) {
                if (i % 3 == 0) {
                    row = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
                    grid.addView(row)
                }
                val idx = i
                val selected = i == sel
                val card = LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER_HORIZONTAL
                    setPadding(dp(activity, 6), dp(activity, 10), dp(activity, 6), dp(activity, 10))
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    isClickable = true
                    // mini page mock: bg + 3 fake text lines + a picture block
                    addView(LinearLayout(activity).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(dp(activity, 9), dp(activity, 9), dp(activity, 9), dp(activity, 9))
                        background = com.zephyr.browser.settings.SettingsUi.roundedFillAttr(activity, dp(activity, 12),
                            if (selected) R.attr.colorPrimaryContainer else R.attr.colorSurfaceVariant)
                        addView(View(activity).apply {
                            setBackgroundColor(lineColors[idx])
                            layoutParams = LinearLayout.LayoutParams(
                                LinearLayout.LayoutParams.MATCH_PARENT, dp(activity, 5)).apply { bottomMargin = dp(activity, 4) }
                        })
                        addView(View(activity).apply {
                            setBackgroundColor(previews[idx])
                            layoutParams = LinearLayout.LayoutParams(
                                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
                        })
                        addView(View(activity).apply {
                            setBackgroundColor(lineColors[idx])
                            layoutParams = LinearLayout.LayoutParams(dp(activity, 34), dp(activity, 5)).apply {
                                topMargin = dp(activity, 4)
                            }
                        })
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT, dp(activity, 74))
                    })
                    addView(TextView(activity).apply {
                        text = activity.getString(nameRes)
                        textSize = 12f
                        maxLines = 1
                        gravity = Gravity.CENTER
                        setTextColor(android.content.res.ColorStateList.valueOf(themedColor(activity,
                            if (selected) R.attr.colorPrimary else R.attr.colorOnSurfaceVariant)))
                        setPadding(0, dp(activity, 6), 0, 0)
                    })
                    setOnClickListener {
                        if (scopeSite) activity.setSiteAppearance(idx)   // per-site override
                        else { com.zephyr.browser.Prefs.setPageAppearance(idx); activity.applyAppearanceLive() }
                        renderCards()
                    }
                }
                cards.add(card)
                row?.addView(card)
            }
        }
        renderCards()
        col.addView(grid)

        // reset row (per-site override present)
        val override = if (onSite) activity.tabs.domainSettingFor(host)?.appearance else null
        if (onSite && override != null) {
            col.addView(TextView(activity).apply {
                text = activity.getString(R.string.appearance_reset_site)
                textSize = 13.5f
                gravity = Gravity.CENTER
                setPadding(dp(activity, 12), dp(activity, 11), dp(activity, 12), dp(activity, 11))
                background = com.zephyr.browser.settings.SettingsUi.rounded(activity, dp(activity, 18))
                setTextColor(android.content.res.ColorStateList.valueOf(themedColor(activity, R.attr.colorOnSurfaceVariant)))
                isClickable = true
                setOnClickListener {
                    activity.setSiteAppearance(null)
                    renderCards()
                }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = dp(activity, 10)
                }
            })
        }
        sheet.setContentView(col)
        sheet.show()
    }

    // =====================================================================
    // shared helpers
    // =====================================================================

    private fun row(activity: Context, iconRes: Int, labelRes: Int, onClick: () -> Unit): View {
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(activity, 14), dp(activity, 13), dp(activity, 14), dp(activity, 13))
            isClickable = true
            foreground = ripple(activity)
            setOnClickListener { onClick() }
        }
        row.addView(ImageView(activity).apply {
            setImageResource(iconRes)
            layoutParams = LinearLayout.LayoutParams(dp(activity, 22), dp(activity, 22))
            imageTintList = android.content.res.ColorStateList.valueOf(themedColor(activity, R.attr.colorOnSurfaceVariant))
        })
        row.addView(TextView(activity).apply {
            text = activity.getString(labelRes)
            textSize = 14f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(activity, 16)
            }
        })
        return row
    }

    /** Teal→green gradient banner like modern browsers' protection stats. */
    private fun gradientBanner(): GradientDrawable = GradientDrawable().apply {
        orientation = GradientDrawable.Orientation.TL_BR
        colors = intArrayOf(0xFF0E9F6E.toInt(), 0xFF14B8A6.toInt(), 0xFF34D399.toInt())
        cornerRadius = 0f  // full-width banner inside the sheet
    }

    /** Rounded-corner ripple so row feedback stays inside the menu's shape family. */
    fun roundedRipple(ctx: Context, radiusDp: Int): android.graphics.drawable.Drawable? = try {
        val mask = GradientDrawable().apply {
            cornerRadius = dp(ctx, radiusDp).toFloat()
            setColor(0xFFFFFFFF.toInt())
        }
        android.graphics.drawable.RippleDrawable(
            android.content.res.ColorStateList.valueOf(0x1F888888), null, mask)
    } catch (_: Exception) { null }

    fun ripple(ctx: Context): android.graphics.drawable.Drawable? = roundedRipple(ctx, 14)

    fun themedColor(ctx: Context, attr: Int): Int = try {
        val ta = ctx.theme.obtainStyledAttributes(intArrayOf(attr))
        val c = ta.getColor(0, 0xFF5F6368.toInt())
        ta.recycle()
        c
    } catch (_: Exception) { 0xFF5F6368.toInt() }

    fun dp(ctx: Context, v: Int): Int = (v * ctx.resources.displayMetrics.density + 0.5f).toInt()


}
