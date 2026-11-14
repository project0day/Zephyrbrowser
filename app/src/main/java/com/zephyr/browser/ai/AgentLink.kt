package com.zephyr.browser.ai

import com.zephyr.browser.Prefs
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * v1.7.33: REMOTE AGENT PAIRING — an AI agent instead of an API key.
 *
 * User: «моя цель — чтобы ИИ-агент был доступен для чата и заменил мне API-ключи;
 * у меня много моделей, у них sandbox, они могут выполнять команды и подключаться».
 *
 * A phone cannot be dialled into: no public address, carrier NAT, no inbound
 * sockets. So neither side listens — BOTH send outbound to a shared mailbox.
 * The mailbox is a public ntfy topic, which needs no account and no key:
 *
 *     Zephyr ──POST /<topic>u──► ntfy ◄──GET /<topic>u── agent connector
 *     Zephyr ──GET  /<topic>d──► ntfy ◄──POST /<topic>d── agent connector
 *
 * The topic name and the encryption key are both derived from the pairing code
 * ([AgentCrypto]), so the topic is unguessable and the broker only ever relays
 * ciphertext. Payloads are chunked because ntfy caps a message at 4 KB.
 *
 * Failure is expected and handled: an agent is a process someone has to keep
 * alive. If nobody answers within [Prefs.agentTimeoutSec], the caller is told
 * so and the assistant falls back to a normal model.
 */
object AgentLink {

    enum class State { OFFLINE, LISTENING, ONLINE }

    private const val CHUNK = 2400          // base64 chars per ntfy message
    private const val PROTO = 1

    @Volatile var state: State = State.OFFLINE; private set
    @Volatile var agentName: String = ""; private set
    @Volatile var lastError: String = ""; private set

    /** Notified on every state change so the UI can repaint. */
    @Volatile var onState: ((State) -> Unit)? = null

    private var key: ByteArray? = null
    private var topicUp = ""
    private var topicDown = ""
    private var listener: Thread? = null
    private var heartbeat: Thread? = null
    @Volatile private var running = false

    /**
     * v1.7.34: when the agent's sandbox is reset or times out, it simply stops
     * answering — there is no disconnect event to observe. Without a heartbeat
     * the indicator would keep claiming "online" over a dead link, and the user
     * would only find out by asking a question and waiting out the timeout.
     */
    @Volatile private var lastHeard = 0L
    private const val HEARTBEAT_MS = 30_000L
    private const val SILENCE_MS = 95_000L

    /** Reassembly buffers for chunked inbound payloads. */
    private val partials = ConcurrentHashMap<String, Array<String?>>()

    /** Requests waiting for their answer, by id. */
    private val pending = ConcurrentHashMap<String, Waiter>()

    private class Waiter {
        val latch = CountDownLatch(1)
        @Volatile var text: String = ""
        @Volatile var error: String? = null
        @Volatile var onDelta: ((String) -> Unit)? = null
    }

    // ---------------- lifecycle ----------------

    fun isPaired(): Boolean = Prefs.agentCode.isNotBlank()

    private fun server(): String =
        Prefs.agentServer.trim().trimEnd('/').ifBlank { "https://ntfy.sh" }

    @Synchronized
    fun start() {
        val code = Prefs.agentCode
        if (code.isBlank()) { setState(State.OFFLINE); return }
        if (running) return
        running = true
        setState(State.LISTENING)
        // PBKDF2 at 60k iterations is ~a few hundred ms — never on the UI thread.
        listener = Thread {
            try {
                key = AgentCrypto.deriveKey(code)
                val base = AgentCrypto.topicBase(code)
                topicUp = base + "u"
                topicDown = base + "d"
                // announce ourselves; a connector already waiting answers "ready"
                send(JSONObject().put("type", "hello").put("v", PROTO))
                listenLoop()
            } catch (_: Throwable) {
                running = false
                setState(State.OFFLINE)
            }
        }.apply { isDaemon = true; start() }

        heartbeat = Thread {
            while (running) {
                try { Thread.sleep(HEARTBEAT_MS) } catch (_: InterruptedException) { break }
                if (!running) break
                // an agent that is alive answers "pong"; one whose sandbox died
                // says nothing, and the indicator stops lying after SILENCE_MS
                if (state == State.ONLINE) {
                    if (System.currentTimeMillis() - lastHeard > SILENCE_MS) {
                        agentName = ""
                        setState(State.LISTENING)
                    }
                    send(JSONObject().put("type", "ping").put("v", PROTO))
                }
            }
        }.apply { isDaemon = true; start() }
    }

    @Synchronized
    fun stop() {
        running = false
        listener?.interrupt()
        listener = null
        heartbeat?.interrupt()
        heartbeat = null
        pending.values.forEach { it.error = "Связь с агентом закрыта"; it.latch.countDown() }
        pending.clear()
        partials.clear()
        setState(State.OFFLINE)
        agentName = ""
    }

    fun restart() { stop(); start() }

    private fun setState(s: State) {
        if (state == s) return
        state = s
        try { onState?.invoke(s) } catch (_: Throwable) {}
    }

    // ---------------- outbound ----------------

    /** Encrypt, chunk and publish one envelope to the agent. */
    private fun send(obj: JSONObject): Boolean {
        val k = key ?: return false
        return try {
            val wire = AgentCrypto.encrypt(k, obj.toString().toByteArray(Charsets.UTF_8))
            val id = java.util.UUID.randomUUID().toString().take(8)
            val total = (wire.length + CHUNK - 1) / CHUNK
            for (i in 0 until total) {
                val part = wire.substring(i * CHUNK, minOf((i + 1) * CHUNK, wire.length))
                val frame = JSONObject()
                    .put("v", PROTO).put("id", id)
                    .put("i", i).put("n", total).put("d", part)
                if (!publish(topicUp, frame.toString())) return false
            }
            true
        } catch (_: Throwable) { false }
    }

    private fun publish(topic: String, body: String): Boolean {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(server() + "/" + topic).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = 12000
                readTimeout = 15000
                setRequestProperty("Content-Type", "text/plain; charset=utf-8")
                setRequestProperty("Title", "zephyr")
            }
            val out: OutputStream = conn.outputStream
            out.write(body.toByteArray(Charsets.UTF_8))
            out.flush(); out.close()
            conn.responseCode in 200..299
        } catch (t: Throwable) {
            lastError = t.localizedMessage ?: "network"
            false
        } finally {
            try { conn?.disconnect() } catch (_: Exception) {}
        }
    }

    // ---------------- inbound ----------------

    /** Long-lived streaming GET on the down topic, with reconnects. */
    private fun listenLoop() {
        var backoff = 1000L
        while (running) {
            var conn: HttpURLConnection? = null
            try {
                conn = (URL(server() + "/" + topicDown + "/json").openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 15000
                    readTimeout = 0          // stream stays open
                    setRequestProperty("Accept", "application/x-ndjson")
                }
                if (conn.responseCode !in 200..299) throw IllegalStateException("http " + conn.responseCode)
                backoff = 1000L
                BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use { r ->
                    while (running) {
                        val line = r.readLine() ?: break
                        if (line.isBlank()) continue
                        handleLine(line)
                    }
                }
            } catch (_: Throwable) {
                if (!running) break
            } finally {
                try { conn?.disconnect() } catch (_: Exception) {}
            }
            if (!running) break
            try { Thread.sleep(backoff) } catch (_: InterruptedException) { break }
            backoff = (backoff * 2).coerceAtMost(15000L)
        }
    }

    private fun handleLine(line: String) {
        val env = try { JSONObject(line) } catch (_: Throwable) { return }
        if (env.optString("event") != "message") return
        val body = env.optString("message").ifBlank { return }
        val frame = try { JSONObject(body) } catch (_: Throwable) { return }
        val id = frame.optString("id")
        val total = frame.optInt("n", 1)
        val idx = frame.optInt("i", 0)
        val part = frame.optString("d")
        if (id.isBlank() || part.isBlank()) return

        val slots = partials.getOrPut(id) { arrayOfNulls(total) }
        if (idx !in slots.indices) return
        slots[idx] = part
        if (slots.any { it == null }) return
        partials.remove(id)

        val k = key ?: return
        val plain = AgentCrypto.decrypt(k, slots.joinToString("")) ?: return
        val msg = try { JSONObject(String(plain, Charsets.UTF_8)) } catch (_: Throwable) { return }
        dispatch(msg)
    }

    private fun dispatch(msg: JSONObject) {
        // any decrypted frame proves the agent is alive right now
        lastHeard = System.currentTimeMillis()
        when (msg.optString("type")) {
            "ready", "pong" -> {
                agentName = msg.optString("agent").ifBlank { agentName.ifBlank { "Агент" } }
                setState(State.ONLINE)
            }
            "bye" -> { agentName = ""; setState(State.LISTENING) }
            "delta" -> {
                setState(State.ONLINE)
                pending[msg.optString("id")]?.let { w ->
                    val piece = msg.optString("text")
                    if (piece.isNotEmpty()) {
                        w.text += piece
                        try { w.onDelta?.invoke(piece) } catch (_: Throwable) {}
                    }
                }
            }
            "reply" -> {
                setState(State.ONLINE)
                agentName = msg.optString("agent").ifBlank { agentName }
                val w = pending.remove(msg.optString("id")) ?: return
                val full = msg.optString("text")
                if (full.isNotEmpty() && full != w.text) {
                    // A connector may stream with "delta" frames or just send the
                    // whole answer here. Emit only the part not already shown.
                    val add = if (full.startsWith(w.text)) full.substring(w.text.length) else full
                    w.text = full
                    if (add.isNotEmpty()) try { w.onDelta?.invoke(add) } catch (_: Throwable) {}
                }
                w.error = msg.optString("error").ifBlank { null }
                w.latch.countDown()
            }
        }
    }

    // ---------------- the call the assistant makes ----------------

    /** Result of one round trip; [ok] false means fall back to a normal model. */
    class Answer(val text: String, val ok: Boolean, val fellThrough: Boolean)

    /**
     * Ask the paired agent. Blocks until it answers or the timeout expires.
     * [onStatus] reports progress, [onDelta] receives streamed fragments.
     */
    fun ask(
        system: String,
        messages: JSONArray,
        onStatus: (String) -> Unit,
        onDelta: (String) -> Unit,
        isActive: () -> Boolean
    ): Answer {
        if (!isPaired()) return Answer("Агент не подключён.", false, true)
        if (!running) start()

        val id = "req_" + System.currentTimeMillis().toString(36) +
            "_" + (1000..9999).random()
        val w = Waiter().also { it.onDelta = onDelta }
        pending[id] = w

        onStatus(
            if (state == State.ONLINE) "Отправляю агенту " + agentName.ifBlank { "" } + "…"
            else "Жду агента…"
        )

        val ok = send(
            JSONObject()
                .put("type", "chat")
                .put("v", PROTO)
                .put("id", id)
                .put("system", system)
                .put("messages", messages)
        )
        if (!ok) {
            pending.remove(id)
            return Answer("Не удалось отправить запрос агенту (нет сети или сервис недоступен).", false, true)
        }

        val timeout = Prefs.agentTimeoutSec.coerceIn(10, 600)
        var waited = 0
        var announced = false
        while (waited < timeout) {
            if (!isActive()) { pending.remove(id); return Answer(w.text, true, false) }
            if (w.latch.await(1, TimeUnit.SECONDS)) break
            waited++
            if (w.text.isNotEmpty() && !announced) { announced = true; onStatus("Агент отвечает…") }
            else if (waited == 3 && state != State.ONLINE) onStatus("Агент пока не отозвался…")
        }
        pending.remove(id)

        if (w.latch.count > 0L && w.text.isBlank()) {
            setState(if (running) State.LISTENING else State.OFFLINE)
            return Answer(
                "⌛ Агент не ответил за " + timeout + " с.\n" +
                    "Проверьте, что коннектор запущен в его песочнице.",
                false, true
            )
        }
        w.error?.let { return Answer("Агент сообщил об ошибке: $it", false, false) }
        return Answer(w.text.ifBlank { "Агент вернул пустой ответ." }, w.text.isNotBlank(), false)
    }

    /** Fire-and-forget liveness check used by the pairing screen. */
    fun ping() {
        if (!running) start()
        send(JSONObject().put("type", "ping").put("v", PROTO))
    }
}
