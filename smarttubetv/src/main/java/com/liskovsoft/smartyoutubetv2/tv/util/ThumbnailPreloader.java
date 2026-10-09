package com.liskovsoft.smartyoutubetv2.tv.util;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.os.Build.VERSION;
import androidx.leanback.widget.ObjectAdapter;
import androidx.leanback.widget.Presenter;
import com.bumptech.glide.Glide;
import com.bumptech.glide.RequestBuilder;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.tv.presenter.VideoCardPresenter;

import java.util.Iterator;
import java.util.LinkedHashSet;

/**
 * JoTube: warms Glide cache with card thumbnails that are about to appear on screen.<br/>
 * The preload request is built by {@link #applyCardOptions} - the same method the card presenter uses for binding,
 * so model url, size, transformation and cache options (i.e. the cache key) are identical.
 */
public final class ThumbnailPreloader {
    private static final int MAX_REMEMBERED = 300;
    private static final LinkedHashSet<String> sRecent = new LinkedHashSet<>();

    private ThumbnailPreloader() {
    }

    /**
     * Options shared by card binding and preloading. Must stay in sync for both (cache key!).
     */
    public static RequestBuilder<Drawable> applyCardOptions(RequestBuilder<Drawable> builder, int width, int height) {
        return builder
                .apply(ViewUtil.glideOptions())
                // glideOptions() disables memory cache (animation restart). Thumbnails are static, so allow it: otherwise preload can only warm the disk cache.
                .skipMemoryCache(false)
                // improve image compression on low end devices
                .override(width, height)
                // Explicit: Glide adds the same transformation implicitly for ImageView with scaleType=centerCrop (see widget_preview_card.xml).
                // Preload has no ImageView, so the transformation is part of the key and must be set by hand.
                .centerCrop()
                // Cache makes app crashing on old android versions
                .diskCacheStrategy(VERSION.SDK_INT > 21 ? DiskCacheStrategy.ALL : DiskCacheStrategy.NONE);
    }

    /**
     * Preload items [from, from + count) of the adapter. Safe to call often (urls are deduplicated).
     */
    public static void preload(Context context, ObjectAdapter adapter, int from, int count) {
        if (context == null || adapter == null || count <= 0) {
            return;
        }

        int end = Math.min(adapter.size(), Math.max(from, 0) + count);

        for (int i = Math.max(from, 0); i < end; i++) {
            Object item = adapter.get(i);

            if (!(item instanceof Video)) {
                continue;
            }

            Presenter presenter = adapter.getPresenter(item);

            if (presenter instanceof VideoCardPresenter) {
                ((VideoCardPresenter) presenter).preload(context, (Video) item);
            }
        }
    }

    /**
     * @param url final model url, exactly as passed to Glide by the card presenter
     */
    public static void preload(Context context, String url, int width, int height) {
        if (context == null || url == null || width <= 0 || height <= 0) {
            return;
        }

        if (!markRecent(url + "#" + width + "x" + height)) {
            return;
        }

        try {
            // Application context: the request doesn't depend on activity lifecycle (no "destroyed activity" crash).
            applyCardOptions(Glide.with(context.getApplicationContext()).load(url), width, height)
                    .preload(width, height); // decoding is done on Glide executors
        } catch (RuntimeException e) {
            // Preload is optional. Never break the UI because of it.
        }
    }

    private static synchronized boolean markRecent(String key) {
        if (!sRecent.add(key)) {
            return false;
        }

        if (sRecent.size() > MAX_REMEMBERED) {
            Iterator<String> it = sRecent.iterator();
            it.next();
            it.remove();
        }

        return true;
    }
}
