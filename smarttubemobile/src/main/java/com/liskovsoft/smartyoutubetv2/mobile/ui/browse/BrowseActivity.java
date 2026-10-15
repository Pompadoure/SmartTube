package com.liskovsoft.smartyoutubetv2.mobile.ui.browse;

import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.liskovsoft.mediaserviceinterfaces.data.MediaGroup;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.BrowseSection;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.SettingsGroup;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.VideoGroup;
import com.liskovsoft.smartyoutubetv2.common.app.models.errors.ErrorFragmentData;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.BrowsePresenter;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.SearchPresenter;
import com.liskovsoft.smartyoutubetv2.common.app.views.BrowseView;
import com.liskovsoft.smartyoutubetv2.mobile.R;
import com.liskovsoft.smartyoutubetv2.mobile.ui.main.MobileActivity;
import com.liskovsoft.smartyoutubetv2.mobile.ui.widgets.Ui;

import java.util.ArrayList;
import java.util.List;

/**
 * The phone home screen, laid out like the YouTube app: top bar with the search button, a row of chips (the
 * sections of the presenter), the feed and a bottom bar (Home, Shorts, Subscriptions, You).
 */
public class BrowseActivity extends MobileActivity implements BrowseView {
    private BrowsePresenter mPresenter;
    private final List<BrowseSection> mSections = new ArrayList<>();
    private final List<TextView> mChips = new ArrayList<>();
    private final List<TextView> mTabs = new ArrayList<>();
    private int mSelectedIndex = -1;
    private boolean mSelecting;
    private LinearLayout mChipRow;
    private HorizontalScrollView mStripScroll;
    private LinearLayout mStrip;
    private LinearLayout mHeader;
    private boolean mFeedPicked;
    private RecyclerView mList;
    private GridLayoutManager mLayoutManager;
    private VideoAdapter mAdapter;
    private ProgressBar mProgress;
    private LinearLayout mErrorBox;
    private TextView mErrorText;
    private Button mErrorButton;

    private static final int[] TAB_SECTION_IDS = {
            MediaGroup.TYPE_HOME, MediaGroup.TYPE_SHORTS, MediaGroup.TYPE_MUSIC, MediaGroup.TYPE_CHANNEL_UPLOADS, MediaGroup.TYPE_HISTORY
    };
    private static final int[] TAB_ICONS = {R.drawable.ic_home, R.drawable.ic_shorts, R.drawable.ic_music, R.drawable.ic_subs, R.drawable.ic_you};
    private static final int[] TAB_TITLES = {R.string.tab_home, R.string.tab_shorts, R.string.tab_music, R.string.tab_subscriptions, R.string.tab_you};

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(createContent());

        mPresenter = BrowsePresenter.instance(this);
        mPresenter.setView(this);
        mPresenter.onViewInitialized();
    }

    private View createContent() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(getColor(R.color.yt_bg));

        // Edge to edge: the feed scrolls under the (transparent) status bar; the top bar and the chips are the
        // first row of the feed and scroll away like in the YouTube app.
        android.view.Window window = getWindow();
        window.setStatusBarColor(0x00000000);
        window.setNavigationBarColor(getColor(R.color.yt_bg));
        root.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);

        mHeader = new LinearLayout(this);
        mHeader.setOrientation(LinearLayout.VERTICAL);
        mHeader.setBackgroundColor(getColor(R.color.yt_bg));
        mHeader.addView(createTopBar());
        mHeader.addView(createChips());
        mHeader.addView(createChannelStrip());

        FrameLayout content = new FrameLayout(this);
        mAdapter = new VideoAdapter(new VideoAdapter.Listener() {
            @Override
            public void onClick(Video video) {
                // music plays as audio (the player can switch to video), everything else as video
                com.liskovsoft.smartyoutubetv2.mobile.ui.playback.PlaybackActivity.setAudioModeForNextPlayback(isMusicSelected());
                mPresenter.onVideoItemClicked(video);
            }

            @Override
            public void onLongClick(Video video) {
                mPresenter.onVideoItemLongClicked(video);
            }
        });
        mAdapter.setFixedHeader(mHeader);
        mList = new RecyclerView(this);
        mLayoutManager = new GridLayoutManager(this, 1);
        mLayoutManager.setSpanSizeLookup(new GridLayoutManager.SpanSizeLookup() {
            @Override
            public int getSpanSize(int position) {
                return mAdapter.isHeader(position) || !isShortsSelected() ? mLayoutManager.getSpanCount() : 1;
            }
        });
        mList.setLayoutManager(mLayoutManager);
        mList.setAdapter(mAdapter);
        mList.setItemAnimator(null);
        mList.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(RecyclerView recyclerView, int dx, int dy) {
                if (dy <= 0 || mAdapter.size() == 0) {
                    return;
                }

                if (mLayoutManager.findLastVisibleItemPosition() >= mAdapter.size() - 12) {
                    Video last = mAdapter.lastVideo();
                    if (last != null) {
                        mPresenter.onScrollEnd(last);
                    }
                }
            }
        });
        content.addView(mList, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        mProgress = new ProgressBar(this);
        mProgress.setVisibility(View.GONE);
        content.addView(mProgress, new FrameLayout.LayoutParams(Ui.dp(this, 40), Ui.dp(this, 40), Gravity.CENTER));

        mErrorBox = new LinearLayout(this);
        mErrorBox.setOrientation(LinearLayout.VERTICAL);
        mErrorBox.setGravity(Gravity.CENTER);
        mErrorBox.setVisibility(View.GONE);
        mErrorText = new TextView(this);
        mErrorText.setTextColor(getColor(R.color.yt_text));
        mErrorText.setGravity(Gravity.CENTER);
        mErrorText.setPadding(Ui.dp(this, 32), 0, Ui.dp(this, 32), Ui.dp(this, 16));
        mErrorBox.addView(mErrorText);
        mErrorButton = new Button(this);
        mErrorBox.addView(mErrorButton);
        content.addView(mErrorBox, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));

        root.addView(content, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        root.addView(createMiniPlayer());
        View bottomBar = createBottomBar();
        root.addView(bottomBar);

        root.setOnApplyWindowInsetsListener((v, insets) -> {
            mHeader.setPadding(0, insets.getSystemWindowInsetTop(), 0, 0);
            bottomBar.setPadding(0, 0, 0, insets.getSystemWindowInsetBottom());
            mErrorBox.setPadding(0, insets.getSystemWindowInsetTop(), 0, 0);
            return insets;
        });

        return root;
    }

    private View createTopBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(Ui.dp(this, 16), 0, Ui.dp(this, 4), 0);

        ImageView logoIcon = new ImageView(this);
        logoIcon.setImageResource(R.drawable.jotube_logo);
        LinearLayout.LayoutParams logoIconParams = new LinearLayout.LayoutParams(Ui.dp(this, 32), Ui.dp(this, 32));
        logoIconParams.setMarginEnd(Ui.dp(this, 8));
        bar.addView(logoIcon, logoIconParams);

        TextView logo = new TextView(this);
        logo.setText("JoTube");
        logo.setTextColor(getColor(R.color.yt_text));
        logo.setTextSize(22);
        logo.setTypeface(Typeface.DEFAULT_BOLD);
        bar.addView(logo, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        ImageView settings = new ImageView(this);
        settings.setImageResource(R.drawable.ic_settings);
        settings.setPadding(Ui.dp(this, 12), Ui.dp(this, 12), Ui.dp(this, 12), Ui.dp(this, 12));
        settings.setOnClickListener(v -> selectSectionById(MediaGroup.TYPE_SETTINGS));
        bar.addView(settings, new LinearLayout.LayoutParams(Ui.dp(this, 48), Ui.dp(this, 48)));

        ImageView search = new ImageView(this);
        search.setImageResource(R.drawable.ic_search);
        search.setPadding(Ui.dp(this, 12), Ui.dp(this, 12), Ui.dp(this, 12), Ui.dp(this, 12));
        search.setOnClickListener(v -> SearchPresenter.instance(this).startSearch(null));
        bar.addView(search, new LinearLayout.LayoutParams(Ui.dp(this, 48), Ui.dp(this, 48)));

        bar.setMinimumHeight(Ui.dp(this, 52));
        return bar;
    }

    private View createChips() {
        HorizontalScrollView scroll = new HorizontalScrollView(this);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.setPadding(Ui.dp(this, 12), Ui.dp(this, 4), Ui.dp(this, 12), Ui.dp(this, 8));
        scroll.setClipToPadding(false);
        mChipRow = new LinearLayout(this);
        mChipRow.setOrientation(LinearLayout.HORIZONTAL);
        scroll.addView(mChipRow);
        return scroll;
    }

    /** Subscriptions: the round channel avatars above the feed, like the YouTube app. */
    private View createChannelStrip() {
        mStripScroll = new HorizontalScrollView(this);
        mStripScroll.setHorizontalScrollBarEnabled(false);
        mStripScroll.setVisibility(View.GONE);
        mStripScroll.setPadding(Ui.dp(this, 8), Ui.dp(this, 4), Ui.dp(this, 8), Ui.dp(this, 8));
        mStrip = new LinearLayout(this);
        mStrip.setOrientation(LinearLayout.HORIZONTAL);
        mStripScroll.addView(mStrip);
        return mStripScroll;
    }

    private void addChannels(VideoGroup group, boolean replace) {
        if (replace) {
            mStrip.removeAllViews();
        }

        for (Video channel : group.getVideos()) {
            LinearLayout item = new LinearLayout(this);
            item.setOrientation(LinearLayout.VERTICAL);
            item.setGravity(Gravity.CENTER_HORIZONTAL);
            item.setPadding(Ui.dp(this, 6), 0, Ui.dp(this, 6), 0);

            ImageView avatar = new ImageView(this);
            avatar.setBackgroundResource(R.drawable.bg_avatar);
            avatar.setScaleType(ImageView.ScaleType.CENTER_CROP);
            Ui.makeCircle(avatar);
            item.addView(avatar, new LinearLayout.LayoutParams(Ui.dp(this, 64), Ui.dp(this, 64)));
            String image = channel.getCardImageUrl() != null ? channel.getCardImageUrl() : channel.authorImageUrl;
            if (channel.isSubscriptionsFeed) {
                // "All subscriptions"
                avatar.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
                avatar.setPadding(Ui.dp(this, 16), Ui.dp(this, 16), Ui.dp(this, 16), Ui.dp(this, 16));
                avatar.setImageResource(R.drawable.ic_subs);
            } else if (image != null) {
                com.bumptech.glide.Glide.with(avatar).load(image).into(avatar);
            }

            TextView name = new TextView(this);
            name.setText(channel.getTitle());
            name.setTextColor(getColor(R.color.yt_text));
            name.setTextSize(12);
            name.setMaxLines(1);
            name.setEllipsize(TextUtils.TruncateAt.END);
            name.setGravity(Gravity.CENTER);
            name.setPadding(0, Ui.dp(this, 4), 0, 0);
            item.addView(name, new LinearLayout.LayoutParams(Ui.dp(this, 72), ViewGroup.LayoutParams.WRAP_CONTENT));

            item.setOnClickListener(v -> mPresenter.onVideoItemClicked(channel));
            item.setOnLongClickListener(v -> {
                mPresenter.onVideoItemLongClicked(channel);
                return true;
            });
            mStrip.addView(item);
        }

        mStripScroll.setVisibility(mStrip.getChildCount() > 0 ? View.VISIBLE : View.GONE);

        // Open the "all subscriptions" feed right away, otherwise the grid stays empty
        if (!mFeedPicked && !group.isEmpty()) {
            Video first = group.getVideos().get(0);
            if (first.isSubscriptionsFeed) {
                mFeedPicked = true;
                mPresenter.onVideoItemClicked(first);
            }
        }
    }

    private boolean isMultiGrid(BrowseSection section) {
        return section != null && section.getType() == BrowseSection.TYPE_MULTI_GRID;
    }

    private View createBottomBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setBackgroundColor(getColor(R.color.yt_bg));

        for (int i = 0; i < TAB_SECTION_IDS.length; i++) {
            final int sectionId = TAB_SECTION_IDS[i];
            TextView tab = new TextView(this);
            tab.setText(TAB_TITLES[i]);
            tab.setTextSize(10);
            tab.setGravity(Gravity.CENTER);
            tab.setCompoundDrawablesWithIntrinsicBounds(0, TAB_ICONS[i], 0, 0);
            tab.setCompoundDrawablePadding(Ui.dp(this, 2));
            tab.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 8));
            tab.setOnClickListener(v -> {
                if (sectionId == MediaGroup.TYPE_MUSIC && !hasSection(MediaGroup.TYPE_MUSIC)) {
                    mPresenter.enableSection(MediaGroup.TYPE_MUSIC, true); // the section is off by default in some setups
                }
                selectSectionById(sectionId);
            });
            bar.addView(tab, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            mTabs.add(tab);
        }

        updateTabs();
        return bar;
    }

    ///////////////////// mini player (the sound that goes on after the player screen was closed)

    private LinearLayout mMiniBar;
    private ImageView mMiniCover, mMiniPlay;
    private TextView mMiniTitle, mMiniAuthor;
    private Object mMiniVideo;
    private final android.os.Handler mMiniHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable mMiniTick = new Runnable() {
        @Override
        public void run() {
            updateMiniPlayer();
            mMiniHandler.postDelayed(this, 500);
        }
    };

    private View createMiniPlayer() {
        mMiniBar = new LinearLayout(this);
        mMiniBar.setOrientation(LinearLayout.HORIZONTAL);
        mMiniBar.setGravity(Gravity.CENTER_VERTICAL);
        mMiniBar.setBackgroundColor(getColor(R.color.yt_surface));
        mMiniBar.setPadding(Ui.dp(this, 8), Ui.dp(this, 6), Ui.dp(this, 4), Ui.dp(this, 6));
        mMiniBar.setVisibility(View.GONE);

        mMiniCover = new ImageView(this);
        mMiniCover.setScaleType(ImageView.ScaleType.CENTER_CROP);
        Ui.makeRounded(mMiniCover, Ui.dp(this, 6));
        mMiniBar.addView(mMiniCover, new LinearLayout.LayoutParams(Ui.dp(this, 44), Ui.dp(this, 44)));

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setPadding(Ui.dp(this, 12), 0, Ui.dp(this, 8), 0);
        mMiniTitle = new TextView(this);
        mMiniTitle.setTextColor(getColor(R.color.yt_text));
        mMiniTitle.setTextSize(14);
        mMiniTitle.setMaxLines(1);
        mMiniTitle.setEllipsize(TextUtils.TruncateAt.END);
        texts.addView(mMiniTitle);
        mMiniAuthor = new TextView(this);
        mMiniAuthor.setTextColor(getColor(R.color.yt_text_secondary));
        mMiniAuthor.setTextSize(12);
        mMiniAuthor.setMaxLines(1);
        mMiniAuthor.setEllipsize(TextUtils.TruncateAt.END);
        texts.addView(mMiniAuthor);
        mMiniBar.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        mMiniPlay = new ImageView(this);
        mMiniPlay.setImageResource(R.drawable.ic_pause);
        mMiniPlay.setPadding(Ui.dp(this, 10), Ui.dp(this, 10), Ui.dp(this, 10), Ui.dp(this, 10));
        mMiniPlay.setOnClickListener(v -> {
            androidx.media3.common.Player player = mMiniPodcast
                    ? com.liskovsoft.smartyoutubetv2.mobile.ui.playback.MediaPlaybackService.getPodcastPlayer()
                    : com.liskovsoft.smartyoutubetv2.mobile.ui.playback.MediaPlaybackService.getPlayer();
            if (player != null) {
                player.setPlayWhenReady(!player.getPlayWhenReady());
            }
        });
        mMiniBar.addView(mMiniPlay, new LinearLayout.LayoutParams(Ui.dp(this, 48), Ui.dp(this, 48)));

        ImageView close = new ImageView(this);
        close.setImageResource(R.drawable.ic_close);
        close.setPadding(Ui.dp(this, 12), Ui.dp(this, 12), Ui.dp(this, 12), Ui.dp(this, 12));
        close.setOnClickListener(v -> {
            if (mMiniPodcast) {
                com.liskovsoft.smartyoutubetv2.mobile.ui.podcast.PodcastPlayer.stop();
            } else {
                com.liskovsoft.smartyoutubetv2.mobile.ui.playback.PlaybackActivity.closeOrphan();
            }
            updateMiniPlayer();
        });
        mMiniBar.addView(close, new LinearLayout.LayoutParams(Ui.dp(this, 48), Ui.dp(this, 48)));

        mMiniBar.setOnClickListener(v -> {
            if (mMiniPodcast) {
                startActivity(new android.content.Intent(this, com.liskovsoft.smartyoutubetv2.mobile.ui.podcast.PodcastPlayerActivity.class));
            } else {
                startActivity(new android.content.Intent(this, com.liskovsoft.smartyoutubetv2.mobile.ui.playback.PlaybackActivity.class)
                        .putExtra(com.liskovsoft.smartyoutubetv2.mobile.ui.playback.PlaybackActivity.EXTRA_FROM_NOTIFICATION, true));
            }
        });
        return mMiniBar;
    }

    private boolean mMiniPodcast;

    private void updateMiniPlayer() {
        androidx.media3.common.Player player = com.liskovsoft.smartyoutubetv2.mobile.ui.playback.MediaPlaybackService.getPlayer();
        Video video = com.liskovsoft.smartyoutubetv2.mobile.ui.playback.MediaPlaybackService.getVideo();
        boolean showVideo = player != null && video != null && com.liskovsoft.smartyoutubetv2.mobile.ui.playback.PlaybackActivity.hasOrphan();

        androidx.media3.common.Player podcastPlayer = com.liskovsoft.smartyoutubetv2.mobile.ui.playback.MediaPlaybackService.getPodcastPlayer();
        com.liskovsoft.smartyoutubetv2.mobile.ui.podcast.PodcastRepository.Episode episode = com.liskovsoft.smartyoutubetv2.mobile.ui.podcast.PodcastPlayer.current();
        boolean showPodcast = !showVideo && podcastPlayer != null && episode != null && com.liskovsoft.smartyoutubetv2.mobile.ui.podcast.PodcastPlayer.isActive();

        mMiniBar.setVisibility(showVideo || showPodcast ? View.VISIBLE : View.GONE);
        if (!showVideo && !showPodcast) {
            mMiniVideo = null;
            return;
        }

        mMiniPodcast = showPodcast;
        Object key = showPodcast ? episode : video;
        if (mMiniVideo != key) {
            mMiniVideo = key;
            mMiniTitle.setText(showPodcast ? episode.title : video.getTitle());
            mMiniAuthor.setText(showPodcast ? episode.feedTitle : video.getAuthor());
            String image = showPodcast ? episode.image : video.getCardImageUrl();
            if (image != null) {
                com.bumptech.glide.Glide.with(mMiniCover).load(image).into(mMiniCover);
            } else {
                mMiniCover.setImageDrawable(null);
            }
        }
        androidx.media3.common.Player active = showPodcast ? podcastPlayer : player;
        mMiniPlay.setImageResource(active.getPlayWhenReady() ? R.drawable.ic_pause : R.drawable.ic_play);
    }

    private boolean hasSection(int sectionId) {
        for (BrowseSection section : mSections) {
            if (section.getId() == sectionId) {
                return true;
            }
        }
        return false;
    }

    private void selectSectionById(int sectionId) {
        for (int i = 0; i < mSections.size(); i++) {
            if (mSections.get(i).getId() == sectionId) {
                selectUserSection(i);
                return;
            }
        }
    }

    private void selectUserSection(int index) {
        if (index == mSelectedIndex) {
            // second tap = refresh / back to top
            mList.scrollToPosition(0);
            mPresenter.onSectionClicked(mSections.get(index).getId());
            startShortsIfSelected(true);
            return;
        }

        selectSection(index, false);
    }

    /** The Shorts chip/tab plays right away, no grid (the presenter starts the player once the feed is there). */
    private void startShortsIfSelected(boolean entered) {
        if (entered && isShortsSelected()) {
            mPresenter.onContentEntered(true);
        }
    }

    private boolean isMusicSelected() {
        return mSelectedIndex >= 0 && mSelectedIndex < mSections.size() && mSections.get(mSelectedIndex).getId() == MediaGroup.TYPE_MUSIC;
    }

    private boolean isShortsSelected() {
        return mSelectedIndex >= 0 && mSelectedIndex < mSections.size() && mSections.get(mSelectedIndex).getId() == MediaGroup.TYPE_SHORTS;
    }

    private void rebuildChips() {
        mChipRow.removeAllViews();
        mChips.clear();

        for (int i = 0; i < mSections.size(); i++) {
            final int index = i;
            TextView chip = new TextView(this);
            chip.setText(mSections.get(i).getTitle());
            chip.setTextSize(14);
            chip.setTypeface(Typeface.DEFAULT_BOLD);
            chip.setPadding(Ui.dp(this, 12), Ui.dp(this, 7), Ui.dp(this, 12), Ui.dp(this, 7));
            chip.setOnClickListener(v -> selectUserSection(index));
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            params.setMarginEnd(Ui.dp(this, 8));
            mChipRow.addView(chip, params);
            mChips.add(chip);
        }

        TextView podcasts = new TextView(this);
        podcasts.setText("Poddar");
        podcasts.setTextSize(14);
        podcasts.setTypeface(Typeface.DEFAULT_BOLD);
        podcasts.setTextColor(getColor(R.color.yt_text));
        podcasts.setBackgroundResource(R.drawable.bg_chip);
        podcasts.setPadding(Ui.dp(this, 12), Ui.dp(this, 7), Ui.dp(this, 12), Ui.dp(this, 7));
        podcasts.setOnClickListener(v -> startActivity(new android.content.Intent(this, com.liskovsoft.smartyoutubetv2.mobile.ui.podcast.PodcastsActivity.class)));
        mChipRow.addView(podcasts, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        updateChips();
    }

    private void updateChips() {
        for (int i = 0; i < mChips.size(); i++) {
            boolean selected = i == mSelectedIndex;
            TextView chip = mChips.get(i);
            chip.setBackgroundResource(selected ? R.drawable.bg_chip_selected : R.drawable.bg_chip);
            chip.setTextColor(getColor(selected ? R.color.yt_bg : R.color.yt_text));
        }
    }

    private void updateTabs() {
        int selectedId = mSelectedIndex >= 0 && mSelectedIndex < mSections.size() ? mSections.get(mSelectedIndex).getId() : -1;

        for (int i = 0; i < mTabs.size(); i++) {
            boolean selected = TAB_SECTION_IDS[i] == selectedId;
            TextView tab = mTabs.get(i);
            int color = getColor(selected ? R.color.yt_text : R.color.yt_text_secondary);
            tab.setTextColor(color);
            tab.setCompoundDrawableTintList(android.content.res.ColorStateList.valueOf(color));
        }
    }

    private void applySpanCount() {
        boolean wide = getResources().getConfiguration().screenWidthDp >= 600;
        mLayoutManager.setSpanCount(isShortsSelected() ? (wide ? 4 : 2) : (wide ? 2 : 1));
    }

    ///////////////////// BrowseView

    @Override
    public void addSection(int index, BrowseSection section) {
        if (section == null || mSections.contains(section)) {
            return;
        }

        mSections.add(Math.min(Math.max(index, 0), mSections.size()), section);
        rebuildChips();
        updateTabs();
    }

    @Override
    public void removeSection(BrowseSection section) {
        mSections.remove(section);
        if (mSelectedIndex >= mSections.size()) {
            mSelectedIndex = mSections.size() - 1;
        }
        rebuildChips();
        updateTabs();
    }

    @Override
    public void removeAllSections() {
        mSections.clear();
        mSelectedIndex = -1;
        rebuildChips();
        updateTabs();
    }

    @Override
    public void selectSection(int index, boolean focusOnContent) {
        if (index < 0 || mSections.isEmpty()) {
            return;
        }

        index = Math.min(index, mSections.size() - 1);

        if (mSelecting) {
            return;
        }

        mSelecting = true;
        try {
            boolean changed = index != mSelectedIndex;
            mSelectedIndex = index;
            updateChips();
            updateTabs();
            applySpanCount();

            if (changed) {
                mFeedPicked = false;
                mFillTries = 0;
                mStrip.removeAllViews();
                mStripScroll.setVisibility(View.GONE);
                mAdapter.clear();
                hideError();
            }

            mPresenter.onSectionFocused(mSections.get(index).getId());
            startShortsIfSelected(changed);
        } finally {
            mSelecting = false;
        }
    }

    @Override
    public void updateSection(VideoGroup group) {
        if (group == null || mSelectedIndex < 0 || mSelectedIndex >= mSections.size()) {
            return;
        }

        BrowseSection section = group.getSection();
        if (section != null && section.getId() != mSections.get(mSelectedIndex).getId()) {
            return;
        }

        hideError();

        // Subscriptions (multi grid): position 0 = channels (the avatar strip), position 1 = the uploads feed (the grid)
        if (isMultiGrid(section != null ? section : mSections.get(mSelectedIndex)) && group.getPosition() == 0) {
            addChannels(group, group.getAction() == VideoGroup.ACTION_REPLACE);
            return;
        }

        switch (group.getAction()) {
            case VideoGroup.ACTION_REPLACE:
                mAdapter.clear();
                appendGroup(group);
                break;
            case VideoGroup.ACTION_REMOVE:
                for (Video video : group.getVideos()) {
                    mAdapter.removeVideo(video);
                }
                break;
            case VideoGroup.ACTION_SYNC:
                break;
            default:
                appendGroup(group);
        }
    }

    private int mFillTries;

    private void appendGroup(VideoGroup group) {
        mAdapter.setDiversify(mSelectedIndex >= 0 && mSelectedIndex < mSections.size() && mSections.get(mSelectedIndex).getId() == MediaGroup.TYPE_HOME);
        appendGroupInner(group);
        ensureFilled();
    }

    /** Home: keep asking for more until there is plenty to scroll (the first pages are short and get thinned out). */
    private void ensureFilled() {
        if (mSelectedIndex < 0 || mSelectedIndex >= mSections.size() || mSections.get(mSelectedIndex).getId() != MediaGroup.TYPE_HOME) {
            return;
        }

        if (mAdapter.size() >= 60 || mFillTries >= 8) {
            return;
        }

        Video last = mAdapter.lastVideo();
        if (last != null) {
            mFillTries++;
            mList.post(() -> mPresenter.onScrollEnd(last));
        }
    }

    private void appendGroupInner(VideoGroup group) {
        BrowseSection section = group.getSection();
        // The TV home feed is a column of rows: here a row is a titled run of cards
        if (section != null && section.getType() == BrowseSection.TYPE_ROW && group.getPosition() >= 0) {
            mAdapter.addHeader(group.getTitle());
        }
        if (isMusicSelected()) {
            // Music: square covers in sideways rows, artists as round avatars
            if (isAllChannels(group.getVideos())) {
                mAdapter.addChannelsRow(group.getVideos());
            } else {
                mAdapter.addMusicRow(group.getVideos());
            }
            return;
        }
        if (isAllSeries(group.getVideos())) {
            mAdapter.addSeriesRow(group.getVideos());
            return;
        }
        if (isAllChannels(group.getVideos())) {
            // The first run of channels on Home is the user's favourites (round avatars); later ones are recommended series (cards)
            if (mAdapter.hasChannelsRow()) {
                mAdapter.addSeriesRow(group.getVideos());
            } else {
                mAdapter.addChannelsRow(group.getVideos());
            }
            return;
        }
        if (!isShortsSelected() && isAllShorts(group.getVideos())) {
            mAdapter.addShortsRow(group.getVideos());
            return;
        }

        mAdapter.addVideos(group.getVideos());
    }

    private static boolean isAllSeries(List<Video> videos) {
        if (videos == null || videos.isEmpty()) {
            return false;
        }

        for (Video video : videos) {
            if (!video.isPlaylistAsChannel()) {
                return false;
            }
        }

        return true;
    }

    private static boolean isAllChannels(List<Video> videos) {
        if (videos == null || videos.isEmpty()) {
            return false;
        }

        for (Video video : videos) {
            if (!video.isChannel() || video.isPlaylistAsChannel()) {
                return false;
            }
        }

        return true;
    }

    private static boolean isAllShorts(List<Video> videos) {
        if (videos == null || videos.isEmpty()) {
            return false;
        }

        for (Video video : videos) {
            if (!video.isShorts) {
                return false;
            }
        }

        return true;
    }

    @Override
    public void updateSection(SettingsGroup group) {
        if (group == null || group.getItems() == null) {
            return;
        }

        mAdapter.clear();
        mAdapter.addSettings(group.getItems());
    }

    @Override
    public void clearSection(BrowseSection section) {
        mAdapter.clear();
    }

    @Override
    public void selectSectionItem(int index) {
        mList.scrollToPosition(Math.max(index, 0));
    }

    @Override
    public void selectSectionItem(Video item) {
        // no focus on a phone
    }

    @Override
    public void showError(ErrorFragmentData data) {
        if (data == null) {
            hideError();
            return;
        }

        mErrorText.setText(data.getMessage());
        String action = data.getActionText();
        mErrorButton.setVisibility(TextUtils.isEmpty(action) ? View.GONE : View.VISIBLE);
        mErrorButton.setText(action);
        mErrorButton.setOnClickListener(v -> data.onAction());
        mErrorBox.setVisibility(View.VISIBLE);
    }

    private void hideError() {
        mErrorBox.setVisibility(View.GONE);
    }

    @Override
    public void showProgressBar(boolean show) {
        mProgress.setVisibility(show ? View.VISIBLE : View.GONE);
    }

    @Override
    public boolean isProgressBarShowing() {
        return mProgress.getVisibility() == View.VISIBLE;
    }

    @Override
    public void focusOnContent() {
        // no focus on a phone
    }

    @Override
    public void showHeaders() {
        // The Shorts player was closed: back to Home instead of an (unwanted) Shorts grid
        if (isShortsSelected()) {
            selectSectionById(MediaGroup.TYPE_HOME);
        }
    }

    @Override
    public void setContentHidden(boolean hidden) {
        mList.setVisibility(hidden ? View.INVISIBLE : View.VISIBLE);
    }

    @Override
    public void preloadCardThumbnails(List<Video> videos) {
        // Glide loads on bind
    }

    @Override
    public void preloadShorts(List<Video> videos) {
        // the Shorts player has its own preloading
    }

    @Override
    public boolean isEmpty() {
        return mAdapter.size() == 0;
    }

    @Override
    public void updateBadge() {
        // no badge on a phone
    }

    ///////////////////// lifecycle

    @Override
    protected void onResume() {
        mMiniHandler.post(mMiniTick);
        super.onResume();
        mPresenter.onViewResumed();
    }

    @Override
    protected void onPause() {
        mMiniHandler.removeCallbacks(mMiniTick);
        super.onPause();
        mPresenter.onViewPaused();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        mPresenter.onViewDestroyed();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
    }
}
