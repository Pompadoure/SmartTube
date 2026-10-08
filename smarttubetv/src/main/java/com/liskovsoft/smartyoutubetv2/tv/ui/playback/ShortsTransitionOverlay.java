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
 * Replaces the black gap between Shorts with the upcoming video's thumbnail,
 * sliding in from the direction of navigation (like the official app's feed),
 * and fades it out as soon as the first video frame is rendered.
 */
public class ShortsTransitionOverlay {
    private static final int SLIDE_DURATION_MS = 260;
    private static final int FADE_IN_DURATION_MS = 150;
    private static final int FADE_OUT_DURATION_MS = 180;
    private static final int FAILSAFE_HIDE_MS = 10_000;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final Runnable mFailsafeHide = () -> hide(false);
    private final Runnable mDeferredHide = () -> hide(true);
    private ImageView mOverlay;
    private boolean mIsShown;
    private long mShowAnimationEndMs;

    /**
     * Place the overlay right above the video surface (index 0) and below the player controls.
     */
    public void attach(ViewGroup root) {
        if (root == null || mOverlay != null) {
            return;
        }

        mOverlay = new ImageView(root.getContext());
        mOverlay.setScaleType(ImageView.ScaleType.FIT_CENTER);
        mOverlay.setBackgroundColor(Color.BLACK);
        mOverlay.setVisibility(View.GONE);
        mOverlay.setFocusable(false);
        mOverlay.setClickable(false);

        int index = Math.min(1, root.getChildCount());
        root.addView(mOverlay, index, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    public void detach() {
        mHandler.removeCallbacks(mFailsafeHide);
        mHandler.removeCallbacks(mDeferredHide);

        if (mOverlay != null) {
            mOverlay.animate().cancel();
            if (mOverlay.getParent() instanceof ViewGroup) {
                ((ViewGroup) mOverlay.getParent()).removeView(mOverlay);
            }
            mOverlay = null;
        }

        mIsShown = false;
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
        mOverlay.animate().cancel();

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
                    .start();
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

    private void reset() {
        if (mOverlay == null || mIsShown) {
            return;
        }

        mOverlay.setVisibility(View.GONE);
        mOverlay.setAlpha(1f);
        mOverlay.setTranslationY(0);
        mOverlay.setImageDrawable(null);
    }
}
