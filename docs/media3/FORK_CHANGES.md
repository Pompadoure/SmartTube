# JoTube M3: changes inside the ExoPlayer fork (step 1b)

What JoTube/SmartTube changed in `exoplayer-amzn-2.10.6/` and what each change becomes in Media3.

**Method (evidence):** `diff -ruN -w` of `library/` and `extensions/` (tests excluded) against the real upstream
base, branch `amazon/r2.10.6` of `github.com/amzn/exoplayer-amazon-port` (commit e2a7039). So everything below is
a JoTube/SmartTube change; the Amazon port's own additions (`util.AmazonQuirks`, Fire TV codec/audio quirks) are
part of the base and handled at the end. `library/sabr` (fork-only module) is step 1c. 40 files differ, ~2.1k
changed lines, of which 726 are the new `DashManifestParser2`.

Verdicts: **built in** (Media3 does it already, nothing to port), **API** (doable with public Media3 API in app code),
**port** (custom class needed: copy/subclass in our code), **drop** (not used by JoTube / not needed on
Streamer + Mi Box). (?) = to be confirmed against the chosen Media3 version (1d) or while porting.

## A. Shorts preloading and the playlist queue (the big one)
| Change | What it does | Media3 |
|---|---|---|
| `PlayedPeriodsPolicy` (new interface, implemented by app `FastStartLoadControl`) | lets the app turn on: keep played periods, runtime back buffer, preload of queued periods | **drop** with the design change: Shorts move to `DefaultPreloadManager` + `PreloadMediaSource` (stage 4), so no single long timeline any more |
| `ExoPlayerImplInternal` (+~165 lines) | preloads the start (6 s) of up to 2 queued playlist periods while the playing one loads (enqueue early, continue the playing period when its buffer is low, buffered-duration based on the playing period); seek back into a retired (played) period is instant; keeps following periods on a forward seek; runtime back buffer | **drop / replaced**: `DefaultPreloadManager` preloads the next Shorts by ranking (`TargetPreloadStatusControl`, e.g. "prepared + 6 s buffered" for the next 1–2); the player swaps to the preloaded source with `setMediaSource`. Jump back = keep the previous Short in the preload manager too. Runtime back buffer: `DefaultLoadControl.Builder.setBackBuffer` is fixed per player (?) — only needed if the old "instant seek back" UX must stay |
| `MediaPeriodQueue` (+~112) | retired periods (max 2), `rewindToRetired`, `canEnqueueEarly`, retired window sequence numbers | **drop** (same reason) |
| `ChunkSampleStream`: seek to a period's clipped start = its first sample | without it a seek to the start of a preloaded SABR period threw the buffer away | **port if still needed**: Media3's `ChunkSampleStream` is internal API; with `PreloadMediaSource` the player starts at the preloaded position, so it may be unnecessary. Re-check in stage 4 with a test (SABR clips the first chunk to the load position) |

## B. Data sources and requests
| Change | Media3 |
|---|---|
| `DataSpec.httpRequestHeaders` (+ constructor), applied in `DefaultHttpDataSource`, `OkHttpDataSource`, `CronetDataSource` | **built in** (`DataSpec.httpRequestHeaders`, all Media3 HTTP data sources apply them) |
| `DataSpec.applyRangeQuery()` (googlevideo `&range=` throttle fix) | **drop**: present but never called (call commented out) |
| `DefaultHttpDataSource`: custom TLS/DNS via `NetworkHelpers` | **drop**: commented out in the fork |
| `CronetEngineWrapper`: QUIC/HTTP2/Brotli | **drop**: wrapper unused (app's `CronetManager` builds the engine); Media3 `CronetDataSource.Factory(engine, executor)` takes that engine |
| `DashManifest.visitorCookie` + `DefaultDashChunkSource` adds `Cookie` to segment requests (subtitles bot check) | **API**: set the cookie as a default request property on the HTTP data source factory or a `ResolvingDataSource` that adds it to timedtext/subtitle URLs |
| `CacheKeyFactory.maxDownloadParallelSegments`, `CacheUtil`, `SegmentDownloader` (parallel offline segment downloads) | **drop**: JoTube has no offline downloads (no `offline`/cache use in app code) |
| Gradle: product flavors, Java 8, `sharedutils`/`youtubeapi` deps in core/dash, `library-sabr` in `library-all` | **drop** (Maven artifacts) |

## C. Formats, manifests, parsers
| Change | Media3 |
|---|---|
| `Format.isDrc`, `Format.lastModified` (+ factory overloads, parcel) — "Sabr specific" | **port**: Media3 `Format` is final and has neither field. Options for 1c: keep them in the SABR manifest (per representation/track id) instead of on `Format`, or `Format.Builder.setCustomData` if the chosen version has it (?) |
| `DashManifestParser2` (new, 726 lines): builds a `DashManifest` straight from `MediaItemFormatInfo` (no XML), used by `ExoMediaSourceFactory.getManifest(formatInfo)` for the non-SABR DASH path | **port** to Media3 `dash.manifest` classes (`Representation.newInstance`, `SegmentBase.SingleSegmentBase`, `Format.Builder`, `Descriptor`). Mostly mechanical. Check in stage 2/3 whether the non-SABR DASH path is still reachable in JoTube (SABR is the default) |
| `HlsPlaylistParser`: frame rate in variant formats; format id without `:name` | frame rate **built in** (Media3 parses `FRAME-RATE`); format id: **API** — the app's track code must not depend on the id shape (check TrackSelectorManager in stage 3) |
| `HlsSampleStreamWrapper`: passes `frameRate` into `Format.createVideoContainerFormat` | **built in** |
| `MatroskaExtractor.read` made non-final | used by SABR (step 1c); Media3 `MatroskaExtractor.read` is not final (?), otherwise wrap it |
| `C` + `AudioAttributes`: allowed capture policy, spatialization behavior | **built in** |
| `ColorInfo`: fake HDR static info for HDR10/HLG BT.2020 without metadata (HDR10+ fix) | **port**: Media3 `ColorInfo` is immutable; set the fake `hdrStaticInfo` when the SABR/DASH format is built (`ColorInfo.Builder.setHdrStaticInfo`) |

## D. Renderers and decoders
| Change | Media3 |
|---|---|
| `MediaCodecRenderer`: transient MediaCodec `IllegalStateException` → re-init the codec in place (max 3 per 5 s), else `TYPE_UNEXPECTED` with a real message | **mostly built in** (?): Media3 handles `CodecException.isRecoverable/isTransient` and reports `MediaCodecDecoderException` with diagnostics. The app-side reason for `TYPE_UNEXPECTED` (ErrorFixerController must not overwrite the saved video preset on a renderer error) has to be kept in ErrorFixerController (stage 3) |
| `MediaCodecVideoRenderer.isBufferLate/isBufferVeryLate` made protected (app `TweaksMediaCodecVideoRenderer` frame-drop tweak) | **API**: override `shouldDropOutputBuffer(...)` / `shouldDropBuffersToKeyframe(...)` (protected in Media3) |
| `MediaCodecVideoRenderer.getCodecMaxInputSize` made protected (app: Shorts codec max values so one codec is reused across Shorts) | **API**: `getCodecMaxValues(...)` and `getCodecMaxInputSize(...)` are protected in Media3 (?); app `canKeepCodec` → `canReuseCodec(MediaCodecInfo, Format, Format)` returning `DecoderReuseEvaluation` |
| `VideoFrameReleaseTimeHelper`: swallow `IllegalStateException: Unable to locate mode -1` | **built in / re-check** (?): class rewritten as `VideoFrameReleaseHelper`; watch for the crash in device logs later |
| `DefaultRenderersFactory`: catch `NoClassDefFoundError` for missing extension renderers | **drop** (no extension renderers) |

## E. Subtitles and UI
| Change | Media3 |
|---|---|
| `WebvttCueParser`: decode HTML entities (`Html.fromHtml`) | **API**: post-process cues in the app before `SubtitleView.setCues` (or a `ForwardingPlayer`/cue listener) (?) — check first whether Media3's WebVTT parser already decodes entities |
| `WebvttSubtitle`: cap each cue at 10 s | **API/port**: app-side timer that clears a cue after 10 s, or a wrapped subtitle parser (`SubtitleParser.Factory`) (?) |
| `SubtitleView`: show only the last cue (overlapping subs) | **API**: filter the cue list in the app before `setCues` |
| `SubtitlePainter`: `PaddingBackgroundColorSpan` (padded background), 3-line height | **API**: wrap cue text in the span in the app (cue post-processing); line height via `SubtitleView` options or a copied painter (?) |
| `AspectRatioFrameLayout`: zoom in percent (`setZoom/getZoom`, used by VideoZoomManager) + never stretch vertical videos in FILL mode | **port**: own `ZoomAspectRatioFrameLayout` (copy of the Media3 class with the two changes) |
| `PlayerNotificationManager`: `FLAG_IMMUTABLE` | **drop** (not used by JoTube; Media3 already does it) |
| `LeanbackPlayerAdapter`: early return when there's no surface (memory leak fix); depends on the custom `:leanback-1.0.0` | **port** (?): check Media3's `LeanbackPlayerAdapter` (media3-ui-leanback, built against androidx leanback); if the leak or the custom leanback lib is a problem, keep a copied adapter in `smarttubetv` |
| `MediaSessionConnector`: no metadata provider → no notification; catch NPE | replaced anyway (see INVENTORY §7) |

## F. Amazon port (base, not JoTube changes)
`util.AmazonQuirks` (Fire TV: skip codec profile/level check, disable vsync snapping, Dolby/audio quirks) is used by
`CustomOverridesRenderersFactory` via two player tweak settings. Media3 has no counterpart. Johan's devices
(Google TV Streamer, Mi Box) are not Fire TV → **drop**; the two tweak settings become no-ops or are hidden
(stage 3). Skip-profile-level-check could be re-done with a custom `MediaCodecSelector` if ever needed.

## G. Not in the fork (app side, for reference)
- Tunneling: off in the app (commented out). Media3 default is off → keep the default.
- `FastStartLoadControl`: wraps `DefaultLoadControl`; the Media3 `LoadControl` interface changed (`PlayerId`,
  `Parameters` object) (?) → **port** in stage 2 as a thin wrapper, minus the `PlayedPeriodsPolicy` part.

## Summary for the plan
- Biggest win: section A (ExoPlayerImplInternal/MediaPeriodQueue/PlayedPeriodsPolicy, ~300 lines of risky player
  internals) disappears with `DefaultPreloadManager`.
- To port as own code: `DashManifestParser2`, the zoom `AspectRatioFrameLayout`, the HDR10+ `ColorInfo` fix,
  SABR's `Format` fields (with 1c), maybe `LeanbackPlayerAdapter` and the `ChunkSampleStream` seek fix.
- App-side API work: cookie header, renderer overrides, subtitle cue post-processing, ErrorFixerController
  behavior on codec errors.
- Dropped: offline download changes, unused range/TLS/Cronet wrapper code, Amazon quirks, gradle flavors.
