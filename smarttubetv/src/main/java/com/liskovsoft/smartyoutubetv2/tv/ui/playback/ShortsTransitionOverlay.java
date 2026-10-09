package com.liskovsoft.smartyoutubetv2.tv.ui.playback;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.PixelCopy;
import android.view.SurfaceView;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.Interpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.request.target.Target;
import com.liskovsoft.sharedutils.mylogger.Log;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.ShortsTransitionState;

/**
 * Shorts switch, like the official app: the current Short (a snapshot of its last frame) stays until the next
 * one's first frame is rendered, then it slides out and the real, already playing video slides in from the
 * direction of navigation. No placeholder thumbnail (a black cover only if the new video is late).
 */
public class ShortsTransitionOverlay {
    // The slide starts when the new video is already playing (its first frame came during the hold)
    private static final int SWIPE_DURATION_MS = 220;
    private static final int REVEAL_DURATION_MS = 120; // the black cover over the moving video fades out
    private static final int FADE_OUT_DURATION_MS = 120;
    private static final int FAILSAFE_HIDE_MS = 10_000;
    private static final int SNAPSHOT_TIMEOUT_MS = 150; // the main thread can be busy ~70 ms at the switch
    // JoTube: a preloaded Short's first frame comes in 190-290 ms. Longer than that the old picture looks frozen:
    // slide on (the new video under a black cover that fades when its frame comes)
    private static final int HOLD_TIMEOUT_MS = 250;
    private static final String TAG = "ShortsTransition";
    private static final int GAP_DP = 16; // space between the outgoing and the incoming Short
    private static final Interpolator SWIPE_INTERPOLATOR = new FastOutSlowIn();
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final Runnable mFailsafeHide = () -> hide(false);
    private Video mPendingVideo;
    private final Runnable mPendingShow = () -> {
        if (mPendingVideo != null) {
            show(mPendingVideo, ShortsTransitionState.DIRECTION_NONE);
        }
    };
    private FrameLayout mContainer; // the whole screen, holds the two images
    private ImageView mIn; // the upcoming video (moves together with the surface)
    private ImageView mOut; // the current video, slides out
    private View mSurfaceRoot; // the real video
    private ValueAnimator mSwipe;
    private Runnable mSnapshotTimeout;
    private final Bitmap[] mSnapshotBitmaps = new Bitmap[2]; // JoTube: reused PixelCopy targets
    private int mSnapshotIndex;
    private Runnable mAfterSnapshot; // e.g. the player reset: only once the old picture is copied
    private Runnable mHoldStart; // the old picture is held until the new video's first frame
    private float mOutStart; // where the outgoing picture is (not 0 if pressed again during a slide)
    private int mSwipeId; // a newer swipe makes the callbacks of an older one void
    private boolean mIsShown;
    private boolean mFirstFrameRendered;

    /**
     * Place the overlay right above the video surface and below the player controls.
     */
    public void attach(ViewGroup root) {
        if (root == null || mContainer != null) {
            return;
        }

        mContainer = new FrameLayout(root.getContext());
        mContainer.setVisibility(View.GONE);
        mContainer.setFocusable(false);
        mContainer.setClickable(false);

        mOut = createImage(mContainer);
        mIn = createImage(mContainer);

        mSurfaceRoot = root.findViewById(com.liskovsoft.smartyoutubetv2.tv.R.id.surface_root);
        int index = mSurfaceRoot != null ? root.indexOfChild(mSurfaceRoot) + 1 : Math.min(1, root.getChildCount());
        root.addView(mContainer, index, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    private static ImageView createImage(FrameLayout container) {
        ImageView image = new ImageView(container.getContext());
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        image.setBackgroundColor(Color.BLACK);
        image.setVisibility(View.GONE);
        container.addView(image, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER));
        return image;
    }

    /**
     * JoTube: in the Shorts frame the images cover only the frame (0 = the whole screen).
     */
    public void setFrame(int width, int height) {
        setImageSize(mIn, width, height);
        setImageSize(mOut, width, height);
    }

    private static void setImageSize(ImageView image, int width, int height) {
        if (image == null || !(image.getLayoutParams() instanceof FrameLayout.LayoutParams)) {
            return;
        }

        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) image.getLayoutParams();
        int newWidth = width > 0 ? width : ViewGroup.LayoutParams.MATCH_PARENT;
        int newHeight = height > 0 ? height : ViewGroup.LayoutParams.MATCH_PARENT;

        if (params.width != newWidth || params.height != newHeight || params.gravity != Gravity.CENTER) {
            params.width = newWidth;
            params.height = newHeight;
            params.gravity = Gravity.CENTER;
            image.setLayoutParams(params);
        }
    }

    public void detach() {
        mHandler.removeCallbacksAndMessages(null);
        mPendingVideo = null;
        mHoldStart = null;
        mSnapshotTimeout = null;
        flushAfterSnapshot();
        mFirstFrameRendered = false;
        mSwipeId++;

        if (mContainer != null) {
            cancelAnimations();
            if (mContainer.getParent() instanceof ViewGroup) {
                ((ViewGroup) mContainer.getParent()).removeView(mContainer);
            }
            mContainer = null;
            mIn = null;
            mOut = null;
        }

        setSurfaceOffset(0);
        mSurfaceRoot = null;
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

        prepare();
        setSurfaceOffset(0);
        mFirstFrameRendered = false;
        // JoTube: black, never the thumbnail (a still image that pops away when the video starts)
        clearImage(mIn);
        mIn.setVisibility(View.VISIBLE);
        mHandler.postDelayed(mFailsafeHide, FAILSAFE_HIDE_MS);
    }

    /**
     * The swipe from one Short to the next (direction: next = the new one comes from the bottom).
     * No placeholder image: the old picture stays still (a snapshot of its last frame) until the new video's
     * first frame is rendered, then the snapshot slides out and the real, already playing video slides in.
     *
     * @param videoView the view the current video is rendered on: its last frame is the outgoing picture.
     *                  Null if it's not on the surface anymore (the player was reset): the thumbnail is used.
     */
    public void swipe(Video from, Video to, int direction, View videoView) {
        if (mContainer == null || to == null) {
            return;
        }

        int frameHeight = mIn.getLayoutParams() != null ? mIn.getLayoutParams().height : 0;
        int height = frameHeight > 0 ? frameHeight : mContainer.getHeight() > 0 ? mContainer.getHeight() :
                mContainer.getParent() instanceof View ? ((View) mContainer.getParent()).getHeight() : 0;

        if (direction == ShortsTransitionState.DIRECTION_NONE || height <= 0) {
            show(to, ShortsTransitionState.DIRECTION_NONE);
            return;
        }

        final float distance = height + GAP_DP * mContainer.getResources().getDisplayMetrics().density;

        if (mHoldStart != null) {
            // Still showing the old picture (the previous target wasn't ready yet): wait for this one instead
            mHandler.removeCallbacks(mHoldStart);
            mHoldStart = null;
            mFirstFrameRendered = false;
            mHandler.removeCallbacks(mFailsafeHide);
            mHandler.postDelayed(mFailsafeHide, FAILSAFE_HIDE_MS);
            hold(++mSwipeId, direction, distance);
            return;
        }

        // The black cover is on screen (the previous video hasn't shown a frame yet): that's the outgoing picture
        boolean coverOnScreen = mIsShown && !mFirstFrameRendered && mSnapshotTimeout == null &&
                mIn.getVisibility() == View.VISIBLE && mIn.getAlpha() > 0.5f;

        // Pressed again during the slide: the new outgoing picture starts where the video is now
        float outStart = mSwipe != null && mSurfaceRoot != null ? mSurfaceRoot.getTranslationY() : 0;

        prepare();
        mOutStart = outStart;
        setSurfaceOffset(outStart);
        mFirstFrameRendered = false;
        final int swipeId = ++mSwipeId;
        clearImage(mIn);
        mIn.setVisibility(View.GONE);
        mHandler.postDelayed(mFailsafeHide, FAILSAFE_HIDE_MS);

        if (coverOnScreen) {
            clearImage(mOut); // black
            hold(swipeId, direction, distance);
            return;
        }

        if (videoView instanceof TextureView && ((TextureView) videoView).isAvailable()) {
            setOutImage(captureTexture((TextureView) videoView), from);
            hold(swipeId, direction, distance);
            return;
        }

        if (videoView instanceof SurfaceView && Build.VERSION.SDK_INT >= 24 && videoView.getWidth() > 0 &&
                videoView.getHeight() > 0 && ((SurfaceView) videoView).getHolder().getSurface().isValid()) {
            final Bitmap bitmap;
            try {
                bitmap = obtainSnapshotBitmap(Math.max(1, videoView.getWidth() / 2), Math.max(1, videoView.getHeight() / 2));
            } catch (OutOfMemoryError e) {
                setOutImage(null, from);
                hold(swipeId, direction, distance);
                return;
            }

            // The old video is still on the surface (the switch comes later): copy its picture (a few ms)
            mSnapshotTimeout = () -> {
                if (swipeId == mSwipeId) {
                    Log.d(TAG, "Snapshot timed out");
                    mSnapshotTimeout = null;
                    flushAfterSnapshot();
                    setOutImage(null, from);
                    hold(swipeId, direction, distance);
                }
            };
            mHandler.postDelayed(mSnapshotTimeout, SNAPSHOT_TIMEOUT_MS);

            try {
                PixelCopy.request((SurfaceView) videoView, bitmap, result -> {
                    // Void if timed out or a newer swipe started
                    if (swipeId != mSwipeId || mSnapshotTimeout == null) {
                        return;
                    }
                    mHandler.removeCallbacks(mSnapshotTimeout);
                    mSnapshotTimeout = null;
                    flushAfterSnapshot();
                    setOutImage(result == PixelCopy.SUCCESS ? bitmap : null, from);
                    hold(swipeId, direction, distance);
                }, mHandler);
            } catch (IllegalArgumentException e) {
                // The surface is gone: the timeout continues with the thumbnail
            }
            return;
        }

        setOutImage(null, from);
        hold(swipeId, direction, distance);
    }

    /**
     * The old picture stays in place until the new video's first frame (or the timeout: then it slides with
     * a black cover over the new video until its frame comes).
     */
    private void hold(int swipeId, int direction, float distance) {
        if (mContainer == null || swipeId != mSwipeId) {
            return;
        }

        mOut.setTranslationY(mOutStart);
        mOut.setVisibility(View.VISIBLE);

        if (mFirstFrameRendered) {
            startSwipe(swipeId, direction, distance);
            return;
        }

        mHoldStart = () -> {
            mHoldStart = null;
            startSwipe(swipeId, direction, distance);
        };
        mHandler.postDelayed(mHoldStart, HOLD_TIMEOUT_MS);
    }

    /**
     * Runs the action once the outgoing picture is copied (at once if no copy is pending).
     */
    public void runAfterSnapshot(Runnable action) {
        if (action == null) {
            return;
        }

        if (mSnapshotTimeout == null) {
            action.run();
            return;
        }

        flushAfterSnapshot(); // an older one first
        mAfterSnapshot = action;
    }

    private void flushAfterSnapshot() {
        Runnable action = mAfterSnapshot;
        mAfterSnapshot = null;

        if (action != null) {
            action.run();
        }
    }

    /**
     * JoTube: the PixelCopy target is reused while the size is the same (no ~1 MB allocation per swipe).
     * Two bitmaps in turn: the one shown in mOut is never the target of the next copy.
     */
    private Bitmap obtainSnapshotBitmap(int width, int height) {
        mSnapshotIndex = 1 - mSnapshotIndex;
        Bitmap bitmap = mSnapshotBitmaps[mSnapshotIndex];

        if (bitmap == null || bitmap.isRecycled() || !bitmap.isMutable() || bitmap.getWidth() != width || bitmap.getHeight() != height) {
            bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            mSnapshotBitmaps[mSnapshotIndex] = bitmap;
        }

        return bitmap;
    }

    private static Bitmap captureTexture(TextureView view) {
        try {
            return view.getBitmap(Math.max(1, view.getWidth() / 2), Math.max(1, view.getHeight() / 2));
        } catch (RuntimeException | OutOfMemoryError e) {
            return null;
        }
    }

    private void setOutImage(Bitmap frame, Video from) {
        if (mOut == null) {
            return;
        }

        if (frame != null) {
            clearImage(mOut); // a late thumbnail load must not replace the snapshot
            mOut.setImageBitmap(frame);
        } else if (from != null) {
            loadThumbnail(mOut, from);
        }
    }

    private void startSwipe(int swipeId, int direction, float distance) {
        if (mContainer == null || swipeId != mSwipeId) {
            return;
        }

        final float outStart = mOutStart;
        final float outEnd = -direction * distance;
        final float inStart = direction * distance;

        mOut.setVisibility(View.VISIBLE);
        mOut.setTranslationY(outStart);
        // The new video: black until its first frame (not ready only after the hold timeout)
        clearImage(mIn);
        mIn.setVisibility(View.VISIBLE);
        mIn.setAlpha(mFirstFrameRendered ? 0f : 1f);
        mIn.setTranslationY(inStart);
        setSurfaceOffset(inStart);

        mSwipe = ValueAnimator.ofFloat(0f, 1f);
        mSwipe.setDuration(SWIPE_DURATION_MS);
        mSwipe.setInterpolator(SWIPE_INTERPOLATOR);
        mSwipe.addUpdateListener(animation -> {
            if (mContainer == null) {
                return;
            }
            float fraction = (float) animation.getAnimatedValue();
            float inOffset = inStart * (1f - fraction);
            mOut.setTranslationY(outStart + (outEnd - outStart) * fraction);
            mIn.setTranslationY(inOffset);
            setSurfaceOffset(inOffset);
        });
        mSwipe.addListener(new AnimatorListenerAdapter() {
            private boolean mCancelled;

            @Override
            public void onAnimationCancel(Animator animation) {
                mCancelled = true;
            }

            @Override
            public void onAnimationEnd(Animator animation) {
                if (mCancelled || swipeId != mSwipeId) {
                    return;
                }
                mSwipe = null;
                onSwipeEnd();
            }
        });
        mSwipe.start();
    }

    private void onSwipeEnd() {
        if (mContainer == null) {
            return;
        }

        setSurfaceOffset(0);
        mOut.setVisibility(View.GONE);
        clearImage(mOut);
        mOut.setTranslationY(0);
        mIn.setTranslationY(0);

        if (mFirstFrameRendered) {
            // The new video is in place and playing
            mIsShown = false;
            mHandler.removeCallbacks(mFailsafeHide);

            if (mIn.getVisibility() == View.VISIBLE && mIn.getAlpha() > 0.01f) {
                // The black cover is still fading out: let it finish (no black step)
                mIn.animate().cancel();
                mIn.animate().alpha(0f).setDuration((long) (REVEAL_DURATION_MS * mIn.getAlpha()))
                        .withEndAction(this::reset).start();
            } else {
                reset();
            }
        }
        // else: the thumbnail stays until the first frame
    }

    private void prepare() {
        mHandler.removeCallbacks(mFailsafeHide);
        cancelPendingShow();
        cancelAnimations();

        if (mSnapshotTimeout != null) {
            mHandler.removeCallbacks(mSnapshotTimeout);
            mSnapshotTimeout = null;
        }
        flushAfterSnapshot();
        if (mHoldStart != null) {
            mHandler.removeCallbacks(mHoldStart);
            mHoldStart = null;
        }

        mContainer.setVisibility(View.VISIBLE);
        mContainer.setAlpha(1f);
        mIn.setAlpha(1f);
        mIn.setTranslationY(0);
        mOut.setTranslationY(0);
        mOut.setVisibility(View.GONE);
        mIsShown = true;
    }

    private void setSurfaceOffset(float offset) {
        if (mSurfaceRoot != null && mSurfaceRoot.getTranslationY() != offset) {
            mSurfaceRoot.setTranslationY(offset);
        }
    }

    private static void loadThumbnail(ImageView image, Video video) {
        try {
            // Shorts: the vertical thumbnail. Regular videos: the HD one (the card image has black bars).
            // Original size, no transformation: the same memory cache entry as the preload (shown at once).
            String url = video.isShorts ? ShortsTransitionState.getThumbnailUrl(video.videoId) :
                    "https://i.ytimg.com/vi/" + video.videoId + "/maxresdefault.jpg";
            Glide.with(image)
                    .load(url)
                    .override(Target.SIZE_ORIGINAL)
                    .dontTransform()
                    .error(Glide.with(image).load(video.getCardImageUrl()))
                    .into(image); // no dontAnimate(): it's an option of the cache key (into() has no transition anyway)
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

        cancelPendingShow();

        if (mContainer == null || !mIsShown) {
            return;
        }

        if (animate && mSnapshotTimeout != null) {
            // The snapshot is due in a few ms: the swipe starts right after it (the frame is ready)
            return;
        }

        if (animate && mHoldStart != null) {
            // The new video is ready: slide now
            Runnable start = mHoldStart;
            mHandler.removeCallbacks(start);
            start.run();
            return;
        }

        if (animate && mSwipe != null) {
            // In the middle of the swipe: uncover the new video, it keeps moving into place
            mIn.animate().cancel();
            mIn.animate().alpha(0f).setDuration(REVEAL_DURATION_MS).start();
            return;
        }

        mHandler.removeCallbacks(mFailsafeHide);
        mIsShown = false;
        mSwipeId++;
        cancelAnimations();

        if (mSnapshotTimeout != null) {
            mHandler.removeCallbacks(mSnapshotTimeout);
            mSnapshotTimeout = null;
        }
        flushAfterSnapshot();
        if (mHoldStart != null) {
            mHandler.removeCallbacks(mHoldStart);
            mHoldStart = null;
        }

        if (!animate) {
            reset();
            return;
        }

        setSurfaceOffset(0);
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
        if (mSwipe != null) {
            ValueAnimator swipe = mSwipe;
            mSwipe = null;
            swipe.cancel();
        }
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

        setSurfaceOffset(0);
        mContainer.setVisibility(View.GONE);
        mContainer.setAlpha(1f);
        mIn.setAlpha(1f);
        mIn.setTranslationY(0);
        mOut.setTranslationY(0);
        mIn.setVisibility(View.GONE);
        mOut.setVisibility(View.GONE);
        clearImage(mIn);
        clearImage(mOut);
    }

    private static void clearImage(ImageView image) {
        try {
            Glide.with(image).clear(image);
        } catch (IllegalArgumentException e) {
            // Activity is destroyed
        }
        image.setImageDrawable(null);
    }
}
