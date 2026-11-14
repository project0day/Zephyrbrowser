package com.zephyr.browser.ai

import com.zephyr.browser.Prefs
import com.zephyr.browser.R
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * v1.7.70: the details that go on every document a self-employed person sends.
 *
 * Name, SIRET, address, IBAN, VAT rate — typed once, not re-typed per quote.
 * They live apart from [AiMemory] on purpose: memory is prose the assistant
 * writes about the user, this is a form the user owns, and mixing the two makes
 * both worse.
 *
 * The numbering is the part that quietly matters. Document numbers have to be
 * unique and sequential — in France a gap or a duplicate in invoice numbering
 * is an accounting problem, not a cosmetic one — and it is exactly what a
 * person copying last month's file gets wrong. The counter lives here, resets
 * with the year, and hands out one number at a time.
 */
object AiBusiness {

    /**
     * Field key → label resource, in the order the settings screen shows them.
     *
     * v1.7.88: these were Russian literals, so the "My business details" form
     * was in Russian for everyone. Unlike the headings that go INSIDE a
     * document (see DocLang), these are interface — the user filling the form
     * in is the one reading it — so they are ordinary resources.
     */
    val FIELDS: List<Pair<String, Int>> = listOf(
        "company" to R.string.biz_company,
        "person" to R.string.biz_person,
        "address" to R.string.biz_address,
        "phone" to R.string.biz_phone,
        "email" to R.string.biz_email,
        "siret" to R.string.biz_siret,
        "vat" to R.string.biz_vat,
        "vat_rate" to R.string.biz_vat_rate,
        "iban" to R.string.biz_iban,
        "bic" to R.string.biz_bic,
        "website" to R.string.biz_website,
        "terms" to R.string.biz_terms
    )

    private fun data(): JSONObject = try {
        JSONObject(Prefs.aiBusiness.ifBlank { "{}" })
    } catch (_: Throwable) { JSONObject() }

    fun get(key: String): String = data().optString(key)

    fun all(): Map<String, String> {
        val o = data()
        val out = LinkedHashMap<String, String>()
        for ((k, _) in FIELDS) o.optString(k).takeIf { it.isNotBlank() }?.let { out[k] = it }
        return out
    }

    fun set(key: String, value: String) {
        val o = data()
        if (value.isBlank()) o.remove(key) else o.put(key, value.trim())
        Prefs.setAiBusiness(o.toString())
    }

    fun isEmpty(): Boolean = all().isEmpty()

    // ---------------- numbering ----------------

    /**
     * Next number for a document kind, e.g. "devis" or "facture".
     *
     * Format: KIND-YYYY-NNN, restarting at 001 each January — the shape most
     * accountants expect and the one that stays sortable.
     */
    fun nextNumber(kind: String): String {
        val k = kind.trim().lowercase().ifBlank { "doc" }
        val year = Calendar.getInstance().get(Calendar.YEAR)
        val o = try { JSONObject(Prefs.aiDocNumbers.ifBlank { "{}" }) } catch (_: Throwable) { JSONObject() }
        val slot = o.optJSONObject(k) ?: JSONObject()
        val storedYear = slot.optInt("year", year)
        val n = if (storedYear != year) 1 else slot.optInt("n", 0) + 1
        slot.put("year", year).put("n", n)
        o.put(k, slot)
        Prefs.setAiDocNumbers(o.toString())
        return k.uppercase() + "-" + year + "-" + String.format(Locale.US, "%03d", n)
    }

    /** What the next number WOULD be, without consuming it. */
    fun peekNumber(kind: String): String {
        val k = kind.trim().lowercase().ifBlank { "doc" }
        val year = Calendar.getInstance().get(Calendar.YEAR)
        val o = try { JSONObject(Prefs.aiDocNumbers.ifBlank { "{}" }) } catch (_: Throwable) { JSONObject() }
        val slot = o.optJSONObject(k) ?: JSONObject()
        val n = if (slot.optInt("year", year) != year) 1 else slot.optInt("n", 0) + 1
        return k.uppercase() + "-" + year + "-" + String.format(Locale.US, "%03d", n)
    }

    fun resetNumbering() = Prefs.setAiDocNumbers("{}")

    // ---------------- for the assistant ----------------

    // ---------------- signature ----------------

    /**
     * v1.7.73 (user: «…и подпиши за меня, и всё»).
     *
     * That sentence only works if a signature exists as an image the layout can
     * place. It is stored once — drawn with a finger or imported from a photo —
     * and reaches templates as `{{signature}}`, already a data: URI, because a
     * file path would not survive being rendered offline.
     */
    fun signatureFile(ctx: android.content.Context): java.io.File =
        java.io.File(ctx.filesDir, "signature.png")

    fun hasSignature(ctx: android.content.Context): Boolean =
        signatureFile(ctx).let { it.isFile && it.length() > 0 }

    fun signatureDataUri(ctx: android.content.Context): String? = try {
        val f = signatureFile(ctx)
        if (!f.isFile || f.length() == 0L) null
        else "data:image/png;base64," +
            android.util.Base64.encodeToString(f.readBytes(), android.util.Base64.NO_WRAP)
    } catch (_: Throwable) { null }

    fun saveSignature(ctx: android.content.Context, bitmap: android.graphics.Bitmap): Boolean = try {
        java.io.FileOutputStream(signatureFile(ctx)).use { out ->
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
        }
        true
    } catch (_: Throwable) { false }

    fun clearSignature(ctx: android.content.Context) {
        try { signatureFile(ctx).delete() } catch (_: Throwable) {}
    }

    /** Values every template can rely on without asking the user. */
    fun autoValues(ctx: android.content.Context?, kind: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        out.putAll(all())
        val today = Date()
        out["date"] = SimpleDateFormat("dd.MM.yyyy", Locale.getDefault()).format(today)
        out["date_long"] = SimpleDateFormat("d MMMM yyyy", Locale.getDefault()).format(today)
        out["year"] = SimpleDateFormat("yyyy", Locale.US).format(today)
        if (kind.isNotBlank()) out["number"] = peekNumber(kind)
        // an <img> with an empty src draws a broken-image icon on the document,
        // so a missing signature becomes nothing at all instead
        ctx?.let { c ->
            out["signature"] = signatureDataUri(c)
                ?: "data:image/gif;base64,R0lGODlhAQABAIAAAAAAAP///yH5BAEAAAAALAAAAAABAAEAAAIBRAA7"
        }
        return out
    }

    fun describe(): String {
        val v = all()
        if (v.isEmpty()) return "Реквизиты пользователя не заполнены. Их можно внести в " +
            "«Шаблоны» → «Мои реквизиты», и тогда они будут подставляться в документы сами."
        val sb = StringBuilder("Реквизиты пользователя (подставляй в документы сам, не спрашивай):\n")
        for ((k, label) in FIELDS) v[k]?.let { sb.append("• ").append(label).append(": ").append(it).append('\n') }
        return sb.toString()
    }
}
