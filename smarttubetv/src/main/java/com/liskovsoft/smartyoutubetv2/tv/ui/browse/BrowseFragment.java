package com.liskovsoft.smartyoutubetv2.tv.ui.browse;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentTransaction;
import androidx.leanback.app.BrowseSupportFragment;
import androidx.leanback.app.HeadersSupportFragment;
import androidx.leanback.widget.ArrayObjectAdapter;
import androidx.leanback.widget.HeaderItem;
import androidx.leanback.widget.ListRowPresenter;
import androidx.leanback.widget.PageRow;
import androidx.leanback.widget.Presenter;
import androidx.leanback.widget.PresenterSelector;
import androidx.leanback.widget.SearchOrbView;
import androidx.leanback.widget.TitleHelper;
import androidx.leanback.widget.VerticalGridView;
import androidx.recyclerview.widget.RecyclerView;
import com.bumptech.glide.Glide;
import com.liskovsoft.sharedutils.helpers.Helpers;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.BrowseSection;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.SettingsGroup;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.VideoGroup;
import com.liskovsoft.smartyoutubetv2.common.app.models.errors.ErrorFragmentData;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.ShortsTransitionState;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.BrowsePresenter;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.SearchPresenter;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.SplashPresenter;
import com.liskovsoft.smartyoutubetv2.common.app.views.BrowseView;
import com.liskovsoft.smartyoutubetv2.common.misc.CrashRestorer;
import com.liskovsoft.smartyoutubetv2.common.utils.Utils;
import com.liskovsoft.smartyoutubetv2.tv.R;
import com.liskovsoft.smartyoutubetv2.tv.presenter.IconHeaderItemPresenter;
import com.liskovsoft.smartyoutubetv2.tv.presenter.ShortsCardPresenter;
import com.liskovsoft.smartyoutubetv2.tv.presenter.VideoCardPresenter;
import com.liskovsoft.smartyoutubetv2.tv.ui.browse.dialog.ErrorDialogFragment;
import com.liskovsoft.smartyoutubetv2.tv.ui.mod.leanback.headers.ExtendedHeadersSupportFragment;
import com.liskovsoft.smartyoutubetv2.tv.ui.mod.leanback.misc.ProgressBarManager;
import com.liskovsoft.smartyoutubetv2.tv.ui.playback.ShortsBackground;
import com.liskovsoft.smartyoutubetv2.tv.ui.widgets.browse.NavigateTitleView;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/*
 * Main class to show BrowseFragment with header and rows of videos
 */
public class BrowseFragment extends BrowseSupportFragment implements BrowseView {
    private static final String TAG = BrowseFragment.class.getSimpleName();
    private ArrayObjectAdapter mSectionRowAdapter;
    private BrowsePresenter mBrowsePresenter;
    private Map<Integer, BrowseSection> mSections;
    private BrowseSectionFragmentFactory mSectionFragmentFactory;
    private Handler mHandler;
    private ProgressBarManager mProgressBarManager;
    private boolean mIsFragmentCreated;
    private boolean mFocusOnContent;
    private boolean mContentFocusedByApp; // JoTube: the running headers transition was started by the app
    private CrashRestorer mCrashRestorer;
    private VideoCardPresenter mPreloadCardPresenter; // JoTube: sidebar warm-up, the same presenter classes as the section fragments
    private ShortsCardPresenter mPreloadShortsPresenter;
    private ValueAnimator mRailAnimator; // JoTube: clip of the sidebar between the icon rail and the full menu
    private VerticalGridView mRailLabelsGrid; // JoTube: the headers list whose attached items get the label alpha

    @Override
    public void onCreate(Bundle savedInstanceState) {
        if (getContext() != null) {
            // JoTube: YouTube-style sidebar, only the icons stay while the content has the focus (before the views exist)
            setHeadersRailWidth(getContext().getResources().getDimensionPixelSize(R.dimen.sidebar_rail_width));
        }

        super.onCreate(null);

        if (getContext() == null) {
            return;
        }
        
        mCrashRestorer = new CrashRestorer(getContext(), savedInstanceState);
        mIsFragmentCreated = true;

        mSections = new HashMap<>();
        mHandler = new Handler();
        mBrowsePresenter = BrowsePresenter.instance(getContext());
        mBrowsePresenter.setView(this);
        mProgressBarManager = new ProgressBarManager();

        setupAdapter();
        setupFragmentFactory();
        setupUi();

        enableMainFragmentScaling(false);
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);

        // Called when the activity is paused
        mCrashRestorer.persistHeaderIndex(outState, getSelectedPosition());
        mCrashRestorer.persistVideo(outState, mBrowsePresenter.getCurrentVideo());
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View root = super.onCreateView(inflater, container, savedInstanceState);

        mProgressBarManager.setRootView((ViewGroup) root);

        // JoTube: account and search sit at the top of the sidebar rail
        if (getTitleView() instanceof NavigateTitleView && isHeadersRailActive()) {
            ((NavigateTitleView) getTitleView()).setSidebarRailMode(getHeadersRailWidth());
        }

        return root;
    }

    @Override
    public void onActivityCreated(Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);

        setupEventListeners();

        prepareEntranceTransition();

        mBrowsePresenter.onViewInitialized();

        // Restore state after crash
        mCrashRestorer.restoreHeader((idx, video) -> {
            selectSection(idx, true);
            selectSectionItem(video);
        });
        mCrashRestorer.restorePlayback();
    }

    @Override
    public HeadersSupportFragment onCreateHeadersSupportFragment() {
        return new ExtendedHeadersSupportFragment();
    }

    private void setupEventListeners() {
        getHeadersSupportFragment().setOnHeaderClickedListener(
                (viewHolder, row) -> {
                    long headerId = row.getHeaderItem().getId();
                    int newPosition = indexOf(headerId);

                    if (getHeadersSupportFragment().getSelectedPosition() != newPosition) {
                        // touch screen support
                        getHeadersSupportFragment().setSelectedPosition(newPosition);
                    } else {
                        // update section when clicked or pressed
                        mBrowsePresenter.onSectionClicked((int) headerId);
                        startHeadersTransitionSafe(false);
                    }
                }
        );

        ((ExtendedHeadersSupportFragment) getHeadersSupportFragment()).setOnHeaderLongPressedListener(
                (viewHolder, row) -> {
                    long headerId = row.getHeaderItem().getId();

                    mBrowsePresenter.onSectionLongPressed((int) headerId);
                }
        );

        setOnSearchClickedListener(view -> SearchPresenter.instance(getContext()).startSearch(null));

        // JoTube: entering the Shorts section (OK on the header or right into the content) starts the Shorts player
        setBrowseTransitionListener(new BrowseTransitionListener() {
            @Override
            public void onHeadersTransitionStop(boolean withHeaders) {
                // Only the user's own move (OK on the header, right into the content). Not app start, restore or refresh.
                boolean byApp = mContentFocusedByApp;
                mContentFocusedByApp = false;
                mBrowsePresenter.onContentEntered(!withHeaders && !byApp);
                if (withHeaders) {
                    mBrowsePresenter.onSidebarFocused(); // JoTube: warm up the sections
                }
            }
        });
    }

    private void setupFragmentFactory() {
        mSectionFragmentFactory = new BrowseSectionFragmentFactory(
                (row) -> {
                    boolean inSidebar = isShowingHeaders() && !mFocusOnContent; // JoTube: before the focus moves on
                    focusOnContentIfNeeded();
                    mBrowsePresenter.onSectionFocused(getSelectedHeaderId());
                    if (inSidebar) {
                        mBrowsePresenter.onSidebarFocused();
                    }
                }
        );

        getMainFragmentRegistry().registerFragment(PageRow.class, mSectionFragmentFactory);
    }

    private int indexOf(long headerId) {
        for (int i = 0; i < mSectionRowAdapter.size(); i++) {
            PageRow row = (PageRow) mSectionRowAdapter.get(i);
            HeaderItem header = row.getHeaderItem();
            if (header.getId() == headerId) {
                return i;
            }
        }

        return 0;
    }

    private void setupAdapter() {
        // Map category results from the database to ListRow objects.
        // This Adapter is used to render the MainFragment sidebar labels.
        mSectionRowAdapter = new ArrayObjectAdapter(new ListRowPresenter());
        setAdapter(mSectionRowAdapter);
    }

    private void setupUi() {
        if (getContext() == null) {
            return;
        }

        setHeadersState(HEADERS_ENABLED);
        setHeadersTransitionOnBackEnabled(true);


        updateBadge();

        // This title replaces badge in case one is null
        //setTitle(getString(R.string.browse_title));

        // Set fastLane (or headers) background color
        // JoTube: YouTube-style dark sidebar, whatever color scheme is chosen
        setBrandColor(ContextCompat.getColor(getContext(), R.color.sidebar_background));

        // JoTube: the search button looks like the sidebar icons (no red circle, a light one on focus)
        setSearchAffordanceColors(new SearchOrbView.Colors(Color.TRANSPARENT,
                ContextCompat.getColor(getContext(), R.color.sidebar_orb_focused),
                ContextCompat.getColor(getContext(), R.color.sidebar_orb_icon)));

        setHeaderPresenterSelector(new PresenterSelector() {
            private final Map<Integer, Presenter> mPresenterMap = new HashMap<>();

            @Override
            public Presenter getPresenter(Object o) {
                Presenter presenter = mPresenterMap.get(o.hashCode());

                if (presenter == null) {
                    presenter = new IconHeaderItemPresenter(getHeaderResId(o), getIconUrl(o));
                    mPresenterMap.put(o.hashCode(), presenter);
                }

                return presenter;
            }

            private int getHeaderResId(Object o) {
                if (o instanceof PageRow) {
                    return ((SectionHeaderItem) ((PageRow) o).getHeaderItem()).getResId();
                }

                return -1;
            }

            private String getIconUrl(Object o) {
                if (o instanceof PageRow) {
                    return ((SectionHeaderItem) ((PageRow) o).getHeaderItem()).getIconUrl();
                }

                return null;
            }
        });
    }

    private int getSelectedHeaderId() {
        if (getSelectedPosition() >= mSectionRowAdapter.size()) {
            return -1;
        }

        return (int) ((PageRow) mSectionRowAdapter.get(getSelectedPosition())).getHeaderItem().getId();
    }
    
    public void updateErrorIfEmpty(ErrorFragmentData data) {
        mHandler.postDelayed(() -> showErrorIfEmpty(data), 500); // need delay because header may be not updated
    }

    @Override
    public void showError(ErrorFragmentData data) {
        replaceMainFragment(new ErrorDialogFragment(data));
    }

    private void showErrorIfEmpty(ErrorFragmentData data) {
        if (isEmpty()) {
            replaceMainFragment(new ErrorDialogFragment(data));
        }
    }

    private void replaceMainFragment(Fragment fragment) {
        //Object mainFragment = Helpers.getField(this,"mMainFragment");
        Fragment mainFragment = getMainFragment();

        if (mainFragment != null && fragment != null && mainFragment != fragment) {
            Helpers.setField(this, "mMainFragment", fragment);

            FragmentTransaction ft = getChildFragmentManager().beginTransaction();
            ft.replace(R.id.scale_frame, fragment);
            //mFocusOnContent = !isShowingHeaders(); // Fix focus lost when error fragment shown and sidebar is hidden
            mFocusOnContent = hasFocus(); // Maintain focus
            ft.runOnCommit(this::focusOnContentIfNeeded);
            ft.commitAllowingStateLoss(); // FIX: "Can not perform this action after onSaveInstanceState"
        }
    }

    @Override
    public void addSection(int index, BrowseSection section) {
        if (section == null) {
            return;
        }

        if (mSections.get(section.getId()) != null && (index == -1 || indexOf(section.getId()) == index)) {
            return;
        }

        removeSection(section);

        mSections.put(section.getId(), section);
        createHeader(index, section);
    }

    @Override
    public void removeSection(BrowseSection section) {
        if (section == null) {
            return;
        }

        mSections.remove(section.getId());
        removeHeader(section);
    }

    @Override
    public void removeAllSections() {
        mSections.clear();
        mSectionRowAdapter.clear();
    }

    @Override
    public void updateSection(VideoGroup group) {
        restoreMainFragment();

        mSectionFragmentFactory.updateCurrentFragment(group);

        fixInvisibleSearchOrb();
    }

    @Override
    public void updateSection(SettingsGroup group) {
        restoreMainFragment();

        mSectionFragmentFactory.updateCurrentFragment(group);
    }

    @Override
    public void selectSection(int index, boolean focusOnContent) {
        if (index >= 0 && mSectionRowAdapter.size() > 0) {
            mFocusOnContent = focusOnContent; // focus after header transition

            // Fix refresh current section
            if (getSelectedPosition() == index) {
                // update section manually
                // headers transition event not fired on the same index
                focusOnContentIfNeeded();
                mBrowsePresenter.onSectionFocused(getSelectedHeaderId());
            }

            // Need select again if current header is removed previously (can't check for it right now)
            // Fallback to the last section if index above size
            setSelectedPosition(index < mSectionRowAdapter.size() ? index : mSectionRowAdapter.size() - 1, false);
        }
    }

    @Override
    public void focusOnContent() {
        boolean starts = isShowingHeaders() && !isInHeadersTransition();
        if (starts) {
            mContentFocusedByApp = true; // a transition starts now, see onHeadersTransitionStop
        }
        startHeadersTransitionSafe(false);
        if (starts && isShowingHeaders()) {
            mContentFocusedByApp = false; // didn't start after all
        }
        if (getMainFragment() != null && getMainFragment().getView() != null) {
            getMainFragment().getView().requestFocus();
        }
    }

    @Override
    public void setContentHidden(boolean hidden) {
        Fragment mainFragment = getMainFragment();

        if (mainFragment != null && mainFragment.getView() != null) {
            mainFragment.getView().setAlpha(hidden ? 0f : 1f);
        }
    }

    @Override
    public void preloadCardThumbnails(List<Video> videos) {
        Context context = getContext();

        if (context == null || videos == null) {
            return;
        }

        if (mPreloadCardPresenter == null) {
            mPreloadCardPresenter = new VideoCardPresenter();
            mPreloadShortsPresenter = new ShortsCardPresenter();
        }

        for (Video video : videos) {
            if (video != null && video.hasVideo()) {
                (video.isShorts ? mPreloadShortsPresenter : mPreloadCardPresenter).preload(context, video);
            }
        }
    }

    @Override
    public void preloadShorts(List<Video> videos) {
        Context context = getContext();

        if (context == null || videos == null) {
            return;
        }

        List<String> ids = new ArrayList<>();

        for (Video video : videos) {
            if (video == null || video.videoId == null) {
                continue;
            }

            ids.add(video.videoId);

            try {
                // The same request as the player's (VideoLoaderController): the vertical thumbnail
                Glide.with(context.getApplicationContext()).load(ShortsTransitionState.getThumbnailUrl(video.videoId)).preload();
            } catch (RuntimeException e) {
                // optional
            }
        }

        ShortsBackground.preload(context, ids);
    }

    @Override
    public void showHeaders() {
        // After the fragment has restored its own focus
        new Handler(Looper.getMainLooper()).post(() -> {
            if (isAdded() && !isShowingHeaders()) {
                startHeadersTransitionSafe(true);
            }
        });
    }

    /**
     * Usually called after header transition or fragment transaction
     */
    private void focusOnContentIfNeeded() {
        if (mFocusOnContent) {
            focusOnContent();
            mFocusOnContent = false;
        }
    }

    private boolean hasFocus() {
        if (getMainFragment() == null || getMainFragment().getView() == null) {
            return false;
        }

        return getMainFragment().getView().hasFocus();
    }

    @Override
    public void selectSectionItem(int index) {
        if (index >= 0) {
            mSectionFragmentFactory.setCurrentFragmentItemIndex(index);
        }
    }

    @Override
    public void selectSectionItem(Video item) {
        if (item != null) {
            mSectionFragmentFactory.selectCurrentFragmentItem(item);
        }
    }

    /**
     * Fix: IllegalStateException: "Can not perform this action after onSaveInstanceState"
     */
    private void startHeadersTransitionSafe(boolean withHeaders) {
        // Fix: IllegalStateException: "Can not perform this action after onSaveInstanceState"
        if (!Utils.checkActivity(getActivity())) {
            return;
        }

        try {
            startHeadersTransition(withHeaders);
        } catch (IllegalStateException e) {
            // NOP
        }
    }

    /**
     * Restore after the error fragment
     */
    private void restoreMainFragment() {
        Fragment currentFragment = mSectionFragmentFactory.getCurrentFragment();

        if (currentFragment != null) {
            replaceMainFragment(currentFragment);
        }
    }

    private void createHeader(int index, BrowseSection header) {
        HeaderItem headerItem = new SectionHeaderItem(header);

        PageRow pageRow = new PageRow(headerItem);
        if (index == -1 || mSectionRowAdapter.size() < index) {
            mSectionRowAdapter.add(pageRow); // add to the end
        } else {
            mSectionRowAdapter.add(index, pageRow);
        }
    }

    private void removeHeader(BrowseSection header) {
        Object foundHeader = null;

        for (Object item : mSectionRowAdapter.unmodifiableList()) {
            if (((PageRow) item).getHeaderItem().getId() == header.getId()) {
                foundHeader = item;
                break;
            }
        }

        if (foundHeader != null) {
            mSectionRowAdapter.remove(foundHeader);
        }
    }

    @Override
    public void clearSection(BrowseSection section) {
        mSectionFragmentFactory.clearCurrentFragment();
    }

    /**
     * JoTube: the sidebar collapses to its icon rail or expands to the full menu. Draw-only on purpose: the headers
     * list never changes its layout (no width, visibility or column changes at runtime, those crashed leanback grids).
     * The labels fade and the panel is clipped; leanback moves the content with its own transition.
     */
    @Override
    protected void onHeadersRailStateChanged(boolean expanded, boolean animate) {
        IconHeaderItemPresenter.setLabelsShown(expanded);

        View headers = getHeadersSupportFragment() != null ? getHeadersSupportFragment().getView() : null;

        if (headers == null) {
            return;
        }

        if (mRailAnimator != null) {
            mRailAnimator.cancel();
            mRailAnimator = null;
        }

        int duration = animate ? headers.getResources().getInteger(R.integer.lb_browse_headers_transition_duration) : 0;

        // Labels
        VerticalGridView grid = getHeadersSupportFragment().getVerticalGridView();
        if (grid != null && grid != mRailLabelsGrid) {
            // Items from the RecyclerView cache come back without a new bind: give them the current label alpha
            mRailLabelsGrid = grid;
            grid.addOnChildAttachStateChangeListener(new RecyclerView.OnChildAttachStateChangeListener() {
                @Override
                public void onChildViewAttachedToWindow(@NonNull View view) {
                    View label = view.findViewById(R.id.header_label);
                    if (label != null) {
                        label.animate().cancel();
                        label.setAlpha(IconHeaderItemPresenter.isLabelsShown() ? 1f : 0f);
                    }
                }

                @Override
                public void onChildViewDetachedFromWindow(@NonNull View view) {
                    // NOP
                }
            });
        }
        if (grid != null) {
            for (int i = 0; i < grid.getChildCount(); i++) {
                View label = grid.getChildAt(i).findViewById(R.id.header_label);
                if (label != null) {
                    label.animate().cancel();
                    if (duration > 0) {
                        label.animate().alpha(expanded ? 1f : 0f).setDuration(expanded ? duration : duration / 2).start();
                    } else {
                        label.setAlpha(expanded ? 1f : 0f);
                    }
                }
            }
        }

        // Clip
        int rail = getHeadersRailWidth();
        int full = headers.getWidth() > 0 ? headers.getWidth() :
                headers.getResources().getDimensionPixelSize(R.dimen.lb_browse_headers_width);
        Rect current = headers.getClipBounds();
        int from = current != null ? current.right : full;
        int to = expanded ? full : rail;

        if (duration <= 0 || from == to) {
            setRailClip(headers, expanded ? -1 : rail);
            return;
        }

        ValueAnimator animator = ValueAnimator.ofInt(from, to);
        animator.setDuration(duration);
        animator.addUpdateListener(a -> setRailClip(headers, (int) a.getAnimatedValue()));
        animator.addListener(new AnimatorListenerAdapter() {
            private boolean mCanceled;

            @Override
            public void onAnimationCancel(Animator animation) {
                mCanceled = true;
            }

            @Override
            public void onAnimationEnd(Animator animation) {
                if (!mCanceled) {
                    setRailClip(headers, expanded ? -1 : rail);
                }
            }
        });
        mRailAnimator = animator;
        animator.start();
    }

    /**
     * @param right the visible width, -1 = no clip
     */
    private static void setRailClip(View headers, int right) {
        if (right < 0) {
            headers.setClipBounds(null);
        } else {
            headers.setClipBounds(new Rect(0, 0, right, Math.max(headers.getHeight(), 100_000)));
        }
    }

    @Override
    public void onDestroyView() {
        if (mRailAnimator != null) {
            mRailAnimator.cancel();
            mRailAnimator = null;
        }
        mRailLabelsGrid = null;

        mSectionFragmentFactory.cleanup();

        super.onDestroyView();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        mBrowsePresenter.onViewDestroyed();
    }

    @Override
    public void onPause() {
        super.onPause();

        if (!mIsFragmentCreated) {
            mBrowsePresenter.onViewPaused();
        }
    }

    @Override
    public void onResume() {
        super.onResume();

        if (!mIsFragmentCreated) {
            mBrowsePresenter.onViewResumed();
            if (isShowingHeaders()) {
                mBrowsePresenter.onSidebarFocused(); // JoTube: back from the player into the sidebar
            }
        }

        mIsFragmentCreated = false;
    }

    /**
     * Fix suddenly invisible search orb<br/>
     * Could happen on topmost category when the page partially scrolled<br/>
     * More info: {@link TitleHelper}
     */
    private void fixInvisibleSearchOrb() {
        if (isShowingTitle() && getTitleView() != null && getTitleView().getVisibility() != View.VISIBLE) {
            getTitleView().setVisibility(View.VISIBLE);
        }
    }

    @Override
    public void showProgressBar(boolean show) {
        Runnable callback;

        if (show) {
            callback = mProgressBarManager::show;
        } else {
            callback = mProgressBarManager::hide;
        }

        // Essential. Need to run on the main thread.
        new Handler(Looper.getMainLooper()).post(callback);
    }

    @Override
    public boolean isProgressBarShowing() {
        return mProgressBarManager.isShowing();
    }

    @Override
    public boolean isEmpty() {
        return mSectionFragmentFactory == null || mSectionFragmentFactory.isEmpty();
    }

    @Override
    public void updateBadge() {
        if (getContext() == null) {
            return;
        }

        SplashPresenter splashPresenter = SplashPresenter.instance(getContext());

        if (splashPresenter == null) {
            return;
        }

        int appLogoRes = Helpers.getThemeAttr(getContext(), R.attr.appLogo);

        Drawable bridgeIcon = Utils.getDrawable(getContext(), splashPresenter.getBridgePackageName(), "app_icon");

        // Top right corner logo
        setBadgeDrawable(bridgeIcon != null ? bridgeIcon : appLogoRes > 0 ? ContextCompat.getDrawable(getContext(), appLogoRes) : null);
    }
}
