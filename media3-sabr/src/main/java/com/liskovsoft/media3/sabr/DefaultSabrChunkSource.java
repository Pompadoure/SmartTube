package com.liskovsoft.media3.sabr;

import android.net.Uri;
import android.os.SystemClock;

import androidx.annotation.CheckResult;
import androidx.annotation.Nullable;

import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.exoplayer.SeekParameters;
import androidx.media3.extractor.Extractor;
import androidx.media3.extractor.text.DefaultSubtitleParserFactory;
import androidx.media3.extractor.text.SubtitleExtractor;
import androidx.media3.extractor.text.SubtitleParser;
import androidx.media3.exoplayer.source.BehindLiveWindowException;
import androidx.media3.exoplayer.source.chunk.Chunk;
import androidx.media3.exoplayer.source.chunk.BundledChunkExtractor;
import androidx.media3.exoplayer.source.chunk.ChunkExtractor;
import androidx.media3.exoplayer.source.chunk.ChunkHolder;
import androidx.media3.exoplayer.source.chunk.ContainerMediaChunk;
import androidx.media3.exoplayer.source.chunk.InitializationChunk;
import androidx.media3.exoplayer.source.chunk.MediaChunk;
import androidx.media3.exoplayer.source.chunk.MediaChunkIterator;
import androidx.media3.exoplayer.source.chunk.SingleSampleMediaChunk;
import com.liskovsoft.media3.sabr.manifest.AdaptationSet;
import com.liskovsoft.media3.sabr.manifest.RangedUri;
import com.liskovsoft.media3.sabr.manifest.Representation;
import com.liskovsoft.media3.sabr.manifest.SabrManifest;
import com.liskovsoft.media3.sabr.parser.adapter.SabrFragmentedMp4Adapter;
import com.liskovsoft.media3.sabr.parser.adapter.SabrMatroskaAdapter;
import com.liskovsoft.media3.sabr.parser.SabrStream;
import com.liskovsoft.media3.sabr.parser.models.AudioSelector;
import com.liskovsoft.media3.sabr.parser.models.CaptionSelector;
import com.liskovsoft.media3.sabr.parser.models.FormatSelector;
import com.liskovsoft.media3.sabr.parser.models.VideoSelector;
import com.liskovsoft.media3.sabr.protos.misc.FormatId;
import androidx.media3.exoplayer.trackselection.FixedTrackSelection;
import androidx.media3.exoplayer.trackselection.ExoTrackSelection;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException;
import androidx.media3.exoplayer.upstream.LoaderErrorThrower;
import androidx.media3.datasource.TransferListener;
import androidx.media3.common.util.Assertions;
import androidx.media3.common.util.Log;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.common.util.Util;
import androidx.media3.exoplayer.LoadingInfo;
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy;
import androidx.media3.common.MimeTypes;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@UnstableApi
public class DefaultSabrChunkSource implements SabrChunkSource {
    // JoTube: off. Ending the init response early made some videos stall at the first segment boundary
    // (~10 s, deterministic, also after an engine restart): the server state no longer matched.
    private static final boolean SKIP_INIT_RESPONSE_MEDIA = false;
    public static final class Factory implements SabrChunkSource.Factory {

        private final DataSource.Factory dataSourceFactory;
        private final int maxSegmentsPerLoad;
        private final SubtitleParser.Factory subtitleParserFactory = new DefaultSubtitleParserFactory();

        public Factory(DataSource.Factory dataSourceFactory) {
            this(dataSourceFactory, 1);
        }

        public Factory(DataSource.Factory dataSourceFactory, int maxSegmentsPerLoad) {
            this.dataSourceFactory = dataSourceFactory;
            this.maxSegmentsPerLoad = maxSegmentsPerLoad;
        }

        /**
         * JoTube M3: SABR text tracks are parsed into cues during extraction (like Media3's DASH module),
         * so their track format is {@code application/x-media3-cues}.
         */
        @Override
        public Format getOutputTextFormat(Format sourceFormat) {
            if (subtitleParserFactory.supportsFormat(sourceFormat)) {
                return sourceFormat
                        .buildUpon()
                        .setSampleMimeType(MimeTypes.APPLICATION_MEDIA3_CUES)
                        .setCueReplacementBehavior(subtitleParserFactory.getCueReplacementBehavior(sourceFormat))
                        .setCodecs(sourceFormat.sampleMimeType
                                + (sourceFormat.codecs != null ? " " + sourceFormat.codecs : ""))
                        .setSubsampleOffsetUs(Format.OFFSET_SAMPLE_RELATIVE)
                        .build();
            }
            return sourceFormat;
        }

        @Override
        public SabrChunkSource createSabrChunkSource(
                LoaderErrorThrower manifestLoaderErrorThrower,
                SabrManifest manifest,
                int periodIndex,
                int[] adaptationSetIndices,
                ExoTrackSelection trackSelection,
                int trackType,
                long elapsedRealtimeOffsetMs,
                List<Format> closedCaptionFormats,
                @Nullable TransferListener transferListener) {
            DataSource dataSource = dataSourceFactory.createDataSource();
            if (transferListener != null) {
                dataSource.addTransferListener(transferListener);
            }
            return new DefaultSabrChunkSource(
                    manifestLoaderErrorThrower,
                    manifest,
                    periodIndex,
                    adaptationSetIndices,
                    trackSelection,
                    trackType,
                    dataSource,
                    elapsedRealtimeOffsetMs,
                    maxSegmentsPerLoad,
                    closedCaptionFormats,
                    subtitleParserFactory);
        }

    }

    private static final String TAG = DefaultSabrChunkSource.class.getSimpleName();
    private static final long END_OF_STREAM_SEEK_TOLERANCE_US = 500_000L; // 0.5 seconds
    private static final long NEAR_END_TOLERANCE_US = 700_000L; // 0.7 seconds
    private final LoaderErrorThrower manifestLoaderErrorThrower;
    private final int[] adaptationSetIndices;
    private final int trackType;
    private final DataSource dataSource;
    private final long elapsedRealtimeOffsetMs;
    private final int maxSegmentsPerLoad;

    protected final RepresentationHolder[] representationHolders;

    private ExoTrackSelection trackSelection;
    private FormatSelector formatSelector;
    private SabrManifest manifest;
    private int periodIndex;
    private IOException fatalError;
    private boolean missingLastSegment;
    private long liveEdgeTimeUs;

    @Nullable private final SabrStream sabrStream;
    private final Map<String, String> sabrHeaders;
    private int nexChunkIdx = -1;

    /**
     * @param manifestLoaderErrorThrower Throws errors affecting loading of manifests.
     * @param manifest The initial manifest.
     * @param periodIndex The index of the period in the manifest.
     * @param adaptationSetIndices The indices of the adaptation sets in the period.
     * @param trackSelection The track selection.
     * @param trackType The type of the tracks in the selection.
     * @param dataSource A {@link DataSource} suitable for loading the media data.
     * @param elapsedRealtimeOffsetMs If known, an estimate of the instantaneous difference between
     *     server-side unix time and {@link SystemClock#elapsedRealtime()} in milliseconds, specified
     *     as the server's unix time minus the local elapsed time. If unknown, set to 0.
     * @param maxSegmentsPerLoad The maximum number of segments to combine into a single request. Note
     *     that segments will only be combined if their {@link Uri}s are the same and if their data
     *     ranges are adjacent.
     * @param closedCaptionFormats The {@link Format Formats} of closed caption tracks to be output.
     */
    public DefaultSabrChunkSource(
            LoaderErrorThrower manifestLoaderErrorThrower,
            SabrManifest manifest,
            int periodIndex,
            int[] adaptationSetIndices,
            ExoTrackSelection trackSelection,
            int trackType,
            DataSource dataSource,
            long elapsedRealtimeOffsetMs,
            int maxSegmentsPerLoad,
            List<Format> closedCaptionFormats,
            SubtitleParser.Factory subtitleParserFactory) {
        this.manifestLoaderErrorThrower = manifestLoaderErrorThrower;
        this.manifest = manifest;
        this.adaptationSetIndices = adaptationSetIndices;
        trackSelection = pinToTopTrack(trackSelection);
        this.trackSelection = trackSelection;
        this.formatSelector = createFormatSelector(trackType, trackSelection);
        this.trackType = trackType;
        this.dataSource = dataSource;
        this.periodIndex = periodIndex;
        this.elapsedRealtimeOffsetMs = elapsedRealtimeOffsetMs;
        this.maxSegmentsPerLoad = maxSegmentsPerLoad;

        long periodDurationUs = manifest.getPeriodDurationUs(periodIndex);
        liveEdgeTimeUs = C.TIME_UNSET;
        
        sabrHeaders = new HashMap<>();
        sabrHeaders.put("Content-Type", "application/x-protobuf");
        //sabrHeaders.put("Accept-Encoding", "identity");
        sabrHeaders.put("Accept", "application/vnd.yt-ump");

        List<Representation> representations = getRepresentations();
        SabrStream stream = null;
        representationHolders = new RepresentationHolder[trackSelection.length()];
        for (int i = 0; i < representationHolders.length; i++) {
            Representation representation = representations.get(trackSelection.getIndexInTrackGroup(i));
            if (stream == null && RepresentationHolder.needsExtractor(representation)) {
                stream = manifest.getSabrStream(trackType);
                stream.setFormatSelector(formatSelector);
            }
            representationHolders[i] =
                    new RepresentationHolder(
                            periodDurationUs,
                            trackType,
                            representation,
                            closedCaptionFormats,
                            subtitleParserFactory,
                            stream);
        }
        this.sabrStream = stream;
    }

    @Override
    public void updateManifest(SabrManifest newManifest, int newPeriodIndex) {
        try {
            manifest = newManifest;
            periodIndex = newPeriodIndex;
            long periodDurationUs = manifest.getPeriodDurationUs(periodIndex);
            List<Representation> representations = getRepresentations();
            for (int i = 0; i < representationHolders.length; i++) {
                Representation representation = representations.get(trackSelection.getIndexInTrackGroup(i));
                representationHolders[i] =
                        representationHolders[i].copyWithNewRepresentation(periodDurationUs, representation);
            }
        } catch (BehindLiveWindowException e) {
            fatalError = e;
        }
    }

    @Override
    public void updateTrackSelection(ExoTrackSelection trackSelection) {
        this.trackSelection = pinToTopTrack(trackSelection);
        trackSelection = this.trackSelection;
        this.formatSelector = createFormatSelector(trackType, trackSelection);
    }

    @Override
    public long getAdjustedSeekPositionUs(long positionUs, SeekParameters seekParameters) {
        // Segments are aligned across representations, so any segment index will do.
        //for (RepresentationHolder representationHolder : representationHolders) {
        //    if (representationHolder.segmentIndex != null) {
        //        long segmentNum = representationHolder.getSegmentNum(positionUs);
        //        long firstSyncUs = representationHolder.getSegmentStartTimeUs(segmentNum);
        //        long secondSyncUs =
        //                firstSyncUs < positionUs && segmentNum < representationHolder.getSegmentCount() - 1
        //                        ? representationHolder.getSegmentStartTimeUs(segmentNum + 1)
        //                        : firstSyncUs;
        //        return Util.resolveSeekPositionUs(positionUs, seekParameters, firstSyncUs, secondSyncUs);
        //    }
        //}

        // Clamp a seek target that lands at/past the end of the video. Without this, seeking to
        // exactly periodDurationUs (e.g. the app's "pause on end" logic parking the player at
        // getDurationMs()) leaves nothing left to buffer, so getNextChunk() immediately re-signals
        // endOfStream, which re-triggers STATE_ENDED, which re-triggers that same seek-to-end logic
        // again - an infinite onTracksChanged()/STATE_ENDED loop every time playback reaches the end.
        for (RepresentationHolder representationHolder : representationHolders) {
            long periodDurationUs = representationHolder.periodDurationUs;
            if (periodDurationUs != C.TIME_UNSET && positionUs >= periodDurationUs) {
                // The constant is usually smaller than the real value in sabrStream.getEndOfStreamSeekToleranceMs()
                return Math.max(0, periodDurationUs - END_OF_STREAM_SEEK_TOLERANCE_US);
            }
        }
        // We don't have a segment index to adjust the seek position with yet.
        return positionUs;
    }

    @Override
    public void maybeThrowError() throws IOException {
        if (fatalError != null) {
            throw fatalError;
        } else {
            manifestLoaderErrorThrower.maybeThrowError();
        }
    }

    @Override
    public int getPreferredQueueSize(long playbackPositionUs, List<? extends MediaChunk> queue) {
        if (fatalError != null || trackSelection.length() < 2) {
            return queue.size();
        }
        return trackSelection.evaluateQueueSize(playbackPositionUs, queue);
    }

    @Override
    public boolean shouldCancelLoad(
            long playbackPositionUs, Chunk loadingChunk, List<? extends MediaChunk> queue) {
        // JoTube M3: never cancel a SABR load half-way. Cancelling a running POST would leave SabrStream's
        // request state out of sync with the server (same spirit as the "never cancel a format fetch" rule).
        return false;
    }

    @Override
    public void getNextChunk(LoadingInfo loadingInfo, long loadPositionUs, List<? extends MediaChunk> queue, ChunkHolder out) {
        if (fatalError != null) {
            return;
        }

        long playbackPositionUs = loadingInfo.playbackPositionUs;

        long bufferedDurationUs = loadPositionUs - playbackPositionUs;
        long timeToLiveEdgeUs = resolveTimeToLiveEdgeUs(playbackPositionUs);
        long nowUnixTimeUs = getNowUnixTimeUs();
        MediaChunk previous = queue.isEmpty() ? null : queue.get(queue.size() - 1);
        MediaChunkIterator[] chunkIterators = new MediaChunkIterator[trackSelection.length()];
        // No segment index for SABR: no per-track iterators (adaptive selections dereference them).
        java.util.Arrays.fill(chunkIterators, MediaChunkIterator.EMPTY);
        //for (int i = 0; i < chunkIterators.length; i++) {
        //    RepresentationHolder representationHolder = representationHolders[i];
        //    if (representationHolder.segmentIndex == null) {
        //        chunkIterators[i] = MediaChunkIterator.EMPTY;
        //    } else {
        //        long firstAvailableSegmentNum =
        //                representationHolder.getFirstAvailableSegmentNum(manifest, periodIndex, nowUnixTimeUs);
        //        long lastAvailableSegmentNum =
        //                representationHolder.getLastAvailableSegmentNum(manifest, periodIndex, nowUnixTimeUs);
        //        long segmentNum =
        //                getSegmentNum(
        //                        representationHolder,
        //                        previous,
        //                        loadPositionUs,
        //                        firstAvailableSegmentNum,
        //                        lastAvailableSegmentNum);
        //        if (segmentNum < firstAvailableSegmentNum) {
        //            chunkIterators[i] = MediaChunkIterator.EMPTY;
        //        } else {
        //            chunkIterators[i] =
        //                    new RepresentationSegmentIterator(
        //                            representationHolder, segmentNum, lastAvailableSegmentNum);
        //        }
        //    }
        //}

        trackSelection.updateSelectedTrack(
                playbackPositionUs, bufferedDurationUs, timeToLiveEdgeUs, queue, chunkIterators);

        RepresentationHolder representationHolder =
                representationHolders[trackSelection.getSelectedIndex()];

        // External captions contain the entire subtitle track in one sample.
        if (representationHolder.extractorWrapper == null && previous != null) {
            out.endOfStream = true;
            return;
        }

        if (representationHolder.extractorWrapper != null) {
            Representation selectedRepresentation = representationHolder.representation;
            RangedUri pendingInitializationUri = null;
            RangedUri pendingIndexUri = null;
            if (representationHolder.extractorWrapper.getSampleFormats() == null) {
                pendingInitializationUri = selectedRepresentation.getInitializationUri();
            }
            // No segment index in SABR
            //if (representationHolder.segmentIndex == null) {
            //    pendingIndexUri = selectedRepresentation.getIndexUri();
            //}
            //if (pendingInitializationUri != null || pendingIndexUri != null) {
            if (pendingInitializationUri != null) {
                // We have initialization and/or index requests to make.
                out.chunk = newInitializationChunk(representationHolder, dataSource,
                        trackSelection.getSelectedFormat(), trackSelection.getSelectionReason(),
                        trackSelection.getSelectionData(), pendingInitializationUri, pendingIndexUri);
                return;
            }
        }

        long periodDurationUs = representationHolder.periodDurationUs;
        boolean periodEnded = periodDurationUs != C.TIME_UNSET;

        // FIX: fire ending event on a video end
        // Tolerance needed: on some (usually short/sponsor at end) videos, real segment duration falls a fraction
        // of a second short of periodDurationUs, so a strict ">=" never fires and getNextChunk() loops forever
        if (periodEnded && (loadPositionUs + NEAR_END_TOLERANCE_US) >= periodDurationUs) {
            // No segment index in SABR, so we can't compare per-segment boundaries like stock
            // DASH does — comparing loadPositionUs directly against periodDurationUs is the
            // SABR equivalent. Without this, getNextChunk() keeps firing "next chunk" requests
            // past the real end of the video forever, and the player never reaches STATE_ENDED.
            out.endOfStream = true;
            return;
        }

        //if (representationHolder.getSegmentCount() == 0) {
        //    // The index doesn't define any segments.
        //    out.endOfStream = periodEnded;
        //    return;
        //}

        //long firstAvailableSegmentNum =
        //        representationHolder.getFirstAvailableSegmentNum(manifest, periodIndex, nowUnixTimeUs);
        //long lastAvailableSegmentNum =
        //        representationHolder.getLastAvailableSegmentNum(manifest, periodIndex, nowUnixTimeUs);

        //updateLiveEdgeTimeUs(representationHolder, lastAvailableSegmentNum);

        //long segmentNum =
        //        getSegmentNum(
        //                representationHolder,
        //                previous,
        //                loadPositionUs,
        //                firstAvailableSegmentNum,
        //                lastAvailableSegmentNum);
        //if (segmentNum < firstAvailableSegmentNum) {
        //    // This is before the first chunk in the current manifest.
        //    fatalError = new BehindLiveWindowException();
        //    return;
        //}
        //
        //if (segmentNum > lastAvailableSegmentNum
        //        || (missingLastSegment && segmentNum >= lastAvailableSegmentNum)) {
        //    // The segment is beyond the end of the period.
        //    out.endOfStream = periodEnded;
        //    return;
        //}

        //if (periodEnded && representationHolder.getSegmentStartTimeUs(segmentNum) >= periodDurationUs) {
        //    // The period duration clips the period to a position before the segment.
        //    out.endOfStream = true;
        //    return;
        //}

        //int maxSegmentCount =
        //        (int) Math.min(maxSegmentsPerLoad, lastAvailableSegmentNum - segmentNum + 1);
        //if (periodDurationUs != C.TIME_UNSET) {
        //    while (maxSegmentCount > 1
        //            && representationHolder.getSegmentStartTimeUs(segmentNum + maxSegmentCount - 1)
        //            >= periodDurationUs) {
        //        // The period duration clips the period to a position before the last segment in the range
        //        // [segmentNum, segmentNum + maxSegmentCount - 1]. Reduce maxSegmentCount.
        //        maxSegmentCount--;
        //    }
        //}

        long seekTimeUs = queue.isEmpty() ? loadPositionUs : C.TIME_UNSET;
        out.chunk =
                newMediaChunk(
                        representationHolder,
                        dataSource,
                        trackType,
                        trackSelection.getSelectedFormat(),
                        trackSelection.getSelectionReason(),
                        trackSelection.getSelectionData(),
                        C.INDEX_UNSET, // TODO: does sabr has segment num?
                        //segmentNum,
                        //maxSegmentCount,
                        seekTimeUs);
    }

    @Override
    public void onChunkLoadCompleted(Chunk chunk) {
        if (chunk instanceof InitializationChunk) {
            InitializationChunk initializationChunk = (InitializationChunk) chunk;
            int trackIndex = trackSelection.indexOf(initializationChunk.trackFormat);
            RepresentationHolder representationHolder = representationHolders[trackIndex];
            // The null check avoids overwriting an index obtained from the manifest with one obtained
            // from the stream. If the manifest defines an index then the stream shouldn't, but in cases
            // where it does we should ignore it.
            //if (representationHolder.segmentIndex == null) {
            //    SeekMap seekMap = representationHolder.extractorWrapper.getSeekMap();
            //    if (seekMap != null) {
            //        representationHolders[trackIndex] =
            //                representationHolder.copyWithNewSegmentIndex(
            //                        new SabrWrappingSegmentIndex(
            //                                (ChunkIndex) seekMap,
            //                                representationHolder.representation.presentationTimeOffsetUs));
            //    }
            //}
        }
    }

    @Override
    public boolean onChunkLoadError(
            Chunk chunk,
            boolean cancelable,
            LoadErrorHandlingPolicy.LoadErrorInfo loadErrorInfo,
            LoadErrorHandlingPolicy loadErrorHandlingPolicy) {
        IOException e = loadErrorInfo.exception;
        Log.e(TAG, "Chunk load failed: " + e.getMessage());
        if (!cancelable) {
            return false;
        }
        // JoTube M3: only SABR POSTs (sources with a SabrStream) may switch the SABR CDN. Caption GETs are
        // ContainerMediaChunks now too (cues parsed during extraction), so the old SingleSampleMediaChunk
        // check no longer tells them apart.
        if (sabrStream != null && isEndpointConnectionFailure(chunk, e)) {
            if (manifest.maybeUseNextCdn(chunk.dataSpec.uri.toString())) {
                Log.w(TAG, "Retrying SABR request on an alternate media network");
                return true;
            }

            // All representations use the same SABR endpoint. Do not rapidly
            // blacklist qualities when the endpoint itself is unreachable.
            return false;
        }
        // Workaround for missing segment at the end of the period
        //if (!manifest.dynamic && chunk instanceof MediaChunk
        //        && e instanceof InvalidResponseCodeException
        //        && ((InvalidResponseCodeException) e).responseCode == 404) {
        //    RepresentationHolder representationHolder =
        //            representationHolders[trackSelection.indexOf(chunk.trackFormat)];
        //    int segmentCount = representationHolder.getSegmentCount();
        //    if (segmentCount != SabrSegmentIndex.INDEX_UNBOUNDED && segmentCount != 0) {
        //        long lastAvailableSegmentNum = representationHolder.getFirstSegmentNum() + segmentCount - 1;
        //        if (((MediaChunk) chunk).getNextChunkIndex() > lastAvailableSegmentNum) {
        //            missingLastSegment = true;
        //            return true;
        //        }
        //    }
        //}
        // Track fallback only (SABR has a single endpoint; CDN fallback is handled above).
        long nowMs = SystemClock.elapsedRealtime();
        int numberOfTracks = trackSelection.length();
        int numberOfExcludedTracks = 0;
        for (int i = 0; i < numberOfTracks; i++) {
            if (trackSelection.isTrackExcluded(i, nowMs)) {
                numberOfExcludedTracks++;
            }
        }
        LoadErrorHandlingPolicy.FallbackOptions fallbackOptions =
                new LoadErrorHandlingPolicy.FallbackOptions(
                        /* numberOfLocations= */ 1,
                        /* numberOfExcludedLocations= */ 0,
                        numberOfTracks,
                        numberOfExcludedTracks);
        if (!fallbackOptions.isFallbackAvailable(LoadErrorHandlingPolicy.FALLBACK_TYPE_TRACK)) {
            return false;
        }
        @Nullable LoadErrorHandlingPolicy.FallbackSelection fallbackSelection =
                loadErrorHandlingPolicy.getFallbackSelectionFor(fallbackOptions, loadErrorInfo);
        if (fallbackSelection == null
                || fallbackSelection.type != LoadErrorHandlingPolicy.FALLBACK_TYPE_TRACK) {
            return false;
        }
        return trackSelection.excludeTrack(
                trackSelection.indexOf(chunk.trackFormat), fallbackSelection.exclusionDurationMs);
    }

    @Override
    public void release() {
        for (RepresentationHolder representationHolder : representationHolders) {
            @Nullable ChunkExtractor chunkExtractor = representationHolder.extractorWrapper;
            if (chunkExtractor != null) {
                chunkExtractor.release();
            }
            @Nullable ChunkExtractor textExtractor = representationHolder.textExtractor;
            if (textExtractor != null) {
                textExtractor.release();
            }
        }
    }

    private static boolean isEndpointConnectionFailure(Chunk chunk, IOException error) {
        return chunk.bytesLoaded() == 0
                && !(error instanceof InvalidResponseCodeException);
    }

    private ArrayList<Representation> getRepresentations() {
        List<AdaptationSet> manifestAdaptationSets = manifest.getPeriod(periodIndex).adaptationSets;
        ArrayList<Representation> representations = new ArrayList<>();
        for (int adaptationSetIndex : adaptationSetIndices) {
            representations.addAll(manifestAdaptationSets.get(adaptationSetIndex).representations);
        }
        return representations;
    }

    private long resolveTimeToLiveEdgeUs(long playbackPositionUs) {
        boolean resolveTimeToLiveEdgePossible = manifest.dynamic && liveEdgeTimeUs != C.TIME_UNSET;
        return resolveTimeToLiveEdgePossible ? liveEdgeTimeUs - playbackPositionUs : C.TIME_UNSET;
    }

    private long getNowUnixTimeUs() {
        if (elapsedRealtimeOffsetMs != 0) {
            return (SystemClock.elapsedRealtime() + elapsedRealtimeOffsetMs) * 1000;
        } else {
            return System.currentTimeMillis() * 1000;
        }
    }

    //private long getSegmentNum(
    //        RepresentationHolder representationHolder,
    //        @Nullable MediaChunk previousChunk,
    //        long loadPositionUs,
    //        long firstAvailableSegmentNum,
    //        long lastAvailableSegmentNum) {
    //    return previousChunk != null
    //            ? previousChunk.getNextChunkIndex()
    //            : Util.constrainValue(
    //            representationHolder.getSegmentNum(loadPositionUs),
    //            firstAvailableSegmentNum,
    //            lastAvailableSegmentNum);
    //}

    //private void updateLiveEdgeTimeUs(
    //        RepresentationHolder representationHolder, long lastAvailableSegmentNum) {
    //    liveEdgeTimeUs = manifest.dynamic
    //            ? representationHolder.getSegmentEndTimeUs(lastAvailableSegmentNum) : C.TIME_UNSET;
    //}

    protected Chunk newInitializationChunk(
            RepresentationHolder representationHolder,
            DataSource dataSource,
            Format trackFormat,
            int trackSelectionReason,
            Object trackSelectionData,
            RangedUri initializationUri,
            RangedUri indexUri) {
        //RangedUri requestUri;
        //String baseUrl = representationHolder.representation.baseUrl;
        //baseUrl = Utils.updateQuery(baseUrl, "rn", sabrStream.getIncSabrRequestNumber());
        //if (initializationUri != null) {
        //    // It's common for initialization and index data to be stored adjacently. Attempt to merge
        //    // the two requests together to request both at once.
        //    requestUri = initializationUri.attemptMerge(indexUri, baseUrl);
        //    if (requestUri == null) {
        //        requestUri = initializationUri;
        //    }
        //} else {
        //    requestUri = indexUri;
        //}
        // NOTE: first protobuf request (before the video start off)
        // MOD: add protobuf data
        //DataSpec dataSpec = new DataSpec(requestUri.resolveUri(baseUrl), requestUri.start,
        //        requestUri.length, representationHolder.representation.getCacheKey());
        DataSpec dataSpec = createSabrDataSpec(
                manifest.getRequestUrl(trackType),
                manifest.createVideoPlaybackAbrRequest(trackType, true).toByteArray(),
                representationHolder.representation.getCacheKey());
        if (sabrStream != null && SKIP_INIT_RESPONSE_MEDIA) {
            // JoTube: don't download the media that comes with the init response, it is dropped anyway
            sabrStream.setInitLoad(true);
        }
        Log.e(TAG, "Load init chunk: track=" + trackType + ", rn=" + manifest.getSabrRequestNumber());
        return new InitializationChunk(dataSource, dataSpec, trackFormat,
                trackSelectionReason, trackSelectionData, representationHolder.extractorWrapper);
    }

    /** A SABR request: UMP over an HTTP POST with a protobuf body (the fork's 11-arg DataSpec constructor). */
    private DataSpec createSabrDataSpec(String requestUrl, byte[] body, @Nullable String cacheKey) {
        return new DataSpec.Builder()
                .setUri(Uri.parse(requestUrl))
                .setHttpMethod(DataSpec.HTTP_METHOD_POST)
                .setHttpBody(body)
                .setPosition(0)
                .setLength(C.LENGTH_UNSET)
                .setKey(cacheKey)
                .setHttpRequestHeaders(sabrHeaders)
                .build();
    }

    // JoTube M3: SingleSampleMediaChunk is deprecated in Media3 (legacy subtitle decoding); SABR text tracks
    // still come as single raw samples. The subtitle path is decided in stage 3 (SABR_PORT.md, Subtitles).
    @SuppressWarnings("deprecation")
    protected Chunk newMediaChunk(
            RepresentationHolder representationHolder,
            DataSource dataSource,
            int trackType,
            Format trackFormat,
            int trackSelectionReason,
            Object trackSelectionData,
            long firstSegmentNum,
            //int maxSegmentCount,
            long seekTimeUs) {
        Representation representation = representationHolder.representation;
        if (representationHolder.extractorWrapper == null) {
            DataSpec dataSpec = new DataSpec.Builder()
                    .setUri(Uri.parse(representation.baseUrl))
                    .setHttpMethod(DataSpec.HTTP_METHOD_GET)
                    .setPosition(0)
                    .setLength(C.LENGTH_UNSET)
                    .setKey(representation.getCacheKey())
                    .setHttpRequestHeaders(manifest.visitorCookie != null
                            ? Collections.singletonMap("Cookie", manifest.visitorCookie)
                            : Collections.emptyMap())
                    .build();
            if (representationHolder.textExtractor != null) {
                // JoTube M3: the whole subtitle file as one chunk, parsed into cues while extracting.
                return new ContainerMediaChunk(
                        dataSource, dataSpec, trackFormat, trackSelectionReason, trackSelectionData,
                        /* startTimeUs= */ 0, representationHolder.periodDurationUs,
                        /* clippedStartTimeUs= */ C.TIME_UNSET, /* clippedEndTimeUs= */ C.TIME_UNSET,
                        /* chunkIndex= */ 0, /* chunkCount= */ 1, /* sampleOffsetUs= */ 0,
                        representationHolder.textExtractor);
            }
            return new SingleSampleMediaChunk(
                    dataSource, dataSpec, trackFormat, trackSelectionReason, trackSelectionData,
                    0, representationHolder.periodDurationUs, 0, trackType, trackFormat);
        }

        SabrStream sabrStream = Assertions.checkNotNull(this.sabrStream);
        sabrStream.setInitLoad(false); // JoTube: the init chunk is done
        boolean isInit = nexChunkIdx == -1;
        FormatId formatId = formatSelector.getSelectedFormatId();
        int iTag = formatId != null ? formatId.getItag() : -1;

        // FIX: seek backwards does infinite loading after 60 second but the buffer is full
        boolean isSeek = seekTimeUs != C.TIME_UNSET; // same condition used for seekTimeUs
        if (isSeek) {
            sabrStream.reset(iTag);
            nexChunkIdx = -1; // or whatever "post-init" value newMediaChunk expects
        }

        // Old code
        //if (nexChunkIdx == -1) {
        //    sabrStream.reset(iTag);
        //}

        nexChunkIdx++;

        //long startTimeMs = sabrStream.getSegmentStartTimeMs(trackType);
        //long durationMs = sabrStream.getSegmentDurationMs(trackType);
        long startTimeMs = sabrStream.getSegmentStartTimeMs(iTag);
        long durationMs = sabrStream.getSegmentDurationMs(iTag);
        boolean isSabrTimeSet = startTimeMs >= 0 && durationMs > 0;

        long startTimeUs = isSabrTimeSet ? startTimeMs * 1_000L : C.TIME_UNSET;
        long endTimeUs = isSabrTimeSet ? startTimeUs + (durationMs * 1_000L) : C.TIME_UNSET;
        long clippedEndTimeUs = C.TIME_UNSET;
        int segmentCount = C.INDEX_UNSET; // TODO: replace with SABR value?
        //long startTimeUs = representationHolder.getSegmentStartTimeUs(firstSegmentNum);
        //RangedUri segmentUri = representationHolder.getSegmentUrl(firstSegmentNum);
        //String baseUrl = representation.baseUrl;
        //baseUrl = Utils.updateQuery(baseUrl, "rn", sabrStream.getIncSabrRequestNumber());

        DataSpec dataSpec = createSabrDataSpec(
                manifest.getRequestUrl(trackType),
                manifest.createVideoPlaybackAbrRequest(trackType, false, seekTimeUs).toByteArray(),
                representation.getCacheKey());
        long sampleOffsetUs = -representation.presentationTimeOffsetUs;
        Log.e(TAG, "Load media chunk: track=" + trackType + ", rn=" + manifest.getSabrRequestNumber()
                + ", startTimeMs=" + startTimeMs + ", backoffTimeMs=" + sabrStream.getBackoffTimeMs());
        return new ContainerMediaChunk(
                dataSource,
                dataSpec,
                trackFormat,
                trackSelectionReason,
                trackSelectionData,
                startTimeUs,
                endTimeUs,
                seekTimeUs,
                clippedEndTimeUs,
                firstSegmentNum,
                segmentCount,
                sampleOffsetUs,
                representationHolder.extractorWrapper);

        //if (representationHolder.extractorWrapper == null) {
        //    long endTimeUs = representationHolder.getSegmentEndTimeUs(firstSegmentNum);
        //    DataSpec dataSpec = new DataSpec(segmentUri.resolveUri(baseUrl),
        //            segmentUri.start, segmentUri.length, representation.getCacheKey());
        //    return new SingleSampleMediaChunk(dataSource, dataSpec, trackFormat, trackSelectionReason,
        //            trackSelectionData, startTimeUs, endTimeUs, firstSegmentNum, trackType, trackFormat);
        //} else {
        //    int segmentCount = 1;
        //    for (int i = 1; i < maxSegmentCount; i++) {
        //        RangedUri nextSegmentUri = representationHolder.getSegmentUrl(firstSegmentNum + i);
        //        RangedUri mergedSegmentUri = segmentUri.attemptMerge(nextSegmentUri, baseUrl);
        //        if (mergedSegmentUri == null) {
        //            // Unable to merge segment fetches because the URIs do not merge.
        //            break;
        //        }
        //        segmentUri = mergedSegmentUri;
        //        segmentCount++;
        //    }
        //    long endTimeUs = representationHolder.getSegmentEndTimeUs(firstSegmentNum + segmentCount - 1);
        //    long periodDurationUs = representationHolder.periodDurationUs;
        //    long clippedEndTimeUs =
        //            periodDurationUs != C.TIME_UNSET && periodDurationUs <= endTimeUs
        //                    ? periodDurationUs
        //                    : C.TIME_UNSET;
        //    // NOTE: next protobuf requests (during the playback)
        //    // MOD: add protobuf data
        //    //DataSpec dataSpec = new DataSpec(segmentUri.resolveUri(baseUrl),
        //    //        segmentUri.start, segmentUri.length, representation.getCacheKey());
        //    int sabrRequestNumber = sabrStream.getIncSabrRequestNumber();
        //    DataSpec dataSpec = new DataSpec(
        //            segmentUri.resolveUri(baseUrl),
        //            DataSpec.HTTP_METHOD_POST,
        //            sabrStream.buildInitVideoPlaybackAbrRequest(trackType).toByteArray(),
        //            0, 0, C.LENGTH_UNSET,
        //            //segmentUri.start,
        //            //segmentUri.start,
        //            //segmentUri.length,
        //            representation.getCacheKey(),
        //            0,
        //            sabrHeaders);
        //    long sampleOffsetUs = -representation.presentationTimeOffsetUs;
        //    return new ContainerMediaChunk(
        //            dataSource,
        //            dataSpec,
        //            trackFormat,
        //            trackSelectionReason,
        //            trackSelectionData,
        //            startTimeUs,
        //            endTimeUs,
        //            seekTimeUs,
        //            clippedEndTimeUs,
        //            firstSegmentNum,
        //            segmentCount,
        //            sampleOffsetUs,
        //            representationHolder.extractorWrapper);
        //}
    }

    private static AudioSelector createAudioSelection(int trackType, ExoTrackSelection trackSelection) {
        if (trackType != C.TRACK_TYPE_AUDIO) {
            return null;
        }

        Format selectedFormat = trackSelection.getSelectedFormat();

        return new AudioSelector("selected_audio", false, selectedFormat);
    }

    private static VideoSelector createVideoSelection(int trackType, ExoTrackSelection trackSelection) {
        if (trackType != C.TRACK_TYPE_VIDEO) {
            return null;
        }

        Format selectedFormat = trackSelection.getSelectedFormat();

        return new VideoSelector("selected_video", false, selectedFormat);
    }

    private static CaptionSelector createCaptionSelection(int trackType, ExoTrackSelection trackSelection) {
        if (trackType != C.TRACK_TYPE_TEXT) {
            return null;
        }

        Format selectedFormat = trackSelection.getSelectedFormat();

        return new CaptionSelector("selected_caption", false, selectedFormat);
    }

    /**
     * SABR requests one fixed itag per stream (the FormatSelector), so the format can't change mid-stream.
     * An adaptive selection (fast start ladder) is pinned to its best track here: same behavior as before.
     */
    private static ExoTrackSelection pinToTopTrack(ExoTrackSelection trackSelection) {
        if (trackSelection == null || trackSelection.length() < 2) {
            return trackSelection;
        }

        int best = 0;
        for (int i = 1; i < trackSelection.length(); i++) {
            Format format = trackSelection.getFormat(i);
            Format bestFormat = trackSelection.getFormat(best);
            if (format.height > bestFormat.height
                    || (format.height == bestFormat.height && format.bitrate > bestFormat.bitrate)) {
                best = i;
            }
        }

        return new FixedTrackSelection(trackSelection.getTrackGroup(), trackSelection.getIndexInTrackGroup(best));
    }

    private static FormatSelector createFormatSelector(int trackType, ExoTrackSelection trackSelection) {
        Format selectedFormat = trackSelection.getSelectedFormat();

        switch (trackType) {
            case C.TRACK_TYPE_AUDIO:
                return new FormatSelector("selected_audio", false, selectedFormat);
            case C.TRACK_TYPE_VIDEO:
                return new FormatSelector("selected_video", false, selectedFormat);
            case C.TRACK_TYPE_TEXT:
                return new FormatSelector("selected_caption", false, selectedFormat);
        }

        throw new IllegalStateException("Unknown track type");
    }

    /** {@link MediaChunkIterator} wrapping a {@link RepresentationHolder}. */
    //protected static final class RepresentationSegmentIterator extends BaseMediaChunkIterator {
    //
    //    private final RepresentationHolder representationHolder;
    //
    //    /**
    //     * Creates iterator.
    //     *
    //     * @param representation The {@link RepresentationHolder} to wrap.
    //     * @param firstAvailableSegmentNum The number of the first available segment.
    //     * @param lastAvailableSegmentNum The number of the last available segment.
    //     */
    //    public RepresentationSegmentIterator(
    //            RepresentationHolder representation,
    //            long firstAvailableSegmentNum,
    //            long lastAvailableSegmentNum) {
    //        super(/* fromIndex= */ firstAvailableSegmentNum, /* toIndex= */ lastAvailableSegmentNum);
    //        this.representationHolder = representation;
    //    }
    //
    //    @Override
    //    public DataSpec getDataSpec() {
    //        checkInBounds();
    //        Representation representation = representationHolder.representation;
    //        RangedUri segmentUri = representationHolder.getSegmentUrl(getCurrentIndex());
    //        Uri resolvedUri = segmentUri.resolveUri(representation.baseUrl);
    //        String cacheKey = representation.getCacheKey();
    //        return new DataSpec(resolvedUri, segmentUri.start, segmentUri.length, cacheKey);
    //    }
    //
    //    @Override
    //    public long getChunkStartTimeUs() {
    //        checkInBounds();
    //        return representationHolder.getSegmentStartTimeUs(getCurrentIndex());
    //    }
    //
    //    @Override
    //    public long getChunkEndTimeUs() {
    //        checkInBounds();
    //        return representationHolder.getSegmentEndTimeUs(getCurrentIndex());
    //    }
    //}

    /** Holds information about a snapshot of a single {@link Representation}. */
    protected static final class RepresentationHolder {

        /* package */ final @Nullable ChunkExtractor extractorWrapper;
        /** Raw text (subtitle) representations: a SubtitleExtractor, no SABR stream. */
        /* package */ final @Nullable ChunkExtractor textExtractor;

        public final Representation representation;
        //public final @Nullable SabrSegmentIndex segmentIndex;

        private final long periodDurationUs;
        private final long segmentNumShift;

        /* package */ RepresentationHolder(
                long periodDurationUs,
                int trackType,
                Representation representation,
                List<Format> closedCaptionFormats,
                SubtitleParser.Factory subtitleParserFactory,
                SabrStream sabrStream) {
            this(
                    periodDurationUs,
                    representation,
                    createExtractorWrapper(
                            trackType,
                            representation,
                            closedCaptionFormats,
                            sabrStream),
                    createTextExtractor(representation, subtitleParserFactory),
                    /* segmentNumShift= */ 0
                    //representation.getIndex()
            );
        }

        private RepresentationHolder(
                long periodDurationUs,
                Representation representation,
                @Nullable ChunkExtractor extractorWrapper,
                @Nullable ChunkExtractor textExtractor,
                long segmentNumShift
                //@Nullable SabrSegmentIndex segmentIndex
        ) {
            this.periodDurationUs = periodDurationUs;
            this.representation = representation;
            this.segmentNumShift = segmentNumShift;
            this.extractorWrapper = extractorWrapper;
            this.textExtractor = textExtractor;
            //this.segmentIndex = segmentIndex;
        }

        @CheckResult
            /* package */ RepresentationHolder copyWithNewRepresentation(
                long newPeriodDurationUs, Representation newRepresentation)
                throws BehindLiveWindowException {

            return new RepresentationHolder(
                    newPeriodDurationUs, newRepresentation, extractorWrapper, textExtractor, segmentNumShift);

            //SabrSegmentIndex oldIndex = representation.getIndex();
            //SabrSegmentIndex newIndex = newRepresentation.getIndex();

            //if (oldIndex == null) {
            //    // Segment numbers cannot shift if the index isn't defined by the manifest.
            //    return new RepresentationHolder(
            //            newPeriodDurationUs, newRepresentation, extractorWrapper, segmentNumShift, oldIndex);
            //}
            //
            //if (!oldIndex.isExplicit()) {
            //    // Segment numbers cannot shift if the index isn't explicit.
            //    return new RepresentationHolder(
            //            newPeriodDurationUs, newRepresentation, extractorWrapper, segmentNumShift, newIndex);
            //}
            //
            //int oldIndexSegmentCount = oldIndex.getSegmentCount(newPeriodDurationUs);
            //if (oldIndexSegmentCount == 0) {
            //    // Segment numbers cannot shift if the old index was empty.
            //    return new RepresentationHolder(
            //            newPeriodDurationUs, newRepresentation, extractorWrapper, segmentNumShift, newIndex);
            //}
            //
            //long oldIndexFirstSegmentNum = oldIndex.getFirstSegmentNum();
            //long oldIndexStartTimeUs = oldIndex.getTimeUs(oldIndexFirstSegmentNum);
            //long oldIndexLastSegmentNum = oldIndexFirstSegmentNum + oldIndexSegmentCount - 1;
            //long oldIndexEndTimeUs =
            //        oldIndex.getTimeUs(oldIndexLastSegmentNum)
            //                + oldIndex.getDurationUs(oldIndexLastSegmentNum, newPeriodDurationUs);
            //long newIndexFirstSegmentNum = newIndex.getFirstSegmentNum();
            //long newIndexStartTimeUs = newIndex.getTimeUs(newIndexFirstSegmentNum);
            //long newSegmentNumShift = segmentNumShift;
            //if (oldIndexEndTimeUs == newIndexStartTimeUs) {
            //    // The new index continues where the old one ended, with no overlap.
            //    newSegmentNumShift += oldIndexLastSegmentNum + 1 - newIndexFirstSegmentNum;
            //} else if (oldIndexEndTimeUs < newIndexStartTimeUs) {
            //    // There's a gap between the old index and the new one which means we've slipped behind the
            //    // live window and can't proceed.
            //    throw new BehindLiveWindowException();
            //} else if (newIndexStartTimeUs < oldIndexStartTimeUs) {
            //    // The new index overlaps with (but does not have a start position contained within) the old
            //    // index. This can only happen if extra segments have been added to the start of the index.
            //    newSegmentNumShift -=
            //            newIndex.getSegmentNum(oldIndexStartTimeUs, newPeriodDurationUs)
            //                    - oldIndexFirstSegmentNum;
            //} else {
            //    // The new index overlaps with (and has a start position contained within) the old index.
            //    newSegmentNumShift +=
            //            oldIndex.getSegmentNum(newIndexStartTimeUs, newPeriodDurationUs)
            //                    - newIndexFirstSegmentNum;
            //}
            //return new RepresentationHolder(
            //        newPeriodDurationUs, newRepresentation, extractorWrapper, newSegmentNumShift, newIndex);
        }

        //@CheckResult
        //    /* package */ RepresentationHolder copyWithNewSegmentIndex(SabrSegmentIndex segmentIndex) {
        //    return new RepresentationHolder(
        //            periodDurationUs, representation, extractorWrapper, segmentNumShift, segmentIndex);
        //}

        //public long getFirstSegmentNum() {
        //    return segmentIndex.getFirstSegmentNum() + segmentNumShift;
        //}
        //
        //public int getSegmentCount() {
        //    return segmentIndex.getSegmentCount(periodDurationUs);
        //}
        //
        //public long getSegmentStartTimeUs(long segmentNum) {
        //    return segmentIndex.getTimeUs(segmentNum - segmentNumShift);
        //}
        //
        //public long getSegmentEndTimeUs(long segmentNum) {
        //    return getSegmentStartTimeUs(segmentNum)
        //            + segmentIndex.getDurationUs(segmentNum - segmentNumShift, periodDurationUs);
        //}
        //
        //public long getSegmentNum(long positionUs) {
        //    return segmentIndex.getSegmentNum(positionUs, periodDurationUs) + segmentNumShift;
        //}
        //
        //public RangedUri getSegmentUrl(long segmentNum) {
        //    return segmentIndex.getSegmentUrl(segmentNum - segmentNumShift);
        //}

        //public long getFirstAvailableSegmentNum(
        //        SabrManifest manifest, int periodIndex, long nowUnixTimeUs) {
        //    if (getSegmentCount() == SabrSegmentIndex.INDEX_UNBOUNDED
        //            && manifest.timeShiftBufferDepthMs != C.TIME_UNSET) {
        //        // The index is itself unbounded. We need to use the current time to calculate the range of
        //        // available segments.
        //        long liveEdgeTimeUs = nowUnixTimeUs - C.msToUs(manifest.availabilityStartTimeMs);
        //        long periodStartUs = C.msToUs(manifest.getPeriod(periodIndex).startMs);
        //        long liveEdgeTimeInPeriodUs = liveEdgeTimeUs - periodStartUs;
        //        long bufferDepthUs = C.msToUs(manifest.timeShiftBufferDepthMs);
        //        return Math.max(
        //                getFirstSegmentNum(), getSegmentNum(liveEdgeTimeInPeriodUs - bufferDepthUs));
        //    }
        //    return getFirstSegmentNum();
        //}
        //
        //public long getLastAvailableSegmentNum(
        //        SabrManifest manifest, int periodIndex, long nowUnixTimeUs) {
        //    int availableSegmentCount = getSegmentCount();
        //    if (availableSegmentCount == SabrSegmentIndex.INDEX_UNBOUNDED) {
        //        // The index is itself unbounded. We need to use the current time to calculate the range of
        //        // available segments.
        //        long liveEdgeTimeUs = nowUnixTimeUs - C.msToUs(manifest.availabilityStartTimeMs);
        //        long periodStartUs = C.msToUs(manifest.getPeriod(periodIndex).startMs);
        //        long liveEdgeTimeInPeriodUs = liveEdgeTimeUs - periodStartUs;
        //        // getSegmentNum(liveEdgeTimeInPeriodUs) will not be completed yet, so subtract one to get
        //        // the index of the last completed segment.
        //        return getSegmentNum(liveEdgeTimeInPeriodUs) - 1;
        //    }
        //    return getFirstSegmentNum() + availableSegmentCount - 1;
        //}

        private static boolean mimeTypeIsWebm(String mimeType) {
            return mimeType.startsWith(MimeTypes.VIDEO_WEBM) || mimeType.startsWith(MimeTypes.AUDIO_WEBM)
                    || mimeType.startsWith(MimeTypes.APPLICATION_WEBM);
        }

        private static boolean mimeTypeIsRawText(String mimeType) {
            return MimeTypes.isText(mimeType) || MimeTypes.APPLICATION_TTML.equals(mimeType);
        }

        private static boolean needsExtractor(Representation representation) {
            String containerMimeType = representation.format.containerMimeType;
            return containerMimeType != null && !mimeTypeIsRawText(containerMimeType);
        }

        private static @Nullable ChunkExtractor createTextExtractor(
                Representation representation, SubtitleParser.Factory subtitleParserFactory) {
            Format format = representation.format;
            if (needsExtractor(representation) || !subtitleParserFactory.supportsFormat(format)) {
                return null;
            }
            return new BundledChunkExtractor(
                    new SubtitleExtractor(subtitleParserFactory.create(format), format),
                    C.TRACK_TYPE_TEXT,
                    format);
        }

        private static @Nullable ChunkExtractor createExtractorWrapper(
                int trackType,
                Representation representation,
                List<Format> closedCaptionFormats,
                SabrStream sabrStream) {
            String containerMimeType = representation.format.containerMimeType;
            if (!needsExtractor(representation)) {
                return null;
            }

            // MOD: replaced with SabrExtractor
            //Extractor extractor;
            //if (MimeTypes.APPLICATION_RAWCC.equals(containerMimeType)) {
            //    extractor = new RawCcExtractor(representation.format);
            //} else if (mimeTypeIsWebm(containerMimeType)) {
            //    extractor = new MatroskaExtractor(MatroskaExtractor.FLAG_DISABLE_SEEK_FOR_CUES);
            //} else {
            //    int flags = 0;
            //    if (enableEventMessageTrack) {
            //        flags |= FragmentedMp4Extractor.FLAG_ENABLE_EMSG_TRACK;
            //    }
            //    extractor =
            //            new FragmentedMp4Extractor(
            //                    flags, null, null, null, closedCaptionFormats, playerEmsgTrackOutput);
            //}

            //Extractor extractor = new SabrExtractor(trackType, representation.format, sabrStream);

            Extractor extractor;
            // JoTube M3: no application/x-rawcc branch, SabrManifestParser never produces it (SABR_PORT.md).
            if (mimeTypeIsWebm(containerMimeType)) {
                extractor = new SabrMatroskaAdapter(SabrMatroskaAdapter.FLAG_DISABLE_SEEK_FOR_CUES, sabrStream);
            } else {
                // JoTube M3: no emsg track (live-only DASH feature, dropped with PlayerEmsgHandler).
                extractor =
                        new SabrFragmentedMp4Adapter(
                                /* flags= */ 0, closedCaptionFormats, /* additionalEmsgTrackOutput= */ null, sabrStream);
            }

            // Prefer drmInitData obtained from the manifest over drmInitData obtained from the stream,
            // as per DASH IF Interoperability Recommendations V3.0, 7.5.3.
            return new BundledChunkExtractor(extractor, trackType, representation.format);
        }
    }
}
