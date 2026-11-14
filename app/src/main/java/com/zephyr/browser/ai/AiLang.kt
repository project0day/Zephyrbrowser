package com.zephyr.browser.ai

import android.content.Context

/**
 * v1.7.86: the name of the user's language, written in English.
 *
 * Instructions to the model are in English — that is what models follow most
 * reliably, and it is what the tool schemas are in. But an instruction like
 * "translate this page" has to name a target, and until now several of them
 * named Russian outright: a French user who tapped "Traduire la page" was told,
 * in the prompt they never saw, to translate it into Russian.
 *
 * So the instruction stays English and the target becomes whatever the
 * interface is set to. English names are used because the model resolves
 * "French" far more reliably than "Français" inside an English sentence.
 */
object AiLang {

    private val NAMES = mapOf(
        "ru" to "Russian",
        "en" to "English",
        "fr" to "French",
        "de" to "German",
        "es" to "Spanish",
        "it" to "Italian",
        "pt" to "Portuguese",
        "uk" to "Ukrainian",
        "pl" to "Polish",
        "tr" to "Turkish",
        "nl" to "Dutch",
        "ar" to "Arabic",
        "zh" to "Chinese",
        "ja" to "Japanese"
    )

    /**
     * The language the interface is currently in.
     *
     * Read from the resources rather than from the system locale, because the
     * app has its own language picker: a user whose phone is in English may
     * well have set the browser to French, and it is the browser's setting they
     * expect their assistant to follow.
     */
    fun code(ctx: Context): String = try {
        val locales = ctx.resources.configuration.locales
        if (locales.isEmpty) "en" else locales.get(0).language.lowercase()
    } catch (_: Throwable) { "en" }

    /** That language's English name, for use inside an English prompt. */
    fun name(ctx: Context): String {
        val c = code(ctx)
        NAMES[c]?.let { return it }
        // an unlisted language still has a name the platform knows
        return try {
            java.util.Locale(c).getDisplayLanguage(java.util.Locale.ENGLISH)
                .ifBlank { "English" }
        } catch (_: Throwable) { "English" }
    }

    /** As above, without a Context — for the singletons that have none. */
    fun name(): String {
        val c = com.zephyr.browser.App.ctx ?: return "English"
        return name(c)
    }

    /** The interface language code, without a Context. */
    fun code(): String {
        val c = com.zephyr.browser.App.ctx ?: return "en"
        return code(c)
    }
}
