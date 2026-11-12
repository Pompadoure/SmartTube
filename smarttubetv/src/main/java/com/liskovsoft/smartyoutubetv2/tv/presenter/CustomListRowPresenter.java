package com.liskovsoft.smartyoutubetv2.tv.presenter;

import androidx.leanback.widget.ListRowPresenter;
import com.liskovsoft.smartyoutubetv2.tv.util.ViewUtil;

public class CustomListRowPresenter extends ListRowPresenter {
    public CustomListRowPresenter() {
        super(ViewUtil.FOCUS_ZOOM_FACTOR, ViewUtil.FOCUS_DIMMER_ENABLED);
        setSelectEffectEnabled(ViewUtil.ROW_SELECT_EFFECT_ENABLED);
        // JoTube: no leanback card shadow. The rounded-corner clip gives every card an opaque outline of the WHOLE card
        // (thumbnail and text), and the raised focus Z draws a shadow box from it: invisible on a near black page, dark
        // banded boxes on YouTube's grey (devices preferring static shadows draw a 9-patch box instead; both are off
        // now). The fragments may turn the rounded corners tweak back on: without the shadow that is only a clip.
        setShadowEnabled(false);
        enableChildRoundedCorners(false);
    }
}
