package com.liskovsoft.smartyoutubetv2.tv.presenter.vineyard;

import android.os.Build.VERSION;
import android.view.ViewGroup;

import androidx.core.content.ContextCompat;
import com.liskovsoft.sharedutils.helpers.Helpers;
import com.liskovsoft.smartyoutubetv2.common.prefs.MainUIData;
import com.liskovsoft.smartyoutubetv2.tv.R;
import com.liskovsoft.smartyoutubetv2.common.app.models.search.vineyard.Tag;
import com.liskovsoft.smartyoutubetv2.common.app.models.search.vineyard.User;
import com.liskovsoft.smartyoutubetv2.tv.presenter.base.LongClickPresenter;
import com.liskovsoft.smartyoutubetv2.tv.ui.widgets.vineyard.TagCardView;

public class TagPresenter extends LongClickPresenter {
    private static int sDefaultBackgroundColor;
    private static int sDefaultTextColor;
    private static int sSelectedBackgroundColor;
    private static int sSelectedTextColor;

    @Override
    public ViewHolder onCreateViewHolder(ViewGroup parent) {
        sDefaultBackgroundColor =
                ContextCompat.getColor(parent.getContext(), Helpers.getThemeAttr(parent.getContext(), R.attr.cardDefaultBackground));
        sDefaultTextColor =
                ContextCompat.getColor(parent.getContext(), R.color.card_default_text);
        sSelectedBackgroundColor =
                ContextCompat.getColor(parent.getContext(), R.color.card_selected_background_white);
        sSelectedTextColor =
                ContextCompat.getColor(parent.getContext(), R.color.card_selected_text_grey);

        TagCardView cardView = new TagCardView(parent.getContext()) {
            @Override
            public void setSelected(boolean selected) {
                updateCardBackgroundColor(this, selected);
                updateCardTextColor(this, selected);
                super.setSelected(selected);
            }
        };

        cardView.setFocusable(true);
        cardView.setFocusableInTouchMode(true);
        updateCardBackgroundColor(cardView, false);
        updateCardTextColor(cardView, false);
        //if (VERSION.SDK_INT >= 23 && MainUIData.instance(parent.getContext()).isUiTweakEnabled(MainUIData.UI_TWEAK_ROUNDED_CORNERS)) {
        //    cardView.setForeground(ContextCompat.getDrawable(parent.getContext(), R.drawable.lb_card_outline));
        //}
        return new ViewHolder(cardView);
    }

    // JoTube: a pill like the official app: soft grey, white when focused (dark text and icon)
    private static void updateCardBackgroundColor(TagCardView view, boolean selected) {
        android.graphics.drawable.GradientDrawable pill = new android.graphics.drawable.GradientDrawable();
        pill.setCornerRadius(100 * view.getResources().getDisplayMetrics().density);
        pill.setColor(selected ? sSelectedBackgroundColor : 0x40FFFFFF);
        view.setBackground(pill);
    }

    private static void updateCardTextColor(TagCardView view, boolean selected) {
        view.setTextColor(selected ? sSelectedTextColor : sDefaultTextColor);
        android.widget.ImageView icon = view.findViewById(R.id.image_icon);
        if (icon != null) {
            icon.setColorFilter(selected ? sSelectedTextColor : sDefaultTextColor);
        }
    }

    @Override
    public void onBindViewHolder(ViewHolder viewHolder, Object item) {
        super.onBindViewHolder(viewHolder, item);

        if (item instanceof Tag) {
            Tag post = (Tag) item;
            TagCardView cardView = (TagCardView) viewHolder.view;

            if (post.tag != null) {
                cardView.setCardText(post.tag);
                cardView.setCardIcon(R.drawable.lb_ic_in_app_search); // JoTube: like the official suggestions
            }
        } else if (item instanceof User) {
            User post = (User) item;
            TagCardView cardView = (TagCardView) viewHolder.view;

            if (post.username != null) {
                cardView.setCardText(post.username);
                cardView.setCardIcon(R.drawable.ic_user);
            }
        }
    }

    @Override
    public void onUnbindViewHolder(ViewHolder viewHolder) {

    }

}