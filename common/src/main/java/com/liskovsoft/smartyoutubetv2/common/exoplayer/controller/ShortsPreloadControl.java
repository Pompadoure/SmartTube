package com.liskovsoft.smartyoutubetv2.common.exoplayer.controller;

import androidx.media3.exoplayer.source.preload.DefaultPreloadManager.PreloadStatus;
import androidx.media3.exoplayer.source.preload.TargetPreloadStatusControl;

/**
 * JoTube M3: how much of each queued Short {@link androidx.media3.exoplayer.source.preload.DefaultPreloadManager}
 * loads in advance. The ranking data of a Short is its index in the {@link ShortsQueue}; the index of the playing one
 * is set on every switch.
 */
public final class ShortsPreloadControl implements TargetPreloadStatusControl<Integer, PreloadStatus> {
    public static final long NEXT_PRELOAD_MS = 6_000;
    public static final long NEXT_2_PRELOAD_MS = 3_000;
    public static final long PREVIOUS_PRELOAD_MS = 3_000; // the swipe back
    private volatile int mCurrentIndex = -1;

    public void setCurrentIndex(int index) {
        mCurrentIndex = index;
    }

    @Override
    public PreloadStatus getTargetPreloadStatus(Integer index) {
        int distance = index - mCurrentIndex;

        switch (distance) {
            case 1:
                return PreloadStatus.specifiedRangeLoaded(0, NEXT_PRELOAD_MS);
            case 2:
                return PreloadStatus.specifiedRangeLoaded(0, NEXT_2_PRELOAD_MS);
            case -1:
                return PreloadStatus.specifiedRangeLoaded(0, PREVIOUS_PRELOAD_MS);
            default:
                return PreloadStatus.PRELOAD_STATUS_NOT_PRELOADED;
        }
    }
}
