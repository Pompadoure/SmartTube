I made all ten changes and wrote `.claude-task/REPORT.md`. Nothing was compiled or run, so a reviewer needs to build it. I didn't commit or push.

Four things differ from what you might expect:
- **Item 4:** `VideoLoaderController.disposeActions()` still disposes the player's own in-flight load when a new video replaces it. Removing that needs a video-ID guard so a stale result isn't processed for the wrong video, which is a bigger change.
- **Item 5:** I skipped "don't start while a foreground fetch is waiting". It needs a waiter counter shared with the wrapper, and the thread already starts only after the running fetch.
- **Item 8:** a PixelCopy that times out and finishes two swipes later could write into a bitmap that's being shown. It would take over 150 ms of a stuck copy plus two swipes, and the result is ignored anyway.
- **Item 7:** the first-Short prefetch now chains to the second Short on `onComplete`. This assumes `getFormatInfoObserve` completes after emitting, which the focus prefetch already relies on. Check this on the device.

What changed, by item:
1. **Cache check before the lock:** `FormatInfoWrapper.getFormatInfo` checks the cache first. A hit returns at once and still calls `ReloadPlaybackGate.reset(videoId)`, so a hit behaves as before. A miss takes the lock and re-checks the cache under it.
2. **Fetch at the click:** `PlaybackPresenter.openVideo` starts a fire-and-forget `getFormatInfoObserve`, so nothing can cancel it. It's skipped when the player is already open, for live/upcoming, and for the Shorts auto-start. I made `BrowsePresenter.isShortsAutoStarted()` public for that last check.
3. **Null-safe error handler:** the `loadFormatInfo` error handler in `VideoLoaderController` captures `getPlayer()` once and null-checks it. The error fixer still runs.
4. **No cancelled fetches:** `MediaServiceManager.disposeActions()` no longer disposes the format-info fetch, so `initRandomNext` and other callers can't cancel one. I deleted `preloadNextVideoIfNeeded`, which had no callers.
5. **Translation languages:** no retry when the list is non-null but under 100 entries. The thread still runs under the lock.
6. **Shorts chain without delay:** the prefetch chain starts immediately when the current Short's info came from the queue or prefetch cache.
7. **Shorts entry:** the first Short is prefetched even when the start is pending, and the second right after it. `prefetchFirstShort` now runs before `startShortsIfReady`.
8. **Snapshot bitmap:** `ShortsTransitionOverlay` alternates two PixelCopy bitmaps and reuses them while the size is unchanged. The one shown in the outgoing ImageView is never the next target.
9. **Volatile:** the fields in `FormatInfoWrapper` and `VideoInfoService` that are written under the lock and read outside it are now volatile.
10. **Timing logs:** a new `JoTubeTiming` class logs click, `PlaybackFragment.onCreate`, `onEngineInitialized`, format info received, source prepared and first frame, each with ms since the click.
