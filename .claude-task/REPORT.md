# Sidebar warm-up (JoTube)

Not built or run (no toolchain here). Imports and the `BrowseView` implementors were checked by reading: `BrowseFragment` is the only implementor.

## Design
While the focus is in the sidebar, `BrowsePresenter` loads content in the background and caches the **emitted `MediaGroup`s**. It does not cache the Observables. The Observables from `RxHelper.create` are cold and re-subscribable, and row sections emit several lists, so the cache stores every emission.

When a section gets the focus and a fresh entry exists, `updateSection` swaps the Observable for `Observable.fromIterable(cachedGroups)`. Everything else is unchanged: the same `updateVideoGrid`/`updateVideoRows`, `filterNew`, continuation, `mShortsGroup`, `startShortsIfReady` and `prefetchFirstShort`. The replay is synchronous, so there is no network wait. The progress bar flips on and off in the same frame.

- **Shorts:** the warm-up subscribes to the Shorts grid Observable, applies `ShortsHistory.filterNew`, and caches on the first emission. It then calls the existing Shorts prefetch (`startShortsPrefetch`, first then second Short, serialized by FormatFetchLock, never cancelled). It also calls `BrowseView.preloadShorts`, which preloads the vertical thumbnails (same request as `VideoLoaderController`) and `ShortsBackground.preload`. A Shorts cache entry is used once and then removed, so the next entry gets a fresh feed.
- **Other sections:** types GRID and ROW with a mapping in `mGridMapping`/`mRowMapping`. The cache entry is stored when the Observable completes. The first 2 rows (or the first 12 cards of a grid) are passed to `BrowseView.preloadCardThumbnails`. `BrowseFragment` implements it with `VideoCardPresenter`/`ShortsCardPresenter.preload`, which goes through `ThumbnailPreloader` and gives the same cache key as the card bind.
- **New files:** none. Changes are in `BrowsePresenter`, `BrowseView` (2 methods) and `BrowseFragment` (the 2 hooks plus 3 trigger calls).

## Triggers
`BrowsePresenter.onSidebarFocused()` is called from `BrowseFragment` in three places:

1. `onHeadersTransitionStop(withHeaders = true)`: BACK to the sidebar, return from the Shorts player.
2. The section-focused callback, when headers are showing and no focus-on-content is pending: app start and moving between sidebar items.
3. `onResume`, when headers are showing: return from the player.

Stop and cleanup:
- `onContentEntered(true)` stops the pass.
- `onViewPaused` stops the pass and clears the cache.
- `onViewDestroyed` stops the pass and clears the cache.
- Account change clears the cache. Channel-sorting and playlist-style changes drop that section's entry.
- Format prefetches are never cancelled. Content requests are disposed, which is safe for them.

## Cache rules
- **Shorts:** usable if younger than 2 min, and removed once it is used. A warm-up runs only if there is no entry younger than 2 min.
- **Other sections:** shown at focus if younger than 3 min. A section is re-warmed only when its entry is 2 min old or older.
- **Focus only:** the cache is used only when `onSectionFocused` (or the Shorts `onContentEntered` reload path) triggered the update, via `mAllowWarmCache`. Manual refresh, timers and `onShortsPlayerClosed` always load from the network.
- **Idle Shorts feed:** the Shorts feed already loaded for the focused item is not reused after 2 min (`mShortsFeedMs`, counted from the warm-up time). `onContentEntered` reloads it unless the user is resuming from a Short.
- **Format-info marker:** the warm-up's Short format fetch is remembered for 2 min (`mWarmPrefetchedId`), so `prefetchFirstShort` on replay doesn't refetch the first Short. That would evict the second from the service's one-video cache.

## Request budget per sidebar visit
- Passes are limited to one per 60 s (`mLastWarmUpMs`, reset in `onViewResumed`) and run strictly one at a time.
- One Shorts feed request, plus 2 format fetches (the existing ones).
- At most 6 other sections, nearest to the focused sidebar item first. The focused section is skipped because its normal load runs.
- Signed out: sections with `isAuthOnly`, and Home/Subscriptions/Notifications/My videos/Channels/Playlists/History, are skipped.
- A section that gets the focus while it is being warmed cancels the warm request, and the visible load takes over. If the Shorts feed was already cached at the first emission, it is used.

## Risks
- **Stale history/notifications:** cached content can be up to 3 min old, for example the history after watching something without leaving the browse screen. Opening the player clears the cache.
- **Not warmed:** MULTI_GRID sections (channels in the default "new look") are not warmed. Their `onVideoItemSelected` logic is tied to the live load.
- **Home cache entry:** the Home entry (the whole `getHomeObserve`) may be large. Only the emitted groups are held, and only until the view pauses or destroys.
- **Hung warm request:** a warm request that hangs without an error would block later passes until content is entered or the view pauses. The network timeout should end it.
- **Replay and continuation:** continuation requests after a replay use the cached `MediaGroup`'s next-page key. The service does not mutate its input, but I only read that, I did not run it.
- **Mutated row lists:** `filterHomeIfNeeded` removes from the list in place. The replay copies the lists.
- **Not tested on device:** timing of the transition-listener trigger on app start is the least certain part. If `onHeadersTransitionStop(true)` does not fire there, the section-focus callback covers it.
