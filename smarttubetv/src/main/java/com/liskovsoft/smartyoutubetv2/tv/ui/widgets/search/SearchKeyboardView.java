package com.liskovsoft.smartyoutubetv2.tv.ui.widgets.search;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.liskovsoft.smartyoutubetv2.tv.R;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * JoTube: on-screen keyboard for the search (the Android keyboard never pops up), laid out like the official
 * YouTube TV app: letters A-Z in alphabetical rows (plus Å Ä Ö), a side column with delete and the &123 layer,
 * and SPACE / CLEAR / SEARCH below. Plain focusable cells, so the D-pad moves between them like between any views.
 * The edges hand the focus over: left = the suggestions, down = the results ({@link Listener}).
 */
public class SearchKeyboardView extends LinearLayout {
    public interface Listener {
        void onChar(String text);
        void onBackspace();
        void onClear();
        void onSubmit();
        /** DPAD right at the right edge of the keyboard */
        boolean onLeaveRight();
        /** DPAD left at the left edge of the keyboard (the suggestions are there) */
        boolean onLeaveLeft();
        /** DPAD down from the bottom row (the results are there) */
        boolean onLeaveDown();
    }

    private static final int COLUMNS = 8;
    private static final String DELETE = "\b"; // a vector icon (the glyph isn't in every font)
    private static final String LAYER_SYMBOLS = "&123";
    private static final String LAYER_LETTERS = "ABC";
    // 4 rows x 8 cells. Special cells: DELETE and the layer switch.
    private static final String[][] LETTERS = {
            {"a", "b", "c", "d", "e", "f", "g", DELETE},
            {"h", "i", "j", "k", "l", "m", "n", LAYER_SYMBOLS},
            {"o", "p", "q", "r", "s", "t", "u", "å"},
            {"v", "w", "x", "y", "z", "ä", "ö", "-"},
    };
    private static final String[][] SYMBOLS = {
            {"1", "2", "3", "4", "5", "6", "7", DELETE},
            {"8", "9", "0", ".", ",", "?", "!", LAYER_LETTERS},
            {"@", "#", "&", "(", ")", "+", ":", "'"},
            {"\"", "/", "_", "*", "%", "=", ";", "-"},
    };
    private static final int KEY_SIZE_DP = 42;
    private static final int KEY_GAP_DP = 3;
    private static final int TEXT_COLOR = 0xFFF1F1F1;

    private Listener mListener;
    private TextView mFirstKey;
    private final List<TextView> mGridKeys = new ArrayList<>();
    private boolean mSymbols;

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

        for (int row = 0; row < LETTERS.length; row++) {
            LinearLayout line = createLine(context);

            for (int col = 0; col < COLUMNS; col++) {
                final int r = row;
                final int c = col;
                TextView key = createKey(context, "", 1, false, col == COLUMNS - 1, col == 0, false);
                key.setOnClickListener(v -> onGridKey(r, c));
                line.addView(key);
                mGridKeys.add(key);

                if (mFirstKey == null) {
                    mFirstKey = key;
                }
            }

            addView(line);
        }

        // Last line: SPACE, CLEAR, SEARCH (like the official app)
        LinearLayout actions = createLine(context);
        actions.setPadding(0, dp(context, 6), 0, 0);

        TextView space = createKey(context, "MELLANSLAG", 3, true, false, true, true);
        space.setOnClickListener(v -> {
            if (mListener != null) {
                mListener.onChar(" ");
            }
        });
        actions.addView(space);

        TextView clear = createKey(context, "RENSA", 2, true, false, false, true);
        clear.setOnClickListener(v -> {
            if (mListener != null) {
                mListener.onClear();
            }
        });
        actions.addView(clear);

        TextView search = createKey(context, "SÖK", 3, true, true, false, true);
        search.setOnClickListener(v -> {
            if (mListener != null) {
                mListener.onSubmit();
            }
        });
        actions.addView(search);

        addView(actions);

        applyLayer();
    }

    private void onGridKey(int row, int col) {
        String label = (mSymbols ? SYMBOLS : LETTERS)[row][col];

        if (DELETE.equals(label)) {
            if (mListener != null) {
                mListener.onBackspace();
            }
        } else if (LAYER_SYMBOLS.equals(label) || LAYER_LETTERS.equals(label)) {
            mSymbols = !mSymbols;
            applyLayer(); // the focus stays on the same cell (the switch is in the same place in both layers)
        } else if (mListener != null) {
            mListener.onChar(label);
        }
    }

    private void applyLayer() {
        String[][] layer = mSymbols ? SYMBOLS : LETTERS;

        for (int row = 0; row < layer.length; row++) {
            for (int col = 0; col < COLUMNS; col++) {
                TextView key = mGridKeys.get(row * COLUMNS + col);
                String label = layer[row][col];
                if (DELETE.equals(label)) {
                    setDeleteIcon(key);
                    continue;
                }
                boolean special = label.length() > 1;
                key.setText(label.length() == 1 && Character.isLetter(label.charAt(0)) ? label.toUpperCase(Locale.ROOT) : label);
                key.setTextSize(TypedValue.COMPLEX_UNIT_SP, special ? 14 : 21);
            }
        }
    }

    private void setDeleteIcon(TextView key) {
        key.setText("");
        if (key.getCompoundDrawablesRelative()[0] != null) {
            return;
        }
        android.graphics.drawable.Drawable icon = androidx.core.content.ContextCompat.getDrawable(key.getContext(), R.drawable.ic_key_backspace);
        if (icon == null) {
            key.setText("DEL");
            return;
        }
        icon = androidx.core.graphics.drawable.DrawableCompat.wrap(icon.mutate());
        androidx.core.graphics.drawable.DrawableCompat.setTintList(icon, key.getTextColors()); // dark when focused
        key.setCompoundDrawablesRelativeWithIntrinsicBounds(icon, null, null, null);
        int keyWidth = key.getLayoutParams().width;
        key.setPaddingRelative(Math.max(0, (keyWidth - icon.getIntrinsicWidth()) / 2), 0, 0, 0);
        key.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
    }

    private LinearLayout createLine(Context context) {
        LinearLayout line = new LinearLayout(context);
        line.setOrientation(HORIZONTAL);
        line.setClipChildren(false);
        line.setLayoutParams(new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));
        return line;
    }

    private TextView createKey(Context context, String label, int span, boolean filled, boolean rightEdge, boolean leftEdge,
                               boolean bottomRow) {
        TextView key = new TextView(context);
        key.setText(label);
        key.setTextSize(TypedValue.COMPLEX_UNIT_SP, label.length() > 1 ? 14 : 21);
        key.setTypeface(androidx.core.content.res.ResourcesCompat.getFont(context, R.font.roboto_medium));
        key.setGravity(Gravity.CENTER);
        key.setFocusable(true);
        key.setClickable(true);
        key.setTextColor(new ColorStateList(
                new int[][] {{android.R.attr.state_focused}, {}},
                new int[] {Color.BLACK, TEXT_COLOR}));
        key.setBackground(createBackground(context, filled));

        int size = dp(context, KEY_SIZE_DP);
        int gap = dp(context, KEY_GAP_DP);
        LayoutParams params = new LayoutParams(size * span + gap * 2 * (span - 1), bottomRow ? dp(context, 38) : size);
        params.setMargins(gap, gap, gap, gap);
        key.setLayoutParams(params);

        key.setOnKeyListener((v, keyCode, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) {
                return false;
            }

            if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && rightEdge) {
                return mListener != null && mListener.onLeaveRight();
            }

            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT && leftEdge) {
                // Nothing else to the left of the keyboard than the suggestions: don't let the focus wander off
                if (mListener != null) {
                    mListener.onLeaveLeft();
                }
                return true;
            }

            if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN && bottomRow) {
                if (mListener != null) {
                    mListener.onLeaveDown();
                }
                return true;
            }

            return false;
        });

        return key;
    }

    /**
     * Like the official app: the letters have no background (white text), SPACE / CLEAR / SEARCH are soft grey
     * buttons; the focused key is white with dark text.
     */
    private static StateListDrawable createBackground(Context context, boolean filled) {
        StateListDrawable states = new StateListDrawable();
        float radius = dp(context, filled ? 8 : 21);

        GradientDrawable focused = new GradientDrawable();
        focused.setColor(Color.WHITE);
        focused.setCornerRadius(radius);

        GradientDrawable normal = new GradientDrawable();
        normal.setColor(filled ? Color.argb(60, 255, 255, 255) : Color.TRANSPARENT);
        normal.setCornerRadius(radius);

        states.addState(new int[] {android.R.attr.state_focused}, focused);
        states.addState(new int[] {}, normal);

        return states;
    }

    private static int dp(Context context, float value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
}
