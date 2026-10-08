package com.liskovsoft.smartyoutubetv2.common.exoplayer.other;

import com.google.android.exoplayer2.LoadControl;
import com.google.android.exoplayer2.PlayedPeriodsPolicy;
import com.google.android.exoplayer2.Renderer;
import com.google.android.exoplayer2.source.TrackGroupArray;
import com.google.android.exoplayer2.trackselection.TrackSelectionArray;
import com.google.android.exoplayer2.upstream.Allocator;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.ShortsTransitionState;

/**
 * Wraps the regular load control. For Shorts, the first frame starts after a short buffer
 * instead of the default 2.5 s, so swiping feels instant, and loading continues past the
 * regular buffer size, so the current Short gets fully buffered and the next ones in the queue
 * get preloaded (ExoPlayer starts the next playlist item only after the current one is fully buffered).
 * Regular (non-live) videos keep at least {@link #VOD_MIN_BUFFER_US} buffered, whatever the buffer setting
 * (a 5 s buffer can't absorb a short network dip with high bitrate formats). Live streams keep the original behavior.
 */
public class FastStartLoadControl implements LoadControl, PlayedPeriodsPolicy {
    private static final long SHORTS_START_BUFFER_US = 500_000; // 0.5 s
    // Ahead of the playback position, across the current and the queued Shorts
    private static final long SHORTS_MIN_BUFFER_US = 10_000_000; // always (like prioritizeTimeOverSizeThresholds)
    private static final long SHORTS_MAX_BUFFER_US = 180_000_000; // 3 min
    private static final int SHORTS_MAX_BUFFER_BYTES = 128 * 1024 * 1024; // current + 2 played + preloaded
    private static final long SHORTS_BACK_BUFFER_US = 10 * 60 * 1_000_000L; // keep played Shorts (instant "previous")
    private static final long VOD_MIN_BUFFER_US = 30_000_000; // 30 s
    private final LoadControl mDelegate;
    private final int mShortsMaxBufferBytes;
    private final int mMaxBufferBytes;

    /**
     * @param maxBufferBytes the app's memory limit for the player buffer on this device
     */
    public FastStartLoadControl(LoadControl delegate, int maxBufferBytes) {
        mDelegate = delegate;
        mShortsMaxBufferBytes = maxBufferBytes > 0 ? Math.min(SHORTS_MAX_BUFFER_BYTES, maxBufferBytes) : SHORTS_MAX_BUFFER_BYTES;
        mMaxBufferBytes = maxBufferBytes > 0 ? maxBufferBytes : SHORTS_MAX_BUFFER_BYTES;
    }

    @Override
    public boolean shouldStartPlayback(long bufferedDurationUs, float playbackSpeed, boolean rebuffering) {
        if (!rebuffering && ShortsTransitionState.isShortsMode()) {
            return bufferedDurationUs >= SHORTS_START_BUFFER_US || mDelegate.shouldStartPlayback(bufferedDurationUs, playbackSpeed, false);
        }

        return mDelegate.shouldStartPlayback(bufferedDurationUs, playbackSpeed, rebuffering);
    }

    // BEGIN JoTube: played Shorts stay in the player (with their data), so going back is instant

    @Override
    public boolean shouldRetainPlayedPeriods() {
        // Not when the buffer memory is already used up (then the played one is released as usual)
        return ShortsTransitionState.isShortsMode() && getAllocator().getTotalBytesAllocated() < mShortsMaxBufferBytes;
    }

    @Override
    public long getCurrentBackBufferDurationUs() {
        return ShortsTransitionState.isShortsMode() ? SHORTS_BACK_BUFFER_US : mDelegate.getBackBufferDurationUs();
    }

    // END JoTube

    @Override
    public void onPrepared() {
        mDelegate.onPrepared();
    }

    @Override
    public void onTracksSelected(Renderer[] renderers, TrackGroupArray trackGroups, TrackSelectionArray trackSelections) {
        mDelegate.onTracksSelected(renderers, trackGroups, trackSelections);
    }

    @Override
    public void onStopped() {
        mDelegate.onStopped();
    }

    @Override
    public void onReleased() {
        mDelegate.onReleased();
    }

    @Override
    public Allocator getAllocator() {
        return mDelegate.getAllocator();
    }

    @Override
    public long getBackBufferDurationUs() {
        return mDelegate.getBackBufferDurationUs();
    }

    @Override
    public boolean retainBackBufferFromKeyframe() {
        return mDelegate.retainBackBufferFromKeyframe();
    }

    @Override
    public boolean shouldContinueLoading(long bufferedDurationUs, float playbackSpeed) {
        if (ShortsTransitionState.isShortsMode()) {
            return bufferedDurationUs < SHORTS_MIN_BUFFER_US || (bufferedDurationUs < SHORTS_MAX_BUFFER_US &&
                    getAllocator().getTotalBytesAllocated() < mShortsMaxBufferBytes);
        }

        if (!ShortsTransitionState.isLive() && bufferedDurationUs < VOD_MIN_BUFFER_US &&
                getAllocator().getTotalBytesAllocated() < mMaxBufferBytes) {
            return true;
        }

        return mDelegate.shouldContinueLoading(bufferedDurationUs, playbackSpeed);
    }
}
