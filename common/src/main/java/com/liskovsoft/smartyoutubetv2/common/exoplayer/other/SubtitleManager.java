package com.liskovsoft.smartyoutubetv2.common.exoplayer.other;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Build.VERSION;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.Html;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.Spanned;
import android.util.TypedValue;
import android.view.View;
import android.view.accessibility.CaptioningManager;
import android.view.accessibility.CaptioningManager.CaptionStyle;

import androidx.annotation.RequiresApi;
import androidx.core.content.ContextCompat;
import androidx.media3.ui.CaptionStyleCompat;
import androidx.media3.common.Player;
import androidx.media3.common.text.Cue;
import androidx.media3.common.text.CueGroup;
import androidx.media3.ui.SubtitleView;
import com.liskovsoft.sharedutils.helpers.Helpers;
import com.liskovsoft.sharedutils.misc.PaddingBackgroundColorSpan;
import com.liskovsoft.smartyoutubetv2.common.R;
import com.liskovsoft.smartyoutubetv2.common.prefs.AppPrefs;
import com.liskovsoft.smartyoutubetv2.common.prefs.common.DataChangeBase.OnDataChange;
import com.liskovsoft.smartyoutubetv2.common.prefs.PlayerData;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * JoTube M3: a Player.Listener (Media3 has no TextOutput registration on the player): register with player.addListener().
 */
public class SubtitleManager implements Player.Listener, OnDataChange {
    private static final String TAG = SubtitleManager.class.getSimpleName();
    private final SubtitleView mSubtitleView;
    private final Context mContext;
    private final List<SubtitleStyle> mSubtitleStyles = new ArrayList<>();
    private final AppPrefs mPrefs;
    private final PlayerData mPlayerData;
    private CharSequence subsBuffer;
    // Fork fix (2.10.6 WebvttSubtitle): a cue is shown 10 sec at most. The cue end time isn't in Media3's Cue.
    private static final long MAX_CUE_LENGTH_MS = 10_000;
    // Entities Media3's WebvttCueParser leaves alone (it only decodes &amp; &lt; &gt; &nbsp; &lrm; &rlm;)
    private static final Pattern ENTITY = Pattern.compile("&(#\\d+|#[xX][0-9a-fA-F]+|[a-zA-Z][a-zA-Z0-9]*);");
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final Runnable mClearCues = this::clearCues;
    private int mBackgroundColor = Color.TRANSPARENT;
    private boolean mIsPlaying = true;
    private boolean mHasCues;
    private long mCueRemainingMs;
    private long mCueTimerStartMs;

    public static class SubtitleStyle {
        public final int nameResId;
        public final int subsColorResId;
        public final int backgroundColorResId;
        public final int captionStyle;

        public SubtitleStyle(int nameResId) {
            this(nameResId, -1, -1, -1);
        }

        public SubtitleStyle(int nameResId, int subsColorResId, int backgroundColorResId, int captionStyle) {
            this.nameResId = nameResId;
            this.subsColorResId = subsColorResId;
            this.backgroundColorResId = backgroundColorResId;
            this.captionStyle = captionStyle;
        }

        public boolean isSystem() {
            return subsColorResId == -1 && backgroundColorResId == -1 && captionStyle == -1;
        }
    }

    public SubtitleManager(SubtitleView subtitleView) {
        mContext = subtitleView.getContext();
        mSubtitleView = subtitleView;
        mPrefs = AppPrefs.instance(mContext);
        mPlayerData = PlayerData.instance(mContext);
        mPlayerData.setOnChange(this);
        configureSubtitleView();
    }

    @Override
    public void onDataChange() {
        configureSubtitleView();
    }

    @Override
    public void onCues(CueGroup cueGroup) {
        if (mSubtitleView != null) {
            List<Cue> cues = forceCenterAlignment(cueGroup.cues);
            mSubtitleView.setCues(cues);
            mHandler.removeCallbacks(mClearCues);
            mHasCues = !cues.isEmpty();
            if (mHasCues) {
                mCueRemainingMs = MAX_CUE_LENGTH_MS;
                if (mIsPlaying) {
                    startCueTimer();
                }
            }
        }
    }

    @Override
    public void onIsPlayingChanged(boolean isPlaying) {
        // The 10 sec cap runs on playback time: stop it on pause/buffering
        mIsPlaying = isPlaying;
        if (!mHasCues) {
            return;
        }
        if (isPlaying) {
            startCueTimer();
        } else {
            mHandler.removeCallbacks(mClearCues);
            mCueRemainingMs = Math.max(0, mCueRemainingMs - (SystemClock.elapsedRealtime() - mCueTimerStartMs));
        }
    }

    private void startCueTimer() {
        mHandler.removeCallbacks(mClearCues);
        mCueTimerStartMs = SystemClock.elapsedRealtime();
        mHandler.postDelayed(mClearCues, mCueRemainingMs);
    }

    private void clearCues() {
        mHasCues = false;
        if (mSubtitleView != null) {
            mSubtitleView.setCues(new ArrayList<>());
        }
    }

    /**
     * Fork fix (WebvttCueParser): decode the html entities Media3 leaves (e.g. &amp;#39; &amp;quot;).
     */
    private static String decodeEntities(String text) {
        return ENTITY.matcher(text).find() ? Html.fromHtml(text).toString() : text;
    }

    /**
     * Fork fix (SubtitlePainter): padded background instead of the plain one.
     */
    private CharSequence applyPaddedBackground(CharSequence text) {
        if (Color.alpha(mBackgroundColor) == 0) {
            return text;
        }
        Spannable result = new SpannableString(text);
        result.setSpan(new PaddingBackgroundColorSpan(mBackgroundColor), 0, result.length(), Spanned.SPAN_PRIORITY);
        return result;
    }

    public void show(boolean show) {
        if (mSubtitleView != null) {
            mSubtitleView.setVisibility(show ? View.VISIBLE : View.GONE);
        }
    }

    private List<SubtitleStyle> getSubtitleStyles() {
        return mSubtitleStyles;
    }

    private SubtitleStyle getSubtitleStyle() {
        return mPlayerData.getSubtitleStyle();
    }

    private void setSubtitleStyle(SubtitleStyle subtitleStyle) {
        mPlayerData.setSubtitleStyle(subtitleStyle);
        configureSubtitleView();
    }

    private List<Cue> forceCenterAlignment(List<Cue> cues) {
        List<Cue> result = new ArrayList<>();

        for (Cue cue : cues) {
            if (cue.text == null) { // M3: bitmap cues have no text
                continue;
            }
            // Autogenerated subs repeated lines fix
            final String textStr = decodeEntities(cue.text.toString());
            if (Helpers.endsWithAny(textStr, "\n", " ")) { // vtt subs format
                subsBuffer = textStr;
            } else if (textStr.contains("\n")) { // ttml subs format
                //CharSequence text = subsBuffer != null ? textStr.replace(subsBuffer, "").replace("\n", "") : textStr;

                CharSequence text;

                if (subsBuffer != null && textStr.contains(subsBuffer)) {
                    text = textStr.replace(subsBuffer, "").replace("\n", "");
                } else {
                    text = textStr;
                }

                result.add(new Cue.Builder().setText(applyPaddedBackground(text)).build()); // sub centered by default

                String[] split = textStr.split("\n");
                subsBuffer = split.length == 2 ? split[1] : textStr;
            } else {
                CharSequence text = subsBuffer != null ? textStr.replace(subsBuffer, "") : textStr;
                result.add(new Cue.Builder().setText(applyPaddedBackground(text)).build()); // sub centered by default
                subsBuffer = text;
            }
        }

        // Fork fix (SubtitleView): all cues are rendered simultaneously, so remain only the last one
        if (result.size() > 1) {
            result = new ArrayList<>(result.subList(result.size() - 1, result.size()));
        }

        return result;
    }

    private void configureSubtitleView() {
        if (mSubtitleView != null) {
            // Cues are rebuilt as plain text + our padding span: keep spans (Media3 may strip them when false)
            mSubtitleView.setApplyEmbeddedStyles(true);

            SubtitleStyle subtitleStyle = getSubtitleStyle();

            if (subtitleStyle.isSystem()) {
                if (VERSION.SDK_INT >= 19) {
                    applySystemStyle();
                }
            } else {
                applyStyle(subtitleStyle);
            }

            mSubtitleView.setBottomPaddingFraction(mPlayerData.getSubtitlePosition());
        }
    }

    private void applyStyle(SubtitleStyle subtitleStyle) {
        int textColor = ContextCompat.getColor(mContext, subtitleStyle.subsColorResId);
        int outlineColor = ContextCompat.getColor(mContext, R.color.black);
        int backgroundColor = ContextCompat.getColor(mContext, subtitleStyle.backgroundColorResId);

        mBackgroundColor = backgroundColor;

        CaptionStyleCompat style =
                new CaptionStyleCompat(textColor,
                        Color.TRANSPARENT, Color.TRANSPARENT, // background is drawn by PaddingBackgroundColorSpan
                        subtitleStyle.captionStyle,
                        outlineColor, Typeface.DEFAULT_BOLD);
        mSubtitleView.setStyle(style);

        float textSize = getTextSizePx();
        mSubtitleView.setFixedTextSize(TypedValue.COMPLEX_UNIT_PX, textSize);
    }

    @RequiresApi(19)
    private void applySystemStyle() {
        CaptioningManager captioningManager =
                (CaptioningManager) mContext.getSystemService(Context.CAPTIONING_SERVICE);

        if (captioningManager != null) {
            CaptionStyle userStyle = captioningManager.getUserStyle();
            mBackgroundColor = userStyle.backgroundColor;

            CaptionStyleCompat style =
                    new CaptionStyleCompat(userStyle.foregroundColor,
                            Color.TRANSPARENT, VERSION.SDK_INT >= 21 ? userStyle.windowColor : Color.TRANSPARENT,
                            userStyle.edgeType,
                            userStyle.edgeColor, userStyle.getTypeface());
            mSubtitleView.setStyle(style);

            float textSizePx = getTextSizePx();
            mSubtitleView.setFixedTextSize(TypedValue.COMPLEX_UNIT_PX, textSizePx * captioningManager.getFontScale());
        }
    }

    private float getTextSizePx() {
        float textSizePx = mSubtitleView.getContext().getResources().getDimension(R.dimen.subtitle_text_size);
        return textSizePx * mPlayerData.getSubtitleScale();
    }
}
