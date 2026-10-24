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
import com.liskovsoft.smartyoutubetv2.common.exoplayer.controller.ShortsQueue;

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
    private static final int START_DELAY_MS = 300;
    private static final float PARKED_Y = 10_000f; // visible (the surface must exist) but far off screen

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final Runnable mRefresh = this::refresh;
    private Context mContext;
    private FrameLayout mHolder; // the frame: set by the overlay (same size as the incoming picture)
    private AspectRatioFrameLayout mAspect;
    private SurfaceView mSurface;
    private ExoPlayer mPlayer;
    private String mVideoId;
    private boolean mFrameRendered;
    private boolean mMainStarted; // the playing Short has shown its first frame
    private boolean mEnabled;
    private boolean mLive; // JoTube: it plays the Short on screen (live switch) until the main player takes over
    private float mAspectRatio; // the prepared Short's picture (width / height), 0 = not known yet

    private final Player.Listener mListener = new Player.Listener() {
        @Override
        public void onRenderedFirstFrame() {
            mFrameRendered = true;
            Log.d(TAG, "First frame of the next Short %s is ready", mVideoId);
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
        public void onPlayerError(PlaybackException error) {
            Log.e(TAG, "Preview failed for %s: %s", mVideoId, error.getMessage());
            mFrameRendered = false;
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
        if (mHolder == null || !(mHolder.getLayoutParams() instanceof FrameLayout.LayoutParams)) {
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
    }

    private void scheduleRefresh() {
        if (!mEnabled || !mMainStarted) {
            return;
        }

        mHandler.removeCallbacks(mRefresh);
        mHandler.postDelayed(mRefresh, START_DELAY_MS);
    }

    private void refresh() {
        if (!mEnabled || !mMainStarted || mHolder == null || mLive) {
            return; // live: it is the Short on screen right now
        }

        MediaItemFormatInfo next = ShortsQueue.getNextFormatInfo();

        if (next == null || next.getVideoId() == null) {
            return;
        }

        if (next.getVideoId().equals(mVideoId)) {
            return; // prepared or preparing
        }

        MediaSource source = ExoPlayerController.createPreviewSource(next);

        if (source == null) {
            return;
        }

        ExoPlayer player = ensurePlayer();

        if (player == null) {
            return;
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
    }

    @Nullable
    private ExoPlayer ensurePlayer() {
        if (mPlayer != null) {
            return mPlayer;
        }

        if (mContext == null || mSurface == null) {
            return null;
        }

        try {
            DefaultTrackSelector trackSelector = new DefaultTrackSelector(mContext);
            // JoTube live switch: with its sound (silent until it is shown) and the main player's picture size (the
            // main player takes over a few seconds later: no change of sharpness at that moment)
            trackSelector.setParameters(trackSelector.buildUponParameters()
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                    .setMaxVideoSize(1920, 1920)
                    .setPreferredAudioLanguage(java.util.Locale.getDefault().getLanguage())
                    .setAllowVideoMixedMimeTypeAdaptiveness(false));
            DefaultLoadControl loadControl = new DefaultLoadControl.Builder()
                    .setBufferDurationsMs(2_500, 8_000, 250, 250)
                    .build();
            mPlayer = new ExoPlayer.Builder(mContext, new DefaultRenderersFactory(mContext).setEnableDecoderFallback(true))
                    .setTrackSelector(trackSelector)
                    .setLoadControl(loadControl)
                    .setBandwidthMeter(ExoMediaSourceFactory.getBandwidthMeter(mContext))
                    .build();
            mPlayer.setVolume(0f);
            mPlayer.setVideoSurfaceView(mSurface);
            mPlayer.addListener(mListener);
        } catch (RuntimeException e) {
            Log.e(TAG, "Can't create the preview player: %s", e.getMessage());
            mPlayer = null;
        }

        return mPlayer;
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
        mLive = true;
        mHandler.removeCallbacks(mRefresh);
        mPlayer.setPlayWhenReady(true);
        return true;
    }

    public boolean isLive() {
        return mLive;
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
            scheduleRefresh(); // prepare the next one
        }
    }

    private void releasePlayer() {
        if (mPlayer != null) {
            try {
                mPlayer.removeListener(mListener);
                mPlayer.release();
            } catch (RuntimeException e) {
                // ignore
            }
            mPlayer = null;
        }
    }
}
