# Zephyr Browser — security audit

Started 22 September 2026. Separate from `FINDINGS.md`, which records defects:
this file records what an attacker could do, and what now stops them.

Severity here is about consequence, not effort:

- `A1` — a remote page can steal data or act as the user
- `A2` — requires a mistake by the user, or local access, to exploit
- `A3` — hardening; no known path to exploit, but the guard belongs there

---

## Scope covered

- [x] The JavaScript bridge exposed to every page (`ZephyrBridge`)
- [x] WebView security settings (file access, cross-origin, popups)
- [x] Exported manifest components
- [x] Credential storage at rest (`VaultCrypto`)
- [x] Assistant file tools — path traversal, workspace escape
- [x] `run_js` and user scripts
- [x] Backup export contents and encryption
- [x] Cleartext traffic policy

---

## Findings

### A-001 · A1 · `FIXED` 1.8.1 — any web page could steal a saved password

**The hole.** `ZephyrBridge.onLoginFocus(domain)` is callable by any script on
any page — that is what `addJavascriptInterface` means. It passed its argument
to `offerLoginAutofill(domain)`, which looked a credential up with it whenever
the current origin had none of its own. The query behind that lookup is:

```sql
SELECT * FROM passwords WHERE origin LIKE '%' || :domain || '%'
```

Wildcarded on both sides, and the caller chooses `:domain`.

**What that allows.** A page runs one line:

```js
Zephyr.onLoginFocus("a")
```

`'%a%'` matches nearly every saved entry; `.firstOrNull()` returns one. The
browser then shows its own autofill dialog, naming a site the attacker chose —
the displayed name came from the same untrusted string — and "Fill" types the
password into a form on the attacker's page.

The device-lock gate does not help. The user unlocks precisely because the
dialog looks like their browser asking a reasonable question.

**Fix.** The parameter no longer takes part in the lookup. A credential is
found by the origin of the page actually loaded in the tab, which a script
cannot influence; a sibling host still matches, but only after
`Urls.domainOf()` on the *stored* origin equals the domain of the *loaded*
page. The dialog names the real domain.

**Note for the rest of the bridge.** `onLoginSubmitted` was already correct —
it takes the origin from `tab.pageUrlSnapshot` and ignores anything the script
says. That contrast is the lesson: one method trusted the page and one did not,
in the same class, and only the reviewed one was safe.

### A-006 · A1 · `FIXED` 1.8.5 — the assistant ran arbitrary JavaScript without asking

`run_js` hands the model's code straight to the open page. It was the only page
tool that never asked permission — `page_click` confirms before a tap,
`page_fill` before typing a value, and the tool that can do everything those two
can plus read the DOM of a logged-in session, reach `document.cookie` and POST
it anywhere, did not.

This is A1 rather than a misjudged default, because **the model's input is not
trusted**. The assistant reads the text of the open page, and a page can carry
instructions written at the model rather than at the reader. Prompt injection
therefore had a direct line to script execution inside the user's own session —
on their bank, their mail, whatever tab was open.

`run_js` now confirms like its weaker siblings, showing the code it is about to
run. It still honours "do not ask for confirmation" for users who set that
deliberately.

### A-005 · A2 · `FIXED` 1.8.6 — a manual backup wrote passwords and CVVs in clear

`BackupManager.buildJson` decrypted every saved password, card number and
**CVV** into the export, and the file password that encrypts the result was
optional. A backup the user then mails to themselves or leaves in cloud storage
could therefore carry the whole vault in clear.

The automatic weekly backup was already correct, and clearly thought about:
`exportAuto` passes `includePasswords = false` precisely because it writes into
the public Downloads folder. The manual export had no such guard.

**Fixed in two parts.**

1. **The file password is now the condition for exporting the vault**, not an
   option beside it. Leave it empty and the backup still contains bookmarks,
   settings and history — but no passwords or cards. The dialog says so, because
   a section vanishing silently would be its own bug.
2. **The CVV is no longer exported at all.** Card schemes forbid keeping it once
   a payment is authorised, and a wallet that writes it back out into a file is
   the worst form of that. Import still reads the old field, so files exported
   before this release stay readable; the cost is three digits re-typed after a
   restore.

### A-004 · A2 · `FIXED` 1.8.6 — one transient keystore error could orphan every password

`VaultCrypto.encrypt` falls back through three stages. Stage two used to call
`deleteKeystoreAlias()` and retry with a fresh key.

That is unrecoverable for existing data. Every password and card already stored
was encrypted under the old key; deleting the alias destroys it, and no later
decrypt can succeed — `decrypt()` tries the other backend, but the keystore key
is simply gone.

The trigger need not be corruption. A keystore can throw transiently — shortly
after the screen lock is changed, or while the device is still unlocking — and a
single such failure during one save would have discarded the whole vault.

Not a disclosure bug; a data-loss one, and the more dangerous for being silent:
nothing would have told the user until the next time they needed a password.

**Fix.** Nothing regenerates the alias any more. A failed write falls through to
the file-backed key, which stores that record safely while every existing row
stays readable by the keystore key that is still there. `deleteKeystoreAlias`
was removed outright rather than left unused — a dead helper named "recovery" is
an invitation to call it again, and the recovery it offered *was* the loss.

### A-002 · A2 · `FIXED` 1.8.1 — a local home page could read storage and post it out

`HomePageView.ensureCustomWebView()` enabled three file permissions together.
Two are the feature: a custom home page kept on the device should be able to
load its own stylesheet and scripts. The third, `allowUniversalAccess-
FromFileURLs`, is a different thing — it lets a `file://` document make
cross-**origin** requests.

Together they turn "read the folder I live in" into "read what the app can read
and send it anywhere". Reaching it needs the user to point their home page at
an HTML file they did not write, which is why this is A2 and not A1 — but
"open this page, it's a nice start page" is not an exotic attack.

Removed. Sibling assets still load.

---

### A-003 · A3 · `WATCH` — cleartext is allowed for everything, not just pages

`usesCleartextTraffic="true"` with no `networkSecurityConfig`. A browser has to
load `http://` pages, so this cannot simply be turned off — but it currently
also permits the app's *own* traffic to go unencrypted: filter lists, the icon
service, and any AI endpoint a user types as `http://`.

A network security config could keep cleartext for WebView while requiring TLS
for the app's own hosts. Recorded rather than changed, because getting the split
wrong would break local AI endpoints (`http://192.168.x.x`, Ollama) that people
deliberately use.

---

## Confirmed safe

Recorded so the same ground is not re-walked.

- **Browser tabs do not get file access.** `TabManager` sets
  `allowFileAccess = false` for page WebViews; the `true` at
  `MainActivity:1132` is a separate, non-page WebView.
- **Automatic popups are off by default** (`javaScriptCanOpenWindowsAutomatically
  = false`) and follow the user's own site setting.
- **Exported components are four, and all deliberate:** `MainActivity` (it is a
  browser), the two widget configuration activities (the launcher starts them),
  and `ZephyrCustomTabsService` (other apps must bind to it). No exported
  receiver or provider.
- **No analytics, advertising or crash-reporting SDK** — verified against the
  dependency list, not only the source.
- **Vault encryption is sound.** AES-256-GCM, key in the Android Keystore, a
  fresh random IV per record read back from the cipher, and no silent failure —
  `encrypt` rethrows rather than storing plaintext. The file-backed fallback key
  lives in `noBackupFilesDir`, so it never leaves the device in a backup. (Its
  *regeneration* path is a separate problem — see A-004.)
- **Backup encryption is sound where it is applied:** AES-256-GCM with
  PBKDF2-SHA256 at 120 000 iterations and a per-file salt. The weakness is that
  applying it is optional — A-005.
- **The assistant cannot escape its workspace.** `AiWorkspace.resolve`
  canonicalises the path and requires it to equal the root or begin with
  `root + "/"`, which resolves `..` before the comparison — the correct check,
  not a string blacklist. `/data/` stays refused even when the user has switched
  on "write anywhere".
- **Credential *saving* trusts only the tab.** `onLoginSubmitted` takes the
  origin from `tab.pageUrlSnapshot`, so a page cannot store a credential under
  somebody else's name. This is the method A-001 should have been written like.
- **Page actions that change something ask first** — tap, fill, close tab, add
  bookmark, write file — unless the user has deliberately turned confirmations
  off. `run_js` was the exception until A-006.
