package com.liskovsoft.smartyoutubetv2.mobile.ui.widgets;

import android.app.Activity;
import android.content.Intent;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.liskovsoft.smartyoutubetv2.mobile.R;
import com.liskovsoft.smartyoutubetv2.mobile.ui.browse.BrowseActivity;

import java.util.function.IntConsumer;

/**
 * The bottom bar of the music part (like YouTube Music): Hem goes back to video, Musik is music, Poddar is podcasts.
 */
public final class MusicNav {
    public static final int HOME = 0;
    public static final int MUSIC = 1;
    public static final int PODCASTS = 2;
    public static final String EXTRA_TARGET = "jotube_target";

    private static final int[] ICONS = {R.drawable.ic_home, R.drawable.ic_music, R.drawable.ic_headphones};
    private static final int[] TITLES = {R.string.tab_home, R.string.tab_music, R.string.tab_podcasts};

    private MusicNav() {
    }

    public static LinearLayout createBar(Activity activity, int selected, IntConsumer onTab) {
        LinearLayout bar = new LinearLayout(activity);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setBackgroundColor(activity.getColor(R.color.yt_bg));

        for (int i = 0; i < ICONS.length; i++) {
            final int target = i;
            TextView tab = new TextView(activity);
            tab.setText(TITLES[i]);
            tab.setTextSize(10);
            tab.setGravity(Gravity.CENTER);
            tab.setCompoundDrawablesWithIntrinsicBounds(0, ICONS[i], 0, 0);
            tab.setCompoundDrawablePadding(Ui.dp(activity, 2));
            tab.setPadding(0, Ui.dp(activity, 8), 0, Ui.dp(activity, 8));
            int color = activity.getColor(i == selected ? R.color.yt_text : R.color.yt_text_secondary);
            tab.setTextColor(color);
            tab.setCompoundDrawableTintList(android.content.res.ColorStateList.valueOf(color));
            tab.setOnClickListener(v -> onTab.accept(target));
            bar.addView(tab, new LinearLayout.LayoutParams(0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        }
        return bar;
    }

    /** Marks another tab as selected, in place (the bar isn't rebuilt). */
    public static void setSelected(LinearLayout bar, int selected) {
        if (bar == null) {
            return;
        }

        for (int i = 0; i < bar.getChildCount(); i++) {
            if (bar.getChildAt(i) instanceof TextView) {
                TextView tab = (TextView) bar.getChildAt(i);
                int color = bar.getContext().getColor(i == selected ? R.color.yt_text : R.color.yt_text_secondary);
                tab.setTextColor(color);
                tab.setCompoundDrawableTintList(android.content.res.ColorStateList.valueOf(color));
            }
        }
    }

    /** From a screen outside the feed: back to the feed on Hem or Musik. */
    public static void openFeed(Activity activity, int target) {
        activity.startActivity(new Intent(activity, BrowseActivity.class)
                .putExtra(EXTRA_TARGET, target)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP));
        activity.finish();
    }
}
