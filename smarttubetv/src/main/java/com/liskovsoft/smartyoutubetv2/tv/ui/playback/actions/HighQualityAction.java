package com.liskovsoft.smartyoutubetv2.tv.ui.playback.actions;

import static androidx.leanback.widget.PlaybackControlsRow.toBitmapDrawable;
import android.content.Context;
import android.graphics.drawable.BitmapDrawable;
import androidx.leanback.widget.Action;
import com.liskovsoft.smartyoutubetv2.tv.R;

/**
 * An action for displaying a HQ (High Quality) icon.
 */
public class HighQualityAction extends Action {
    public HighQualityAction(Context context) {
        super(R.id.lb_control_high_quality);
        BitmapDrawable uncoloredDrawable = toBitmapDrawable(context, ActionHelpers.getStyledDrawable(context,
                R.styleable.lbPlaybackControlsActionIcons_high_quality));

        setIcon(uncoloredDrawable);
        setLabel1(context.getString(
                R.string.playback_settings));
    }
}
