package com.liskovsoft.smartyoutubetv2.mobile.ui.widgets;

import android.content.Context;
import android.graphics.Outline;
import android.view.View;
import android.view.ViewOutlineProvider;

public final class Ui {
    private Ui() {
    }

    public static int dp(Context context, float dp) {
        return Math.round(dp * context.getResources().getDisplayMetrics().density);
    }

    public static void makeCircle(View view) {
        view.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View v, Outline outline) {
                outline.setOval(0, 0, v.getWidth(), v.getHeight());
            }
        });
        view.setClipToOutline(true);
    }

    /** A fully rounded background (pills, chips over a tinted page). */
    public static android.graphics.drawable.Drawable pill(Context context, int color) {
        android.graphics.drawable.GradientDrawable shape = new android.graphics.drawable.GradientDrawable();
        shape.setColor(color);
        shape.setCornerRadius(dp(context, 100));
        android.content.res.ColorStateList ripple = android.content.res.ColorStateList.valueOf(0x33FFFFFF);
        return new android.graphics.drawable.RippleDrawable(ripple, shape, null);
    }

    /** A round touch feedback for icon buttons. */
    public static android.graphics.drawable.Drawable roundRipple() {
        return new android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x33FFFFFF), null, null);
    }

    public static void makeRounded(View view, int radiusPx) {
        view.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View v, Outline outline) {
                outline.setRoundRect(0, 0, v.getWidth(), v.getHeight(), radiusPx);
            }
        });
        view.setClipToOutline(true);
    }
}
