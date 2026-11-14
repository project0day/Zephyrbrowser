package com.zephyr.browser.settings

import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.zephyr.browser.Prefs
import com.zephyr.browser.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * v1.7.6 (user: «Резервная копия — отдельное окно в настройках, убери пункт из
 * Основных; в резервную копию будем расширять возможности: история, конфигурация
 * VPN итд»): dedicated screen — WHAT to include (bookmarks / quick tiles /
 * history / settings / VPN configs), weekly AUTO-BACKUP into Downloads/Zephyr
 * with a notification, the last-copy stamp, manual export/import (SAF).
 */
class BackupPrefsFragment : Fragment() {

    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var lastBackupView: TextView? = null

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        val act = activity as? SettingsActivity ?: return View(context)
        val ctx = requireContext()
        val root = act.buildScreen(R.string.set_backup_screen)
        val col = SettingsUi.screen(ctx)
        root.addView(SettingsUi.scrollOf(col), LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        // ---- what goes into the copy ----
        col.addView(SettingsUi.sectionHeader(ctx, getString(R.string.set_backup_include)))
        fun includeRow(id: String, icon: Int, labelRes: Int) {
            col.addView(SettingsUi.switchRow(ctx, icon, getString(labelRes), null, id in Prefs.backupInclude) {
                Prefs.toggleBackupInclude(id, it)
            })
        }
        includeRow("bm", R.drawable.ic_bookmark, R.string.bk_bookmarks)
        includeRow("quick", R.drawable.ic_grid, R.string.bk_quick)
        includeRow("history", R.drawable.ic_history, R.string.bk_history)
        includeRow("settings", R.drawable.ic_settings, R.string.bk_settings)
        includeRow("vpn", R.drawable.ic_vpn, R.string.bk_vpn)
        // v1.7.7 (user: «бэкап паролей в общий JSON»): passwords + cards + autofill
        includeRow("pw", R.drawable.ic_key, R.string.bk_passwords)

        // ---- weekly auto-backup ----
        col.addView(SettingsUi.sectionHeader(ctx, getString(R.string.bk_auto)))
        col.addView(SettingsUi.switchRow(ctx, R.drawable.ic_replay, getString(R.string.bk_auto),
            getString(R.string.bk_auto_sum), Prefs.autoBackup) { on ->
            Prefs.setAutoBackup(on)
            if (on) com.zephyr.browser.settings.BackupScheduler.schedule(ctx)
            else com.zephyr.browser.settings.BackupScheduler.cancel(ctx)
        })

        // ---- last copy stamp ----
        val lastRow = SettingsUi.clickRow(ctx, R.drawable.ic_info, lastBackupText(), null) { }
        lastRow.isClickable = false
        // v1.7.80 FIX: child 2 of this row is the chevron, not a label, so the
        // cast always produced null and the stamp never refreshed after a
        // backup — it only ever updated when the screen was rebuilt.
        lastBackupView = firstTextView(lastRow)
        col.addView(lastRow)

        // ---- manual export / import ----
        // v1.7.80 (user: «два последних пункта надо красиво в две строчки, они
        // криво расписаны»). They were built with clickRow, whose fourth
        // argument is the right-hand VALUE slot — a single line, right-aligned,
        // meant for a short state like «вкл». Two full sentences went in there
        // and were squeezed against the chevron. clickRowSummary puts the same
        // text where it belongs: a second line under the title, like every
        // other described row in Settings.
        col.addView(SettingsUi.sectionHeader(ctx, getString(R.string.set_backup)))
        col.addView(SettingsUi.clickRowSummary(ctx, R.drawable.ic_upload, getString(R.string.bk_now),
            getString(R.string.bk_now_sum)) { pickBackupExport() })
        col.addView(SettingsUi.clickRowSummary(ctx, R.drawable.ic_download, getString(R.string.set_backup_import),
            getString(R.string.set_backup_import_sum)) { pickBackupImport() })

        return root
    }

    /** The row's title label, wherever the row builder happened to put it. */
    private fun firstTextView(v: View): TextView? {
        if (v is TextView) return v
        if (v is android.view.ViewGroup) {
            for (i in 0 until v.childCount) firstTextView(v.getChildAt(i))?.let { return it }
        }
        return null
    }

    private fun lastBackupText(): String =
        if (Prefs.lastBackupAt > 0) {
            val fmt = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault())
            getString(R.string.bk_last, fmt.format(Date(Prefs.lastBackupAt)))
        } else getString(R.string.bk_last_never)

    // ---------------- SAF export/import ----------------

    private val backupExportLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) doBackupExport(uri)
    }
    private val backupImportLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) doBackupImport(uri)
    }

    private fun pickBackupExport() {
        val name = "zephyr-backup-" +
            SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date()) + ".json"
        try { backupExportLauncher.launch(name) } catch (_: Exception) {
            Toast.makeText(requireContext(), R.string.backup_failed, Toast.LENGTH_SHORT).show()
        }
    }

    private fun pickBackupImport() {
        try { backupImportLauncher.launch(arrayOf("application/json", "application/octet-stream", "text/*")) }
        catch (_: Exception) { Toast.makeText(requireContext(), R.string.backup_failed, Toast.LENGTH_SHORT).show() }
    }

    private fun doBackupExport(uri: Uri) {
        val ctx = context ?: return
        // v1.7.7: optional FILE PASSWORD — the whole backup is AES-256-GCM encrypted
        // (PBKDF2 120k); strongly suggested when passwords are included.
        val wantsPw = "pw" in Prefs.backupInclude
        val input = android.widget.EditText(ctx).apply {
            hint = getString(R.string.bk_file_password_hint)
            setSingleLine(true)
            transformationMethod = android.text.method.PasswordTransformationMethod()
            setPadding(dp(20), dp(12), dp(20), dp(4))
        }
        // v1.8.6 (audit A-005): the password is no longer optional *for the
        // vault*. Leaving it empty now exports everything except passwords and
        // cards, instead of writing them in clear — and that has to be said
        // here, because a section disappearing silently is its own bug.
        com.google.android.material.dialog.MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.bk_file_password)
            .setMessage(if (wantsPw) getString(R.string.bk_password_required) else null)
            .setView(input)
            .setPositiveButton(R.string.save) { _, _ ->
                val pw = input.text.toString()
                io.launch {
                    val ok = BackupManager.export(ctx, uri, pw)
                    withContext(Dispatchers.Main) {
                        Toast.makeText(ctx, if (ok) R.string.backup_exported else R.string.backup_failed,
                            Toast.LENGTH_SHORT).show()
                        lastBackupView?.text = lastBackupText()
                    }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun doBackupImport(uri: Uri) {
        val ctx = context ?: return
        io.launch {
            val n = BackupManager.import(ctx, uri)
            withContext(Dispatchers.Main) {
                when {
                    n >= 0 -> Toast.makeText(ctx, getString(R.string.backup_imported_fmt, n), Toast.LENGTH_LONG).show()
                    // v1.7.7: password-protected file → ask, then retry
                    n == -2 -> askImportPassword(uri)
                    n == -3 -> {
                        Toast.makeText(ctx, R.string.bk_wrong_password, Toast.LENGTH_SHORT).show()
                        askImportPassword(uri)
                    }
                    else -> Toast.makeText(ctx, R.string.backup_failed, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun askImportPassword(uri: Uri) {
        val ctx = context ?: return
        val input = android.widget.EditText(ctx).apply {
            hint = getString(R.string.bk_file_password_hint)
            setSingleLine(true)
            transformationMethod = android.text.method.PasswordTransformationMethod()
            setPadding(dp(20), dp(12), dp(20), dp(4))
        }
        com.google.android.material.dialog.MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.bk_file_password)
            .setView(input)
            .setPositiveButton(R.string.open) { _, _ ->
                val pw = input.text.toString()
                io.launch {
                    val n = BackupManager.import(ctx, uri, pw)
                    withContext(Dispatchers.Main) {
                        when {
                            n >= 0 -> Toast.makeText(ctx, getString(R.string.backup_imported_fmt, n), Toast.LENGTH_LONG).show()
                            n == -3 -> Toast.makeText(ctx, R.string.bk_wrong_password, Toast.LENGTH_SHORT).show()
                            else -> Toast.makeText(ctx, R.string.backup_failed, Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()
}
