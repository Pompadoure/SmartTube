The foreground fetch for the video the user is waiting on now goes before any background fetch that hasn't started yet. I couldn't build or run anything here, so none of it is compiled or tested.

- **How it works:** `FormatFetchLock` keeps its monitor and gains a foreground counter. A foreground fetch raises the counter from before it waits for the lock until it finishes. A background fetch, before taking the lock, polls every 50 ms for up to 4 s while the counter is above zero. A running fetch is never interrupted.
- **Deadlock/starvation:** Background threads wait in a sleep loop that holds no lock. The counter is decremented in a `finally`. The 4 s cap means background fetches can't be blocked for good. The foreground fetch waits for at most the one fetch already running.
- **Flag plumbing:** I added `getFormatInfoObserveForeground(videoId)` to `MediaItemService`. `YouTubeMediaItemService` implements it by setting a thread-local flag inside the Rx callable, which is the thread that calls `getFormatInfo`.
- **Foreground callers:** `VideoLoaderController.loadFormatInfo` and the click prefetch in `PlaybackPresenter`. Everything else stays background, including the translation-languages thread, which now waits for a foreground fetch before taking the lock.
- **Hover dwell:** For regular videos it goes from 600 to 350 ms in `BrowsePresenter`.
- **Risk:** `MediaItemService` has a new abstract method, so any other implementer or mock would fail to compile. I found only `YouTubeMediaItemService` in the repo.
- **Not done:** The AUDIT's "queue only the newest focused card" and the neighbour prefetch weren't in scope. The `warmUpPoToken` call at app start isn't marked foreground either.

The full design, files changed and risks are in `.claude-task/REPORT.md`. Nothing was committed or pushed.
