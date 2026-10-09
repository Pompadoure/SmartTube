I cut the per-Short fetch cost in two places, but nothing here was built or run, so the gain is an estimate. I expect roughly 1–2.5 s off each 2.5–4.5 s fetch, plus about 1 s when the extra storyboard request was being made.

**Where the time goes (from reading the code, not measured):** the n-parameter/signature solver. On every fetch it created a new V8 runtime. It re-evaluated about 200 KB of solver JS and read the preprocessed player (megabytes) from disk. Then it parsed that player and disposed everything again. The default TV client goes through the same path via `TclChallengeProvider`.

**What I changed:**
- **Solver reuse:** the V8 runtime now stays alive between fetches, and the parsed player is cached on the JS side.
  - Both solver scripts (`tcl.solver.js`, `yt.solver.core.js`) accept a new `cached` request.
  - The Kotlin side sends `cached` only if the same player URL is already loaded, and any error falls back to the old full path.
  - A new `IdleReaper.kt` frees the runtimes after 2 idle minutes. The first fetch after that is as slow as before.
  - Everything still runs one at a time under `v8Lock` and `FormatFetchLock`.
- **Storyboard request:** for Shorts (≤180 s), I skip the extra iOS HLS request when its only purpose is the seek-preview storyboard. This is in `VideoInfoService.java`.

**What I didn't change:**
- **Client ordering:** "try the last working client first" isn't implemented. The code can't tell a Short from a normal video before the request, so starting from the last client would change which client normal videos use, and TV/auth matters for Premium. I also can't tell from the log whether TV is playable for Shorts. If your logs show a fallback past TV on every Short, the next step is passing a Shorts hint from `getFormatInfoObserve`.
- **Batching:** I found no endpoint in this code base that returns several Shorts' streams at once.

**Main risk:** the V8 runtime is now used across threads. A device that throws "Invalid V8 thread access" would end up disposing and recreating the runtime, which is slower but not broken. The Kotlin changes (the new `IdleReaper.kt` and the edits to `JsRuntimeChalBaseJCP.kt`) need a compile check.

The report with the full request timeline is in `.claude-task/REPORT.md`. Nothing is committed.
