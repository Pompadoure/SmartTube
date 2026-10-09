# JoTube speed audit: next speed-ups (analysis only, no code changed)

Scope: click on a regular video → first frame, and Shorts entry / swipe → next Short playing.
I read the code (paths below) but could not run it on the device. Time estimates are reasoning from the
measurements in TASK.md, not new measurements. Items marked **(unverified)** need a log or a trace first.

Paths are relative to the repo root; `common/` = `common/src/main/java/com/liskovsoft/smartyoutubetv2/common/`,
`MSC/` = `MediaServiceCore/youtubeapi/src/main/java/com/liskovsoft/youtubeapi/`.

## How the two paths work now

**Regular video click.** `BrowsePresenter.onVideoItemClicked` → `VideoActionPresenter.apply` → `PlaybackPresenter.openVideo`
(stores the video, `startView(PlaybackView)`) → activity + `PlaybackFragment.onCreate` (new `ExoPlayerController`,
presenters) → `onStart` → `initializePlayer()` (`PlaybackFragment.java:479`: player, glue, rows, clock, …) →
`PlaybackPresenter.onEngineInitialized` → `VideoLoaderController.loadVideo` → `loadFormatInfo` (`:340`) →
`getFormatInfoObserve` (io thread, result on main) → `processFormatInfo` → `openSabr/openDash` (manifest parse + source build on
main) → `prepare` → SABR init chunks → media chunks → `shouldStartPlayback` after 1 s buffered → first frame.
If the hover prefetch (`BrowsePresenter.prefetchFocused`, 600 ms dwell) already filled `FormatInfoWrapper`'s cache, the fetch step is a cache hit.

**Shorts.** Sidebar focus loads the feed; `prefetchFirstShort` fetches the first Short's info; entering the section calls
`startShortsIfReady` → `onVideoItemClicked(first)`. In the player, `schedulePrefetch` (300 ms after load) chains
`prefetchNext`: one format fetch at a time, up to 4 ahead, each one appended to the `ConcatenatingMediaSource`
(`ExoPlayerController.enqueueShortInt`) so ExoPlayer preloads it. A swipe is `seekToDefaultPosition(index)`
(`switchToQueued`, `ExoPlayerController.java:~280`).

---

## Ranked list

Gain/risk are my estimates. Effort: S = under 1 h, M = a few hours, L = a day or more.

### 1. Format-info cache lookup happens *inside* the global fetch lock (all paths) — biggest cheap win
- **Where:** `MSC/service/internal/FormatInfoWrapper.kt` `getFormatInfo()` takes `synchronized(FormatFetchLock.LOCK)` and only then
  `getCachedFormatInfo()` runs inside `getFormatInfoLegacy/Innertube`.
- **Now:** a click on an already-prefetched video still waits for any fetch that is running at that moment (hover prefetch of
  another card, the Shorts lookahead chain, the history update `updateHistoryPosition` → `getFormatInfo`, the translation-language
  thread). Each one holds the lock for a full fetch (1–3 s).
- **Change:** check the cache first, outside the lock (`getCachedFormatInfo` is already guarded by its own monitor); take the lock
  only on a miss, and re-check the cache after acquiring it (double-checked).
- **Saves:** 0 s when idle, 0.5–3 s whenever something else is fetching (typical when you click right after hovering/scrolling,
  and on Shorts swipes while the lookahead chain is running: `loadFormatInfo` for a prefetched Short that fell out of
  `mPrefetchedFormats` still goes through the wrapper).
- **Risk:** low. The cache already has `isCacheActual()` and a 30 min age check. Keep returning the same object (shared mutable
  `MediaItemFormatInfo`, `getVideo().sync()` mutates the Video, not the info).
- **Effort:** S.

### 2. The user's fetch waits behind a background fetch (priority)
- **Where:** `FormatFetchLock` (plain monitor, unfair), callers: `BrowsePresenter.prefetchFocused` (`:458`),
  `VideoLoaderController.prefetchNext` (`:~700`), `VideoInfoService.fetchTranslationLanguagesInBackground`.
- **Now:** fetches are never cancelled (cancelling caused 403s), so a click during a 2 s hover prefetch waits up to 2 s.
  `prefetchFocused` also queues the *latest* focused card behind the running one (`mFocusPrefetchWanted`), so fast scrolling
  runs several useless fetches in a row and the clicked card queues behind them.
- **Change:** (a) a fair "priority" lock: a foreground request (`loadFormatInfo`) jumps ahead of queued background ones
  (e.g. a counter `foregroundWaiting`; background fetches check it before starting and wait). Do not interrupt a running one.
  (b) In `prefetchFocused`, only queue the newest wanted card and drop it if the focus moved on (already done through
  `wanted == mCurrentVideo`; add a minimum dwell for a *queued* one too).
- **Saves:** 0–2 s in the "click while something is fetching" case.
- **Risk:** low if the running fetch is never interrupted; a starving background fetch is harmless.
- **Effort:** S–M.

### 3. First-ever translation-language fetch holds the lock for a WEB request + BotGuard
- **Where:** `MSC/videoinfo/V2/VideoInfoService.java` `fetchTranslationLanguagesInBackground` (synchronized on `FormatFetchLock.LOCK`).
- **Now:** after the first video of a session with auto-subtitles, a background thread takes the lock for 1–3 s (comment says
  "an extra WEB request plus a BotGuard run"). A click in that window waits. It re-runs every 10 min while the list has < 100 entries
  (`mCachedTranslationLanguages.size() < 100` — if a client never returns ≥ 100 languages it retries forever, each time under the lock).
- **Change:** run it once at idle (e.g. 30 s after app start, or only when no foreground fetch happened for N s), persist the list
  to prefs so it survives restarts; stop retrying if the result is non-null but < 100.
- **Saves:** 1–3 s once per session (and every 10 min if the < 100 case applies). **(unverified)**: whether the < 100 case happens in practice, check the log for "Enable full list of auto generated subtitles".
- **Risk:** low. **Effort:** S.

### 4. Format fetch itself: parallelize the network part, keep only the shared-state part serialized
- **Where:** `FormatInfoWrapper.getFormatInfo` (lock around everything), `VideoInfoService.firstInfoWith` (clients tried one after another: TV first, then VISIONOS, TV_DOWNGRADED, WEB, … each a full round trip when unplayable), `AppService` decipher, `PoTokenGate`.
- **Now:** one fetch = player request (~0.3–0.8 s) + poToken + n/sig solve, all under one lock; Shorts lookahead of 4 = 4 serial fetches
  (≈ 4–8 s before the 4th is ready; a user who swipes every 1–2 s catches up with the chain and hits "Waiting for the running prefetch" or a cold fetch).
- **Change (in order of risk):**
  1. Do the HTTP player request outside the lock for the *current client* and only decipher/pot inside. Needs an audit of what is shared (`mUseAuth` is already thread-local; `resetClientPlaybackNonce`, `mActualInfoType`, `mNextInfoType`, PoToken caches are not).
  2. Prefetch the *next* Short's raw player response while the previous one is deciphering (pipeline of 2).
  3. For Shorts only: try a client that needs no poToken/cipher first (`ANDROID_VR`/`ANDROID_REEL` are in the code, commented out as "hangs"/"often hangs?"). If one is reliable for Shorts it would drop the solver and pot steps entirely.
- **Saves:** step 1/2: ~30–50 % of lookahead fill time. Step 3: potentially 1 s per Short, but I can't judge reliability from the code.
- **Risk:** HIGH for 1–2 (the earlier 403 bug came from exactly this shared state), MEDIUM for 3 (stream URLs from other clients may be throttled or have a different format set). Do 1–2 only with stress logging of 403s.
- **Effort:** L.

### 5. Start the format fetch on click, not after the player engine is initialized (not-prefetched videos)
- **Where:** `PlaybackPresenter.openVideo` (`:112`) → `startView` → fragment `onCreate/onStart/initializePlayer` (`PlaybackFragment.java:479`) → `onEngineInitialized` → `loadVideo` → `loadFormatInfo`. The fetch is only started at the end of this chain.
- **Now:** activity start + fragment create + `createPlayerObjects()` (ExoPlayer, `CustomOverridesRenderersFactory`, `LeanbackPlayerAdapter`, glue, media session, rows, clock, pixel ratio, double-tap) happens *before* the network request begins. On a TV that is typically 150–400 s ms **(unverified; the app logs timing, check "first frame" log lines)**, serial with the 1–2 s fetch.
- **Change:** in `PlaybackPresenter.openVideo` (or `VideoActionPresenter.apply`), fire `getFormatInfoObserve(videoId)` right before `startView` and discard the result (it lands in `FormatInfoWrapper`'s cache; same trick as the hover prefetch). Skip when the player is already running (then `loadVideo` runs at once anyway).
- **Saves:** the activity/player start time (~0.2–0.4 s) on every non-prefetched click (keyboard-fast clicks, search results, channel pages, notifications).
- **Risk:** low (same code path as the hover prefetch, no cancel). Must not run when a Shorts auto-start already did it.
- **Effort:** S.

### 6. Manifest parsing and media-source building run on the main thread
- **Where:** `processFormatInfo` (`VideoLoaderController.java:~387`) runs on the main thread (`RxHelper.fromCallable` observes on main) and calls `player.openSabr/openDash` → `ExoMediaSourceFactory.buildSabrMediaSource/buildDashMediaSource` → `SabrManifestParser.parse(formatInfo)` / `DashManifestParser2.parse(formatInfo)` (`ExoMediaSourceFactory.java:189,203,249,254`).
- **Now:** for a regular video the parser builds a representation per format (30+ with codecs, languages, HDR, segment info). For the Shorts lookahead it runs 4 times in one go when `enqueueShort` is called for several cached items in `prefetchNext` (the loop `continue`s over cached items). The code comment in `ShortsTransitionOverlay` says "the main thread can be busy ~70 ms at the switch", so main-thread time matters at swipe.
- **Change:** parse the manifest on the io thread inside the Rx chain (a small `ParsedFormatInfo` holder: format info + `SabrManifest`/`DashManifest` built before `observeOn(main)`), or at least cache the parsed manifest next to the format info so `enqueueShort`, `openQueued` fallback and the retry path don't parse twice. Then build the `MediaSource` on main (cheap).
- **Saves:** ~10–80 ms of main-thread time per video start / swipe (**unverified**; measure with `Trace.beginSection` or timestamps around `fromSabrFormatInfo`).
- **Risk:** low–medium: the manifest object must not be mutated by ExoPlayer (it is immutable in 2.10 DASH; check `SabrManifest`). **Effort:** M.

### 7. Swipe snapshot adds up to 150 ms before the player reset when the next Short is *not* preloaded
- **Where:** `PlaybackFragment.resetPlayerState` (`:1762`) → `ShortsTransitionOverlay.swipe` (PixelCopy, timeout `SNAPSHOT_TIMEOUT_MS = 150`, `:38`), `runAfterSnapshot` (`:319`) defers `mExoPlayerController.resetPlayerState()` until the copy is done.
- **Now:** only for not-preloaded Shorts (preloaded ones return early at `:1774`). The reset is delayed by the PixelCopy round trip (a few ms, but up to 150 ms if the main thread is busy). This is also when format info is being fetched, so the delay is usually hidden behind the fetch (1–2 s). Real cost only when the fetch was a cache hit.
- **Change:** start `loadFormatInfo` first (it is already posted), and let the reset wait on the snapshot only if the source will be opened before the snapshot is done. In practice nothing to gain unless the info is prefetched; low priority.
- **Saves:** ≤ 150 ms in rare cases. **Risk:** medium (the reset vs snapshot ordering was fixed in several review commits). **Effort:** M. **Skip unless item 1–5 are done.**
- Also: the PixelCopy `Bitmap` (half-size ARGB_8888, ~1 MB) is allocated per swipe (`:250`); reuse one bitmap (GC on a 2 GB TV box can cause a hiccup during the swipe animation). Effort S, gain small.

### 8. Shorts preloading: ExoPlayer only loads the next item when the current one is fully buffered
- **Where:** `FastStartLoadControl.shouldContinueLoading` (Shorts branch: loads until 180 s buffered or 128 MB), `ShortsQueue`, `ExoPlayerController.enqueueShortInt`.
- **Now:** a Short of 30–60 s at SABR/DASH quality is buffered in 1–3 s on a good link, then the next one starts. The load control is fine (continues past the buffer limit). The issue is on a slow link or with a long Short (up to 3 min): item N+1 starts only after N is complete, and N+2 only after N+1.
- **Change options:**
  1. Cap the *initial* quality of queued (not-current) Shorts lower (e.g. 360p/480p) so they finish faster. Needs a per-source track filter; the viewer's swipe sees a lower quality for ~1 s until the adaptive selection raises it (3 s `minDurationForQualityIncrease`, `PlaybackFragment.createPlayer:582`).
  2. Don't add Shorts longer than ~90 s to the queue ahead of shorter ones (they block the chain). The duration is known from the format info (`getLengthSeconds`).
  3. A second `ExoPlayer` instance as preloader: too big (decoder + memory on a 2 GB device). Not recommended.
- **Saves:** only matters on slow links (> 1.5 s per Short). **Risk:** medium (quality/track selection). **Effort:** M.

### 9. Shorts lookahead: sequential chain with a 300 ms start delay and per-step re-posting
- **Where:** `VideoLoaderController.schedulePrefetch` (`PREFETCH_DELAY_MS = 300`), `prefetchNext` (`:~688`), `isCurrentLoaded()` (retries up to 10 × 300 ms while `isActionsRunning()`).
- **Now:** after a swipe the chain restarts: 300 ms delay, then (if the current Short's own fetch is running) up to 10 × 300 ms retries. After each fetch `Utils.post(mPrefetchNext)`; fine. On swipe the next Short's info is usually already prefetched, so the chain restarts at the item after it.
- **Change:** keep the first prefetch delay (so the current Short isn't slowed), but when the current one came from the queue (`ShortsQueue.getQueuedFormatInfo` hit), start immediately (`isActionsRunning()` is false). Make the lookahead adaptive: depth 2 while the user is swiping fast, 4 when idle (saves lock time for the item the user will land on).
- **Saves:** 0.3 s of earlier prefetch after every swipe, which lets the chain stay ahead of a fast swiper. **Risk:** low. **Effort:** S.

### 10. Entering Shorts: first Short plays only after the feed is loaded *and* the info is fetched
- **Where:** `BrowsePresenter.onContentEntered` (`:~496`) / `onSectionClicked` / `prefetchFirstShort` (`:534`), `updateGridHeader` callback (`:~1005–1030`).
- **Now:** the feed loads when the sidebar item gets the focus, then `prefetchFirstShort` fetches the first Short. If the user presses OK/right before the feed is there (`mPendingShortsStart = true`), `prefetchFirstShort` is skipped (`mPendingShortsStart` check at `:537`), and the first fetch only starts in the player. Also `prefetchFirstShort` fetches only the first Short, not the 2nd.
- **Change:** (a) when the feed arrives while `mPendingShortsStart` is true, still start the prefetch (the player will wait for the running one through `mWaitingForPrefetchId`... but note that only works in `VideoLoaderController`; `BrowsePresenter`'s prefetch is invisible to it, so it simply waits on the lock, which is fine). (b) Also prefetch the second Short after the first (chain of 2), because the first swipe is the most visible. (c) Persist the last seen Shorts feed (ids) so entering the section can start from the previous feed while the new one loads (a fresh feed is a requirement though: `ShortsHistory` filters repeats).
- **Saves:** (a) up to the whole fetch (1–2 s) in the "pressed before ready" case; (b) removes the first-swipe cold start. **Risk:** low. **Effort:** S.

### 11. `PlaybackFragment.createPlayerObjects()` and the player activity startup
- **Where:** `PlaybackFragment.java:552-575`, `ExoPlayerController` ctor (`:79`), `ExoPlayerInitializer`.
- **Now:** all on the main thread, for every player open: `new ExoMediaSourceFactory` (data source factories, Cronet engine lookup `CronetManager.getEngine`), `TrackSelectorManager`, `PlayerData.getFormat` ×3, `LeanbackPlayerAdapter`, `VideoPlayerGlue` (builds actions/rows: many drawables), media session, `DateTimeView`, double-tap view.
- **Change:** measure first (`Log` timestamps between `onCreate` and `onEngineInitialized`). Candidates: create the `ExoMediaSourceFactory`/OkHttp/Cronet client once per process (static, it's the same client every time; `getMediaDataSourceFactory` creates new factories), lazy-create the glue's secondary actions, `createMediaSession` after first frame, `initializeGlobalClock/EndingTime/PixelRatio/DoubleTap` after first frame (`Utils.post` once `mFirstFrameListener` fires).
- **Saves:** probably 50–150 ms **(unverified)**. **Risk:** medium (order matters, "NOTE: position matters!"). **Effort:** M.

### 12. Regular-video buffering thresholds
- **Where:** `FastStartLoadControl` (`VOD_START_BUFFER_US = 1 s`, `VOD_MIN_BUFFER_US = 30 s`), `PlaybackFragment.createPlayer` (`AdaptiveTrackSelection(…, 3_000, 25_000, 25_000, 0.85f)`).
- **Now:** start at 1 s buffered. The 1.2 s "source open → first frame" you measured is dominated by the SABR init chunks (one per track, `DefaultSabrChunkSource.newInitializationChunk` `:550`, each a full request, `sabrStream.setInitLoad(true)`) plus the first media chunk, then 1 s of media.
- **Change:** (a) lower `VOD_START_BUFFER_US` to 0.5 s like Shorts (as long as the chunk is ≥ 0.5 s: SABR chunks are seconds long, so this gains only if a single chunk is partially delivered; gain is likely ≤ 100 ms, cheap to try). (b) Check whether video and audio init requests go out in parallel: if `sabrStream` serializes them (shared `SabrStream`), issue video+audio init in one request (SABR supports multiple formats per request, not verified in this code). **(unverified: needs the request timeline from the log "Load init chunk: track=…, rn=…")**.
- **Saves:** (a) ≤ 0.1 s; (b) up to one RTT (0.1–0.3 s). **Risk:** (a) low (rebuffer right after start on a slow link, `shouldStartPlayback` for rebuffering is unchanged); (b) high (SABR protocol).
- **Effort:** (a) S, (b) L.

### 13. Browse side: hover prefetch timing for regular videos
- **Where:** `BrowsePresenter.java:~447`: 600 ms dwell for regular videos, 400 ms for Shorts.
- **Now:** a user who scrolls and clicks within 600 ms gets no prefetch; fetch takes ~1–2 s now.
- **Change:** 600 → 350 ms (the fetch is cheap now that the runtime is reused), and prefetch the card *to the right/below* of the focused one at low priority after the focused one is done (typical D-pad moves are one step). Needs item 2 (priority) first, else a prefetch of the neighbour blocks the click.
- **Saves:** converts some 2 s cold starts to ~0.2 s. **Risk:** more requests per scroll (rate limiting / bot detection is a real risk with many fetches; keep at most 1 neighbour). **Effort:** S–M.

---

## Main-thread work that can block (summary)

| Where | What | Notes |
|---|---|---|
| `VideoLoaderController.processFormatInfo` → `ExoMediaSourceFactory.getSabrManifest/getManifest` | manifest parse for each started/enqueued video | item 6 |
| `VideoLoaderController.prefetchNext` | up to 4 `enqueueShort` → 4 parses in one callback when all 4 are already cached | item 6 |
| `PlaybackFragment.createPlayerObjects` | whole player UI + ExoPlayer creation | item 11 |
| `VideoLoaderController.loadVideo` | `ShortsHistory.markSeen` (`join` of all seen ids + `SharedPreferences.apply`, async write but the string build is on main; grows with the history) and two `Glide.preload` calls | small; cap the history list or build the string off-thread |
| `ShortsTransitionOverlay.swipe` | `Bitmap.createBitmap` per swipe | item 7 |
| `ExoPlayerController.scheduleItemEnd` | `new Timeline.Window()` per call, `timeline.getWindow()` | trivial |

## Things that look like bugs

1. **`VideoLoaderController.loadFormatInfo` error handler** (`:~370`): `getPlayer().showProgressBar(false)` — `getPlayer()` can be null by the time the async error arrives (user closed the player) → NPE inside an Rx `onError` (it would crash through `OnErrorNotImplemented`/the global handler). Capture `PlaybackView player = getPlayer()` and null-check.
2. **`VideoLoaderController.onMetadata` → `initRandomNext` → `MediaServiceManager.disposeActions()`**: `initRandomNext` calls `MediaServiceManager.instance().disposeActions()` unconditionally (also for Shorts, also when shuffle is off), which now disposes `mFormatInfoAction` (the change at `MediaServiceManager.java:312`). If anything started via `MediaServiceManager.loadFormatInfo` is running (e.g. `preloadNextVideoIfNeeded`, if it is called) it gets cancelled **in the middle of a fetch**, which is exactly what the 403 commit says must never happen. Move the `disposeActions()` call after the early-return checks (only when shuffle is really going to load metadata), and don't dispose format fetches there. **(unverified whether `preloadNextVideoIfNeeded` has callers; it has none in this file, the method looks dead, check and delete)**.
3. **`VideoLoaderController.loadFormatInfo` calls `disposeActions()` → `MediaServiceManager.disposeActions()`**, which also disposes the metadata/rows/uploads actions of the *browse* side if they are running (`mMetadataAction, mUploadsAction, mRowsAction, mSubscribedChannelsAction`). Harmless while the player is on top, but the Shorts auto-start from the sidebar means a row/uploads load of the browse screen can be cancelled by the first Short's load. Probably OK; just be aware.
4. **`FormatInfoWrapper.getFormatInfo`**: `ReloadPlaybackGate.reset(videoId)` in `finally` also runs for cache hits; not a bug but it means the cache hit path is not free of side effects (check what `reset` does if you move the cache check out of the lock, item 1).
5. **`FormatInfoWrapper.mCachedTimesMs`** is a plain `HashMap` read in `getCachedFormatInfo` under `synchronized(mCachedFormatInfos)`: consistent. `invalidateCache` also takes it. OK. But `mTryInnertubeFirst`, `mInnertubeResult` are plain fields written under `FormatFetchLock` and read in `switchNextFormat` (called from the error fixer, *not* under the lock): a data race, only matters when an error fix runs during a prefetch. Make them `@Volatile` or take the lock in `switchNextFormat`.
6. **`VideoInfoService.mActualInfoType/mNextInfoType`** are non-volatile and written by `persistRecentTypeIfNeeded` (under the lock) and `switchNextFormat/resetInfoType` (not under the lock, from the error fixer). Same race as 5.
7. **`VideoInfoService.fetchTranslationLanguagesInBackground`** retries every 10 min if the list is < 100 entries, under the global lock (item 3).
8. **`ShortsQueue.isActual`** uses `formatInfo.isCacheActual()` plus a 1 h max age while `FormatInfoWrapper` uses 30 min and `ShortsQueue.FORMAT_INFO_MAX_AGE_MS` is 1 h too (the comment says URLs live ~6 h, so both are safe). Inconsistent constants only; not a bug.
9. **`FastStartLoadControl.shouldRetainPlayedPeriods`** compares `getTotalBytesAllocated()` with `mShortsMaxBufferBytes` (≤ 128 MB; on a device with `deviceRam/18` smaller this is `Math.min(...)`): with retained 10 min back buffer and 180 s forward buffer, memory is bounded only by that byte cap, so a Short-heavy session can sit near the cap permanently, and then `shouldContinueLoading` returns false (the cap check) and **preloading of the next Short stops** until old data is released (back buffer is only trimmed by `getCurrentBackBufferDurationUs`, 10 min). Check with a long Shorts session: if "preloading" log lines stop appearing after ~15–25 Shorts, lower `SHORTS_BACK_BUFFER_US` to ~2–3 min or drop the oldest queue items (the queue never removes items, by design). **(unverified, memory-related; worth one test with the Allocator size logged).**
10. **`BrowsePresenter.prefetchFirstShort`** returns when `mPendingShortsStart` is true (see item 10); then the first Short is fetched in the player only. Not wrong, just slower.

## Suggested order

1. Item 1 (cache check before the lock) + item 5 (fetch on click) + bug 1 (NPE): all small, low risk, immediate gain.
2. Items 2, 3, 9, 10: lock priority, translation-language fetch, prefetch chain timing, Shorts entry.
3. Measure (add timestamps): activity start → `onEngineInitialized`, manifest parse time, init chunk timeline. Then decide on items 6, 11, 12.
4. Items 4 and 8 only with a 403 stress test in place (many Shorts swipes + hover scroll, watch for 403 in the log).

## What I did not verify

No code was run. I did not read `DashManifestParser2`/`SabrManifestParser` internals, the SABR stream request code beyond the chunk-source init path, `RestoreTrackSelector`, or the `VideoPlayerGlue` construction cost. The timing numbers above are estimates unless marked otherwise; the first thing to do is to add timestamp logs along the click path (click → `onCreate` → `onEngineInitialized` → format info back → `prepare` → first frame).
