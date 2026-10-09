# Task: warm up content when the user is in the sidebar (JoTube)

Repo: personal SmartTube fork (Android TV YouTube client). Browse screen: `common/.../app/presenters/BrowsePresenter.java`
(sections, `onSectionFocused`, `updateCurrentSection` → `updateSection` → `updateVideoGrid`/`updateVideoRows`,
Shorts section logic: `onContentEntered`, `onSectionClicked`, `prefetchFirstShort`, `mShortsGroup`, `startShortsIfReady`,
`ShortsHistory.filterNew`), view side `smarttubetv/.../tv/ui/browse/BrowseFragment.java` (sidebar/headers, its
BrowseTransitionListener calls `onContentEntered`), card thumbnails `smarttubetv/.../tv/presenter/VideoCardPresenter.java`
+ `smarttubetv/.../tv/util/ThumbnailPreloader.java` (cache-key-exact preloads), Shorts backgrounds
`smarttubetv/.../tv/ui/playback/ShortsBackground.java` (`preload(context, ids)`). Read `git log --oneline -40`.

Today a section's content is loaded from the network only when its sidebar item gets the focus, and the first Short
starts loading only then too. The owner wants the UI to feel instant: as soon as the focus is in the sidebar
(headers shown: app start, BACK to the sidebar, return from the player), warm things up in the background:

1. Shorts: if the Shorts section exists and there is no fresh Shorts feed (younger than ~2 min), load the Shorts feed
   in the background (same request as the section, same `filterNew`), then prefetch the first and second Short's
   format info (existing prefetch code; fetches are serialized by FormatFetchLock — never cancel one), and preload
   their vertical thumbnails and blurred backgrounds (expose a hook from the view: e.g. a BrowseView method
   implemented in BrowseFragment that calls ShortsBackground.preload). When the user then enters the Shorts section
   and the warmed feed is fresh, use it immediately (no reload) so the first Short starts at once. Keep the existing
   "fresh feed on every entry" idea: the warm-up itself is the refresh; don't reuse a feed older than ~2 min.
2. Other video sections (home rows, subscriptions, history, channels... grid and row types; not settings): load the
   first page of each in the background, nearest to the focused sidebar item first, one request at a time, and keep
   it in a small per-section cache with a timestamp. When such a section gets the focus and its cache is younger
   than ~3 min, show the cached content immediately (no progress bar / network wait); otherwise load as today.
   Also preload the thumbnails of the first ~2 rows/~12 cards of the warmed sections through a BrowseView hook that
   uses VideoCardPresenter/ThumbnailPreloader so the cache keys match exactly.
3. Throttle: at most one warm-up pass per ~60 s; stop when the user leaves the sidebar into content or opens the
   player; don't warm sections that need auth when signed out; never block the main thread; no change to the normal
   visible flows except using fresh cached data. Be careful with the Rx Observables (cold? re-subscribable? rows
   sections may emit several times) — check how they are built (`mGridMapping`, `mRowMapping`) and cache the emitted
   MediaGroup(s), not the Observable.
4. Small, commented `// JoTube:` changes, Java 8, no new dependencies. Don't touch MediaServiceCore, exoplayer, .github.
You can't build or run here: be precise (imports, interface implementors — check every implementor of BrowseView).
In REPORT.md: design, the trigger points, cache rules, request budget per sidebar visit, risks.
