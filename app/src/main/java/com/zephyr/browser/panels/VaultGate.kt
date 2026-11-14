package com.zephyr.browser.panels

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.text.InputFilter
import android.text.InputType
import android.widget.Toast
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.zephyr.browser.Prefs
import com.zephyr.browser.R
import com.zephyr.browser.VaultCrypto
import java.security.SecureRandom

/**
 * v1.5.0 vault gate — ONE shared lock system for the whole app
 * (the user complained the PIN/biometrics options were scattered and disconnected).
 *
 * - [VaultSession] holds the process-wide unlock window + failed-attempt counter.
 * - [VaultGate.showGate] unlocks the vault with a biometric prompt when available,
 *   otherwise with the PIN dialog. v1.7.2 optional finish-on-cancel mode: the
 *   passwords panel passes it so cancelling CLOSES the host screen instead of
 *   leaving a dead empty background; in-screen verification callers (Security
 *   settings) keep the default stay-locked behavior.
 * - [VaultGate.setupPinDialog] is the shared two-step (enter → confirm) PIN setup
 *   used by the passwords panel first-run offer and by Security settings.
 */
object VaultSession {
    @Volatile var unlockedUntil: Long = 0   // epoch ms
    @Volatile var failedAttempts: Int = 0

    fun isUnlocked(): Boolean = System.currentTimeMillis() < unlockedUntil

    fun unlock(seconds: Int) {
        unlockedUntil = System.currentTimeMillis() + seconds * 1000L
        failedAttempts = 0
    }

    fun lock() { unlockedUntil = 0 }
}

object VaultGate {

    /** Wrong PINs in a row before the user must reopen the vault. */
    const val MAX_ATTEMPTS = 5

    /**
     * Session window in seconds: the configured autolock delay, or 300 s when
     * «lock on exit» (vaultAutolock == 0) is selected — in that mode the real lock
     * fires in the panel's onPause.
     */
    fun sessionSeconds(): Int = Prefs.vaultAutolock.takeIf { it > 0 } ?: 300

    /** True when a biometric prompt can be shown right now (setting on + hardware + enrolled). */
    fun canUseBiometrics(activity: FragmentActivity): Boolean {
        if (!Prefs.useBiometrics || Prefs.pinHash.isEmpty()) return false
        return try {
            // v1.5.0 fix: Authenticators.DEVICE_CREDENTIAL is the correct constant in
            // androidx.biometric 1.1.0 (BIOMETRIC_DEVICE_CREDENTIAL does not exist).
            // Combining DEVICE_CREDENTIAL with biometrics is only supported on API 30+,
            // so below that we probe BIOMETRIC_WEAK only (the PIN dialog is the fallback).
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                BiometricManager.from(activity).canAuthenticate(
                    BiometricManager.Authenticators.BIOMETRIC_WEAK or
                        BiometricManager.Authenticators.DEVICE_CREDENTIAL
                ) == BiometricManager.BIOMETRIC_SUCCESS
            } else {
                BiometricManager.from(activity).canAuthenticate(
                    BiometricManager.Authenticators.BIOMETRIC_WEAK
                ) == BiometricManager.BIOMETRIC_SUCCESS
            }
        } catch (_: Exception) { false }
    }

    /**
     * Show the unlock gate: biometric prompt when available, otherwise the PIN dialog.
     * [onUnlocked] runs on the main thread after successful authentication.
     *
     * v1.7.2 [finishOnCancel] (user report: «нажимаю Отмена в паролях — и фон остаётся
     * пустым, логичнее закрыть окно»): cancelling the gate — PIN dialog «Отмена» or a
     * user-initiated biometric cancel (back press / negative button) — finishes the
     * host activity. Hardware errors and lockouts still fall back to the PIN dialog
     * so the user can retry. Default false = old stay-locked behavior.
     */
    fun showGate(activity: FragmentActivity, finishOnCancel: Boolean = false, onUnlocked: () -> Unit) {
        if (Prefs.pinHash.isEmpty()) {              // nothing to unlock against
            VaultSession.unlock(sessionSeconds())
            onUnlocked()
            return
        }
        if (VaultSession.failedAttempts >= MAX_ATTEMPTS) {
            Toast.makeText(activity, R.string.vault_locked_out, Toast.LENGTH_LONG).show()
            if (finishOnCancel) finishHost(activity)   // no dead empty background either
            return
        }
        if (canUseBiometrics(activity)) {
            try {
                val executor = ContextCompat.getMainExecutor(activity)
                val prompt = BiometricPrompt(activity, executor,
                    object : BiometricPrompt.AuthenticationCallback() {
                        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                            VaultSession.unlock(sessionSeconds())
                            onUnlocked()
                        }

                        override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                            // v1.7.2: user-initiated cancel (back press / «Отмена» in the system
                            // prompt) + finishOnCancel → close the host screen directly, no PIN
                            // fallback; hardware errors and retries still get the PIN dialog.
                            val userCancelled =
                                errorCode == BiometricPrompt.ERROR_USER_CANCELED ||
                                errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON
                            if (userCancelled && finishOnCancel) finishHost(activity)
                            else pinDialog(activity, onUnlocked, finishOnCancel)
                        }
                    })
                val info = BiometricPrompt.PromptInfo.Builder()
                    .setTitle(activity.getString(R.string.vault_biometric_title))
                    .setSubtitle(activity.getString(R.string.vault_biometric_subtitle))
                    .setAllowedAuthenticators(
                        if (android.os.Build.VERSION.SDK_INT >= 30)
                            BiometricManager.Authenticators.BIOMETRIC_WEAK or
                                BiometricManager.Authenticators.DEVICE_CREDENTIAL
                        else BiometricManager.Authenticators.BIOMETRIC_WEAK)
                    .setConfirmationRequired(false)
                    .build()
                prompt.authenticate(info)
                return
            } catch (_: Exception) { /* fall back to the PIN dialog below */ }
        }
        pinDialog(activity, onUnlocked, finishOnCancel)
    }

    /**
     * PIN unlock dialog (number-password field, non-cancelable except the negative
     * button). Up to [MAX_ATTEMPTS] wrong tries. v1.7.2: with [finishOnCancel] the
     * negative button (and the 5th wrong try) CLOSES the host activity instead of
     * leaving it locked on an empty background.
     */
    fun pinDialog(activity: FragmentActivity, onUnlocked: () -> Unit, finishOnCancel: Boolean = false) {
        if (Prefs.pinHash.isEmpty()) {
            VaultSession.unlock(sessionSeconds())
            onUnlocked()
            return
        }
        if (VaultSession.failedAttempts >= MAX_ATTEMPTS) {
            Toast.makeText(activity, R.string.vault_locked_out, Toast.LENGTH_LONG).show()
            if (finishOnCancel) finishHost(activity)
            return
        }
        val input = pinField(activity)
        try {
            MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.vault_gate_title)
                .setMessage(R.string.vault_gate_message)
                .setView(PanelUi.fieldWrap(input))
                .setPositiveButton(R.string.ok) { _, _ ->
                    val pin = input.text.toString()
                    val ok = try {
                        pin.isNotEmpty() && VaultCrypto.sha256Hex(pin, Prefs.pinSalt) == Prefs.pinHash
                    } catch (_: Exception) { false }
                    if (ok) {
                        VaultSession.unlock(sessionSeconds())
                        onUnlocked()
                    } else {
                        VaultSession.failedAttempts++
                        if (VaultSession.failedAttempts >= MAX_ATTEMPTS) {
                            Toast.makeText(activity, R.string.vault_locked_out, Toast.LENGTH_LONG).show()
                            if (finishOnCancel) finishHost(activity)
                        } else {
                            Toast.makeText(
                                activity,
                                activity.getString(R.string.vault_wrong_pin) +
                                    " (" + VaultSession.failedAttempts + "/" + MAX_ATTEMPTS + ")",
                                Toast.LENGTH_SHORT
                            ).show()
                            pinDialog(activity, onUnlocked, finishOnCancel)
                        }
                    }
                }
                // v1.7.2 (user: «нажимаю Отмена — фон остаётся пустым, окно должно
                // закрыться»): with finishOnCancel the whole host screen closes.
                .setNegativeButton(R.string.cancel) { _, _ ->
                    if (finishOnCancel) finishHost(activity)
                }
                .setCancelable(false)
                .show()
        } catch (_: Exception) { /* activity gone — stay locked */ }
    }

    /**
     * v1.7.2: closes the host screen of a cancelled vault gate. Posted to the main
     * thread (dialog and biometric callbacks already run there, but an explicit post
     * also covers coroutine resumption and lets the dialog auto-dismiss first).
     */
    private fun finishHost(activity: FragmentActivity) {
        try {
            Handler(Looper.getMainLooper()).post {
                try { activity.finish() } catch (_: Exception) {}
            }
        } catch (_: Exception) {}
    }

    /**
     * Two-step PIN setup: enter → confirm. On success the PIN is persisted
     * (salted SHA-256), the session is unlocked and [onDone] runs.
     */
    fun setupPinDialog(activity: FragmentActivity, onDone: () -> Unit) {
        askNewPin(activity) { pin -> confirmNewPin(activity, pin, onDone) }
    }

    private fun askNewPin(activity: FragmentActivity, onPinEntered: (String) -> Unit) {
        val input = pinField(activity)
        try {
            MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.vault_setup_title)
                .setMessage(R.string.vault_setup_message)
                .setView(PanelUi.fieldWrap(input))
                .setPositiveButton(R.string.ok) { _, _ ->
                    val pin = input.text.toString()
                    if (pin.length in 4..8) onPinEntered(pin)
                    else {
                        Toast.makeText(activity, R.string.vault_pin_too_short, Toast.LENGTH_SHORT).show()
                        askNewPin(activity, onPinEntered)
                    }
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        } catch (_: Exception) {}
    }

    private fun confirmNewPin(activity: FragmentActivity, pin: String, onDone: () -> Unit) {
        val input = pinField(activity)
        try {
            MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.vault_confirm_title)
                .setView(PanelUi.fieldWrap(input))
                .setPositiveButton(R.string.ok) { _, _ ->
                    if (input.text.toString() == pin) {
                        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
                            .joinToString("") { "%02x".format(it) }
                        Prefs.setPin(VaultCrypto.sha256Hex(pin, salt), salt)
                        VaultSession.unlock(sessionSeconds())
                        Toast.makeText(activity, R.string.vault_pin_set_done, Toast.LENGTH_SHORT).show()
                        onDone()
                    } else {
                        Toast.makeText(activity, R.string.vault_pin_mismatch, Toast.LENGTH_SHORT).show()
                        confirmNewPin(activity, pin, onDone)
                    }
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        } catch (_: Exception) {}
    }

    private fun pinField(activity: FragmentActivity): TextInputEditText =
        PanelUi.dialogFieldLabeled(activity, activity.getString(R.string.vault_pin_label), "••••").apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            filters = arrayOf(InputFilter.LengthFilter(8))
        }

    // ------------------------------------------------------------------
    // One-time «Установить PIN?» offer state (shared between the passwords
    // panel and Security settings, kept out of Prefs.kt on purpose).
    // ------------------------------------------------------------------

    private fun ui(ctx: Context) = ctx.getSharedPreferences("vault_ui", Context.MODE_PRIVATE)

    fun pinOfferDeclined(ctx: Context): Boolean =
        try { ui(ctx).getBoolean("pin_offer_declined", false) } catch (_: Exception) { false }

    fun markPinOfferDone(ctx: Context) {
        try { ui(ctx).edit().putBoolean("pin_offer_declined", true).apply() } catch (_: Exception) {}
    }

    /** PIN removed → the panel should offer the setup again on the next open. */
    fun resetPinOffer(ctx: Context) {
        try { ui(ctx).edit().remove("pin_offer_declined").apply() } catch (_: Exception) {}
    }
}
