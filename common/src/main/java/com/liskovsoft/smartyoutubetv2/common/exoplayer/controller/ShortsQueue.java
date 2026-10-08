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
    private int mCurrentIndex;

    ShortsQueue(String videoId, MediaSource first) {
        mPlaylist = new ConcatenatingMediaSource(first);
        mVideoIds.add(videoId);
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

    void setCurrentIndex(int index) {
        mCurrentIndex = index;
    }

    boolean isFull() {
        return mVideoIds.size() >= MAX_SIZE;
    }

    /**
     * Insert right after the current item (that's what plays next).
     */
    void insertNext(String videoId, MediaSource source) {
        int index = mCurrentIndex + 1;
        mPlaylist.addMediaSource(index, source);
        mVideoIds.add(index, videoId);
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
}
