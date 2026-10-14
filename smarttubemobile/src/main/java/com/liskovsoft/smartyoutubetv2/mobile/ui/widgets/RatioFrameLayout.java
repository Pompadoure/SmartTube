package com.liskovsoft.smartyoutubetv2.mobile.ui.widgets;

import android.content.Context;
import android.widget.FrameLayout;

/**
 * Height = width * ratio (16:9 = 0.5625, a Short = 1.7778).
 */
public class RatioFrameLayout extends FrameLayout {
    private float mHeightPerWidth = 9f / 16f;
    private boolean mFullscreen;

    public RatioFrameLayout(Context context) {
        super(context);
    }

    public void setHeightPerWidth(float ratio) {
        if (ratio != mHeightPerWidth) {
            mHeightPerWidth = ratio;
            requestLayout();
        }
    }

    /**
     * Landscape: fill the parent instead of keeping the ratio.
     */
    public void setFullscreen(boolean fullscreen) {
        if (fullscreen != mFullscreen) {
            mFullscreen = fullscreen;
            requestLayout();
        }
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        if (mFullscreen) {
            super.onMeasure(MeasureSpec.makeMeasureSpec(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(MeasureSpec.getSize(heightMeasureSpec), MeasureSpec.EXACTLY));
            return;
        }

        int width = MeasureSpec.getSize(widthMeasureSpec);
        int height = Math.round(width * mHeightPerWidth);
        super.onMeasure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY));
    }
}
