# Shorts format-info fetch: report

Nothing was built or run here. Everything below comes from reading the code.

## One `getFormatInfo(videoId)` for a Short, in order
1. `FormatInfoWrapper.getFormatInfo`: takes `FormatFetchLock` and checks the 6-entry cache. This is a cache miss for prefetch.
2. `getFormatInfoLegacy` → `VideoInfoService.getVideoInfo`. It starts at `mNextInfoType`, which is null, so at `VIDEO_INFO_TYPE_LIST[0]` = TV. It falls through the list while the result is unplayable (`firstPlayable`, two passes).
3. Per client tried:
   - Build the query: `PoTokenGate.getPoToken` (TV = session token, cached; WEB = BotGuard mint per videoId), visitor data, reload token.
   - Send one POST `/player`.
4. `applyFixesIfNeeded`:
   - Extra iOS HLS request if extended formats are broken (setting) **or the storyboard is broken**.
   - The WEB request for subtitles is already skipped for Shorts and cached (earlier patches).
5. `transformFormats` → `decipherFormats` → `AppService.bulkSigExtract` → `getPlayerDataExtractor` (cached, 10 h) → the solver:
   - TV client = `TclChallengeProvider.solveN`.
   - Other clients = `V8ChallengeProvider.bulkSolve`.
6. The poToken is fetched again for the final URLs. This hits the cache.

**Slowest step (found by reading, not measured):** the solver step (5).
- On every call it created a new V8 runtime.
- It evaluated polyfill, meriyah, astring and the solver (about 200 KB of JS).
- It read the preprocessed player (megabytes) from disk and put it through Gson.
- It parsed that player in V8 (`new Function`), then disposed the runtime.
- All of this was repeated for every Short, and for the TV client on every fetch.
- It is probably the largest share of the 2.5–4.5 s on that device. It is done under the global lock.

## Changes
1. **Solver V8 runtime kept alive between fetches, with the parsed player cached in JS.** This covers both `TclChallengeProvider` (the default TV client) and `V8ChallengeProvider` (web clients).
   - A new request type `cached` (no player payload) reuses the n-function/solvers of the last loaded player.
     - `tcl.solver.js`: the n-function is cached.
     - `yt.solver.core.js`: the solvers are cached. This also keys on the `player_key` (the player URL), so a different player can never be answered from the wrong cache.
   - `V8ChallengeProvider` and `JsRuntimeChalBaseJCP` follow the same logic. The Kotlin side only sends `cached` if the same player URL was loaded into the live runtime.
   - On any error, an unknown-type response, or a response/size mismatch, it falls back to the old full path (disk preprocessed script → full player).
   - On any V8 exception the runtime is disposed and recreated next time.
   - New `IdleReaper` (daemon thread) disposes the runtimes after 2 idle minutes, so memory is not held for the whole session.
2. **Skip the extra iOS HLS request when the only reason is a broken storyboard (seek preview) for Shorts** (≤180 s, same `isShortVideo` rule as the subtitles skip). One request (~0.5–1 s) less per Short. The extended-HLS case is untouched.

## Why it is safe
- The same JS functions are called with the same inputs; only the setup is reused. The n-function is pure for a given player build.
- Everything still runs under `v8Lock` (and inside `FormatFetchLock` for fetches). No new parallelism, no cancelling.
- The cache is keyed by player URL in both JS and Kotlin. A new player build → full path.
- Fallback to the previous behaviour on every error path.

## Expected gain
Probably 1–2.5 s of the 2.5–4.5 s per Short, plus about 1 s when the storyboard request was being made. This is an estimate; please verify with the logs. The first fetch after the 2-minute idle timeout is as slow as before.

## Not changed, and why
- **Client ordering ("try the last working client first")**: start-client logic cannot tell a Short from a normal video before the request, and starting from the last client would change which client regular videos use (TV/auth for Premium). I don't know from the log whether TV is playable for Shorts. If the logs show a fallback past TV for every Short, a Shorts hint passed from `getFormatInfoObserve` would be the next step.
- **Batching**: no multi-video player endpoint was found in this code base.
- **WEB poToken**: minted per videoId by BotGuard. It is only used by the WEB-family clients; the TV session token is already cached.

## Risks
- J2V8 thread affinity: the runtime is now used across threads. `withLock` is used each time, as before for disposal. After the first locked run the locker is released, so other threads can acquire it. If a device throws "Invalid V8 thread access", the code disposes and recreates the runtime (slower, but not broken).
- Memory: a kept-alive runtime plus player (some MB) for up to 2 minutes after the last use.
- The Kotlin files need a compile check: `IdleReaper.kt` is new and `JsRuntimeChalBaseJCP.kt` changed. I could not build here.
