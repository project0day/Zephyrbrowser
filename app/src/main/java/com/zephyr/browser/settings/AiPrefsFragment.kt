package com.zephyr.browser.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.zephyr.browser.Prefs
import com.zephyr.browser.R
import com.zephyr.browser.ai.AiModelDialogs
import com.zephyr.browser.ai.AiMemory
import com.zephyr.browser.ai.AiModels
import com.zephyr.browser.ai.AiVoice
import com.zephyr.browser.ai.AiWorkspace

/**
 * Settings screen for the AI Assistant.
 *
 * v1.7.25: the old single-provider form (one key / one endpoint / one model) is
 * replaced by a LIST of model profiles — add as many providers and models as you
 * like and switch between them here or right inside the chat window.
 */
class AiPrefsFragment : Fragment() {

    private var col: LinearLayout? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val act = activity as? SettingsActivity ?: return View(context)
        val ctx = requireContext()
        val root = act.buildScreen(R.string.set_ai_assistant)
        val c = SettingsUi.screen(ctx)
        col = c
        root.addView(SettingsUi.scrollOf(c), LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        rebuild()
        return root
    }

    private fun rebuild() {
        val ctx = context ?: return
        val c = col ?: return
        c.removeAllViews()

        // v1.7.90: the single most common "why doesn't it work" state, and the
        // one that would turn into refunds and one-star reviews on a paid
        // listing. The assistant is a client, not a service — with no key it
        // cannot answer at all, and saying so here, once, beats explaining it
        // to every buyer afterwards. It disappears the moment a key exists.
        if (AiModels.list().none { AiModels.isConfigured(it) }) {
            c.addView(SettingsUi.banner(
                ctx, SettingsUi.Tone.WARN, R.drawable.ic_key, R.string.ai_no_key_banner
            ))
        }

        // ── saved models ─────────────────────────────────────────────────────
        c.addView(SettingsUi.sectionHeader(ctx, getString(R.string.ai_models)))
        val card = SettingsUi.groupCard(ctx)
        val profiles = AiModels.list()
        val activeIdx = AiModels.activeIndex()
        profiles.forEachIndexed { i, p ->
            val mark = if (i == activeIdx) "●  " else "○  "
            val row = SettingsUi.clickRow(
                ctx,
                R.drawable.ic_ai,
                mark + p.name,
                p.model
            ) {
                MaterialAlertDialogBuilder(ctx)
                    .setTitle(p.name)
                    .setItems(
                        arrayOf(
                            getString(R.string.ai_choose_model),
                            getString(R.string.ai_edit_model)
                        )
                    ) { _, which ->
                        if (which == 0) {
                            // v1.7.44 FIX: choosing a single model here left the
                            // swarm ON, so the swarm kept answering and the
                            // selected profile was never called at all.
                            Prefs.setSwarmEnabled(false)
                            AiModels.setActive(i)
                            rebuild()
                        }
                        else AiModelDialogs.editProfile(ctx, i) { rebuild() }
                    }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
            }
            card.addView(row)
        }
        card.addView(SettingsUi.clickRowSummary(ctx, R.drawable.ic_add, getString(R.string.ai_add_model), getString(R.string.h_ai_add_model)) {
            AiModelDialogs.editProfile(ctx, null) { rebuild() }
        })
        card.addView(SettingsUi.clickRowSummary(
            ctx, R.drawable.ic_sync, getString(R.string.agent_menu),
            getString(R.string.agent_sub)
        ) { (activity as? SettingsActivity)?.openScreen("agent") })
        card.addView(SettingsUi.clickRowSummary(
            ctx, R.drawable.ic_widgets, getString(R.string.swarm_title),
            getString(R.string.swarm_sub)
        ) { (activity as? SettingsActivity)?.openScreen("swarm") })
        c.addView(card)

        // ── behaviour ────────────────────────────────────────────────────────
        c.addView(SettingsUi.sectionHeader(ctx, getString(R.string.set_ai_prompt)))
        val card2 = SettingsUi.groupCard(ctx)
        card2.addView(SettingsUi.clickRowSummary(
            ctx, R.drawable.ic_edit, getString(R.string.set_ai_prompt),
            Prefs.aiPrompt.take(60)
        ) {
            editFieldDialog(getString(R.string.set_ai_prompt), Prefs.aiPrompt, "System instructions…") { newPrompt ->
                Prefs.setAiSettings(Prefs.aiApiKey, Prefs.aiEndpoint, Prefs.aiModel, newPrompt)
                Toast.makeText(ctx, R.string.saved, Toast.LENGTH_SHORT).show()
                rebuild()
            }
        })
        c.addView(card2)

        // ── generation tuning (v1.7.27) ──────────────────────────────────────
        c.addView(SettingsUi.sectionHeader(ctx, getString(R.string.ai_generation)))
        val card3 = SettingsUi.groupCard(ctx)
        card3.addView(SettingsUi.clickRow(
            ctx, R.drawable.ic_contrast, getString(R.string.ai_temperature),
            String.format(java.util.Locale.US, "%.2f", Prefs.aiTemperature / 100.0)
        ) {
            pickNumber(
                getString(R.string.ai_temperature),
                getString(R.string.ai_temperature_hint),
                intArrayOf(0, 20, 40, 70, 100, 130, 160, 200),
                Prefs.aiTemperature
            ) { v -> Prefs.setAiTemperature(v); rebuild() }
        })
        card3.addView(SettingsUi.clickRow(
            ctx, R.drawable.ic_storage, getString(R.string.ai_max_tokens),
            Prefs.aiMaxTokens.toString()
        ) {
            pickNumber(
                getString(R.string.ai_max_tokens),
                getString(R.string.ai_max_tokens_hint),
                intArrayOf(512, 1024, 2048, 4096, 8192, 16384),
                Prefs.aiMaxTokens
            ) { v -> Prefs.setAiMaxTokens(v); rebuild() }
        })
        card3.addView(SettingsUi.clickRow(
            ctx, R.drawable.ic_history, getString(R.string.ai_history_depth),
            getString(R.string.ai_history_depth_fmt, Prefs.aiHistoryDepth)
        ) {
            pickNumber(
                getString(R.string.ai_history_depth),
                getString(R.string.ai_history_depth_hint),
                intArrayOf(2, 6, 10, 20, 40, 100),
                Prefs.aiHistoryDepth
            ) { v -> Prefs.setAiHistoryDepth(v); rebuild() }
        })
        c.addView(card3)

        // ── behaviour toggles ────────────────────────────────────────────────
        c.addView(SettingsUi.sectionHeader(ctx, getString(R.string.ai_behaviour)))
        val card4 = SettingsUi.groupCard(ctx)
        card4.addView(SettingsUi.switchRow(
            ctx, R.drawable.ic_globe, getString(R.string.ai_web_access),
            getString(R.string.ai_web_access_sub), Prefs.aiWebAccess
        ) { on -> Prefs.setAiWebAccess(on) })
        card4.addView(SettingsUi.switchRow(
            ctx, R.drawable.ic_play, getString(R.string.ai_streaming),
            getString(R.string.ai_streaming_sub), Prefs.aiStreaming
        ) { on -> Prefs.setAiStreaming(on) })
        card4.addView(SettingsUi.switchRow(
            ctx, R.drawable.ic_info, getString(R.string.ai_show_usage),
            getString(R.string.ai_show_usage_sub), Prefs.aiShowUsage
        ) { on -> Prefs.setAiShowUsage(on) })
        card4.addView(SettingsUi.switchRow(
            ctx, R.drawable.ic_website, getString(R.string.ai_send_page),
            getString(R.string.ai_send_page_sub), Prefs.aiSendPageContext
        ) { on -> Prefs.setAiSendPageContext(on) })
        // v1.7.33: remote agent behaviour
        card4.addView(SettingsUi.switchRow(
            ctx, R.drawable.ic_sync, getString(R.string.agent_fallback),
            getString(R.string.agent_fallback_sub), Prefs.agentFallback
        ) { on -> Prefs.setAgentFallback(on) })
        card4.addView(SettingsUi.clickRow(
            ctx, R.drawable.ic_timer, getString(R.string.agent_timeout),
            getString(R.string.agent_timeout_fmt, Prefs.agentTimeoutSec)
        ) {
            pickNumber(
                getString(R.string.agent_timeout),
                getString(R.string.agent_timeout_hint),
                intArrayOf(30, 60, 120, 180, 300, 600),
                Prefs.agentTimeoutSec
            ) { v -> Prefs.setAgentTimeoutSec(v); rebuild() }
        })
        c.addView(card4)

        // ── what the assistant may DO (v1.7.41) ──────────────────────────────
        c.addView(SettingsUi.sectionHeader(ctx, getString(R.string.ai_abilities)))
        val card6 = SettingsUi.groupCard(ctx)
        card6.addView(SettingsUi.switchRow(
            ctx, R.drawable.ic_script, getString(R.string.ai_tools_page),
            getString(R.string.ai_tools_page_sub), Prefs.aiToolsPage
        ) { on -> Prefs.setAiToolsPage(on) })
        card6.addView(SettingsUi.switchRow(
            ctx, R.drawable.ic_tabs, getString(R.string.ai_tools_browser),
            getString(R.string.ai_tools_browser_sub), Prefs.aiToolsBrowser
        ) { on -> Prefs.setAiToolsBrowser(on) })
        card6.addView(SettingsUi.switchRow(
            ctx, R.drawable.ic_history, getString(R.string.ai_tools_data),
            getString(R.string.ai_tools_data_sub), Prefs.aiToolsData
        ) { on -> Prefs.setAiToolsData(on) })
        card6.addView(SettingsUi.switchRow(
            ctx, R.drawable.ic_key, getString(R.string.ai_tools_models),
            getString(R.string.ai_tools_models_sub), Prefs.aiToolsModels
        ) { on -> Prefs.setAiToolsModels(on) })
        // v1.7.54: writing files, and the fence around it
        card6.addView(SettingsUi.switchRow(
            ctx, R.drawable.ic_file, getString(R.string.ai_tools_write),
            getString(R.string.ai_tools_write_sub), Prefs.aiToolsWrite
        ) { on -> Prefs.setAiToolsWrite(on) })
        card6.addView(SettingsUi.switchRow(
            ctx, R.drawable.ic_folder_open, getString(R.string.ai_write_anywhere),
            getString(R.string.ai_write_anywhere_sub), Prefs.aiWriteAnywhere
        ) { on ->
            // leaving the workspace is the one write setting worth a warning
            if (on) {
                MaterialAlertDialogBuilder(ctx)
                    .setTitle(R.string.ai_write_anywhere)
                    .setMessage(R.string.ai_write_anywhere_warn)
                    .setPositiveButton(R.string.ok) { _, _ -> Prefs.setAiWriteAnywhere(true) }
                    .setNegativeButton(R.string.cancel) { _, _ -> rebuild() }
                    .setOnCancelListener { rebuild() }
                    .show()
            } else Prefs.setAiWriteAnywhere(false)
        })
        card6.addView(SettingsUi.switchRow(
            ctx, R.drawable.ic_script, getString(R.string.ai_tools_scripts),
            getString(R.string.ai_tools_scripts_sub), Prefs.aiToolsScripts
        ) { on -> Prefs.setAiToolsScripts(on) })
        card6.addView(SettingsUi.switchRow(
            ctx, R.drawable.ic_calendar, getString(R.string.ai_tools_tasks),
            getString(R.string.ai_tools_tasks_sub), Prefs.aiToolsTasks
        ) { on -> Prefs.setAiToolsTasks(on) })
        card6.addView(SettingsUi.clickRowSummary(
            ctx, R.drawable.ic_folder, getString(R.string.ai_workspace),
            AiWorkspace.rootPath()
        ) {
            MaterialAlertDialogBuilder(ctx)
                .setTitle(R.string.ai_workspace)
                .setMessage(AiWorkspace.rootPath() + "\n\n" + getString(R.string.ai_workspace_note))
                .setPositiveButton(R.string.ok, null)
                .show()
        })
        card6.addView(SettingsUi.switchRow(
            ctx, R.drawable.ic_lock_open, getString(R.string.ai_trust_actions),
            getString(R.string.ai_trust_actions_sub), Prefs.aiTrustActions
        ) { on ->
            if (on) {
                MaterialAlertDialogBuilder(ctx)
                    .setTitle(R.string.ai_trust_actions)
                    .setMessage(R.string.ai_trust_actions_warn)
                    .setPositiveButton(R.string.ok) { _, _ -> Prefs.setAiTrustActions(true) }
                    .setNegativeButton(R.string.cancel) { _, _ -> rebuild() }
                    .setOnCancelListener { rebuild() }
                    .show()
            } else Prefs.setAiTrustActions(false)
        })
        c.addView(card6)

        // ── voice input (v1.7.55) ────────────────────────────────────────────
        // Lives here, not under a "voice" heading, because it runs on the same
        // provider and the same key as everything else on this screen.
        // ── notifications (v1.7.80) ──────────────────────────────────────────
        // The answer arrives whether or not the browser is the window in front
        // of the user; this decides whether the phone says so out loud.
        c.addView(SettingsUi.sectionHeader(ctx, getString(R.string.ai_notify_section)))
        val cardN = SettingsUi.groupCard(ctx)
        cardN.addView(SettingsUi.switchRow(
            ctx, R.drawable.ic_ai, getString(R.string.ai_notify_on),
            getString(R.string.ai_notify_on_sub), Prefs.aiReplyNotify
        ) { on -> Prefs.setAiReplyNotify(on) })
        c.addView(cardN)

        c.addView(SettingsUi.sectionHeader(ctx, getString(R.string.voice_stt)))

        // v1.7.79 (user: «по дефолту у кого есть гугл сервисы пусть используется
        // гугловская, а у кого нету — в настройках возможность включить и
        // закачать твой вариант»). The engine picker states the order plainly
        // instead of leaving the user to guess which of three routes answered.
        val cardV = SettingsUi.groupCard(ctx)
        val sysOk = AiVoice.systemAvailable(ctx)
        cardV.addView(SettingsUi.clickRowSummary(
            ctx, R.drawable.ic_mic, getString(R.string.voice_engine),
            engineLabel(Prefs.voiceEngine, sysOk)
        ) { chooseVoiceEngine(sysOk) })
        cardV.addView(SettingsUi.clickRowSummary(
            ctx, R.drawable.ic_download, getString(R.string.voice_offline_models),
            offlineSummary()
        ) { showOfflineModels() })
        c.addView(cardV)
        // the honest version: say up front that the system recogniser is
        // Google's and that it is simply absent on a de-Googled phone
        c.addView(SettingsUi.note(ctx,
            if (sysOk) R.string.voice_engine_note else R.string.voice_engine_note_nosys))

        val card7 = SettingsUi.groupCard(ctx)
        card7.addView(SettingsUi.clickRowSummary(
            ctx, R.drawable.ic_mic, getString(R.string.voice_stt_endpoint),
            Prefs.sttEndpoint.ifBlank {
                AiVoice.endpoint().ifBlank { getString(R.string.voice_stt_auto) }
            }
        ) {
            editFieldDialog(getString(R.string.voice_stt_endpoint), Prefs.sttEndpoint,
                "https://api.groq.com/openai/v1/audio/transcriptions") { v ->
                Prefs.setSttEndpoint(v.trim()); rebuild()
            }
        })
        card7.addView(SettingsUi.clickRowSummary(
            ctx, R.drawable.ic_ai, getString(R.string.voice_stt_model),
            Prefs.sttModel.ifBlank { AiVoice.model() }
        ) {
            editFieldDialog(getString(R.string.voice_stt_model), Prefs.sttModel, "whisper-large-v3-turbo") { v ->
                Prefs.setSttModel(v.trim()); rebuild()
            }
        })
        card7.addView(SettingsUi.clickRowSummary(
            ctx, R.drawable.ic_key, getString(R.string.voice_stt_key),
            if (Prefs.sttKey.isNotBlank()) "••••••" else getString(R.string.voice_stt_auto)
        ) {
            editFieldDialog(getString(R.string.voice_stt_key), Prefs.sttKey, "gsk_… / sk-…") { v ->
                Prefs.setSttKey(v.trim()); rebuild()
            }
        })
        c.addView(card7)
        c.addView(SettingsUi.note(ctx, R.string.voice_stt_note))

        // ── memory across chats (v1.7.57) ────────────────────────────────────
        c.addView(SettingsUi.sectionHeader(ctx, getString(R.string.mem_title)))
        val card8 = SettingsUi.groupCard(ctx)
        card8.addView(SettingsUi.switchRow(
            ctx, R.drawable.ic_ai, getString(R.string.mem_on),
            getString(R.string.mem_on_sub), Prefs.aiMemoryOn
        ) { on -> Prefs.setAiMemoryOn(on) })
        card8.addView(SettingsUi.clickRowSummary(
            ctx, R.drawable.ic_list, getString(R.string.mem_view),
            getString(R.string.mem_count_fmt, AiMemory.all().size)
        ) { showMemory() })
        c.addView(card8)
        c.addView(SettingsUi.note(ctx, R.string.mem_note))
        c.addView(SettingsUi.note(ctx, R.string.ai_abilities_note))

        // ── data ─────────────────────────────────────────────────────────────
        c.addView(SettingsUi.sectionHeader(ctx, getString(R.string.ai_bk_section)))
        val card5 = SettingsUi.groupCard(ctx)
        card5.addView(SettingsUi.clickRowSummary(
            ctx, R.drawable.ic_upload, getString(R.string.ai_bk_export),
            getString(R.string.ai_bk_export_sub)
        ) { startExport() })
        card5.addView(SettingsUi.clickRowSummary(
            ctx, R.drawable.ic_download, getString(R.string.ai_bk_import),
            getString(R.string.ai_bk_import_sub)
        ) { startImport() })
        card5.addView(SettingsUi.clickRow(
            ctx, R.drawable.ic_delete, getString(R.string.ai_clear_all_chats), null
        ) {
            MaterialAlertDialogBuilder(ctx)
                .setTitle(R.string.ai_clear_all_chats)
                .setMessage(R.string.ai_clear_all_chats_msg)
                .setPositiveButton(R.string.delete) { _, _ ->
                    com.zephyr.browser.ai.AiChatStore.deleteAll(ctx)
                    Toast.makeText(ctx, R.string.saved, Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        })
        c.addView(card5)
    }

    /** Small picker used by the tuning rows: labelled presets + «свой вариант». */
    private fun pickNumber(
        title: String,
        hint: String,
        presets: IntArray,
        current: Int,
        onPick: (Int) -> Unit
    ) {
        val ctx = context ?: return
        val isTemp = presets.size == 8 && presets[0] == 0 && presets.last() == 200
        val labels = presets.map { v ->
            if (isTemp) String.format(java.util.Locale.US, "%.2f", v / 100.0) else v.toString()
        }.toTypedArray()
        MaterialAlertDialogBuilder(ctx)
            // v1.7.98: the hint must ride in the title — a dialog cannot show a
            // message AND a list, and setMessage was silently winning, leaving
            // these settings with nothing to choose from.
            .setCustomTitle(SettingsUi.dialogTitle(ctx, title, hint))
            .setSingleChoiceItems(labels, presets.indexOfFirst { it == current }) { d, which ->
                onPick(presets[which])
                d.dismiss()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()


    /**
     * v1.7.57: everything the assistant remembers, in one list.
     *
     * A memory the user cannot read is a memory they cannot correct, and a
     * wrong fact here steers every future answer — so each entry is editable
     * (tap) and deletable (✕), with the buttons in a fixed row under the list
     * rather than in the dialog's own stacking button bar.
     */
    private fun showMemory() {
        val ctx = context ?: return
        lateinit var dialog: androidx.appcompat.app.AlertDialog

        val listCol = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), 0, dp(16), dp(8))
        }
        fun fill() {
            listCol.removeAllViews()
            val facts = AiMemory.all()
            if (facts.isEmpty()) {
                listCol.addView(android.widget.TextView(ctx).apply {
                    setText(R.string.mem_empty)
                    textSize = 13f
                    alpha = 0.7f
                    gravity = android.view.Gravity.CENTER
                    setPadding(0, dp(40), 0, dp(40))
                })
                return
            }
            for (f in facts) {
                listCol.addView(LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    setPadding(dp(4), dp(10), dp(4), dp(10))
                    addView(android.widget.TextView(ctx).apply {
                        text = f.text
                        textSize = 14f
                        layoutParams = LinearLayout.LayoutParams(
                            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                        isClickable = true
                        setOnClickListener {
                            editFieldDialog(getString(R.string.mem_edit), f.text, "") { v ->
                                if (v.isBlank()) AiMemory.remove(f.id) else AiMemory.replace(f.id, v)
                                fill()
                            }
                        }
                    })
                    addView(android.widget.TextView(ctx).apply {
                        text = "✕"
                        textSize = 17f
                        setPadding(dp(12), dp(2), dp(6), dp(2))
                        isClickable = true
                        setOnClickListener { AiMemory.remove(f.id); fill() }
                    })
                })
            }
        }
        fill()

        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (ctx.resources.displayMetrics.heightPixels * 0.7f).toInt()
            )
            addView(android.widget.TextView(ctx).apply {
                setText(R.string.mem_view)
                textSize = 19f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                setPadding(dp(24), dp(20), dp(24), dp(10))
            })
            addView(android.widget.ScrollView(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
                addView(listCol)
            })
        }

        dialog = MaterialAlertDialogBuilder(ctx).setView(root).create()

        root.addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(12), dp(4), dp(12), dp(10))
            addView(android.widget.TextView(ctx).apply {
                setText(R.string.mem_clear)
                textSize = 14f
                gravity = android.view.Gravity.CENTER
                setPadding(dp(6), dp(12), dp(6), dp(12))
                isClickable = true
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                setOnClickListener { AiMemory.clear(); fill() }
            })
            addView(android.widget.TextView(ctx).apply {
                setText(R.string.mem_add)
                textSize = 14f
                gravity = android.view.Gravity.CENTER
                setPadding(dp(6), dp(12), dp(6), dp(12))
                isClickable = true
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                setOnClickListener {
                    editFieldDialog(getString(R.string.mem_add), "", getString(R.string.mem_hint)) { v ->
                        AiMemory.add(v); fill()
                    }
                }
            })
            addView(android.widget.TextView(ctx).apply {
                setText(R.string.ok)
                textSize = 14f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                gravity = android.view.Gravity.CENTER
                setPadding(dp(6), dp(12), dp(6), dp(12))
                isClickable = true
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                setOnClickListener { dialog.dismiss(); rebuild() }
            })
        })

        dialog.show()
    }
    private fun editFieldDialog(title: String, current: String, hintText: String, onSave: (String) -> Unit) {
        val ctx = context ?: return
        val field = EditText(ctx).apply {
            setText(current)
            hint = hintText
            maxLines = 6
        }
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(8), dp(22), dp(8))
            addView(field)
        }
        MaterialAlertDialogBuilder(ctx)
            .setTitle(title)
            .setView(box)
            .setPositiveButton(R.string.save) { _, _ -> onSave(field.text.toString().trim()) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // ================= v1.7.28: export / import of the AI setup =================

    private val exportLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/json")
    ) { uri -> if (uri != null) runExport(uri) }

    private val importLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) runImport(uri) }

    private var exportKeys = true
    private var exportChats = false
    private var exportPassword = ""

    /** Ask WHAT travels and whether the file is encrypted, then open the picker. */
    private fun startExport() {
        val ctx = context ?: return
        exportKeys = true; exportChats = false; exportPassword = ""

        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(8), dp(22), dp(4))
        }
        val keysCb = android.widget.CheckBox(ctx).apply {
            text = getString(R.string.ai_bk_include_keys)
            isChecked = true
        }
        val chatsCb = android.widget.CheckBox(ctx).apply {
            text = getString(R.string.ai_bk_include_chats)
            isChecked = false
        }
        val pwField = EditText(ctx).apply {
            hint = getString(R.string.ai_bk_password_hint)
            setSingleLine(true)
            transformationMethod = android.text.method.PasswordTransformationMethod()
        }
        // v1.7.90: a banner, not a note. Handing someone this file hands them
        // billing access to your provider account — that is a consequence, and
        // a grey line under a checkbox is not where consequences belong.
        val warn = SettingsUi.banner(
            ctx, SettingsUi.Tone.DANGER, R.drawable.ic_key, R.string.ai_bk_key_warning
        ).apply {
            // inside a dialog the screen-edge margins would be wrong
            (layoutParams as LinearLayout.LayoutParams).setMargins(0, dp(10), 0, 0)
        }
        box.addView(keysCb); box.addView(chatsCb); box.addView(pwField); box.addView(warn)

        MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.ai_bk_export)
            .setView(android.widget.ScrollView(ctx).apply { addView(box) })
            .setPositiveButton(R.string.save) { _, _ ->
                exportKeys = keysCb.isChecked
                exportChats = chatsCb.isChecked
                exportPassword = pwField.text.toString()
                try { exportLauncher.launch(com.zephyr.browser.ai.AiBackup.fileName()) }
                catch (_: Exception) { toast(getString(R.string.backup_failed)) }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun runExport(uri: android.net.Uri) {
        val ctx = context ?: return
        try {
            val bytes = com.zephyr.browser.ai.AiBackup.export(ctx, exportKeys, exportChats, exportPassword)
            ctx.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
            toast(getString(R.string.ai_bk_exported))
        } catch (t: Throwable) {
            toast(getString(R.string.backup_failed))
        } finally {
            exportPassword = ""
        }
    }

    private fun startImport() {
        try { importLauncher.launch(arrayOf("application/json", "application/octet-stream", "text/*")) }
        catch (_: Exception) { toast(getString(R.string.backup_failed)) }
    }

    private fun runImport(uri: android.net.Uri) {
        val ctx = context ?: return
        val raw = try {
            ctx.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
        } catch (_: Throwable) { null }
        if (raw.isNullOrBlank() || !com.zephyr.browser.ai.AiBackup.looksLikeOurs(raw)) {
            toast(getString(R.string.ai_bk_not_ours)); return
        }
        if (com.zephyr.browser.ai.AiBackup.isEncrypted(raw)) {
            val field = EditText(ctx).apply {
                hint = getString(R.string.ai_bk_password_hint)
                setSingleLine(true)
                transformationMethod = android.text.method.PasswordTransformationMethod()
            }
            MaterialAlertDialogBuilder(ctx)
                .setTitle(R.string.ai_bk_password)
                .setView(LinearLayout(ctx).apply {
                    setPadding(dp(22), dp(10), dp(22), dp(4)); addView(field)
                })
                .setPositiveButton(R.string.ai_bk_import) { _, _ ->
                    askMergeMode(raw, field.text.toString())
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        } else askMergeMode(raw, "")
    }

    private fun askMergeMode(raw: String, password: String) {
        val ctx = context ?: return
        MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.ai_bk_import)
            .setMessage(R.string.ai_bk_import_mode)
            .setPositiveButton(R.string.ai_bk_merge) { _, _ -> applyImport(raw, password, false) }
            .setNeutralButton(R.string.ai_bk_replace) { _, _ -> applyImport(raw, password, true) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun applyImport(raw: String, password: String, replace: Boolean) {
        val ctx = context ?: return
        val res = com.zephyr.browser.ai.AiBackup.import(ctx, raw, password, replace)
        if (!res.ok) {
            toast(res.error ?: getString(R.string.backup_failed))
            return
        }
        rebuild()
        val parts = ArrayList<String>()
        parts.add(getString(R.string.ai_bk_done_models, res.profiles))
        if (res.chats > 0) parts.add(getString(R.string.ai_bk_done_chats, res.chats))
        if (!res.hadKeys) parts.add(getString(R.string.ai_bk_no_keys))
        MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.ai_bk_imported)
            .setMessage(parts.joinToString("\n"))
            .setPositiveButton(R.string.close, null)
            .show()
    }

    private fun toast(s: String) {
        context?.let { Toast.makeText(it, s, Toast.LENGTH_SHORT).show() }
    }

    // ── voice engine (v1.7.79) ───────────────────────────────────────────────

    /**
     * What the picker row reads. "Auto" is spelled out rather than left as a
     * word, because which engine "auto" lands on depends on the phone, and a
     * row that just said "Автоматически" would not tell the user which one is
     * actually going to answer when they press the microphone.
     */
    private fun engineLabel(engine: String, sysOk: Boolean): String {
        val ctx = context ?: return ""
        return when (engine) {
            "system" -> getString(R.string.voice_engine_system)
            "offline" -> getString(R.string.voice_engine_offline)
            "cloud" -> getString(R.string.voice_engine_cloud)
            else -> getString(
                R.string.voice_engine_auto_fmt,
                when {
                    sysOk -> getString(R.string.voice_engine_system)
                    com.zephyr.browser.ai.AiVosk.ready(ctx) -> getString(R.string.voice_engine_offline)
                    else -> getString(R.string.voice_engine_cloud)
                }
            )
        }
    }

    private fun chooseVoiceEngine(sysOk: Boolean) {
        val ctx = context ?: return
        val codes = arrayOf("auto", "system", "offline", "cloud")
        val labels = arrayOf(
            engineLabel("auto", sysOk),
            getString(R.string.voice_engine_system) +
                if (sysOk) "" else "  —  " + getString(R.string.voice_engine_absent),
            getString(R.string.voice_engine_offline) +
                if (com.zephyr.browser.ai.AiVosk.ready(ctx)) "" else "  —  " + getString(R.string.voice_engine_absent),
            getString(R.string.voice_engine_cloud)
        )
        MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.voice_engine)
            .setSingleChoiceItems(labels, codes.indexOf(Prefs.voiceEngine).coerceAtLeast(0)) { d, which ->
                Prefs.setVoiceEngine(codes[which])
                d.dismiss()
                rebuild()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun offlineSummary(): String {
        val ctx = context ?: return ""
        val have = com.zephyr.browser.ai.AiVosk.installedLangs(ctx)
        return if (have.isEmpty()) getString(R.string.voice_offline_none)
        else have.joinToString(", ") { it.title }
    }

    /**
     * The download list. Each row carries its own size, because these are
     * 35–75 MB over what may well be mobile data — the user gets to see the
     * cost before the transfer, not after.
     */
    private fun showOfflineModels() {
        val ctx = context ?: return
        val act = activity ?: return
        val langs = com.zephyr.browser.ai.AiVosk.LANGS
        val labels = langs.map { l ->
            if (com.zephyr.browser.ai.AiVosk.installed(ctx, l.code)) {
                val mb = (com.zephyr.browser.ai.AiVosk.sizeOnDisk(ctx, l.code) / (1024 * 1024)).toInt()
                "✓  " + l.title + "   (" + getString(R.string.voice_model_installed_fmt, mb) + ")"
            } else {
                "↓  " + l.title + "   (" + l.mb + " MB)"
            }
        }.toTypedArray()

        MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.voice_offline_models)
            .setItems(labels) { _, which ->
                val l = langs[which]
                if (com.zephyr.browser.ai.AiVosk.installed(ctx, l.code)) {
                    MaterialAlertDialogBuilder(ctx)
                        .setTitle(l.title)
                        .setItems(
                            arrayOf(
                                getString(R.string.voice_model_use),
                                getString(R.string.voice_model_delete)
                            )
                        ) { _, pick ->
                            if (pick == 0) {
                                Prefs.setVoiceLang(l.code)
                                if (Prefs.voiceEngine == "auto" && AiVoice.systemAvailable(ctx)) {
                                    // choosing a model explicitly is a request to
                                    // USE it; auto would keep deferring to Google
                                    Prefs.setVoiceEngine("offline")
                                }
                            } else {
                                com.zephyr.browser.ai.AiVosk.delete(ctx, l.code)
                            }
                            rebuild()
                        }
                        .setNegativeButton(R.string.cancel, null)
                        .show()
                } else {
                    com.zephyr.browser.ai.AiVosk.download(act, l.code) { rebuild() }
                }
            }
            .setNegativeButton(R.string.close, null)
            .show()
    }
}
