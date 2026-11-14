package com.zephyr.browser.ai

import android.content.Context
import com.zephyr.browser.db.AppDb
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * v1.7.50: a record of what the assistant actually did.
 *
 * It opens tabs, clicks buttons on pages, reads files and adds bookmarks. Those
 * actions scroll past in the status line and are gone. When something lands
 * where it should not have, "what did it just do?" needs an answer that is not
 * a guess.
 *
 * v1.7.60, two changes from the user:
 *
 *  - «журнал иногда пропадал — проверь, чтобы он сохранялся и очистку делал бы
 *    сам пользователь». It lived in a field of this object, so Android
 *    reclaiming the process erased it. It is now a file, and nothing clears it
 *    but the user.
 *  - «чтобы вдруг пользователь передумал — можно нажать вернуть». Each entry
 *    can carry an UNDO, described as data rather than as a closure, so it still
 *    works tomorrow. A file write also keeps a copy of the previous bytes.
 *
 * Only genuinely reversible actions get one, and that honesty matters more than
 * coverage: a bookmark can be removed, a file restored, a reminder cancelled. A
 * click on someone else's page, a submitted form, a closed tab — no button at
 * all, rather than a button that quietly does nothing.
 */
object AiActionLog {

    private const val FILE = "ai_actions.json"
    private const val BACKUP_DIR = "ai_undo"
    private const val MAX = 200

    /** What an entry's undo does. */
    object Kind {
        const val BOOKMARK = "bookmark"
        const val REMINDER = "reminder"
        const val MEMORY = "memory"
        const val SCRIPT = "script"
        const val MODEL = "model"
        const val FILE_WRITE = "file_write"
        const val FILE_COPY = "file_copy"
        const val WORKFLOW = "workflow"
        const val TEMPLATE = "template"
    }

    class Entry(
        val at: Long,
        val tool: String,
        val args: String,
        val result: String,
        val ok: Boolean,
        /** Undo descriptor: {kind, …}. Null when the action cannot be reversed. */
        val undo: JSONObject?,
        var undone: Boolean
    )

    @Volatile private var appCtx: Context? = null
    private val entries = ArrayDeque<Entry>()
    @Volatile private var loaded = false

    /** Set by the tool that just ran, consumed by the next [add]. */
    @Volatile private var pendingUndo: JSONObject? = null

    fun init(ctx: Context) {
        if (appCtx == null) appCtx = ctx.applicationContext
        load()
    }

    // ---------------- persistence ----------------

    private fun file(): File? = appCtx?.let { File(it.filesDir, FILE) }

    private fun backupDir(): File? = appCtx?.let {
        File(it.filesDir, BACKUP_DIR).apply { if (!exists()) mkdirs() }
    }

    @Synchronized
    private fun load() {
        if (loaded) return
        loaded = true
        val f = file() ?: return
        if (!f.exists()) return
        try {
            val arr = JSONArray(f.readText())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                entries.addLast(
                    Entry(
                        o.optLong("at"), o.optString("tool"), o.optString("args"),
                        o.optString("result"), o.optBoolean("ok", true),
                        o.optJSONObject("undo"), o.optBoolean("undone", false)
                    )
                )
            }
        } catch (_: Throwable) { /* a corrupt log is not worth a crash */ }
    }

    @Synchronized
    private fun persist() {
        val f = file() ?: return
        try {
            val arr = JSONArray()
            for (e in entries) {
                arr.put(
                    JSONObject()
                        .put("at", e.at).put("tool", e.tool).put("args", e.args)
                        .put("result", e.result).put("ok", e.ok)
                        .put("undone", e.undone)
                        .apply { e.undo?.let { put("undo", it) } }
                )
            }
            f.writeText(arr.toString())
        } catch (_: Throwable) {}
    }

    // ---------------- writing ----------------

    /**
     * Register how to reverse the action currently running.
     *
     * Called from inside the tool, because that is the only moment the previous
     * state still exists — afterwards the old bookmark row or the old file
     * bytes are gone.
     */
    fun offerUndo(spec: JSONObject) { pendingUndo = spec }

    /** Keep a copy of [path]'s current bytes and return the backup's name. */
    fun stashFile(path: String): String? {
        return try {
            val src = File(path)
            if (!src.isFile) return null
            val dir = backupDir() ?: return null
            val name = System.currentTimeMillis().toString() + "-" + src.name + ".bak"
            src.copyTo(File(dir, name), overwrite = true)
            name
        } catch (_: Throwable) { null }
    }

    @Synchronized
    fun add(tool: String, args: String, result: String) {
        load()
        val refused = result.startsWith("Пользователь отказал")
        val failed = refused || result.startsWith("Инструмент не отработал") ||
            result.startsWith("Неизвестный инструмент") || result.startsWith("Не удалось")
        val undo = pendingUndo
        pendingUndo = null
        entries.addFirst(
            Entry(
                System.currentTimeMillis(), tool,
                args.take(200), result.lineSequence().firstOrNull()?.take(160).orEmpty(),
                !failed,
                // a refused or failed action has nothing to reverse
                if (failed) null else undo,
                false
            )
        )
        while (entries.size > MAX) {
            val dropped = entries.removeLast()
            // the backup of an entry nobody can see any more is dead weight
            dropped.undo?.optString("backup")?.takeIf { it.isNotBlank() }?.let { b ->
                try { backupDir()?.let { File(it, b).delete() } } catch (_: Throwable) {}
            }
        }
        persist()
    }

    fun dropPendingUndo() { pendingUndo = null }

    @Synchronized
    fun all(): List<Entry> { load(); return entries.toList() }

    /** Cleared only by the user, from the log screen. */
    @Synchronized
    fun clear() {
        entries.clear()
        try { backupDir()?.listFiles()?.forEach { it.delete() } } catch (_: Throwable) {}
        persist()
    }

    @Synchronized
    fun isEmpty(): Boolean { load(); return entries.isEmpty() }

    // ---------------- undoing ----------------

    /**
     * Reverse one action. Blocking — call from a background thread.
     *
     * Every branch treats "it is already gone" as success: undoing a bookmark
     * the user deleted by hand should say so calmly, not fail.
     */
    fun undo(ctx: Context, entry: Entry): String {
        val spec = entry.undo ?: return "Это действие нельзя отменить."
        if (entry.undone) return "Уже отменено."
        val app = ctx.applicationContext
        val msg = try {
            when (spec.optString("kind")) {
                Kind.BOOKMARK -> runBlocking {
                    val dao = AppDb.get(app).bookmarkDao()
                    val url = spec.optString("url")
                    val row = dao.allOnce().firstOrNull { it.url == url }
                    if (row == null) "Закладки уже нет." else { dao.delete(row); "Закладка удалена." }
                }
                Kind.REMINDER -> runBlocking {
                    val id = spec.optLong("id", -1)
                    val dao = AppDb.get(app).reminderDao()
                    val row = dao.byId(id)
                    if (row == null) "Дела уже нет." else {
                        com.zephyr.browser.tasks.Reminders.cancel(app, id)
                        dao.delete(row)
                        // v1.9.14: undoing an assistant action changes the list too
                        com.zephyr.browser.widget.TasksWidgetProvider.refreshAll(app)
                        "Дело удалено."
                    }
                }
                Kind.MEMORY -> {
                    if (AiMemory.remove(spec.optLong("id", -1))) "Факт забыт." else "Факта уже нет."
                }
                Kind.SCRIPT -> runBlocking {
                    val dao = AppDb.get(app).scriptDao()
                    val row = dao.byId(spec.optLong("id", -1))
                    if (row == null) "Скрипта уже нет." else {
                        dao.delete(row)
                        com.zephyr.browser.userscripts.ScriptInjector.refresh()
                        "Скрипт удалён."
                    }
                }
                Kind.MODEL -> {
                    val name = spec.optString("name")
                    val items = AiModels.list().toMutableList()
                    val idx = items.indexOfFirst { it.name == name }
                    if (idx < 0) "Профиля уже нет." else {
                        items.removeAt(idx)
                        AiModels.save(items, 0)
                        "Профиль модели удалён."
                    }
                }
                Kind.FILE_WRITE -> {
                    val path = spec.optString("path")
                    val backup = spec.optString("backup")
                    val target = File(path)
                    if (backup.isBlank()) {
                        // the file did not exist before — undo means removing it
                        if (!target.exists()) "Файла уже нет."
                        else if (target.delete()) "Файл удалён." else "Не удалось удалить файл."
                    } else {
                        val b = backupDir()?.let { File(it, backup) }
                        if (b == null || !b.exists()) "Резервная копия не найдена."
                        else { b.copyTo(target, overwrite = true); "Файл возвращён к прежней версии." }
                    }
                }
                Kind.TEMPLATE -> {
                    if (AiTemplates.remove(spec.optLong("id", -1))) "Шаблон удалён."
                    else "Шаблона уже нет."
                }
                Kind.WORKFLOW -> {
                    if (AiWorkflows.remove(spec.optLong("id", -1))) "Сценарий удалён."
                    else "Сценария уже нет."
                }
                Kind.FILE_COPY -> {
                    val to = File(spec.optString("to"))
                    if (!to.exists()) "Копии уже нет."
                    else {
                        val n = AiWorkspace.delete(to)
                        if (n > 0) "Скопированное удалено." else "Не удалось удалить."
                    }
                }
                else -> "Это действие нельзя отменить."
            }
        } catch (t: Throwable) {
            "Не получилось отменить: " + (t.localizedMessage ?: t.javaClass.simpleName)
        }
        synchronized(this) { entry.undone = true; persist() }
        return msg
    }
}
