package com.liskovsoft.smartyoutubetv2.tv.ui.search.tags;

import android.os.Bundle;
import android.view.KeyEvent;
import com.liskovsoft.smartyoutubetv2.tv.R;
import com.liskovsoft.smartyoutubetv2.tv.ui.common.LeanbackActivity;

public class SearchTagsActivity extends LeanbackActivity {
    private SearchTagsFragment mFragment;
    private boolean mDownPressed;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.fragment_search_tags);
        mFragment = (SearchTagsFragment) getSupportFragmentManager()
                .findFragmentById(R.id.search_tags_fragment);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        // If there are no results found, press the left key to reselect the microphone
        // JoTube: not while moving on the built-in keyboard (onKeyDown runs before the focus moves, so every LEFT
        // there jumped back to the first key while the results were empty or still loading)
        if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT && !mFragment.hasResults() && !mFragment.isKeyboardFocused()) {
            mFragment.focusOnSearchField();
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public void onBackPressed() {
        // JoTube: BACK on the keyboard after a search: the keyboard collapses and the results get the focus
        if (mFragment != null && mFragment.collapseKeyboardToResults()) {
            return;
        }
        super.onBackPressed();
    }

    @Override
    public void finishReally() {
        super.finishReally();

        mFragment.onFinish();
    }
}
