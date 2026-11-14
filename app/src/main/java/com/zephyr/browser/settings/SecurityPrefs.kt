package com.zephyr.browser.settings

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.Toast
import androidx.biometric.BiometricManager
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.zephyr.browser.Prefs
import com.zephyr.browser.R
import com.zephyr.browser.panels.PanelsActivity
import com.zephyr.browser.panels.VaultGate
import com.zephyr.browser.panels.VaultSession

// =====================================================================
// Security (v1.5.0 restructure): everything about the password vault lives
// in ONE section — PIN, biometrics, autolock, saving, autofill, vault entry.
// All switches are wired to real state; the rows re-render from Prefs after
// every dialog so the screen always shows the truth (no dead switches).
// =====================================================================

class SecurityPrefsFragment : Fragment() {

    private lateinit var col: LinearLayout

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        val act = activity as? SettingsActivity ?: return View(context)
        val ctx = requireContext()
        val root = act.buildScreen(R.string.set_security)
        col = SettingsUi.screen(ctx)
        root.addView(SettingsUi.scrollOf(col), LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        buildRows()
        return root
    }

    /** Rows are rebuilt from the live Prefs state after every change (single source of truth). */
    private fun buildRows() {
        val ctx = context ?: return
        if (!this::col.isInitialized) return
        col.removeAllViews()

        // =================== Хранилище паролей ===================
        col.addView(SettingsUi.sectionHeader(ctx, getString(R.string.vault_section)))

        // --- PIN-код хранилища ---
        col.addView(SettingsUi.switchRow(ctx, R.drawable.ic_lock, getString(R.string.set_pin),
            getString(R.string.vault_sum_pin), Prefs.pinHash.isNotEmpty()) { on ->
            if (on) turnPinOn() else turnPinOff()
        })

        // --- Сменить PIN (only meaningful when a PIN exists) ---
        if (Prefs.pinHash.isNotEmpty()) {
            col.addView(SettingsUi.clickRow(ctx, R.drawable.ic_edit,
                getString(R.string.vault_change_pin)) { changePin() })
        }

        // --- Биометрия (requires a PIN; verified against real hardware) ---
        col.addView(SettingsUi.switchRow(ctx, R.drawable.ic_user, getString(R.string.vault_biometrics),
            getString(R.string.vault_sum_biometrics),
            Prefs.useBiometrics && Prefs.pinHash.isNotEmpty()) { on ->
            if (on) turnBiometricsOn() else Prefs.setUseBiometrics(false)
        })

        // --- Автоблокировка ---
        col.addView(SettingsUi.clickRow(ctx, R.drawable.ic_timer, getString(R.string.vault_autolock),
            autolockLabel()) { chooseAutolock() })

        // --- Сохранять пароли ---
        col.addView(SettingsUi.switchRow(ctx, R.drawable.ic_key, getString(R.string.set_save_passwords),
            getString(R.string.vault_sum_save), Prefs.savePasswords) { on ->
            Prefs.setSavePasswords(on)
        })

        // --- Автозаполнение ---
        col.addView(SettingsUi.switchRow(ctx, R.drawable.ic_globe, getString(R.string.set_autofill),
            getString(R.string.vault_sum_autofill), Prefs.autofillEnabled) { on ->
            Prefs.setAutofill(on)
        })

        // --- Открыть хранилище ---
        col.addView(SettingsUi.clickRow(ctx, R.drawable.ic_external, getString(R.string.vault_open)) {
            try {
                startActivity(Intent(ctx, PanelsActivity::class.java).apply {
                    putExtra("panel", "passwords")
                })
            } catch (_: Exception) {}
        })

        // =================== Прочее ===================
        col.addView(SettingsUi.sectionHeader(ctx, getString(R.string.vault_section_other)))
        col.addView(SettingsUi.switchRow(ctx, R.drawable.ic_copy, getString(R.string.set_clipboard), getString(R.string.h_clipboard),
            Prefs.clipboardClear) { on ->
            Prefs.setClipboardClear(on)
        })
    }

    // ------------------------------------------------------------------
    // PIN flows (shared two-step setup lives in VaultGate)
    // ------------------------------------------------------------------

    /** Switch turned ON → two-step PIN setup (enter + confirm, 4–8 digits). */
    private fun turnPinOn() {
        val act = activity as? FragmentActivity ?: run { buildRows(); return }
        VaultGate.setupPinDialog(act) { buildRows() }
        buildRows()   // truth while the dialog is running (PIN not yet set)
    }

    /** Switch turned OFF → the CURRENT PIN must be entered first. */
    private fun turnPinOff() {
        val act = activity as? FragmentActivity ?: run { buildRows(); return }
        if (Prefs.pinHash.isEmpty()) { buildRows(); return }
        VaultGate.showGate(act) {
            Prefs.setPin("", "")
            Prefs.setUseBiometrics(false)     // biometrics makes no sense without a PIN
            VaultSession.lock()
            VaultGate.resetPinOffer(act)       // panel will offer the setup again
            Toast.makeText(act, R.string.vault_pin_removed, Toast.LENGTH_SHORT).show()
            buildRows()
        }
        buildRows()   // truth while the verification gate is running
    }

    /** Сменить PIN: verify the current one, then run the two-step new-PIN flow. */
    private fun changePin() {
        val act = activity as? FragmentActivity ?: return
        VaultGate.showGate(act) {
            VaultGate.setupPinDialog(act) {
                Toast.makeText(act, R.string.vault_pin_changed, Toast.LENGTH_SHORT).show()
                buildRows()
            }
        }
    }

    // ------------------------------------------------------------------
    // biometrics
    // ------------------------------------------------------------------

    private fun turnBiometricsOn() {
        val act = activity as? FragmentActivity ?: run { buildRows(); return }
        if (Prefs.pinHash.isEmpty()) {
            Toast.makeText(act, R.string.vault_need_pin_first, Toast.LENGTH_SHORT).show()
            buildRows()
            return
        }
        val can = try {
            // v1.5.0 fix: DEVICE_CREDENTIAL (not BIOMETRIC_DEVICE_CREDENTIAL — that constant
            // does not exist in androidx.biometric 1.1.0); combination only probed on API 30+.
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                BiometricManager.from(act).canAuthenticate(
                    BiometricManager.Authenticators.BIOMETRIC_WEAK or
                        BiometricManager.Authenticators.DEVICE_CREDENTIAL
                ) == BiometricManager.BIOMETRIC_SUCCESS
            } else {
                BiometricManager.from(act).canAuthenticate(
                    BiometricManager.Authenticators.BIOMETRIC_WEAK
                ) == BiometricManager.BIOMETRIC_SUCCESS
            }
        } catch (_: Exception) { false }
        if (!can) {
            Toast.makeText(act, R.string.vault_biometrics_unavailable, Toast.LENGTH_SHORT).show()
            buildRows()
            return
        }
        Prefs.setUseBiometrics(true)
        buildRows()
    }

    // ------------------------------------------------------------------
    // autolock
    // ------------------------------------------------------------------

    private fun chooseAutolock() {
        val ctx = context ?: return
        val values = intArrayOf(0, 30, 60, 300)
        val labels = arrayOf(
            getString(R.string.vault_autolock_immediately),
            getString(R.string.vault_autolock_30s),
            getString(R.string.vault_autolock_1m),
            getString(R.string.vault_autolock_5m))
        val current = values.indexOfFirst { it == Prefs.vaultAutolock }.let { if (it < 0) 2 else it }
        MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.vault_autolock)
            .setSingleChoiceItems(labels, current) { di, which ->
                Prefs.setVaultAutolock(values[which])
                VaultSession.lock()   // the session re-arms with the new delay on next unlock
                di.dismiss()
                buildRows()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun autolockLabel(): String = when (Prefs.vaultAutolock) {
        0 -> getString(R.string.vault_autolock_immediately)
        30 -> getString(R.string.vault_autolock_30s)
        300 -> getString(R.string.vault_autolock_5m)
        else -> getString(R.string.vault_autolock_1m)
    }
}
