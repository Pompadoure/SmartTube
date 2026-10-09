package com.liskovsoft.smartyoutubetv2.tv.ui.playback;

import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;

import android.view.animation.Interpolator;

import com.bumptech.glide.Glide;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.ShortsTransitionState;

/**
 * Shorts switch, like the official app: inside the (fixed) Shorts frame the current Short slides out and the
 * next one slides in from the direction of navigation. Thumbnails are moved, never the video surface. The next
 * Short's thumbnail stays until the first frame of the new video is rendered, then it fades out quickly, so the
 * old picture never shows up again and the new one never "pops" in behind.
 */
public class ShortsTransitionOverlay {
    private static final int SWIPE_DURATION_MS = 250;
    private static final int FADE_OUT_DURATION_MS = 100;
    private static final int FAILSAFE_HIDE_MS = 10_000;
    private static final Interpolator SWIPE_INTERPOLATOR = new FastOutSlowIn();
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final Runnable mFailsafeHide = () -> hide(false);
    private final Runnable mDeferredHide = () -> hide(true);
    private Video mPendingVideo;
    private final Runnable mPendingShow = () -> {
        if (mPendingVideo != null) {
            show(mPendingVideo, ShortsTransitionState.DIRECTION_NONE);
        }
    };
    private FrameLayout mContainer; // clips the slide to the frame
    private ImageView mIn; // the upcoming video
    private ImageView mOut; // the current video, slides out
    private boolean mIsShown;
    private boolean mFirstFrameRendered;
    private long mShowAnimationEndMs;

    /**
     * Place the overlay right above the video surface and below the player controls.
     */
    public void attach(ViewGroup root) {
        if (root == null || mContainer != null) {
            return;
        }

        mContainer = new FrameLayout(root.getContext());
        mContainer.setClipChildren(true);
        mContainer.setVisibility(View.GONE);
        mContainer.setFocusable(false);
        mContainer.setClickable(false);

        mOut = createImage(mContainer);
        mIn = createImage(mContainer);

        View surfaceRoot = root.findViewById(com.liskovsoft.smartyoutubetv2.tv.R.id.surface_root);
        int index = surfaceRoot != null ? root.indexOfChild(surfaceRoot) + 1 : Math.min(1, root.getChildCount());
        root.addView(mContainer, index, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER));
    }

    private static ImageView createImage(FrameLayout container) {
        ImageView image = new ImageView(container.getContext());
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        image.setBackgroundColor(Color.BLACK);
        image.setVisibility(View.GONE);
        container.addView(image, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        return image;
    }

    /**
     * JoTube: in the Shorts frame the overlay covers only the frame (0 = the whole screen).
     */
    public void setFrame(int width, int height) {
        if (mContainer == null || !(mContainer.getLayoutParams() instanceof FrameLayout.LayoutParams)) {
            return;
        }

        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) mContainer.getLayoutParams();
        int newWidth = width > 0 ? width : ViewGroup.LayoutParams.MATCH_PARENT;
        int newHeight = height > 0 ? height : ViewGroup.LayoutParams.MATCH_PARENT;

        if (params.width != newWidth || params.height != newHeight || params.gravity != Gravity.CENTER) {
            params.width = newWidth;
            params.height = newHeight;
            params.gravity = Gravity.CENTER;
            mContainer.setLayoutParams(params);
        }
    }

    public void detach() {
        mHandler.removeCallbacksAndMessages(null);
        mPendingVideo = null;

        if (mContainer != null) {
            cancelAnimations();
            if (mContainer.getParent() instanceof ViewGroup) {
                ((ViewGroup) mContainer.getParent()).removeView(mContainer);
            }
            mContainer = null;
            mIn = null;
            mOut = null;
        }

        mIsShown = false;
    }

    /**
     * Preloaded video, no swipe: normally its first frame comes within a few frames, so nothing is shown.
     * If it takes longer (e.g. the data has to be loaded again), the thumbnail covers the old picture.
     */
    public void showIfSlow(Video video, long delayMs) {
        cancelPendingShow();

        if (mContainer == null || video == null) {
            return;
        }

        mFirstFrameRendered = false;
        mPendingVideo = video;
        mHandler.postDelayed(mPendingShow, delayMs);
    }

    private void cancelPendingShow() {
        mHandler.removeCallbacks(mPendingShow);
        mPendingVideo = null;
    }

    /**
     * A new video is about to load: its thumbnail covers the old picture (no animation).
     */
    public void show(Video video, int direction) {
        if (mContainer == null || video == null) {
            return;
        }

        mFirstFrameRendered = false;
        prepare();
        loadThumbnail(mIn, video);
        mIn.setVisibility(View.VISIBLE);
        mShowAnimationEndMs = SystemClock.uptimeMillis();
        mHandler.postDelayed(mFailsafeHide, FAILSAFE_HIDE_MS);
    }

    /**
     * The swipe from one Short to the next (direction: next = the new one comes from the bottom).
     */
    public void swipe(Video from, Video to, int direction) {
        if (mContainer == null || to == null) {
            return;
        }

        int height = mContainer.getHeight() > 0 ? mContainer.getHeight() :
                mContainer.getParent() instanceof View ? ((View) mContainer.getParent()).getHeight() : 0;

        if (direction == ShortsTransitionState.DIRECTION_NONE || height <= 0) {
            show(to, ShortsTransitionState.DIRECTION_NONE);
            return;
        }

        mFirstFrameRendered = false;
        prepare();

        if (from != null) {
            loadThumbnail(mOut, from);
            mOut.setVisibility(View.VISIBLE);
            mOut.setTranslationY(0);
            mOut.animate()
                    .translationY(-direction * height)
                    .setDuration(SWIPE_DURATION_MS)
                    .setInterpolator(SWIPE_INTERPOLATOR)
                    .withEndAction(() -> {
                        if (mOut != null) {
                            mOut.setVisibility(View.GONE);
                            mOut.setImageDrawable(null);
                        }
                    })
                    .start();
        }

        loadThumbnail(mIn, to);
        mIn.setVisibility(View.VISIBLE);
        mIn.setTranslationY(direction * height);
        mIn.animate()
                .translationY(0)
                .setDuration(SWIPE_DURATION_MS)
                .setInterpolator(SWIPE_INTERPOLATOR)
                .start();

        mShowAnimationEndMs = SystemClock.uptimeMillis() + SWIPE_DURATION_MS;
        mHandler.postDelayed(mFailsafeHide, FAILSAFE_HIDE_MS);
    }

    private void prepare() {
        mHandler.removeCallbacks(mFailsafeHide);
        mHandler.removeCallbacks(mDeferredHide);
        cancelPendingShow();
        cancelAnimations();

        mContainer.setVisibility(View.VISIBLE);
        mContainer.setAlpha(1f);
        mIn.setTranslationY(0);
        mOut.setTranslationY(0);
        mOut.setVisibility(View.GONE);
        mIsShown = true;
    }

    private void loadThumbnail(ImageView image, Video video) {
        try {
            // Shorts: the vertical thumbnail. Regular videos: the HD one (the card image has black bars)
            String url = video.isShorts ? ShortsTransitionState.getThumbnailUrl(video.videoId) :
                    "https://i.ytimg.com/vi/" + video.videoId + "/maxresdefault.jpg";
            Glide.with(image)
                    .load(url)
                    .error(Glide.with(image).load(video.getCardImageUrl()))
                    .dontAnimate()
                    .into(image);
        } catch (IllegalArgumentException e) {
            // Activity is destroyed
        }
    }

    /**
     * The first frame of the new video is on screen (animate = true), or the overlay isn't needed (false).
     */
    public void hide(boolean animate) {
        if (animate) {
            mFirstFrameRendered = true;
        }

        mHandler.removeCallbacks(mFailsafeHide);
        mHandler.removeCallbacks(mDeferredHide);
        cancelPendingShow();

        if (mContainer == null || !mIsShown) {
            return;
        }

        long remainingMs = mShowAnimationEndMs - SystemClock.uptimeMillis();

        if (animate && remainingMs > 0) {
            // The video is ready before the swipe is over (preloaded Short): finish the swipe first
            mHandler.postDelayed(mDeferredHide, remainingMs);
            return;
        }

        mIsShown = false;
        cancelAnimations();

        if (!animate) {
            reset();
            return;
        }

        mContainer.animate()
                .alpha(0f)
                .setDuration(FADE_OUT_DURATION_MS)
                .withEndAction(this::reset)
                .start();
    }

    public boolean isShown() {
        return mIsShown;
    }

    private void cancelAnimations() {
        if (mContainer != null) {
            mContainer.animate().cancel();
        }
        if (mIn != null) {
            mIn.animate().cancel();
        }
        if (mOut != null) {
            mOut.animate().cancel();
        }
    }

    /**
     * Material "fast out, slow in": cubic bezier (0.4, 0, 0.2, 1), the curve of the official app's swipe.
     */
    private static final class FastOutSlowIn implements Interpolator {
        @Override
        public float getInterpolation(float t) {
            if (t <= 0f) {
                return 0f;
            }
            if (t >= 1f) {
                return 1f;
            }

            // Find the curve parameter u for x(u) = t (Newton), then return y(u)
            float u = t;
            for (int i = 0; i < 8; i++) {
                float x = bezier(u, 0.4f, 0.2f) - t;
                float dx = bezierDerivative(u, 0.4f, 0.2f);
                if (Math.abs(x) < 1e-4f || dx == 0f) {
                    break;
                }
                u = Math.max(0f, Math.min(1f, u - x / dx));
            }

            return bezier(u, 0f, 1f);
        }

        private static float bezier(float u, float p1, float p2) {
            float v = 1f - u;
            return 3f * v * v * u * p1 + 3f * v * u * u * p2 + u * u * u;
        }

        private static float bezierDerivative(float u, float p1, float p2) {
            float v = 1f - u;
            return 3f * v * v * p1 + 6f * v * u * (p2 - p1) + 3f * u * u * (1f - p2);
        }
    }

    private void reset() {
        if (mContainer == null || mIsShown) {
            return;
        }

        mContainer.setVisibility(View.GONE);
        mContainer.setAlpha(1f);
        mIn.setTranslationY(0);
        mOut.setTranslationY(0);
        mIn.setVisibility(View.GONE);
        mOut.setVisibility(View.GONE);
        mIn.setImageDrawable(null);
        mOut.setImageDrawable(null);
    }
}
