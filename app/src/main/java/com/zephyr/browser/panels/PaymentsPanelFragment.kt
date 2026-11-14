package com.zephyr.browser.panels

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.zephyr.browser.Prefs
import com.zephyr.browser.R
import com.zephyr.browser.VaultCrypto
import com.zephyr.browser.db.AppDb
import com.zephyr.browser.db.PaymentEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first

/**
 * Payment methods panel (v1.4.1): bank cards + PayPal, stored SECRETLY
 * (PAN/CVV encrypted with Android-Keystore AES-256-GCM via VaultCrypto).
 * Used by the payment-form quick fill (MainActivity payment detection).
 *
 * v1.7.9 (user: «платёжные данные надо тоже дать опцию установить пароль
 * для входа с биометрией на всякий»): the panel can now be gated with the
 * SHARED vault lock (PIN + biometrics, [VaultGate]/[VaultSession] — same
 * system as the passwords panel):
 *  - [Prefs.paymentsGate] ON → a frosted lock overlay covers the content on
 *    open and the vault gate (biometric prompt / PIN dialog) is shown; the
 *    card list is NOT populated until the gate passes ([reveal]).
 *  - A premium switch row at the top of the list toggles the gate; first
 *    enable walks through the shared two-step PIN setup.
 */
class PaymentsPanelFragment : Fragment() {

    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var items: List<PaymentEntity> = emptyList()
    private var revealed = false                      // v1.7.9: gate passed, content visible
    private lateinit var list: LinearLayout
    private lateinit var gateOverlay: FrameLayout     // v1.7.9: frosted cover while locked

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        val ctx = requireContext()
        // v1.7.9: a fresh panel open resets the wrong-PIN counter (5 tries per open)
        if (Prefs.paymentsGate) VaultSession.failedAttempts = 0

        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        }

        // actions: add card / add paypal
        root.addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(dp(8), dp(4), dp(8), 0)
            fun ib(icon: Int, desc: Int, onClick: () -> Unit) = android.widget.ImageButton(ctx).apply {
                setImageResource(icon)
                background = PanelUi.ripple(ctx)
                setPadding(dp(10), dp(10), dp(10), dp(10))
                layoutParams = LinearLayout.LayoutParams(dp(42), dp(42))
                imageTintList = android.content.res.ColorStateList.valueOf(
                    PanelUi.themedColor(ctx, R.attr.colorOnSurfaceVariant))
                contentDescription = getString(desc)
                setOnClickListener { onClick() }
            }
            addView(ib(R.drawable.ic_add, R.string.pay_add_card) { addCardDialog() })
            addView(ib(R.drawable.ic_user, R.string.pay_add_paypal) { addPaypalDialog() })
        })

        val scroll = ScrollView(ctx)
        list = PanelUi.listColumn(ctx)
        scroll.addView(list)
        root.addView(scroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        // v1.7.9: the lock overlay sits OVER the whole content (list + action row),
        // so card data never flashes before the gate result and nothing is
        // reachable while locked. List stays empty until the gate passes.
        gateOverlay = buildGateOverlay(ctx)
        return FrameLayout(ctx).apply {
            addView(root)
            addView(gateOverlay, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        gate()
    }

    override fun onResume() {
        super.onResume()
        // v1.7.9: autolock expiry while the panel was in the background → re-gate
        if (revealed && Prefs.paymentsGate && Prefs.pinHash.isNotEmpty() &&
            !VaultSession.isUnlocked()) {
            lock()
            gate()
        }
    }

    override fun onPause() {
        super.onPause()
        // v1.7.9: «Сразу при выходе» mode (vaultAutolock == 0) drops the shared
        // session as soon as the panel loses focus; timed windows are governed
        // by VaultSession itself, no code needed here.
        if (Prefs.paymentsGate && Prefs.vaultAutolock == 0) VaultSession.lock()
    }

    // ------------------------------------------------------------------
    // v1.7.9 vault gate (PIN / biometrics) — mirrors PasswordsPanelFragment
    // ------------------------------------------------------------------

    private fun gate() {
        if (!Prefs.paymentsGate || VaultSession.isUnlocked()) { reveal(); return }
        val act = activity as? FragmentActivity ?: return   // stay locked, list stays empty
        lock()
        // finishOnCancel: cancelling the biometric prompt / PIN dialog closes the
        // host screen (PanelsActivity) — same UX as the passwords panel.
        VaultGate.showGate(act, finishOnCancel = true) { reveal() }
    }

    private fun lock() {
        revealed = false
        items = emptyList()
        if (this::list.isInitialized) list.removeAllViews()
        if (this::gateOverlay.isInitialized) gateOverlay.visibility = View.VISIBLE
    }

    private fun reveal() {
        revealed = true
        if (this::gateOverlay.isInitialized) gateOverlay.visibility = View.GONE
        reload()
    }

    /**
     * Frosted full-cover overlay shown while the vault gate is up: premium lock
     * badge (aurora gradient circle + halo, matches the v1.7.9 hint banners),
     * pay_gate_locked title, an unlock prompt and an «Разблокировать» button.
     */
    private fun buildGateOverlay(ctx: Context): FrameLayout = FrameLayout(ctx).apply {
        visibility = View.GONE
        isClickable = true          // swallow taps — nothing is reachable while locked
        isFocusable = true
        // dark-theme-aware frosted backdrop: the themed surface at ~70 % alpha
        setBackgroundColor(
            (PanelUi.themedColor(ctx, R.attr.colorSurface, 0xFFFFFFFF.toInt()) and 0x00FFFFFF)
                or 0xB3000000.toInt())

        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(24), dp(24), dp(24))
        }

        // premium badge: soft cyan halo behind a violet→indigo→teal gradient circle
        val badge = FrameLayout(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(dp(148), dp(148)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
            }
        }
        badge.addView(View(ctx).apply {
            layoutParams = FrameLayout.LayoutParams(dp(148), dp(148), Gravity.CENTER)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0x2E22D3EE.toInt())       // aurora-cyan halo (hint-banner accent)
            }
        })
        badge.addView(FrameLayout(ctx).apply {
            layoutParams = FrameLayout.LayoutParams(dp(96), dp(96), Gravity.CENTER)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                orientation = GradientDrawable.Orientation.TL_BR
                colors = intArrayOf(0xFF2D2A55.toInt(), 0xFF223A63.toInt(), 0xFF164E63.toInt())
                setStroke(dp(1).coerceAtLeast(1), 0x33FFFFFF)   // frosted rim
            }
            addView(ImageView(ctx).apply {
                setImageResource(R.drawable.ic_lock)
                layoutParams = FrameLayout.LayoutParams(dp(36), dp(36), Gravity.CENTER)
                imageTintList = android.content.res.ColorStateList.valueOf(0xFFA7F3D0.toInt())
            })
        })
        col.addView(badge)

        col.addView(TextView(ctx).apply {
            setText(R.string.pay_gate_locked)
            textSize = 17f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(0, dp(18), 0, dp(4))
            setTextColor(PanelUi.themedColor(ctx, R.attr.colorOnSurface))
        })
        col.addView(TextView(ctx).apply {
            setText(R.string.pay_gate_sum)
            textSize = 13f
            gravity = Gravity.CENTER
            setLineSpacing(dp(2).toFloat(), 1f)
            setTextColor(PanelUi.themedColor(ctx, R.attr.colorOnSurfaceVariant))
        })
        col.addView(TextView(ctx).apply {
            setText(R.string.vault_unlock)
            textSize = 15f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(0xFFFFFFFF.toInt())
            gravity = Gravity.CENTER
            setPadding(dp(22), dp(12), dp(22), dp(12))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(24).toFloat()
                setColor(PanelUi.accent(ctx))
            }
            isClickable = true
            isFocusable = true
            foreground = PanelUi.ripple(ctx)
            setOnClickListener { gate() }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(18)
                gravity = Gravity.CENTER_HORIZONTAL
            }
        })

        addView(col, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.CENTER))
    }

    /**
     * v1.7.9: premium switch row at the top of the list column — rounded 16 dp
     * surface card, lock icon chip, pay_gate_title / pay_gate_sum and a passive
     * Switch bound to [Prefs.paymentsGate] (the whole row toggles).
     */
    private fun gateSwitchRow(ctx: Context): LinearLayout {
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(12), dp(10), dp(12))
            minimumHeight = dp(52)
            background = PanelUi.cardBg(ctx, 16)
            foreground = PanelUi.ripple(ctx)
            isClickable = true
            isFocusable = true
            clipToOutline = true
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(dp(12), dp(12), dp(12), dp(12)) }
        }
        row.addView(PanelUi.iconChip(ctx, R.drawable.ic_lock))
        row.addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            addView(TextView(ctx).apply {
                setText(R.string.pay_gate_title)
                textSize = 15f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                maxLines = 2
                setTextColor(PanelUi.themedColor(ctx, R.attr.colorOnSurface))
            })
            addView(TextView(ctx).apply {
                setText(R.string.pay_gate_sum)
                textSize = 12.5f
                setPadding(0, dp(2), 0, 0)
                setTextColor(PanelUi.themedColor(ctx, R.attr.colorOnSurfaceVariant))
            })
        })
        val sw = Switch(ctx).apply {
            isChecked = Prefs.paymentsGate
            isClickable = false      // passive — the row itself toggles
            isFocusable = false
        }
        row.addView(sw)
        row.setOnClickListener {
            if (sw.isChecked) {
                // turning OFF: simple toggle, no confirmation needed
                Prefs.setPaymentsGate(false)
                sw.isChecked = false
            } else {
                requestGateOn(sw)
            }
        }
        return row
    }

    /**
     * Enable flow: with an existing vault PIN the gate turns on silently; without
     * one the shared two-step PIN setup runs first — cancelling it leaves the
     * switch off (the gate only enables after a PIN is actually persisted).
     */
    private fun requestGateOn(sw: Switch) {
        val act = activity as? FragmentActivity
        if (act == null) { sw.isChecked = Prefs.paymentsGate; return }
        if (Prefs.pinHash.isNotEmpty()) {
            Prefs.setPaymentsGate(true)
            sw.isChecked = true
            return
        }
        VaultGate.setupPinDialog(act) {
            if (Prefs.pinHash.isNotEmpty()) {
                Prefs.setPaymentsGate(true)
                rebuild()          // refresh the row (switch now checked)
            }
        }
    }

    private fun reload() {
        val ctx = context ?: return
        io.launch {
            items = try { AppDb.get(ctx.applicationContext).paymentDao().all().first() } catch (_: Exception) { emptyList() }
            withContext(Dispatchers.Main) { rebuild() }
        }
    }

    private fun rebuild() {
        val ctx = context ?: return
        if (!revealed) return        // v1.7.9: never populate the list behind the lock overlay
        list.removeAllViews()

        // v1.7.9: gate toggle row — part of the revealed content, hidden while locked
        list.addView(gateSwitchRow(ctx))

        // security note card
        list.addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(10)
            }
            background = GradientDrawable().apply {
                cornerRadius = dp(16).toFloat()
                setColor(0x148FD6A0)
            }
            addView(TextView(ctx).apply {
                setText(R.string.pay_security_title)
                textSize = 13f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                setTextColor(PanelUi.themedColor(ctx, R.attr.colorOnSurface))
            })
            addView(TextView(ctx).apply {
                setText(R.string.pay_security_note)
                textSize = 12f
                setPadding(0, dp(4), 0, 0)
                setTextColor(PanelUi.themedColor(ctx, R.attr.colorOnSurfaceVariant))
            })
        })

        if (items.isEmpty()) {
            list.addView(PanelUi.emptyState(ctx, R.drawable.ic_lock,
                R.string.empty_payments_title, R.string.empty_payments_hint))
            return
        }
        for (p in items) {
            list.addView(PanelUi.swipeable(ctx, buildRow(p)) {
                io.launch {
                    try { AppDb.get(ctx.applicationContext).paymentDao().delete(p) } catch (_: Exception) {}
                    withContext(Dispatchers.Main) { reload() }
                }
            })
        }
    }

    private fun buildRow(p: PaymentEntity): LinearLayout {
        val ctx = requireContext()
        val isCard = p.kind == "card"

        // brand badge
        val brand = brandOf(p)
        val badge = LinearLayout(ctx).apply {
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(brand.second)
            }
            layoutParams = LinearLayout.LayoutParams(dp(46), dp(34)).apply { marginEnd = dp(14) }
            addView(TextView(ctx).apply {
                text = brand.first
                textSize = if (brand.first.length > 2) 10f else 14f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                setTextColor(0xFFFFFFFF.toInt())
            })
        }

        val subtitle = if (isCard) {
            val holder = p.holder.ifEmpty { "—" }
            val exp = if (p.expMonth > 0) "%02d/%02d".format(p.expMonth, p.expYear % 100) else ""
            listOfNotNull(holder.ifEmpty { null }, exp.ifEmpty { null }).joinToString(" · ")
        } else getString(R.string.pay_paypal)

        val moreBtn = android.widget.ImageButton(ctx).apply {
            setImageResource(R.drawable.ic_more_vert)
            background = PanelUi.ripple(ctx)
            setPadding(dp(8), dp(8), dp(8), dp(8))
            layoutParams = LinearLayout.LayoutParams(dp(38), dp(38))
            imageTintList = android.content.res.ColorStateList.valueOf(
                PanelUi.themedColor(ctx, R.attr.colorOnSurfaceVariant))
            contentDescription = getString(R.string.menu)
            setOnClickListener { entryMenu(p) }
        }
        return PanelUi.cardRow(ctx, leading = badge, title = p.label, subtitle = subtitle,
            trailing = moreBtn) { entryMenu(p) }
    }

    /** (display name, badge color) by kind + decrypted number prefix. */
    private fun brandOf(p: PaymentEntity): Pair<String, Int> {
        if (p.kind == "paypal") return "P" to 0xFF003087.toInt()
        val num = try { VaultCrypto.decrypt(p.numberEnc) ?: "" } catch (_: Exception) { "" }
        return when {
            num.startsWith("4") -> "VISA" to 0xFF1A1F71.toInt()
            num.length >= 2 && (num.substring(0, 2).toInt() in 51..55 ||
                listOf("2221","2720").any { num.startsWith(it) }) -> "MC" to 0xFFEB001B.toInt()
            num.startsWith("2200") || num.startsWith("2201") || num.startsWith("2202") ||
                num.startsWith("2203") || num.startsWith("2204") -> "МИР" to 0xFF116B36.toInt()
            num.startsWith("34") || num.startsWith("37") -> "AMEX" to 0xFF2E77BC.toInt()
            else -> "•••" to 0xFF3E4A5C.toInt()
        }
    }

    // ------------------------------------------------------------------
    // dialogs
    // ------------------------------------------------------------------

    private fun addCardDialog() {
        val ctx = context ?: return
        // v1.5.0 FIX (bug 5): all fields now use dialogFieldLabeled (FLOATING label +
        // placeholder). The old pattern set `hint = "MM/YY"` on the TextInputEditText
        // AFTER TextInputLayout had wrapped it — the layout could not float that hint,
        // so "MM/YY" rendered ON TOP of the typed text until the field was focused.
        val number = numberField(getString(R.string.pay_number), getString(R.string.pay_ph_number))
        val holder = PanelUi.dialogFieldLabeled(ctx, getString(R.string.pay_holder))
        val exp = PanelUi.dialogFieldLabeled(ctx, getString(R.string.pay_expiry), getString(R.string.pay_ph_mmyy)).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            attachExpiryFormatter(this)
        }
        val cvv = PanelUi.dialogFieldLabeled(ctx, getString(R.string.pay_ph_cvv), getString(R.string.pay_ph_cvv_digits)).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
        }
        val zip = PanelUi.dialogFieldLabeled(ctx, getString(R.string.pay_zip))

        fun inputNumber(): String = number.text.toString().filter { it.isDigit() }
        fun inputExp(): Pair<Int, Int> {
            val digits = exp.text.toString().filter { it.isDigit() }
            return if (digits.length >= 4) digits.take(2).toInt() to digits.substring(2, 4).toInt() else 0 to 0
        }

        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            // v1.5.0: a bit more breathing room top/bottom (was dp(4))
            setPadding(dp(8), dp(8), dp(8), dp(8))
            addView(PanelUi.fieldWrap(number))
            addView(PanelUi.fieldWrap(holder))
            addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    // v1.5.0: gap between the expiry and CVV columns
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                        marginEnd = dp(8)
                    }
                    addView(PanelUi.fieldWrap(exp))
                })
                addView(LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    addView(PanelUi.fieldWrap(cvv))
                })
            })
            addView(PanelUi.fieldWrap(zip))
        }
        MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.pay_add_card)
            .setView(ScrollView(ctx).apply {
                isVerticalScrollBarEnabled = false   // no reserved right inset (same as menu popup)
                addView(box)
            })
            .setPositiveButton(R.string.save) { _, _ ->
                val num = inputNumber()
                val (m, y) = inputExp()
                if (num.length !in 13..19) {
                    Toast.makeText(ctx, R.string.pay_bad_number, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val label = "${brandName(num)} • ${num.takeLast(4)}"
                io.launch {
                    try {
                        AppDb.get(ctx.applicationContext).paymentDao().insert(PaymentEntity(
                            kind = "card",
                            label = label,
                            numberEnc = VaultCrypto.encrypt(num),
                            holder = holder.text.toString().trim(),
                            expMonth = m,
                            expYear = if (y < 100) 2000 + y else y,
                            cvvEnc = cvv.text.toString().trim().takeIf { it.isNotEmpty() }
                                ?.let { VaultCrypto.encrypt(it) } ?: "",
                            billingZip = zip.text.toString().trim()
                        ))
                    } catch (_: Exception) {}
                    withContext(Dispatchers.Main) { reload() }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun addPaypalDialog() {
        val ctx = context ?: return
        val email = PanelUi.dialogFieldLabeled(ctx, getString(R.string.pay_field_email), getString(R.string.pay_ph_email)).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
        }
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
            addView(PanelUi.fieldWrap(email))
        }
        MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.pay_add_paypal)
            .setView(box)
            .setPositiveButton(R.string.save) { _, _ ->
                val mail = email.text.toString().trim()
                if (!mail.contains('@')) {
                    Toast.makeText(ctx, R.string.pay_bad_email, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                io.launch {
                    try {
                        AppDb.get(ctx.applicationContext).paymentDao().insert(PaymentEntity(
                            kind = "paypal",
                            label = "PayPal · $mail",
                            numberEnc = VaultCrypto.encrypt(mail)
                        ))
                    } catch (_: Exception) {}
                    withContext(Dispatchers.Main) { reload() }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** Card-number field: floating label + placeholder + live 4-4-4-4 (Amex 4-6-5) grouping. */
    private fun numberField(label: String, placeholder: String? = null): com.google.android.material.textfield.TextInputEditText =
        PanelUi.dialogFieldLabeled(requireContext(), label, placeholder).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            addTextChangedListener(object : android.text.TextWatcher {
                private var self = false
                override fun beforeTextChanged(p0: CharSequence?, p1: Int, p2: Int, p3: Int) {}
                override fun onTextChanged(p0: CharSequence?, p1: Int, p2: Int, p3: Int) {}
                override fun afterTextChanged(p0: android.text.Editable?) {
                    if (self) return
                    val editable = p0 ?: return
                    self = true
                    val digits = editable.toString().filter { it.isDigit() }.take(19)
                    val grouped = buildString {
                        var count = 0
                        for ((i, ch) in digits.withIndex()) {
                            append(ch)
                            count++
                            // Amex 4-6-5 grouping, others 4-4-4-4
                            val gap = if (digits.length == 15 && (digits.startsWith("34") || digits.startsWith("37"))) {
                                count == 4 || count == 10
                            } else count % 4 == 0
                            if (gap && i < digits.length - 1) append(' ')
                        }
                    }
                    editable.replace(0, editable.length, grouped)
                    setSelection(length())
                    self = false
                }
            })
        }

    /**
     * v1.5.0: live MM/YY formatting for the expiry field — inserts '/' after the second
     * digit as the user types (digits-only, max 4). inputExp() filters digits, so the
     * existing parser keeps working unchanged.
     */
    private fun attachExpiryFormatter(field: EditText) {
        field.addTextChangedListener(object : android.text.TextWatcher {
            private var self = false
            override fun beforeTextChanged(p0: CharSequence?, p1: Int, p2: Int, p3: Int) {}
            override fun onTextChanged(p0: CharSequence?, p1: Int, p2: Int, p3: Int) {}
            override fun afterTextChanged(p0: android.text.Editable?) {
                if (self) return
                val editable = p0 ?: return
                self = true
                val digits = editable.toString().filter { it.isDigit() }.take(4)
                val formatted = if (digits.length > 2) "${digits.take(2)}/${digits.substring(2)}" else digits
                if (formatted != editable.toString()) {
                    editable.replace(0, editable.length, formatted)
                    field.setSelection(field.length())
                }
                self = false
            }
        })
    }

    private fun brandName(num: String): String = when {
        num.startsWith("4") -> "Visa"
        (num.length >= 2 && num.substring(0, 2).toInt() in 51..55) ||
            listOf("2221","2720").any { num.startsWith(it) } -> "Mastercard"
        num.startsWith("2200") || num.startsWith("2201") || num.startsWith("2202") ||
            num.startsWith("2203") || num.startsWith("2204") -> "МИР"
        num.startsWith("34") || num.startsWith("37") -> "Amex"
        else -> "Card"
    }

    // ------------------------------------------------------------------
    // actions
    // ------------------------------------------------------------------

    private fun entryMenu(p: PaymentEntity) {
        val ctx = context ?: return
        val menu = PopupMenu(ctx, list)
        menu.menu.add(0, 1, 0, getString(R.string.pay_copy_number))
        if (p.kind == "card" && p.cvvEnc.isNotEmpty()) menu.menu.add(0, 2, 0, getString(R.string.pay_copy_cvv))
        if (p.kind == "card") menu.menu.add(0, 3, 0, getString(R.string.bm_edit))
        menu.menu.add(0, 4, 0, getString(R.string.delete))
        menu.setOnMenuItemClickListener { mi ->
            when (mi.itemId) {
                1 -> {
                    val num = try { VaultCrypto.decrypt(p.numberEnc) ?: "" } catch (_: Exception) { "" }
                    copyToClipboard(num)
                }
                2 -> {
                    val cvv = try { VaultCrypto.decrypt(p.cvvEnc) ?: "" } catch (_: Exception) { "" }
                    copyToClipboard(cvv, clearAfter = true)
                }
                3 -> editCard(p)
                4 -> io.launch {
                    try { AppDb.get(ctx.applicationContext).paymentDao().delete(p) } catch (_: Exception) {}
                    withContext(Dispatchers.Main) { reload() }
                }
            }
            true
        }
        menu.show()
    }

    private fun editCard(p: PaymentEntity) {
        val ctx = context ?: return
        val num = try { VaultCrypto.decrypt(p.numberEnc) ?: "" } catch (_: Exception) { "" }
        val number = numberField(getString(R.string.pay_number), getString(R.string.pay_ph_number)).apply { setText(num) }
        val holder = PanelUi.dialogFieldLabeled(ctx, getString(R.string.pay_holder), preset = p.holder)
        val exp = PanelUi.dialogFieldLabeled(ctx, getString(R.string.pay_expiry), getString(R.string.pay_ph_mmyy),
            if (p.expMonth > 0) "%02d/%02d".format(p.expMonth, p.expYear % 100) else "").apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            attachExpiryFormatter(this)
        }
        val cvv = PanelUi.dialogFieldLabeled(ctx, getString(R.string.pay_ph_cvv), getString(R.string.pay_ph_cvv_digits)).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
        }
        val zip = PanelUi.dialogFieldLabeled(ctx, getString(R.string.pay_zip), preset = p.billingZip)
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
            addView(PanelUi.fieldWrap(number))
            addView(PanelUi.fieldWrap(holder))
            addView(PanelUi.fieldWrap(exp))
            addView(PanelUi.fieldWrap(cvv))
            addView(PanelUi.fieldWrap(zip))
        }
        MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.bm_edit)
            .setView(ScrollView(ctx).apply {
                isVerticalScrollBarEnabled = false   // no reserved right inset (same as menu popup)
                addView(box)
            })
            .setPositiveButton(R.string.save) { _, _ ->
                val digits = number.text.toString().filter { it.isDigit() }
                val expDigits = exp.text.toString().filter { it.isDigit() }
                val m = if (expDigits.length >= 2) expDigits.take(2).toInt() else p.expMonth
                val y = if (expDigits.length >= 4) expDigits.substring(2, 4).toInt() else p.expYear % 100
                io.launch {
                    try {
                        val dao = AppDb.get(ctx.applicationContext).paymentDao()
                        dao.delete(p)
                        dao.insert(p.copy(
                            label = "${brandName(digits)} • ${digits.takeLast(4)}",
                            numberEnc = VaultCrypto.encrypt(digits),
                            holder = holder.text.toString().trim(),
                            expMonth = m,
                            expYear = if (y < 100) 2000 + y else y,
                            cvvEnc = cvv.text.toString().trim().takeIf { it.isNotEmpty() }
                                ?.let { VaultCrypto.encrypt(it) } ?: p.cvvEnc,
                            billingZip = zip.text.toString().trim()
                        ))
                    } catch (_: Exception) {}
                    withContext(Dispatchers.Main) { reload() }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun copyToClipboard(text: String, clearAfter: Boolean = false) {
        val ctx = context ?: return
        if (text.isEmpty()) return
        try {
            val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("ZephyrPay", text))
            Toast.makeText(ctx, if (clearAfter) R.string.pay_copied_cvv else R.string.copied, Toast.LENGTH_SHORT).show()
            if (clearAfter) {
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    try { cm.clearPrimaryClip() } catch (_: Exception) {}
                }, 30000)
            }
        } catch (_: Exception) {}
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()
}
