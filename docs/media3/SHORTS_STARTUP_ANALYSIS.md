# Shorts Startup Analysis

## 1. Cold start format fetch delay (~6.4 s)

**Evidence (Code):**
- In `BrowsePresenter.java:1120`, the prefetch is triggered when focusing a video (`startShortsPrefetch`).
- `FormatFetchLock.java` ensures fetches are completely serialized and running fetches are never interrupted, as they count in `FOREGROUND_ACTIVE` (Priority logic added in patch 0008).
- `FormatFetchLock` also notes that `VideoInfoService`, `PoTokenGate`, and the decipher in `AppService` share state that isn't thread-safe, which causes 403s on parallel fetch.
- `PoTokenGate` is called under the lock before fetching (`AppService.java` or `MediaItemService.java`). Generating a PoToken (Proof of Origin) often requires solving a web challenge or WebView interaction, which can be extremely slow on initial load (cold start).

**Hypothesis/Guess:**
The FIRST format fetch wait is ~6.4s likely because the system has to generate the initial PoToken for the session inside the `FormatFetchLock`. All subsequent requests take ~50-600ms because the PoToken is cached or the challenge is already solved, bypassing the heavy initialization path.

**Suggested Change (Concrete):**
Move the `PoTokenGate.getPoToken(AppClient.TV)` initialization path (e.g. warming up the token generator) outside of the `FormatFetchLock` during the app's initial startup (e.g., during `BrowsePresenter` initialization or `MediaServiceManager` start), so it runs in the background before the user even clicks the first Short, reducing the on-click latency.

## 2. Preview start delay (~3 s)

**Evidence (Code):**
- In `ShortsPreview.java:311`, `onHandedOver()` calls `park()` and sets `mQuietUntilMs = android.os.SystemClock.uptimeMillis() + QUIET_AFTER_TAKEOVER_MS;`.
- `QUIET_AFTER_TAKEOVER_MS` is hardcoded to `2500` ms (`ShortsPreview.java:48`).
- In `scheduleRefresh()` (`ShortsPreview.java:183`), the refresh is delayed by `Math.max(START_DELAY_MS, quiet)` where `START_DELAY_MS` is `300` ms.
- Thus, the total delay before `refresh()` prepares the next player is `2500 + 300 = 2800` ms (close to ~3 s).
- `ShortsQueue.java:232` handles `releasePreloadHold` after `PRELOAD_HOLD_MAX_MS` (1500 ms) which might allow preloading, but `ShortsPreview` holds back its own ExoPlayer preparation anyway.

**Explanation:**
The preview explicitly waits for 2.5 seconds (`QUIET_AFTER_TAKEOVER_MS`) after handing over the previous player before it schedules the next prepare. The intent is to give the main player's decoders and network requests time to stabilize on low-end devices before starting a new ExoPlayer instance which would compete for CPU/Network.

**Suggested Change (Concrete):**
The `FormatFetchLock` already serializes the network request, preventing parallel requests. We can reduce `QUIET_AFTER_TAKEOVER_MS` to a smaller value (e.g., `1000` ms) or even `START_DELAY_MS` if the `FormatFetchLock` is sufficient to prevent network congestion. The `FormatFetchLock` guarantees that the preview's format fetch will just queue behind the main player's fetch if they overlap, safely avoiding 403s.

## 3. Live switch incoming player at "buffered to 0 ms, state 2" (BUFFERING)

**Evidence (Code):**
- In `ShortsLiveSwitch.java:175`, `mState = State.SLIDING;` is set.
- The logs show `state 2` which maps to `Player.STATE_BUFFERING`.
- At this point, `incoming.setTranslationY` and `mainSurface.setTranslationY` animate the slide-in (`ShortsLiveSwitch.java:180-208`).
- The incoming player's view is visible (`incoming.setAlpha(1f)`) while it is still in `STATE_BUFFERING` (`state 2`).

**Explanation/What is shown:**
When the swipe occurs, the `ShortsLiveSwitch` starts the slide animation immediately. If the spare player (`incomingPlayer`) hasn't finished buffering the first frame, it will be in `STATE_BUFFERING`. Since the view is sliding in but no frame is rendered yet, the user sees a black frame or a placeholder/thumbnail popping in if one is set on the `SurfaceView` or its container, until `onRenderedFirstFrame` fires and the player reaches `STATE_READY`.

**Suggested Change (Concrete):**
In `ShortsLiveSwitch.java:135`, the check `!preview.isReadyFor(next.videoId)` should theoretically prevent starting the live switch if the first frame isn't rendered. However, `isReadyFor` relies on `mFrameRendered`, which might be true from a previous prepare or there is a gap where `isReadyFor` returns true while the player is still buffering (e.g., due to a recent seek or resolution change).
Ensure `ShortsPreview.isReadyFor` also explicitly checks that `mPlayer.getPlaybackState() == Player.STATE_READY` before allowing the live slide to start. If it's not ready, `start()` should return `false` to fall back to the normal switch.
