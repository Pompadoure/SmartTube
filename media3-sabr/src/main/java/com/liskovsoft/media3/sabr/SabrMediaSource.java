package com.liskovsoft.media3.sabr;

import android.os.Looper;
import android.os.SystemClock;
import android.util.SparseArray;

import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Timeline;
import androidx.media3.common.util.Assertions;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.common.util.Util;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.TransferListener;
import androidx.media3.exoplayer.drm.DrmSessionEventListener;
import androidx.media3.exoplayer.drm.DrmSessionManager;
import androidx.media3.exoplayer.source.BaseMediaSource;
import androidx.media3.exoplayer.source.CompositeSequenceableLoaderFactory;
import androidx.media3.exoplayer.source.DefaultCompositeSequenceableLoaderFactory;
import androidx.media3.exoplayer.source.MediaPeriod;
import androidx.media3.exoplayer.source.MediaSourceEventListener;
import androidx.media3.exoplayer.upstream.Allocator;
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy;
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy;
import androidx.media3.exoplayer.upstream.Loader;
import androidx.media3.exoplayer.upstream.LoaderErrorThrower;

import com.liskovsoft.media3.sabr.manifest.AdaptationSet;
import com.liskovsoft.media3.sabr.manifest.SabrManifest;

import java.io.IOException;

/**
 * A SABR {@link androidx.media3.exoplayer.source.MediaSource}, modelled on Media3's {@code DashMediaSource}.
 *
 * <p>The manifest is built in memory from the video's format info ({@code SabrManifestParser}), so nothing
 * is downloaded here: the {@link Loader} only exists for the error thrower. JoTube M3: no emsg/manifest
 * refresh callbacks (SABR is VOD only, see docs/media3/SABR_PORT.md).
 */
@UnstableApi
public final class SabrMediaSource extends BaseMediaSource {
    /**
     * The minimum default start position for live streams, relative to the start of the live window.
     */
    private static final long MIN_LIVE_DEFAULT_START_POSITION_US = 5000000;
    /**
     * A constant indicating that the presentation delay for live streams should be set to
     * {@link SabrManifest#suggestedPresentationDelayMs} if specified, and 30 s otherwise.
     */
    private static final long DEFAULT_LIVE_PRESENTATION_DELAY_MS = 30000;

    private final SabrManifest manifest;
    private final SabrChunkSource.Factory chunkSourceFactory;
    private final CompositeSequenceableLoaderFactory compositeSequenceableLoaderFactory;
    private final DrmSessionManager drmSessionManager;
    private final LoadErrorHandlingPolicy loadErrorHandlingPolicy;
    private final LoaderErrorThrower manifestLoadErrorThrower;
    private final long livePresentationDelayMs;
    private final boolean livePresentationDelayOverridesManifest;
    private final SparseArray<SabrMediaPeriod> periodsById;

    @Nullable private TransferListener mediaTransferListener;
    @Nullable private Loader loader;
    @Nullable private IOException manifestFatalError;
    private long elapsedRealtimeOffsetMs;
    private int firstPeriodId;
    private MediaItem mediaItem;

    private SabrMediaSource(
            SabrManifest manifest,
            MediaItem mediaItem,
            SabrChunkSource.Factory chunkSourceFactory,
            CompositeSequenceableLoaderFactory compositeSequenceableLoaderFactory,
            DrmSessionManager drmSessionManager,
            LoadErrorHandlingPolicy loadErrorHandlingPolicy,
            long livePresentationDelayMs,
            boolean livePresentationDelayOverridesManifest) {
        this.manifest = manifest;
        this.mediaItem = mediaItem;
        this.chunkSourceFactory = chunkSourceFactory;
        this.compositeSequenceableLoaderFactory = compositeSequenceableLoaderFactory;
        this.drmSessionManager = drmSessionManager;
        this.loadErrorHandlingPolicy = loadErrorHandlingPolicy;
        this.livePresentationDelayMs = livePresentationDelayMs;
        this.livePresentationDelayOverridesManifest = livePresentationDelayOverridesManifest;
        periodsById = new SparseArray<>();
        manifestLoadErrorThrower = new ManifestLoadErrorThrower();
    }

    /** The in-memory manifest this source plays. */
    public SabrManifest getManifest() {
        return manifest;
    }

    // MediaSource implementation.

    @Override
    public synchronized MediaItem getMediaItem() {
        return mediaItem;
    }

    @Override
    public boolean canUpdateMediaItem(MediaItem mediaItem) {
        return false;
    }

    @Override
    protected void prepareSourceInternal(@Nullable TransferListener mediaTransferListener) {
        this.mediaTransferListener = mediaTransferListener;
        drmSessionManager.setPlayer(Assertions.checkNotNull(Looper.myLooper()), getPlayerId());
        drmSessionManager.prepare();
        loader = new Loader("Loader:SabrMediaSource");
        processManifest();
    }

    @Override
    public void maybeThrowSourceInfoRefreshError() throws IOException {
        manifestLoadErrorThrower.maybeThrowError();
    }

    @Override
    public MediaPeriod createPeriod(MediaPeriodId periodId, Allocator allocator, long startPositionUs) {
        int periodIndex = (Integer) periodId.periodUid - firstPeriodId;
        MediaSourceEventListener.EventDispatcher periodEventDispatcher = createEventDispatcher(periodId);
        DrmSessionEventListener.EventDispatcher drmEventDispatcher = createDrmEventDispatcher(periodId);
        SabrMediaPeriod mediaPeriod =
                new SabrMediaPeriod(
                        firstPeriodId + periodIndex,
                        manifest,
                        periodIndex,
                        chunkSourceFactory,
                        mediaTransferListener,
                        drmSessionManager,
                        drmEventDispatcher,
                        loadErrorHandlingPolicy,
                        periodEventDispatcher,
                        elapsedRealtimeOffsetMs,
                        manifestLoadErrorThrower,
                        allocator,
                        compositeSequenceableLoaderFactory);
        periodsById.put(mediaPeriod.id, mediaPeriod);
        return mediaPeriod;
    }

    @Override
    public void releasePeriod(MediaPeriod mediaPeriod) {
        SabrMediaPeriod sabrMediaPeriod = (SabrMediaPeriod) mediaPeriod;
        sabrMediaPeriod.release();
        periodsById.remove(sabrMediaPeriod.id);
    }

    @Override
    protected void releaseSourceInternal() {
        if (loader != null) {
            loader.release();
            loader = null;
        }
        elapsedRealtimeOffsetMs = 0;
        manifestFatalError = null;
        firstPeriodId = 0;
        periodsById.clear();
        drmSessionManager.release();
    }

    // Internal methods.

    private void processManifest() {
        // Update any periods.
        for (int i = 0; i < periodsById.size(); i++) {
            int id = periodsById.keyAt(i);
            if (id >= firstPeriodId) {
                periodsById.valueAt(i).updateManifest(manifest, id - firstPeriodId);
            }
            // Else: the period has been removed from the manifest, keep it until it's released.
        }
        // Update the window.
        int lastPeriodIndex = manifest.getPeriodCount() - 1;
        PeriodSeekInfo firstPeriodSeekInfo =
                PeriodSeekInfo.createPeriodSeekInfo(manifest.getPeriod(0), manifest.getPeriodDurationUs(0));
        PeriodSeekInfo lastPeriodSeekInfo =
                PeriodSeekInfo.createPeriodSeekInfo(
                        manifest.getPeriod(lastPeriodIndex), manifest.getPeriodDurationUs(lastPeriodIndex));
        // Get the period-relative start/end times.
        long currentStartTimeUs = firstPeriodSeekInfo.availableStartTimeUs;
        long currentEndTimeUs = lastPeriodSeekInfo.availableEndTimeUs;
        if (manifest.dynamic && !lastPeriodSeekInfo.isIndexExplicit) {
            // The manifest describes an incomplete live stream. Update the start/end times to reflect the
            // live stream duration and the manifest's time shift buffer depth.
            long liveStreamDurationUs = getNowUnixTimeUs() - Util.msToUs(manifest.availabilityStartTimeMs);
            long liveStreamEndPositionInLastPeriodUs =
                    liveStreamDurationUs - Util.msToUs(manifest.getPeriod(lastPeriodIndex).startMs);
            currentEndTimeUs = Math.min(liveStreamEndPositionInLastPeriodUs, currentEndTimeUs);
            if (manifest.timeShiftBufferDepthMs != C.TIME_UNSET) {
                long timeShiftBufferDepthUs = Util.msToUs(manifest.timeShiftBufferDepthMs);
                long offsetInPeriodUs = currentEndTimeUs - timeShiftBufferDepthUs;
                int periodIndex = lastPeriodIndex;
                while (offsetInPeriodUs < 0 && periodIndex > 0) {
                    offsetInPeriodUs += manifest.getPeriodDurationUs(--periodIndex);
                }
                if (periodIndex == 0) {
                    currentStartTimeUs = Math.max(currentStartTimeUs, offsetInPeriodUs);
                } else {
                    // The time shift buffer starts after the earliest period.
                    currentStartTimeUs = manifest.getPeriodDurationUs(0);
                }
            }
        }
        long windowDurationUs = currentEndTimeUs - currentStartTimeUs;
        for (int i = 0; i < manifest.getPeriodCount() - 1; i++) {
            windowDurationUs += manifest.getPeriodDurationUs(i);
        }
        long windowDefaultStartPositionUs = 0;
        if (manifest.dynamic) {
            long presentationDelayForManifestMs = livePresentationDelayMs;
            if (!livePresentationDelayOverridesManifest
                    && manifest.suggestedPresentationDelayMs != C.TIME_UNSET) {
                presentationDelayForManifestMs = manifest.suggestedPresentationDelayMs;
            }
            // Snap the default position to the start of the segment containing it.
            windowDefaultStartPositionUs = windowDurationUs - Util.msToUs(presentationDelayForManifestMs);
            if (windowDefaultStartPositionUs < MIN_LIVE_DEFAULT_START_POSITION_US) {
                // The default start position is too close to the start of the live window. Set it to the
                // minimum default start position provided the window is at least twice as big. Else set
                // it to the middle of the window.
                windowDefaultStartPositionUs =
                        Math.min(MIN_LIVE_DEFAULT_START_POSITION_US, windowDurationUs / 2);
            }
        }
        long windowStartTimeMs =
                manifest.availabilityStartTimeMs
                        + manifest.getPeriod(0).startMs
                        + Util.usToMs(currentStartTimeUs);
        SabrTimeline timeline =
                new SabrTimeline(
                        manifest.availabilityStartTimeMs,
                        windowStartTimeMs,
                        firstPeriodId,
                        currentStartTimeUs,
                        windowDurationUs,
                        windowDefaultStartPositionUs,
                        manifest,
                        getMediaItem());
        refreshSourceInfo(timeline);
    }

    private long getNowUnixTimeUs() {
        if (elapsedRealtimeOffsetMs != 0) {
            return Util.msToUs(SystemClock.elapsedRealtime() + elapsedRealtimeOffsetMs);
        } else {
            return Util.msToUs(System.currentTimeMillis());
        }
    }

    /** Factory for {@link SabrMediaSource}s. */
    public static final class Factory {
        private final SabrChunkSource.Factory chunkSourceFactory;
        @Nullable private final DataSource.Factory manifestDataSourceFactory;
        private final CompositeSequenceableLoaderFactory compositeSequenceableLoaderFactory;
        private DrmSessionManager drmSessionManager;
        private LoadErrorHandlingPolicy loadErrorHandlingPolicy;
        private long livePresentationDelayMs;
        private boolean livePresentationDelayOverridesManifest;

        /**
         * @param chunkSourceFactory A factory for {@link SabrChunkSource} instances.
         * @param manifestDataSourceFactory Unused (the manifest is built in memory), kept for the app's
         *     call sites. May be null.
         */
        public Factory(
                SabrChunkSource.Factory chunkSourceFactory,
                @Nullable DataSource.Factory manifestDataSourceFactory) {
            this.chunkSourceFactory = chunkSourceFactory;
            this.manifestDataSourceFactory = manifestDataSourceFactory;
            drmSessionManager = DrmSessionManager.DRM_UNSUPPORTED;
            loadErrorHandlingPolicy = new DefaultLoadErrorHandlingPolicy();
            livePresentationDelayMs = DEFAULT_LIVE_PRESENTATION_DELAY_MS;
            compositeSequenceableLoaderFactory = new DefaultCompositeSequenceableLoaderFactory();
        }

        /** Sets the {@link LoadErrorHandlingPolicy}. The default is {@link DefaultLoadErrorHandlingPolicy}. */
        public Factory setLoadErrorHandlingPolicy(LoadErrorHandlingPolicy loadErrorHandlingPolicy) {
            this.loadErrorHandlingPolicy = Assertions.checkNotNull(loadErrorHandlingPolicy);
            return this;
        }

        /** Sets the {@link DrmSessionManager}. The default is {@link DrmSessionManager#DRM_UNSUPPORTED}. */
        public Factory setDrmSessionManager(DrmSessionManager drmSessionManager) {
            this.drmSessionManager = Assertions.checkNotNull(drmSessionManager);
            return this;
        }

        /** Sets the presentation delay for live streams (unused for SABR VOD). */
        public Factory setLivePresentationDelayMs(long livePresentationDelayMs, boolean overridesManifest) {
            this.livePresentationDelayMs = livePresentationDelayMs;
            this.livePresentationDelayOverridesManifest = overridesManifest;
            return this;
        }

        /** Creates a source for the manifest, with a {@link MediaItem} named after the video id. */
        public SabrMediaSource createMediaSource(SabrManifest manifest) {
            String videoId = manifest.getVideoId();
            MediaItem mediaItem =
                    new MediaItem.Builder()
                            .setMediaId(videoId != null ? videoId : MediaItem.DEFAULT_MEDIA_ID)
                            .build();
            return createMediaSource(manifest, mediaItem);
        }

        /** Creates a source for the manifest with the given {@link MediaItem}. */
        public SabrMediaSource createMediaSource(SabrManifest manifest, MediaItem mediaItem) {
            return new SabrMediaSource(
                    manifest,
                    mediaItem,
                    chunkSourceFactory,
                    compositeSequenceableLoaderFactory,
                    drmSessionManager,
                    loadErrorHandlingPolicy,
                    livePresentationDelayMs,
                    livePresentationDelayOverridesManifest);
        }
    }

    /* package */ final class ManifestLoadErrorThrower implements LoaderErrorThrower {

        @Override
        public void maybeThrowError() throws IOException {
            if (loader != null) {
                loader.maybeThrowError();
            }
            maybeThrowManifestError();
        }

        @Override
        public void maybeThrowError(int minRetryCount) throws IOException {
            if (loader != null) {
                loader.maybeThrowError(minRetryCount);
            }
            maybeThrowManifestError();
        }

        private void maybeThrowManifestError() throws IOException {
            if (manifestFatalError != null) {
                throw manifestFatalError;
            }
        }
    }

    private static final class SabrTimeline extends Timeline {

        private final long presentationStartTimeMs;
        private final long windowStartTimeMs;
        private final int firstPeriodId;
        private final long offsetInFirstPeriodUs;
        private final long windowDurationUs;
        private final long windowDefaultStartPositionUs;
        private final SabrManifest manifest;
        private final MediaItem mediaItem;

        public SabrTimeline(
                long presentationStartTimeMs,
                long windowStartTimeMs,
                int firstPeriodId,
                long offsetInFirstPeriodUs,
                long windowDurationUs,
                long windowDefaultStartPositionUs,
                SabrManifest manifest,
                MediaItem mediaItem) {
            this.presentationStartTimeMs = presentationStartTimeMs;
            this.windowStartTimeMs = windowStartTimeMs;
            this.firstPeriodId = firstPeriodId;
            this.offsetInFirstPeriodUs = offsetInFirstPeriodUs;
            this.windowDurationUs = windowDurationUs;
            this.windowDefaultStartPositionUs = windowDefaultStartPositionUs;
            this.manifest = manifest;
            this.mediaItem = mediaItem;
        }

        @Override
        public int getPeriodCount() {
            return manifest.getPeriodCount();
        }

        @Override
        public Period getPeriod(int periodIndex, Period period, boolean setIdentifiers) {
            Assertions.checkIndex(periodIndex, 0, getPeriodCount());
            Object id = setIdentifiers ? manifest.getPeriod(periodIndex).id : null;
            Object uid = setIdentifiers ? (firstPeriodId + periodIndex) : null;
            return period.set(
                    id,
                    uid,
                    /* windowIndex= */ 0,
                    manifest.getPeriodDurationUs(periodIndex),
                    Util.msToUs(manifest.getPeriod(periodIndex).startMs - manifest.getPeriod(0).startMs)
                            - offsetInFirstPeriodUs);
        }

        @Override
        public int getWindowCount() {
            return 1;
        }

        @Override
        public Window getWindow(int windowIndex, Window window, long defaultPositionProjectionUs) {
            Assertions.checkIndex(windowIndex, 0, 1);
            long windowDefaultStartPositionUs =
                    getAdjustedWindowDefaultStartPositionUs(defaultPositionProjectionUs);
            boolean isDynamic =
                    manifest.dynamic
                            && manifest.minUpdatePeriodMs != C.TIME_UNSET
                            && manifest.durationMs == C.TIME_UNSET;
            return window.set(
                    Window.SINGLE_WINDOW_UID,
                    mediaItem,
                    manifest,
                    presentationStartTimeMs,
                    windowStartTimeMs,
                    /* elapsedRealtimeEpochOffsetMs= */ C.TIME_UNSET,
                    /* isSeekable= */ true,
                    isDynamic,
                    /* liveConfiguration= */ isDynamic ? mediaItem.liveConfiguration : null,
                    windowDefaultStartPositionUs,
                    windowDurationUs,
                    /* firstPeriodIndex= */ 0,
                    /* lastPeriodIndex= */ getPeriodCount() - 1,
                    offsetInFirstPeriodUs);
        }

        @Override
        public int getIndexOfPeriod(Object uid) {
            if (!(uid instanceof Integer)) {
                return C.INDEX_UNSET;
            }
            int periodId = (int) uid;
            int periodIndex = periodId - firstPeriodId;
            return periodIndex < 0 || periodIndex >= getPeriodCount() ? C.INDEX_UNSET : periodIndex;
        }

        @Override
        public Object getUidOfPeriod(int periodIndex) {
            Assertions.checkIndex(periodIndex, 0, getPeriodCount());
            return firstPeriodId + periodIndex;
        }

        private long getAdjustedWindowDefaultStartPositionUs(long defaultPositionProjectionUs) {
            long windowDefaultStartPositionUs = this.windowDefaultStartPositionUs;
            if (!manifest.dynamic) {
                return windowDefaultStartPositionUs;
            }
            if (defaultPositionProjectionUs > 0) {
                windowDefaultStartPositionUs += defaultPositionProjectionUs;
                if (windowDefaultStartPositionUs > windowDurationUs) {
                    // The projection takes us beyond the end of the live window.
                    return C.TIME_UNSET;
                }
            }
            // The 2.10.6 SABR code had no segment-based snapping here (start time "TODO: calc SABR start
            // time" = 0), so the projected default position is used as is.
            return windowDefaultStartPositionUs;
        }
    }

    private static final class PeriodSeekInfo {

        public static PeriodSeekInfo createPeriodSeekInfo(
                com.liskovsoft.media3.sabr.manifest.Period period, long durationUs) {
            int adaptationSetCount = period.adaptationSets.size();
            long availableStartTimeUs = 0;
            long availableEndTimeUs = Long.MAX_VALUE;
            boolean isIndexExplicit = false;
            boolean seenEmptyIndex = false;

            boolean haveAudioVideoAdaptationSets = false;
            for (int i = 0; i < adaptationSetCount; i++) {
                int type = period.adaptationSets.get(i).type;
                if (type == C.TRACK_TYPE_AUDIO || type == C.TRACK_TYPE_VIDEO) {
                    haveAudioVideoAdaptationSets = true;
                    break;
                }
            }

            for (int i = 0; i < adaptationSetCount; i++) {
                AdaptationSet adaptationSet = period.adaptationSets.get(i);
                // Exclude text adaptation sets from duration calculations, if we have at least one audio
                // or video adaptation set. See: https://github.com/google/ExoPlayer/issues/4029
                if (haveAudioVideoAdaptationSets && adaptationSet.type == C.TRACK_TYPE_TEXT) {
                    continue;
                }

                SabrSegmentIndex index = adaptationSet.representations.get(0).getIndex();
                if (index == null) {
                    return new PeriodSeekInfo(true, 0, durationUs);
                }
                isIndexExplicit |= index.isExplicit();
                long segmentCount = index.getSegmentCount(durationUs);
                if (segmentCount == 0) {
                    seenEmptyIndex = true;
                    availableStartTimeUs = 0;
                    availableEndTimeUs = 0;
                } else if (!seenEmptyIndex) {
                    long firstSegmentNum = index.getFirstSegmentNum();
                    long adaptationSetAvailableStartTimeUs = index.getTimeUs(firstSegmentNum);
                    availableStartTimeUs = Math.max(availableStartTimeUs, adaptationSetAvailableStartTimeUs);
                    if (segmentCount != SabrSegmentIndex.INDEX_UNBOUNDED) {
                        long lastSegmentNum = firstSegmentNum + segmentCount - 1;
                        long adaptationSetAvailableEndTimeUs =
                                index.getTimeUs(lastSegmentNum) + index.getDurationUs(lastSegmentNum, durationUs);
                        availableEndTimeUs = Math.min(availableEndTimeUs, adaptationSetAvailableEndTimeUs);
                    }
                }
            }
            return new PeriodSeekInfo(isIndexExplicit, availableStartTimeUs, availableEndTimeUs);
        }

        public final boolean isIndexExplicit;
        public final long availableStartTimeUs;
        public final long availableEndTimeUs;

        private PeriodSeekInfo(boolean isIndexExplicit, long availableStartTimeUs, long availableEndTimeUs) {
            this.isIndexExplicit = isIndexExplicit;
            this.availableStartTimeUs = availableStartTimeUs;
            this.availableEndTimeUs = availableEndTimeUs;
        }
    }
}
