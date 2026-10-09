I wrote the audit to `.claude-task/REPORT.md` and changed no code. I only read the code and ran nothing on the device. Time savings are estimates unless the report marks them as measured. Several items are marked "unverified" and need a timing log first.

**Best speed-ups, easiest and safest first:**
1. **Cache check inside the fetch lock.** `FormatInfoWrapper.getFormatInfo` takes the global lock before it looks in the cache. A prefetched video, or a queued Short, therefore waits behind any fetch already running. Checking the cache before taking the lock is a small, low-risk change. It saves anywhere from 0 to about 3 s, depending on what is fetching at that moment.
2. **Start the fetch on click.** For videos that weren't prefetched, the format fetch only begins after the activity and player objects exist. Starting it in `PlaybackPresenter.openVideo`, the same way the hover prefetch works, should save roughly 0.2–0.4 s. That figure is a guess.
3. **Fetch priority.** A foreground fetch could go ahead of queued background ones. The background translation-languages request also holds the lock for 1–3 s and can retry every 10 minutes, so it should run once at idle.
4. **Shorts prefetch chain.** It starts 300 ms after load, then fetches one Short at a time. `prefetchFirstShort` is skipped when the user enters the section before the feed has loaded.
5. **Main-thread work.** Manifest parsing and media-source building run on the main thread when a video starts or a Short is queued. Moving them off should save about 10–80 ms, which is unmeasured.

**Higher risk, only with a 403 stress test in place:**
- Running the player request outside the lock.
- Trying a client that needs no poToken or cipher for Shorts.
- Lowering the start quality of queued Shorts.

**Possible bugs:**
- The error handler in `loadFormatInfo` calls `getPlayer()` without a null check, so it can throw a NullPointerException.
- `initRandomNext` calls `MediaServiceManager.disposeActions()` even when shuffle is off. That now cancels running format fetches mid-flight, which the earlier 403 fix says must never happen.
- A few fields in `FormatInfoWrapper` and `VideoInfoService` are read and written from different threads without synchronisation.
- Shorts preloading may stall in a long session. Retained played Shorts (10 minutes of back buffer) could hold the allocator at its byte cap, which stops loading of further Shorts. I haven't tested this.

The report also lists the main-thread work that can block, a suggested order, and what I did not read.
