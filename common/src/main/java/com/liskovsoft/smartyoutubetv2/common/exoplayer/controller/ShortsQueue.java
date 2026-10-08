package com.liskovsoft.smartyoutubetv2.common.exoplayer.controller;

import com.google.android.exoplayer2.source.ConcatenatingMediaSource;
import com.google.android.exoplayer2.source.MediaSource;

import java.util.ArrayList;
import java.util.List;

/**
 * SmartTube J: Shorts are played from a player playlist, so ExoPlayer buffers the next Short
 * in the background (it starts loading the next item once the current one is fully buffered).
 * Switching to a queued Short is a seek inside the playlist: no new source, no player reset.
 *
 * Only accessed from the main thread.
 */
public final class ShortsQueue {
    // Keep one item behind the current for instant "previous"
    private static final int KEEP_BEHIND = 1;
    private static volatile ShortsQueue sActive;
    private final ConcatenatingMediaSource mPlaylist;
    private final List<String> mVideoIds = new ArrayList<>();

    ShortsQueue(String videoId, MediaSource first) {
        mPlaylist = new ConcatenatingMediaSource(first);
        mVideoIds.add(videoId);
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

    void append(String videoId, MediaSource source) {
        mPlaylist.addMediaSource(source);
        mVideoIds.add(videoId);
    }

    /**
     * Drop items that are too far behind the current one (saves memory).
     * @return number of removed items (current index shifts by that amount)
     */
    int trimBefore(int currentIndex) {
        int removeCount = currentIndex - KEEP_BEHIND;

        if (removeCount <= 0) {
            return 0;
        }

        mPlaylist.removeMediaSourceRange(0, removeCount);
        mVideoIds.subList(0, removeCount).clear();

        return removeCount;
    }

    /**
     * Drop everything after the current item (e.g. the feed changed direction).
     */
    void trimAfter(int currentIndex) {
        int size = mVideoIds.size();

        if (currentIndex + 1 >= size) {
            return;
        }

        mPlaylist.removeMediaSourceRange(currentIndex + 1, size);
        mVideoIds.subList(currentIndex + 1, size).clear();
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
        return queue != null && queue.contains(videoId);
    }
}
