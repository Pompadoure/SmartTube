# Foreground format-info fetches go first (AUDIT items 2 and 13)

Not built or run (no build here); checked by reading the code only.

## Design
- `FormatFetchLock` (MediaServiceCore) keeps the plain monitor `LOCK`. It gains:
  - `FOREGROUND_ACTIVE`, an `AtomicInteger`.
  - `FOREGROUND`, a thread-local flag.
  - `callForeground(Callable)`, which sets the flag around a call.
  - `beforeLock()`:
    - A foreground thread increments the counter and returns `true`.
    - A background thread polls every 50 ms while the counter is above 0, for at most 4 s, and returns `false`. It holds nothing while it waits.
  - `afterFetch(boolean)`, which decrements the counter for a foreground thread.
  - `awaitForeground()`, the wait loop on its own.
- `FormatInfoWrapper.getFormatInfo`:
  - The cache check still comes first and a hit never waits.
  - `beforeLock()` runs before `synchronized(LOCK)`.
  - `afterFetch()` runs in an outer `finally`.
  - So the counter covers the foreground fetch's wait and its run.
- `MediaItemService.getFormatInfoObserveForeground(videoId)` is new. `YouTubeMediaItemService` implements it as `RxHelper.fromCallable(() -> FormatFetchLock.callForeground(() -> getFormatInfo(videoId)))`.
  - The flag is set inside the callable, on the io thread that calls `getFormatInfo`, so the thread-local is correct.
  - No other signatures changed.
- The translation-language thread (`VideoInfoService.fetchTranslationLanguagesInBackground`) calls `FormatFetchLock.awaitForeground()` before taking the monitor, so it also yields.
- Foreground callers:
  - `VideoLoaderController.loadFormatInfo`.
  - `PlaybackPresenter` click prefetch.
- All other callers are unchanged and count as background: hover prefetch, Shorts lookahead, first/second Short, `StreamReminderService`, `MediaServiceManager`.
- Hover dwell for regular videos goes from 600 to 350 ms (`BrowsePresenter`).

## Why it can't deadlock or starve the foreground
- Background threads wait only in a sleep loop that holds no lock, so a waiter never blocks the monitor holder.
- The counter is decremented in a `finally`, so an exception or a disposed subscription can't leave it raised.
- Foreground threads never wait on the counter. They only contend for the monitor, which is held for one fetch at a time.
- A background fetch that is already running or already inside `synchronized` is never interrupted. The foreground fetch waits at most for that one.
- The wait is bounded at 4 s, so a stuck counter can't block background fetches for good.
- Race window: a background thread that checked the counter just before a foreground one registered can still win the monitor. The foreground then waits for that single fetch, which is the same as before this change.
- The foreground wait is therefore at most one running fetch, never a queue of them.

## Files changed
- `MediaServiceCore/mediaserviceinterfaces/.../MediaItemService.java`
- `MediaServiceCore/youtubeapi/.../service/YouTubeMediaItemService.java`
- `MediaServiceCore/youtubeapi/.../service/internal/FormatFetchLock.java`
- `MediaServiceCore/youtubeapi/.../service/internal/FormatInfoWrapper.kt`
- `MediaServiceCore/youtubeapi/.../videoinfo/V2/VideoInfoService.java`
- `common/.../VideoLoaderController.java`
- `common/.../PlaybackPresenter.java`
- `common/.../BrowsePresenter.java`

## Risks
- `MediaItemService` has a new abstract method. Only `YouTubeMediaItemService` implements it in the repo; a mock elsewhere would fail to compile.
- Background fetches are delayed by up to 4 s while the player fetches. This is intended, and the lookahead and hover prefetch resume afterwards.
- The first `warmUpPoToken` call at app start still goes straight to the monitor. It is not marked foreground and I left it unchanged.
- The 350 ms dwell means more hover fetches while scrolling slowly, which raises the request rate a little. Each one yields to a click.
- The "queue only the newest focused card" part of item 2(b) and the neighbour prefetch from item 13 were not in scope and are not done.
