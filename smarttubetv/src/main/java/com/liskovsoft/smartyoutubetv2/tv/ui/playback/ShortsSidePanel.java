package com.liskovsoft.smartyoutubetv2.tv.ui.playback;

import android.content.Context;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.bumptech.glide.Glide;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.tv.R;
import com.liskovsoft.smartyoutubetv2.tv.ui.playback.actions.ActionHelpers;

import java.util.HashSet;
import java.util.Set;

/**
 * JoTube: info and buttons next to the vertical video, like the official app (title, channel, like,
 * dislike, comments, more). The video keeps its size, the panel sits in the free space to the right.<br/>
 * Remote: right (up when left/right switches Shorts) moves into the panel, left/back leaves it,
 * up/down inside the panel moves between the channel and the buttons, OK presses.
 */
public class ShortsSidePanel {
    public interface Callback {
        int getButtonState(int buttonId);
        void clickButton(int buttonId);
        void showControls();
        boolean isLeftRightSwitchEnabled();
    }

    private static final int ITEM_NONE = -1;
    private static final int ITEM_CHANNEL = 0;
    private static final int ITEM_LIKE = 1;
    private static final int ITEM_DISLIKE = 2;
    private static final int ITEM_COMMENTS = 3;
    private static final int ITEM_MORE = 4;
    private static final int FOCUS_TIMEOUT_MS = 10_000;
    private static final int MIN_WIDTH_DP = 180;
    private static final int MAX_WIDTH_DP = 460;
    private static final int GAP_DP = 48;

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final Runnable mFocusTimeout = () -> setFocus(ITEM_NONE);
    private final Runnable mUpdatePosition = this::updatePosition;
    private final Set<Integer> mConsumedKeys = new HashSet<>();
    private ViewGroup mRoot;
    private View mSurface;
    private LinearLayout mPanel;
    private TextView mTitle;
    private LinearLayout mChannelRow;
    private ImageView mAvatar;
    private TextView mAuthor;
    private final ImageView[] mButtons = new ImageView[5]; // index = item (channel slot unused)
    private TextView mLikeCount;
    private Callback mCallback;
    private int mFocus = ITEM_NONE;
    private boolean mEnabled; // a Short is playing and nothing covers the panel
    private boolean mFits; // positioned, with enough free space next to the video
    private String mAvatarUrl;
    private final View.OnLayoutChangeListener mLayoutListener =
            (v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> mHandler.post(mUpdatePosition);

    public void attach(ViewGroup root, Callback callback) {
        if (root == null || mPanel != null) {
            return;
        }

        mRoot = root;
        mCallback = callback;
        Context context = root.getContext();

        mPanel = new LinearLayout(context);
        mPanel.setOrientation(LinearLayout.VERTICAL);
        mPanel.setVisibility(View.GONE);
        mPanel.setFocusable(false);
        mPanel.setClickable(false);

        mTitle = new TextView(context);
        mTitle.setTextColor(Color.WHITE);
        mTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        mTitle.setTypeface(Typeface.DEFAULT_BOLD);
        mTitle.setMaxLines(3);
        mTitle.setEllipsize(TextUtils.TruncateAt.END);
        mTitle.setShadowLayer(dp(4), 0, dp(1), Color.argb(160, 0, 0, 0));
        mPanel.addView(mTitle, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        mChannelRow = new LinearLayout(context);
        mChannelRow.setOrientation(LinearLayout.HORIZONTAL);
        mChannelRow.setGravity(Gravity.CENTER_VERTICAL);
        mChannelRow.setPadding(dp(8), dp(6), dp(16), dp(6));
        mAvatar = new ImageView(context);
        mAvatar.setScaleType(ImageView.ScaleType.CENTER_CROP);
        mChannelRow.addView(mAvatar, new LinearLayout.LayoutParams(dp(36), dp(36)));
        mAuthor = new TextView(context);
        mAuthor.setTextColor(Color.WHITE);
        mAuthor.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        mAuthor.setSingleLine(true);
        mAuthor.setEllipsize(TextUtils.TruncateAt.END);
        mAuthor.setShadowLayer(dp(3), 0, dp(1), Color.argb(160, 0, 0, 0));
        LinearLayout.LayoutParams authorParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        authorParams.leftMargin = dp(12);
        mChannelRow.addView(mAuthor, authorParams);
        LinearLayout.LayoutParams channelParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        channelParams.topMargin = dp(18);
        channelParams.leftMargin = -dp(8); // the avatar lines up with the title
        mPanel.addView(mChannelRow, channelParams);

        LinearLayout buttonRow = new LinearLayout(context);
        buttonRow.setOrientation(LinearLayout.HORIZONTAL);
        buttonRow.setGravity(Gravity.CENTER_VERTICAL);
        mButtons[ITEM_LIKE] = createButton(context, buttonRow, R.drawable.lb_ic_thumb_up);
        mLikeCount = new TextView(context);
        mLikeCount.setTextColor(Color.WHITE);
        mLikeCount.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        mLikeCount.setShadowLayer(dp(3), 0, dp(1), Color.argb(160, 0, 0, 0));
        LinearLayout.LayoutParams countParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        countParams.leftMargin = dp(8);
        buttonRow.addView(mLikeCount, countParams);
        mButtons[ITEM_DISLIKE] = createButton(context, buttonRow, R.drawable.lb_ic_thumb_down);
        mButtons[ITEM_COMMENTS] = createButton(context, buttonRow, R.drawable.action_chat);
        mButtons[ITEM_MORE] = createButton(context, buttonRow, R.drawable.lb_ic_more);
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rowParams.topMargin = dp(20);
        mPanel.addView(buttonRow, rowParams);

        // Above the video and the subtitles, below the live chat and the player controls
        View subtitles = root.findViewById(R.id.leanback_subtitles);
        mSurface = root.findViewById(R.id.surface_root);
        int index = subtitles != null ? root.indexOfChild(subtitles) + 1 :
                mSurface != null ? root.indexOfChild(mSurface) + 1 : Math.min(1, root.getChildCount());
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(dp(MAX_WIDTH_DP), ViewGroup.LayoutParams.WRAP_CONTENT);
        params.gravity = Gravity.BOTTOM | Gravity.LEFT;
        root.addView(mPanel, index, params);

        if (mSurface != null) {
            mSurface.addOnLayoutChangeListener(mLayoutListener);
        }
        root.addOnLayoutChangeListener(mLayoutListener);

        updateStyles();
    }

    public void detach() {
        mHandler.removeCallbacksAndMessages(null);
        mConsumedKeys.clear();

        if (mSurface != null) {
            mSurface.removeOnLayoutChangeListener(mLayoutListener);
        }

        if (mRoot != null) {
            mRoot.removeOnLayoutChangeListener(mLayoutListener);
            if (mPanel != null) {
                mRoot.removeView(mPanel);
            }
        }

        mRoot = null;
        mSurface = null;
        mPanel = null;
        mCallback = null;
        mFocus = ITEM_NONE;
        mEnabled = false;
        mAvatarUrl = null;
    }

    /**
     * Show the info of the current Short.
     */
    public void bind(Video video) {
        if (mPanel == null || video == null) {
            return;
        }

        String title = video.getTitleFull() != null ? video.getTitleFull() : video.getTitle();
        mTitle.setText(title != null ? title : "");
        String author = video.getAuthor();
        mAuthor.setText(author != null ? author : "");
        mChannelRow.setVisibility(author != null || video.authorImageUrl != null ? View.VISIBLE : View.INVISIBLE);
        mLikeCount.setText(video.likeCount != null ? video.likeCount : "");
        loadAvatar(video.authorImageUrl);
        updateStyles();
    }

    /**
     * Like/dislike state changed.
     */
    public void refreshStates() {
        if (mPanel != null) {
            updateStyles();
        }
    }

    /**
     * A Short is playing and the player controls are hidden.
     */
    public void setEnabled(boolean enabled) {
        if (mEnabled == enabled || mPanel == null) {
            return;
        }

        mEnabled = enabled;

        if (!enabled) {
            setFocus(ITEM_NONE);
        } else {
            mHandler.post(mUpdatePosition);
        }

        updateVisibility();
    }

    public boolean isShown() {
        return mPanel != null && mEnabled && mFits;
    }

    /**
     * Keys go here before the player handles them. Returns true when consumed (key down and its key up).
     */
    public boolean onKey(KeyEvent event) {
        int keyCode = event.getKeyCode();

        if (event.getAction() == KeyEvent.ACTION_UP) {
            // The key up of a consumed key down never reaches the player (no half key presses there)
            return mConsumedKeys.remove(keyCode);
        }

        if (event.getAction() != KeyEvent.ACTION_DOWN) {
            return false;
        }

        // A held key belongs entirely to whoever got its first press (the player's controls track presses)
        if (event.getRepeatCount() > 0) {
            if (mConsumedKeys.contains(keyCode)) {
                if (mFocus != ITEM_NONE && isShown() && isNavigationKey(keyCode)) {
                    handleFocusedKey(keyCode, event.getRepeatCount()); // hold to move along the buttons
                }
                return true;
            }
            return false;
        }

        mConsumedKeys.remove(keyCode); // a stale entry (its key up went to another screen)

        if (!isShown()) {
            return false;
        }

        boolean handled = mFocus == ITEM_NONE ? handleEntryKey(keyCode) : handleFocusedKey(keyCode, 0);

        if (handled) {
            mConsumedKeys.add(keyCode);
        }

        return handled;
    }

    private static boolean isNavigationKey(int keyCode) {
        return keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT ||
                keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_DOWN;
    }

    private boolean handleEntryKey(int keyCode) {
        int entryKey = mCallback != null && mCallback.isLeftRightSwitchEnabled() ? KeyEvent.KEYCODE_DPAD_UP : KeyEvent.KEYCODE_DPAD_RIGHT;

        if (keyCode == entryKey) {
            setFocus(ITEM_LIKE);
            return true;
        }

        return false;
    }

    private boolean handleFocusedKey(int keyCode, int repeatCount) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (mFocus > ITEM_LIKE) {
                    setFocus(mFocus - 1);
                } else if (repeatCount == 0) {
                    setFocus(ITEM_NONE); // leave the panel
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (mFocus == ITEM_CHANNEL) {
                    setFocus(ITEM_LIKE);
                } else if (mFocus < ITEM_MORE) {
                    setFocus(mFocus + 1);
                } else {
                    restartTimeout();
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
                if (mFocus != ITEM_CHANNEL && mChannelRow.getVisibility() == View.VISIBLE) {
                    setFocus(ITEM_CHANNEL);
                } else {
                    restartTimeout();
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (mFocus == ITEM_CHANNEL) {
                    setFocus(ITEM_LIKE);
                } else {
                    restartTimeout();
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
            case KeyEvent.KEYCODE_BUTTON_A:
                if (repeatCount == 0) {
                    pressFocused();
                }
                return true;
            case KeyEvent.KEYCODE_BACK:
            case KeyEvent.KEYCODE_ESCAPE:
                setFocus(ITEM_NONE);
                return true;
            default:
                return false; // e.g. play/pause keys work as usual
        }
    }

    private void pressFocused() {
        Callback callback = mCallback;

        if (callback == null) {
            return;
        }

        switch (mFocus) {
            case ITEM_CHANNEL:
                setFocus(ITEM_NONE);
                callback.clickButton(R.id.action_channel);
                break;
            case ITEM_LIKE:
                callback.clickButton(R.id.action_thumbs_up);
                restartTimeout();
                updateStyles();
                break;
            case ITEM_DISLIKE:
                callback.clickButton(R.id.action_thumbs_down);
                restartTimeout();
                updateStyles();
                break;
            case ITEM_COMMENTS:
                setFocus(ITEM_NONE);
                callback.clickButton(R.id.action_chat);
                break;
            case ITEM_MORE:
                setFocus(ITEM_NONE);
                callback.showControls(); // all the player options
                break;
        }
    }

    private void setFocus(int item) {
        mFocus = item;
        mHandler.removeCallbacks(mFocusTimeout);

        if (item != ITEM_NONE) {
            mHandler.postDelayed(mFocusTimeout, FOCUS_TIMEOUT_MS);
        }

        if (mPanel != null) {
            updateStyles();
        }
    }

    private void restartTimeout() {
        if (mFocus != ITEM_NONE) {
            mHandler.removeCallbacks(mFocusTimeout);
            mHandler.postDelayed(mFocusTimeout, FOCUS_TIMEOUT_MS);
        }
    }

    private void updateVisibility() {
        if (mPanel != null) {
            mPanel.setVisibility(isShown() ? View.VISIBLE : View.GONE);
        }
    }

    /**
     * Next to the right edge of the video (the surface container is resized to the video).
     */
    private void updatePosition() {
        if (mPanel == null || mRoot == null || mSurface == null || !mEnabled) {
            return;
        }

        int rootWidth = mRoot.getWidth();
        int rootHeight = mRoot.getHeight();

        if (rootWidth <= 0 || rootHeight <= 0 || mSurface.getWidth() <= 0) {
            return;
        }

        float center = (mSurface.getLeft() + mSurface.getRight()) / 2f + mSurface.getTranslationX();
        int videoRight = Math.round(center + mSurface.getWidth() * mSurface.getScaleX() / 2f);
        int left = videoRight + dp(GAP_DP);
        int width = Math.min(rootWidth - left - dp(GAP_DP), dp(MAX_WIDTH_DP));

        boolean fits = width >= dp(MIN_WIDTH_DP);

        if (fits) {
            FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) mPanel.getLayoutParams();
            int bottom = Math.round(rootHeight * 0.12f);

            if (params.leftMargin != left || params.width != width || params.bottomMargin != bottom) {
                params.leftMargin = left;
                params.width = width;
                params.bottomMargin = bottom;
                mPanel.setLayoutParams(params);
            }
        }

        if (mFits != fits) {
            mFits = fits;
            if (!fits) {
                setFocus(ITEM_NONE);
            }
        }

        updateVisibility();
    }

    private void loadAvatar(String url) {
        if (mAvatar == null) {
            return;
        }

        if (url == null) {
            if (mAvatarUrl != null || mAvatar.getDrawable() == null) {
                mAvatarUrl = null;
                try {
                    Glide.with(mAvatar).clear(mAvatar);
                } catch (IllegalArgumentException e) {
                    // Activity destroyed
                }
                mAvatar.setImageResource(R.drawable.action_channel);
            }
            return;
        }

        if (url.equals(mAvatarUrl)) {
            return;
        }

        mAvatarUrl = url;

        try {
            Glide.with(mAvatar)
                    .load(url)
                    .circleCrop()
                    .placeholder(R.drawable.action_channel)
                    .into(mAvatar);
        } catch (IllegalArgumentException e) {
            // Activity destroyed
        }
    }

    private ImageView createButton(Context context, LinearLayout row, int iconResId) {
        ImageView button = new ImageView(context);
        button.setImageResource(iconResId);
        button.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        int padding = dp(14);
        button.setPadding(padding, padding, padding, padding);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(60), dp(60));
        params.rightMargin = dp(16);

        if (row.getChildCount() > 0 && !(row.getChildAt(row.getChildCount() - 1) instanceof ImageView)) {
            params.leftMargin = dp(16); // after the like count
        }

        row.addView(button, params);
        return button;
    }

    private void updateStyles() {
        if (mPanel == null) {
            return;
        }

        int highlight = ActionHelpers.getIconHighlightColor(mPanel.getContext());

        for (int item = ITEM_LIKE; item <= ITEM_MORE; item++) {
            ImageView button = mButtons[item];
            boolean focused = mFocus == item;
            boolean on = isOn(item);

            GradientDrawable background = new GradientDrawable();
            background.setShape(GradientDrawable.OVAL);
            background.setColor(focused ? Color.WHITE : Color.argb(70, 255, 255, 255));
            button.setBackground(background);

            int iconColor = focused ? (on ? highlight : Color.BLACK) : (on ? highlight : Color.WHITE);
            button.setColorFilter(iconColor, PorterDuff.Mode.SRC_IN);
            button.setScaleX(focused ? 1.1f : 1f);
            button.setScaleY(focused ? 1.1f : 1f);
        }

        boolean channelFocused = mFocus == ITEM_CHANNEL;
        GradientDrawable channelBackground = new GradientDrawable();
        channelBackground.setCornerRadius(dp(24));
        channelBackground.setColor(channelFocused ? Color.WHITE : Color.TRANSPARENT);
        mChannelRow.setBackground(channelBackground);
        mAuthor.setTextColor(channelFocused ? Color.BLACK : Color.WHITE);
        mAuthor.setShadowLayer(channelFocused ? 0 : dp(3), 0, channelFocused ? 0 : dp(1), Color.argb(160, 0, 0, 0));
    }

    private boolean isOn(int item) {
        if (mCallback == null) {
            return false;
        }

        if (item == ITEM_LIKE) {
            return mCallback.getButtonState(R.id.action_thumbs_up) == 1; // TwoStateAction.INDEX_ON
        }

        if (item == ITEM_DISLIKE) {
            return mCallback.getButtonState(R.id.action_thumbs_down) == 1;
        }

        return false;
    }

    private int dp(float value) {
        Context context = mPanel != null ? mPanel.getContext() : mRoot != null ? mRoot.getContext() : null;
        float density = context != null ? context.getResources().getDisplayMetrics().density : 1;
        return Math.round(value * density);
    }
}
