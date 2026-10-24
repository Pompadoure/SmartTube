package com.liskovsoft.smartyoutubetv2.tv.ui.playback;

import android.view.View;
import android.view.ViewPropertyAnimator;

import com.liskovsoft.sharedutils.mylogger.Log;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;

/**
 * JoTube: {@link View#animate()} on the RenderThread, so a slide keeps moving while the main thread is busy (a Shorts
 * switch blocks it 200-370 ms: device log 2026-10-24). The framework has this backend (ViewPropertyAnimatorRT, the one
 * ripples use) but never sets it for apps; it is set here by reflection. It is used only for animations without
 * listeners or end actions (otherwise the framework itself falls back to the main thread), so callers schedule their
 * clean-up with a delayed post instead. If the platform blocks the access, the normal animator is used.
 */
final class RenderThreadAnimation {
    private static final String TAG = RenderThreadAnimation.class.getSimpleName();
    private static Field sBackend;
    private static Constructor<?> sConstructor;
    private static boolean sAvailable;

    static {
        try {
            Field backend = ViewPropertyAnimator.class.getDeclaredField("mRTBackend");
            backend.setAccessible(true);
            Class<?> type = Class.forName("android.view.ViewPropertyAnimatorRT");
            Constructor<?> constructor = type.getDeclaredConstructor(View.class);
            constructor.setAccessible(true);
            sBackend = backend;
            sConstructor = constructor;
            sAvailable = true;
        } catch (Throwable e) {
            sAvailable = false;
            Log.w(TAG, "RenderThread animations not available: %s", e);
        }
    }

    private RenderThreadAnimation() {
    }

    /**
     * Like {@code view.animate()}. Don't add listeners or end actions (they would move it to the main thread).
     */
    static ViewPropertyAnimator animate(View view) {
        ViewPropertyAnimator animator = view.animate();

        if (sAvailable && view.isHardwareAccelerated()) {
            try {
                if (sBackend.get(animator) == null) {
                    sBackend.set(animator, sConstructor.newInstance(view));
                }
            } catch (Throwable e) {
                sAvailable = false;
                Log.w(TAG, "RenderThread animations disabled: %s", e);
            }
        }

        return animator;
    }

    static boolean isAvailable() {
        return sAvailable;
    }
}
