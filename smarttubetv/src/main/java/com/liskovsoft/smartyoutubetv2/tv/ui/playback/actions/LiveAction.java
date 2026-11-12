package com.liskovsoft.smartyoutubetv2.tv.ui.playback.actions;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import androidx.core.content.res.ResourcesCompat;
import androidx.leanback.widget.PlaybackControlsRow.MultiAction;
import com.liskovsoft.smartyoutubetv2.tv.R;

/**
 * JoTube: jumps to the live edge of a live stream (like TiViMate). A small framed "LIVE" with a dot:
 * red when playing at the live edge, grey when behind it. Shown only while a live stream plays.
 */
public class LiveAction extends MultiAction {
    public static final int INDEX_BEHIND = 0;
    public static final int INDEX_AT_EDGE = 1;
    private static final int COLOR_LIVE = 0xFFFF4E45;
    private static final int COLOR_BEHIND = 0xFF9AA0A6;

    public LiveAction(Context context) {
        super(R.id.action_live);

        setDrawables(new Drawable[] {createIcon(context, COLOR_BEHIND), createIcon(context, COLOR_LIVE)});

        String label = context.getString(R.string.go_to_live);
        setLabels(new String[] {label, label});

        setIndex(INDEX_BEHIND);
    }

    /**
     * Same size as the other icons (lb_control_icon_width/height): a rounded frame with the dot and the text inside.
     */
    private static BitmapDrawable createIcon(Context context, int dotColor) {
        float density = context.getResources().getDisplayMetrics().density;
        int size = context.getResources().getDimensionPixelSize(R.dimen.lb_control_icon_width);
        Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);

        float frameHeight = 15 * density;
        float stroke = 1.5f * density;
        RectF frame = new RectF(stroke / 2, (size - frameHeight) / 2, size - stroke / 2, (size + frameHeight) / 2);

        Paint framePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        framePaint.setStyle(Paint.Style.STROKE);
        framePaint.setStrokeWidth(stroke);
        framePaint.setColor(0xDDFFFFFF);
        canvas.drawRoundRect(frame, 3 * density, 3 * density, framePaint);

        Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        textPaint.setColor(0xFFFFFFFF);
        textPaint.setTextSize(8 * density);
        Typeface typeface = ResourcesCompat.getFont(context, R.font.roboto_medium);
        textPaint.setTypeface(typeface != null ? typeface : Typeface.DEFAULT_BOLD);
        textPaint.setLetterSpacing(0.05f);

        String text = "LIVE";
        float dotRadius = 2.5f * density;
        float gap = 2 * density;
        float textWidth = textPaint.measureText(text);
        float contentWidth = dotRadius * 2 + gap + textWidth;
        float startX = (size - contentWidth) / 2;
        float centerY = size / 2f;

        Paint dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        dotPaint.setColor(dotColor);
        canvas.drawCircle(startX + dotRadius, centerY, dotRadius, dotPaint);

        Paint.FontMetrics metrics = textPaint.getFontMetrics();
        float baseline = centerY - (metrics.ascent + metrics.descent) / 2;
        canvas.drawText(text, startX + dotRadius * 2 + gap, baseline, textPaint);

        return new BitmapDrawable(context.getResources(), bitmap);
    }
}
