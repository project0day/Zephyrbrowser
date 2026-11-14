# Zephyr Browser — audit findings

A running log of defects, risks and loose ends found while reading the code,
kept separate from the work that triggered them. Nothing here is fixed just
because it is written down: each entry stays until it is closed, and closed
entries keep their resolution so the same ground is not re-walked later.

**Status:** `OPEN` · `FIXED` · `WONTFIX` (with a reason) · `WATCH` (accepted risk)

**Severity:** `S1` data loss, security, or a crash on a common path ·
`S2` a feature is wrong or broken · `S3` cosmetic, or wrong only in a corner ·
`S4` cleanliness, dead code, inconsistency

---

## Open

### F-006 · S1 · Signing key needs a backup off this machine
`keystore/zephyr.keystore` and `keystore/keystore.properties` sit in the project
tree, and the properties file holds the store and key passwords as plain text.

This is the app's identity on Google Play: an update is only accepted if it is
signed with the same key. Lose the file and the listing can never be updated
again — not by anyone, including Google. Leak it and a third party can sign a
package that installs over the real app.

Excluded from git as of 1.7.83, which closes the leak-by-push risk. Two things
remain, and neither is a code change:

1. **Back the keystore up off this machine** — an encrypted archive elsewhere.
   A disk failure today ends the app's upgrade path permanently.
2. Consider enrolling in Play App Signing when the listing is created, so
   Google holds the upload key's counterpart and a lost key can be recovered.

### F-007 · S2 · Contact address is a placeholder in six shipped documents
`assets/legal/privacy_{en,ru,fr}.txt` and `assets/legal/terms_{en,ru,fr}.txt`
each end with `[CONTACT EMAIL — see FINDINGS.md F-007]`. A privacy policy
without a reachable contact is not a valid policy, and Google Play requires a
support address on the listing.

Left as a placeholder deliberately: which address receives support mail — and
whether that is a personal address or one created for the app — is the
developer's decision, not one to make on their behalf by publishing an address
found elsewhere. A dedicated address is the better choice: it can be handed to
someone else later, and it keeps support traffic out of a personal inbox.

Replace in all six files before the first upload.

### F-003 · S2/S4 · Russian string literals in Kotlin — **re-scoped**
The first estimate here said "~1315 user-visible strings". That was wrong, and
the correction matters because it changes what the work is.

A proper count finds **1091 Russian string literals**, and they fall into two
groups that need opposite treatment:

**a) Model-facing (~950, mostly `AiTools.kt`).** Tool schema descriptions and
tool result strings. The user never sees these — the model reads them and
phrases its own answer. They are not a localisation bug. They should still
become English, because tool schemas in English are what models are trained on
and it is the convention for an API contract, but that is a quality change, not
a correctness one. **No string resources involved.**

**b) User-facing (~90).** Text that actually reaches the screen. These are the
real defect: they ignore the translations, so a French user meets Russian.

Progress on (b):
- `AiActionLogSheet.kt` — 66 tool labels in "What the assistant did" were a
  Kotlin map of Russian literals, so the whole log read in Russian whatever the
  app language. Now resources, all three languages. **Done, 1.7.86.**
- `AiClient.kt` — the whole status line (*Typing…*, *Reasoning…*, *Connecting
  to…*) and every error message, including the HTTP ones users actually hit.
  **Done, 1.7.86.**
- `AiSwarm.kt` — swarm statuses and failure messages. **Done, 1.7.87.**
- `AiWriteReview.kt` — the diff labels in the file-review sheet.
  **Done, 1.7.87.**
- `AiBusiness.kt`, `CvStyleSheet.kt` — the business-details form and the CV
  style picker. **Done, 1.7.88.**
- `CvBuilder.kt`, `CvTemplates.kt` — document headings, via `DocLang` rather
  than resources. See F-013. **Done, 1.7.88.**
- Default names («Диалог», «Модель», «Мой ИИ-агент»), template category folders
  and the size units Б/КБ/МБ/ГБ. **Done, 1.7.88.**
- `SettingsSearch.kt` — see F-014. **Done, 1.7.89.**

All three locales now hold 1890 strings each — exact parity.

**Group (a) — the model-facing text — has not been started.** It is the larger
half and it changes nothing for the user; it is a quality and readability job,
to be done with the comment translation.

Two singletons (`AiClient`, `AiSwarm`) write text the user reads but are handed
no `Context` — which is precisely why their strings were literals. Both now
borrow the application context through a new `App.ctx`, set first thing in
`onCreate`.

### F-009 · S4 · Card brand "МИР" is a Cyrillic literal
`panels/PaymentsPanelFragment.kt` returns the literal `"МИР"` for cards whose
number starts 2203/2204. This one is correct as it stands — it is the brand's
name, not UI text — and is recorded only so a future sweep for Cyrillic does not
"fix" it. `WONTFIX`.

### F-004 · S4 · Comments are written in Russian
~2288 comment lines contain Cyrillic. Harmless to users, but the repository is
going to a private GitHub and the code should read in one language throughout.

---

## Closed

### F-024 · S2 · `FIXED` 1.8.3 — cancelling a download still did not cancel it
The 1.8.2 fix was incomplete, and the reason is worth keeping.

`cancel()` set a flag on a `Ctrl`, and a `Ctrl` exists only while
`runTransfer` is executing. A download waiting for one of the three slots had
none, so the cancel landed on nothing. Worse, `runTransfer` opened with
`ctrl.cancelReq = false` — so even a cancel that *did* reach a Ctrl was erased
the moment the transfer started. Cancelling while a download was queued, which
is exactly when the user is still watching it, could not work at all.

That also explains the rest of the report:

- **the notification froze** — nothing ever took it down;
- **it then said "download failed"** — with the flag cleared, the outcome fell
  through to the failure branch, announcing a failure for something the user
  had deliberately stopped.

Now the intent to cancel is recorded in a set that outlives any `Ctrl`.
`runTransfer` checks it before starting and leaves; the outcome asks once
whether it was cancelled; a cancel that lands *after* the final byte deletes the
file rather than letting it appear anyway; and the notification is taken down
in every one of those paths.

Two more, both reported in the same breath:

- **the panel had no cancel button at all** — the notification got one in 1.8.2
  and this screen did not, so stopping a transfer here meant deleting the row
  and hoping. It has its own button now, on running rows.
- **the endlessly sweeping progress bar** — that is what a bar shows with no
  total, and the panel was working the total out by itself with a HEAD request
  that frequently fails. The engine already read the real `Content-Length`; the
  panel asks it now.

### F-023 · S2 · `FIXED` 1.8.2 — deleting a download did not stop it
User: «удалил загрузку, очистил, но она не прекратилась и файл всё равно
скачался».

`deleteEntry` removed the stored file and then the database row. It never
called `DownloadEngine.cancel`, so the transfer coroutine carried on and wrote
the finished file afterwards.

The order made the outcome certain rather than merely likely: the file was
deleted *while* the download was still running, and the download then recreated
it. Deleting an active download was not ineffective — it was backwards.

The transfer is now stopped first, and only then is anything removed.

Two things shipped with it, both asked for:
- the notification and the panel row now read `12.4 MB / 48.0 MB · 26%`
  instead of a bare moving bar. The bar answers "is it alive"; the numbers
  answer "what is this costing me" and "how much longer";
- the notification carries a **Cancel** action, handled by a broadcast rather
  than an activity — stopping a download should not open the browser.

### F-022 · S2 · `FIXED` 1.7.98 — five settings looked read-only
User: «Температура, Лимит ответа, глубина истории, Ждать ответа агента, Глубина
размышлений — недоступны для модификации».

An `AlertDialog` has **one** content slot: a message or a list, never both.
Every one of these pickers called `setMessage(hint)` and then
`setSingleChoiceItems(...)`, so the dialog opened showing the explanation and
no options at all. The setting was reachable, explained, and impossible to
change.

**This is the second time the same mistake shipped** — the burn dialog had it in
1.7.55 («в диалоге нет галочек») and was fixed there by moving the text into the
title. The lesson did not travel because the fix lived inline.

So the fix is now a shared `SettingsUi.dialogTitle(title, hint)`, and it was
applied to all four call sites: `pickNumber` in `AiPrefsFragment` (temperature,
reply limit, history depth, agent timeout), the reasoning-depth picker in
`AiModelDialogs`, and the autofill profile chooser in `MainActivity` — which had
the same defect and nobody had reported it yet.

### F-021 · S2 · `FIXED` 1.7.97 — one switch quietly governed every tool
User: «агент через эндпоинт не имеет доступа к закладкам, истории, созданию
файлов… что сказать агенту?» — nothing, as it turned out. It was not the agent.

`AiClient.chatStream` opened with `var toolsAllowed = Prefs.aiWebAccess`. That
single line meant the **"Internet access"** switch decided whether the model was
offered *any* tool at all. Turn it off and the assistant also lost bookmarks,
history, the workspace, file creation and browser control — none of which touch
the internet, and every one of which has its own switch that was then ignored.

From the outside this is indistinguishable from a provider that does not support
tool calling, which is exactly how it was read both times it came up.

Fixed in three parts:
- `toolsAllowed` is now `aiWebAccess || AiTools.anyEnabled()`;
- `web_search` and `open_page` answer to the web switch alone, instead of
  riding along unconditionally;
- an empty tools array is no longer sent. `"tools": []` is not the same as no
  tools field — several providers reject it, and that rejection lands in the
  "provider choked on tools" branch, silently disabling tools for the rest of
  the request. That path could turn one refused request into a session with no
  tools at all.

### F-016 · S4 · `FIXED` 1.7.95 — teaching cards where the name explains nothing
Four screens got the dismissible first-run card: Templates, Workspace,
Workflows and Tasks. Chosen because their names tell a new reader least —
«Сценарий» prompted the user to ask outright what it meant, and «Проекты» does
not suggest "files the assistant wrote for you".

Deliberately not added to Bookmarks, History or Downloads: a card on every
panel is noise, and those names already say what they are.

Widget colours went from 18 to 28 in the same release, the picker being shared
by all three widgets.

### F-020 · S2 · `FIXED` 1.7.94 — desktop pages could not be panned sideways
User: «в ПК режиме не всегда я могу двигать страницу влево вправо».

"Not always" was the clue. In desktop mode a page is wider than the screen and
has to be dragged sideways — and you grab near an edge to do it, which is
exactly where the back/forward edge gesture lives. A drag from the left edge
navigated back, a drag from the right went forward, and a drag from the middle
worked. Hence sometimes.

`handleEdgeGesture` now yields while the page still has somewhere to scroll in
that direction: `webView.canScrollHorizontally(towards)`. Once the page reaches
its edge the gesture engages exactly as before, and an ordinary page — which
cannot scroll sideways at all — is unaffected, because the check is false for it
from the first move.

### F-019 · S2 · `FIXED` 1.7.92 — the search widget never ran its config screen
User: «появились только дела с выбором цветов». Correct, and the cause was one
missing attribute.

`WidgetConfigActivity` was declared in the manifest with an
`APPWIDGET_CONFIGURE` intent-filter, which looks like enough and is not: the
provider has to *name* the activity with `android:configure`. Without it the
system has nothing to launch, so placement went straight to defaults — meaning
the engine picker written long before the colours had in fact never appeared
either. `search_widget_info.xml` had never carried the attribute.

Verified in the built APK rather than by reading the source, because that is
what went wrong the first time: `android:configure` is now present in the
compiled `res/lq.xml`.

### F-018 · S4 · `FIXED` 1.7.92 — every setting now says what it does
Sixteen switches and seventeen rows were a bare label. A label says what a
thing is called, not what happens when you touch it — and someone who installs
the browser in a language they did not choose has only these lines to go on.

Each new line says what the setting does and, where it is not obvious, what it
costs: third-party cookies explain that this is how you are followed between
sites; search suggestions admit that what you type is sent as you type it;
"reset all settings" states that bookmarks, passwords and files are not touched.

Three rows keep no hint by design — they are dynamic (a domain, a date, a
filter-list name) and a fixed sentence under them would be noise. The backup
category switches keep none either: "include bookmarks in the backup" is not
something a subtitle improves.

### F-017 · S3 · `FIXED` 1.7.91 — one widget, and no way to recolour it
Two separate things behind one complaint.

**The colour was unreachable.** A widget's configuration screen runs when it is
*placed* and never again, so a widget already on the home screen kept whatever
it was added with. Fixed by declaring `reconfigurable`, which puts a settings
entry in the widget's own long-press menu (Android 12+). Deliberately *not*
`configuration_optional`: placement should still open the picker, or nobody
discovers the setting exists.

**There was only one widget, in six colours.** Now two widgets in fourteen
colours. The new one is Tasks: what is due, read without being touched, which
is the only thing on this home screen that earns a widget on its own terms —
everything else is a shortcut to opening the browser.

Built as four fixed rows plus a counter rather than a `RemoteViewsService`
collection. A scrolling list costs a service and an adapter that must stay
answerable for a surface nobody interacts with, and four rows is roughly what
stays readable at a glance anyway. It repaints whenever a reminder is added,
edited, deleted or created by the assistant — a to-do widget showing
yesterday's list is worse than no widget, because the user stops trusting it.

### F-015 · S4 · `FIXED` 1.7.90 — explanatory text was written four ways
The same "quiet note under a group of settings" appeared as `alpha = 0.7f`,
`alpha = 0.75f` and an explicit `colorOnSurfaceVariant`, with bottom padding of
4, 8 or 10 depending on who wrote the screen. Empty lists were worse: some had
a full icon-title-hint block, some a single grey line, some nothing.

Nobody notices any one of these. Everybody notices that the app is slightly
uneven.

There is now one of each kind, and the choice between them is a rule rather
than a preference:

| | what it is for |
|---|---|
| `SettingsUi.note` | a quiet explanation; no consequence attached |
| `SettingsUi.banner(tone)` | something to take in *before* acting — INFO, WARN, DANGER |
| `PanelUi.hintBanner` | dismissible first-run teaching card (already existed) |
| `PanelUi.emptyState` | an empty list: icon, what is missing, what to do |

`emptyView` — the bare grey line — is deprecated; it never told the user what to
do next.

Applied: every note on the settings screens; the API-key export warning promoted
from a grey line to a DANGER banner, because handing that file to someone hands
them billing access; and empty states across Autofill, Payments, Passwords,
Offline, Templates, Workspace and Workflows, each hint saying what to do rather
than restating the title.

Added where it was missing: a WARN banner on the AI screen when no key is
configured. That is the commonest "why doesn't it work" state and the one that
would otherwise become refunds on a paid listing — see `RELEASE_CHECKLIST.md`
§4. It disappears as soon as a key exists.

### F-014 · S2 · `FIXED` 1.7.89 — Settings search worked in Russian only
`SettingsSearch.kt` indexed each entry with a `keys` string of extra search
words — «тема цвет тёмная», «сжечь очистить всё огонь паника». The titles came
from resources and so were translated; the keywords were Kotlin literals and
were not.

That quietly halves the feature for everyone else. Searching Settings for
"theme", "ads" or "fingerprint" matched nothing unless the word happened to
appear in a title — and the keywords exist precisely because titles often do
not contain the word people look for. All 37 entries are now resources, with
Russian, English and French keyword sets written for each.

### F-013 · S2 · `FIXED` 1.7.88 — generated documents were always Russian
`CvBuilder.kt` stamped «Опыт работы», «Образование», «Навыки» into every CV it
produced, and `CvStyleSheet`/`AiBusiness` were Russian in the interface too.

The fix had to separate two things that look alike and are not:

**Interface** — the "My business details" form and the CV style picker. The
person reading those is the person using the app, so they are ordinary string
resources and follow the app language. Done.

**Document content** — the headings printed inside a finished CV or invoice.
These are *not* resources, and making them resources would have been the
obvious mistake: the language of a document is a property of the document, not
of the app. The central case in this app is exactly where the two differ — a
Russian-speaking autoentrepreneur in France writes a French devis for a French
client, from a browser they read in Russian. Resources would have printed
«Опыт работы» on a CV meant for a French employer.

So `DocLang` holds the headings in six languages, and the document language is
decided per document:

1. the assistant passes `lang` when the context settles it — a job advert in
   German, a client whose details are French;
2. otherwise it falls back to the interface language, which is right whenever
   someone is writing for their own country;
3. and the user can simply say which language they want.

The system prompt now states that rule explicitly, and says to ask in one short
sentence when it is genuinely unclear. Unknown languages fall back to English
rather than to the app's language: an English heading on a Hungarian CV is a
mild oddity, a Russian one is wrong.

### F-012 · S2 · `FIXED` 1.7.86 — "Translate page" always translated to Russian
Two places, one cause. The suggestion chip sent the model the literal
instruction «Переведи … на русский язык», and the "Translator" preset said
«Любой текст переводи на русский». The prompts are invisible, so a French user
tapped *Traduire la page* and simply got Russian back with no explanation.

Worse in general: all four presets were written in Russian, which pushes a model
towards answering in Russian whoever is asking.

Every preset is now English — which models follow more reliably — and each one
ends by telling the model to reply in the user's own language. Where a prompt
must name a target language it asks the new `AiLang`, which reports the
interface language by its English name (`"French"`, not `"Français"` — models
resolve that far more reliably inside an English sentence). `AiLang` reads the
resource configuration rather than the system locale, because the app has its
own language picker and it is that choice the assistant should follow.

### F-011 · S3 · `FIXED` 1.7.86 — crash log carried a hard-coded version
`App.installCrashLogger()` wrote `version: 1.7.17 (31)` into every
`last_crash.txt`. The same defect as F-001, in the file that matters more: the
crash log itself. Both the log and the share subject now ask the package
manager.

### F-010 · S2 · `FIXED` 1.7.85 — the app fell back to Russian for the world
`values/` held the Russian strings, which makes Russian the resource fallback:
a phone set to German, Spanish or anything else without its own translation got
**Russian**, not English. On top of that, 228 strings had no English at all and
250 no French, so even an English phone hit Russian at those points.

Fixed in two steps. Every missing string was translated — English and French are
now at 1726 each, exactly matching the base. Then `values/` and `values-en/`
were swapped: English is the base, Russian moved to `values-ru/`, and
`values-en/` was deleted as redundant. Verified in the built APK: the default
entry of each string is English, with `fr` and `ru` as variants.

The language picker still defaults to "system", so a Russian phone still shows
Russian — only the fallback for everyone else changed.

### F-008 · S2 · `FIXED` 1.7.84 — every visited domain was sent to Google
`FaviconLoader.fetchNetwork()` asked `google.com/s2/favicons` (falling back to
DuckDuckGo) for each site's icon, which tells that service the domain is being
opened. There was no switch and — the real defect — **no exemption for private
tabs**, so a browsing mode that promises to leave no trace was quietly
announcing every domain to a third party.

Now skipped entirely in private tabs and under "always incognito", and
switchable in Settings → Privacy. Disclosed in the privacy policy, §2.3.

### F-002 · S2 · `FIXED` 1.7.84 — About screen was not publishable
`AboutPrefsFragment` had a version row and two rows that did nothing. A paid
listing needs more than that, and some of it is a legal obligation rather than a
nicety — the Apache-licensed components require their notices to be distributed
with the app.

Added: privacy policy, terms of use and open source licences, shipped as text in
`assets/legal` and shown by `LegalPrefsFragment`; the WebView engine version,
which is the usual explanation when one site misbehaves; and a support section.
Policy and terms are in Russian, English and French; licences are English, being
notices reproduced from their authors.

### F-005 · S4 · `FIXED` 1.7.83 — release APKs sat at the project root
Twenty-eight `Zephyr-Browser-*.apk` files beside the sources. Now excluded by
`.gitignore`; the files themselves are still on disk and can be deleted at any
time.

### F-001 · S3 · `FIXED` 1.7.82 — crash report carried a hard-coded version
`shareLastCrashReport()` set the share subject to the literal
`"Zephyr 1.7.17 crash report"`. Every report sent since that release was
labelled with a version dozens of releases old — the one field that makes a
report triageable. Now asked of the package manager.

### F-000 · S1 · `FIXED` 1.7.80 — photographs reached the model rotated
`ai/AiClient.kt` — `encodeImage()` decoded a photo and re-encoded it as JPEG,
which discards EXIF. A phone camera does not rotate pixels; it records the
orientation in a tag. So a portrait photo arrived at the vision model lying on
its side, invisibly: every viewer in the app honoured the tag, only the encoder
did not. Fixed by applying the orientation before compressing. The review screen
in `ui/CameraAiActivity.kt` had the same blind spot and was fixed with it.

### F-000b · S3 · `FIXED` 1.7.80 — backup timestamp never refreshed
`settings/BackupPrefsFragment.kt` — the row's label was looked up with
`getChildAt(2)`, which is the chevron image, not a `TextView`. The cast returned
null silently, so the "last copy" stamp only ever changed when the whole screen
was rebuilt.
