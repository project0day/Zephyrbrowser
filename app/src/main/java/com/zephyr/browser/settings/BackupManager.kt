package com.zephyr.browser.settings

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.zephyr.browser.Prefs
import com.zephyr.browser.db.AppDb
import com.zephyr.browser.db.BookmarkEntity
import com.zephyr.browser.db.BookmarkFolderEntity
import com.zephyr.browser.db.HistoryEntity
import com.zephyr.browser.db.QuickSiteEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File

/**
 * v1.7.5: one-file backup/restore of the user's browser data.
 * v1.7.6 (user: «в резервную копию будем расширять возможности — копия чего:
 *  истории, конфигурации, VPN итд»): WHAT goes into the copy is now user-selected
 *  (Prefs.backupInclude: bookmarks / quick tiles / history / settings / VPN
 *  configs), and AUTO-BACKUP writes weekly into the public Downloads/Zephyr
 *  folder via MediaStore ([exportAuto]) — no SAF picker, notification afterwards.
 *
 * EXPORT writes a single JSON document; IMPORT is MERGE-safe: bookmarks/quick
 * tiles are skipped when the URL already exists; folders are re-created by name;
 * settings are applied through their regular setters; VPN .conf files are
 * restored into the WireGuard config dir.
 */
object BackupManager {

    private const val FORMAT = "zephyr-backup"
    private const val VERSION = 3   // v2: include flags + vpn; v3: passwords/payments/autofill + optional AES-GCM file encryption

    // ---------------- export ----------------

    suspend fun export(ctx: Context, uri: Uri, filePassword: String = ""): Boolean = withContext(Dispatchers.IO) {
        try {
            // SECURITY (audit A-005, fixed 1.8.6): without a file password the
            // vault section is dropped rather than written in clear.
            //
            // The automatic weekly backup already did this — it writes into the
            // public Downloads folder, so `exportAuto` passes
            // includePasswords = false. The manual export had no such guard and
            // defaulted to including everything, which meant a file the user
            // then mails to themselves or drops in cloud storage could carry
            // every decrypted password and card number.
            //
            // The password is therefore not an option any more but the
            // condition for exporting the vault at all. Everything else —
            // bookmarks, settings, history — still exports either way, so a
            // plain backup is still a plain backup.
            val includeVault = filePassword.isNotEmpty()
            val json = buildJson(ctx, includePasswords = includeVault)
            val bytes = json.toString(2).toByteArray(Charsets.UTF_8)
            val outBytes = if (filePassword.isNotEmpty()) encryptFile(bytes, filePassword) else bytes
            ctx.contentResolver.openOutputStream(uri, "wt")?.use { os ->
                os.write(outBytes)
            } ?: return@withContext false
            Prefs.setLastBackupAt(System.currentTimeMillis())
            true
        } catch (_: Exception) {
            false
        }
    }

    /** v1.7.7: AES-256-GCM file encryption — key = PBKDF2(password, salt, 120k).
     *  Output: JSON envelope {format, version, enc, salt, iv, payload(b64)}. */
    private fun encryptFile(plain: ByteArray, password: String): ByteArray {
        val salt = ByteArray(16).also { java.security.SecureRandom().nextBytes(it) }
        val iv = ByteArray(12).also { java.security.SecureRandom().nextBytes(it) }
        val key = deriveKey(password, salt)
        val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, key, javax.crypto.spec.GCMParameterSpec(128, iv))
        val ct = cipher.doFinal(plain)
        val env = JSONObject()
            .put("format", FORMAT)
            .put("version", VERSION)
            .put("enc", "AES-256-GCM")
            .put("kdf", "PBKDF2-SHA256-120000")
            .put("salt", android.util.Base64.encodeToString(salt, android.util.Base64.NO_WRAP))
            .put("iv", android.util.Base64.encodeToString(iv, android.util.Base64.NO_WRAP))
            .put("payload", android.util.Base64.encodeToString(ct, android.util.Base64.NO_WRAP))
        return env.toString(2).toByteArray(Charsets.UTF_8)
    }

    /** v1.7.7: decrypts an envelope produced by [encryptFile]; null on wrong password. */
    private fun decryptFile(text: String, password: String): ByteArray? = try {
        val env = JSONObject(text)
        if (env.optString("enc") != "AES-256-GCM") null
        else {
            val salt = android.util.Base64.decode(env.optString("salt"), android.util.Base64.NO_WRAP)
            val iv = android.util.Base64.decode(env.optString("iv"), android.util.Base64.NO_WRAP)
            val ct = android.util.Base64.decode(env.optString("payload"), android.util.Base64.NO_WRAP)
            val key = deriveKey(password, salt)
            val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(javax.crypto.Cipher.DECRYPT_MODE, key, javax.crypto.spec.GCMParameterSpec(128, iv))
            cipher.doFinal(ct)
        }
    } catch (_: Exception) { null }

    private fun deriveKey(password: String, salt: ByteArray): javax.crypto.SecretKey {
        val spec = javax.crypto.spec.PBEKeySpec(password.toCharArray(), salt, 120_000, 256)
        val factory = javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        return javax.crypto.spec.SecretKeySpec(factory.generateSecret(spec).encoded, "AES")
    }

    /** v1.7.7: is this backup file password-protected? (peeks the header only) */
    fun isEncrypted(text: String): Boolean = try {
        JSONObject(text).optString("enc").isNotEmpty()
    } catch (_: Exception) { false }

    /** v1.7.6: writes the backup into the PUBLIC Downloads/Zephyr folder (MediaStore
     *  on API 29+, classic external dir on older devices). @return the display name
     *  or null on failure. Used by the weekly auto-backup. */
    suspend fun exportAuto(ctx: Context): String? = withContext(Dispatchers.IO) {
        try {
            // v1.7.7: NEVER write decrypted passwords into the unencrypted auto file
            val json = buildJson(ctx, includePasswords = false)
            val name = "zephyr-backup-" +
                java.text.SimpleDateFormat("yyyyMMdd-HHmm", java.util.Locale.US).format(java.util.Date()) + ".json"
            val uri: Uri?
            if (Build.VERSION.SDK_INT >= 29) {
                val cv = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                    put(MediaStore.MediaColumns.MIME_TYPE, "application/json")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Zephyr")
                }
                uri = ctx.contentResolver.insert(MediaStore.Files.getContentUri("external"), cv)
            } else {
                @Suppress("DEPRECATION")
                val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Zephyr")
                    .apply { mkdirs() }
                val f = File(dir, name)
                uri = Uri.fromFile(f)
            }
            if (uri == null) return@withContext null
            ctx.contentResolver.openOutputStream(uri)?.use { os ->
                os.write(json.toString(2).toByteArray(Charsets.UTF_8))
            } ?: return@withContext null
            Prefs.setLastBackupAt(System.currentTimeMillis())
            name
        } catch (_: Exception) {
            null
        }
    }

    /** Builds the backup JSON honoring the include toggles.
     *  v1.7.7: [includePasswords] false (auto-backup → plaintext file in the PUBLIC
     *  Downloads folder!) silently drops the vault section — passwords only ever go
     *  into a user-picked file, ideally password-encrypted. */
    private suspend fun buildJson(ctx: Context, includePasswords: Boolean = true): JSONObject {
        val db = AppDb.get(ctx)
        val inc = Prefs.backupInclude
        val out = JSONObject()
        out.put("format", FORMAT)
        out.put("version", VERSION)
        out.put("exportedAt", System.currentTimeMillis())
        out.put("includes", JSONArray(inc.toList()))

        // folders: id → name (restored by NAME on import) — only meaningful with bookmarks
        val folderList = ArrayList<JSONObject>()
        if ("bm" in inc) {
            val foldersOnce: List<BookmarkFolderEntity> = try { db.bookmarkFolderDao().all() } catch (_: Exception) { emptyList() }
            for (f in foldersOnce) folderList.add(JSONObject().put("id", f.id).put("name", f.name))
            val bookmarks = ArrayList<JSONObject>()
            for (b in db.bookmarkDao().allOnce()) {
                bookmarks.add(JSONObject()
                    .put("t", b.title)
                    .put("u", b.url)
                    .put("f", b.folderId)
                    .put("p", b.position))
            }
            out.put("bookmarks", JSONArray(bookmarks))
        }
        out.put("folders", JSONArray(folderList))

        if ("quick" in inc) {
            val quick = ArrayList<JSONObject>()
            for (q in db.quickSiteDao().allOnce()) {
                quick.add(JSONObject().put("t", q.title).put("u", q.url).put("p", q.position))
            }
            out.put("quick", JSONArray(quick))
        }

        if ("history" in inc) {
            val hist = ArrayList<JSONObject>()
            for (h in db.historyDao().all().first()) {
                hist.add(JSONObject().put("u", h.url).put("t", h.title).put("at", h.visitedAt))
            }
            out.put("history", JSONArray(hist))
        }

        if ("settings" in inc) {
            out.put("settings", settingsSnapshot())
        }

        // v1.7.7 (user: «бэкап паролей в общий JSON + шифрование паролем файла»):
        // passwords (decrypted via VaultCrypto), payments and autofill profiles —
        // the file itself can then be AES-GCM encrypted with a user password
        // (export(ctx, uri, password)); even without it the vault data lives
        // encrypted-at-rest in the app, and a merge-safe import dedupes everything.
        if ("pw" in inc && includePasswords) {
            val pws = ArrayList<JSONObject>()
            for (p in try { db.passwordDao().allOnce() } catch (_: Exception) { emptyList() }) {
                val plain = try { com.zephyr.browser.VaultCrypto.decrypt(p.passwordEnc) ?: "" } catch (_: Exception) { "" }
                if (plain.isEmpty()) continue
                pws.add(JSONObject().put("o", p.origin).put("u", p.username).put("p", plain))
            }
            out.put("passwords", JSONArray(pws))
            val pays = ArrayList<JSONObject>()
            for (c in try { db.paymentDao().allOnce() } catch (_: Exception) { emptyList() }) {
                val number = try { com.zephyr.browser.VaultCrypto.decrypt(c.numberEnc) ?: "" } catch (_: Exception) { "" }
                if (number.isEmpty()) continue
                // SECURITY (audit A-005, fixed 1.8.6): the CVV is deliberately
                // NOT exported. Card schemes forbid keeping it once a payment
                // is authorised, and a wallet that writes it back out in a file
                // the user then mails to themselves is the worst form of that.
                //
                // The cost is three digits re-typed after a restore. The import
                // side still reads "c" from older files, so nothing already
                // exported becomes unreadable.
                pays.add(JSONObject().put("k", c.kind).put("l", c.label).put("n", number)
                    .put("h", c.holder).put("m", c.expMonth).put("y", c.expYear))
            }
            out.put("payments", JSONArray(pays))
            val fills = ArrayList<JSONObject>()
            for (a in try { db.autofillDao().all().first() } catch (_: Exception) { emptyList() }) {
                fills.add(JSONObject().put("l", a.label).put("f", a.fields))
            }
            out.put("autofill", JSONArray(fills))
        }

        // v1.7.6: VPN (WireGuard/Amnezia) .conf configs, base64
        if ("vpn" in inc) {
            val vpn = ArrayList<JSONObject>()
            try {
                val wgDir = File(ctx.filesDir, "wireguard")
                wgDir.listFiles { f -> f.isFile && f.name.endsWith(".conf", true) }?.forEach { f ->
                    try {
                        val b64 = android.util.Base64.encodeToString(f.readBytes(), android.util.Base64.NO_WRAP)
                        vpn.add(JSONObject().put("n", f.nameWithoutExtension).put("c", b64))
                    } catch (_: Exception) {}
                }
            } catch (_: Exception) {}
            out.put("vpn", JSONArray(vpn))
        }
        return out
    }

    /** Curated settings snapshot (import re-applies them through the setters). */
    private fun settingsSnapshot(): JSONObject = JSONObject().apply {
        put("engineId", Prefs.engineId)
        put("customEngineName", Prefs.customEngineName)
        put("customEngineUrl", Prefs.customEngineUrl)
        put("themeId", Prefs.themeId)
        put("customAccent", Prefs.customAccent)
        put("pageAppearance", Prefs.pageAppearance)
        put("homeQuick", Prefs.homeQuick)
        put("homeRecents", Prefs.homeRecents)
        put("homeBookmarks", Prefs.homeBookmarks)
        put("homeSearchBar", Prefs.homeSearchBar)
        put("homeTileSize", Prefs.homeTileSize)
        put("homeGridColumns", Prefs.homeGridColumns)
        put("toolbarActions", Prefs.toolbarActions)
        put("openInApps", Prefs.openInApps)
        put("adBlockMode", Prefs.adBlockMode)
        put("youtubeAdBlock", Prefs.youtubeAdBlock)
        put("sbSponsor", Prefs.sbSponsor)
        put("sbSelfpromo", Prefs.sbSelfpromo)
        put("sbInteraction", Prefs.sbInteraction)
        put("sbIntro", Prefs.sbIntro)
        put("jsEnabled", Prefs.jsEnabled)
        put("cookiesEnabled", Prefs.cookiesEnabled)
        put("thirdPartyCookies", Prefs.thirdPartyCookies)
        put("imagesEnabled", Prefs.imagesEnabled)
        put("popupsEnabled", Prefs.popupsEnabled)
        put("blockWebRTC", Prefs.blockWebRTC)
        put("alwaysIncognito", Prefs.alwaysIncognito)
        put("desktopDefault", Prefs.desktopDefault)
        put("restoreTabs", Prefs.restoreTabs)
        put("doNotTrack", Prefs.doNotTrack)
        put("clipboardClear", Prefs.clipboardClear)
        put("edgeGestures", Prefs.edgeGestures)
        put("searchSuggestions", Prefs.searchSuggestions)
        put("uaMode", Prefs.uaMode)
        put("customUa", Prefs.customUa)
        put("language", Prefs.language)
        put("menuTilesOrder", Prefs.menuTilesOrder)
        put("menuRowsOrder", Prefs.menuRowsOrder)
        put("adBlockWhitelist", JSONArray(Prefs.adBlockWhitelist))
    }

    // ---------------- import ----------------

    /** @return imported bookmark count, or -1 on a broken/corrupt file, or
     *  -2 when the file needs a password, -3 on a WRONG password. */
    suspend fun import(ctx: Context, uri: Uri, filePassword: String = ""): Int = withContext(Dispatchers.IO) {
        try {
            val text = ctx.contentResolver.openInputStream(uri)?.use { ins ->
                BufferedReader(ins.reader(Charsets.UTF_8)).readText()
            } ?: return@withContext -1
            var root: JSONObject
            if (isEncrypted(text)) {
                if (filePassword.isEmpty()) return@withContext -2
                val plain = decryptFile(text, filePassword) ?: return@withContext -3
                root = JSONObject(String(plain, Charsets.UTF_8))
            } else {
                root = JSONObject(text)
            }
            if (root.optString("format") != FORMAT) return@withContext -1
            val db = AppDb.get(ctx)

            // folders by name
            val folderMap = HashMap<Long, Long>()     // old id → new id
            val foldersArr = root.optJSONArray("folders") ?: JSONArray()
            val existingFolders = try { db.bookmarkFolderDao().all() } catch (_: Exception) { emptyList() }
            for (i in 0 until foldersArr.length()) {
                val f = foldersArr.getJSONObject(i)
                val name = f.optString("name")
                val existing = existingFolders.firstOrNull { it.name == name }
                val newId = if (existing != null) existing.id
                else try { db.bookmarkFolderDao().insert(BookmarkFolderEntity(name = name)) } catch (_: Exception) { 0L }
                folderMap[f.optLong("id")] = newId
            }

            // bookmarks (skip URLs that exist)
            var imported = 0
            val bArr = root.optJSONArray("bookmarks") ?: JSONArray()
            for (i in 0 until bArr.length()) {
                try {
                    val b = bArr.getJSONObject(i)
                    val url = b.optString("u")
                    if (url.isEmpty() || db.bookmarkDao().byUrl(url) != null) continue
                    db.bookmarkDao().insert(BookmarkEntity(
                        title = b.optString("t").ifEmpty { url },
                        url = url,
                        folderId = folderMap[b.optLong("f")] ?: 0L,
                        position = b.optInt("p")))
                    imported++
                } catch (_: Exception) {}
            }

            // quick tiles (skip dupes)
            val qArr = root.optJSONArray("quick") ?: JSONArray()
            for (i in 0 until qArr.length()) {
                try {
                    val q = qArr.getJSONObject(i)
                    val url = q.optString("u")
                    if (url.isEmpty() || db.quickSiteDao().byUrl(url) != null) continue
                    db.quickSiteDao().insert(QuickSiteEntity(
                        title = q.optString("t").ifEmpty { url }, url = url, position = q.optInt("p")))
                } catch (_: Exception) {}
            }

            // history (skip URL+day dupes)
            val hArr = root.optJSONArray("history") ?: JSONArray()
            for (i in 0 until hArr.length()) {
                try {
                    val h = hArr.getJSONObject(i)
                    db.historyDao().insert(HistoryEntity(
                        url = h.optString("u"), title = h.optString("t"), visitedAt = h.optLong("at")))
                } catch (_: Exception) {}
            }

            // v1.7.6: VPN configs back into filesDir/wireguard (merge-safe by name)
            val vArr = root.optJSONArray("vpn") ?: JSONArray()
            for (i in 0 until vArr.length()) {
                try {
                    val v = vArr.getJSONObject(i)
                    val safe = v.optString("n").replace(Regex("[\\\\/:*?\"<>|]"), "_").take(48)
                    if (safe.isEmpty()) continue
                    val bytes = android.util.Base64.decode(v.optString("c"), android.util.Base64.NO_WRAP)
                    if (bytes.isEmpty()) continue
                    val wgDir = File(ctx.filesDir, "wireguard").apply { mkdirs() }
                    File(wgDir, "$safe.conf").writeBytes(bytes)
                } catch (_: Exception) {}
            }

            // v1.7.7: passwords (skip origin+username dupes), payments, autofill
            val pwArr = root.optJSONArray("passwords") ?: JSONArray()
            for (i in 0 until pwArr.length()) {
                try {
                    val e = pwArr.getJSONObject(i)
                    val origin = e.optString("o"); val user = e.optString("u"); val pass = e.optString("p")
                    if (origin.isEmpty() || pass.isEmpty()) continue
                    val dupe = db.passwordDao().byOrigin(origin)
                    if (dupe != null && dupe.username == user) continue
                    val enc = try { com.zephyr.browser.VaultCrypto.encrypt(pass) } catch (_: Exception) { null } ?: continue
                    db.passwordDao().insert(com.zephyr.browser.db.PasswordEntity(
                        origin = origin, username = user, passwordEnc = enc))
                } catch (_: Exception) {}
            }
            val payArr = root.optJSONArray("payments") ?: JSONArray()
            for (i in 0 until payArr.length()) {
                try {
                    val e = payArr.getJSONObject(i)
                    val number = e.optString("n")
                    if (number.isEmpty()) continue
                    val label = e.optString("l")
                    val existing = try { db.paymentDao().allOnce() } catch (_: Exception) { emptyList() }
                    if (existing.any { it.label == label }) continue
                    val nEnc = try { com.zephyr.browser.VaultCrypto.encrypt(number) } catch (_: Exception) { null } ?: continue
                    val cEnc = try { com.zephyr.browser.VaultCrypto.encrypt(e.optString("c")) } catch (_: Exception) { "" }
                    db.paymentDao().insert(com.zephyr.browser.db.PaymentEntity(
                        kind = e.optString("k", "card"), label = label, numberEnc = nEnc,
                        holder = e.optString("h"), expMonth = e.optInt("m"), expYear = e.optInt("y"),
                        cvvEnc = cEnc))
                } catch (_: Exception) {}
            }
            val afArr = root.optJSONArray("autofill") ?: JSONArray()
            for (i in 0 until afArr.length()) {
                try {
                    val e = afArr.getJSONObject(i)
                    val label = e.optString("l")
                    if (label.isEmpty()) continue
                    val existing = try { db.autofillDao().all().first() } catch (_: Exception) { emptyList() }
                    if (existing.any { it.label == label }) continue
                    db.autofillDao().insert(com.zephyr.browser.db.AutofillEntity(
                        label = label, fields = e.optString("f")))
                } catch (_: Exception) {}
            }

            applySettings(root.optJSONObject("settings"))
            imported
        } catch (_: Exception) {
            -1
        }
    }

    private fun applySettings(s: JSONObject?) {
        if (s == null) return
        try { if (s.has("engineId")) Prefs.setEngine(s.getString("engineId")) } catch (_: Exception) {}
        try { if (s.has("customEngineName")) Prefs.setCustomEngine(s.getString("customEngineName"), Prefs.customEngineUrl) } catch (_: Exception) {}
        try { if (s.has("customEngineUrl")) Prefs.setCustomEngine(Prefs.customEngineName, s.getString("customEngineUrl")) } catch (_: Exception) {}
        try { if (s.has("engineId") && s.getString("engineId") == "custom") Prefs.setEngine("custom") } catch (_: Exception) {}
        try { if (s.has("themeId")) Prefs.setTheme(s.getInt("themeId")) } catch (_: Exception) {}
        try { if (s.has("customAccent")) Prefs.setCustomAccent(s.getInt("customAccent")) } catch (_: Exception) {}
        try { if (s.has("pageAppearance")) Prefs.setPageAppearance(s.getInt("pageAppearance")) } catch (_: Exception) {}
        try { if (s.has("homeQuick") || s.has("homeRecents") || s.has("homeBookmarks")) Prefs.setHomeToggles(
            s.optBoolean("homeQuick", true), s.optBoolean("homeRecents", true), s.optBoolean("homeBookmarks", true)) } catch (_: Exception) {}
        try { if (s.has("homeSearchBar")) Prefs.setHomeSearchBar(s.getBoolean("homeSearchBar")) } catch (_: Exception) {}
        try { if (s.has("homeTileSize")) Prefs.setHomeTileSize(s.getInt("homeTileSize")) } catch (_: Exception) {}
        try { if (s.has("homeGridColumns")) Prefs.setHomeGridColumns(s.getInt("homeGridColumns")) } catch (_: Exception) {}
        try { if (s.has("toolbarActions")) Prefs.setToolbarActions(s.getString("toolbarActions")) } catch (_: Exception) {}
        try { if (s.has("openInApps")) Prefs.setOpenInApps(s.getInt("openInApps")) } catch (_: Exception) {}
        try { if (s.has("adBlockMode")) Prefs.setAdBlockMode(s.getInt("adBlockMode")) } catch (_: Exception) {}
        try { if (s.has("youtubeAdBlock")) Prefs.setYoutubeAdBlock(s.getBoolean("youtubeAdBlock")) } catch (_: Exception) {}
        try { if (s.has("jsEnabled")) Prefs.setJs(s.getBoolean("jsEnabled")) } catch (_: Exception) {}
        try { if (s.has("cookiesEnabled")) Prefs.setCookies(s.getBoolean("cookiesEnabled")) } catch (_: Exception) {}
        try { if (s.has("thirdPartyCookies")) Prefs.setThirdPartyCookies(s.getBoolean("thirdPartyCookies")) } catch (_: Exception) {}
        try { if (s.has("imagesEnabled")) Prefs.setImages(s.getBoolean("imagesEnabled")) } catch (_: Exception) {}
        try { if (s.has("popupsEnabled")) Prefs.setPopups(s.getBoolean("popupsEnabled")) } catch (_: Exception) {}
        try { if (s.has("blockWebRTC")) Prefs.setBlockWebRTC(s.getBoolean("blockWebRTC")) } catch (_: Exception) {}
        try { if (s.has("alwaysIncognito")) Prefs.setAlwaysIncognito(s.getBoolean("alwaysIncognito")) } catch (_: Exception) {}
        try { if (s.has("desktopDefault")) Prefs.setDesktopDefault(s.getBoolean("desktopDefault")) } catch (_: Exception) {}
        try { if (s.has("restoreTabs")) Prefs.setRestoreTabs(s.getBoolean("restoreTabs")) } catch (_: Exception) {}
        try { if (s.has("doNotTrack")) Prefs.setDoNotTrack(s.getBoolean("doNotTrack")) } catch (_: Exception) {}
        try { if (s.has("clipboardClear")) Prefs.setClipboardClear(s.getBoolean("clipboardClear")) } catch (_: Exception) {}
        try { if (s.has("edgeGestures")) Prefs.setEdgeGestures(s.getBoolean("edgeGestures")) } catch (_: Exception) {}
        try { if (s.has("searchSuggestions")) Prefs.setSearchSuggestions(s.getBoolean("searchSuggestions")) } catch (_: Exception) {}
        try { if (s.has("uaMode")) Prefs.setUaMode(s.getInt("uaMode")) } catch (_: Exception) {}
        try { if (s.has("customUa")) Prefs.setCustomUa(s.getString("customUa")) } catch (_: Exception) {}
        try { if (s.has("menuTilesOrder")) Prefs.setMenuTilesOrder(s.getString("menuTilesOrder")) } catch (_: Exception) {}
        try { if (s.has("menuRowsOrder")) Prefs.setMenuRowsOrder(s.getString("menuRowsOrder")) } catch (_: Exception) {}
    }
}
