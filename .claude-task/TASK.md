# Fix the SHOULD-FIX findings 1 and 2 of the stage 2c review (JoTube M3, branch m3/stage2)

Read `CLAUDE.md`, `docs/media3/PROGRESS.md` and the review in `.claude-task/REVIEW.md` first. The app was ported from a
forked ExoPlayer 2.10.6 to AndroidX Media3 1.11.1. Fix ONLY these two findings, minimal and safe, and keep everything
compiling (CI builds `assembleStjohanRelease` on Java 17; you can't build here, so check every symbol, import and
signature by reading the code; Media3 classes are `androidx.media3.*`).

1. Network errors no longer reach the "Unable to connect to" handling in `ErrorFixerController` (finding 1). Make
   `ErrorFixerController` (or `ExoPlayerController.onPlayerError`, whichever is cleaner) treat Media3's
   `PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED` and `ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT` (and a
   nested `HttpDataSource.HttpDataSourceException` with type `TYPE_OPEN`) exactly like the old "Unable to connect to"
   case, so the network/date fix runs again instead of switching the client. Keep the old string check too.
2. Played Shorts are dropped immediately because Media3 asks `LoadControl.getBackBufferDurationUs(PlayerId)` (finding 2).
   In `FastStartLoadControl`, override `getBackBufferDurationUs(PlayerId playerId)` (and the no-arg variant if it exists
   in Media3 1.11.1's LoadControl interface; check `retainBackBufferFromKeyframe` too) to return the existing Shorts back
   buffer duration while Shorts mode is on, and the delegate's value otherwise. Use what the class already has for the
   Shorts mode and duration (read it). Don't implement preloading (that's stage 4).

Do not touch anything else. In `.claude-task/REPORT.md` list the exact changes (file:line) and anything you were unsure
about.
