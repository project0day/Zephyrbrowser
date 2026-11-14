package com.zephyr.browser.settings

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import com.zephyr.browser.Prefs
import com.zephyr.browser.R

/** Settings root (list of screens) + fragment router for deep links ("screen" extra). */
class SettingsActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_SCREEN = "screen"
        // v1.7.3: RC_OVPN_IMPORT removed with the OpenVPN engine (kept: filter import, wallpaper).
        const val RC_FILTER_IMPORT = 802
        const val RC_WALLPAPER = 803

        /**
         * v1.5.0 FIX (user: "при выборе темы кидает меня на экран настроек"):
         * themeCard calls act.recreate() which restarts the activity at the ROOT menu.
         * Fragments set this BEFORE recreate(); onCreate re-opens that screen instead.
         */
        @Volatile var recreateScreen: String? = null
    }

    private var current: Fragment? = null
    private lateinit var container: android.widget.FrameLayout
    private lateinit var rootMenu: View

    /** v1.7.7 (user: «из попапа зашёл в ВПН, жму назад — попадаю в настройки, а не
     *  на сайт; с DNS так же»): when the screen was DEEP-LINKED from the browser
     *  menu, back must EXIT to the browser — not dump the user at the settings root
     *  (which they never opened). Navigating root → screen keeps the normal
     *  back-to-root behavior. */
    private var openedViaDeepLink = false

    override fun onCreate(savedInstanceState: Bundle?) {
        Prefs.init(this)
        setTheme(Prefs.themeStyle())
        super.onCreate(savedInstanceState)

        container = android.widget.FrameLayout(this).apply { id = View.generateViewId() }
        setContentView(container)

        // v1.7.29: ONE place gives every settings screen the grouped rounded-card
        // look that so far only the AI screen had. Fragments keep building plain
        // rows; SettingsUi.autoGroup folds them into cards right after the view
        // tree is created, so no fragment had to be rewritten.
        supportFragmentManager.registerFragmentLifecycleCallbacks(
            object : androidx.fragment.app.FragmentManager.FragmentLifecycleCallbacks() {
                override fun onFragmentViewCreated(
                    fm: androidx.fragment.app.FragmentManager,
                    f: androidx.fragment.app.Fragment,
                    v: View,
                    savedInstanceState: Bundle?
                ) {
                    v.post { SettingsUi.autoGroup(SettingsUi.findColumn(v)) }
                }
            }, false
        )

        rootMenu = createRootMenu()
        container.addView(rootMenu)

        val deep = intent.getStringExtra(EXTRA_SCREEN)
        if (deep != null) {
            openedViaDeepLink = true
            openScreen(deep)
        } else {
            // v1.5.0 FIX: activity was re-created (theme change) — return the user to
            // the screen they were on instead of dumping them at the root menu.
            // Deep links via EXTRA_SCREEN still win (checked above).
            val pending = recreateScreen
            if (pending != null) {
                recreateScreen = null
                openScreen(pending)
            }
        }
    }

    private fun createRootMenu(): View {
        val ctx = this
        val root = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        // v1.7.71: the root screen gets a search button — a hundred settings
        // across fourteen screens are not findable by scrolling
        root.addView(toolbar(getString(R.string.settings), withSearch = true))
        val col = SettingsUi.screen(ctx)
        root.addView(SettingsUi.scrollOf(col), LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        // v1.7.1: grouped card layout (user: "разбить на группы с карточками — красивее,
        // чем плоский фон"). Rows keep their icons/click behavior; cards sit on the soft
        // backdrop painted by SettingsUi.screen().
        fun group(labelRes: Int, build: (LinearLayout) -> Unit) {
            col.addView(SettingsUi.groupLabel(ctx, labelRes))
            val card = SettingsUi.groupCard(ctx)
            build(card)
            col.addView(card)
        }
        fun nav(to: LinearLayout, icon: Int, title: String, screen: String) {
            to.addView(SettingsUi.clickRow(ctx, icon, title) { openScreen(screen) })
        }
        fun div(to: LinearLayout) { to.addView(SettingsUi.rowDivider(ctx)) }

        // v1.7.3 (user: «О приложении на втором пункте — должен быть в конце! Основное
        // засунь в группу Персонализация сверху»): logically ordered groups — personalization
        // first, privacy & protection second, data third, and About as the very LAST card.
        group(R.string.grp_personalization) { card ->
            nav(card, R.drawable.ic_tune, getString(R.string.set_general), "general")
            div(card)
            nav(card, R.drawable.ic_palette, getString(R.string.set_appearance), "appearance")
            div(card)
            nav(card, R.drawable.ic_grid, getString(R.string.set_home_screen), "home")
            div(card)
            nav(card, R.drawable.ic_ai, getString(R.string.set_ai_assistant), "ai")
            div(card)
            nav(card, R.drawable.ic_list, getString(R.string.set_menu), "menu")
        }
        group(R.string.grp_network_privacy) { card ->
            nav(card, R.drawable.ic_shield, getString(R.string.set_privacy), "privacy")
            div(card)
            nav(card, R.drawable.ic_key, getString(R.string.set_security), "security")
            div(card)
            nav(card, R.drawable.ic_block, getString(R.string.set_adblock), "adblock")
            div(card)
            nav(card, R.drawable.ic_website, getString(R.string.set_site), "site")
            div(card)
            nav(card, R.drawable.ic_vpn, getString(R.string.set_proxy), "proxy")
            div(card)
            // v1.7.0: built-in DNS + leak protection
            nav(card, R.drawable.ic_globe, getString(R.string.set_dns), "dns")
        }
        // v1.7.6 (user: «почему Пароли/скрипты/автозаполнение/платёжные данные нет в
        // настройках пунктами отдельно — они доступны только из попапа!»): every
        // personal-data vault now has its own top-level row in its own group.
        group(R.string.grp_personal_data) { card ->
            card.addView(SettingsUi.clickRowSummary(ctx, R.drawable.ic_key, getString(R.string.panel_passwords), getString(R.string.h_panel_passwords)) {
                openDataPanel("passwords")
            })
            div(card)
            card.addView(SettingsUi.clickRowSummary(ctx, R.drawable.ic_user, getString(R.string.panel_autofill), getString(R.string.h_panel_autofill)) {
                openDataPanel("autofill")
            })
            div(card)
            card.addView(SettingsUi.clickRowSummary(ctx, R.drawable.ic_desktop, getString(R.string.panel_payments), getString(R.string.h_panel_payments)) {
                openDataPanel("payments")
            })
            div(card)
            card.addView(SettingsUi.clickRowSummary(ctx, R.drawable.ic_script, getString(R.string.panel_scripts), getString(R.string.h_panel_scripts)) {
                openDataPanel("scripts")
            })
            div(card)
            // v1.7.54: Дела — календарь с напоминаниями
            card.addView(SettingsUi.clickRowSummary(ctx, R.drawable.ic_calendar, getString(R.string.panel_tasks), getString(R.string.h_panel_tasks)) {
                openDataPanel("tasks")
            })
            div(card)
            // v1.7.71 (user: «а куда ты поместил Шаблоны и Проекты? я не вижу
            // в настройках»): they were reachable only by adding them to the
            // menu by hand, which nobody would think to do.
            card.addView(SettingsUi.clickRowSummary(ctx, R.drawable.ic_file, getString(R.string.panel_templates), getString(R.string.h_panel_templates)) {
                openDataPanel("templates")
            })
            div(card)
            card.addView(SettingsUi.clickRowSummary(ctx, R.drawable.ic_folder_open, getString(R.string.panel_workspace), getString(R.string.h_panel_workspace)) {
                openDataPanel("workspace")
            })
            div(card)
            card.addView(SettingsUi.clickRowSummary(ctx, R.drawable.ic_script, getString(R.string.panel_workflows), getString(R.string.h_panel_workflows)) {
                openDataPanel("workflows")
            })
        }
        group(R.string.grp_data_panels) { card ->
            // v1.4.1: Downloads opens the panel DIRECTLY — no more intermediate screen-in-screen
            // (user report: "много экранов загрузки не надо").
            // v1.7.1: "Открыть панель" right-side text removed (user: "почему? уберите").
            card.addView(SettingsUi.clickRowSummary(ctx, R.drawable.ic_download, getString(R.string.set_downloads), getString(R.string.h_set_downloads)) {
                startActivity(Intent(this, com.zephyr.browser.panels.PanelsActivity::class.java).apply {
                    putExtra("panel", "downloads")
                })
            })
            div(card)
            // v1.7.7: built-in Files app (full file manager + txt editor)
            card.addView(SettingsUi.clickRowSummary(ctx, R.drawable.ic_folder_open, getString(R.string.files_title), getString(R.string.h_files_title)) {
                // v1.7.12: mark the launch attempt — if FilesActivity dies before its
                // first line (class load / super.onCreate), MainActivity's resume check
                // surfaces the crash report instead of a silent bounce back.
                try { java.io.File(filesDir, "files_launch_probe.txt")
                    .writeText(System.currentTimeMillis().toString()) } catch (_: Throwable) {}
                startActivity(Intent(this, com.zephyr.browser.files.FilesActivity::class.java))
            })
            div(card)
            // v1.7.7: Reader-library panel (saved clean articles, offline)
            card.addView(SettingsUi.clickRowSummary(ctx, R.drawable.ic_reader, getString(R.string.reader_lib_title), getString(R.string.h_reader_lib)) {
                openDataPanel("library")
            })
            div(card)
            // v1.7.6: offline pages + backup got their own rows (backup moved OUT of
            // «Основные» — its own screen, ready to grow: what to include, autobackup)
            card.addView(SettingsUi.clickRowSummary(ctx, R.drawable.ic_reader, getString(R.string.set_offline), getString(R.string.h_set_offline)) {
                openDataPanel("offline")
            })
            div(card)
            nav(card, R.drawable.ic_upload, getString(R.string.set_backup_screen), "backup")
            div(card)
            // v1.7.9: per-site data/cache manager (storage, cookies, selective cleanup)
            nav(card, R.drawable.ic_storage, getString(R.string.cache_title), "cache")
            div(card)
            nav(card, R.drawable.ic_delete, getString(R.string.set_clear), "clear")
        }
        // v1.7.3: «О приложении» — its own single-row card at the very BOTTOM of the list.
        group(R.string.grp_app) { card ->
            nav(card, R.drawable.ic_info, getString(R.string.set_about), "about")
        }
        return root
    }

    /** v1.7.6: opens a personal-data panel (passwords / autofill / payments / scripts /
     *  offline) — same windows as the main-menu popup rows, now reachable from Settings. */
    private fun openDataPanel(panel: String) {
        startActivity(Intent(this, com.zephyr.browser.panels.PanelsActivity::class.java).apply {
            putExtra("panel", panel)
        })
    }

    /**
     * v1.7.71: one destination id for the search sheet.
     *
     * "panel:files" opens the Files activity, "panel:x" opens a list panel,
     * anything else is a settings screen — so a search result does not need to
     * know which of the three kinds of destination it is.
     */
    fun goTo(dest: String) {
        when {
            dest == "panel:files" -> startActivity(
                Intent(this, com.zephyr.browser.files.FilesActivity::class.java)
            )
            dest.startsWith("panel:") -> openDataPanel(dest.removePrefix("panel:"))
            else -> openScreen(dest)
        }
    }

    fun openScreen(screen: String) {
        val f: Fragment = when (screen) {
            "general" -> GeneralPrefsFragment()
            "appearance" -> AppearancePrefsFragment()
            "home" -> HomePrefsFragment()
            "ai" -> AiPrefsFragment()
            "menu" -> MenuPrefsFragment()
            "privacy" -> PrivacyPrefsFragment()
            "security" -> SecurityPrefsFragment()
            "adblock" -> AdBlockPrefsFragment()
            "site" -> SitePrefsFragment()
            "proxy" -> ProxyPrefsFragment()
            "dns" -> DnsPrefsFragment()
            "about" -> AboutPrefsFragment()
            // v1.7.84: the documents a paid listing has to carry
            "legal_privacy" -> LegalPrefsFragment.of("privacy")
            "legal_terms" -> LegalPrefsFragment.of("terms")
            "legal_licenses" -> LegalPrefsFragment.of("licenses")
            "clear" -> ClearDataPrefsFragment()
            "backup" -> BackupPrefsFragment()
            "cache" -> CachePrefsFragment()
            // v1.7.36: agent pairing + swarm are full screens, not sheets
            "agent" -> AgentPrefsFragment()
            "swarm" -> SwarmPrefsFragment()
            else -> GeneralPrefsFragment()
        }
        current = f
        rootMenu.visibility = View.GONE
        // v1.5.0: settings screens now slide in/out (user: "В системе нет анимаций вообще!")
        supportFragmentManager.beginTransaction()
            .setCustomAnimations(
                R.anim.screen_in, R.anim.screen_out,
                R.anim.screen_in_back, R.anim.screen_out_back)
            .replace(container.id, f)
            .commit()
    }

    /** Used by fragments to build their screen with a back toolbar. */
    fun buildScreen(titleRes: Int): LinearLayout {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(toolbar(getString(titleRes)))
        return root
    }

    private fun toolbar(title: String, withSearch: Boolean = false): LinearLayout {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        val back = ImageButton(this).apply {
            setImageResource(R.drawable.ic_back)
            background = SettingsUi.ripple(this@SettingsActivity)
            setPadding(dp(10), dp(10), dp(10), dp(10))
            layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
            contentDescription = getString(R.string.pl_back)
            imageTintList = android.content.res.ColorStateList.valueOf(
                SettingsUi.themedColor(this@SettingsActivity, R.attr.colorOnSurface))
            setOnClickListener {
                onBackPressed()
            }
        }
        bar.addView(back)
        bar.addView(TextView(this).apply {
            text = title
            textSize = 18f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(SettingsUi.themedColor(this@SettingsActivity, R.attr.colorOnSurface))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(6)
            }
        })
        if (withSearch) {
            val activity = this
            val searchBtn = ImageButton(activity)
            searchBtn.setImageResource(R.drawable.ic_search)
            searchBtn.background = SettingsUi.ripple(activity)
            searchBtn.setPadding(dp(10), dp(10), dp(10), dp(10))
            searchBtn.layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
            searchBtn.contentDescription = getString(R.string.set_search)
            searchBtn.imageTintList = android.content.res.ColorStateList.valueOf(
                SettingsUi.themedColor(activity, R.attr.colorOnSurface))
            searchBtn.setOnClickListener { SettingsSearch.show(activity) }
            bar.addView(searchBtn)
        }
        return bar
    }

    override fun onBackPressed() {
        if (current != null) {
            // v1.7.7: deep-linked screens EXIT to the caller (the browser), not to
            // the settings root the user never saw
            if (openedViaDeepLink) {
                finish()
                return
            }
            // v1.5.0: animate the screen out when returning to the root menu
            supportFragmentManager.beginTransaction()
                .setCustomAnimations(R.anim.screen_in_back, R.anim.screen_out_back)
                .remove(current!!)
                .commit()
            current = null
            rootMenu.visibility = View.VISIBLE
        } else super.onBackPressed()
    }

    @Suppress("DEPRECATION")
    override fun finish() {
        // v1.5.0: exit animation for the whole settings panel (enter side is applied
        // by the menu code — panel_in; here we only own the exit).
        overridePendingTransition(R.anim.hold, R.anim.panel_out)
        super.finish()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()
}
