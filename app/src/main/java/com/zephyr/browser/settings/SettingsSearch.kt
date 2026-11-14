package com.zephyr.browser.settings

import android.content.Context
import android.graphics.Typeface
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.zephyr.browser.R

/**
 * v1.7.71 (user: «в настройках надо добавить иконку поиска, чтобы любую функцию
 * найти быстро»).
 *
 * There are well over a hundred settings across fourteen screens, and the real
 * cost is not the scrolling — it is that people do not know a setting exists.
 * Someone looking for "будильник" will not guess it lives under «Дела».
 *
 * The index is written by hand rather than scraped from the built screens. That
 * is a deliberate trade: the screens are constructed imperatively, so there is
 * nothing to walk without building all fourteen of them, and a hand-written
 * list can carry the words people actually search for — "пароль", "реклама",
 * "будильник" — which are often not the words on the button.
 *
 * The cost is honest too: a new screen has to be added here, or it will not be
 * findable. Which is why each entry is one line.
 */
object SettingsSearch {

    /**
     * @param title what the user sees
     * @param screen settings screen id, or "panel:<id>" for a panel
     * @param where the screen's own name, shown as the subtitle
     * @param keys extra words that should find this entry
     */
    class Entry(val title: String, val screen: String, val where: String, val keys: String = "")

    fun index(ctx: Context): List<Entry> {
        fun s(id: Int) = ctx.getString(id)
        val general = s(R.string.set_general)
        val appearance = s(R.string.set_appearance)
        val privacy = s(R.string.set_privacy)
        val ai = s(R.string.ai_chat)

        return listOf(
            // ── personalization ──────────────────────────────────────────────
            Entry(general, "general", general, s(R.string.ss_k01)),
            Entry(appearance, "appearance", appearance, s(R.string.ss_k02)),
            Entry(s(R.string.set_home_screen), "home", s(R.string.set_home_screen), s(R.string.ss_k03)),
            Entry(s(R.string.set_menu), "menu", s(R.string.set_menu), s(R.string.ss_k04)),

            // ── assistant ────────────────────────────────────────────────────
            Entry(ai, "ai", ai, s(R.string.ss_k05)),
            Entry(s(R.string.ai_abilities), "ai", ai, s(R.string.ss_k06)),
            Entry(s(R.string.mem_title), "ai", ai, s(R.string.ss_k07)),
            Entry(s(R.string.voice_stt), "ai", ai, s(R.string.ss_k08)),
            Entry(s(R.string.ai_workspace), "ai", ai, s(R.string.ss_k09)),
            Entry(s(R.string.ai_tools_scripts), "ai", ai, s(R.string.ss_k10)),
            Entry(s(R.string.agent_title), "agent", ai, s(R.string.ss_k11)),
            Entry(s(R.string.swarm_title), "swarm", ai, s(R.string.ss_k12)),

            // ── privacy & protection ─────────────────────────────────────────
            Entry(privacy, "privacy", privacy, s(R.string.ss_k13)),
            Entry(s(R.string.burn_settings), "privacy", privacy, s(R.string.ss_k14)),
            Entry(s(R.string.set_security), "security", s(R.string.set_security), s(R.string.ss_k15)),
            Entry(s(R.string.set_adblock), "adblock", s(R.string.set_adblock), s(R.string.ss_k16)),
            Entry(s(R.string.set_site), "site", s(R.string.set_site), s(R.string.ss_k17)),
            Entry(s(R.string.set_proxy), "proxy", s(R.string.set_proxy), s(R.string.ss_k18)),
            Entry(s(R.string.set_dns), "dns", s(R.string.set_dns), s(R.string.ss_k19)),

            // ── data ─────────────────────────────────────────────────────────
            Entry(s(R.string.cache_title), "cache", s(R.string.grp_data_panels), s(R.string.ss_k20)),
            Entry(s(R.string.set_clear), "clear", s(R.string.grp_data_panels), s(R.string.ss_k21)),
            Entry(s(R.string.set_backup), "backup", s(R.string.grp_data_panels), s(R.string.ss_k22)),

            // ── panels ───────────────────────────────────────────────────────
            Entry(s(R.string.panel_bookmarks), "panel:bookmarks", s(R.string.grp_data_panels), s(R.string.ss_k23)),
            Entry(s(R.string.panel_history), "panel:history", s(R.string.grp_data_panels), s(R.string.ss_k24)),
            Entry(s(R.string.panel_downloads), "panel:downloads", s(R.string.grp_data_panels), s(R.string.ss_k25)),
            Entry(s(R.string.panel_passwords), "panel:passwords", s(R.string.grp_data_panels), s(R.string.ss_k26)),
            Entry(s(R.string.panel_payments), "panel:payments", s(R.string.grp_data_panels), s(R.string.ss_k27)),
            Entry(s(R.string.panel_autofill), "panel:autofill", s(R.string.grp_data_panels), s(R.string.ss_k28)),
            Entry(s(R.string.panel_scripts), "panel:scripts", s(R.string.grp_data_panels), s(R.string.ss_k29)),
            Entry(s(R.string.panel_tasks), "panel:tasks", s(R.string.grp_data_panels), s(R.string.ss_k30)),
            Entry(s(R.string.panel_templates), "panel:templates", s(R.string.grp_data_panels), s(R.string.ss_k31)),
            Entry(s(R.string.panel_workspace), "panel:workspace", s(R.string.grp_data_panels), s(R.string.ss_k32)),
            Entry(s(R.string.panel_workflows), "panel:workflows", s(R.string.grp_data_panels), s(R.string.ss_k33)),
            Entry(s(R.string.files_title), "panel:files", s(R.string.grp_data_panels), s(R.string.ss_k34)),
            Entry(s(R.string.set_offline), "panel:offline", s(R.string.grp_data_panels), s(R.string.ss_k35)),
            Entry(s(R.string.reader_lib_title), "panel:library", s(R.string.grp_data_panels), s(R.string.ss_k36)),

            Entry(s(R.string.set_about), "about", s(R.string.set_about), s(R.string.ss_k37))
        )
    }

    /**
     * Rank matches so the obvious answer is first.
     *
     * A title that STARTS with the query beats one that merely contains it,
     * and both beat a keyword-only hit — otherwise typing "пар" offers
     * "Автозаполнение" (which mentions passwords) above "Пароли".
     */
    fun search(ctx: Context, raw: String): List<Entry> {
        val q = raw.trim().lowercase()
        if (q.length < 2) return emptyList()
        val scored = ArrayList<Pair<Int, Entry>>()
        for (e in index(ctx)) {
            val title = e.title.lowercase()
            val score = when {
                title.startsWith(q) -> 100
                title.contains(q) -> 70
                e.keys.lowercase().split(' ').any { it.startsWith(q) } -> 50
                e.keys.lowercase().contains(q) -> 30
                e.where.lowercase().contains(q) -> 10
                else -> 0
            }
            if (score > 0) scored.add(score to e)
        }
        return scored.sortedByDescending { it.first }.map { it.second }.take(20)
    }

    /** The search sheet: type, tap, land on the setting. */
    fun show(activity: SettingsActivity) {
        val ctx: Context = activity
        val d = { v: Int -> (v * ctx.resources.displayMetrics.density + 0.5f).toInt() }

        val results = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(d(8), d(4), d(8), d(8))
        }
        val field = EditText(ctx).apply {
            setHint(R.string.set_search_hint)
            setSingleLine(true)
            textSize = 16f
        }
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(d(20), d(16), d(20), d(4))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (ctx.resources.displayMetrics.heightPixels * 0.72f).toInt()
            )
            addView(field)
            addView(ScrollView(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
                addView(results)
            })
        }

        val dialog = MaterialAlertDialogBuilder(ctx)
            .setView(root)
            .setNegativeButton(R.string.cancel, null)
            .create()

        fun fill(q: String) {
            results.removeAllViews()
            val hits = search(ctx, q)
            if (q.trim().length < 2) {
                results.addView(TextView(ctx).apply {
                    setText(R.string.set_search_start)
                    textSize = 13f
                    alpha = 0.7f
                    gravity = Gravity.CENTER
                    setPadding(0, d(40), 0, d(40))
                })
                return
            }
            if (hits.isEmpty()) {
                results.addView(TextView(ctx).apply {
                    setText(R.string.set_search_none)
                    textSize = 13f
                    alpha = 0.7f
                    gravity = Gravity.CENTER
                    setPadding(0, d(40), 0, d(40))
                })
                return
            }
            for (e in hits) {
                results.addView(LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(d(10), d(11), d(10), d(11))
                    isClickable = true
                    background = SettingsUi.ripple(ctx)
                    addView(TextView(ctx).apply {
                        text = e.title
                        textSize = 15f
                        typeface = Typeface.DEFAULT_BOLD
                        setTextColor(SettingsUi.themedColor(ctx, R.attr.colorOnSurface))
                    })
                    addView(TextView(ctx).apply {
                        text = e.where
                        textSize = 12f
                        setTextColor(SettingsUi.themedColor(ctx, R.attr.colorOnSurfaceVariant))
                    })
                    setOnClickListener {
                        dialog.dismiss()
                        activity.goTo(e.screen)
                    }
                })
            }
        }

        field.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) = fill(s?.toString().orEmpty())
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
        fill("")
        dialog.show()
        field.requestFocus()
    }
}
