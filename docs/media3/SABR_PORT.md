# JoTube M3: the SABR module on Media3 (step 1c)

What `exoplayer-amzn-2.10.6/library/sabr` (68 Java files, ~9k lines, 27 .proto files, 3 tests) uses from ExoPlayer and
what each piece becomes in Media3 (`androidx.media3.*`). Method: every `com.google.android.exoplayer2` import in
`src/main/java` counted and the call sites read (chunk creation, extractor wiring, media period/source, input
pipeline). Media3 API names below are for the current 1.x line; (?) = check against the exact version chosen in 1d.

## Shape of the module
The module is a copy of ExoPlayer's DASH module with the transport swapped for SABR (UMP over HTTP POST):

| SABR class | Modelled on (2.10.6) | Role |
|---|---|---|
| `SabrMediaSource` (618) | `DashMediaSource` | builds a `SabrTimeline` from an in-memory `SabrManifest` (no manifest download; the `Loader` only exists for the error thrower) |
| `SabrMediaPeriod` (711) | `DashMediaPeriod` | track groups per adaptation set, one `ChunkSampleStream<SabrChunkSource>` per selected track group, CEA-608/emsg embedded streams, `EventSampleStream` for manifest events |
| `SabrChunkSource` / `DefaultSabrChunkSource` (82 / 1095) | `DashChunkSource` / `DefaultDashChunkSource` | per track type: one POST per chunk to the SABR URL with a protobuf `VideoPlaybackAbrRequest` body (built per chunk from the manifest), `InitializationChunk` first, then `ContainerMediaChunk`s; subtitles as `SingleSampleMediaChunk` (GET + visitor cookie) |
| `SabrSegmentIndex`, `SabrWrappingSegmentIndex` | `DashSegmentIndex`, `DashWrappingSegmentIndex` | segment index (uses `extractor.ChunkIndex`) |
| `PlayerEmsgHandler`, `EventSampleStream` | same names in DASH | emsg/manifest events; only meaningful for live, and SABR live is not used (`VideoLoaderController`: SABR only when `!isLive()`) |
| `manifest/*` (2.6k) | `dash.manifest.*` | own copies (`SabrManifest` implements `FilterableManifest`), `SabrManifestParser` builds it from `MediaItemFormatInfo`, `SabrCdnSelector` |
| `parser/*` (~2.7k) | – (SABR specific) | `SabrStream` (UMP part state machine, request state, backoff, redirects, PO token status), `UMPDecoder/UMPInputStream`, parts/results/models, `SabrExtractorInput`, two extractor adapters |
| protos (27 files) | – | protobuf-lite, `java_package` = `com.google.android.exoplayer2.source.sabr.protos.*` |

How the bytes flow: `ContainerMediaChunk.load()` opens the POST on the `DataSource`, wraps it in a
`DefaultExtractorInput` and calls the chunk extractor → `SabrFragmentedMp4Adapter` / `SabrMatroskaAdapter`
(`extends FragmentedMp4Extractor` / `MatroskaExtractor`) swap that input for a `SabrExtractorInput`, which pulls UMP
parts from `SabrStream.parse(input)` and only exposes the bytes of `MediaSegmentData` parts to the real extractor.
This pattern ports 1:1: Media3's `BundledChunkExtractor.read(input)` also calls `extractor.read(input, positionHolder)`.

## Mapping, by ExoPlayer API used

### Mechanical (package rename + small signature changes)
| 2.10.6 | Media3 | Notes |
|---|---|---|
| `C`, `Format`, `FormatHolder`, `ParserException`, `SeekParameters`, `Timeline`, `MimeTypes`, `ParsableByteArray`, `TimestampAdjuster`, `UriUtil`, `Util`, `Assertions`, `Log` | `androidx.media3.common.*` / `common.util.*` | `Format.createXxxFormat(...)` factories → `Format.Builder` (SabrManifestParser, ~10 call sites) |
| `DrmInitData`, `SchemeData` | `androidx.media3.common.DrmInitData` | |
| `extractor.ExtractorInput`, `PositionHolder`, `TrackOutput`, `Extractor`, `ChunkIndex` | `androidx.media3.extractor.*` | `ExtractorInput` methods throw only `IOException` (no `InterruptedException`): `SabrExtractorInput`, `UMPDecoder`, `UMPInputStream`, adapters drop it. `TrackOutput.sampleData(DataReader, …)` replaces `ExtractorInput` there |
| `extractor.mp4.FragmentedMp4Extractor`, `mkv.MatroskaExtractor`, `mp4.Track`, `rawcc.RawCcExtractor` | `androidx.media3.extractor.mp4/mkv/…` | both extractors are still non-final public classes, so the adapters keep `extends`. Constructors changed: since 1.4 they take a `SubtitleParser.Factory` first (use `SubtitleParser.Factory.UNSUPPORTED` + no `FLAG_EMIT_RAW_SUBTITLE_DATA`, SABR has no in-band subtitles) (?). `RawCcExtractor` moved/may be gone (?): only used for `application/x-rawcc`, never produced by `SabrManifestParser` → drop that branch |
| `metadata.Metadata`, `MetadataInputBuffer`, `emsg.EventMessage(+Decoder/Encoder)` | `androidx.media3.common.Metadata`, `androidx.media3.extractor.metadata.*` | `SabrFormatMetadata implements Metadata.Entry`: Media3's `Entry` no longer has to be `Parcelable` in recent versions (?); keep `equals/hashCode` |
| `offline.FilterableManifest`, `StreamKey` | `androidx.media3.exoplayer.offline.FilterableManifest`, `androidx.media3.common.StreamKey` | `copy()` is unused (no offline) — can be dropped |
| `source.*` (`BaseMediaSource`, `MediaPeriod`, `MediaSource`, `SampleStream`, `SequenceableLoader`, `TrackGroupArray`, `EmptySampleStream`, `BehindLiveWindowException`, `CompositeSequenceableLoaderFactory`, `SampleQueue`) | `androidx.media3.exoplayer.source.*` (`TrackGroup` → `androidx.media3.common.TrackGroup`) | see "Real API changes" |
| `source.chunk.*` (`Chunk`, `ChunkHolder`, `ChunkSampleStream`(+`EmbeddedSampleStream`), `ChunkSource`, `ContainerMediaChunk`, `InitializationChunk`, `MediaChunk`, `MediaChunkIterator`, `SingleSampleMediaChunk`) | `androidx.media3.exoplayer.source.chunk.*` | all still public (`@UnstableApi`); `ChunkExtractorWrapper` → `BundledChunkExtractor` (implements `ChunkExtractor`), same constructor idea `(extractor, primaryTrackType, primaryTrackManifestFormat)`. `ContainerMediaChunk`/`InitializationChunk` take a `ChunkExtractor`, argument order unchanged |
| `trackselection.TrackSelection`, `FixedTrackSelection` | `androidx.media3.exoplayer.trackselection.ExoTrackSelection`, `FixedTrackSelection` | `blacklist(i, ms)` → `excludeTrack(i, ms)`; `updateSelectedTrack(...)` and `evaluateQueueSize` unchanged |
| `upstream.Allocator`, `DataSource`, `TransferListener`, `Loader`, `LoaderErrorThrower` | `androidx.media3.exoplayer.upstream.*` / `androidx.media3.datasource.*` | `DataSource`, `DataSpec`, `TransferListener`, `HttpDataSource` live in `media3-datasource` |
| `upstream.DataSpec` (fork's 11-arg constructor with method/body/headers) | `new DataSpec.Builder().setUri().setHttpMethod(DataSpec.HTTP_METHOD_POST).setHttpBody(bytes).setKey(cacheKey).setHttpRequestHeaders(headers).build()` | headers per request are built in (1b). The visitor cookie on subtitle GETs goes the same way |
| `HttpDataSource.InvalidResponseCodeException` | `androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException` | constructor/fields grew (`responseBody`, `cause`) — only read here |

### Real API changes (hand work, follow Media3's `dash` module as the template)
1. **ChunkSource** (`DefaultSabrChunkSource`): `getNextChunk(LoadingInfo loadingInfo, long loadPositionUs, List<? extends MediaChunk> queue, ChunkHolder out)` (playback position from `loadingInfo.playbackPositionUs`) (?: 1.2+); new `shouldCancelLoad(playbackPositionUs, loadingChunk, queue)` — must return false for SABR (cancelling a SABR POST half-way corrupts `SabrStream` state, same spirit as the "never cancel a format fetch" rule); `onChunkLoadError(chunk, cancelable, LoadErrorInfo, LoadErrorHandlingPolicy)` with `getFallbackSelectionFor(...)` instead of `blacklistDurationMs`; `release()`.
2. **MediaPeriod** (`SabrMediaPeriod`): `prepare/selectTracks(ExoTrackSelection[] …)`, `continueLoading(LoadingInfo)` (?: 1.2+), `getStreamKeys(List<ExoTrackSelection>)`; `ChunkSampleStream` constructor gains `DrmSessionManager`, `DrmSessionEventListener.EventDispatcher`, `MediaSourceEventListener.EventDispatcher` and (newer) `canReportInitialDiscontinuity`/`downloadExecutor` args (?) — pass `DrmSessionManager.DRM_UNSUPPORTED`; `CompositeSequenceableLoaderFactory.create(...)` takes the per-loader track types in newer versions (?).
3. **MediaSource** (`SabrMediaSource`): implement `getMediaItem()`, `prepareSourceInternal(TransferListener)`, `createPeriod(MediaPeriodId, Allocator, long)`; event dispatchers come from `createEventDispatcher(...)`/`createDrmEventDispatcher(...)`; `SabrTimeline.getWindow` must fill `Window.set(uid, mediaItem, manifest, …)` (DashTimeline in Media3 shows the field list). The `Factory` becomes a `MediaSource.Factory` (`setLoadErrorHandlingPolicy`, `setDrmSessionManagerProvider`, `getSupportedTypes`, `createMediaSource(MediaItem)`) plus our own `createMediaSource(SabrManifest, MediaItem)`. `DefaultPreloadManager` (stage 4) accepts a ready `MediaSource` via `add(MediaSource, rankingData)`, so the manifest-based factory method is enough.
4. **SampleQueue** (`PlayerEmsgHandler`): `SampleQueue.createWithoutDrm(allocator)`. Better: drop `PlayerEmsgHandler` + `EventSampleStream` + the manifest event streams entirely (live-only DASH features; SABR live isn't used) → ~470 lines fewer and simpler `SabrMediaPeriod`.
5. **Error policy** (app `SabrDefaultLoadErrorHandlingPolicy` extends `DashDefaultLoadErrorHandlingPolicy`): the 2.10 methods `getBlacklistDurationMsFor/getRetryDelayMsFor(dataType, loadDurationMs, exception, errorCount)` become `getFallbackSelectionFor(FallbackOptions, LoadErrorInfo)` / `getRetryDelayMsFor(LoadErrorInfo)` on `DefaultLoadErrorHandlingPolicy` (stage 2, app side).
6. **Format fields** `isDrc` / `lastModified` (fork-only, 1b): put them into `SabrFormatMetadata` (already a `Metadata.Entry` on `Format.metadata` carrying `xTags`/`audioTrackId`), set via `Format.Builder.setMetadata`. Readers: `FormatSelector.createFormatId` (SABR) and app `ExoFormatItem`, `TrackSelectorUtil.isDrc` → one static helper `SabrFormatMetadata.isDrc(format)` / `lastModified(format)`. No `Format` subclassing needed.

### SABR-specific code (no ExoPlayer types, copy as is)
`parser/models`, `parser/parts`, `parser/results`, `parser/exceptions`, `parser/frames`, `parser/ump` (except the
`InterruptedException` cleanup), `SabrStream` logic, `SabrCdnSelector`, all protos. Keep the proto `java_package` as is
or rename to `…media3.sabr.protos` — only imports change.

### App-side SABR touch points (stage 2)
`ExoMediaSourceFactory` (builds `SabrManifestParser` → `SabrMediaSource.Factory(DefaultSabrChunkSource.Factory(dataSourceFactory, MAX_SEGMENTS_PER_LOAD))` with the SABR error policy and `TrackErrorFixer` as event listener), `DebugInfoManager` (`instanceof SabrManifest`), `VideoLoaderController.openSabr`, and the track selector classes reading `isDrc`.

## Proposal for stage 2 (input for 1d)
- New Gradle module `media3-sabr` (package `com.liskovsoft.media3.sabr` or keep `…exoplayer2.source.sabr` renamed to
  `androidx`-free `org.smarttube.sabr`; decide in 1d), deps: `media3-exoplayer`, `media3-extractor`,
  `media3-datasource`, `protobuf-javalite`, `mediaserviceinterfaces`, `sharedutils`, `youtubeapi`. Protobuf plugin
  config copied from the old `build.gradle`.
- Port order: protos + `parser/*` (compile alone) → `manifest/*` (+ `Format.Builder`) → `DefaultSabrChunkSource` →
  `SabrMediaPeriod` (without emsg/events) → `SabrMediaSource`. Use Media3's `DashMediaPeriod`/`DefaultDashChunkSource`
  of the chosen version side by side as the reference for every signature.
- Fork-only behaviour that lives in this module and must survive: `SKIP_INIT_RESPONSE_MEDIA`, the end-of-stream
  tolerance fix (getNextChunk end condition), the seek-backwards `sabrStream.reset(iTag)` fix, `FormatId` without
  LMT sentinel, `SabrCdnSelector`. The `ChunkSampleStream` "seek to clipped start" fix from the fork (1b, A) is
  re-checked in stage 4 with `PreloadMediaSource`.
- Size estimate: ~60% of the lines are copy + import rename (good fit for one budgeted claude-task run if it saves
  enough of this plan's usage), ~2k lines (`DefaultSabrChunkSource`, `SabrMediaPeriod`, `SabrMediaSource`,
  `SabrManifestParser`, the adapters/input) need hand porting against Media3's DASH classes.
- The 3 unit tests (`DefaultSabrChunkSourceTest`, `SabrManifestParserTest`, `SabrCdnSelectorTest`, 447 lines,
  Robolectric) come along and are the first regression check in CI.
