package com.zephyr.browser.ai

import com.zephyr.browser.Prefs
import com.zephyr.browser.R

/**
 * v1.7.36: THE SWARM — «всегда доступный ИИ» as a property of the system.
 *
 * The user's problem was never one bad provider: an agent sandbox resets daily,
 * a free tier hits its quota, a heavy model times out. Pinning the assistant to
 * one profile makes every one of those a dead end.
 *
 * So the assistant stops asking "which model" and starts asking "who is
 * available". The user marks which of their profiles take part, and the swarm
 * does the rest:
 *
 *   CHAIN   — walk the members in order, first one to answer wins. The paired
 *             agent can lead; a free-tier key catches it when the sandbox is
 *             gone; nothing looks broken from the outside.
 *   COUNCIL — everybody answers, each reply labelled with who wrote it. Slower
 *             and costs more quota, but you see the models disagree, which is
 *             exactly when a second opinion is worth having.
 *
 * Membership is stored by a stable id (endpoint + model), so reordering or
 * renaming profiles never silently drops someone from the swarm.
 */
object AiSwarm {

    /**
     * A string resource. Like [AiClient], this object is handed no Context and
     * yet writes text the user reads, which is exactly why its messages were
     * Russian literals until v1.7.86.
     */
    private fun s(res: Int, vararg args: Any): String = try {
        val c = com.zephyr.browser.App.ctx
        if (c == null) "" else if (args.isEmpty()) c.getString(res) else c.getString(res, *args)
    } catch (_: Throwable) { "" }

    const val MODE_CHAIN = 0
    const val MODE_COUNCIL = 1

    /** Stable identity of a profile — survives renames and reordering. */
    fun idOf(p: AiProfile): String =
        (p.endpoint.trim().lowercase() + "|" + p.model.trim().lowercase())

    fun isMember(p: AiProfile): Boolean = idOf(p) in Prefs.swarmMembers

    fun setMember(p: AiProfile, on: Boolean) {
        val set = Prefs.swarmMembers.toMutableSet()
        if (on) set.add(idOf(p)) else set.remove(idOf(p))
        Prefs.setSwarmMembers(set)
    }

    /** Members in the order they appear in the user's model list. */
    fun members(): List<AiProfile> =
        AiModels.list().filter { isMember(it) && AiModels.isConfigured(it) }

    /** True when the swarm should handle the next turn instead of one model. */
    fun active(): Boolean = Prefs.swarmEnabled && members().isNotEmpty()

    /**
     * Run one turn through the swarm.
     *
     * [runOne] performs a single normal request against one profile and reports
     * back through a sink; the swarm only decides who is asked and what happens
     * when they fail. Keeping the transport out of here is what lets the agent,
     * an HTTP provider and anything added later sit in the same swarm.
     */
    fun run(
        history: List<AiMessage>,
        pageContext: String,
        sink: AiClient.StreamSink,
        runOne: (AiProfile, AiClient.StreamSink) -> Unit
    ) {
        val team = members()
        if (team.isEmpty()) {
            sink.onDone(AiResult(s(R.string.swarm_err_empty), false))
            return
        }
        if (Prefs.swarmMode == MODE_COUNCIL) runCouncil(team, sink, runOne)
        else runChain(team, sink, runOne)
    }

    // ---------------- chain ----------------

    private fun runChain(
        team: List<AiProfile>,
        sink: AiClient.StreamSink,
        runOne: (AiProfile, AiClient.StreamSink) -> Unit
    ) {
        val failures = StringBuilder()
        for ((index, member) in team.withIndex()) {
            if (!sink.isActive()) return
            if (index > 0) sink.onStatus(s(R.string.swarm_st_next, member.name))

            var settled = false
            var ok = false
            var text = ""
            var usage: AiUsage? = null
            var calls = 1

            runOne(member, object : AiClient.StreamSink {
                override fun onStatus(t: String) = sink.onStatus(t)
                override fun onReasoning(d: String) = sink.onReasoning(d)
                override fun onDelta(d: String) {
                    // only the winner is allowed to paint into the bubble
                    if (!settled) sink.onDelta(d)
                }
                override fun isActive(): Boolean = sink.isActive()
                override fun onDone(result: AiResult) {
                    settled = true
                    ok = result.ok
                    text = result.text
                    usage = result.usage
                    calls = result.requests
                }
            })

            if (ok && text.isNotBlank()) {
                // v1.7.44: no more credit line glued onto the text — the name
                // travels as a field and is shown under every reply
                sink.onDone(AiResult(text, true, usage, member.name, calls))
                return
            }
            failures.append("• ").append(member.name).append(": ")
                .append(text.lineSequence().firstOrNull()?.take(120).orEmpty()).append('\n')
        }
        sink.onDone(
            AiResult(
                s(R.string.swarm_err_none) + "\n\n" + failures.toString().trim(),
                false
            )
        )
    }

    // ---------------- council ----------------

    private fun runCouncil(
        team: List<AiProfile>,
        sink: AiClient.StreamSink,
        runOne: (AiProfile, AiClient.StreamSink) -> Unit
    ) {
        val out = StringBuilder()
        var answered = 0
        for ((index, member) in team.withIndex()) {
            if (!sink.isActive()) break
            sink.onStatus(s(R.string.swarm_st_asking, member.name, index + 1, team.size))

            var text = ""
            var ok = false
            runOne(member, object : AiClient.StreamSink {
                override fun onStatus(t: String) {}
                override fun onReasoning(d: String) = sink.onReasoning(d)
                override fun onDelta(d: String) {}
                override fun isActive(): Boolean = sink.isActive()
                override fun onDone(result: AiResult) { text = result.text; ok = result.ok }
            })

            val header = "### " + member.name + "\n"
            val block = if (ok && text.isNotBlank()) {
                answered++
                header + text + "\n\n"
            } else {
                header + "_" + s(R.string.swarm_no_answer) + ": " +
                    text.lineSequence().firstOrNull()?.take(120).orEmpty() + "_\n\n"
            }
            out.append(block)
            // paint each reply the moment it lands, so the chat fills up live
            sink.onDelta(block)
        }
        sink.onDone(
            if (answered > 0) AiResult(out.toString().trim(), true, null, s(R.string.swarm_council_via), team.size)
            else AiResult(s(R.string.swarm_err_none) + "\n\n" + out.toString().trim(), false)
        )
    }

}
