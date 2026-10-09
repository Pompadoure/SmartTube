# Task: implement the low-risk speed-ups from the audit (JoTube)

Read `.claude-task/AUDIT.md` (a speed audit of this repo written by another Claude run; file:line references are
approximate) and `git log --oneline -40`. MediaServiceCore is already patched with patches 0001..0006 in this run;
edit its files directly (the workflow turns your edits there into a new patch file).

Implement exactly these items, each small and commented with `JoTube:`:
1. Audit item 1: in FormatInfoWrapper, check the format-info cache BEFORE taking FormatFetchLock (double-checked:
   check again after acquiring it). Read what `ReloadPlaybackGate.reset(videoId)` does and keep the behavior correct
   for cache hits (only call it where it was effectively needed).
2. Audit item 5: start the format-info fetch at the click, before the player activity/engine is created
   (fire-and-forget `getFormatInfoObserve(videoId)` like the hover prefetch, result lands in the cache), but not when
   the player is already open, not for live/upcoming, and not twice for the Shorts auto-start. Never cancel it.
3. Audit bug 1: null-safe `getPlayer()` in the async error handler(s) of VideoLoaderController.
4. Audit bug 2: make sure `initRandomNext` / any `disposeActions()` can never cancel a running format-info fetch;
   delete `preloadNextVideoIfNeeded` if it really has no callers.
5. Audit item 3: the translation-language background fetch: no retry loop when the list is non-null but < 100, and
   don't start it while a foreground fetch is waiting/running if that's easy; keep it under the lock.
6. Audit item 9: start the Shorts prefetch chain immediately (no 300 ms delay) when the current Short came from the
   queue/prefetch cache (no own fetch running).
7. Audit item 10 (a)+(b): Shorts entry: prefetch the first Short even when the start is pending, and the second
   Short right after the first.
8. Audit item 7 (bitmap only): reuse the PixelCopy bitmap in ShortsTransitionOverlay when the size is unchanged
   (make sure a bitmap shown in the outgoing ImageView isn't overwritten while still visible — use two alternating
   bitmaps or allocate when in use).
9. Audit bugs 5/6: `@Volatile`/volatile for the fields written under the lock and read outside it.
10. Timing logs (Log.d, tag "JoTubeTiming"): click time, PlaybackFragment.onCreate, onEngineInitialized, format info
    received, source prepared (openSabr/openDash), first frame — each with ms since the click, for regular videos.
Do NOT do items 4, 6, 8, 11, 12, 13 or anything else. Don't change UI behavior. Java 8 / the project's Kotlin.
You can't build or run here: be precise with imports, types and nullability; a reviewer builds it.
In REPORT.md: per item what you changed (file), why it's safe, anything you skipped and why.
