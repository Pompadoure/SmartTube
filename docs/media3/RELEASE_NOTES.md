# JoTube M3 – release notes (draft for Johan)

JoTube M3 is JoTube on AndroidX Media3 1.11.1 instead of the forked ExoPlayer 2.10.6 (app id `org.smarttube.johan.media3`,
name "JoTube M3", pre-release `latest-media3`). It installs next to JoTube. **Nothing has been installed on any device by Claude.**

## What changed
- Player: Media3 ExoPlayer, new module `media3-sabr` (SABR on Media3), toolchain AGP 8.13 / Gradle 8.14 / compileSdk 36 / minSdk 23 / targetSdk 34.
- Shorts: `DefaultPreloadManager` preloads the next Shorts (next 6 s, +2 3 s, previous kept); switching plays the preloaded source (no playlist, no timeline sync).
- Start: the saved position is passed with the source (no seek after the tracks are known); JoTubeTiming log lines mark regular start and Shorts switch times.
- Subtitles: WebVTT fixes (HTML entities, 10 s cue cap) kept via a parser factory; last cue only.
- Auto frame rate strategy off, live speed control fixed at 1x, BEHIND_LIVE_WINDOW recovery, codec errors mapped as before.

## Known differences / no-ops
- Audio sync fix and the AmazonQuirks settings do nothing (Media3 has no equivalent).
- Only the `stjohan` flavor builds on this branch.
- Played Shorts more than 2 behind are dropped from the preload manager; swiping back further fetches normally.
- Open, not done on purpose: skipping a no-longer-wanted foreground format fetch (needs device evidence; fetches are never cancelled half-way).
