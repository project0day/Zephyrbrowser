package com.zephyr.browser.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.zephyr.browser.R

/**
 * v1.7.84: shows one of the legal documents that ship with the app.
 *
 * The texts live in `assets/legal` rather than in `strings.xml`. They are long,
 * they are paragraphs rather than labels, and they have to be read and revised
 * as documents — none of which the resource system is for. Keeping them as
 * files also means the exact text that shipped in a version can be diffed
 * against the next one, which matters for a policy.
 *
 * Bundled rather than linked to a website on purpose: the policy is then always
 * the one belonging to the installed version, it opens with no network, and
 * there is no address that can quietly go dead a year from now. Google Play
 * separately requires a policy URL in the listing — that is a listing field,
 * not a substitute for this.
 */
class LegalPrefsFragment : Fragment() {

    companion object {
        const val ARG_DOC = "doc"

        /** `privacy`, `terms` or `licenses`. */
        fun of(doc: String): LegalPrefsFragment = LegalPrefsFragment().apply {
            arguments = Bundle().apply { putString(ARG_DOC, doc) }
        }
    }

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        val act = activity as? SettingsActivity ?: return View(context)
        val ctx = requireContext()
        val doc = arguments?.getString(ARG_DOC) ?: "privacy"

        val root = act.buildScreen(
            when (doc) {
                "terms" -> R.string.about_terms
                "licenses" -> R.string.about_licenses
                else -> R.string.about_privacy
            }
        )
        val col = SettingsUi.screen(ctx)
        root.addView(
            SettingsUi.scrollOf(col),
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        )

        val d = { v: Int -> (v * resources.displayMetrics.density + 0.5f).toInt() }
        col.addView(TextView(ctx).apply {
            text = load(doc)
            // monospace: these texts are laid out with hard line breaks and
            // rules, and a proportional face turns that into ragged noise
            typeface = android.graphics.Typeface.MONOSPACE
            textSize = 12f
            setTextIsSelectable(true)
            setLineSpacing(d(2).toFloat(), 1f)
            setTextColor(SettingsUi.themedColor(ctx, R.attr.colorOnSurface))
            setPadding(d(20), d(12), d(20), d(32))
        })
        return root
    }

    /**
     * The document in the reader's language, falling back to English.
     *
     * Licences are English-only by nature — they are notices reproduced from
     * their authors, not prose to translate.
     */
    private fun load(doc: String): String {
        val ctx = context ?: return ""
        val lang = java.util.Locale.getDefault().language
        val names = if (doc == "licenses") {
            listOf("legal/licenses.txt")
        } else {
            listOf("legal/${doc}_$lang.txt", "legal/${doc}_en.txt")
        }
        for (name in names) {
            try {
                ctx.assets.open(name).use { return it.readBytes().toString(Charsets.UTF_8) }
            } catch (_: Throwable) {
                // try the next candidate; a missing translation is not an error
            }
        }
        return getString(R.string.about_doc_missing)
    }
}
