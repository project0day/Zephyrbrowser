package com.zephyr.browser.ai

import android.content.Context
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.zephyr.browser.Prefs
import com.zephyr.browser.R

/**
 * v1.7.50: ready-made modes for the assistant.
 *
 * Changing how it answers meant opening Settings and rewriting the system
 * prompt by hand — so in practice nobody did. These are the four shapes that
 * actually get used, switchable from the chat header in one tap.
 */
object AiPromptPresets {

    data class Preset(val id: String, val title: String, val prompt: String)

    fun all(ctx: Context): List<Preset> = listOf(
        Preset(
            "default", ctx.getString(R.string.preset_default),
            "You are Zephyr AI Assistant, a helpful, fast, and concise web browsing companion."
        ),
        // v1.7.86: the prompts themselves are English now. They were Russian,
        // which pushed every model towards answering in Russian whoever was
        // asking — and the translator preset was worse than that: it said
        // «переводи на русский» outright, so a French user who picked
        // "Traducteur" got Russian. Where a preset needs to name a language it
        // now names the interface's, through [AiLang].
        Preset(
            "short", ctx.getString(R.string.preset_short),
            "Answer as briefly as possible: the substance in two or three sentences, " +
                "with no preamble, no restating of the question and no closing summary. " +
                "Use a list only when there are more than three items. " +
                "Reply in the same language the user writes in."
        ),
        Preset(
            "translate", ctx.getString(R.string.preset_translate),
            "You are a translator. Translate any text the user sends into " +
                AiLang.name(ctx) + "; if it is already in " + AiLang.name(ctx) + ", " +
                "translate it into English instead. Output the translation only, with no " +
                "explanations, and keep the original markup and formatting intact."
        ),
        Preset(
            "code", ctx.getString(R.string.preset_code),
            "You review code. Explain what a fragment does, where its faults are and how to " +
                "fix them. Give code as whole working blocks in ``` fences with the language " +
                "named. Never invent APIs that do not exist — say so when you are unsure. " +
                "Reply in the same language the user writes in."
        ),
        Preset(
            "facts", ctx.getString(R.string.preset_facts),
            "Answer only with what you can confirm. For anything that may have changed, " +
                "search the web first and cite your sources with links. When there is no " +
                "evidence, say you do not know rather than guessing. " +
                "Reply in the same language the user writes in."
        )
    )

    /** Which preset the current global prompt matches, or null when custom. */
    fun current(ctx: Context): Preset? =
        all(ctx).firstOrNull { it.prompt.trim() == Prefs.aiPrompt.trim() }

    fun pick(ctx: Context, onChanged: () -> Unit) {
        val items = all(ctx)
        val cur = current(ctx)
        val labels = items.map { it.title }.toTypedArray()
        MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.preset_title)
            .setSingleChoiceItems(labels, items.indexOf(cur)) { d, which ->
                Prefs.setAiSettings(
                    Prefs.aiApiKey, Prefs.aiEndpoint, Prefs.aiModel, items[which].prompt
                )
                d.dismiss()
                onChanged()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
}
