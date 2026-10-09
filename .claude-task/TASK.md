# Task: make the format-info fetch for Shorts much faster (JoTube)

Repo: personal fork of SmartTube (Android TV YouTube client). The library `MediaServiceCore/` (submodule) talks to
YouTube. In this run it is already patched with `patches/MediaServiceCore/0001..0005` (read them to see what changed);
edit the files under `MediaServiceCore/` directly, the workflow turns your edits into a new patch file.

## Problem (measured on the device, Google TV Streamer)
While a Short plays, the app prefetches the "format info" (stream URLs etc.) of the next Shorts one by one
(`common/.../app/models/playback/controllers/VideoLoaderController.java`: prefetchNext → MediaItemService.getFormatInfoObserve).
Each fetch takes 2.5–4.5 s:
- 16:25:00.176 prefetch +1 → done 03.644 (3.5 s); 03.645 +2 → 06.245 (2.6 s); 06.246 +3 → 10.729 (4.5 s);
  10.729 → 14.719 (4.0 s); 14.720 → 18.2 (3.5 s).
The user swipes every ~3 s, so the lookahead runs dry after 4–5 Shorts and the next Short isn't preloaded (slow, ugly
transition). Fetches must stay one at a time: `FormatFetchLock` serializes them because shared state (VideoInfoService,
PoTokenGate, AppService decipher, auth flag) broke streams with 403 when they ran in parallel. Never cancel a fetch midway.

## What to do
1. Trace exactly what one `getFormatInfo(videoId)` does for a Short (FormatInfoWrapper → VideoInfoService / Innertube
   providers): every network request in order (which clients are tried — `firstPlayable` may try several —, poToken
   generation, player JS / decipher / nsig, visitor data, history sync, translations), and which of them are repeated
   for every video although they could be reused (same for all videos, cacheable for minutes/hours).
2. Implement the safest changes that cut the per-Short time substantially, for example: reuse per-session data
   instead of refetching; try the client that worked last time first (Shorts are all the same kind); skip work the
   Shorts preload doesn't need; batch if YouTube offers a request returning several Shorts' streams at once (only if
   you can verify it in this code base). Keep correctness: a wrong client/token = 403 = broken playback.
3. Don't change app UI code. Small, commented (`JoTube:`) changes; Java 8 / the project's Kotlin version; no new deps.
You can't build or run anything here; a reviewer will check and build your diff.
In REPORT.md: the request timeline you found (with which steps are slow), what you changed, why it is safe, expected gain, risks.
