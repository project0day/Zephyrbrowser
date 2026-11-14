package com.zephyr.browser.panels

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.view.WindowInsetsControllerCompat
import androidx.fragment.app.Fragment
import com.zephyr.browser.Prefs
import com.zephyr.browser.R
import com.zephyr.browser.ui.Anim

/** Container for list panels: bookmarks / history / downloads / passwords / scripts / autofill / payments. */
class PanelsActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PANEL = "panel"
        /** v1.5.0 FIX 5: true → purple incognito chrome (sent by MainActivity.openPanel). */
        const val EXTRA_INCOGNITO = "incognito"
        const val RC_IMPORT = 700
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        Prefs.init(this)
        setTheme(Prefs.themeStyle())
        super.onCreate(savedInstanceState)

        val panel = intent.getStringExtra(EXTRA_PANEL) ?: "bookmarks"
        // v1.5.0 FIX 5: purple chrome when opened from an incognito tab
        val incognito = intent.getBooleanExtra(EXTRA_INCOGNITO, false)

        // adblock panel lives in Settings — forward
        if (panel == "adblock") {
            startActivity(Intent(this, com.zephyr.browser.settings.SettingsActivity::class.java).apply {
                putExtra("screen", "adblock")
            })
            finish()
            return
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        // toolbar
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(6), dp(4), dp(2))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        val back = ImageButton(this).apply {
            setImageResource(R.drawable.ic_back)
            background = PanelUi.ripple(this@PanelsActivity)
            setPadding(dp(10), dp(10), dp(10), dp(10))
            layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
            contentDescription = getString(R.string.pl_back)
            setOnClickListener { finish() }
            imageTintList = android.content.res.ColorStateList.valueOf(
                if (incognito) 0xFFEDE7FB.toInt()
                else PanelUi.themedColor(this@PanelsActivity, R.attr.colorOnSurface))
        }
        val title = TextView(this).apply {
            textSize = 19f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(if (incognito) 0xFFEDE7FB.toInt()
            else PanelUi.themedColor(this@PanelsActivity, R.attr.colorOnSurface))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(6)
            }
        }
        bar.addView(back)
        bar.addView(title)
        root.addView(bar)

        val fragment: Fragment = when (panel) {
            "history" -> HistoryPanelFragment().also { title.setText(R.string.panel_history) }
            "downloads" -> DownloadsPanelFragment().also { title.setText(R.string.panel_downloads) }
            "passwords" -> PasswordsPanelFragment().also { title.setText(R.string.panel_passwords) }
            "scripts" -> ScriptsPanelFragment().also { title.setText(R.string.panel_scripts) }
            "autofill" -> AutofillPanelFragment().also { title.setText(R.string.panel_autofill) }
            "payments" -> PaymentsPanelFragment().also { title.setText(R.string.panel_payments) }
            "offline" -> OfflinePanelFragment().also { title.setText(R.string.set_offline) }
            // v1.7.54: tasks, rendezvous and their alarms
            "tasks" -> TasksPanelFragment().also { title.setText(R.string.panel_tasks) }
            // v1.7.57: the assistant workspace, browsable at last
            "workspace" -> WorkspacePanelFragment().also { title.setText(R.string.panel_workspace) }
            // v1.7.61: the user own saved commands
            "workflows" -> WorkflowsPanelFragment().also { title.setText(R.string.panel_workflows) }
            // v1.7.70: documents the user sends again and again
            "templates" -> TemplatesPanelFragment().also { title.setText(R.string.panel_templates) }
            // v1.7.7: Reader library — saved clean articles, offline premium reader
            "library" -> ReaderLibraryFragment().also { title.setText(R.string.reader_lib_title) }
            else -> BookmarksPanelFragment().also { title.setText(R.string.panel_bookmarks) }
        }

        val containerId = View.generateViewId()
        val container = LinearLayout(this).apply {
            id = containerId
            // v1.4.1 "half screen" ROOT CAUSE FIX: LinearLayout defaults to HORIZONTAL —
            // the fragment view then received WRAP_CONTENT width and squeezed to ~half screen
            // (bookmarks / downloads / history / passwords / autofill all shared this bug).
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        root.addView(container)
        setContentView(root)

        if (incognito) {
            // v1.5.0 FIX 5: full incognito chrome — gradient root + purple system bars
            // (was: grey theme bars even for incognito-opened panels).
            window.statusBarColor = 0xFF2E1E4F.toInt()
            window.navigationBarColor = 0xFF1F1740.toInt()
            root.setBackgroundResource(R.drawable.bg_incognito_root)
            setLightStatusIcons(false)
            // v1.6.0: the NAV bar icons too — the activity theme may be a LIGHT one
            // (dark icons), which is invisible on the dark purple nav bar.
            setLightNavIcons(false)
        } else {
            // v1.6.0: match the THEME's nav-bar icon appearance (the base theme now
            // declares windowLightNavigationBar; this keeps activities consistent
            // even if the theme was resolved before the attr existed).
            val ta = obtainStyledAttributes(Prefs.themeStyle(), intArrayOf(android.R.attr.windowLightStatusBar))
            val light = ta.getBoolean(0, false)
            ta.recycle()
            setLightNavIcons(light)
        }

        // v1.5.0 FIX 7: the panel slides up over the browser
        Anim.slideUp(root, distanceDp = 24f, duration = 240)

        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction().replace(containerId, fragment).commit()
        }
    }

    override fun finish() {
        super.finish()
        // v1.5.0 FIX 7: exit animation mirroring the slide-up entrance
        @Suppress("DEPRECATION") overridePendingTransition(R.anim.hold, R.anim.panel_out)
    }

    /** v1.5.0: white status bar icons for the dark incognito chrome (min API 24 safe). */
    private fun setLightStatusIcons(light: Boolean) {
        if (Build.VERSION.SDK_INT >= 30) {
            WindowInsetsControllerCompat(window, window.decorView).isAppearanceLightStatusBars = light
        } else {
            @Suppress("DEPRECATION")
            val flags = window.decorView.systemUiVisibility
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = if (light)
                flags or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
            else
                flags and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv()
        }
    }

    /** v1.6.0: navigation bar icon appearance (API 26+). */
    private fun setLightNavIcons(light: Boolean) {
        if (Build.VERSION.SDK_INT >= 26) {
            if (Build.VERSION.SDK_INT >= 30) {
                WindowInsetsControllerCompat(window, window.decorView).isAppearanceLightNavigationBars = light
            } else {
                @Suppress("DEPRECATION")
                val flags = window.decorView.systemUiVisibility
                @Suppress("DEPRECATION")
                window.decorView.systemUiVisibility = if (light)
                    flags or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
                else
                    flags and View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR.inv()
            }
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()
}
