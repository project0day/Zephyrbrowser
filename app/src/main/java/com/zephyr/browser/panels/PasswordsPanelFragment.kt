package com.zephyr.browser.panels

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputLayout
import com.zephyr.browser.FaviconLoader
import com.zephyr.browser.MainActivity
import com.zephyr.browser.PasswordGenerator
import com.zephyr.browser.Prefs
import com.zephyr.browser.R
import com.zephyr.browser.VaultCrypto
import com.zephyr.browser.db.AppDb
import com.zephyr.browser.db.PasswordEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * v1.5.0 FULL password vault (user complaint: «во вкладке пароли только создать пароль
 * и всё… а нужна полноценная система с доступом по пину или биометрии»).
 *
 * - VaultSession + VaultGate gate (biometric prompt or PIN, 5 attempts, autolock).
 * - Live search over origin/username, favicon avatars, overflow menus.
 * - Tap a row → detail dialog with reveal-on-demand (decrypt only when the eye
 *   is toggled), copy login / copy password (30 s clipboard auto-clear respected).
 * - Add / edit / delete dialogs with generator chip; standalone generator; CSV export.
 * - «Lock on exit» (vaultAutolock == 0) locks the session in onPause.
 */
class PasswordsPanelFragment : Fragment() {

    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var items: List<PasswordEntity> = emptyList()
    private var query = ""
    private var contentShown = false

    private lateinit var lockView: LinearLayout
    private lateinit var contentRoot: LinearLayout
    private lateinit var search: EditText
    private lateinit var list: LinearLayout

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        val ctx = requireContext()
        // a fresh panel open resets the wrong-PIN counter (5 tries per open)
        VaultSession.failedAttempts = 0
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT)
        }

        // ---- locked placeholder (shown while the vault is locked) ----
        lockView = buildLockView(ctx)
        root.addView(lockView, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        // ---- main content: search + actions, then the list ----
        val content = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        contentRoot = content

        val top = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(6), dp(6), dp(6))
        }
        top.addView(ImageView(ctx).apply {
            setImageResource(R.drawable.ic_search)
            layoutParams = LinearLayout.LayoutParams(dp(18), dp(18)).apply { marginEnd = dp(8) }
            imageTintList = ColorStateList.valueOf(
                PanelUi.themedColor(ctx, R.attr.colorOnSurfaceVariant))
        })
        search = EditText(ctx).apply {
            hint = getString(R.string.vault_search_hint)
            textSize = 14f
            background = PanelUi.searchFieldBg(ctx)
            setSingleLine(true)
            setPadding(dp(12), dp(9), dp(12), dp(9))
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(p0: CharSequence?, p1: Int, p2: Int, p3: Int) {}
                override fun onTextChanged(p0: CharSequence?, p1: Int, p2: Int, p3: Int) {}
                override fun afterTextChanged(p0: Editable?) {
                    query = p0?.toString() ?: ""
                    rebuild()
                }
            })
        }
        top.addView(search, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        fun ib(icon: Int, desc: Int, onClick: () -> Unit) = ImageButton(ctx).apply {
            setImageResource(icon)
            background = PanelUi.ripple(ctx)
            setPadding(dp(9), dp(9), dp(9), dp(9))
            layoutParams = LinearLayout.LayoutParams(dp(42), dp(42))
            imageTintList = ColorStateList.valueOf(
                PanelUi.themedColor(ctx, R.attr.colorOnSurfaceVariant))
            contentDescription = getString(desc)
            setOnClickListener { onClick() }
        }
        top.addView(ib(R.drawable.ic_add, R.string.vault_add_title) { addDialog() })
        top.addView(ib(R.drawable.ic_key, R.string.pwd_generate) { generatorDialog() })
        // v1.7.6 (user: «я кое что даже не вижу — проверка на утечки!»): one tap checks
        // EVERY stored password against HaveIBeenPwned (k-anonymity).
        top.addView(ib(R.drawable.ic_shield, R.string.pwd_check_leaks_all) { scanAllPasswords() })
        top.addView(ib(R.drawable.ic_upload, R.string.pwd_export_csv) { exportCsv() })
        content.addView(top)

        val scroll = ScrollView(ctx)
        list = PanelUi.listColumn(ctx)
        scroll.addView(list)
        content.addView(scroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(content)
        return root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        gate()
    }

    override fun onResume() {
        super.onResume()
        // autolock expiry while the panel was in the background → re-gate
        if (contentShown && !VaultSession.isUnlocked() && Prefs.pinHash.isNotEmpty()) {
            contentShown = false
            items = emptyList()
            search.setText("")
            list.removeAllViews()
            showLocked()
            gate()
        }
    }

    override fun onPause() {
        super.onPause()
        // «Сразу при выходе» mode: lock the session as soon as the panel loses focus
        if (Prefs.vaultAutolock == 0) VaultSession.lock()
    }

    // ------------------------------------------------------------------
    // gate / unlock flow
    // ------------------------------------------------------------------

    private fun gate() {
        val act = activity as? FragmentActivity ?: return
        when {
            Prefs.pinHash.isEmpty() -> offerPinSetup(act)
            VaultSession.isUnlocked() -> showContent()
            // v1.7.2 (user: «нажимаю Отмена — и фон остаётся пустым, логичнее закрыть
            // окно»): cancelling the unlock now CLOSES the whole panel screen.
            else -> VaultGate.showGate(act, finishOnCancel = true) { showContent() }
        }
    }

    /** One-time «Установить PIN?» offer when no PIN exists yet. */
    private fun offerPinSetup(act: FragmentActivity) {
        if (VaultGate.pinOfferDeclined(act)) {
            VaultSession.unlock(VaultGate.sessionSeconds())
            showContent()
            return
        }
        MaterialAlertDialogBuilder(act)
            .setTitle(R.string.vault_offer_title)
            .setMessage(R.string.vault_offer_message)
            .setPositiveButton(R.string.vault_offer_set) { _, _ ->
                VaultGate.setupPinDialog(act) {
                    VaultGate.markPinOfferDone(act)
                    showContent()
                }
            }
            .setNegativeButton(R.string.vault_offer_skip) { _, _ ->
                VaultGate.markPinOfferDone(act)
                VaultSession.unlock(VaultGate.sessionSeconds())
                showContent()
            }
            .setCancelable(false)
            .show()
    }

    private fun showLocked() {
        lockView.visibility = View.VISIBLE
        if (this::contentRoot.isInitialized) contentRoot.visibility = View.GONE
    }

    private fun showContent() {
        contentShown = true
        lockView.visibility = View.GONE
        if (this::contentRoot.isInitialized) contentRoot.visibility = View.VISIBLE
        reload()
    }

    private fun buildLockView(ctx: Context): LinearLayout {
        val v = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(24), dp(24), dp(24))
            visibility = View.GONE
        }
        val chip = PanelUi.iconChip(ctx, R.drawable.ic_lock, 72)
        (chip.layoutParams as LinearLayout.LayoutParams).gravity = Gravity.CENTER_HORIZONTAL
        v.addView(chip)
        v.addView(TextView(ctx).apply {
            setText(R.string.vault_gate_title)
            textSize = 17f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(0, dp(16), 0, dp(4))
            setTextColor(PanelUi.themedColor(ctx, R.attr.colorOnSurface))
        })
        v.addView(TextView(ctx).apply {
            setText(R.string.vault_locked_hint)
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(PanelUi.themedColor(ctx, R.attr.colorOnSurfaceVariant))
        })
        v.addView(bigButton(ctx, getString(R.string.vault_unlock)) { gate() })
        return v
    }

    /** Rounded primary button used by the lock screen and the empty state. */
    private fun bigButton(ctx: Context, text: String, onClick: () -> Unit): TextView =
        TextView(ctx).apply {
            this.text = text
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(0xFFFFFFFF.toInt())
            gravity = Gravity.CENTER
            setPadding(dp(22), dp(12), dp(22), dp(12))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(24).toFloat()
                setColor(PanelUi.themedColor(ctx, R.attr.colorPrimary, 0xFF2E7D32.toInt()))
            }
            isClickable = true
            isFocusable = true
            foreground = PanelUi.ripple(ctx)
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(18)
                gravity = Gravity.CENTER_HORIZONTAL
            }
        }

    // ------------------------------------------------------------------
    // data + list rendering
    // ------------------------------------------------------------------

    private fun reload() {
        val ctx = context ?: return
        io.launch {
            items = try {
                AppDb.get(ctx.applicationContext).passwordDao().allOnce().sortedBy { it.origin }
            } catch (_: Exception) { emptyList() }
            withContext(Dispatchers.Main) { rebuild() }
        }
    }

    private fun rebuild() {
        val ctx = context ?: return
        if (!this::list.isInitialized) return
        list.removeAllViews()
        val q = query.trim()
        val filtered = if (q.isEmpty()) items else items.filter {
            it.origin.contains(q, true) || it.username.contains(q, true)
        }
        if (filtered.isEmpty()) {
            val empty = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(dp(10), dp(36), dp(10), dp(36))
            }
            empty.addView(PanelUi.emptyState(ctx, R.drawable.ic_lock,
                R.string.empty_passwords_title, R.string.empty_passwords_hint))
            empty.addView(bigButton(ctx, getString(R.string.vault_add_first)) { addDialog() })
            list.addView(empty)
            return
        }
        for (p in filtered) list.addView(passwordRow(ctx, p))
    }

    private fun passwordRow(ctx: Context, p: PasswordEntity): LinearLayout {
        val host = hostOf(p.origin)
        val moreBtn = ImageButton(ctx).apply {
            setImageResource(R.drawable.ic_more_vert)
            background = PanelUi.ripple(ctx)
            setPadding(dp(8), dp(8), dp(8), dp(8))
            layoutParams = LinearLayout.LayoutParams(dp(38), dp(38))
            imageTintList = ColorStateList.valueOf(
                PanelUi.themedColor(ctx, R.attr.colorOnSurfaceVariant))
            contentDescription = getString(R.string.menu)
            setOnClickListener { anchor -> entryMenu(anchor, p) }
        }
        return PanelUi.cardRow(ctx,
            leading = faviconChip(ctx, p.origin, host),
            title = host,
            subtitle = p.username,
            trailing = moreBtn) {
            detailDialog(p)
        }
    }

    /** Rounded avatar with the site favicon (letter-avatar fallback is built in). */
    private fun faviconChip(ctx: Context, origin: String, host: String): View {
        val size = dp(42)
        val frame = LinearLayout(ctx).apply {
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(size, size).apply { marginEnd = dp(14) }
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(PanelUi.themedColor(ctx, R.attr.colorSurfaceVariant, 0xFFEFF1F3.toInt()))
            }
            clipToOutline = true
        }
        val iv = ImageView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(size - dp(10), size - dp(10))
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        try { iv.setImageBitmap(FaviconLoader.load(ctx, origin, host)) } catch (_: Exception) {}
        frame.addView(iv)
        return frame
    }

    private fun hostOf(origin: String): String = try {
        Uri.parse(origin).host ?: origin
    } catch (_: Exception) { origin }

    private fun normalizeOrigin(raw: String): String {
        val t = raw.trim()
        return when {
            t.isEmpty() -> t
            t.startsWith("http://") || t.startsWith("https://") -> t
            else -> "https://$t"
        }
    }

    // ------------------------------------------------------------------
    // overflow menu
    // ------------------------------------------------------------------

    private fun entryMenu(anchor: View, p: PasswordEntity) {
        val ctx = context ?: return
        val menu = PopupMenu(ctx, anchor)
        menu.menu.add(0, 1, 0, getString(R.string.pwd_copy_login))
        menu.menu.add(0, 2, 0, getString(R.string.pwd_copy_password))
        menu.menu.add(0, 3, 0, getString(R.string.vault_open_site))
        menu.menu.add(0, 4, 0, getString(R.string.edit))
        menu.menu.add(0, 5, 0, getString(R.string.delete))
        menu.menu.add(0, 6, 0, getString(R.string.pwd_check_leaks))   // v1.7.6: HIBP
        menu.setOnMenuItemClickListener { mi ->
            when (mi.itemId) {
                1 -> copyToClipboard(p.username)
                2 -> copyToClipboard(decrypt(p.passwordEnc))
                3 -> openSite(p)
                4 -> editDialog(p)
                5 -> confirmDelete(p)
                6 -> checkOneDialog(p)
            }
            true
        }
        menu.show()
    }

    // ------------------------------------------------------------------
    // detail dialog (reveal on demand)
    // ------------------------------------------------------------------

    private fun detailDialog(p: PasswordEntity) {
        val ctx = context ?: return
        val host = hostOf(p.origin)

        fun iconButton(icon: Int, desc: Int, onClick: () -> Unit) = ImageButton(ctx).apply {
            setImageResource(icon)
            background = PanelUi.ripple(ctx)
            setPadding(dp(7), dp(7), dp(7), dp(7))
            layoutParams = LinearLayout.LayoutParams(dp(36), dp(36))
            imageTintList = ColorStateList.valueOf(
                PanelUi.themedColor(ctx, R.attr.colorOnSurfaceVariant))
            contentDescription = getString(desc)
            setOnClickListener { onClick() }
        }

        // login row (selectable + copy)
        val loginValue = TextView(ctx).apply {
            text = p.username
            textSize = 15f
            setTextIsSelectable(true)
            setTextColor(PanelUi.themedColor(ctx, R.attr.colorOnSurface))
        }

        // password row: masked, decrypted ONLY when the eye is toggled on
        var revealed = false
        var plain: String? = null
        val passValue = TextView(ctx).apply {
            text = "••••••••"
            textSize = 15f
            typeface = Typeface.MONOSPACE
            setTextColor(PanelUi.themedColor(ctx, R.attr.colorOnSurface))
        }
        val eye = iconButton(R.drawable.ic_visibility, R.string.pwd_show) { }
        eye.setOnClickListener {
            revealed = !revealed
            if (revealed) {
                if (plain == null) plain = decrypt(p.passwordEnc)
                passValue.text = plain
                eye.setImageResource(R.drawable.ic_visibility_off)
            } else {
                passValue.text = "••••••••"
                eye.setImageResource(R.drawable.ic_visibility)
            }
        }
        val passActions = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(eye)
            addView(iconButton(R.drawable.ic_copy, R.string.pwd_copy_password) {
                copyToClipboard(decrypt(p.passwordEnc))
            })
        }

        val siteValue = TextView(ctx).apply {
            text = p.origin
            textSize = 13f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
            setTextColor(PanelUi.themedColor(ctx, R.attr.colorOnSurfaceVariant))
        }

        fun infoRow(iconRes: Int, label: String, valueView: View, trailing: View? = null): LinearLayout {
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(8), 0, dp(8))
            }
            row.addView(ImageView(ctx).apply {
                setImageResource(iconRes)
                layoutParams = LinearLayout.LayoutParams(dp(20), dp(20)).apply { marginEnd = dp(12) }
                imageTintList = ColorStateList.valueOf(
                    PanelUi.themedColor(ctx, R.attr.colorOnSurfaceVariant))
            })
            row.addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                addView(TextView(ctx).apply {
                    text = label
                    textSize = 11.5f
                    letterSpacing = 0.06f
                    setTextColor(PanelUi.themedColor(ctx, R.attr.colorOnSurfaceVariant))
                })
                addView(valueView)
            })
            if (trailing != null) row.addView(trailing)
            return row
        }

        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(4), dp(8), dp(4))
            addView(infoRow(R.drawable.ic_user, getString(R.string.vault_field_login), loginValue,
                iconButton(R.drawable.ic_copy, R.string.pwd_copy_login) { copyToClipboard(p.username) }))
            addView(infoRow(R.drawable.ic_lock, getString(R.string.vault_field_password), passValue, passActions))
            addView(infoRow(R.drawable.ic_globe, getString(R.string.vault_field_site), siteValue))
        }

        MaterialAlertDialogBuilder(ctx)
            .setTitle(host)
            .setView(box)
            .setPositiveButton(R.string.close, null)
            .setNeutralButton(R.string.edit) { _, _ -> editDialog(p) }
            .setNegativeButton(R.string.delete) { _, _ -> confirmDelete(p) }
            .show()
    }

    // ------------------------------------------------------------------
    // add / edit / delete
    // ------------------------------------------------------------------

    private fun addDialog(presetSite: String = "", presetLogin: String = "", presetPass: String = "") {
        val ctx = context ?: return
        val site = PanelUi.dialogFieldLabeled(ctx, getString(R.string.vault_field_site), "example.com", presetSite)
        val login = PanelUi.dialogFieldLabeled(ctx, getString(R.string.vault_field_login), "mail@example.com", presetLogin)
        val pass = PanelUi.dialogFieldLabeled(ctx, getString(R.string.vault_field_password), "••••••••", presetPass).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val passLayout = PanelUi.fieldWrap(pass).apply {
            endIconMode = TextInputLayout.END_ICON_PASSWORD_TOGGLE
        }
        val generateChip = PanelUi.gradientChip(ctx, getString(R.string.vault_generate_short), intArrayOf(
            PanelUi.themedColor(ctx, R.attr.colorPrimary, 0xFF2E7D32.toInt()),
            PanelUi.themedColor(ctx, R.attr.colorPrimaryContainer, 0xFFA5D6A7.toInt())
        )).apply {
            isClickable = true
            isFocusable = true
            foreground = PanelUi.ripple(ctx)
            setOnClickListener {
                pass.setText(PasswordGenerator.generate(16, true, true, true))
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(6)
                gravity = Gravity.END
            }
        }
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(6), dp(4), dp(6), dp(4))
            addView(PanelUi.fieldWrap(site))
            addView(PanelUi.fieldWrap(login))
            addView(passLayout)
            addView(generateChip)
        }

        MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.vault_add_title)
            .setView(ScrollView(ctx).apply { addView(box) })
            .setPositiveButton(R.string.save) { _, _ ->
                val originRaw = site.text.toString().trim()
                val username = login.text.toString().trim()
                val password = pass.text.toString()
                if (originRaw.isEmpty() || username.isEmpty() || password.isEmpty()) {
                    Toast.makeText(ctx, R.string.vault_need_site_login, Toast.LENGTH_SHORT).show()
                    addDialog(originRaw, username, password)
                    return@setPositiveButton
                }
                val origin = normalizeOrigin(originRaw)
                val enc = try { VaultCrypto.encrypt(password) } catch (_: Exception) { "" }
                if (enc.isEmpty()) {
                    Toast.makeText(ctx, R.string.vault_encrypt_error, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                io.launch {
                    try {
                        AppDb.get(ctx.applicationContext).passwordDao()
                            .insert(PasswordEntity(origin = origin, username = username, passwordEnc = enc))
                    } catch (_: Exception) {}
                    withContext(Dispatchers.Main) {
                        Toast.makeText(ctx, R.string.vault_saved, Toast.LENGTH_SHORT).show()
                        reload()
                    }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun editDialog(p: PasswordEntity) {
        val ctx = context ?: return
        val plainPass = decrypt(p.passwordEnc)
        val site = PanelUi.dialogFieldLabeled(ctx, getString(R.string.vault_field_site), "example.com", p.origin)
        val login = PanelUi.dialogFieldLabeled(ctx, getString(R.string.vault_field_login), "mail@example.com", p.username)
        val pass = PanelUi.dialogFieldLabeled(ctx, getString(R.string.vault_field_password), "••••••••", plainPass).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val passLayout = PanelUi.fieldWrap(pass).apply {
            endIconMode = TextInputLayout.END_ICON_PASSWORD_TOGGLE
        }
        val generateChip = PanelUi.gradientChip(ctx, getString(R.string.vault_generate_short), intArrayOf(
            PanelUi.themedColor(ctx, R.attr.colorPrimary, 0xFF2E7D32.toInt()),
            PanelUi.themedColor(ctx, R.attr.colorPrimaryContainer, 0xFFA5D6A7.toInt())
        )).apply {
            isClickable = true
            isFocusable = true
            foreground = PanelUi.ripple(ctx)
            setOnClickListener {
                pass.setText(PasswordGenerator.generate(16, true, true, true))
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(6)
                gravity = Gravity.END
            }
        }
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(6), dp(4), dp(6), dp(4))
            addView(PanelUi.fieldWrap(site))
            addView(PanelUi.fieldWrap(login))
            addView(passLayout)
            addView(generateChip)
        }

        MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.vault_edit_title)
            .setView(ScrollView(ctx).apply { addView(box) })
            .setPositiveButton(R.string.save) { _, _ ->
                val originRaw = site.text.toString().trim()
                val username = login.text.toString().trim()
                val password = pass.text.toString()
                if (originRaw.isEmpty() || username.isEmpty()) {
                    Toast.makeText(ctx, R.string.vault_need_site_login, Toast.LENGTH_SHORT).show()
                    editDialog(p)
                    return@setPositiveButton
                }
                val origin = normalizeOrigin(originRaw)
                // keep the old ciphertext when the password was not changed
                val newEnc = if (password.isEmpty() || password == plainPass) p.passwordEnc
                else try { VaultCrypto.encrypt(password) } catch (_: Exception) { p.passwordEnc }
                io.launch {
                    try {
                        AppDb.get(ctx.applicationContext).passwordDao().update(
                            p.copy(origin = origin, username = username, passwordEnc = newEnc))
                    } catch (_: Exception) {}
                    withContext(Dispatchers.Main) {
                        Toast.makeText(ctx, R.string.vault_saved, Toast.LENGTH_SHORT).show()
                        reload()
                    }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun confirmDelete(p: PasswordEntity) {
        val ctx = context ?: return
        MaterialAlertDialogBuilder(ctx)
            .setMessage(R.string.pwd_delete_confirm)
            .setPositiveButton(R.string.delete) { _, _ ->
                io.launch {
                    try { AppDb.get(ctx.applicationContext).passwordDao().delete(p) } catch (_: Exception) {}
                    withContext(Dispatchers.Main) {
                        Toast.makeText(ctx, R.string.vault_deleted, Toast.LENGTH_SHORT).show()
                        reload()
                    }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun openSite(p: PasswordEntity) {
        val ctx = context ?: return
        val url = p.origin
        try {
            startActivity(Intent(ctx, MainActivity::class.java).apply {
                action = Intent.ACTION_VIEW
                data = Uri.parse(url)
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            })
            activity?.finish()
        } catch (_: Exception) {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            } catch (_: Exception) {
                Toast.makeText(ctx, R.string.app_not_found, Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ------------------------------------------------------------------
    // clipboard (30 s auto-clear, same policy as MainActivity)
    // ------------------------------------------------------------------

    /** Decrypts ONLY where a plaintext value is explicitly requested (copy / reveal / edit). */
    private fun decrypt(enc: String): String = try {
        VaultCrypto.decrypt(enc) ?: ""
    } catch (_: Exception) { "" }

    private fun copyToClipboard(text: String) {
        val ctx = context ?: return
        try {
            val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("Zephyr", text))
            Toast.makeText(ctx, R.string.copied, Toast.LENGTH_SHORT).show()
            if (Prefs.clipboardClear) {
                view?.postDelayed({
                    try { if (cm.primaryClip?.itemCount ?: 0 > 0) cm.clearPrimaryClip() } catch (_: Exception) {}
                }, 30000)
            }
        } catch (_: Exception) {}
    }

    // ------------------------------------------------------------------
    // v1.7.6: breach check — HaveIBeenPwned Pwned Passwords (k-anonymity)
    // ------------------------------------------------------------------

    /** SHA-1(password) → first 5 hex chars are sent, the rest is matched locally:
     *  the password itself NEVER leaves the device. @return occurrence count, or
     *  null on a network/API failure. */
    private suspend fun hibpCount(password: String): Int? =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val sha1 = java.security.MessageDigest.getInstance("SHA-1")
                    .digest(password.toByteArray(Charsets.UTF_8))
                    .joinToString("") { "%02X".format(it) }
                val prefix = sha1.substring(0, 5)
                val suffix = sha1.substring(5)
                val conn = java.net.URL("https://api.pwnedpasswords.com/range/$prefix")
                    .openConnection() as java.net.HttpURLConnection
                conn.connectTimeout = 8000
                conn.readTimeout = 8000
                conn.setRequestProperty("User-Agent", "Zephyr-Browser")   // HIBP requires a UA
                conn.setRequestProperty("Add-Padding", "true")            // anti-timing protection
                try {
                    if (conn.responseCode != 200) return@withContext null
                    val body = conn.inputStream.bufferedReader().readText()
                    for (line in body.lines()) {
                        val parts = line.split(":")
                        if (parts.size >= 2 && parts[0].trim().equals(suffix, ignoreCase = true)) {
                            return@withContext parts[1].trim().toIntOrNull() ?: 0
                        }
                    }
                    0
                } finally {
                    try { conn.disconnect() } catch (_: Exception) {}
                }
            } catch (_: Exception) { null }
        }

    /** Single-password breach dialog (entry menu / generator). */
    private fun checkOneDialog(p: PasswordEntity) {
        val ctx = context ?: return
        val body = TextView(ctx).apply {
            setPadding(dp(8), dp(8), dp(8), dp(8))
            textSize = 14f
            text = getString(R.string.pwd_checking)
            setTextColor(PanelUi.themedColor(ctx, R.attr.colorOnSurface))
        }
        val note = TextView(ctx).apply {
            setPadding(dp(8), dp(4), dp(8), dp(8))
            textSize = 11.5f
            text = getString(R.string.pwd_leak_privacy_note)
            setTextColor(PanelUi.themedColor(ctx, R.attr.colorOnSurfaceVariant))
        }
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            addView(body); addView(note)
        }
        val dlg = MaterialAlertDialogBuilder(ctx)
            .setTitle(hostOf(p.origin))
            .setView(ScrollView(ctx).apply { addView(box) })
            .setPositiveButton(R.string.close, null)
            .show()
        io.launch {
            val count = hibpCount(decrypt(p.passwordEnc))
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                if (!dlg.isShowing) return@withContext
                when (count) {
                    null -> body.text = getString(R.string.pwd_leak_net)
                    0 -> body.text = getString(R.string.pwd_leak_safe)
                    else -> body.text = getString(R.string.pwd_leak_found_fmt, java.text.NumberFormat.getIntegerInstance().format(count))
                }
            }
        }
    }

    /** Scan EVERY stored password (rate-limited) with a progress dialog + summary. */
    private fun scanAllPasswords() {
        val ctx = context ?: return
        val all = items
        if (all.isEmpty()) return
        val cancelled = java.util.concurrent.atomic.AtomicBoolean(false)
        val dlg = MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.pwd_leak_scan_title)
            .setMessage(getString(R.string.pwd_leak_scan_msg, 0, all.size))
            .setPositiveButton(R.string.cancel) { _, _ -> cancelled.set(true) }
            .setCancelable(false)
            .show()
        io.launch {
            val leaked = ArrayList<Pair<PasswordEntity, Int>>()
            var checked = 0
            var netFail = false
            for (p in all) {
                if (cancelled.get()) break
                val count = hibpCount(decrypt(p.passwordEnc))
                if (count == null) netFail = true
                else if (count > 0) leaked.add(p to count)
                checked++
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    if (dlg.isShowing) dlg.setMessage(getString(R.string.pwd_leak_scan_msg, checked, all.size))
                }
                try { kotlinx.coroutines.delay(150) } catch (_: Exception) {}   // HIBP rate politeness
            }
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                try { if (dlg.isShowing) dlg.dismiss() } catch (_: Exception) {}
                if (cancelled.get()) return@withContext
                showScanResults(all.size, checked, leaked, netFail)
            }
        }
    }

    private fun showScanResults(total: Int, checked: Int, leaked: List<Pair<PasswordEntity, Int>>, netFail: Boolean) {
        val ctx = context ?: return
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        val safe = checked - leaked.size
        if (checked < total || netFail) {
            col.addView(TextView(ctx).apply {
                text = getString(R.string.pwd_leak_net)
                textSize = 12.5f
                setPadding(0, 0, 0, dp(8))
                setTextColor(PanelUi.themedColor(ctx, R.attr.colorOnSurfaceVariant))
            })
        }
        col.addView(TextView(ctx).apply {
            text = getString(R.string.pwd_leak_scan_done_fmt, safe, leaked.size)
            textSize = 14.5f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(PanelUi.themedColor(ctx, R.attr.colorOnSurface))
            setPadding(0, 0, 0, dp(8))
        })
        if (leaked.isNotEmpty()) {
            for ((p, count) in leaked.take(30)) {
                col.addView(TextView(ctx).apply {
                    text = "• ${hostOf(p.origin)} · ${p.username} — ${getString(R.string.pwd_leak_found)} $count×"
                    textSize = 13f
                    maxLines = 2
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    setPadding(dp(4), dp(3), dp(4), dp(3))
                    setTextColor(PanelUi.themedColor(ctx, R.attr.colorOnSurfaceVariant))
                })
            }
            col.addView(TextView(ctx).apply {
                text = getString(R.string.pwd_leak_privacy_note)
                textSize = 11.5f
                setPadding(0, dp(8), 0, 0)
                setTextColor(PanelUi.themedColor(ctx, R.attr.colorOnSurfaceVariant))
            })
        }
        MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.pwd_leak_scan_title)
            .setView(ScrollView(ctx).apply { addView(col) })
            .setPositiveButton(R.string.close, null)
            .show()
    }

    // ------------------------------------------------------------------
    // standalone generator (kept from v1.4.x) + CSV export
    // ------------------------------------------------------------------

    private fun generatorDialog() {
        val ctx = context ?: return
        var len = 16
        var upper = true; var digits = true; var special = true
        val preview = TextView(ctx).apply {
            textSize = 16f
            setPadding(dp(8), dp(10), dp(8), dp(10))
            typeface = Typeface.create("monospace", Typeface.NORMAL)
            text = PasswordGenerator.generate(len, upper, digits, special)
        }
        val seek = SeekBar(ctx).apply {
            max = 40; progress = len
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, v: Int, fromUser: Boolean) {
                    len = (v + 8).coerceIn(8, 48)
                    preview.text = PasswordGenerator.generate(len, upper, digits, special)
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
        fun check(label: String, init: Boolean, onChange: (Boolean) -> Unit): TextView {
            val t = TextView(ctx)
            fun render(on: Boolean) { t.text = (if (on) "✓ " else "· ") + label }
            render(init)
            t.textSize = 14f
            t.setPadding(dp(10), dp(10), dp(10), dp(10))
            t.isClickable = true
            t.setOnClickListener {
                val now = !t.text.toString().startsWith("✓")
                onChange(now)
                render(now)
                preview.text = PasswordGenerator.generate(len, upper, digits, special)
            }
            return t
        }
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(6), dp(10), dp(6))
            addView(preview)
            addView(TextView(ctx).apply { text = getString(R.string.pwd_gen_length); textSize = 12f })
            addView(seek)
            addView(check(getString(R.string.pwd_gen_upper), upper) { upper = it })
            addView(check(getString(R.string.pwd_gen_digits), digits) { digits = it })
            addView(check(getString(R.string.pwd_gen_special), special) { special = it })
        }
        MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.pwd_generate)
            .setView(ScrollView(ctx).apply { addView(box) })
            .setPositiveButton(R.string.pwd_copy_password) { _, _ ->
                copyToClipboard(preview.text.toString())
            }
            // v1.7.6: check the generated password against HIBP before using it
            .setNeutralButton(R.string.pwd_check_leaks) { _, _ ->
                checkGenerated(preview.text.toString())
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** Breach dialog for the freshly generated password (generator button). */
    private fun checkGenerated(password: String) {
        val ctx = context ?: return
        val body = TextView(ctx).apply {
            setPadding(dp(8), dp(8), dp(8), dp(4))
            textSize = 14f
            text = getString(R.string.pwd_checking)
            setTextColor(PanelUi.themedColor(ctx, R.attr.colorOnSurface))
        }
        val note = TextView(ctx).apply {
            setPadding(dp(8), dp(4), dp(8), dp(8))
            textSize = 11.5f
            text = getString(R.string.pwd_leak_privacy_note)
            setTextColor(PanelUi.themedColor(ctx, R.attr.colorOnSurfaceVariant))
        }
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL; addView(body); addView(note)
        }
        val dlg = MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.pwd_check_leaks)
            .setView(ScrollView(ctx).apply { addView(box) })
            .setPositiveButton(R.string.close, null)
            .show()
        io.launch {
            val count = hibpCount(password)
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                if (!dlg.isShowing) return@withContext
                when (count) {
                    null -> body.text = getString(R.string.pwd_leak_net)
                    0 -> body.text = getString(R.string.pwd_leak_safe)
                    else -> body.text = getString(R.string.pwd_leak_found_fmt, java.text.NumberFormat.getIntegerInstance().format(count))
                }
            }
        }
    }

    private fun exportCsv() {
        val ctx = context ?: return
        fun csv(s: String): String = "\"" + s.replace("\"", "\"\"") + "\""

        io.launch {
            val all = try { AppDb.get(ctx.applicationContext).passwordDao().allOnce() } catch (_: Exception) { emptyList() }
            val sb = StringBuilder("origin,username,password\n")
            for (p in all) {
                val pass = try { VaultCrypto.decrypt(p.passwordEnc) ?: "" } catch (_: Exception) { "" }
                sb.append(csv(p.origin)).append(',').append(csv(p.username)).append(',').append(csv(pass)).append('\n')
            }
            val dir = File(ctx.getExternalFilesDir(android.os.Environment.DIRECTORY_DOCUMENTS) ?: ctx.filesDir, "export").apply { mkdirs() }
            val f = File(dir, "zephyr-passwords.csv")
            try {
                f.writeText(sb.toString())
                withContext(Dispatchers.Main) {
                    Toast.makeText(ctx, getString(R.string.saved) + ": " + f.name, Toast.LENGTH_LONG).show()
                }
            } catch (_: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(ctx, R.string.vault_export_error, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()
}
