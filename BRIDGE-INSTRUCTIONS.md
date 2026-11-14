# Zephyr Bridge — how to deliver generated media to the Zephyr app

**Written for:** the developer/agent working on the Zephyr Bridge server
(`p12wm75fzf01-d.space-z.ai`). From the author of the client app.

Your analysis of the two blockers is accurate. This document corrects one
assumption, closes Blocker B from the client side, and tells you exactly what
to change on the server so Blocker A stops mattering.

---

## 1. The client already does what you were about to build

You proposed option **A2 — "late delivery on the next turn"**. Do not build it.
It is unnecessary, and it delivers the image attached to the user's *next*
message, which is worse than what the app can already do.

The Zephyr app has had a pending-media poller since 1.7.53. Given a URL it
recognises as generated media, it:

1. renders a placeholder bubble with a shimmer and a caption
   *"Generating… N s"*, counting seconds;
2. requests the URL with `Range: bytes=0-0`;
3. treats **202 / 204 / 404 / 425** as *not ready yet* and retries every 4 s;
4. on **200**, switches on `Content-Type`: `image/*` becomes an inline image in
   the bubble, `audio/*` becomes a player;
5. gives up after its timeout and says so.

That is precisely the behaviour the owner is asking for. Nothing about it needs
a long-lived HTTP response.

## 2. Why it looked broken — one character, on our side

The owner reported that the first message never showed a picture and a later
one did. Both carried a link to the same bridge. The difference:

```
.../api/bridge/media/<id>        → recognised → placeholder → polled → shown
.../api/bridge/live-media/<id>   → NOT recognised → left as plain text
```

The client matched the path segment `/media/`. In `live-media/` the character
before `media` is a hyphen, so the check missed it, and with it the whole
polling path. **This was a client bug and it is fixed in app 1.9.6** — the
detector now matches the segment, so `media`, `live-media`, `media-v2`,
`tmp-audio` and similar all work.

Please still collapse the two routes into **one canonical path**. Two spellings
for the same resource is what made this intermittent and therefore expensive to
find.

## 3. Blocker A disappears if you stop waiting inline

You are holding the HTTP response open for 60–160 s waiting for bytes, and the
Function Compute proxy kills it at 30–70 s. Do not try to win that fight; the
platform limit is not yours to control, and a request that long is fragile on a
phone regardless — a tunnel change or a screen lock ends it.

**Return the URL as soon as the generation is queued, not when it completes.**

- `image_generation` should create the record, start the work, and return
  within a second or two with the media id and its URL;
- the response then finishes in a few seconds and survives the proxy
  comfortably;
- `GET /api/bridge/media/<id>` already answers **202 while pending** and **200
  with bytes when ready** — you built exactly the endpoint this needs. It is
  currently unused, because the code waits instead of letting the client poll.

The keep-alive `: ping` comments then become unnecessary for images.

## 4. Use the in-band channel. Nothing in the text.

**Updated 23 Sep 2026 — app 1.9.7 now reads the standard fields.** An earlier
version of this document asked you to keep the URL in the prose. That was true
of app ≤1.9.6 and is no longer. Your Era-3 contract was right; the app was the
side that could not read it, and that is fixed.

The app now parses, on both the streaming and non-streaming paths:

```jsonc
choices[0].delta.images   // and message.images
  [ { "type": "image_url", "image_url": { "url": "https://…" } } ]
  [ "https://…" ]                       // the short form is accepted too

choices[0].delta.audio    // and message.audio
  { "url": "https://…" }
  { "id": "audio_x", "data": "<base64 wav>", "transcript": "…" }
```

Base64 audio is decoded to a local file and played from there, so you may send
either shape.

Therefore, please **remove every text-side workaround**:

- no `![](url)`, no bare URLs, no `<img>`/`<audio>` in the assistant's text;
- no `🎨 Генерирую…` / `🔊 Озвучиваю…` lines — **the app draws the progress
  animation itself**, from the moment it has the URL. Your text duplicates it
  and arrives at the wrong time;
- no sentences like *"the audio is already embedded in the message"*. It is not,
  and it reads as a false claim while the player is still loading;
- do not let the model quote a media URL from an earlier turn. The app now shows
  one id once per conversation, but the model should not be echoing them at all.

The assistant's text should read as if a human wrote it: *"Here is the wolf."*
The picture arrives through the field, not the sentence.

## 5. Requirements on the media endpoint

The poller depends on these. Please verify each:

| Requirement | Why |
|---|---|
| `Range: bytes=0-0` must not fail | the probe asks for one byte to read headers cheaply; a 400/500 on Range looks like a dead link |
| `Content-Type` correct on 200 | `image/jpeg`, `audio/wav`… the app switches image-vs-player on this, not on the extension |
| 202 (or 404) while pending | anything in {202, 204, 404, 425} means "keep waiting" |
| a terminal failure status | so the app can stop; 409 is fine, just make it not 202 |
| CORS not required | it is a native client, not a browser page |

## 6. Worth doing anyway

- **Disable "thinking" for `/v1` requests that carry tools.** You measured
  30–70 s of silence before any content delta. With async generation that no
  longer risks the response, but it is still 30–70 s of the user watching
  nothing.
- **Keep the open feed** `GET /api/bridge/generations`. It is a good recovery
  path if a URL is ever lost, and it costs nothing.

## 7. Questions to answer before changing anything

The owner suspects the server has accumulated workarounds from earlier eras.
Please answer these plainly — they decide how much has to be removed:

1. **Which channel does `image_generation` actually use today?** In-band
   (`delta.images`), a URL printed into the text, or both at once? The owner is
   seeing text URLs, which suggests the text path is still live.
2. **Which of the earlier fallbacks are still in the code?** "Late delivery on
   the next turn", the top-level `media` field, the `live-media` route, the
   `resultForModel` instruction that tells the model to print or not print
   URLs — what is still wired in?
3. **Why are there two media routes** (`/api/bridge/media/<id>` and
   `/api/bridge/live-media/<id>`)? What is the difference, and can it be one?
4. **Does `image_generation` still block until the bytes exist?** If yes, that is
   §3 and it is the remaining cause of images failing through the proxy.
5. **Who emits `🎨 Генерирую изображение…` and `🔊 Озвучиваю текст…`** — the
   model, or the server injecting text? Either way it should stop; the app has
   its own progress state.

## 8. How to verify, end to end

1. Owner asks for an image in the app.
2. Expected within ~3 s: a `delta.images` frame carrying the URL, ordinary
   prose with **no URL in it**, and the response **completes** — no long-held
   connection.
3. The app shows *"Generating… N s"* in the bubble, on its own.
4. `GET /api/bridge/media/<id>` returns 202 during generation.
5. When the bytes land it returns 200 `image/jpeg`; the placeholder becomes the
   picture in the same bubble.
6. Same for `Озвучь "hello"`: a `delta.audio` frame, clean prose, a player in
   the bubble.

If step 2 takes longer than ~10 s, the generation is still inline — that is the
change in §3, and nothing after it will work reliably through the proxy.

A quick way to see what is really on the wire, without the app:

```bash
curl -N -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"model":"glm-5.3","stream":true,"messages":[{"role":"user","content":"Нарисуй волка"}]}' \
  https://p12wm75fzf01-d.space-z.ai/v1/chat/completions
```

The assistant's `content` deltas should contain no `http`, no `![`, and no
`Генерирую`. If they do, the text path is still live — that is §4.
