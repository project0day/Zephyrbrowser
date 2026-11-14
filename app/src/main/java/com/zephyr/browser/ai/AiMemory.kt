package com.zephyr.browser.ai

import com.zephyr.browser.Prefs
import org.json.JSONArray
import org.json.JSONObject

/**
 * v1.7.57 (my own proposal, accepted: «агент забывает тебя между чатами — нужен
 * файл памяти, как в Claude Code»).
 *
 * Every conversation used to start from nothing: that the phone runs microG,
 * that the user writes Kotlin, what they are building — all re-explained each
 * time. This is the small set of durable facts that travels with every request.
 *
 * Three rules keep it from becoming a junk drawer, because a memory that grows
 * without limit is one that poisons every future answer:
 *
 *  - SMALL. [MAX_FACTS] entries, each capped at [MAX_LEN] characters, and the
 *    whole block is injected as plain text, so its cost per request is visible
 *    and bounded;
 *  - EXPLICIT. The assistant writes here only through the remember_fact tool,
 *    and the tool description tells it to save durable facts about the user —
 *    not the contents of the current task;
 *  - VISIBLE. Everything can be listed, edited and deleted from settings. A
 *    memory the user cannot read is a memory they cannot correct.
 *
 * Stored as JSON in Prefs rather than a Room table: it is a handful of short
 * strings, read on every request and written rarely.
 */
object AiMemory {

    const val MAX_FACTS = 40
    const val MAX_LEN = 300

    class Fact(val id: Long, val text: String, val at: Long)

    fun all(): List<Fact> {
        val out = ArrayList<Fact>()
        try {
            val arr = JSONArray(Prefs.aiMemory.ifBlank { "[]" })
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val t = o.optString("t").trim()
                if (t.isNotEmpty()) out.add(Fact(o.optLong("id", i.toLong()), t, o.optLong("at")))
            }
        } catch (_: Throwable) {}
        return out
    }

    private fun save(facts: List<Fact>) {
        val arr = JSONArray()
        for (f in facts.takeLast(MAX_FACTS)) {
            arr.put(JSONObject().put("id", f.id).put("t", f.text).put("at", f.at))
        }
        Prefs.setAiMemory(arr.toString())
    }

    /**
     * Add a fact. Returns the text actually stored, or null when it was a
     * duplicate — a model asked to remember something twice should be told so
     * rather than quietly growing the list.
     */
    fun add(raw: String): String? {
        val text = raw.trim().take(MAX_LEN)
        if (text.isEmpty()) return null
        val facts = all().toMutableList()
        if (facts.any { it.text.equals(text, true) }) return null
        facts.add(Fact(System.currentTimeMillis(), text, System.currentTimeMillis()))
        // oldest out first when full — a cap that refuses new facts would make
        // the memory freeze at whatever it learned on day one
        save(facts)
        return text
    }

    fun remove(id: Long): Boolean {
        val facts = all()
        val left = facts.filter { it.id != id }
        if (left.size == facts.size) return false
        save(left)
        return true
    }

    fun replace(id: Long, text: String): Boolean {
        val facts = all()
        var found = false
        val out = facts.map {
            if (it.id == id) { found = true; Fact(it.id, text.trim().take(MAX_LEN), it.at) } else it
        }
        if (found) save(out)
        return found
    }

    fun clear() = Prefs.setAiMemory("[]")

    /** The block folded into the system prompt. Empty when there is nothing to say. */
    fun promptBlock(): String {
        if (!Prefs.aiMemoryOn) return ""
        val facts = all()
        if (facts.isEmpty()) return ""
        val sb = StringBuilder("\n\nЧто ты знаешь о пользователе (его постоянные факты, ")
        sb.append("не пересказывай их без нужды, просто учитывай):\n")
        for (f in facts) sb.append("• ").append(f.text).append('\n')
        return sb.toString()
    }
}
