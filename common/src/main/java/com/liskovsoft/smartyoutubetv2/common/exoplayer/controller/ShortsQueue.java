package com.liskovsoft.smartyoutubetv2.common.exoplayer.controller;

import com.google.android.exoplayer2.source.ConcatenatingMediaSource;
import com.google.android.exoplayer2.source.MediaSource;
import com.liskovsoft.mediaserviceinterfaces.data.MediaItemFormatInfo;

import java.util.ArrayList;
import java.util.List;

/**
 * SmartTube J: Shorts are played from a player playlist, so ExoPlayer buffers the next Shorts
 * in the background (it starts loading the next item once the current one is fully buffered).
 * Switching to a queued Short is a seek inside the playlist: no new source, no player reset.
 *
 * The id list here is the single source of truth. The player's timeline is updated asynchronously,
 * so a switch is only allowed while both have the same size. Items are never removed during a session
 * (removal is what made the indexes go out of sync); inactive items hold no buffered media.
 *
 * Only accessed from the main thread.
 */
public final class ShortsQueue {
    private static final int MAX_SIZE = 50;
    private static volatile ShortsQueue sActive;
    private final ConcatenatingMediaSource mPlaylist;
    private final List<String> mVideoIds = new ArrayList<>();
    private final List<MediaItemFormatInfo> mFormatInfos = new ArrayList<>();
    private int mCurrentIndex;

    ShortsQueue(MediaItemFormatInfo formatInfo, MediaSource first) {
        mPlaylist = new ConcatenatingMediaSource(first);
        mVideoIds.add(formatInfo.getVideoId());
        mFormatInfos.add(formatInfo);
        mCurrentIndex = 0;
    }

    MediaSource getPlaylist() {
        return mPlaylist;
    }

    int indexOf(String videoId) {
        return videoId != null ? mVideoIds.indexOf(videoId) : -1;
    }

    int size() {
        return mVideoIds.size();
    }

    boolean contains(String videoId) {
        return indexOf(videoId) != -1;
    }

    int getCurrentIndex() {
        return mCurrentIndex;
    }

    String getCurrentVideoId() {
        return mCurrentIndex >= 0 && mCurrentIndex < mVideoIds.size() ? mVideoIds.get(mCurrentIndex) : null;
    }

    void setCurrentIndex(int index) {
        mCurrentIndex = index;
    }

    boolean isFull() {
        return mVideoIds.size() >= MAX_SIZE;
    }

    /**
     * The stream urls of the item are still valid.
     */
    boolean isActual(int index) {
        MediaItemFormatInfo formatInfo = index >= 0 && index < mFormatInfos.size() ? mFormatInfos.get(index) : null;
        return formatInfo != null && formatInfo.isCacheActual();
    }

    /**
     * Insert at the given distance after the current item (1 = plays next), keeping the feed order.
     */
    void insertAt(int distance, MediaItemFormatInfo formatInfo, MediaSource source) {
        int index = Math.min(mCurrentIndex + Math.max(1, distance), mVideoIds.size());
        mPlaylist.addMediaSource(index, source);
        mVideoIds.add(index, formatInfo.getVideoId());
        mFormatInfos.add(index, formatInfo);
    }

    static void setActive(ShortsQueue queue) {
        sActive = queue;
    }

    static ShortsQueue getActive() {
        return sActive;
    }

    /**
     * Used by the UI to skip the "black screen" reset when the video is already in the player.
     */
    public static boolean isQueued(String videoId) {
        ShortsQueue queue = sActive;
        return queue != null && queue.indexOf(videoId) != -1 && queue.indexOf(videoId) != queue.getCurrentIndex();
    }

    /**
     * Format info of a Short that is already in the player (e.g. the previous one), so going back
     * doesn't wait for the network. Null if the video isn't queued (or it's the current one).
     */
    public static MediaItemFormatInfo getQueuedFormatInfo(String videoId) {
        ShortsQueue queue = sActive;

        if (queue == null || !isQueued(videoId)) {
            return null;
        }

        int index = queue.indexOf(videoId);

        return queue.isActual(index) ? queue.mFormatInfos.get(index) : null;
    }
}
