package com.liskovsoft.smartyoutubetv2.common.m3;

import android.content.Context;

import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.preload.DefaultPreloadManager;
import androidx.media3.exoplayer.source.chunk.ChunkSampleStream;
import androidx.media3.exoplayer.dash.DashMediaSource;

/** Toolchain probe for JoTube M3 (step 1d): does Media3 compile and package with this build setup? */
public final class M3Probe {
    private M3Probe() {}

    public static Object probe(Context context) {
        ExoPlayer player = new ExoPlayer.Builder(context).build();
        Class<?>[] classes = {DefaultPreloadManager.class, ChunkSampleStream.class, DashMediaSource.class};
        return classes.length > 0 ? player : null;
    }
}
