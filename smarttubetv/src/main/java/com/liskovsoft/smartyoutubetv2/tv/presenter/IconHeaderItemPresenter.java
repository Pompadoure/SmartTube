package com.liskovsoft.smartyoutubetv2.tv.presenter;

import android.animation.ArgbEvaluator;
import android.content.Context;
import android.content.res.Resources;
import android.graphics.PorterDuff;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.Drawable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.leanback.widget.HeaderItem;
import androidx.leanback.widget.ListRow;
import androidx.leanback.widget.PageRow;
import androidx.leanback.widget.Presenter;
import androidx.leanback.widget.RowHeaderPresenter;
import com.bumptech.glide.Glide;
import com.bumptech.glide.load.DataSource;
import com.bumptech.glide.load.engine.GlideException;
import com.bumptech.glide.request.RequestListener;
import com.bumptech.glide.request.target.Target;
import com.liskovsoft.sharedutils.mylogger.Log;
import com.liskovsoft.smartyoutubetv2.tv.R;
import com.liskovsoft.smartyoutubetv2.tv.util.ViewUtil;

public class IconHeaderItemPresenter extends RowHeaderPresenter {
    private static final String TAG = IconHeaderItemPresenter.class.getSimpleName();
    private static final ArgbEvaluator ARGB = new ArgbEvaluator();
    // JoTube: YouTube-style sidebar. A pill fades in behind the focused item, icon and text turn dark.
    private int mPillColor;
    private int mPillColorTransparent;
    private int mContentNormalColor;
    private int mContentFocusedColor;
    private final int mResId;
    private final String mIconUrl;
    private Drawable mDefaultIcon;

    public IconHeaderItemPresenter(int resId, String iconUrl) {
        mResId = resId;
        mIconUrl = iconUrl;
    }

    @Override
    public ViewHolder onCreateViewHolder(ViewGroup viewGroup) {
        Context context = viewGroup.getContext();
        Resources res = context.getResources();
        mPillColor = ContextCompat.getColor(context, R.color.sidebar_item_focused);
        mPillColorTransparent = mPillColor & 0x00FFFFFF;
        mContentNormalColor = ContextCompat.getColor(context, R.color.sidebar_content_normal);
        mContentFocusedColor = ContextCompat.getColor(context, R.color.sidebar_content_focused);
        LayoutInflater inflater = (LayoutInflater) context
                .getSystemService(Context.LAYOUT_INFLATER_SERVICE);
        mDefaultIcon = new ColorDrawable(ContextCompat.getColor(context, R.color.lb_grey));

        View view = inflater.inflate(R.layout.icon_header_item, viewGroup, false);

        GradientDrawable pill = new GradientDrawable();
        pill.setShape(GradientDrawable.RECTANGLE);
        pill.setCornerRadius(res.getDimensionPixelSize(R.dimen.sidebar_item_height) / 2f);
        pill.setColor(mPillColorTransparent);
        view.setBackground(pill);

        ViewHolder holder = new ViewHolder(view);
        applyLevel(view, 0);

        return holder;
    }

    @Override
    public void onBindViewHolder(Presenter.ViewHolder viewHolder, Object item) {
        HeaderItem headerItem;

        if (item instanceof PageRow) {
            headerItem = ((PageRow) item).getHeaderItem();
        } else {
            headerItem = ((ListRow) item).getHeaderItem();
        }

        View rootView = viewHolder.view;
        rootView.setFocusable(true);

        ImageView iconView = rootView.findViewById(R.id.header_icon);
        if (iconView != null) {
            if (mIconUrl != null) {
                iconView.setTag(R.id.header_icon, null); // channel avatar: keep its colors
                iconView.clearColorFilter();
                Glide.with(rootView.getContext())
                        .load(mIconUrl)
                        .apply(ViewUtil.glideOptions().error(mDefaultIcon))
                        .listener(mErrorListener)
                        .into(iconView);

                //ViewUtil.makeMonochrome(iconView);
            } else {
                Drawable icon = mResId > 0 ? ContextCompat.getDrawable(rootView.getContext(), mResId) : mDefaultIcon;
                iconView.setImageDrawable(icon);
                iconView.setTag(R.id.header_icon, Boolean.TRUE); // monochrome icon: tinted with the text
                applyLevel(rootView, ((RowHeaderPresenter.ViewHolder) viewHolder).getSelectLevel());
            }
        }

        TextView label = rootView.findViewById(R.id.header_label);
        if (label != null) {
            label.setText(headerItem.getName());
        }
    }

    @Override
    public void onUnbindViewHolder(Presenter.ViewHolder viewHolder) {
        // NOP
    }

    /**
     * JoTube: leanback animates the select level (0..1) of the focused header. It drives the pill and the colors,
     * so the focus glides from item to item like in the YouTube app.
     */
    @Override
    protected void onSelectLevelChanged(RowHeaderPresenter.ViewHolder holder) {
        applyLevel(holder.view, holder.getSelectLevel());
    }

    private void applyLevel(View view, float level) {
        view.setAlpha(1f);

        if (view.getBackground() instanceof GradientDrawable) {
            ((GradientDrawable) view.getBackground()).setColor((int) ARGB.evaluate(level, mPillColorTransparent, mPillColor));
        }

        int contentColor = (int) ARGB.evaluate(level, mContentNormalColor, mContentFocusedColor);

        TextView label = view.findViewById(R.id.header_label);
        if (label != null) {
            label.setTextColor(contentColor);
        }

        ImageView icon = view.findViewById(R.id.header_icon);
        if (icon != null && icon.getTag(R.id.header_icon) != null) {
            icon.setColorFilter(contentColor, PorterDuff.Mode.SRC_IN);
        }
    }

    private final RequestListener<Drawable> mErrorListener = new RequestListener<Drawable>() {
        @Override
        public boolean onLoadFailed(@Nullable GlideException e, Object model, Target<Drawable> target, boolean isFirstResource) {
            Log.e(TAG, "Glide load failed: " + e);
            return false;
        }

        @Override
        public boolean onResourceReady(Drawable resource, Object model, Target<Drawable> target, DataSource dataSource, boolean isFirstResource) {
            return false;
        }
    };
}
