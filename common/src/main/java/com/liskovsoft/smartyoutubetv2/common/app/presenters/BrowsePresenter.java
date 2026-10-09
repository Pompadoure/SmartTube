package com.liskovsoft.smartyoutubetv2.common.app.presenters;

import android.annotation.SuppressLint;
import android.content.Context;
import android.text.TextUtils;
import android.util.Pair;

import androidx.annotation.Nullable;

import com.liskovsoft.mediaserviceinterfaces.oauth.Account;
import com.liskovsoft.mediaserviceinterfaces.data.MediaGroup;
import com.liskovsoft.sharedutils.helpers.Helpers;
import com.liskovsoft.sharedutils.locale.LocaleUtility;
import com.liskovsoft.sharedutils.mylogger.Log;
import com.liskovsoft.sharedutils.rx.RxHelper;
import com.liskovsoft.youtubeapi.service.YouTubeServiceManager;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.ShortsTransitionState;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.ShortsHistory;
import com.liskovsoft.smartyoutubetv2.common.R;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.BrowseSection;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Playlist;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.SettingsGroup;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.SettingsItem;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.VideoGroup;
import com.liskovsoft.smartyoutubetv2.common.app.models.errors.CategoryEmptyError;
import com.liskovsoft.smartyoutubetv2.common.app.models.errors.ErrorFragmentData;
import com.liskovsoft.smartyoutubetv2.common.app.models.errors.PasswordError;
import com.liskovsoft.smartyoutubetv2.common.app.models.errors.SignInError;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.service.VideoStateService;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.service.VideoStateService.State;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.base.BasePresenter;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.dialogs.VideoActionPresenter;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.dialogs.menu.ChannelUploadsMenuPresenter;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.dialogs.menu.SectionMenuPresenter;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.dialogs.menu.VideoMenuPresenter;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.dialogs.menu.VideoMenuPresenter.VideoMenuCallback;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.dialogs.menu.providers.channelgroup.ChannelGroupServiceWrapper;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.interfaces.SectionPresenter;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.interfaces.VideoGroupPresenter;
import com.liskovsoft.smartyoutubetv2.common.app.views.BrowseView;
import com.liskovsoft.smartyoutubetv2.common.misc.AppDataSourceManager;
import com.liskovsoft.smartyoutubetv2.common.misc.BrowseProcessorManager;
import com.liskovsoft.smartyoutubetv2.common.misc.MediaServiceManager;
import com.liskovsoft.smartyoutubetv2.common.misc.MediaServiceManager.AccountChangeListener;
import com.liskovsoft.smartyoutubetv2.common.prefs.AccountsData;
import com.liskovsoft.smartyoutubetv2.common.prefs.BlockedChannelData;
import com.liskovsoft.smartyoutubetv2.common.prefs.MainUIData;
import com.liskovsoft.smartyoutubetv2.common.utils.Utils;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicInteger;

import io.reactivex.Observable;
import io.reactivex.disposables.Disposable;

public class BrowsePresenter extends BasePresenter<BrowseView> implements SectionPresenter, VideoGroupPresenter, AccountChangeListener {
    private static final String TAG = BrowsePresenter.class.getSimpleName();
    @SuppressLint("StaticFieldLeak")
    private static BrowsePresenter sInstance;
    private final List<BrowseSection> mSections;
    private final List<BrowseSection> mErrorSections;
    private final Map<Integer, Observable<MediaGroup>> mGridMapping;
    private final Map<Integer, Observable<List<MediaGroup>>> mRowMapping;
    private final Map<Integer, Callable<List<SettingsItem>>> mSettingsGridMapping;
    private final Map<Integer, Callable<List<Video>>> mLocalGridMappings;
    private final Map<Integer, BrowseSection> mSectionsMapping;
    private final AppDataSourceManager mDataSourcePresenter;
    private final BrowseProcessorManager mBrowseProcessor;
    private final List<Disposable> mActions;
    private final Runnable mRefreshSection = this::refresh;
    private BrowseSection mCurrentSection;
    private Video mCurrentVideo;
    private long mLastUpdateTimeMs = -1;
    private int mBootSectionIndex;
    private int mBootstrapSectionId = -1;

    private BrowsePresenter(Context context) {
        super(context);
        mDataSourcePresenter = AppDataSourceManager.instance();
        mSections = new ArrayList<>();
        mErrorSections = new ArrayList<>();
        mGridMapping = new HashMap<>();
        mRowMapping = new HashMap<>();
        mSettingsGridMapping = new HashMap<>();
        mLocalGridMappings = new HashMap<>();
        mSectionsMapping = new HashMap<>();
        MediaServiceManager.instance().addAccountListener(this);

        mBrowseProcessor = new BrowseProcessorManager(getContext(), this::syncItem);
        mActions = new ArrayList<>();

        initSectionMappings();
        updateChannelSorting();
        updatePlaylistsStyle();
    }

    public static BrowsePresenter instance(Context context) {
        if (sInstance == null) {
            sInstance = new BrowsePresenter(context);
        }

        sInstance.setContext(context);

        return sInstance;
    }

    public static void unhold() {
        sInstance = null;
    }

    @Override
    public void onViewInitialized() {
        super.onViewInitialized();

        // JoTube: a new browse view (e.g. recreated after low memory) never auto starts the Shorts player again
        mPendingShortsStart = false;
        mShortsAutoStarted = false;

        if (getView() == null) {
            return;
        }

        updateSections();

        // Move default focus
        int selectedSectionIndex = findSectionIndex(mCurrentSection != null ? mCurrentSection.getId() : mBootstrapSectionId);
        mBootstrapSectionId = -1;
        getView().selectSection(selectedSectionIndex != -1 ? selectedSectionIndex : mBootSectionIndex, true);
    }

    @Override
    public void onViewPaused() {
        super.onViewPaused();

        saveSelectedItems();
    }

    @Override
    public void onViewResumed() {
        super.onViewResumed();

        // JoTube: left from the Shorts player opens the sidebar
        String resumeVideoId = ShortsTransitionState.consumeResumeVideoId();
        onShortsPlayerClosed(resumeVideoId);
        if (resumeVideoId != null && getView() != null) {
            getView().showHeaders();
        }
        refreshIfNeeded();
    }

    private void refreshIfNeeded() {
        if (getView() == null || !isHomeSection() || mLastUpdateTimeMs == -1 || System.currentTimeMillis() - mLastUpdateTimeMs < 3 * 60 * 60 * 1_000) {
            return;
        }

        Utils.testUrl("https://www.youtube.com", () -> refresh(false));
    }

    private void saveSelectedItems() {
        // Fix position reset when jumping between sections
        if (mCurrentVideo != null && mCurrentVideo.getPositionInsideGroup() == 0 && (System.currentTimeMillis() - mCurrentVideo.timestamp) < 10_000) {
            return;
        }

        if ((isSubscriptionsSection() && getGeneralData().isRememberSubscriptionsPositionEnabled()) ||
                (isPinnedSection() && getGeneralData().isRememberPinnedPositionEnabled())) {
            getGeneralData().setSelectedItem(mCurrentSection.getId(), mCurrentVideo);
        }
    }

    private void restoreSelectedItems() {
        if (getView() == null) {
            return;
        }

        if ((isSubscriptionsSection() && getGeneralData().isRememberSubscriptionsPositionEnabled()) ||
                (isPinnedSection() && getGeneralData().isRememberPinnedPositionEnabled())) {
            getView().selectSectionItem(getGeneralData().getSelectedItem(mCurrentSection.getId()));
        }
    }

    private void initSectionMappings() {
        initSectionMapping();

        initRowAndGridMapping();

        initSettingsGridMapping();
        initLocalGridMapping();
    }

    private void initSectionMapping() {
        String country = LocaleUtility.getCurrentLocale(getContext()).getCountry();
        int uploadsType = getMainUIData().isUploadsOldLookEnabled() ? BrowseSection.TYPE_GRID : BrowseSection.TYPE_MULTI_GRID;

        mSectionsMapping.put(MediaGroup.TYPE_HOME, new BrowseSection(MediaGroup.TYPE_HOME, getContext().getString(R.string.header_home), BrowseSection.TYPE_ROW, R.drawable.icon_home, false));
        mSectionsMapping.put(MediaGroup.TYPE_SHORTS, new BrowseSection(MediaGroup.TYPE_SHORTS, getContext().getString(R.string.header_shorts), BrowseSection.TYPE_SHORTS_GRID, R.drawable.icon_shorts));
        mSectionsMapping.put(MediaGroup.TYPE_TRENDING, new BrowseSection(MediaGroup.TYPE_TRENDING, getContext().getString(R.string.header_trending), BrowseSection.TYPE_ROW, R.drawable.icon_trending));
        mSectionsMapping.put(MediaGroup.TYPE_KIDS_HOME, new BrowseSection(MediaGroup.TYPE_KIDS_HOME, getContext().getString(R.string.header_kids_home), BrowseSection.TYPE_ROW, R.drawable.icon_kids_home));
        mSectionsMapping.put(MediaGroup.TYPE_SPORTS, new BrowseSection(MediaGroup.TYPE_SPORTS, getContext().getString(R.string.header_sports), BrowseSection.TYPE_ROW, R.drawable.icon_sports));
        mSectionsMapping.put(MediaGroup.TYPE_LIVE, new BrowseSection(MediaGroup.TYPE_LIVE, getContext().getString(R.string.badge_live), BrowseSection.TYPE_ROW, R.drawable.icon_live));
        mSectionsMapping.put(MediaGroup.TYPE_MY_VIDEOS, new BrowseSection(MediaGroup.TYPE_MY_VIDEOS, getContext().getString(R.string.my_videos), BrowseSection.TYPE_GRID, R.drawable.icon_playlist));
        mSectionsMapping.put(MediaGroup.TYPE_GAMING, new BrowseSection(MediaGroup.TYPE_GAMING, getContext().getString(R.string.header_gaming), BrowseSection.TYPE_ROW, R.drawable.icon_gaming));
        if (!Helpers.equalsAny(country, "RU", "BY")) {
            mSectionsMapping.put(MediaGroup.TYPE_NEWS, new BrowseSection(MediaGroup.TYPE_NEWS, getContext().getString(R.string.header_news), BrowseSection.TYPE_ROW, R.drawable.icon_news));
        }
        mSectionsMapping.put(MediaGroup.TYPE_MUSIC, new BrowseSection(MediaGroup.TYPE_MUSIC, getContext().getString(R.string.header_music), BrowseSection.TYPE_ROW, R.drawable.icon_music));
        mSectionsMapping.put(MediaGroup.TYPE_CHANNEL_UPLOADS, new BrowseSection(MediaGroup.TYPE_CHANNEL_UPLOADS, getContext().getString(R.string.header_channels), uploadsType, R.drawable.icon_channels, false));
        mSectionsMapping.put(MediaGroup.TYPE_SUBSCRIPTIONS, new BrowseSection(MediaGroup.TYPE_SUBSCRIPTIONS, getContext().getString(R.string.header_subscriptions), BrowseSection.TYPE_GRID, R.drawable.icon_subscriptions, false));
        mSectionsMapping.put(MediaGroup.TYPE_HISTORY, new BrowseSection(MediaGroup.TYPE_HISTORY, getContext().getString(R.string.header_history), BrowseSection.TYPE_GRID, R.drawable.icon_history, true));
        mSectionsMapping.put(MediaGroup.TYPE_BLOCKED_CHANNELS,
                new BrowseSection(MediaGroup.TYPE_BLOCKED_CHANNELS, getContext().getString(R.string.header_blocked_channels), BrowseSection.TYPE_GRID, R.drawable.icon_blocked_channels, false));
        mSectionsMapping.put(MediaGroup.TYPE_USER_PLAYLISTS, new BrowseSection(MediaGroup.TYPE_USER_PLAYLISTS, getContext().getString(R.string.header_playlists), BrowseSection.TYPE_ROW, R.drawable.icon_playlist, false));
        mSectionsMapping.put(MediaGroup.TYPE_NOTIFICATIONS, new BrowseSection(MediaGroup.TYPE_NOTIFICATIONS, getContext().getString(R.string.header_notifications), BrowseSection.TYPE_GRID, R.drawable.icon_notification, false));
        mSectionsMapping.put(MediaGroup.TYPE_PLAYBACK_QUEUE, new BrowseSection(MediaGroup.TYPE_PLAYBACK_QUEUE, getContext().getString(R.string.playback_queue_category_title), BrowseSection.TYPE_GRID, R.drawable.icon_queue, false));

        if (getSidebarService().isSettingsSectionEnabled()) {
            mSectionsMapping.put(MediaGroup.TYPE_SETTINGS, new BrowseSection(MediaGroup.TYPE_SETTINGS, getContext().getString(R.string.header_settings), BrowseSection.TYPE_SETTINGS_GRID, R.drawable.icon_settings));
        }
    }

    private void initRowAndGridMapping() {
        mRowMapping.put(MediaGroup.TYPE_HOME, getContentService().getHomeObserve());
        mRowMapping.put(MediaGroup.TYPE_TRENDING, getContentService().getTrendingObserve());
        mRowMapping.put(MediaGroup.TYPE_KIDS_HOME, getContentService().getKidsHomeObserve());
        mRowMapping.put(MediaGroup.TYPE_SPORTS, getContentService().getSportsObserve());
        mRowMapping.put(MediaGroup.TYPE_LIVE, getContentService().getLiveObserve());
        mRowMapping.put(MediaGroup.TYPE_NEWS, getContentService().getNewsObserve());
        mRowMapping.put(MediaGroup.TYPE_MUSIC, getContentService().getMusicObserve());
        mRowMapping.put(MediaGroup.TYPE_GAMING, getContentService().getGamingObserve());
        mRowMapping.put(MediaGroup.TYPE_USER_PLAYLISTS, getContentService().getPlaylistRowsObserve());

        mGridMapping.put(MediaGroup.TYPE_SHORTS, getContentService().getShortsObserve());
        mGridMapping.put(MediaGroup.TYPE_SUBSCRIPTIONS, getContentService().getSubscriptionsObserve());
        mGridMapping.put(MediaGroup.TYPE_HISTORY, getContentService().getHistoryObserve());
        mGridMapping.put(MediaGroup.TYPE_CHANNEL_UPLOADS, getContentService().getSubscribedChannelsByNewContentObserve());
        mGridMapping.put(MediaGroup.TYPE_NOTIFICATIONS, getNotificationsService().getNotificationItemsObserve());
        mGridMapping.put(MediaGroup.TYPE_MY_VIDEOS, getContentService().getMyVideosObserve());
    }

    private void initPinnedSections() {
        mSections.clear();

        Collection<Video> pinnedItems = getSidebarService().getPinnedItems();

        for (Video item : pinnedItems) {
            if (item != null) {
                if (item.sectionId == -1) { // pinned channel or playlist
                    BrowseSection section = createPinnedSection(item);
                    mSections.add(section);
                } else {
                    BrowseSection section = mSectionsMapping.get(item.sectionId);

                    if (section != null) {
                        mSections.add(section);
                    }
                }
            }
        }
    }

    private void initPinnedCallbacks() {
        Collection<Video> pinnedItems = getSidebarService().getPinnedItems();

        for (Video item : pinnedItems) {
            if (item != null && item.sectionId == -1) {
                createPinnedMapping(item);
            }
        }
    }

    private void initSettingsGridMapping() {
        mSettingsGridMapping.put(MediaGroup.TYPE_SETTINGS, () -> mDataSourcePresenter.getSettingItems(getContext()));
    }

    private void initLocalGridMapping() {
        mLocalGridMappings.put(MediaGroup.TYPE_PLAYBACK_QUEUE, () -> Playlist.instance().getAllReversed());
        mLocalGridMappings.put(MediaGroup.TYPE_BLOCKED_CHANNELS, this::getBlockedChannels);
    }

    private List<Video> getBlockedChannels() {
        BlockedChannelData blockedChannelData = BlockedChannelData.instance(getContext());
        List<Video> videos = new ArrayList<>();

        for (Pair<String, String> entry : blockedChannelData.getChannelIdsWithNames()) {
            Video video = new Video();
            video.channelId = entry.first;
            video.title = entry.second;
            videos.add(video);
        }

        return videos;
    }

    public void updateSections() {
        if (getView() == null) {
            return;
        }

        initPinnedData();

        refreshSections();
    }

    private void refreshSections() {
        if (getView() == null) {
            return;
        }

        // clean up (profile changed etc)
        getView().removeAllSections();

        int bootSectionId = getSidebarService().getBootSectionId();

        // Empty Home on first run fix. Switch to something non-empty.
        if (!getSignInService().isSigned() && VideoStateService.instance(getContext()).isEmpty()) {
            bootSectionId = MediaGroup.TYPE_MUSIC;
        }

        int index = 0;

        for (BrowseSection section : mErrorSections) {
            getView().addSection(index++, section);
        }

        for (BrowseSection section : mSections) { // contains sections and pinned items!
            if (section.getId() == MediaGroup.TYPE_SETTINGS) {
                section.setEnabled(true);
            }

            if (section.isEnabled()) {
                if (section.getId() == bootSectionId) {
                    mBootSectionIndex = index;
                }
                getView().addSection(index++, section);
            } else {
                getView().removeSection(section);
            }
        }

        // Refresh and restore last focus
        int selectedSectionIndex = findSectionIndex(mCurrentSection != null ? mCurrentSection.getId() : -1);
        getView().selectSection(selectedSectionIndex != -1 ? selectedSectionIndex : mBootSectionIndex, false);
    }

    private void initPinnedData() {
        initPinnedSections();
        initPinnedCallbacks();
        initPasswordSection();
    }

    private void sortSections() {
        // NOTE: Comparator.comparingInt API >= 24
        Collections.sort(mSections, (o1, o2) -> {
            return getSidebarService().getSectionIndex(o1.getId()) - getSidebarService().getSectionIndex(o2.getId());
        });
    }

    public void updateChannelSorting() {
        int sortingType = getMainUIData().getChannelCategorySorting();

        switch (sortingType) {
            case MainUIData.CHANNEL_SORTING_DEFAULT:
                mGridMapping.put(MediaGroup.TYPE_CHANNEL_UPLOADS, getContentService().getSubscribedChannelsObserve());
                break;
            case MainUIData.CHANNEL_SORTING_NAME2:
            case MainUIData.CHANNEL_SORTING_NAME:
                mGridMapping.put(MediaGroup.TYPE_CHANNEL_UPLOADS, getContentService().getSubscribedChannelsByNameObserve());
                break;
            case MainUIData.CHANNEL_SORTING_NEW_CONTENT:
                mGridMapping.put(MediaGroup.TYPE_CHANNEL_UPLOADS, getContentService().getSubscribedChannelsByNewContentObserve());
                break;
            case MainUIData.CHANNEL_SORTING_LAST_VIEWED:
                mGridMapping.put(MediaGroup.TYPE_CHANNEL_UPLOADS, getContentService().getSubscribedChannelsByLastViewedObserve());
                break;
        }
    }

    public void updatePlaylistsStyle() {
        int playlistsStyle = getMainUIData().getPlaylistsStyle();

        switch (playlistsStyle) {
            case MainUIData.PLAYLISTS_STYLE_GRID:
                mRowMapping.remove(MediaGroup.TYPE_USER_PLAYLISTS);
                mGridMapping.put(MediaGroup.TYPE_USER_PLAYLISTS, getContentService().getPlaylistsObserve());
                updateCategoryType(MediaGroup.TYPE_USER_PLAYLISTS, BrowseSection.TYPE_GRID);
                break;
            case MainUIData.PLAYLISTS_STYLE_ROWS:
                mGridMapping.remove(MediaGroup.TYPE_USER_PLAYLISTS);
                mRowMapping.put(MediaGroup.TYPE_USER_PLAYLISTS, getContentService().getPlaylistRowsObserve());
                updateCategoryType(MediaGroup.TYPE_USER_PLAYLISTS, BrowseSection.TYPE_ROW);
                break;
        }
    }

    private void updateCategoryType(int categoryId, int categoryType) {
        if (categoryType == -1 || categoryId == -1 || mSections == null) {
            return;
        }

        BrowseSection section = mSectionsMapping.get(categoryId);

        if (section != null) {
            section.setType(categoryType);
        }

        for (BrowseSection category : mSections) {
            if (category.getId() == categoryId) {
                category.setType(categoryType);
                break;
            }
        }
    }

    @Override
    public void onViewDestroyed() {
        super.onViewDestroyed();
        disposeActions();
        saveSelectedItems();
    }

    @Override
    public void onVideoItemSelected(Video item) {
        if (getView() == null) {
            return;
        }

        if (belongsToChannelUploadsMultiGrid(item)) {
            if (getMainUIData().isUploadsAutoLoadEnabled()) {
                updateChannelUploadsMultiGrid(item);
            } else {
                updateChannelUploadsMultiGrid(null); // clear
            }
        }

        mCurrentVideo = item;

        // JoTube: the card the focus stays on is probably the one that gets opened. Fetch its stream info now
        // (one video cache in the service), so the player starts 2-4 s sooner. The fetches run one at a time
        // (FormatFetchLock) and are never cancelled midway (that broke the shared state before: 403).
        Utils.removeCallbacks(mPrefetchFocusedShort);
        if (item != null && item.hasVideo() && !item.isLive && !item.isUpcoming) {
            // Regular videos: a card the user stays on for a moment (the fetch takes ~3 s, the click then starts at once)
            Utils.postDelayed(mPrefetchFocusedShort, item.isShorts ? 400 : 600);
        }
    }

    private final Runnable mPrefetchFocusedShort = () -> prefetchFocused(mCurrentVideo);
    private Disposable mFocusPrefetchAction;
    private Video mFocusPrefetchWanted; // focused while another prefetch was running

    private void prefetchFocused(Video item) {
        if (item == null || item.videoId == null || getView() == null || isShortsAutoStarted()) {
            return;
        }

        if (RxHelper.isAnyActionRunning(mFocusPrefetchAction)) {
            mFocusPrefetchWanted = item; // after the running one
            return;
        }

        mFocusPrefetchWanted = null;
        mFocusPrefetchAction = YouTubeServiceManager.instance().getMediaItemService().getFormatInfoObserve(item.videoId)
                .subscribe(formatInfo -> {}, error -> {
                    Log.e(TAG, "Focus prefetch failed: %s", error.getMessage());
                    onFocusPrefetchDone();
                }, this::onFocusPrefetchDone);
    }

    private void onFocusPrefetchDone() {
        Video wanted = mFocusPrefetchWanted;
        mFocusPrefetchWanted = null;

        // Still on that card: fetch it now
        if (wanted != null && wanted == mCurrentVideo && !RxHelper.isAnyActionRunning(mFocusPrefetchAction)) {
            prefetchFocused(wanted);
        }
    }

    // JoTube: the Shorts section plays right away (like the official app) instead of showing the grid first
    private boolean mPendingShortsStart; // the user entered the Shorts section, start once the feed is loaded
    private boolean mShortsAutoStarted; // the player was started from the sidebar, back returns to the sidebar
    private VideoGroup mShortsGroup; // the loaded Shorts feed
    private VideoGroup mPlayingShortsGroup; // strong ref: the player follows its order (Video holds a weak ref only)
    private long mShortsLoadStartMs = -1; // the running Shorts feed load
    private Disposable mFirstShortPrefetch;
    private String mResumeVideoId; // left to the sidebar from this Short: continue from it

    /**
     * The focus moved from the sidebar into the section content (true) or back to the sidebar (false).
     */
    public void onContentEntered(boolean entered) {
        if (!entered || !isShortsSection()) {
            mPendingShortsStart = false;
            if (getView() != null) {
                getView().setContentHidden(false);
            }
            return;
        }

        mPendingShortsStart = true;
        if (getView() != null) {
            getView().setContentHidden(true); // the player opens on top, never show the grid meanwhile
        }

        if (mShortsGroup == null && mShortsLoadStartMs == -1) {
            updateCurrentSection(); // nothing loaded or loading (e.g. the last load failed)
        } else {
            startShortsIfReady();
        }
    }

    /**
     * OK on a sidebar item. The Shorts feed loaded (or loading) since the item got the focus is used as is.
     */
    public void onSectionClicked(int sectionId) {
        boolean shortsFeedReady = isShortsSection() && mCurrentSection.getId() == sectionId &&
                (mShortsGroup != null || (mShortsLoadStartMs != -1 && System.currentTimeMillis() - mShortsLoadStartMs < 15_000));

        if (!shortsFeedReady) {
            onSectionFocused(sectionId);
        }
    }

    /**
     * The first Short's stream info is fetched as soon as the feed is there, so it plays right away when entered.
     */
    private void prefetchFirstShort() {
        Video first = findFirstShort(mShortsGroup);

        if (first == null || mShortsAutoStarted || mPendingShortsStart || RxHelper.isAnyActionRunning(mFirstShortPrefetch)) {
            return;
        }

        // Own request, never cancelled (a cancel interrupts the fetch in the middle). The player gets it from the cache.
        mFirstShortPrefetch = YouTubeServiceManager.instance().getMediaItemService().getFormatInfoObserve(first.videoId)
                .subscribe(formatInfo -> {}, error -> Log.e(TAG, "First Short prefetch failed: %s", error.getMessage()));
    }

    private boolean isShortsAutoStarted() {
        return mShortsAutoStarted;
    }

    private boolean isShortsSection() {
        return mCurrentSection != null && mCurrentSection.getType() == BrowseSection.TYPE_SHORTS_GRID;
    }

    private void startShortsIfReady() {
        if (!mPendingShortsStart || !isShortsSection() || getContext() == null || getView() == null) {
            return;
        }

        Video resume = findVideo(mShortsGroup, mResumeVideoId);
        Video first = resume != null ? resume : findFirstShort(mShortsGroup);
        mResumeVideoId = null;

        if (first == null) {
            return; // the feed is still loading
        }

        mPendingShortsStart = false;
        mShortsAutoStarted = true;
        mPlayingShortsGroup = mShortsGroup;
        onVideoItemClicked(first);
    }

    private static Video findFirstShort(VideoGroup group) {
        if (group == null || group.isEmpty()) {
            return null;
        }

        for (Video item : group.getVideos()) {
            if (item != null && item.hasVideo() && !item.isLive && !item.isUpcoming) {
                return item;
            }
        }

        return null;
    }

    private void onShortsPlayerClosed(String resumeVideoId) {
        if (!mShortsAutoStarted) {
            return;
        }

        VideoGroup playedGroup = mPlayingShortsGroup;
        mShortsAutoStarted = false;
        mPendingShortsStart = false;
        mPlayingShortsGroup = null;

        if (getView() != null) {
            getView().setContentHidden(false);
        }

        if (getView() != null && isShortsSection()) {
            getView().showHeaders();

            if (resumeVideoId != null && findVideo(playedGroup, resumeVideoId) != null) {
                // Left to the sidebar: going back in continues from the same Short, in the same feed
                mShortsGroup = playedGroup;
                mResumeVideoId = resumeVideoId;
            } else {
                // Closed: a fresh feed for the next time
                mResumeVideoId = null;
                updateCurrentSection();
            }
        }
    }

    private static Video findVideo(VideoGroup group, String videoId) {
        if (group == null || group.isEmpty() || videoId == null) {
            return null;
        }

        for (Video item : group.getVideos()) {
            if (item != null && videoId.equals(item.videoId)) {
                return item;
            }
        }

        return null;
    }

    @Override
    public void onVideoItemClicked(Video item) {
        Utils.removeCallbacks(mPrefetchFocusedShort); // JoTube
        mFocusPrefetchWanted = null; // JoTube: the player fetches the clicked one
        if (getContext() == null) {
            return;
        }

        // Check that channels new look enabled and we're on the first columnAdd commentMore actions
        if (belongsToChannelUploadsMultiGrid(item)) {
            if (getMainUIData().isUploadsAutoLoadEnabled()) {
                VideoActionPresenter.instance(getContext()).apply(item);
            } else {
                updateChannelUploadsMultiGrid(item);
            }
        } else {
            VideoActionPresenter.instance(getContext()).apply(item);
        }
    }

    @Override
    public void onVideoItemLongClicked(Video item) {
        if (getContext() == null) {
            return;
        }

        if (belongsToChannelUploads(item)) { // We need to be sure we exactly on Channels section
            ChannelUploadsMenuPresenter.instance(getContext()).showMenu(item, (videoItem, action) -> {
                if (action == VideoMenuCallback.ACTION_UNSUBSCRIBE) { // works with any uploads section look
                    removeItem(item);
                }
            });
        } else {
            VideoMenuPresenter.instance(getContext()).showMenu(item, (videoItem, action) -> {
                if (action == VideoMenuCallback.ACTION_REMOVE ||
                    action == VideoMenuCallback.ACTION_REMOVE_FROM_PLAYLIST ||
                    (action == VideoMenuCallback.ACTION_REMOVE_FROM_QUEUE && isPlaybackQueueSection())) {
                    removeItem(videoItem);
                } else if (action == VideoMenuCallback.ACTION_UNSUBSCRIBE && isMultiGridChannelUploadsSection()) {
                    removeItem(mCurrentVideo);
                    VideoMenuPresenter.instance(getContext()).closeDialog();
                } else if (action == VideoMenuCallback.ACTION_UNSUBSCRIBE && isSubscriptionsSection()) {
                    removeItemAuthor(videoItem);
                    VideoMenuPresenter.instance(getContext()).closeDialog();
                } else if (action == VideoMenuCallback.ACTION_REMOVE_AUTHOR) {
                    removeItemAuthor(videoItem);
                }
            });
        }
    }

    @Override
    public void onScrollEnd(Video item) {
        if (item == null) {
            Log.e(TAG, "Can't scroll. Video is null.");
            return;
        }

        VideoGroup group = item.getGroup();

        continueGroup(group);
    }

    @Override
    public void onSectionFocused(int sectionId) {
        saveSelectedItems(); // save previous state
        mCurrentSection = findSectionById(sectionId);
        mCurrentVideo = null; // fast scroll through the sections (fix empty selected item)
        updateCurrentSection();
        restoreSelectedItems(); // Don't place anywhere else
    }

    @Override
    public void onSectionLongPressed(int sectionId) {
        SectionMenuPresenter.instance(getContext()).showMenu(findSectionById(sectionId));
    }

    @Override
    public boolean hasPendingActions() {
        return RxHelper.isAnyActionRunning(mActions);
    }

    public boolean isItemPinned(Video item) {
        Collection<Video> items = getSidebarService().getPinnedItems();

        return items.contains(item);
    }

    public void moveSectionUp(BrowseSection section) {
        mCurrentSection = section; // move current focus
        getSidebarService().moveSectionUp(section.getId());
        updateSections();
    }

    public void moveSectionDown(BrowseSection section) {
        mCurrentSection = section; // move current focus
        getSidebarService().moveSectionDown(section.getId());
        updateSections();
    }

    public void renameSection(BrowseSection section) {
        mCurrentSection = section; // move current focus
        getSidebarService().renameSection(section.getId(), section.getTitle());
        updateSections();
    }

    public void renameSection(Video section) {
        getSidebarService().renameSection(section.getId(), section.getTitle());
        updateSections();
    }

    public void enableAllSections(boolean enable) {
        enableSection(MediaGroup.TYPE_HISTORY, enable);
        enableSection(MediaGroup.TYPE_USER_PLAYLISTS, enable);
        enableSection(MediaGroup.TYPE_SUBSCRIPTIONS, enable);
        enableSection(MediaGroup.TYPE_CHANNEL_UPLOADS, enable);
        enableSection(MediaGroup.TYPE_GAMING, enable);
        enableSection(MediaGroup.TYPE_MUSIC, enable);
        enableSection(MediaGroup.TYPE_NEWS, enable);
        enableSection(MediaGroup.TYPE_HOME, enable);
        enableSection(MediaGroup.TYPE_TRENDING, enable);
        enableSection(MediaGroup.TYPE_SHORTS, enable);
    }

    public void enableSection(int sectionId, boolean enable) {
        getSidebarService().enableSection(sectionId, enable);

        if (!enable && mCurrentSection != null && mCurrentSection.getId() == sectionId) {
            mCurrentSection = findNearestSection(sectionId);
        }

        updateSections();
    }

    public void pinItem(Video item) {
        if (getView() == null) {
            return;
        }

        int idx = getSidebarService().addPinnedItem(item);

        createPinnedMapping(item);

        BrowseSection newSection = createPinnedSection(item);
        if (!mSections.contains(newSection)) {
            if (idx != -1) {
                mSections.add(idx, newSection);
            } else {
                mSections.add(newSection);
            }
        }
        getView().addSection(idx, newSection);
    }

    public void pinItem(String title, int resId, ErrorFragmentData data) {
        if (getView() == null) {
            return;
        }

        BrowseSection newSection = new BrowseSection(title.hashCode(), title, BrowseSection.TYPE_ERROR, resId, false, data);
        Helpers.removeIf(mErrorSections, section -> section.getId() == newSection.getId());
        mErrorSections.add(newSection);
        getView().addSection(0, newSection);
    }

    private void appendToSections(String title, int resId, ErrorFragmentData data) {
        int id = title.hashCode();
        Helpers.removeIf(mSections, section -> section.getId() == id);
        mSections.add(new BrowseSection(id, title, BrowseSection.TYPE_ERROR, resId, false, data));
    }

    public void unpinItem(Video item) {
        getSidebarService().removePinnedItem(item);
        getGeneralData().removeSelectedItem(item.getId());

        BrowseSection section = null;

        for (BrowseSection cat : mSections) {
            if (cat.getId() == item.getId()) {
                section = cat;
                break;
            }
        }

        mGridMapping.remove(item.getId());

        if (getView() != null) {
            getView().removeSection(section);
        }
    }

    public void refresh() {
        refresh(true);
    }

    public void refresh(boolean focusOnContent) {
        updateCurrentSection();
        if (focusOnContent && getView() != null) {
            getView().focusOnContent();
        }
    }

    private void updateRefreshTime() {
        mLastUpdateTimeMs = System.currentTimeMillis();
    }

    private void updateCurrentSection() {
        disposeActions();

        if (getView() == null || mCurrentSection == null) {
            return;
        }

        Log.d(TAG, "Update section %s", mCurrentSection.getTitle());
        updateSection(mCurrentSection);
    }

    private void updateSection(BrowseSection section) {
        switch (section.getType()) {
            case BrowseSection.TYPE_GRID:
            case BrowseSection.TYPE_SHORTS_GRID:
                if (mGridMapping.containsKey(section.getId())) {
                    Observable<MediaGroup> group = mGridMapping.get(section.getId());
                    updateVideoGrid(section, group, section.isAuthOnly());
                } else if (mLocalGridMappings.containsKey(section.getId())) {
                    Callable<List<Video>> localVideos = mLocalGridMappings.get(section.getId());
                    updateLocalGrid(section, localVideos);
                }
                break;
            case BrowseSection.TYPE_ROW:
                Observable<List<MediaGroup>> groups = mRowMapping.get(section.getId());
                updateVideoRows(section, groups, section.isAuthOnly());
                break;
            case BrowseSection.TYPE_SETTINGS_GRID:
                Callable<List<SettingsItem>> items = mSettingsGridMapping.get(section.getId());
                updateSettingsGrid(section, items);
                break;
            case BrowseSection.TYPE_MULTI_GRID:
                Observable<MediaGroup> group2 = mGridMapping.get(section.getId());
                updateVideoGrid(section, group2, 0, section.isAuthOnly());
                break;
            case BrowseSection.TYPE_ERROR:
                getView().showProgressBar(false);
                break;
        }

        updateRefreshTime();
    }

    private void updateSettingsGrid(BrowseSection section, Callable<List<SettingsItem>> items) {
        getView().updateSection(SettingsGroup.from(Helpers.get(items), section));
        getView().showProgressBar(false);
    }

    private void updateLocalGrid(BrowseSection section, Callable<List<Video>> items) {
        VideoGroup videoGroup = VideoGroup.from(Helpers.get(items), section);
        videoGroup.setAction(VideoGroup.ACTION_REPLACE);
        videoGroup.setId(videoGroup.hashCode());
        videoGroup.setTitle(section.getTitle());
        getView().updateSection(videoGroup);
        getView().showProgressBar(false);
    }

    private void updateVideoRows(BrowseSection section, Observable<List<MediaGroup>> groups, boolean authCheck) {
        Log.d(TAG, "loadRowsHeader: Start loading section: " + section.getTitle());

        authCheck(authCheck, () -> updateVideoRows(section, groups));
    }

    private void updateVideoGrid(BrowseSection section, Observable<MediaGroup> group, boolean authCheck) {
        updateVideoGrid(section, group, -1, authCheck);
    }

    private void updateVideoGrid(BrowseSection section, Observable<MediaGroup> group, int column, boolean authCheck) {
        Log.d(TAG, "loadMultiGridHeader: Start loading section: " + section.getTitle());

        authCheck(authCheck, () -> updateVideoGrid(section, group, column));
    }

    private void updateVideoRows(BrowseSection section, Observable<List<MediaGroup>> groups) {
        Log.d(TAG, "updateRowsHeader: Start loading section: " + section.getTitle());

        disposeActions();

        if (getView() == null) {
            Log.e(TAG, "Browse view has been unloaded from the memory. Low RAM?");
            getViewManager().startView(BrowseView.class);
            return;
        }
        
        getView().showProgressBar(true);

        VideoGroup firstGroup = VideoGroup.from(section);
        firstGroup.setAction(VideoGroup.ACTION_REPLACE);
        getView().updateSection(firstGroup);

        if (groups == null) {
            // No group. Maybe just clear.
            getView().showProgressBar(false);
            return;
        }

        AtomicInteger groupIndex = new AtomicInteger(-1);

        Disposable updateAction = groups
                .subscribe(
                        mediaGroups -> {
                            getView().showProgressBar(false);

                            filterHomeIfNeeded(mediaGroups);

                            for (MediaGroup mediaGroup : mediaGroups) {
                                if (mediaGroup.isEmpty()) {
                                    Log.e(TAG, "loadRowsHeader: MediaGroup is empty. Group Name: " + mediaGroup.getTitle());
                                    continue;
                                }

                                VideoGroup videoGroup = VideoGroup.from(mediaGroup, section, groupIndex.incrementAndGet());

                                if (TextUtils.isEmpty(videoGroup.getTitle())) {
                                    videoGroup.setTitle(getContext().getString(R.string.suggestions));
                                }

                                getView().updateSection(videoGroup);
                                mBrowseProcessor.process(videoGroup);

                                continueGroupIfNeeded(videoGroup, false);
                            }
                        },
                        error -> {
                            Log.e(TAG, "updateRowsHeader error: %s", error.getMessage());
                            handleLoadError(error);
                        }, () -> handleLoadError(null));

        mActions.add(updateAction);
    }

    private void updateVideoGrid(BrowseSection section, Observable<MediaGroup> group, int column) {
        disposeActions();

        if (getView() == null) {
            Log.e(TAG, "Browse view has been unloaded from the memory. Low RAM?");
            getViewManager().startView(BrowseView.class);
            return;
        }

        Log.d(TAG, "updateGridHeader: Start loading section: " + section.getTitle());

        getView().showProgressBar(true);

        // Stay on the same group in case of multiple subscribe calls
        VideoGroup baseGroup = VideoGroup.from(section, column);
        baseGroup.setAction(VideoGroup.ACTION_REPLACE);
        getView().updateSection(baseGroup);

        boolean isShortsGrid = section.getType() == BrowseSection.TYPE_SHORTS_GRID;
        if (isShortsGrid) {
            mShortsGroup = null; // reloading
            mResumeVideoId = null;
            mShortsLoadStartMs = System.currentTimeMillis();
        }

        if (group == null) {
            // No group. Maybe just clear.
            getView().showProgressBar(false);
            return;
        }

        Disposable updateAction = group
                .subscribe(
                        mediaGroup -> {
                            getView().showProgressBar(false);

                            if (getView() == null) {
                                Log.e(TAG, "Browse view has been unloaded from the memory. Low RAM?");
                                getViewManager().startView(BrowseView.class);
                                return;
                            }

                            int sizeBefore = Math.max(0, baseGroup.getSize());
                            VideoGroup videoGroup = VideoGroup.from(baseGroup, mediaGroup);
                            if (isShortsGrid && ShortsHistory.instance(getContext()) != null) {
                                ShortsHistory.instance(getContext()).filterNew(videoGroup, sizeBefore); // JoTube: less repetition
                            }
                            appendLocalHistory(videoGroup);
                            getView().updateSection(videoGroup);
                            mBrowseProcessor.process(videoGroup);

                            continueGroupIfNeeded(videoGroup);

                            if (isShortsGrid && mCurrentSection != null && mCurrentSection.getId() == section.getId()) {
                                mShortsGroup = videoGroup;
                                mShortsLoadStartMs = -1;
                                startShortsIfReady();
                                prefetchFirstShort();
                            }
                        },
                        error -> {
                            Log.e(TAG, "updateGridHeader error: %s", error.getMessage());
                            handleLoadError(error);
                            if (isShortsGrid) {
                                onShortsFeedFinished();
                            }
                        }, () -> {
                            handleLoadError(null);
                            if (isShortsGrid) {
                                onShortsFeedFinished();
                            }
                        });

        mActions.add(updateAction);
    }

    /**
     * The Shorts feed load ended (error or done). Nothing to play: show the section as is (e.g. the error).
     */
    private void onShortsFeedFinished() {
        mShortsLoadStartMs = -1;

        if (mPendingShortsStart && findFirstShort(mShortsGroup) == null) {
            mPendingShortsStart = false;
            if (getView() != null) {
                getView().setContentHidden(false);
            }
        }
    }

    private void continueGroup(VideoGroup group) {
        continueGroup(group, true);
    }

    private void continueGroup(VideoGroup group, boolean showLoading) {
        if (getView() == null) {
            Log.e(TAG, "Can't continue group. The view is null.");
            return;
        }

        if (group == null) {
            Log.e(TAG, "Can't continue group. The group is null.");
            return;
        }

        if (getCurrentSection() != null && mLocalGridMappings.containsKey(getCurrentSection().getId())) {
            Log.d(TAG, "Local grid section doesn't assume a continuation...");
            return;
        }

        Log.d(TAG, "continueGroup: start continue group: " + group.getTitle());

        // Small amount of items == small load time. Loading bar are useless?
        if (showLoading) {
            getView().showProgressBar(true);
        }

        MediaGroup mediaGroup = group.getMediaGroup();

        Observable<MediaGroup> continuation;

        //if (mediaGroup.getType() == MediaGroup.TYPE_SUGGESTIONS) { // Pinned playlist
        //    continuation = mItemService.continueGroupObserve(mediaGroup);
        //} else {
        //    continuation = getContentService().continueGroupObserve(mediaGroup);
        //}

        continuation = getContentService().continueGroupObserve(mediaGroup);

        Disposable continueAction = continuation
                .subscribe(
                        continueGroup -> {
                            getView().showProgressBar(false);

                            int sizeBefore = Math.max(0, group.getSize());
                            VideoGroup videoGroup = VideoGroup.from(group, continueGroup);
                            if (videoGroup == mShortsGroup && ShortsHistory.instance(getContext()) != null) {
                                ShortsHistory.instance(getContext()).filterNew(videoGroup, sizeBefore); // JoTube: less repetition
                            }
                            getView().updateSection(videoGroup);
                            mBrowseProcessor.process(videoGroup);

                            continueGroupIfNeeded(videoGroup, showLoading);
                        },
                        error -> {
                            Log.e(TAG, "continueGroup error: %s", error.getMessage());
                            if (getView() != null) {
                                getView().showProgressBar(false);
                            }
                        },
                        () -> {
                            if (getView() != null) {
                                getView().showProgressBar(false);
                            }
                        }
                );

        mActions.add(continueAction);
    }

    private void authCheck(boolean check, Runnable callback) {
        if (!check) {
            callback.run();
            return;
        }

        getView().showProgressBar(true);

        if (getSignInService().isSigned()) {
            callback.run();
        } else if (getView() != null) {
            if (isHistorySection() && !VideoStateService.instance(getContext()).isEmpty()) {
                getView().showProgressBar(false);
                VideoGroup videoGroup = VideoGroup.from(getCurrentSection());
                appendLocalHistory(videoGroup);
                getView().updateSection(videoGroup);
            } else {
                getView().showProgressBar(false);
                getView().showError(new SignInError(getContext()));
            }
        }
    }

    /**
     * Most tiny ui has 8 cards in a row or 24 in grid.
     */
    private void continueGroupIfNeeded(VideoGroup group) {
        continueGroupIfNeeded(group, true);
    }

    /**
     * Most tiny ui has 8 cards in a row or 24 in grid.
     */
    private void continueGroupIfNeeded(VideoGroup group, boolean showLoading) {
        if (MediaServiceManager.instance().shouldContinueTheGroup(getContext(), group, isGridSection())) {
            continueGroup(group, showLoading);
        }
    }

    private void disposeActions() {
        RxHelper.disposeActions(mActions);
        Utils.removeCallbacks(mRefreshSection);
        mLastUpdateTimeMs = -1;
        mBrowseProcessor.dispose();
    }

    private void updateChannelUploadsMultiGrid(Video item) {
        if (mCurrentSection == null) {
            return;
        }

        updateVideoGrid(mCurrentSection, ChannelUploadsPresenter.instance(getContext()).obtainUploadsObservable(item), 1, false);
    }

    private boolean belongsToChannelUploadsMultiGrid(Video item) {
        return isMultiGridChannelUploadsSection() && belongsToChannelUploads(item);
    }

    private boolean belongsToChannelUploads(Video item) {
        return item.belongsToChannelUploads() && !item.hasVideo();
    }

    @Nullable
    public BrowseSection getCurrentSection() {
        return mCurrentSection;
    }

    private BrowseSection findSectionById(int sectionId) {
        for (BrowseSection section : mErrorSections) {
            if (section.getId() == sectionId) {
                return section;
            }
        }

        for (BrowseSection section : mSections) {
            if (section.getId() == sectionId) {
                return section;
            }
        }

        return null;
    }

    private int findSectionIndex(int sectionId) {
        if (sectionId == -1) {
            return -1;
        }

        int sectionIndex = -1;

        for (BrowseSection section : mErrorSections) {
            if (section.isEnabled()) {
                sectionIndex++;
                if (section.getId() == sectionId) {
                    return sectionIndex;
                }
            }
        }

        for (BrowseSection section : mSections) {
            if (section.isEnabled()) {
                sectionIndex++;
                if (section.getId() == sectionId) {
                    return sectionIndex;
                }
            }
        }

        return -1;
    }

    private BrowseSection findNearestSection(int sectionId) {
        BrowseSection result = findNearestSection(mErrorSections, sectionId);

        if (result == null) {
            result = findNearestSection(mSections, sectionId);
        }

        return result;
    }

    private BrowseSection findNearestSection(List<BrowseSection> sections, int sectionId) {
        BrowseSection result = null;
        BrowseSection previousSection = null;
        boolean found = false;
        for (BrowseSection section : sections) {
            if (section.getId() == sectionId) {
                found = true;
                continue;
            }
            if (section.isEnabled()) {
                if (found) {
                    result = section;
                    break;
                }
                previousSection = section;
            }
        }

        return result != null ? result : previousSection;
    }

    private void filterHomeIfNeeded(List<MediaGroup> mediaGroups) {
        if (mediaGroups == null || !isHomeSection()) {
            return;
        }

        Helpers.removeIf(mediaGroups, value -> Helpers.containsAny(
                value.getTitle(),
                "Primetime", // Free movies and shows row
                "News", // Top news
                "news", // Top news
                "NBA TV", // Sports
                "The Life of a Showgirl", // Taylor Swift ADS
                "FIFA", // Sports (FIFA World Cup 2026™)
                "FORMULA 1" // Sports (FORMULA 1 BRITISH GRAND PRIX)
        ) || Helpers.equalsAny(
                value.getTitle(),
                //getContext().getString(R.string.news_row_name),
                getContext().getString(R.string.breaking_news_row_name),
                getContext().getString(R.string.covid_news_row_name)
        ));
    }

    private int moveToTopIfNeeded(MediaGroup mediaGroup) {
        if (mediaGroup == null) {
            return -1;
        }

        return Helpers.equalsAny(mediaGroup.getTitle(), getContext().getString(R.string.trending_row_name)) ? 0 : -1;
    }

    private Observable<MediaGroup> createPinnedGridAction(Video item) {
        if (item.channelGroupId != null) {
            return getContentService().getRssFeedObserve(ChannelGroupServiceWrapper.instance(getContext()).findChannelIdsForGroup(item.channelGroupId));
        }

        return ChannelUploadsPresenter.instance(getContext()).obtainUploadsObservable(item);
    }

    private Observable<List<MediaGroup>> createPinnedRowAction(Video item) {
        return ChannelPresenter.instance(getContext()).obtainChannelObservable(item.channelId);
    }

    /**
     * Is Channels new look enabled?
     */
    public boolean isMultiGridChannelUploadsSection() {
        return mCurrentSection != null && mCurrentSection.getType() == BrowseSection.TYPE_MULTI_GRID && mCurrentSection.getId() == MediaGroup.TYPE_CHANNEL_UPLOADS;
    }

    public boolean isSettingsSection() {
        return isSection(MediaGroup.TYPE_SETTINGS);
    }

    public boolean isPlaylistsSection() {
        return isSection(MediaGroup.TYPE_USER_PLAYLISTS);
    }

    public boolean isHomeSection() {
        return isSection(MediaGroup.TYPE_HOME);
    }

    public boolean isHistorySection() {
        return isSection(MediaGroup.TYPE_HISTORY);
    }

    public boolean isSubscriptionsSection() {
        return isSection(MediaGroup.TYPE_SUBSCRIPTIONS);
    }
    
    public boolean isPlaybackQueueSection() {
        return isSection(MediaGroup.TYPE_PLAYBACK_QUEUE);
    }

    public boolean isPinnedSection() {
        return mCurrentSection != null && isPinnedId(mCurrentSection.getId());
    }

    private boolean isPinnedId(int id) {
        return id > 100;
    }

    private boolean isSection(int sectionId) {
        return mCurrentSection != null && mCurrentSection.getId() == sectionId;
    }

    public void selectSection(int sectionId) {
        getViewManager().startView(BrowseView.class); // focus view

        if (getView() == null) {
            mBootstrapSectionId = sectionId;
            return;
        }

        int sectionIndex = findSectionIndex(sectionId);

        if (sectionIndex == -1) {
            enableSection(sectionId, true);
            sectionIndex = findSectionIndex(sectionId);
            getSidebarService().enableSection(sectionId, false); // enable temporally (till restart)
        }

        if (sectionIndex != -1) {
            getView().selectSection(sectionIndex, true);
        }
    }

    public boolean inForeground() {
        return getViewManager().getTopView() == BrowseView.class;
    }

    private boolean isGridSection() {
        return mCurrentSection != null && mCurrentSection.getType() != BrowseSection.TYPE_ROW;
    }

    @Override
    public void onAccountChanged(Account account) {
        Log.d(TAG, "On account changed");

        if (getView() == null) {
            return;
        }

        initSectionMappings();
        updateChannelSorting();
        updatePlaylistsStyle();
        updateSections();
    }

    public Video getCurrentVideo() {
        return mCurrentVideo;
    }

    private void initPasswordSection() {
        AccountsData accountsData = AccountsData.instance(getContext());
        if (accountsData.getAccountPassword() == null || accountsData.isPasswordAccepted()) {
            return;
        }

        mSections.clear();
        appendToSections(getContext().getString(R.string.header_notifications), R.drawable.icon_notification, new PasswordError(getContext()));
    }

    private void createPinnedMapping(Video item) {
        if (enableRows(item)) {
            mRowMapping.put(item.getId(), createPinnedRowAction(item));
        } else {
            mGridMapping.put(item.getId(), createPinnedGridAction(item));
        }
    }

    private BrowseSection createPinnedSection(Video item) {
        return new BrowseSection(
                item.getId(), item.getTitle(), enableRows(item) ? BrowseSection.TYPE_ROW : BrowseSection.TYPE_GRID, R.drawable.icon_pin, item.getCardImageUrl(), false, item);
    }

    private boolean enableRows(Video item) {
        return getMainUIData().isPinnedChannelRowsEnabled() && item.hasChannel() && !item.isPlaylistAsChannel();
    }

    private void handleLoadError(Throwable error) {
        if (getView() == null) {
            return;
        }

        getView().showProgressBar(false);

        if (getView().isEmpty() || error != null) {
            ErrorFragmentData errorFragmentData;
            if (error != null && !Helpers.containsAny(error.getMessage(), "fromNullable result is null")) {
                errorFragmentData = new CategoryEmptyError(getContext(), error);
            } else if (getSignInService().isSigned()) {
                errorFragmentData = new CategoryEmptyError(getContext(), null);
            } else {
                errorFragmentData = new SignInError(getContext());
            }

            getView().showError(errorFragmentData);

            if (Utils.fixRetrofitErrors(getContext(), error)) {
                return;
            }

            Utils.postDelayed(mRefreshSection, 30_000);
        }
    }

    private void appendLocalHistory(VideoGroup videoGroup) {
        if (!isHistorySection()) {
            return;
        }

        VideoStateService stateService = VideoStateService.instance(getContext());

        if (stateService.isEmpty() || (!stateService.isHistoryBroken() && !videoGroup.isEmpty())) {
            return;
        }

        Video lastHistoryItem = videoGroup.isEmpty() ? null : videoGroup.get(0);
        State lastState = stateService.getLastState();

        if (lastState == null || Helpers.equals(lastHistoryItem, lastState.video)) {
            return;
        }

        for (State state : stateService.getStates()) {
            if (lastHistoryItem == null || state.timestamp > stateService.getSessionStartTimeMs()) {
                videoGroup.add(0, state.video);
            }
        }
    }
}
