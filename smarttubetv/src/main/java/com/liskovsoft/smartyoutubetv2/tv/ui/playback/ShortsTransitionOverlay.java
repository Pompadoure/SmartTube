package com.liskovsoft.smartyoutubetv2.tv.ui.playback;

import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.ShortsTransitionState;

/**
 * Like the official app's feed: the current video slides out and the upcoming video (its thumbnail,
 * replaced by the video itself as soon as its first frame is rendered) slides in from the direction
 * of navigation. The video surface is moved together with the thumbnail, so the old picture doesn't
 * stay in place under it.
 */
public class ShortsTransitionOverlay {
    private static final int SLIDE_DURATION_MS = 300;
    private static final int FADE_IN_DURATION_MS = 0; // static: no animation
    private static final int FADE_OUT_DURATION_MS = 120;
    private static final int FAILSAFE_HIDE_MS = 10_000;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final Runnable mFailsafeHide = () -> hide(false);
    private final Runnable mDeferredHide = () -> hide(true);
    private Video mPendingVideo;
    private final Runnable mPendingShow = () -> {
        if (mPendingVideo != null) {
            show(mPendingVideo, ShortsTransitionState.DIRECTION_NONE);
        }
    };
    private ImageView mOverlay;
    private View mSurface; // the video surface (moves together with the overlay)
    private boolean mIsShown;
    private long mShowAnimationEndMs;

    /**
     * Place the overlay right above the video surface (index 0) and below the player controls.
     */
    public void attach(ViewGroup root) {
        if (root == null || mOverlay != null) {
            return;
        }

        mSurface = root.findViewById(com.liskovsoft.smartyoutubetv2.tv.R.id.surface_root); // the video surface container

        mOverlay = new ImageView(root.getContext());
        mOverlay.setScaleType(ImageView.ScaleType.FIT_CENTER);
        mOverlay.setBackgroundColor(Color.BLACK);
        mOverlay.setVisibility(View.GONE);
        mOverlay.setFocusable(false);
        mOverlay.setClickable(false);

        View surfaceRoot = root.findViewById(com.liskovsoft.smartyoutubetv2.tv.R.id.surface_root);
        int index = surfaceRoot != null ? root.indexOfChild(surfaceRoot) + 1 : Math.min(1, root.getChildCount());
        root.addView(mOverlay, index, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    public void detach() {
        mHandler.removeCallbacks(mFailsafeHide);
        mHandler.removeCallbacks(mDeferredHide);
        cancelPendingShow();

        if (mOverlay != null) {
            mOverlay.animate().cancel();
            if (mOverlay.getParent() instanceof ViewGroup) {
                ((ViewGroup) mOverlay.getParent()).removeView(mOverlay);
            }
            mOverlay = null;
        }

        resetSurface();
        mSurface = null;
        mIsShown = false;
    }

    /**
     * Preloaded video: normally its first frame comes within a few frames, so nothing is shown.
     * If it takes longer (e.g. the data has to be loaded again), the thumbnail covers the old picture.
     */
    public void showIfSlow(Video video, long delayMs) {
        cancelPendingShow();

        if (mOverlay == null || video == null) {
            return;
        }

        mPendingVideo = video;
        mHandler.postDelayed(mPendingShow, delayMs);
    }

    private void cancelPendingShow() {
        mHandler.removeCallbacks(mPendingShow);
        mPendingVideo = null;
    }

    /**
     * Called when a new video is about to load (the previous one is already stopped).
     */
    public void show(Video video, int direction) {
        if (mOverlay == null || video == null) {
            return;
        }

        mHandler.removeCallbacks(mFailsafeHide);
        mHandler.removeCallbacks(mDeferredHide);
        cancelPendingShow();
        mOverlay.animate().cancel();
        resetSurface();

        String fallbackUrl = video.getCardImageUrl();
        try {
            Glide.with(mOverlay)
                    .load(ShortsTransitionState.getThumbnailUrl(video.videoId))
                    .error(Glide.with(mOverlay).load(fallbackUrl))
                    .transition(DrawableTransitionOptions.withCrossFade(120))
                    .into(mOverlay);
        } catch (IllegalArgumentException e) {
            // Activity is destroyed
            return;
        }

        mOverlay.setVisibility(View.VISIBLE);
        mIsShown = true;

        // The overlay itself may be GONE (not measured), so use the parent size
        View parent = (View) mOverlay.getParent();
        int height = parent != null ? parent.getHeight() : 0;

        boolean slide = direction != ShortsTransitionState.DIRECTION_NONE && height > 0;
        mShowAnimationEndMs = SystemClock.uptimeMillis() + (slide ? SLIDE_DURATION_MS : FADE_IN_DURATION_MS);

        if (slide) {
            // Next: comes from the bottom. Previous: comes from the top.
            mOverlay.setAlpha(1f);
            mOverlay.setTranslationY(direction * height);
            mOverlay.animate()
                    .translationY(0)
                    .setDuration(SLIDE_DURATION_MS)
                    .setInterpolator(new DecelerateInterpolator(1.6f))
                    .withEndAction(this::resetSurface) // fully covered now: put the surface back for the new video
                    .start();

            // The current video goes out the same way (SurfaceView follows view transforms since Android 7)
            if (mSurface != null) {
                mSurface.animate().cancel();
                mSurface.setTranslationY(0);
                mSurface.animate()
                        .translationY(-direction * height)
                        .setDuration(SLIDE_DURATION_MS)
                        .setInterpolator(new DecelerateInterpolator(1.6f))
                        .start();
            }
        } else {
            mOverlay.setTranslationY(0);
            mOverlay.setAlpha(0f);
            mOverlay.animate()
                    .alpha(1f)
                    .setDuration(FADE_IN_DURATION_MS)
                    .start();
        }

        mHandler.postDelayed(mFailsafeHide, FAILSAFE_HIDE_MS);
    }

    /**
     * Called when the first frame of the new video is on screen.
     */
    public void hide(boolean animate) {
        mHandler.removeCallbacks(mFailsafeHide);
        mHandler.removeCallbacks(mDeferredHide);
        cancelPendingShow();

        if (mOverlay == null || !mIsShown) {
            return;
        }

        long remainingMs = mShowAnimationEndMs - SystemClock.uptimeMillis();

        if (animate && remainingMs > 0) {
            // The video is ready before the slide is over (preloaded Short): finish the slide first
            mHandler.postDelayed(mDeferredHide, remainingMs);
            return;
        }

        mIsShown = false;
        mOverlay.animate().cancel();
        resetSurface(); // the overlay covers the whole screen at this point

        if (!animate) {
            reset();
            return;
        }

        mOverlay.animate()
                .alpha(0f)
                .translationY(0)
                .setDuration(FADE_OUT_DURATION_MS)
                .setInterpolator(new AccelerateInterpolator())
                .withEndAction(this::reset)
                .start();
    }

    public boolean isShown() {
        return mIsShown;
    }

    private void resetSurface() {
        if (mSurface != null) {
            mSurface.animate().cancel();
            mSurface.setTranslationY(0);
        }
    }

    private void reset() {
        resetSurface();

        if (mOverlay == null || mIsShown) {
            return;
        }

        mOverlay.setVisibility(View.GONE);
        mOverlay.setAlpha(1f);
        mOverlay.setTranslationY(0);
        mOverlay.setImageDrawable(null);
    }
}
