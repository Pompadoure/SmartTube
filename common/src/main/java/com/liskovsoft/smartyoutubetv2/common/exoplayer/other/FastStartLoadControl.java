package com.liskovsoft.smartyoutubetv2.common.exoplayer.other;

import com.google.android.exoplayer2.LoadControl;
import com.google.android.exoplayer2.Renderer;
import com.google.android.exoplayer2.source.TrackGroupArray;
import com.google.android.exoplayer2.trackselection.TrackSelectionArray;
import com.google.android.exoplayer2.upstream.Allocator;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.ShortsTransitionState;

/**
 * Wraps the regular load control. For Shorts, the first frame starts after a short buffer
 * instead of the default 2.5 s, so swiping feels instant. Rebuffering and regular videos
 * keep the original behavior.
 */
public class FastStartLoadControl implements LoadControl {
    private static final long SHORTS_START_BUFFER_US = 500_000; // 0.5 s
    private final LoadControl mDelegate;

    public FastStartLoadControl(LoadControl delegate) {
        mDelegate = delegate;
    }

    @Override
    public boolean shouldStartPlayback(long bufferedDurationUs, float playbackSpeed, boolean rebuffering) {
        if (!rebuffering && ShortsTransitionState.isShortsMode()) {
            return bufferedDurationUs >= SHORTS_START_BUFFER_US || mDelegate.shouldStartPlayback(bufferedDurationUs, playbackSpeed, false);
        }

        return mDelegate.shouldStartPlayback(bufferedDurationUs, playbackSpeed, rebuffering);
    }

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
        return mDelegate.shouldContinueLoading(bufferedDurationUs, playbackSpeed);
    }
}
