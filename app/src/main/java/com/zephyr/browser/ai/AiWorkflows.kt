package com.zephyr.browser.ai

import com.zephyr.browser.Prefs
import org.json.JSONArray
import org.json.JSONObject

/**
 * v1.7.61 «Сценарии» — the user's own commands.
 *
 * The idea came back from two directions at once: another assistant called them
 * Workflows / Skills and put them at the top of its list, and it was right for
 * a reason worth stating plainly — a scenario is the cheapest powerful thing
 * this app can gain, because everything it needs already exists.
 *
 * A scenario is deliberately NOT a program. It is a saved instruction plus a
 * name. Running it means sending that instruction to the assistant, which then
 * picks its own tools exactly as it would for a typed request. That is why
 * there is no interpreter here, no step list, no conditionals: the model is the
 * interpreter, and the existing tool loop is the runtime.
 *
 * Two consequences fall out for free. A scenario can be attached to a schedule,
 * because a scheduled job is also just an instruction (see AgentJobs). And the
 * assistant can WRITE one — "создай команду, которая…" — because writing a
 * scenario means writing a paragraph, not generating code.
 */
object AiWorkflows {

    const val MAX = 60
    private const val MAX_NAME = 40
    private const val MAX_PROMPT = 4000

    class Flow(val id: Long, val name: String, val prompt: String, val at: Long)

    fun all(): List<Flow> {
        val out = ArrayList<Flow>()
        try {
            val arr = JSONArray(Prefs.aiWorkflows.ifBlank { "[]" })
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val n = o.optString("n").trim()
                val p = o.optString("p").trim()
                if (n.isNotEmpty() && p.isNotEmpty()) {
                    out.add(Flow(o.optLong("id", i.toLong()), n, p, o.optLong("at")))
                }
            }
        } catch (_: Throwable) {}
        return out
    }

    private fun save(list: List<Flow>) {
        val arr = JSONArray()
        for (f in list.take(MAX)) {
            arr.put(JSONObject().put("id", f.id).put("n", f.name).put("p", f.prompt).put("at", f.at))
        }
        Prefs.setAiWorkflows(arr.toString())
    }

    /**
     * Create or replace by name.
     *
     * Replacing by name rather than appending is on purpose: "создай команду
     * подготовь счета" said twice should refine the command, not leave two
     * entries with the same name and different behaviour.
     */
    fun put(name: String, prompt: String): Flow? {
        val n = name.trim().removePrefix("/").take(MAX_NAME)
        val p = prompt.trim().take(MAX_PROMPT)
        if (n.isEmpty() || p.isEmpty()) return null
        val list = all().toMutableList()
        val idx = list.indexOfFirst { it.name.equals(n, true) }
        val flow = Flow(
            if (idx >= 0) list[idx].id else System.currentTimeMillis(),
            n, p, System.currentTimeMillis()
        )
        if (idx >= 0) list[idx] = flow else list.add(flow)
        save(list)
        return flow
    }

    fun remove(id: Long): Boolean {
        val list = all()
        val left = list.filter { it.id != id }
        if (left.size == list.size) return false
        save(left)
        return true
    }

    fun byId(id: Long): Flow? = all().firstOrNull { it.id == id }

    fun byName(name: String): Flow? {
        val n = name.trim().removePrefix("/")
        return all().firstOrNull { it.name.equals(n, true) }
    }

    /** Names only, for the system prompt and for the picker. */
    fun names(): List<String> = all().map { it.name }
}
