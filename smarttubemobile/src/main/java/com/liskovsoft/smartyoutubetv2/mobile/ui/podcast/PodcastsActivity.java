package com.liskovsoft.smartyoutubetv2.mobile.ui.podcast;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.LinearLayout;

import com.liskovsoft.smartyoutubetv2.mobile.R;
import com.liskovsoft.smartyoutubetv2.mobile.ui.widgets.MiniPlayerBar;
import com.liskovsoft.smartyoutubetv2.mobile.ui.widgets.MusicNav;

/**
 * Podcasts as a screen of its own (the list itself is {@link PodcastsView}). The music part shows the same view
 * inside BrowseActivity, so switching Musik / Poddar there keeps the bottom bar in place; this activity is only
 * kept for anything that still opens it directly.
 */
public class PodcastsActivity extends Activity {
    private PodcastsView mPodcasts;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(getColor(R.color.yt_bg));
        root.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
        getWindow().setStatusBarColor(0x00000000);

        mPodcasts = new PodcastsView(this, this::finish);
        root.addView(mPodcasts, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        root.addView(new MiniPlayerBar(this, () -> true));
        root.addView(MusicNav.createBar(this, MusicNav.PODCASTS, target -> {
            if (target != MusicNav.PODCASTS) {
                MusicNav.openFeed(this, target);
            }
        }));

        root.setOnApplyWindowInsetsListener((v, insets) -> {
            v.setPadding(0, insets.getSystemWindowInsetTop(), 0, insets.getSystemWindowInsetBottom());
            return insets;
        });
        setContentView(root);

        mPodcasts.onShown();
    }

    @Override
    protected void onDestroy() {
        mPodcasts.release();
        super.onDestroy();
    }

    @Override
    protected void onResume() {
        super.onResume();
        mPodcasts.onResume();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        mPodcasts.onActivityResult(requestCode, resultCode, data);
    }
}
