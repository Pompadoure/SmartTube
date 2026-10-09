The sidebar warm-up is written but not built or run, since there's no toolchain here. I checked imports and signatures by reading the code. `BrowseFragment` is the only implementor of `BrowseView`. Nothing is committed, and I didn't touch MediaServiceCore, exoplayer or `.github`.

**How it works**
- **Cache:** while the focus is in the sidebar, `BrowsePresenter` loads content in the background and caches the emitted `MediaGroup`s, not the Observables. The Observables are cold and can be subscribed again, and row sections emit several lists, so every emission is stored.
- **Replay on focus:** when a section gets the focus and its entry is fresh, `updateSection` replays the cached groups through the existing grid/rows code. That keeps `filterNew`, continuation and the Shorts start logic unchanged, and the replay is synchronous, so there is no network wait.
- **Shorts:** the feed is loaded in the background with the same request and `filterNew`. Then the first and second Short's format info are prefetched through the existing code, which FormatFetchLock serializes and which is never cancelled. Their vertical thumbnails and blurred backgrounds are preloaded through a new `BrowseView.preloadShorts`. A warmed feed is used only if it is under 2 minutes old, and only once.
- **Other sections:** home rows, subscriptions, history, channels and the rest are loaded one at a time, nearest to the focused sidebar item first, and cached when the request completes. A cached section is shown immediately if it is under 3 minutes old. Thumbnails for the first 2 rows or about 12 cards go through a new `BrowseView.preloadCardThumbnails`, which uses the card presenters so the cache keys match.

**Triggers**
- `BrowseFragment` calls `onSidebarFocused()` when the headers transition ends with the sidebar shown, when a sidebar item gets the focus, and on resume with the sidebar showing.
- Entering the content stops the pass. Pausing the view (the player opening) stops it and clears the cache.

**Budget and rules**
- A pass runs at most once per 60 seconds.
- Each pass loads one Shorts feed, the 2 format fetches, and at most 6 other sections.
- Signed out, auth-only and personal sections are skipped.
- The cache is used only when the focus change triggers the load, so manual refresh and timers still hit the network.
- A Shorts feed left idle for over 2 minutes is reloaded on entry unless you are resuming from a Short.

**Risks**
- History or notifications can be up to 3 minutes stale if you don't leave the browse screen.
- The channels section in the default "new look" isn't warmed, because its multi-grid loading is tied to the live load.
- The least certain part is whether the headers transition fires at app start. If it doesn't, the sidebar-item focus callback covers it.

The full design, cache rules, request budget and risks are in `.claude-task/REPORT.md`.
