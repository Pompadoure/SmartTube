package com.liskovsoft.smartyoutubetv2.tv.ui.playback;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.VideoSize;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector;
import androidx.media3.ui.AspectRatioFrameLayout;

import com.liskovsoft.mediaserviceinterfaces.data.MediaItemFormatInfo;
import com.liskovsoft.sharedutils.mylogger.Log;
import com.liskovsoft.smartyoutubetv2.common.exoplayer.ExoMediaSourceFactory;
import com.liskovsoft.smartyoutubetv2.common.exoplayer.controller.ExoPlayerController;
import com.liskovsoft.smartyoutubetv2.common.exoplayer.other.Ablation;
import com.liskovsoft.smartyoutubetv2.common.exoplayer.controller.ShortsQueue;
import com.liskovsoft.smartyoutubetv2.common.exoplayer.other.VolumeBooster;
import com.liskovsoft.smartyoutubetv2.common.prefs.PlayerData;
import com.liskovsoft.smartyoutubetv2.common.prefs.PlayerTweaksData;

/**
 * JoTube media3: the second player of the Shorts feed. While a Short plays, this player prepares the NEXT one (video
 * only, paused on its first frame) on a surface of its own. When the user swipes, that real first frame slides in
 * together with the swipe (instead of a blurred thumbnail) and stays until the main player shows its own first
 * frame of the same Short (same picture: the handover is invisible).<br/>
 * Costs a second decoder and the first segment of the next Short loaded twice, so it is a setting (off by default)
 * and it only starts once the playing Short has shown its first frame.
 */
public class ShortsPreview {
    private static final String TAG = "ShortsPreview";
    // after the playing Short's first frame (JoTube live switch: soon, so a swipe after ~1 s finds it ready)
    private static final int START_DELAY_MS = 500; // silent spare (2026-10-28): cheap enough to start soon; 1200 ms left fast swipers without a ready Short (log 12:40: 9 of 14 swipes)
    private static final float PARKED_Y = 10_000f; // visible (the surface must exist) but far off screen

    // after a take-over the main player's sound is young: no decoder start-up and network work on this weak box then
    private static final int QUIET_AFTER_TAKEOVER_MS = 0;
    private long mQuietUntilMs;

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final Runnable mRefresh = () -> {
        mRefreshPending = false;
        refresh();
    };
    private boolean mRefreshPending;
    private Runnable mFirstFrameListener; // JoTube: the live switch waits for this Short's first frame
    private Context mContext;
    private FrameLayout mHolder; // the frame: set by the overlay (same size as the incoming picture)
    private AspectRatioFrameLayout mAspect;
    private SurfaceView mSurface;
    private ExoPlayer mPlayer;
    private com.liskovsoft.smartyoutubetv2.common.exoplayer.versions.selector.RestoreTrackSelector mSelector;
    private String mVideoId;
    private boolean mFrameRendered;
    private boolean mMainStarted; // the playing Short has shown its first frame
    private boolean mEnabled;
    private boolean mLive; // JoTube: it plays the Short on screen (live switch) until the main player takes over
    private float mAspectRatio; // the prepared Short's picture (width / height), 0 = not known yet
    private Runnable mLiveProblem; // JoTube: the live Short failed or ended (the main player must take over)

    private final Player.Listener mListener = new Player.Listener() {
        @Override
        public void onRenderedFirstFrame() {
            mFrameRendered = true;
            Log.d(TAG, "First frame of the next Short %s is ready", mVideoId);
            Runnable listener = mFirstFrameListener;
            mFirstFrameListener = null;
            if (listener != null) {
                listener.run();
            }
        }

        @Override
        public void onVideoSizeChanged(VideoSize size) {
            if (size.height > 0) {
                mAspectRatio = (float) size.width * size.pixelWidthHeightRatio / size.height;
                if (mAspect != null) {
                    mAspect.setAspectRatio(mAspectRatio);
                }
            }
        }

        @Override
        public void onPlaybackStateChanged(int state) {
            if (mLive && state == Player.STATE_ENDED && mLiveProblem != null) {
                mLiveProblem.run();
            }
        }

        @Override
        public void onPlayerError(PlaybackException error) {
            Log.e(TAG, "Preview failed for %s: %s", mVideoId, error.getMessage());
            mFrameRendered = false;
            Runnable listener = mFirstFrameListener;
            mFirstFrameListener = null;
            if (listener != null) {
                listener.run(); // the waiting switch sees "not ready" and takes the usual way at once
            }
            if (mLive && mLiveProblem != null) {
                mLiveProblem.run();
            }
            // A failed one is not retried until the next Short comes
        }
    };

    /**
     * @param container the player's root: the preview sits at the given child index (above the overlay's covers). Not
     *                  inside the overlay: that one is hidden between the swipes, which would destroy the surface (and
     *                  the rendered first frame with it).
     */
    public void attach(ViewGroup container, int index, boolean enabled) {
        if (container == null || mHolder != null) {
            return;
        }

        mContext = container.getContext();
        mEnabled = enabled;

        mHolder = new FrameLayout(mContext);
        mHolder.setClipChildren(true);
        mAspect = new AspectRatioFrameLayout(mContext);
        mAspect.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FIT);
        mSurface = new SurfaceView(mContext);
        mSurface.setZOrderMediaOverlay(true); // above the main player's surface
        mAspect.addView(mSurface, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        mHolder.addView(mAspect, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER));
        mHolder.setTranslationY(PARKED_Y);
        mHolder.setFocusable(false);
        mHolder.setClickable(false);
        container.addView(mHolder, Math.min(index, container.getChildCount()), new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER));

        if (mEnabled) {
            ShortsQueue.setChangeListener(this::scheduleRefresh);
        }
    }

    public void detach() {
        mHandler.removeCallbacksAndMessages(null);
        mRefreshPending = false;
        mFirstFrameListener = null;
        ShortsQueue.setChangeListener(null);
        releasePlayer();

        if (mHolder != null && mHolder.getParent() instanceof ViewGroup) {
            ((ViewGroup) mHolder.getParent()).removeView(mHolder);
        }

        mHolder = null;
        mAspect = null;
        mSurface = null;
        mVideoId = null;
    }

    public boolean isEnabled() {
        return mEnabled;
    }

    /**
     * Same size as the incoming picture (the Shorts frame): 0 = whole container.
     */
    public void setFrame(int width, int height, int offsetX) {
        setFrame(width, height, offsetX, false);
    }

    /**
     * @param force also while live (the live switch sets the frame of the incoming Short itself; the frame updates of
     *              the playing one must not change it meanwhile)
     */
    public void setFrame(int width, int height, int offsetX, boolean force) {
        if (mHolder == null || !(mHolder.getLayoutParams() instanceof FrameLayout.LayoutParams) || (mLive && !force)) {
            return;
        }

        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) mHolder.getLayoutParams();
        int w = width > 0 ? width : ViewGroup.LayoutParams.MATCH_PARENT;
        int h = height > 0 ? height : ViewGroup.LayoutParams.MATCH_PARENT;

        if (params.width != w || params.height != h) {
            params.width = w;
            params.height = h;
            params.gravity = Gravity.CENTER;
            mHolder.setLayoutParams(params);
        }

        mHolder.setTranslationX(offsetX);
    }

    /**
     * The playing Short has shown its first frame: now the next one may be prepared (it would share the network and
     * the CPU with the start of the playing one).
     */
    public void onMainFirstFrame() {
        mMainStarted = true;
        scheduleRefresh();
    }

    /**
     * A new Short starts: no preview work until its first frame.
     */
    public void onMainStarting() {
        mMainStarted = false;
        mHandler.removeCallbacks(mRefresh);
        mRefreshPending = false;
    }

    private void scheduleRefresh() {
        if (!mEnabled || !mMainStarted) {
            return;
        }

        if (mRefreshPending) {
            return; // queue updates must not push the pending preparation back (they came every ~1 s: log 2026-10-28)
        }

        long quiet = mQuietUntilMs - android.os.SystemClock.uptimeMillis();
        long delay = Ablation.has(mContext, "late") ? 8000 : START_DELAY_MS;
        mRefreshPending = true;
        mHandler.postDelayed(mRefresh, Math.max(delay, quiet));
    }

    /**
     * The spare player prepares the next Short without audio (the audio track is switched on when it goes live). Measured on
     * the Streamer (2026-10-28, 12-16 swipes per run): with audio the playing Short dropped up to 41 frames per swap, without
     * it at most 5 and the incoming one none. The word "audio" in ablate.txt brings the old behaviour back for comparison.
     */
    private boolean silentSpare() {
        return !Ablation.has(mContext, "audio");
    }

    private boolean isAblated() {
        return Ablation.has(mContext, "preview");
    }

    private void refresh() {
        if (!mEnabled || !mMainStarted || mHolder == null || mLive) {
            return; // live: it is the Short on screen right now
        }

        if (isAblated()) {
            return; // test switch: no preview work at all
        }

        MediaItemFormatInfo next = ShortsQueue.getNextFormatInfo();

        if (next == null || next.getVideoId() == null) {
            return;
        }

        if (next.getVideoId().equals(mVideoId)) {
            return; // prepared or preparing
        }

        prepare(next);
    }

    /**
     * JoTube: the user swiped to {@code videoId} before it was prepared: prepare it now (not after the start delay).
     *
     * @return true if it is prepared or being prepared here (its first frame will come: {@link #setFirstFrameListener})
     */
    public boolean prepareNow(String videoId) {
        if (!mEnabled || mHolder == null || mLive || videoId == null || mPlayer == null || isAblated()) {
            return false;
        }

        if (videoId.equals(mVideoId)) {
            return true;
        }

        MediaItemFormatInfo info = ShortsQueue.getQueuedFormatInfo(videoId);

        if (info == null) {
            MediaItemFormatInfo next = ShortsQueue.getNextFormatInfo();
            info = next != null && videoId.equals(next.getVideoId()) ? next : null;
        }

        if (info == null) {
            return false;
        }

        mHandler.removeCallbacks(mRefresh);
        mRefreshPending = false;
        return prepare(info) && videoId.equals(mVideoId);
    }

    /** This Short is being prepared here and has no first frame yet. */
    public boolean isPreparing(String videoId) {
        return mPlayer != null && !mLive && videoId != null && videoId.equals(mVideoId) && !mFrameRendered;
    }

    /** Called once on the next first frame (or failure) of the prepared Short; null removes it. */
    public void setFirstFrameListener(@Nullable Runnable listener) {
        mFirstFrameListener = listener;
    }

    private boolean prepare(MediaItemFormatInfo next) {
        if (mSelector != null) {
            if (Ablation.has(mContext, "cap480")) {
                mSelector.setParameters(mSelector.buildUponParameters().setMaxVideoSize(Integer.MAX_VALUE, 480));
            }
            if (silentSpare()) {
                mSelector.setParameters(mSelector.buildUponParameters().setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true));
            }
        }

        MediaSource source = ExoPlayerController.createPreviewSource(next);

        if (source == null) {
            return false;
        }

        ExoPlayer player = ensurePlayer();

        if (player == null) {
            return false;
        }

        mVideoId = next.getVideoId();
        mFrameRendered = false;
        mAspectRatio = 0;
        mHolder.setTranslationY(PARKED_Y);
        mHolder.setAlpha(1f);
        Log.d(TAG, "Preparing the preview of %s", mVideoId);
        player.setPlayWhenReady(false);
        player.setMediaSource(source);
        player.prepare();
        return true;
    }

    @Nullable
    private ExoPlayer ensurePlayer() {
        return mSurface != null ? mPlayer : null;
    }

    /**
     * JoTube role swap: the spare player (built and released by the player screen like the main one). It prepares the
     * next Short here; on a swipe it plays it live and is handed to the core ({@link #handOverLive}); the core's old
     * player comes back here as the new spare.
     */
    public void setPlayer(@Nullable ExoPlayer player, @Nullable com.liskovsoft.smartyoutubetv2.common.exoplayer.versions.selector.RestoreTrackSelector selector) {
        if (mPlayer == player) {
            return;
        }

        if (mPlayer != null) {
            try {
                mPlayer.removeListener(mListener);
                if (mSurface != null) {
                    mPlayer.clearVideoSurfaceView(mSurface);
                }
            } catch (RuntimeException e) {
                // released already
            }
        }

        mPlayer = player;
        mSelector = selector;
        mLive = false;
        mVideoId = null;
        mFrameRendered = false;
        mAspectRatio = 0;

        if (player != null) {
            player.setVolume(0f);
            player.setPlayWhenReady(false);
            player.addListener(mListener);
            if (mSurface != null) {
                player.setVideoSurfaceView(mSurface);
            }
        }
    }

    /**
     * JoTube role swap: the live Short's player goes to the core (it stays on screen here until the core's surface shows
     * it; {@link #park} then). This preview has no player until {@link #setPlayer} gives it the old one.
     */
    @Nullable
    public ExoPlayer handOverLive() {
        ExoPlayer player = mPlayer;

        if (player == null || !mLive) {
            return null;
        }

        player.removeListener(mListener);
        mPlayer = null;
        mSelector = null;
        mLive = false;
        mVideoId = null;
        mFrameRendered = false;
        mAspectRatio = 0;
        mHandler.removeCallbacks(mRefresh);
        return player;
    }

    /** JoTube role swap: the live Short is on the core's surface now: the preview frame goes away. */
    public void onHandedOver() {
        park();
        mQuietUntilMs = android.os.SystemClock.uptimeMillis() + QUIET_AFTER_TAKEOVER_MS;
    }

    /**
     * @return true if the real first frame of the Short is rendered and can slide in
     */
    public boolean isReadyFor(String videoId) {
        return mEnabled && mHolder != null && mPlayer != null && videoId != null && videoId.equals(mVideoId) && mFrameRendered;
    }

    /** JoTube: the prepared Short's picture shape (width / height), 0 if not known. */
    public float getAspectRatio() {
        return mAspectRatio;
    }

    @Nullable
    public ExoPlayer getPlayer() {
        return mPlayer;
    }

    /** The view that moves (the frame with the surface). */
    @Nullable
    public View getView() {
        return mHolder;
    }

    /**
     * JoTube live switch: the prepared Short starts playing on screen (silent: the switch fades the sound in).
     */
    public boolean startLive() {
        if (mPlayer == null || !mFrameRendered) {
            return false;
        }
        if (mSelector != null && silentSpare()) {
            mSelector.setParameters(mSelector.buildUponParameters().setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false));
        }
        mLive = true;
        mHandler.removeCallbacks(mRefresh);
        mPlayer.setPlayWhenReady(true);
        return true;
    }

    public boolean isLive() {
        return mLive;
    }

    public void setLiveProblemListener(Runnable listener) {
        mLiveProblem = listener;
    }

    /**
     * Move the preview with the incoming picture (0 = in place). Parked far off screen when not used.
     */
    public void setOffsetY(float offsetY) {
        if (mHolder != null) {
            mHolder.setTranslationY(offsetY);
        }
    }

    public void setAlpha(float alpha) {
        if (mHolder != null) {
            mHolder.setAlpha(alpha);
        }
    }

    /**
     * The main player shows its first frame: uncover it (the preview shows the same picture) and free the preview.
     */
    public void fadeOutAndConsume(long durationMs) {
        if (mHolder == null) {
            return;
        }

        mHolder.animate().cancel();
        mHolder.animate().alpha(0f).setDuration(durationMs).withEndAction(this::consumed).start();
    }

    public boolean isShowing() {
        return mHolder != null && mHolder.getTranslationY() < PARKED_Y / 2;
    }

    public void park() {
        if (mHolder != null) {
            mHolder.animate().cancel();
            mHolder.setAlpha(1f);
            mHolder.setTranslationY(PARKED_Y);
        }
    }

    /**
     * The Short was taken by the main player: this one is free for the next.
     */
    public void consumed() {
        boolean wasLive = mLive;
        mLive = false;
        mVideoId = null;
        mFrameRendered = false;
        mAspectRatio = 0;

        if (mPlayer != null) {
            mPlayer.stop();
            mPlayer.clearMediaItems();
        }

        park();

        if (mPlayer != null) {
            mPlayer.setVolume(0f);
        }
        if (wasLive) {
            mQuietUntilMs = android.os.SystemClock.uptimeMillis() + QUIET_AFTER_TAKEOVER_MS;
            scheduleRefresh(); // prepare the next one
        }
    }

    /** JoTube role swap: the player isn't this one's (the player screen releases it): only let it go. */
    private void releasePlayer() {
        setPlayer(null, null);
    }
}
