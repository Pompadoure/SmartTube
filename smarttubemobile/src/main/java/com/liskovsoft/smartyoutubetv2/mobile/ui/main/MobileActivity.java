package com.liskovsoft.smartyoutubetv2.mobile.ui.main;

import com.liskovsoft.smartyoutubetv2.common.misc.MotherActivity;

/**
 * Base of the phone activities: the TV build's "pretend the screen is 1920 px wide" scaling and its leanback
 * colour themes are off, the phone uses real dp and the theme from the manifest.
 */
public class MobileActivity extends MotherActivity {
    @Override
    protected boolean isDpiScalingEnabled() {
        return false;
    }

    @Override
    protected void initTheme() {
        // the theme comes from the manifest
    }
}
