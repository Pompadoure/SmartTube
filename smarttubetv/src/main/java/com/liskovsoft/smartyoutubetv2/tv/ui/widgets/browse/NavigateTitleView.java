package com.liskovsoft.smartyoutubetv2.tv.ui.widgets.browse;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.leanback.widget.SearchOrbView;
import androidx.leanback.widget.SearchOrbView.Colors;
import androidx.leanback.widget.TitleView;
import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.request.target.SimpleTarget;
import com.bumptech.glide.request.transition.Transition;
import com.liskovsoft.mediaserviceinterfaces.oauth.Account;
import com.liskovsoft.sharedutils.locale.LocaleUtility;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.PlaybackPresenter;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.dialogs.AccountSelectionPresenter;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.settings.AccountSettingsPresenter;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.settings.LanguageSettingsPresenter;
import com.liskovsoft.smartyoutubetv2.common.app.views.PlaybackView;
import com.liskovsoft.smartyoutubetv2.common.app.views.ViewManager;
import com.liskovsoft.smartyoutubetv2.common.misc.MediaServiceManager;
import com.liskovsoft.smartyoutubetv2.common.misc.MediaServiceManager.AccountChangeListener;
import com.liskovsoft.smartyoutubetv2.common.prefs.common.DataChangeBase.OnDataChange;
import com.liskovsoft.smartyoutubetv2.common.prefs.GeneralData;
import com.liskovsoft.smartyoutubetv2.common.prefs.MainUIData;
import com.liskovsoft.smartyoutubetv2.common.utils.Utils;
import com.liskovsoft.smartyoutubetv2.tv.R;
import com.liskovsoft.smartyoutubetv2.tv.ui.mod.leanback.playerglue.tooltips.TooltipCompatHandler;
import com.liskovsoft.smartyoutubetv2.tv.ui.widgets.search.LongClickSearchOrbView;
import com.liskovsoft.smartyoutubetv2.tv.ui.widgets.time.DateTimeView;
import com.liskovsoft.smartyoutubetv2.tv.util.ViewUtil;

import java.util.Locale;

import static androidx.leanback.widget.TitleViewAdapter.BRANDING_VIEW_VISIBLE;
import static androidx.leanback.widget.TitleViewAdapter.FULL_VIEW_VISIBLE;
import static androidx.leanback.widget.TitleViewAdapter.SEARCH_VIEW_VISIBLE;

/**
 * View that supports dpad navigation between children<br/>
 * NOTE: You should set android:nextFocusLeft and android:nextFocusRight<br/>
 * https://stackoverflow.com/questions/38169378/use-multiple-orb-buttons-or-other-buttons-in-the-leanbacks-title-view<br/>
 * https://stackoverflow.com/questions/40802470/add-button-to-browsefragment
 */
public class NavigateTitleView extends TitleView implements OnDataChange, AccountChangeListener {
    private LongClickSearchOrbView mAccountView;
    private SearchOrbView mLanguageView;
    private SearchOrbView mExitPip;
    private TextView mPipTitle;
    private int mSearchVisibility = View.INVISIBLE;
    private int mBrandingVisibility = View.INVISIBLE;
    private DateTimeView mGlobalClock;
    private DateTimeView mGlobalDate;
    private SearchOrbView mSearchOrbView;
    private boolean mInitDone;
    private int mFlags = FULL_VIEW_VISIBLE;
    private int mIconWidth;
    private int mIconHeight;
    private boolean mIsSearchOrbEnabled;
    private boolean mIsAccountViewEnabled;
    private boolean mIsLanguageViewEnabled;
    private boolean mIsGlobalClockEnabled;
    private boolean mSidebarRailMode; // JoTube: search and account sit in the sidebar rail, stacked vertically

    public NavigateTitleView(Context context) {
        super(context);
    }

    public NavigateTitleView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public NavigateTitleView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);

        // findViewById is null in constructor. Init mAccountView later.
    }

    @Override
    public View focusSearch(View focused, int direction) {
        if (mSidebarRailMode) {
            return focusSearchRail(focused, direction);
        }

        View nextFoundFocusableViewInLayout = null;

        // Only concerned about focusing left and right at the moment
        if (direction == View.FOCUS_LEFT || direction == View.FOCUS_RIGHT) {

            // Try to find the next focusable item in this layout for the supplied direction
            int nextFoundFocusableViewInLayoutId = -1;
            switch(direction) {
                case View.FOCUS_LEFT :
                    nextFoundFocusableViewInLayoutId = focused.getNextFocusLeftId();
                    break;
                case View.FOCUS_RIGHT :
                    nextFoundFocusableViewInLayoutId = focused.getNextFocusRightId();
                    break;
            }

            // View id for next focus direction found....get the View
            if (nextFoundFocusableViewInLayoutId != -1) {
                nextFoundFocusableViewInLayout = findViewById(nextFoundFocusableViewInLayoutId);
            }
        }

        //  Return the found View in the layout if it's focusable
        if (nextFoundFocusableViewInLayout != null && nextFoundFocusableViewInLayout != focused && nextFoundFocusableViewInLayout.isFocusable()) {
            if (nextFoundFocusableViewInLayout.getVisibility() == View.VISIBLE) {
                return nextFoundFocusableViewInLayout;
            } else {
                return focusSearch(nextFoundFocusableViewInLayout, direction);
            }
        } else {
            // No focusable view found in layout...propagate to super (should invoke the BrowseFrameLayout.OnFocusSearchListener
            return super.focusSearch(focused, direction);
        }
    }

    /**
     * JoTube: the buttons are a vertical column in the sidebar rail. Up/down moves between them (down from the last one
     * leaves the title like before), left stays, right leaves the title (to the content, like before).
     */
    private View focusSearchRail(View focused, int direction) {
        if (direction == View.FOCUS_UP || direction == View.FOCUS_DOWN) {
            ViewGroup column = getRailColumn();
            View current = findDirectChild(column, focused);

            if (column != null && current != null) {
                int step = direction == View.FOCUS_DOWN ? 1 : -1;

                for (int i = column.indexOfChild(current) + step; i >= 0 && i < column.getChildCount(); i += step) {
                    View candidate = column.getChildAt(i);

                    if (candidate.getVisibility() == View.VISIBLE && candidate.isFocusable()) {
                        return candidate;
                    }
                }
            }

            if (direction == View.FOCUS_UP) {
                return focused; // the top of the rail
            }
        } else if (direction == View.FOCUS_LEFT) {
            return focused;
        }

        return super.focusSearch(focused, direction);
    }

    private ViewGroup getRailColumn() {
        View searchOrb = findViewById(R.id.title_orb);
        return searchOrb != null && searchOrb.getParent() instanceof ViewGroup ? (ViewGroup) searchOrb.getParent() : null;
    }

    private static View findDirectChild(ViewGroup parent, View view) {
        View current = view;

        while (current != null && parent != null) {
            if (current.getParent() == parent) {
                return current;
            }

            current = current.getParent() instanceof View ? (View) current.getParent() : null;
        }

        return null;
    }

    /**
     * JoTube: puts the account (on top) and the search button in a vertical column centered in the collapsed sidebar
     * rail, smaller and in the sidebar colors. Called once when the browse view is created (one layout pass, no grids).
     *
     * @param railWidthPx width of the collapsed sidebar rail
     */
    public void setSidebarRailMode(int railWidthPx) {
        if (mSidebarRailMode || railWidthPx <= 0) {
            return;
        }

        ViewGroup column = getRailColumn();
        View account = findViewById(R.id.account_orb);

        if (!(column instanceof LinearLayout) || account == null || account.getParent() != column) {
            return;
        }

        mSidebarRailMode = true;

        int orbSize = getResources().getDimensionPixelSize(R.dimen.sidebar_orb_size);
        int gap = getResources().getDimensionPixelSize(R.dimen.sidebar_orb_gap);

        // Account first, like YouTube
        column.removeView(account);
        column.addView(account, 0);

        LinearLayout linear = (LinearLayout) column;
        linear.setOrientation(LinearLayout.VERTICAL);

        for (int i = 0; i < column.getChildCount(); i++) {
            View child = column.getChildAt(i);
            ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) child.getLayoutParams();
            lp.setMarginStart(0);
            lp.topMargin = i == 0 ? 0 : gap;
            child.setLayoutParams(lp);

            if (child instanceof SearchOrbView) {
                resizeOrb((SearchOrbView) child, orbSize, child == account);
            }
        }

        // Center the column in the rail (the orb view is as wide as its circle)
        int orbViewWidth = orbSize;
        if (column.getLayoutParams() instanceof FrameLayout.LayoutParams) {
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) column.getLayoutParams();
            lp.gravity = android.view.Gravity.TOP | android.view.Gravity.START;
            lp.setMarginStart((railWidthPx - orbViewWidth) / 2 - getPaddingStart());
            column.setLayoutParams(lp);
        }

        // The title grew taller (a column): the branding/clock block stays at the top instead of its vertical center
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (child != column && child.getLayoutParams() instanceof FrameLayout.LayoutParams) {
                FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) child.getLayoutParams();
                lp.gravity = android.view.Gravity.TOP | android.view.Gravity.END;
                child.setLayoutParams(lp);
            }
        }

        // Same look as the sidebar icons: no colored circle, a light one only on focus
        int iconColor = ContextCompat.getColor(getContext(), R.color.sidebar_orb_icon);
        Colors colors = new Colors(Color.TRANSPARENT, ContextCompat.getColor(getContext(), R.color.sidebar_orb_focused), iconColor);
        if (mAccountView != null) {
            mAccountView.setOrbColors(colors);
        } else if (account instanceof SearchOrbView) {
            ((SearchOrbView) account).setOrbColors(colors);
        }

        mIconWidth = 0; // the avatar is loaded for the new size
        mIconHeight = 0;
        updateAccountIcon();
    }

    private void resizeOrb(SearchOrbView orb, int orbSize, boolean isAccount) {
        View circle = orb.findViewById(R.id.search_orb);

        if (circle != null) {
            ViewGroup.LayoutParams lp = circle.getLayoutParams();
            lp.width = orbSize;
            lp.height = orbSize;
            circle.setLayoutParams(lp);
        }

        View icon = orb.findViewById(R.id.icon);

        if (icon instanceof ImageView) {
            int iconSize = getResources().getDimensionPixelSize(isAccount ? R.dimen.sidebar_orb_avatar_size : R.dimen.sidebar_orb_icon_size);
            ViewGroup.LayoutParams lp = icon.getLayoutParams();
            lp.width = iconSize;
            lp.height = iconSize;
            icon.setLayoutParams(lp);
            ((ImageView) icon).setScaleType(ImageView.ScaleType.FIT_CENTER);
        }
    }

    @Override
    protected boolean onRequestFocusInDescendants(int direction, Rect previouslyFocusedRect) {
        // Gives focus to the SearchOrb first....if not...default to normal descendant focus search
        return getSearchAffordanceView().requestFocus() || super.onRequestFocusInDescendants(direction, previouslyFocusedRect);
    }

    @Override
    public void updateComponentsVisibility(int flags) {
        // Fix for: Fatal Exception: java.lang.IllegalStateException
        // Fragment has not been attached yet.
        // Inside: super.updateComponentsVisibility(flags);
        if (getWindowToken() == null) {
            return;
        }

        super.updateComponentsVisibility(flags);

        init();

        mFlags = flags;

        mSearchVisibility = (flags & SEARCH_VIEW_VISIBLE) == SEARCH_VIEW_VISIBLE
                ? View.VISIBLE : View.INVISIBLE;

        mBrandingVisibility = (flags & BRANDING_VIEW_VISIBLE) == BRANDING_VIEW_VISIBLE
                ? View.VISIBLE : View.INVISIBLE;

        if (mIsSearchOrbEnabled) {
            mSearchOrbView.setVisibility(View.GONE);
        }

        if (mIsAccountViewEnabled) {
            mAccountView.setVisibility(mSearchVisibility);
        }

        if (mIsLanguageViewEnabled) {
            mLanguageView.setVisibility(mSearchVisibility);
        }

        if (mExitPip != null && (PlaybackPresenter.instance(getContext()).isRunningInBackground() || mSearchVisibility != View.VISIBLE)) {
            mExitPip.setVisibility(mSearchVisibility);
            mPipTitle.setVisibility(mSearchVisibility);
        }

        if (mIsGlobalClockEnabled) {
            mGlobalClock.setVisibility(mBrandingVisibility);
        }

        if (mIsGlobalClockEnabled) {
            mGlobalDate.setVisibility(mBrandingVisibility);
        }
    }

    private void init() {
        if (mInitDone) {
            return;
        }

        MediaServiceManager.instance().addAccountListener(this);

        setupButtons();

        MainUIData mainUIData = MainUIData.instance(getContext());
        mainUIData.setOnChange(this);

        mInitDone = true;
    }

    private void setupButtons() {
        MainUIData mainUIData = MainUIData.instance(getContext());

        mSearchOrbView = findViewById(R.id.title_orb);

        mAccountView = findViewById(R.id.account_orb);
        // JoTube: the profile picture opens the account settings (switching accounts on click signed Johan out)
        mAccountView.setOnOrbClickedListener(v -> AccountSettingsPresenter.instance(getContext()).show());
        mAccountView.setOnOrbLongClickedListener(v -> {
            AccountSettingsPresenter.instance(getContext()).show();
            return true;
        });
        TooltipCompatHandler.setTooltipText(mAccountView, getContext().getString(R.string.settings_accounts));

        mLanguageView = findViewById(R.id.language_orb);
        mLanguageView.setOnOrbClickedListener(v -> LanguageSettingsPresenter.instance(getContext()).show());
        TooltipCompatHandler.setTooltipText(mLanguageView, getContext().getString(R.string.settings_language_country));

        mExitPip = findViewById(R.id.exit_pip);
        mPipTitle = findViewById(R.id.pip_title);
        mExitPip.setOnOrbClickedListener(v -> ViewManager.instance(getContext()).startView(PlaybackView.class));
        ViewUtil.enableMarquee(mPipTitle);
        ViewUtil.setTextScrollSpeed(mPipTitle, mainUIData.getCardTextScrollSpeed());
        TooltipCompatHandler.setTooltipText(mExitPip, getContext().getString(R.string.return_to_background_video));

        mGlobalClock = findViewById(R.id.global_time);
        mGlobalClock.showDate(false);

        mGlobalDate = findViewById(R.id.global_date);
        mGlobalDate.showTime(false);
        mGlobalDate.showDate(true);

        updateButtonsVisibility();
    }

    private void updateButtonsVisibility() {
        MainUIData mainUIData = MainUIData.instance(getContext());

        mIsSearchOrbEnabled = !mainUIData.isTopButtonEnabled(MainUIData.TOP_BUTTON_SEARCH);
        mIsAccountViewEnabled = mainUIData.isTopButtonEnabled(MainUIData.TOP_BUTTON_BROWSE_ACCOUNTS);
        mIsLanguageViewEnabled = mainUIData.isTopButtonEnabled(MainUIData.TOP_BUTTON_CHANGE_LANGUAGE);
        mIsGlobalClockEnabled = GeneralData.instance(getContext()).isGlobalClockEnabled();

        mSearchOrbView.setVisibility(mIsSearchOrbEnabled ? View.VISIBLE : View.GONE);
        mAccountView.setVisibility(mIsAccountViewEnabled ? View.VISIBLE : View.GONE);
        mLanguageView.setVisibility(mIsLanguageViewEnabled ? View.VISIBLE : View.GONE);
        mGlobalClock.setVisibility(mIsGlobalClockEnabled ? View.VISIBLE : View.GONE);
        mGlobalDate.setVisibility(mIsGlobalClockEnabled ? View.VISIBLE : View.GONE);

        Utils.postDelayed(this::updateAccountIcon, 1_000); // give a time to engine to fetch an updated icon url
        //updateAccountIcon();
        updateLanguageIcon();
    }

    @Override
    protected void onVisibilityChanged(@NonNull View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);

        if (visibility == View.VISIBLE) { // scroll grid up, scroll grid down
            applyPipParameters();
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasWindowFocus) {
        super.onWindowFocusChanged(hasWindowFocus);

        if (hasWindowFocus) { // pip window closed, dialog closed
            applyPipParameters();
        }
    }

    @Override
    public void onAccountChanged(Account account) {
        updateAccountIcon();
    }

    private void applyPipParameters() {
        if (mExitPip != null) {
            int newVisibility = PlaybackPresenter.instance(getContext()).isRunningInBackground() ? mSearchVisibility : View.INVISIBLE;
            mExitPip.setVisibility(newVisibility);
            mPipTitle.setVisibility(newVisibility);

            if (newVisibility == View.VISIBLE) {
                Video video = PlaybackPresenter.instance(getContext()).getVideo();
                mPipTitle.setText(video != null ? String.format("%s - %s", video.getTitle(), video.getAuthor()) : "");
            }
        }
    }

    private void updateAccountIcon() {
        if (!mIsAccountViewEnabled) {
            return;
        }

        Account current = MediaServiceManager.instance().getSelectedAccount();

        if (current != null && current.getAvatarImageUrl() != null) {
            loadIcon(mAccountView, current.getAvatarImageUrl(), false);
            String accountName = current.getName() != null ? current.getName() : current.getEmail();
            //TooltipCompatHandler.setTooltipText(mAccountView, Utils.updateTooltip(getContext(), accountName));
            TooltipCompatHandler.setTooltipText(mAccountView, accountName);
        } else {
            Colors orbColors = mAccountView.getOrbColors();
            mAccountView.setOrbColors(new Colors(orbColors.color, orbColors.brightColor, ContextCompat.getColor(getContext(), R.color.orb_icon_color)));
            mAccountView.setOrbIcon(ContextCompat.getDrawable(getContext(), R.drawable.browse_title_account));
            TooltipCompatHandler.setTooltipText(mAccountView, getContext().getString(R.string.dialog_account_none));
        }
    }

    private void updateLanguageIcon() {
        if (!mIsLanguageViewEnabled) {
            return;
        }

        // Use delay to fix icon initialization on app boot
        Utils.postDelayed(() -> {
            Locale locale = LocaleUtility.getCurrentLocale(getContext());
            loadIcon(mLanguageView, Utils.getCountryFlagUrl(locale.getCountry()), true); // flag server could be down
            TooltipCompatHandler.setTooltipText(mLanguageView, String.format("%s (%s)", locale.getDisplayCountry(), locale.getDisplayLanguage()));
        }, 100);
    }

    private void loadIcon(SearchOrbView view, String url, boolean useCache) {
        if (view == null) {
            return;
        }

        // The view with GONE visibility has zero width and height
        if (view.getWidth() <= 0 || view.getHeight() <= 0) {
            Utils.postDelayed(() -> loadIcon(view, url, useCache), 500);
            return;
        }

        Context context = view.getContext();

        if (context instanceof Activity && !Utils.checkActivity((Activity) context)) {
            return;
        }

        // Size of the view might increase after icon change (bug on some firmwares). So, it's better to cache initial values.
        if (mIconWidth == 0 || mIconHeight == 0) {
            mIconWidth = view.getWidth();
            mIconHeight = view.getHeight();
        }

        try {
            loadIcon(context, view, url, mIconWidth, mIconHeight, useCache);
        } catch (ExceptionInInitializerError e) {
            // Glide Kivi error
            e.printStackTrace();
        }
    }

    private static void loadIcon(Context context, SearchOrbView view, String url, int iconWidth, int iconHeight, boolean useCache) {
        Glide.with(context)
                .load(url)
                .apply(ViewUtil.glideOptions())
                .diskCacheStrategy(useCache ? DiskCacheStrategy.ALL : DiskCacheStrategy.NONE)
                .circleCrop() // resize image
                .into(new SimpleTarget<Drawable>(iconWidth, iconHeight) {
                    @Override
                    public void onResourceReady(@NonNull Drawable resource, @Nullable Transition<? super Drawable> transition) {
                        Colors orbColors = view.getOrbColors();
                        view.setOrbColors(new Colors(orbColors.color, orbColors.brightColor, Color.TRANSPARENT));
                        view.setOrbIcon(resource);
                    }
                });
    }

    @Override
    public void onDataChange() {
        try {
            updateButtonsVisibility();
            updateComponentsVisibility(mFlags);
        } catch (IllegalStateException e) {
            // Fragment BrowseFragment has not been attached yet.
            e.printStackTrace();
        }
    }
}
