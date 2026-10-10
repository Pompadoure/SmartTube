package com.liskovsoft.media3.sabr;

import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.TrackGroup;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.TransferListener;
import androidx.media3.exoplayer.LoadingInfo;
import androidx.media3.exoplayer.SeekParameters;
import androidx.media3.exoplayer.drm.DrmSessionEventListener;
import androidx.media3.exoplayer.drm.DrmSessionManager;
import androidx.media3.exoplayer.source.CompositeSequenceableLoaderFactory;
import androidx.media3.exoplayer.source.MediaSourceEventListener;
import androidx.media3.exoplayer.source.MediaPeriod;
import androidx.media3.exoplayer.source.SampleStream;
import androidx.media3.exoplayer.source.SequenceableLoader;
import androidx.media3.exoplayer.source.TrackGroupArray;
import androidx.media3.exoplayer.source.chunk.ChunkSampleStream;
import androidx.media3.exoplayer.trackselection.ExoTrackSelection;
import androidx.media3.exoplayer.upstream.Allocator;
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy;
import androidx.media3.exoplayer.upstream.LoaderErrorThrower;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.liskovsoft.media3.sabr.manifest.AdaptationSet;
import com.liskovsoft.media3.sabr.manifest.Period;
import com.liskovsoft.media3.sabr.manifest.Representation;
import com.liskovsoft.media3.sabr.manifest.SabrManifest;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A SABR {@link MediaPeriod}, modelled on Media3's {@code DashMediaPeriod}.
 *
 * <p>JoTube M3: one primary track group per adaptation set, one {@link ChunkSampleStream} per selected
 * group. The fork's emsg/manifest-event streams and embedded CEA-608 tracks are gone: SABR is only used for
 * VOD (no live), the 2.10.6 code never created them (no event streams, no CEA-608 formats), see
 * docs/media3/SABR_PORT.md.
 */
@UnstableApi
final class SabrMediaPeriod
        implements MediaPeriod,
        SequenceableLoader.Callback<ChunkSampleStream<SabrChunkSource>>,
        ChunkSampleStream.ReleaseCallback<SabrChunkSource> {
    /* package */ final int id;
    private final SabrChunkSource.Factory chunkSourceFactory;
    @Nullable private final TransferListener transferListener;
    private final DrmSessionManager drmSessionManager;
    private final DrmSessionEventListener.EventDispatcher drmEventDispatcher;
    private final LoadErrorHandlingPolicy loadErrorHandlingPolicy;
    private final MediaSourceEventListener.EventDispatcher mediaSourceEventDispatcher;
    private final long elapsedRealtimeOffsetMs;
    private final LoaderErrorThrower manifestLoaderErrorThrower;
    private final TrackGroupArray trackGroups;
    private final TrackGroupInfo[] trackGroupInfos;
    private final Allocator allocator;
    private final CompositeSequenceableLoaderFactory compositeSequenceableLoaderFactory;

    @Nullable private Callback callback;
    private ChunkSampleStream<SabrChunkSource>[] sampleStreams;
    private SequenceableLoader compositeSequenceableLoader;
    private SabrManifest manifest;
    private int periodIndex;

    public SabrMediaPeriod(
            int id,
            SabrManifest manifest,
            int periodIndex,
            SabrChunkSource.Factory chunkSourceFactory,
            @Nullable TransferListener transferListener,
            DrmSessionManager drmSessionManager,
            DrmSessionEventListener.EventDispatcher drmEventDispatcher,
            LoadErrorHandlingPolicy loadErrorHandlingPolicy,
            MediaSourceEventListener.EventDispatcher mediaSourceEventDispatcher,
            long elapsedRealtimeOffsetMs,
            LoaderErrorThrower manifestLoaderErrorThrower,
            Allocator allocator,
            CompositeSequenceableLoaderFactory compositeSequenceableLoaderFactory) {
        this.id = id;
        this.manifest = manifest;
        this.periodIndex = periodIndex;
        this.chunkSourceFactory = chunkSourceFactory;
        this.transferListener = transferListener;
        this.drmSessionManager = drmSessionManager;
        this.drmEventDispatcher = drmEventDispatcher;
        this.loadErrorHandlingPolicy = loadErrorHandlingPolicy;
        this.mediaSourceEventDispatcher = mediaSourceEventDispatcher;
        this.elapsedRealtimeOffsetMs = elapsedRealtimeOffsetMs;
        this.manifestLoaderErrorThrower = manifestLoaderErrorThrower;
        this.allocator = allocator;
        this.compositeSequenceableLoaderFactory = compositeSequenceableLoaderFactory;
        sampleStreams = newSampleStreamArray(0);
        compositeSequenceableLoader = compositeSequenceableLoaderFactory.empty();
        Period period = manifest.getPeriod(periodIndex);
        List<AdaptationSet> adaptationSets = period.adaptationSets;
        int groupCount = adaptationSets.size();
        TrackGroup[] groups = new TrackGroup[groupCount];
        trackGroupInfos = new TrackGroupInfo[groupCount];
        for (int i = 0; i < groupCount; i++) {
            AdaptationSet adaptationSet = adaptationSets.get(i);
            List<Representation> representations = adaptationSet.representations;
            Format[] formats = new Format[representations.size()];
            for (int j = 0; j < formats.length; j++) {
                formats[j] = representations.get(j).format;
            }
            groups[i] = new TrackGroup(/* id= */ String.valueOf(adaptationSet.id), formats);
            trackGroupInfos[i] = new TrackGroupInfo(adaptationSet.type, new int[] {i});
        }
        trackGroups = new TrackGroupArray(groups);
    }

    /**
     * Updates the {@link SabrManifest} and the index of this period in the manifest.
     */
    public void updateManifest(SabrManifest manifest, int periodIndex) {
        this.manifest = manifest;
        this.periodIndex = periodIndex;
        if (sampleStreams != null) {
            for (ChunkSampleStream<SabrChunkSource> sampleStream : sampleStreams) {
                sampleStream.getChunkSource().updateManifest(manifest, periodIndex);
            }
            if (callback != null) {
                callback.onContinueLoadingRequested(this);
            }
        }
    }

    public void release() {
        for (ChunkSampleStream<SabrChunkSource> sampleStream : sampleStreams) {
            sampleStream.release(this);
        }
        callback = null;
    }

    // ChunkSampleStream.ReleaseCallback implementation.

    @Override
    public synchronized void onSampleStreamReleased(ChunkSampleStream<SabrChunkSource> stream) {
        // Nothing to clean up: no per-stream emsg handlers in the SABR port.
    }

    // MediaPeriod implementation.

    @Override
    public void prepare(Callback callback, long positionUs) {
        this.callback = callback;
        callback.onPrepared(this);
    }

    @Override
    public void maybeThrowPrepareError() throws IOException {
        manifestLoaderErrorThrower.maybeThrowError();
    }

    @Override
    public TrackGroupArray getTrackGroups() {
        return trackGroups;
    }

    @Override
    public long selectTracks(
            @Nullable ExoTrackSelection[] selections,
            boolean[] mayRetainStreamFlags,
            @Nullable SampleStream[] streams,
            boolean[] streamResetFlags,
            long positionUs) {
        // Release streams that are disabled or can't be retained.
        for (int i = 0; i < selections.length; i++) {
            if (selections[i] == null || !mayRetainStreamFlags[i]) {
                if (streams[i] instanceof ChunkSampleStream) {
                    @SuppressWarnings("unchecked")
                    ChunkSampleStream<SabrChunkSource> stream =
                            (ChunkSampleStream<SabrChunkSource>) streams[i];
                    stream.release(this);
                }
                streams[i] = null;
            }
        }
        // Create new streams, update the retained ones.
        for (int i = 0; i < selections.length; i++) {
            ExoTrackSelection selection = selections[i];
            if (selection == null) {
                continue;
            }
            if (streams[i] == null) {
                streamResetFlags[i] = true;
                int trackGroupIndex = trackGroups.indexOf(selection.getTrackGroup());
                streams[i] = buildSampleStream(trackGroupInfos[trackGroupIndex], selection, positionUs);
            } else if (streams[i] instanceof ChunkSampleStream) {
                @SuppressWarnings("unchecked")
                ChunkSampleStream<SabrChunkSource> stream = (ChunkSampleStream<SabrChunkSource>) streams[i];
                stream.getChunkSource().updateTrackSelection(selection);
            }
        }

        ArrayList<ChunkSampleStream<SabrChunkSource>> sampleStreamList = new ArrayList<>();
        for (SampleStream sampleStream : streams) {
            if (sampleStream instanceof ChunkSampleStream) {
                @SuppressWarnings("unchecked")
                ChunkSampleStream<SabrChunkSource> stream =
                        (ChunkSampleStream<SabrChunkSource>) sampleStream;
                sampleStreamList.add(stream);
            }
        }
        sampleStreams = newSampleStreamArray(sampleStreamList.size());
        sampleStreamList.toArray(sampleStreams);

        compositeSequenceableLoader =
                compositeSequenceableLoaderFactory.create(
                        sampleStreamList,
                        Lists.transform(sampleStreamList, s -> ImmutableList.of(s.primaryTrackType)));
        return positionUs;
    }

    @Override
    public void discardBuffer(long positionUs, boolean toKeyframe) {
        for (ChunkSampleStream<SabrChunkSource> sampleStream : sampleStreams) {
            sampleStream.discardBuffer(positionUs, toKeyframe);
        }
    }

    @Override
    public void reevaluateBuffer(long positionUs) {
        compositeSequenceableLoader.reevaluateBuffer(positionUs);
    }

    @Override
    public boolean continueLoading(LoadingInfo loadingInfo) {
        return compositeSequenceableLoader.continueLoading(loadingInfo);
    }

    @Override
    public boolean isLoading() {
        return compositeSequenceableLoader.isLoading();
    }

    @Override
    public long getNextLoadPositionUs() {
        return compositeSequenceableLoader.getNextLoadPositionUs();
    }

    @Override
    public long readDiscontinuity() {
        return C.TIME_UNSET;
    }

    @Override
    public long getBufferedPositionUs() {
        return compositeSequenceableLoader.getBufferedPositionUs();
    }

    @Override
    public long seekToUs(long positionUs) {
        for (ChunkSampleStream<SabrChunkSource> sampleStream : sampleStreams) {
            sampleStream.seekToUs(positionUs);
        }
        return positionUs;
    }

    @Override
    public long getAdjustedSeekPositionUs(long positionUs, SeekParameters seekParameters) {
        for (ChunkSampleStream<SabrChunkSource> sampleStream : sampleStreams) {
            if (sampleStream.primaryTrackType == C.TRACK_TYPE_VIDEO) {
                return sampleStream.getAdjustedSeekPositionUs(positionUs, seekParameters);
            }
        }
        return positionUs;
    }

    // SequenceableLoader.Callback implementation.

    @Override
    public void onContinueLoadingRequested(ChunkSampleStream<SabrChunkSource> sampleStream) {
        if (callback != null) {
            callback.onContinueLoadingRequested(this);
        }
    }

    // Internal methods.

    @SuppressWarnings("unchecked")
    private static ChunkSampleStream<SabrChunkSource>[] newSampleStreamArray(int length) {
        return new ChunkSampleStream[length];
    }

    private ChunkSampleStream<SabrChunkSource> buildSampleStream(
            TrackGroupInfo trackGroupInfo, ExoTrackSelection selection, long positionUs) {
        SabrChunkSource chunkSource =
                chunkSourceFactory.createSabrChunkSource(
                        manifestLoaderErrorThrower,
                        manifest,
                        periodIndex,
                        trackGroupInfo.adaptationSetIndices,
                        selection,
                        trackGroupInfo.trackType,
                        elapsedRealtimeOffsetMs,
                        /* closedCaptionFormats= */ Collections.emptyList(),
                        transferListener);
        return new ChunkSampleStream<>(
                trackGroupInfo.trackType,
                /* embeddedTrackTypes= */ null,
                /* embeddedTrackFormats= */ null,
                chunkSource,
                this,
                allocator,
                positionUs,
                drmSessionManager,
                drmEventDispatcher,
                loadErrorHandlingPolicy,
                mediaSourceEventDispatcher,
                /* handleInitialDiscontinuity= */ false,
                /* firstChunkStartTimeUs= */ C.TIME_UNSET,
                /* downloadExecutor= */ null);
    }

    private static final class TrackGroupInfo {
        public final int trackType;
        public final int[] adaptationSetIndices;

        TrackGroupInfo(int trackType, int[] adaptationSetIndices) {
            this.trackType = trackType;
            this.adaptationSetIndices = adaptationSetIndices;
        }
    }
}
