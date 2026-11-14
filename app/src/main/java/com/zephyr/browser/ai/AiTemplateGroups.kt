package com.zephyr.browser.ai

import com.zephyr.browser.Prefs
import org.json.JSONArray
import org.json.JSONObject

/**
 * v1.7.73 (user: «а как создавать группу? надо подписать — возможность
 * подписать самому, или агент сам создаёт группу с названием на выбор, можно
 * добавить эмодзи или цвет»).
 *
 * A folder name alone stops working the moment there are two templates: "какой
 * из них для каменных работ?" is answered by a glance at an emoji and a colour
 * long before it is answered by reading. So a group is a name plus those two.
 *
 * The groups live apart from the templates that reference them by name, which
 * means a template can name a group that does not exist yet — it simply gets
 * the default look until the group is created. That is deliberate: the
 * assistant writing a template should never fail because a folder is missing.
 */
object AiTemplateGroups {

    /** Colours offered in the picker, as (id, ARGB). */
    val COLORS = listOf(
        "blue" to 0xFF1F6FEB.toInt(),
        "teal" to 0xFF0F9D8C.toInt(),
        "green" to 0xFF2E9E52.toInt(),
        "amber" to 0xFFD98216.toInt(),
        "red" to 0xFFD9453D.toInt(),
        "purple" to 0xFF7C4DDB.toInt(),
        "graphite" to 0xFF5A6472.toInt()
    )

    val EMOJI = listOf(
        "📄", "💼", "🧾", "🔨", "🏠", "🚚", "💡", "📐",
        "✉️", "📊", "🧰", "🎓", "⚖️", "🩺", "🍽️", "⭐"
    )

    class Group(val name: String, val emoji: String, val color: Int)

    private fun data(): JSONObject = try {
        JSONObject(Prefs.aiTemplateGroups.ifBlank { "{}" })
    } catch (_: Throwable) { JSONObject() }

    fun colorOf(id: String): Int =
        COLORS.firstOrNull { it.first == id }?.second ?: COLORS[0].second

    /**
     * The look of a group, invented deterministically when it has never been
     * configured — so an un-styled folder is still distinguishable from its
     * neighbour rather than all of them being the same grey.
     */
    fun get(name: String): Group {
        val o = data().optJSONObject(name.lowercase())
        if (o != null) {
            return Group(
                name,
                o.optString("emoji").ifBlank { defaultEmoji(name) },
                colorOf(o.optString("color").ifBlank { defaultColorId(name) })
            )
        }
        return Group(name, defaultEmoji(name), colorOf(defaultColorId(name)))
    }

    fun set(name: String, emoji: String, colorId: String) {
        val o = data()
        o.put(
            name.lowercase(),
            JSONObject().put("emoji", emoji).put("color", colorId)
        )
        Prefs.setAiTemplateGroups(o.toString())
    }

    fun forget(name: String) {
        val o = data()
        o.remove(name.lowercase())
        Prefs.setAiTemplateGroups(o.toString())
    }

    /** Stable per name, so a folder keeps its colour between launches. */
    private fun defaultColorId(name: String): String {
        val i = Math.abs(name.lowercase().hashCode()) % COLORS.size
        return COLORS[i].first
    }

    private fun defaultEmoji(name: String): String {
        val n = name.lowercase()
        return when {
            n.contains("комм") || n.contains("devis") || n.contains("счёт") ||
                n.contains("счет") || n.contains("factur") -> "🧾"
            n.contains("док") -> "📄"
            n.contains("письм") || n.contains("mail") -> "✉️"
            n.contains("програм") || n.contains("app") -> "💡"
            n.contains("строй") || n.contains("macon") || n.contains("работ") -> "🔨"
            else -> "💼"
        }
    }

    /** Every group that has templates, plus every one the user styled. */
    fun all(): List<Group> {
        val fromTemplates = AiTemplates.all().map { it.category }
        val styled = ArrayList<String>()
        val o = data()
        val keys = o.keys()
        while (keys.hasNext()) styled.add(keys.next())
        return (fromTemplates + styled).distinctBy { it.lowercase() }.map { get(it) }
    }

    fun toJson(): JSONArray {
        val arr = JSONArray()
        for (g in all()) {
            arr.put(JSONObject().put("name", g.name).put("emoji", g.emoji))
        }
        return arr
    }
}
