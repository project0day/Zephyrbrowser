package com.zephyr.browser.tasks

import android.content.Context
import com.zephyr.browser.db.ReminderEntity
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * v1.7.57: tasks as an .ics file, so they can leave the browser.
 *
 * Reminders that live only inside one app are reminders you cannot check on a
 * laptop. iCalendar is the one format every calendar reads, and writing it is
 * a few dozen lines — no library, no account, no sync service.
 *
 * Times are written in UTC with the trailing Z, which is the only form every
 * reader agrees on; a local-time VEVENT without a VTIMEZONE block is
 * interpreted differently by different calendars.
 */
object TaskExport {

    private fun utc(): SimpleDateFormat =
        SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }

    /** RFC 5545 escaping: backslash, comma, semicolon, newline. */
    private fun esc(s: String): String = s
        .replace("\\", "\\\\")
        .replace(";", "\\;")
        .replace(",", "\\,")
        .replace("\r\n", "\\n")
        .replace("\n", "\\n")

    private fun rrule(repeat: Int): String? = when (repeat) {
        Reminders.DAILY -> "RRULE:FREQ=DAILY"
        Reminders.WEEKDAYS -> "RRULE:FREQ=WEEKLY;BYDAY=MO,TU,WE,TH,FR"
        Reminders.WEEKLY -> "RRULE:FREQ=WEEKLY"
        Reminders.MONTHLY -> "RRULE:FREQ=MONTHLY"
        else -> null
    }

    fun build(rows: List<ReminderEntity>): String {
        val f = utc()
        val stamp = f.format(Date())
        val sb = StringBuilder()
        sb.append("BEGIN:VCALENDAR\r\n")
        sb.append("VERSION:2.0\r\n")
        sb.append("PRODID:-//Zephyr Browser//Tasks//EN\r\n")
        sb.append("CALSCALE:GREGORIAN\r\n")
        for (r in rows) {
            sb.append("BEGIN:VEVENT\r\n")
            sb.append("UID:zephyr-").append(r.id).append("@zephyr.browser\r\n")
            sb.append("DTSTAMP:").append(stamp).append("\r\n")
            sb.append("DTSTART:").append(f.format(Date(r.dueAt))).append("\r\n")
            // a reminder is a moment, not a span; 30 minutes keeps it visible
            // in week and day views instead of collapsing to a hairline
            sb.append("DTEND:").append(f.format(Date(r.dueAt + 1_800_000))).append("\r\n")
            sb.append("SUMMARY:").append(esc(r.title)).append("\r\n")
            val desc = buildString {
                if (r.notes.isNotBlank()) append(r.notes)
                if (r.url.isNotBlank()) {
                    if (isNotEmpty()) append("\n")
                    append(r.url)
                }
            }
            if (desc.isNotBlank()) sb.append("DESCRIPTION:").append(esc(desc)).append("\r\n")
            if (r.url.isNotBlank()) sb.append("URL:").append(esc(r.url)).append("\r\n")
            rrule(r.repeat)?.let { sb.append(it).append("\r\n") }
            if (r.done) sb.append("STATUS:CANCELLED\r\n")
            // an alarm at the moment itself, matching what the phone does
            sb.append("BEGIN:VALARM\r\n")
            sb.append("TRIGGER:PT0M\r\n")
            sb.append("ACTION:DISPLAY\r\n")
            sb.append("DESCRIPTION:").append(esc(r.title)).append("\r\n")
            sb.append("END:VALARM\r\n")
            sb.append("END:VEVENT\r\n")
        }
        sb.append("END:VCALENDAR\r\n")
        return sb.toString()
    }

    /** Write into Documents/Zephyr and return the file, or null. */
    fun writeFile(ctx: Context, rows: List<ReminderEntity>): File? = try {
        val dir = File(
            android.os.Environment.getExternalStoragePublicDirectory(
                android.os.Environment.DIRECTORY_DOCUMENTS
            ), "Zephyr"
        )
        if (!dir.exists()) dir.mkdirs()
        val name = "zephyr-tasks-" +
            SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date()) + ".ics"
        val out = File(dir, name)
        out.writeText(build(rows), Charsets.UTF_8)
        try {
            android.media.MediaScannerConnection.scanFile(
                ctx.applicationContext, arrayOf(out.absolutePath), null, null
            )
        } catch (_: Throwable) {}
        out
    } catch (_: Throwable) { null }
}
