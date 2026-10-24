/*
 * Copyright (C) 2017 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.liskovsoft.smartyoutubetv2.tv.ui.playback.other;

// JoTube M3: copy of Media3 1.11.1's androidx.media3.ui.leanback.LeanbackPlayerAdapter (final there) with the fork's
// fix: no progress updates while there's no surface (memory leak fix, see "MOD"). Keep it in sync with the Media3 version.

import android.content.Context;
import android.os.Handler;
import android.util.Pair;
import android.view.Surface;
import android.view.SurfaceHolder;
import androidx.annotation.Nullable;
import androidx.leanback.R;
import androidx.leanback.media.PlaybackGlueHost;
import androidx.leanback.media.PlayerAdapter;
import androidx.leanback.media.SurfaceHolderGlueHost;
import androidx.media3.common.C;
import androidx.media3.common.ErrorMessageProvider;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.Player.DiscontinuityReason;
import androidx.media3.common.Player.TimelineChangeReason;
import androidx.media3.common.Timeline;
import androidx.media3.common.VideoSize;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.common.util.Util;

/** Leanback {@code PlayerAdapter} implementation for {@link Player}. */
@UnstableApi
public final class JoLeanbackPlayerAdapter extends PlayerAdapter implements Runnable {

  private final Context context;
  private Player player; // JoTube role swap: the players of two Shorts swap ({@link #setPlayer})
  @Nullable private Surface surface;
  private final Handler handler;
  private final PlayerListener playerListener;
  private final int updatePeriodMs;

  @Nullable private ErrorMessageProvider<? super PlaybackException> errorMessageProvider;
  @Nullable private SurfaceHolderGlueHost surfaceHolderGlueHost;
  private boolean hasSurface;
  private boolean progressUpdatingEnabled; // MOD
  private boolean lastNotifiedPreparedState;

  /**
   * Builds an instance. Note that the {@code PlayerAdapter} does not manage the lifecycle of the
   * {@link Player} instance. The caller remains responsible for releasing the player when it's no
   * longer required.
   *
   * @param context The current {@link Context} (activity).
   * @param player The {@link Player} being used.
   * @param updatePeriodMs The delay between player control updates, in milliseconds.
   */
  public JoLeanbackPlayerAdapter(Context context, Player player, final int updatePeriodMs) {
    this.context = context;
    this.player = player;
    this.updatePeriodMs = updatePeriodMs;
    handler = Util.createHandlerForCurrentOrMainLooper();
    playerListener = new PlayerListener();
  }

  /**
   * Sets the optional {@link ErrorMessageProvider}.
   *
   * @param errorMessageProvider The {@link ErrorMessageProvider}.
   */
  public void setErrorMessageProvider(
      @Nullable ErrorMessageProvider<? super PlaybackException> errorMessageProvider) {
    this.errorMessageProvider = errorMessageProvider;
  }

  // PlayerAdapter implementation.

  @Override
  public void onAttachedToHost(PlaybackGlueHost host) {
    if (host instanceof SurfaceHolderGlueHost) {
      surfaceHolderGlueHost = ((SurfaceHolderGlueHost) host);
      surfaceHolderGlueHost.setSurfaceHolderCallback(playerListener);
    }
    notifyStateChanged();
    player.addListener(playerListener);
  }

  // dereference of possibly-null reference callback
  @SuppressWarnings("nullness:dereference.of.nullable")
  @Override
  public void onDetachedFromHost() {
    player.removeListener(playerListener);
    if (surfaceHolderGlueHost != null) {
      removeSurfaceHolderCallback(surfaceHolderGlueHost);
      surfaceHolderGlueHost = null;
    }
    hasSurface = false;
    Callback callback = getCallback();
    callback.onBufferingStateChanged(this, false);
    callback.onPlayStateChanged(this);
    maybeNotifyPreparedStateChanged(callback);
  }

  /**
   * JoTube role swap (Shorts): another player becomes the one shown and controlled. The old one leaves the surface
   * first (a surface has one producer at a time; ExoPlayer waits until it is detached), then the new one takes it.
   */
  /** JoTube role swap: the main surface exists (a player can be moved onto it). */
  public boolean hasVideoSurface() {
    return surface != null;
  }

  public void setPlayer(Player newPlayer) {
    if (newPlayer == null || newPlayer == player) {
      return;
    }
    Player old = player;
    old.removeListener(playerListener);
    if (surface != null) {
      old.clearVideoSurface(surface);
    }
    player = newPlayer;
    if (surface != null) {
      newPlayer.setVideoSurface(surface);
    }
    newPlayer.addListener(playerListener);
    Callback callback = getCallback();
    if (callback != null) {
      notifyStateChanged();
      callback.onDurationChanged(this);
      callback.onCurrentPositionChanged(this);
      callback.onBufferedPositionChanged(this);
    }
  }

  @Override
  public void setProgressUpdatingEnabled(boolean enable) {
    progressUpdatingEnabled = enable; // MOD
    handler.removeCallbacks(this);
    if (enable) {
      handler.post(this);
    }
  }

  @Override
  public boolean isPlaying() {
    return !Util.shouldShowPlayButton(player);
  }

  @Override
  public long getDuration() {
    long durationMs = player.getDuration();
    return durationMs == C.TIME_UNSET ? -1 : durationMs;
  }

  @Override
  public long getCurrentPosition() {
    return player.getPlaybackState() == Player.STATE_IDLE ? -1 : player.getCurrentPosition();
  }

  // dereference of possibly-null reference getCallback()
  @SuppressWarnings("nullness:dereference.of.nullable")
  @Override
  public void play() {
    if (Util.handlePlayButtonAction(player)) {
      getCallback().onPlayStateChanged(this);
    }
  }

  // dereference of possibly-null reference getCallback()
  @SuppressWarnings("nullness:dereference.of.nullable")
  @Override
  public void pause() {
    if (Util.handlePauseButtonAction(player)) {
      getCallback().onPlayStateChanged(this);
    }
  }

  @Override
  public void seekTo(long positionInMs) {
    player.seekTo(player.getCurrentMediaItemIndex(), positionInMs);
  }

  @Override
  public long getBufferedPosition() {
    return player.getBufferedPosition();
  }

  @Override
  public boolean isPrepared() {
    return player.getPlaybackState() != Player.STATE_IDLE
        && (surfaceHolderGlueHost == null || hasSurface);
  }

  // Runnable implementation.

  // dereference of possibly-null reference callback
  @SuppressWarnings("nullness:dereference.of.nullable")
  @Override
  public void run() {
    if (!hasSurface) { // MOD: mem leak fix (SegmentTimelineElement), from the ExoPlayer 2.10 fork
      return;
    }

    Callback callback = getCallback();
    callback.onCurrentPositionChanged(this);
    callback.onBufferedPositionChanged(this);
    handler.postDelayed(this, updatePeriodMs);
  }

  // Internal methods.

  /* package */
  // incompatible argument for parameter callback of maybeNotifyPreparedStateChanged.
  @SuppressWarnings("nullness:argument.type.incompatible")
  void setVideoSurface(@Nullable Surface surface) {
    hasSurface = surface != null;
    this.surface = surface;
    player.setVideoSurface(surface);
    if (hasSurface && progressUpdatingEnabled) { // MOD: run() stopped while there was no surface, restart it
      handler.removeCallbacks(this);
      handler.post(this);
    }
    maybeNotifyPreparedStateChanged(getCallback());
  }

  /* package */
  // incompatible argument for parameter callback of maybeNotifyPreparedStateChanged.
  @SuppressWarnings("nullness:argument.type.incompatible")
  void notifyStateChanged() {
    int playbackState = player.getPlaybackState();
    Callback callback = getCallback();
    maybeNotifyPreparedStateChanged(callback);
    callback.onPlayStateChanged(this);
    callback.onBufferingStateChanged(this, playbackState == Player.STATE_BUFFERING);
    if (playbackState == Player.STATE_ENDED) {
      callback.onPlayCompleted(this);
    }
  }

  private void maybeNotifyPreparedStateChanged(Callback callback) {
    boolean isPrepared = isPrepared();
    if (lastNotifiedPreparedState != isPrepared) {
      lastNotifiedPreparedState = isPrepared;
      callback.onPreparedStateChanged(this);
    }
  }

  @SuppressWarnings("nullness:argument")
  private static void removeSurfaceHolderCallback(SurfaceHolderGlueHost surfaceHolderGlueHost) {
    surfaceHolderGlueHost.setSurfaceHolderCallback(null);
  }

  private final class PlayerListener implements Player.Listener, SurfaceHolder.Callback {

    // SurfaceHolder.Callback implementation.

    @Override
    public void surfaceCreated(SurfaceHolder surfaceHolder) {
      setVideoSurface(surfaceHolder.getSurface());
    }

    @Override
    public void surfaceChanged(SurfaceHolder surfaceHolder, int format, int width, int height) {
      // Do nothing.
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder surfaceHolder) {
      setVideoSurface(null);
    }

    // Player.Listener implementation.

    // dereference of possibly-null reference callback
    @SuppressWarnings("nullness:dereference.of.nullable")
    @Override
    public void onPlayerError(PlaybackException error) {
      Callback callback = getCallback();
      if (errorMessageProvider != null) {
        Pair<Integer, String> errorMessage = errorMessageProvider.getErrorMessage(error);
        callback.onError(JoLeanbackPlayerAdapter.this, errorMessage.first, errorMessage.second);
      } else {
        callback.onError(
            JoLeanbackPlayerAdapter.this,
            error.errorCode,
            // This string was probably tailored for MediaPlayer, whose error callback takes two
            // int arguments (int what, int extra). Since PlaybackException defines a single error
            // code, we pass 0 as the extra.
            context.getString(
                R.string.lb_media_player_error, /* formatArgs...= */ error.errorCode, 0));
      }
    }

    // dereference of possibly-null reference callback
    @SuppressWarnings("nullness:dereference.of.nullable")
    @Override
    public void onTimelineChanged(Timeline timeline, @TimelineChangeReason int reason) {
      Callback callback = getCallback();
      callback.onDurationChanged(JoLeanbackPlayerAdapter.this);
      callback.onCurrentPositionChanged(JoLeanbackPlayerAdapter.this);
      callback.onBufferedPositionChanged(JoLeanbackPlayerAdapter.this);
    }

    // dereference of possibly-null reference callback
    @SuppressWarnings("nullness:dereference.of.nullable")
    @Override
    public void onPositionDiscontinuity(
        Player.PositionInfo oldPosition,
        Player.PositionInfo newPosition,
        @DiscontinuityReason int reason) {
      Callback callback = getCallback();
      callback.onCurrentPositionChanged(JoLeanbackPlayerAdapter.this);
      callback.onBufferedPositionChanged(JoLeanbackPlayerAdapter.this);
    }

    // dereference of possibly-null reference getCallback()
    @SuppressWarnings("nullness:dereference.of.nullable")
    @Override
    public void onVideoSizeChanged(VideoSize videoSize) {
      if (videoSize.width == 0 || videoSize.height == 0) {
        // Do not report unknown or placeholder sizes as Leanback can't handle these cases correctly
        // (see https://github.com/androidx/media/issues/617).
        return;
      }
      // There's no way to pass pixelWidthHeightRatio to leanback, so we scale the width that we
      // pass to take it into account. This is necessary to ensure that leanback uses the correct
      // aspect ratio when playing content with non-square pixels.
      int scaledWidth = Math.round(videoSize.width * videoSize.pixelWidthHeightRatio);
      getCallback().onVideoSizeChanged(JoLeanbackPlayerAdapter.this, scaledWidth, videoSize.height);
    }

    @Override
    public void onEvents(Player player, Player.Events events) {
      if (events.containsAny(
          Player.EVENT_PLAY_WHEN_READY_CHANGED, Player.EVENT_PLAYBACK_STATE_CHANGED)) {
        notifyStateChanged();
      }
    }
  }
}
