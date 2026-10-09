# Task: foreground format-info fetches go first (JoTube)

Read `.claude-task/AUDIT.md` (items 2 and 13) and `git log --oneline -40`. MediaServiceCore is patched with 0001..0007
in this run (0007 already moved the cache check before the lock); edit it directly if needed.

All format-info fetches are serialized by `FormatFetchLock.LOCK` (a plain monitor) and must never run in parallel and
never be cancelled midway (that caused 403s). Background fetches: hover prefetch (BrowsePresenter.prefetchFocused),
Shorts lookahead (VideoLoaderController.prefetchNext), first/second Short (BrowsePresenter), click prefetch
(PlaybackPresenter), translation languages thread (VideoInfoService). Foreground: the player's own load
(VideoLoaderController.loadFormatInfo) — the video the user is waiting for.

## Implement
1. A foreground fetch waiting for the lock goes before any background fetch that hasn't started yet: e.g. a
   `FormatFetchLock` helper with a foreground-waiting counter; a background fetch, before taking the lock, waits
   while foreground fetches are waiting (bounded, e.g. re-check every 50 ms, max a few seconds). The running fetch is
   never interrupted. Plumb a "foreground" flag through the call path in the least invasive way (e.g. a separate
   service method or a thread-local set by the caller around the blocking call inside the Rx callable — check how
   getFormatInfoObserve builds its Observable and on which thread it runs).
2. Mark as foreground only VideoLoaderController.loadFormatInfo's fetch and PlaybackPresenter's click prefetch.
3. Hover prefetch dwell for regular videos 600 → 350 ms (BrowsePresenter) once 1 is in place.
Keep everything else unchanged. Small, commented (`JoTube:`), Java 8 / project Kotlin. You can't build or run here:
be precise. In REPORT.md: the design, why it can't deadlock or starve the foreground, files changed, risks.
