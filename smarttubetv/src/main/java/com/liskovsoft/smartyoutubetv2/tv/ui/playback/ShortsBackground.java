package com.liskovsoft.smartyoutubetv2.tv.ui.playback;

import android.content.Context;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;

import androidx.annotation.Nullable;

import com.bumptech.glide.Glide;
import com.bumptech.glide.RequestBuilder;
import com.bumptech.glide.RequestManager;
import com.bumptech.glide.load.DataSource;
import com.bumptech.glide.load.engine.GlideException;
import com.bumptech.glide.request.RequestListener;
import com.bumptech.glide.request.target.Target;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.ShortsTransitionState;

import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Dynamic background for Shorts, like the official app: a blurred, darkened version of the video's
 * thumbnail fills the area next to the vertical video (downscaled + box blurred, no RenderScript).<br/>
 * JoTube: two layers. The new background is loaded into the back layer and cross-faded in when it's ready, so the
 * old one stays until then (no black flash between Shorts, also when the vertical thumbnail doesn't exist).
 */
public class ShortsBackground {
    // Small bitmap, real blur on top of it (smooth after upscaling, no visible pixels)
    private static final int BLUR_WIDTH = 120;
    private static final int BLUR_HEIGHT = 214;
    private static final int BLUR_RADIUS = 10;
    private static final int DIM_COLOR = Color.argb(150, 0, 0, 0);
    private static final int FADE_DURATION_MS = 250;
    private static final LinkedHashSet<String> sPreloaded = new LinkedHashSet<>();
    private FrameLayout mContainer;
    private ImageView mFront; // shown
    private ImageView mBack; // the next one loads here
    private String mVideoId;

    /**
     * Placed behind the video surface (index 0 of the fragment root).
     */
    public void attach(ViewGroup root) {
        if (root == null || mContainer != null) {
            return;
        }

        mContainer = new FrameLayout(root.getContext());
        mContainer.setVisibility(View.GONE);
        mContainer.setFocusable(false);
        mContainer.setClickable(false);
        mBack = createLayer(mContainer);
        mFront = createLayer(mContainer);

        root.addView(mContainer, 0, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    private static ImageView createLayer(FrameLayout container) {
        ImageView view = new ImageView(container.getContext());
        view.setScaleType(ImageView.ScaleType.CENTER_CROP);
        view.setColorFilter(DIM_COLOR, PorterDuff.Mode.SRC_ATOP);
        view.setFocusable(false);
        view.setClickable(false);
        container.addView(view, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        return view;
    }

    public void detach() {
        if (mContainer != null) {
            clear(mFront);
            clear(mBack);
            if (mContainer.getParent() instanceof ViewGroup) {
                ((ViewGroup) mContainer.getParent()).removeView(mContainer);
            }
            mContainer = null;
            mFront = null;
            mBack = null;
        }

        mVideoId = null;
    }

    public void show(Video video) {
        if (mContainer == null || video == null || video.videoId == null) {
            return;
        }

        if (video.videoId.equals(mVideoId) && mContainer.getVisibility() == View.VISIBLE) {
            return;
        }

        mVideoId = video.videoId;
        mContainer.setVisibility(View.VISIBLE);

        // A fade still running: finish it (its end action would clear the layer that loads now)
        mFront.animate().cancel();
        mFront.setAlpha(1f);

        final ImageView target = mBack;
        final String videoId = video.videoId;
        target.animate().cancel();
        target.setAlpha(0f);

        RequestListener<Drawable> onReady = new RequestListener<Drawable>() {
            @Override
            public boolean onLoadFailed(@Nullable GlideException e, Object model, Target<Drawable> t, boolean isFirstResource) {
                return false; // the error request (card image) follows
            }

            @Override
            public boolean onResourceReady(Drawable resource, Object model, Target<Drawable> t, DataSource dataSource, boolean isFirstResource) {
                // Set by Glide right after this returns: fade it in over the current one (posted: after the set)
                target.post(() -> reveal(target, videoId));
                return false;
            }
        };

        try {
            blurred(Glide.with(target), videoId)
                    .listener(onReady)
                    .error(Glide.with(target).load(video.getCardImageUrl()).override(BLUR_WIDTH, BLUR_HEIGHT)
                            .transform(new BlurTransformation(BLUR_RADIUS)).listener(onReady))
                    .into(target); // no dontAnimate(): it's part of the cache key (into() has no transition anyway)
        } catch (IllegalArgumentException e) {
            // Activity is destroyed
        }
    }

    private void reveal(ImageView target, String videoId) {
        if (mContainer == null || target != mBack || !videoId.equals(mVideoId)) {
            return; // a newer one is loading meanwhile
        }

        final ImageView old = mFront;
        mFront = target;
        mBack = old;
        // Order by z (no relayout, unlike bringChildToFront)
        target.setZ(1f);
        old.setZ(0f);
        target.animate()
                .alpha(1f)
                .setDuration(old.getDrawable() != null ? FADE_DURATION_MS : 0)
                .withEndAction(() -> {
                    if (mBack == old && mContainer != null) {
                        clear(old); // behind the shown one now
                    }
                })
                .start();
    }

    private static void clear(ImageView view) {
        if (view == null) {
            return;
        }

        view.animate().cancel();

        try {
            Glide.with(view).clear(view);
        } catch (IllegalArgumentException e) {
            // Activity is destroyed
        }

        view.setImageDrawable(null);
    }

    /**
     * JoTube: the same request as in show() (same memory cache key): url, size and blur.
     */
    private static RequestBuilder<Drawable> blurred(RequestManager glide, String videoId) {
        return glide
                .load(ShortsTransitionState.getThumbnailUrl(videoId))
                .override(BLUR_WIDTH, BLUR_HEIGHT)
                .transform(new BlurTransformation(BLUR_RADIUS));
    }

    /**
     * JoTube: blur the backgrounds of the next Shorts in advance, so a switch shows them at once (memory cache).
     */
    public static void preload(Context context, List<String> videoIds) {
        if (context == null || videoIds == null) {
            return;
        }

        for (String videoId : videoIds) {
            if (videoId == null || !sPreloaded.add(videoId)) {
                continue;
            }

            if (sPreloaded.size() > 100) {
                Iterator<String> iterator = sPreloaded.iterator();
                iterator.next();
                iterator.remove();
            }

            try {
                blurred(Glide.with(context.getApplicationContext()), videoId).preload(BLUR_WIDTH, BLUR_HEIGHT);
            } catch (RuntimeException e) {
                // optional
            }
        }
    }

    /**
     * JoTube: the cover of a Short sliding in before its first frame: its blurred, dimmed background
     * (memory cache, preloaded) instead of black. Undo with {@link #clearCover}.
     */
    public static void loadCover(ImageView view, Video video) {
        if (view == null || video == null || video.videoId == null) {
            return;
        }

        view.setScaleType(ImageView.ScaleType.CENTER_CROP);
        view.setColorFilter(DIM_COLOR, PorterDuff.Mode.SRC_ATOP);

        try {
            blurred(Glide.with(view), video.videoId)
                    .error(Glide.with(view).load(video.getCardImageUrl()).override(BLUR_WIDTH, BLUR_HEIGHT)
                            .transform(new BlurTransformation(BLUR_RADIUS)))
                    .into(view);
        } catch (IllegalArgumentException e) {
            // Activity is destroyed
        }
    }

    public static void clearCover(ImageView view) {
        if (view == null) {
            return;
        }

        view.clearColorFilter();
        view.setScaleType(ImageView.ScaleType.FIT_CENTER);
    }

    public void hide() {
        mVideoId = null;

        if (mContainer != null) {
            mContainer.setVisibility(View.GONE);
            clear(mFront);
            clear(mBack);
        }
    }
}
