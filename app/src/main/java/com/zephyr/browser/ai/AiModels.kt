package com.zephyr.browser.ai

import com.zephyr.browser.Prefs
import org.json.JSONArray
import org.json.JSONObject

/**
 * v1.7.25: multi-model support for the AI Assistant.
 *
 * The assistant used to be locked to ONE provider config (key/endpoint/model in
 * [Prefs]). Users can now keep a LIST of model profiles (OpenAI + NVIDIA NIM +
 * a local Ollama, …) and switch between them right from the chat window.
 *
 * Storage: a JSON array in `Prefs.aiProfiles` + the active index in
 * `Prefs.aiActiveProfile`. The legacy single-config fields stay mirrored to the
 * active profile so every existing call site keeps working.
 */
data class AiProfile(
    val name: String,
    val endpoint: String,
    val model: String,
    val key: String,
    /**
     * v1.7.48: system prompt for THIS model only, blank = the global one.
     * A bridge to GLM and a Gemini key have different strengths, so telling
     * them the same thing was always a compromise.
     */
    val prompt: String = "",
    /**
     * v1.7.51: how hard this model should think before answering.
     * "auto" sends nothing, "off" asks it not to reason at all, and
     * low/medium/high map to the providers' reasoning-effort levels.
     */
    val thinking: String = "auto"
) {
    /** Endpoint actually used for the request (see [AiModels.normalizeEndpoint]). */
    val requestUrl: String get() = AiModels.normalizeEndpoint(endpoint)
}

object AiModels {

    /** Known providers: label → (base endpoint, default model). */
    val PRESETS: List<Triple<String, String, String>> = listOf(
        // v1.7.36: the genuinely FREE tiers first — «всегда доступный ИИ» is
        // mostly a matter of knowing these exist.
        Triple(
            "Google Gemini (free)",
            "https://generativelanguage.googleapis.com/v1beta/openai/chat/completions",
            "gemini-2.0-flash"
        ),
        Triple("Groq (free)", "https://api.groq.com/openai/v1/chat/completions", "llama-3.3-70b-versatile"),
        Triple("OpenAI", "https://api.openai.com/v1/chat/completions", "gpt-4o-mini"),
        Triple("NVIDIA NIM", "https://integrate.api.nvidia.com/v1/chat/completions", "meta/llama-3.3-70b-instruct"),
        Triple("DeepSeek", "https://api.deepseek.com/v1/chat/completions", "deepseek-chat"),
        Triple("OpenRouter", "https://openrouter.ai/api/v1/chat/completions", "meta-llama/llama-3.1-8b-instruct:free"),
        Triple("Mistral", "https://api.mistral.ai/v1/chat/completions", "mistral-large-latest"),
        Triple("Together AI", "https://api.together.xyz/v1/chat/completions", "meta-llama/Llama-3.3-70B-Instruct-Turbo"),
        Triple("Cerebras", "https://api.cerebras.ai/v1/chat/completions", "llama-3.3-70b"),
        Triple("Local Ollama", "http://127.0.0.1:11434/v1/chat/completions", "llama3"),
        Triple("Custom", "", "")
    )

    /**
     * THE 404 FIX. Providers publish a BASE url (NVIDIA: `https://integrate.api.nvidia.com/v1`)
     * while the chat API lives at `<base>/chat/completions`. Pasting the base url
     * verbatim produced the infamous «HTTP Error 404: 404 page not found».
     * Anything the user pastes — base, full path, with or without a trailing
     * slash — is normalized into a working chat-completions URL here.
     */
    fun normalizeEndpoint(raw: String): String {
        var e = raw.trim().trimEnd('/')
        if (e.isEmpty()) return "https://api.openai.com/v1/chat/completions"
        if (!e.startsWith("http://", true) && !e.startsWith("https://", true)) e = "https://$e"
        val low = e.lowercase()
        // already a concrete completions/messages/generate endpoint → leave alone
        if (low.endsWith("/chat/completions") || low.endsWith("/completions") ||
            low.endsWith("/messages") || low.endsWith("/api/chat") || low.endsWith("/generate")
        ) return e
        // v1.7.45: a very easy mistake — dashboards show `<base>/v1/models` as
        // the "check I am alive" link, and pasting that produced
        // `.../v1/models/v1/chat/completions` and a baffling 404. `/models` is a
        // catalogue, never a chat endpoint, so swap it for the real one.
        if (low.endsWith("/models")) return e.dropLast("/models".length) + "/chat/completions"
        // ".../v1" (or ".../openai/v1", ".../v1beta") → append the chat path
        if (Regex("""/v\d[a-z0-9]*$""").containsMatchIn(low)) return "$e/chat/completions"
        // bare host or unknown path → assume the OpenAI-compatible layout
        return "$e/v1/chat/completions"
    }

    /** Saved profiles; falls back to the legacy single config when none were saved yet. */
    fun list(): List<AiProfile> {
        val raw = Prefs.aiProfiles
        if (raw.isNotBlank()) {
            try {
                val arr = JSONArray(raw)
                val out = ArrayList<AiProfile>(arr.length())
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    out.add(
                        AiProfile(
                            name = o.optString("name").ifBlank { o.optString("model", "model") },
                            endpoint = o.optString("endpoint"),
                            model = o.optString("model"),
                            key = o.optString("key"),
                            prompt = o.optString("prompt"),
                            thinking = o.optString("thinking").ifBlank { "auto" }
                        )
                    )
                }
                if (out.isNotEmpty()) return out
            } catch (_: Exception) { /* fall through to the legacy config */ }
        }
        return listOf(
            AiProfile(
                name = Prefs.aiModel.ifBlank { "Default" },
                endpoint = Prefs.aiEndpoint,
                model = Prefs.aiModel,
                key = Prefs.aiApiKey
            )
        )
    }

    fun activeIndex(): Int = Prefs.aiActiveProfile.coerceIn(0, (list().size - 1).coerceAtLeast(0))

    fun active(): AiProfile = list()[activeIndex()]

    private fun persist(items: List<AiProfile>, active: Int) {
        val arr = JSONArray()
        for (p in items) {
            arr.put(
                JSONObject()
                    .put("name", p.name)
                    .put("endpoint", p.endpoint)
                    .put("model", p.model)
                    .put("key", p.key)
                    .put("prompt", p.prompt)
                    .put("thinking", p.thinking)
            )
        }
        val idx = active.coerceIn(0, (items.size - 1).coerceAtLeast(0))
        Prefs.setAiProfiles(arr.toString(), idx)
        // keep the legacy single-config fields mirrored to the active profile
        items.getOrNull(idx)?.let {
            Prefs.setAiSettings(it.key, it.endpoint, it.model, Prefs.aiPrompt)
        }
    }

    fun save(items: List<AiProfile>, active: Int = activeIndex()) = persist(items, active)

    fun add(p: AiProfile) {
        val items = list().toMutableList()
        items.add(p)
        persist(items, items.size - 1)
    }

    fun update(index: Int, p: AiProfile) {
        val items = list().toMutableList()
        if (index !in items.indices) return
        items[index] = p
        persist(items, activeIndex())
    }

    fun remove(index: Int) {
        val items = list().toMutableList()
        if (index !in items.indices || items.size <= 1) return
        items.removeAt(index)
        persist(items, activeIndex().coerceAtMost(items.size - 1))
    }

    fun setActive(index: Int) {
        val items = list()
        if (index !in items.indices) return
        persist(items, index)
    }

    /** v1.7.33: the pseudo-profile that routes the chat through a paired agent. */
    fun ensureAgentProfile(code: String) {
        val endpoint = AiClient.AGENT_SCHEME + AgentCrypto.normalize(code)
        val items = list().toMutableList()
        val idx = items.indexOfFirst { isAgent(it) }
        val p = AiProfile(
            name = try { com.zephyr.browser.App.ctx?.getString(com.zephyr.browser.R.string.ai_agent_profile_name) ?: "My AI agent" } catch (_: Throwable) { "My AI agent" },
            endpoint = endpoint,
            model = "remote-agent",
            key = ""
        )
        if (idx >= 0) items[idx] = p else items.add(0, p)
        save(items, items.indexOfFirst { isAgent(it) }.coerceAtLeast(0))
    }

    fun removeAgentProfile() {
        val items = list().toMutableList()
        val idx = items.indexOfFirst { isAgent(it) }
        if (idx < 0 || items.size <= 1) return   // never leave the list empty
        items.removeAt(idx)
        save(items, 0)
    }

    fun isAgent(p: AiProfile): Boolean = p.endpoint.startsWith(AiClient.AGENT_SCHEME, true)

    /** True when the active profile can actually be called (local endpoints need no key). */
    fun isConfigured(p: AiProfile = active()): Boolean {
        // v1.7.33: a paired agent needs neither an endpoint nor a key
        if (isAgent(p)) return Prefs.agentCode.isNotBlank()
        val local = p.endpoint.contains("localhost") || p.endpoint.contains("127.0.0.1") ||
            p.endpoint.contains("10.0.2.2")
        return p.endpoint.isNotBlank() && (local || p.key.isNotBlank())
    }
}
