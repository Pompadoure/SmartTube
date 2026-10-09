package com.liskovsoft.smartyoutubetv2.tv.presenter;

import android.content.Context;
import android.util.Pair;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;

import androidx.core.content.ContextCompat;
import androidx.leanback.widget.Presenter;

import com.liskovsoft.sharedutils.helpers.Helpers;
import com.liskovsoft.smartyoutubetv2.common.prefs.MainUIData;
import com.liskovsoft.smartyoutubetv2.tv.R;
import com.liskovsoft.smartyoutubetv2.tv.ui.browse.video.GridFragmentHelper;

/**
 * JoTube: a card with the size of a video thumbnail and a reload arrow. Reloads the section (last row).
 */
public class RefreshCardPresenter extends Presenter {
    /**
     * The only item of the refresh row.
     */
    public static final class RefreshItem {
        @Override
        public String toString() {
            return "";
        }
    }

    @Override
    public ViewHolder onCreateViewHolder(ViewGroup parent) {
        Context context = parent.getContext();
        // The same size as the main image of VideoCardPresenter
        Pair<Integer, Integer> dimens = GridFragmentHelper.getCardDimensPx(
                context, R.dimen.card_width, R.dimen.card_height, MainUIData.instance(context).getVideoGridScale());

        int defaultColor = ContextCompat.getColor(context, Helpers.getThemeAttr(context, R.attr.cardDefaultBackground));
        int selectedColor = ContextCompat.getColor(context, Helpers.getThemeAttr(context, R.attr.cardSelectedBackground));

        FrameLayout card = new FrameLayout(context);
        card.setLayoutParams(new ViewGroup.LayoutParams(dimens.first, dimens.second));
        card.setFocusable(true);
        card.setFocusableInTouchMode(true);
        card.setBackgroundColor(defaultColor);

        // Focused: the same colors as a focused card's info field (light background, dark content)
        int selectedIconColor = ContextCompat.getColor(context, R.color.card_selected_text_grey);
        ImageView icon = new ImageView(context);
        card.setOnFocusChangeListener((v, hasFocus) -> {
            v.setBackgroundColor(hasFocus ? selectedColor : defaultColor);
            if (hasFocus) {
                icon.setColorFilter(selectedIconColor);
            } else {
                icon.clearColorFilter();
            }
        });

        icon.setImageResource(R.drawable.ic_refresh_white);
        icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        int iconSize = Math.round(Math.min(dimens.first, dimens.second) * 0.4f);
        card.addView(icon, new FrameLayout.LayoutParams(iconSize, iconSize, Gravity.CENTER));

        return new ViewHolder(card);
    }

    @Override
    public void onBindViewHolder(ViewHolder viewHolder, Object item) {
        // Static content
    }

    @Override
    public void onUnbindViewHolder(ViewHolder viewHolder) {
        // Nothing to release
    }
}
