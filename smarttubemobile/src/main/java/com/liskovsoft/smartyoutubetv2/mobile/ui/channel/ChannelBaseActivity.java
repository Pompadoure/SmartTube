package com.liskovsoft.smartyoutubetv2.mobile.ui.channel;

import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.VideoGroup;
import com.liskovsoft.smartyoutubetv2.mobile.R;
import com.liskovsoft.smartyoutubetv2.mobile.ui.browse.VideoAdapter;
import com.liskovsoft.smartyoutubetv2.mobile.ui.main.MobileActivity;
import com.liskovsoft.smartyoutubetv2.mobile.ui.playback.PlaybackActivity;
import com.liskovsoft.smartyoutubetv2.mobile.ui.widgets.Ui;

import java.util.HashSet;
import java.util.Set;

/**
 * Channel / artist page: back arrow, title and the channel's rows as the same cards as the feed.
 */
abstract class ChannelBaseActivity extends MobileActivity {
    private RecyclerView mList;
    private GridLayoutManager mLayoutManager;
    protected VideoAdapter mAdapter;
    private ProgressBar mProgress;
    private TextView mTitle;
    private final Set<String> mSeenGroups = new HashSet<>();

    protected abstract void onPresenterAttach();

    protected abstract void onPresenterDetach();

    protected abstract void onPresenterFinish();

    protected abstract void onVideoClicked(Video video);

    protected abstract void onVideoLongClicked(Video video);

    protected abstract void onScrollEnd(Video last);

    protected abstract String channelTitle();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(createContent());
        onPresenterAttach();
    }

    private View createContent() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(getColor(R.color.yt_bg));
        root.setFitsSystemWindows(true);

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);

        ImageView back = new ImageView(this);
        back.setImageResource(R.drawable.ic_back);
        back.setPadding(Ui.dp(this, 14), Ui.dp(this, 14), Ui.dp(this, 14), Ui.dp(this, 14));
        back.setOnClickListener(v -> finish());
        bar.addView(back, new LinearLayout.LayoutParams(Ui.dp(this, 52), Ui.dp(this, 52)));

        mTitle = new TextView(this);
        mTitle.setTextColor(getColor(R.color.yt_text));
        mTitle.setTextSize(18);
        mTitle.setSingleLine(true);
        mTitle.setEllipsize(TextUtils.TruncateAt.END);
        bar.addView(mTitle, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        root.addView(bar);

        FrameLayout content = new FrameLayout(this);

        mAdapter = new VideoAdapter(new VideoAdapter.Listener() {
            @Override
            public void onClick(Video video) {
                // artists: music from a channel plays as sound only when it came from the music tab
                PlaybackActivity.setAudioModeForNextPlayback(PlaybackActivity.isMusicContext());
                onVideoClicked(video);
            }

            @Override
            public void onLongClick(Video video) {
                onVideoLongClicked(video);
            }
        });
        mList = new RecyclerView(this);
        mLayoutManager = new GridLayoutManager(this, getResources().getConfiguration().screenWidthDp >= 600 ? 2 : 1);
        mLayoutManager.setSpanSizeLookup(new GridLayoutManager.SpanSizeLookup() {
            @Override
            public int getSpanSize(int position) {
                return mAdapter.isHeader(position) ? mLayoutManager.getSpanCount() : 1;
            }
        });
        mList.setLayoutManager(mLayoutManager);
        mList.setAdapter(mAdapter);
        mList.setItemAnimator(null);
        mList.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(RecyclerView recyclerView, int dx, int dy) {
                if (dy > 0 && mLayoutManager.findLastVisibleItemPosition() >= mAdapter.size() - 6) {
                    Video last = mAdapter.lastVideo();
                    if (last != null) {
                        onScrollEnd(last);
                    }
                }
            }
        });
        content.addView(mList, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        mProgress = new ProgressBar(this);
        mProgress.setVisibility(View.GONE);
        content.addView(mProgress, new FrameLayout.LayoutParams(Ui.dp(this, 40), Ui.dp(this, 40), Gravity.CENTER));

        root.addView(content, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        return root;
    }

    public void update(VideoGroup group) {
        if (group == null || isDestroyed()) {
            return;
        }

        if (group.getAction() == VideoGroup.ACTION_REPLACE) {
            mAdapter.clear();
            mSeenGroups.clear();
        }

        String title = channelTitle();
        if (!TextUtils.isEmpty(title)) {
            mTitle.setText(title);
        }

        if (group.getVideos() == null || group.getVideos().isEmpty()) {
            return;
        }

        String key = group.getId() + ":" + group.getTitle();
        if (!TextUtils.isEmpty(group.getTitle()) && mSeenGroups.add(key)) {
            mAdapter.addHeader(group.getTitle());
        }

        mAdapter.addVideos(group.getVideos());
    }

    public void clear() {
        mAdapter.clear();
        mSeenGroups.clear();
    }

    public void showProgressBar(boolean show) {
        mProgress.setVisibility(show ? View.VISIBLE : View.GONE);
    }

    @Override
    public void finishReally() {
        super.finishReally();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        onPresenterDetach();
        if (isFinishing()) {
            onPresenterFinish();
        }
    }
}
