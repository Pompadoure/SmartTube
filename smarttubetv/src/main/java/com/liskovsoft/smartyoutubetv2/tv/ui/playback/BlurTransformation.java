package com.liskovsoft.smartyoutubetv2.tv.ui.playback;

import android.graphics.Bitmap;

import androidx.annotation.NonNull;

import com.bumptech.glide.load.engine.bitmap_recycle.BitmapPool;
import com.bumptech.glide.load.resource.bitmap.BitmapTransformation;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Software box blur (3 passes = close to gaussian) for the Shorts background. Works on a small bitmap
 * (Glide override), so it's cheap and the result is smooth after upscaling.
 */
public class BlurTransformation extends BitmapTransformation {
    private static final String ID = "com.liskovsoft.smartyoutubetv2.tv.ui.playback.BlurTransformation";
    private static final int PASSES = 3;
    private final int mRadius;

    public BlurTransformation(int radius) {
        mRadius = radius;
    }

    @Override
    protected Bitmap transform(@NonNull BitmapPool pool, @NonNull Bitmap toTransform, int outWidth, int outHeight) {
        int width = toTransform.getWidth();
        int height = toTransform.getHeight();
        int[] src = new int[width * height];
        int[] dst = new int[width * height];
        toTransform.getPixels(src, 0, width, 0, 0, width, height);

        for (int i = 0; i < PASSES; i++) {
            blurHorizontal(src, dst, width, height, mRadius);
            blurVertical(dst, src, width, height, mRadius);
        }

        Bitmap result = pool.get(width, height, Bitmap.Config.ARGB_8888);
        result.setPixels(src, 0, width, 0, 0, width, height);
        return result;
    }

    private static void blurHorizontal(int[] in, int[] out, int w, int h, int r) {
        int div = 2 * r + 1;
        for (int y = 0; y < h; y++) {
            int row = y * w;
            int sumR = 0, sumG = 0, sumB = 0;
            for (int x = -r; x <= r; x++) {
                int p = in[row + clamp(x, w)];
                sumR += (p >> 16) & 0xff; sumG += (p >> 8) & 0xff; sumB += p & 0xff;
            }
            for (int x = 0; x < w; x++) {
                out[row + x] = 0xff000000 | ((sumR / div) << 16) | ((sumG / div) << 8) | (sumB / div);
                int pOut = in[row + clamp(x - r, w)];
                int pIn = in[row + clamp(x + r + 1, w)];
                sumR += ((pIn >> 16) & 0xff) - ((pOut >> 16) & 0xff);
                sumG += ((pIn >> 8) & 0xff) - ((pOut >> 8) & 0xff);
                sumB += (pIn & 0xff) - (pOut & 0xff);
            }
        }
    }

    private static void blurVertical(int[] in, int[] out, int w, int h, int r) {
        int div = 2 * r + 1;
        for (int x = 0; x < w; x++) {
            int sumR = 0, sumG = 0, sumB = 0;
            for (int y = -r; y <= r; y++) {
                int p = in[clamp(y, h) * w + x];
                sumR += (p >> 16) & 0xff; sumG += (p >> 8) & 0xff; sumB += p & 0xff;
            }
            for (int y = 0; y < h; y++) {
                out[y * w + x] = 0xff000000 | ((sumR / div) << 16) | ((sumG / div) << 8) | (sumB / div);
                int pOut = in[clamp(y - r, h) * w + x];
                int pIn = in[clamp(y + r + 1, h) * w + x];
                sumR += ((pIn >> 16) & 0xff) - ((pOut >> 16) & 0xff);
                sumG += ((pIn >> 8) & 0xff) - ((pOut >> 8) & 0xff);
                sumB += (pIn & 0xff) - (pOut & 0xff);
            }
        }
    }

    private static int clamp(int v, int max) {
        return v < 0 ? 0 : (v >= max ? max - 1 : v);
    }

    @Override
    public void updateDiskCacheKey(@NonNull MessageDigest messageDigest) {
        messageDigest.update((ID + mRadius).getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof BlurTransformation && ((BlurTransformation) o).mRadius == mRadius;
    }

    @Override
    public int hashCode() {
        return ID.hashCode() * 31 + mRadius;
    }
}
