# JoTube M3 step 3b: the fork's subtitle fixes on Media3 1.11.1 (branch base: m3/stage3)

Read `CLAUDE.md`, `docs/media3/PROGRESS.md`, `docs/media3/FORK_CHANGES.md` (section E) and `docs/media3/SABR_PORT.md`
("Subtitles") first. The app was ported from a forked ExoPlayer 2.10.6 (`exoplayer-amzn-2.10.6/`, still in the repo as
reference, not built) to AndroidX Media3 1.11.1 (`androidx.media3.*`). Subtitles are now parsed at extraction time
(Media3 default; SABR does the same in `media3-sabr`, see `DefaultSabrChunkSource`), so the cues reach the app through
`Player.Listener.onCues(CueGroup)` (`common/.../exoplayer/other/SubtitleManager.java`) and the app's `SubtitleView`.

The fork changed four things for subtitles (find each in `exoplayer-amzn-2.10.6/` by diffing the files named in
FORK_CHANGES §E against what stock code would do; the fork marks most changes with comments):
1. `WebvttCueParser`: HTML entities in cue text decoded (`Html.fromHtml`).
2. `WebvttSubtitle`: every cue shown at most 10 s.
3. `SubtitleView`: only the last cue shown when cues overlap.
4. `SubtitlePainter`: padded background (`PaddingBackgroundColorSpan`) and the 3-line height.

Port them to Media3 with public API only, in the app (`common/`), no copies of Media3 classes unless there is no other
way (then say why in the report):
- 1 and 3 (and 4's span): post-process the cue list in `SubtitleManager` before it reaches `SubtitleView.setCues`
  (`Cue.buildUpon().setText(...)`). First check whether Media3 1.11.1's `WebvttCueParser` already decodes entities
  (it handles `&amp; &lt; &gt; &nbsp;` itself; the fork added the rest): decode only what's left, never twice.
- 2: cues come with their times in Media3 (`CueGroup.presentationTimeUs`; the cue end time is not in `Cue`). Choose
  the smallest safe way (e.g. a Handler in SubtitleManager that clears the shown cues 10 s after they were set if no new
  CueGroup came, or a wrapping `SubtitleParser.Factory` set through `DefaultMediaSourceFactory`/the DASH and SABR chunk
  sources that shortens `CuesWithTiming.durationUs` to 10 s). Explain the choice.
- 4's line height: check what Media3's `SubtitleView`/`CanvasSubtitleOutput` offers (`setFractionalTextSize`,
  `setBottomPaddingFraction`, style); if 3-line height can't be done with public API, leave it and say so.

If the Media3 sources are reachable, verify signatures against them:
`git clone --depth 1 --branch 1.11.1 --filter=blob:none --sparse https://github.com/androidx/media /tmp/m3src && cd /tmp/m3src && git sparse-checkout set libraries/common libraries/exoplayer libraries/extractor libraries/ui`.
You can't build here: check every symbol, import and signature by reading code. Keep changes minimal; don't touch
unrelated code, MediaServiceCore or the submodules. No `@UnstableApi` lint problems: the app's packages already opt in
(check how, e.g. `package-info.java` or lint config, and follow it).

Write `.claude-task/REPORT.md` (Swedish is fine): each change (file:line), what each of the 4 fixes became, and every
point you were unsure about (mark UNVERIFIED).
