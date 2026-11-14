# Release checklist — before the first Google Play upload

Decisions and tasks that are **not** code, and that nobody but the developer can
make. Each one blocks publication.

---

## 1. Back up the signing key — do this first

**Files:** `keystore/zephyr.keystore`, `keystore/keystore.properties`

The keystore is the app's identity on Google Play. Google accepts an update only
if it is signed with the same key. There is no recovery: lose the file and the
listing can never be updated again, by anyone. `keystore.properties` also holds
the store and key passwords in plain text.

Excluded from git as of 1.7.83, so it will not reach GitHub. That leaves the
part git cannot help with:

- [ ] Copy both files into an encrypted archive stored **off this machine**
      (password manager, encrypted USB, or private cloud — not the same disk).
- [ ] Enrol in **Play App Signing** when creating the listing. Google then holds
      the counterpart of the upload key, and a lost key becomes survivable.

Until this is done, a disk failure permanently ends the app's upgrade path.

---

## 2. Choose the support / contact address

**Two separate fields, often confused.**

**Support email — any address, and a dedicated one is better.** A new free
Gmail is enough; no Workspace subscription is needed. It is published in the
listing and in the privacy policy permanently, it cannot be withdrawn later, it
will be scraped by spam lists, and a personal address makes the project
impossible to hand over.

**Developer address — not the same question.** Google Play has a separate
developer address field, and for **paid** apps and for sellers in the EU it is
**shown publicly** on the listing under EU seller-transparency rules. As an
autoentrepreneur this applies.

This cannot be a made-up address: it is the registered business address, which
for an autoentrepreneur defaults to the home address. The usual way to avoid
publishing a home address is a *domiciliation* service (a commercial registered
address, roughly 15–40 €/month in France).

The exact requirement should be confirmed in the Play Console when the account
is created — the form states what is mandatory. **Decide before submitting**,
because the address becomes public with the listing.


**Six files** currently contain the placeholder
`[CONTACT EMAIL — see FINDINGS.md F-007]`:

```
app/src/main/assets/legal/privacy_en.txt
app/src/main/assets/legal/privacy_ru.txt
app/src/main/assets/legal/privacy_fr.txt
app/src/main/assets/legal/terms_en.txt
app/src/main/assets/legal/terms_ru.txt
app/src/main/assets/legal/terms_fr.txt
```

A privacy policy without a reachable contact is not a valid policy, and Play
requires a support address on the listing.

- [ ] Decide the address. **A dedicated one is better than a personal inbox:**
      it can be handed to someone else later, it keeps support mail separate,
      and it will be published permanently.
- [ ] Replace the placeholder in all six files.
- [ ] Enter the same address in the Play Console listing.

---

## 3. Privacy policy URL

Play requires a policy reachable at a **public URL**, separately from the copy
bundled in the app. The bundled copy satisfies the user; the URL satisfies the
Console.

- [ ] Publish `privacy_en.txt` somewhere public and stable. Free options:
      GitHub Pages (needs a public repo, or a Pro account for a private one),
      a Google Site, or any static host.
- [ ] Keep the published text and the bundled text identical. If they differ,
      the bundled one is the one users see.

---

## 4. Price and the API-key expectation

The assistant needs the buyer's own API key. This is stated in the terms
(§2) and must also be visible **before purchase**, or it becomes refunds and
one-star reviews from people who expected AI included.

- [ ] Put it in the first two lines of the Play description, not at the bottom.
- [ ] Price: 1–2 € is a reasonable entry point. Google takes 15 % of the first
      million; EU VAT is added on top of the listed price; the income is
      declared as autoentrepreneur revenue.

---

## 5. Play Console data safety form

The Console asks, item by item, what the app collects. The answers follow from
the privacy policy — keep the two consistent, because a mismatch is a common
rejection reason.

- [ ] Declares **no** data collection by the developer (no analytics, no ads,
      no crash SDK — verified in the source, see `FINDINGS.md`).
- [ ] Declares that user-supplied content is sent to a **user-configured**
      third-party AI provider.
- [ ] Declares the optional third-party icon service (Settings → Privacy).

---

## 6. Permissions justification

Several permissions need an explanation in the Console, and reviewers reject
vague ones. What each is for is set out in the privacy policy, §3.

- [ ] `SCHEDULE_EXACT_ALARM` — reminders that must fire at a stated time.
      Play scrutinises this one; be ready to justify it or switch to
      `USE_EXACT_ALARM` with its stricter eligibility rules.
- [ ] `CAMERA`, `RECORD_AUDIO`, storage — tie each to the feature that uses it.
- [ ] VPN / `BIND_VPN_SERVICE` — this always draws review attention. Be ready to
      state that no traffic is routed to any server operated by the developer.

---

## 7. Store listing assets

- [ ] Icon 512×512 (source artwork exists — regenerate from the same file used
      for the launcher icon).
- [ ] Feature graphic 1024×500.
- [ ] Screenshots, at least two per form factor.
- [ ] Short and full description, in the languages the app supports.
