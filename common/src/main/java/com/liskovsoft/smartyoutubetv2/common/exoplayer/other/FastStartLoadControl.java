package com.liskovsoft.smartyoutubetv2.common.exoplayer.other;

import androidx.annotation.Nullable;
import androidx.media3.common.Timeline;
import androidx.media3.exoplayer.LoadControl;
import androidx.media3.exoplayer.analytics.PlayerId;
import androidx.media3.exoplayer.source.MediaSource.MediaPeriodId;
import androidx.media3.exoplayer.source.TrackGroupArray;
import androidx.media3.exoplayer.trackselection.ExoTrackSelection;
import androidx.media3.exoplayer.upstream.Allocator;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.ShortsTransitionState;

/**
 * Wraps the regular load control. For Shorts, the first frame starts after a short buffer
 * instead of the default 2.5 s, so swiping feels instant, and loading continues past the
 * regular buffer size, so the current Short gets fully buffered and the next ones in the queue
 * get preloaded (ExoPlayer starts the next playlist item only after the current one is fully buffered).
 * Regular (non-live) videos keep at least {@link #VOD_MIN_BUFFER_US} buffered, whatever the buffer setting
 * (a 5 s buffer can't absorb a short network dip with high bitrate formats). Live streams keep the original behavior.
 */
public class FastStartLoadControl implements LoadControl {
    private static final long SHORTS_START_BUFFER_US = 500_000; // 0.5 s
    // Ahead of the playback position, across the current and the queued Shorts
    private static final long SHORTS_MIN_BUFFER_US = 10_000_000; // always (like prioritizeTimeOverSizeThresholds)
    private static final long SHORTS_MAX_BUFFER_US = 180_000_000; // 3 min
    private static final int SHORTS_MAX_BUFFER_BYTES = 128 * 1024 * 1024; // current + 2 played + preloaded
    private static final long SHORTS_BACK_BUFFER_US = 10 * 60 * 1_000_000L; // keep played Shorts (instant "previous")
    private static final long VOD_MIN_BUFFER_US = 30_000_000; // 30 s
    private static final long VOD_START_BUFFER_US = 1_000_000; // 1 s
    private final LoadControl mDelegate;
    private final int mShortsMaxBufferBytes;
    private final int mMaxBufferBytes;
    @Nullable private PlayerId mPlayerId;

    /**
     * @param maxBufferBytes the app's memory limit for the player buffer on this device
     */
    public FastStartLoadControl(LoadControl delegate, int maxBufferBytes) {
        mDelegate = delegate;
        mShortsMaxBufferBytes = maxBufferBytes > 0 ? Math.min(SHORTS_MAX_BUFFER_BYTES, maxBufferBytes) : SHORTS_MAX_BUFFER_BYTES;
        mMaxBufferBytes = maxBufferBytes > 0 ? maxBufferBytes : SHORTS_MAX_BUFFER_BYTES;
    }

    @Override
    public boolean shouldStartPlayback(Parameters parameters) {
        boolean rebuffering = parameters.rebuffering;
        long bufferedDurationUs = parameters.bufferedDurationUs;

        if (!rebuffering && ShortsTransitionState.isShortsMode()) {
            return bufferedDurationUs >= SHORTS_START_BUFFER_US || mDelegate.shouldStartPlayback(parameters);
        }

        if (!rebuffering && !ShortsTransitionState.isLive()) {
            // Regular video: start after 1 s instead of 2.5 s (the adaptive start quality keeps the first chunk small)
            return bufferedDurationUs >= VOD_START_BUFFER_US || mDelegate.shouldStartPlayback(parameters);
        }

        return mDelegate.shouldStartPlayback(parameters);
    }

    // BEGIN JoTube: played Shorts stay in the player (with their data), so going back is instant
    // M3: not called by Media3 (the fork's PlayedPeriodsPolicy hook); stage 4 decides how this maps to Media3

    public boolean shouldRetainPlayedPeriods() {
        // Not when the buffer memory is already used up (then the played one is released as usual)
        return ShortsTransitionState.isShortsMode() && mPlayerId != null &&
                getAllocator(mPlayerId).getTotalBytesAllocated() < mShortsMaxBufferBytes;
    }

    public boolean shouldPreloadQueuedPeriods() {
        return ShortsTransitionState.isShortsMode();
    }

    public long getCurrentBackBufferDurationUs() {
        return ShortsTransitionState.isShortsMode() ? SHORTS_BACK_BUFFER_US :
                (mPlayerId != null ? mDelegate.getBackBufferDurationUs(mPlayerId) : 0);
    }

    // END JoTube

    @Override
    public void onPrepared(PlayerId playerId) {
        mPlayerId = playerId;
        mDelegate.onPrepared(playerId);
    }

    @Override
    public void onTracksSelected(Parameters parameters, TrackGroupArray trackGroups, @Nullable ExoTrackSelection[] trackSelections) {
        mDelegate.onTracksSelected(parameters, trackGroups, trackSelections);
    }

    @Override
    public void onStopped(PlayerId playerId) {
        mDelegate.onStopped(playerId);
    }

    @Override
    public void onReleased(PlayerId playerId) {
        mDelegate.onReleased(playerId);
    }

    @Override
    public Allocator getAllocator(PlayerId playerId) {
        return mDelegate.getAllocator(playerId);
    }

    @Override
    public long getBackBufferDurationUs(PlayerId playerId) {
        return ShortsTransitionState.isShortsMode() ? SHORTS_BACK_BUFFER_US : mDelegate.getBackBufferDurationUs(playerId);
    }

    @Override
    public boolean retainBackBufferFromKeyframe(PlayerId playerId) {
        return ShortsTransitionState.isShortsMode() || mDelegate.retainBackBufferFromKeyframe(playerId);
    }

    @Override
    public boolean shouldContinuePreloading(PlayerId playerId, Timeline timeline, MediaPeriodId mediaPeriodId, long bufferedDurationUs) {
        return mDelegate.shouldContinuePreloading(playerId, timeline, mediaPeriodId, bufferedDurationUs);
    }

    @Override
    public boolean shouldContinueLoading(Parameters parameters) {
        long bufferedDurationUs = parameters.bufferedDurationUs;
        Allocator allocator = getAllocator(parameters.playerId);

        if (ShortsTransitionState.isShortsMode()) {
            return bufferedDurationUs < SHORTS_MIN_BUFFER_US || (bufferedDurationUs < SHORTS_MAX_BUFFER_US &&
                    allocator.getTotalBytesAllocated() < mShortsMaxBufferBytes);
        }

        if (!ShortsTransitionState.isLive() && bufferedDurationUs < VOD_MIN_BUFFER_US &&
                allocator.getTotalBytesAllocated() < mMaxBufferBytes) {
            return true;
        }

        return mDelegate.shouldContinueLoading(parameters);
    }
}
