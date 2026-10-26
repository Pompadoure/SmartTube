package com.liskovsoft.smartyoutubetv2.tv.ui.widgets.search;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.liskovsoft.smartyoutubetv2.tv.R;

/**
 * JoTube: on-screen keyboard for the search (the Android keyboard never pops up).<br/>
 * Keys are plain focusable cells, so the D-pad moves between them like between any other views.
 * Right from the last column hands the focus over to the suggestions / results ({@link Listener#onLeaveRight()}).
 */
public class SearchKeyboardView extends LinearLayout {
    public interface Listener {
        void onChar(String text);
        void onBackspace();
        void onClear();
        void onSubmit();
        /** DPAD right at the right edge of the keyboard */
        boolean onLeaveRight();
    }

    private static final String KEYS = "abcdefghijklmnopqrstuvwxyzåäö0123456789";
    private static final int COLUMNS = 8;
    private static final int KEY_SIZE_DP = 46;
    private static final int KEY_GAP_DP = 3;

    private Listener mListener;
    private TextView mFirstKey;

    public SearchKeyboardView(Context context) {
        super(context);
        init(context);
    }

    public SearchKeyboardView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    public SearchKeyboardView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init(context);
    }

    public void setListener(Listener listener) {
        mListener = listener;
    }

    /**
     * Focus the first key
     */
    public boolean focusFirstKey() {
        return mFirstKey != null && mFirstKey.requestFocus();
    }

    private void init(Context context) {
        setOrientation(VERTICAL);
        setFocusable(false);
        setClipChildren(false);

        int rows = (KEYS.length() + COLUMNS - 1) / COLUMNS;

        for (int row = 0; row < rows; row++) {
            LinearLayout line = createLine(context);

            for (int col = 0; col < COLUMNS; col++) {
                int index = row * COLUMNS + col;

                if (index >= KEYS.length()) {
                    break;
                }

                final String ch = String.valueOf(KEYS.charAt(index));
                TextView key = createKey(context, ch, 1, col == COLUMNS - 1 || index == KEYS.length() - 1, col == 0);
                key.setOnClickListener(v -> {
                    if (mListener != null) {
                        mListener.onChar(ch);
                    }
                });
                line.addView(key);

                if (mFirstKey == null) {
                    mFirstKey = key;
                }
            }

            addView(line);
        }

        // Last line: space (wide), backspace, clear, search
        LinearLayout actions = createLine(context);

        TextView space = createKey(context, "Space", 2, false, true);
        space.setOnClickListener(v -> {
            if (mListener != null) {
                mListener.onChar(" ");
            }
        });
        actions.addView(space);

        TextView backspace = createKey(context, "Del", 2, false, false);
        backspace.setOnClickListener(v -> {
            if (mListener != null) {
                mListener.onBackspace();
            }
        });
        actions.addView(backspace);

        TextView clear = createKey(context, "Clear", 2, false, false);
        clear.setOnClickListener(v -> {
            if (mListener != null) {
                mListener.onClear();
            }
        });
        actions.addView(clear);

        TextView search = createKey(context, "Search", 2, true, false);
        search.setOnClickListener(v -> {
            if (mListener != null) {
                mListener.onSubmit();
            }
        });
        actions.addView(search);

        addView(actions);
    }

    private LinearLayout createLine(Context context) {
        LinearLayout line = new LinearLayout(context);
        line.setOrientation(HORIZONTAL);
        line.setClipChildren(false);
        line.setLayoutParams(new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));
        return line;
    }

    private TextView createKey(Context context, String label, int span, boolean rightEdge, boolean leftEdge) {
        TextView key = new TextView(context);
        key.setText(label);
        key.setTextSize(TypedValue.COMPLEX_UNIT_SP, label.length() > 1 ? 15 : 20);
        key.setTypeface(androidx.core.content.res.ResourcesCompat.getFont(context, R.font.roboto_medium));
        key.setGravity(Gravity.CENTER);
        key.setFocusable(true);
        key.setClickable(true);
        key.setTextColor(new android.content.res.ColorStateList(
                new int[][] {{android.R.attr.state_focused}, {}},
                new int[] {Color.BLACK, Color.WHITE}));
        key.setBackground(createBackground());

        int size = dp(context, KEY_SIZE_DP);
        int gap = dp(context, KEY_GAP_DP);
        LayoutParams params = new LayoutParams(size * span + gap * 2 * (span - 1), size);
        params.setMargins(gap, gap, gap, gap);
        key.setLayoutParams(params);

        key.setOnKeyListener((v, keyCode, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) {
                return false;
            }

            if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && rightEdge) {
                return mListener != null && mListener.onLeaveRight();
            }

            // Nothing to the left of the keyboard: don't let the focus wander off
            return keyCode == KeyEvent.KEYCODE_DPAD_LEFT && leftEdge;
        });

        return key;
    }

    private static StateListDrawable createBackground() {
        StateListDrawable states = new StateListDrawable();

        GradientDrawable focused = new GradientDrawable();
        focused.setColor(Color.WHITE);
        focused.setCornerRadius(12);

        GradientDrawable normal = new GradientDrawable();
        normal.setColor(Color.argb(70, 255, 255, 255));
        normal.setCornerRadius(12);

        states.addState(new int[] {android.R.attr.state_focused}, focused);
        states.addState(new int[] {}, normal);

        return states;
    }

    private static int dp(Context context, float value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
}
