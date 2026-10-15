package com.liskovsoft.smartyoutubetv2.mobile.ui.music;

import android.graphics.Typeface;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Playlist;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.ChannelPresenter;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.PlaybackPresenter;
import com.liskovsoft.smartyoutubetv2.mobile.R;
import com.liskovsoft.smartyoutubetv2.mobile.ui.main.MobileActivity;
import com.liskovsoft.smartyoutubetv2.mobile.ui.playback.PlaybackActivity;
import com.liskovsoft.smartyoutubetv2.mobile.ui.widgets.Ui;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Search in the music part: only YouTube Music results (songs, albums, artists, playlists), like the YouTube Music app.
 */
public class MusicSearchActivity extends MobileActivity {
    private static final MusicApi.Filter[] FILTERS = {MusicApi.Filter.SONGS, MusicApi.Filter.ALBUMS, MusicApi.Filter.ARTISTS, MusicApi.Filter.PLAYLISTS, MusicApi.Filter.VIDEOS};
    private static final String[] FILTER_TITLES = {"Låtar", "Album", "Artister", "Spellistor", "Videor"};

    private final ExecutorService mExecutor = Executors.newSingleThreadExecutor();
    private final List<MusicApi.Item> mItems = new ArrayList<>();
    private final List<TextView> mChips = new ArrayList<>();
    private EditText mField;
    private ProgressBar mProgress;
    private TextView mMessage;
    private TextView mTitle;
    private RecyclerView mList;
    private RecyclerView.Adapter<RecyclerView.ViewHolder> mAdapter;
    private int mFilterIndex;
    private int mRequest;
    private boolean mTracksMode; // inside an album / playlist
    private String mLastQuery;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(createContent());
        mField.requestFocus();
    }

    private View createContent() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(getColor(R.color.yt_bg));
        root.setFitsSystemWindows(true);

        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);

        ImageView back = new ImageView(this);
        back.setImageResource(R.drawable.ic_back);
        back.setPadding(Ui.dp(this, 14), Ui.dp(this, 14), Ui.dp(this, 14), Ui.dp(this, 14));
        back.setOnClickListener(v -> onBackPressed());
        bar.addView(back, new LinearLayout.LayoutParams(Ui.dp(this, 52), Ui.dp(this, 52)));

        mField = new EditText(this);
        mField.setHint("Sök musik");
        mField.setHintTextColor(getColor(R.color.yt_text_secondary));
        mField.setTextColor(getColor(R.color.yt_text));
        mField.setTextSize(16);
        mField.setSingleLine(true);
        mField.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        mField.setBackgroundResource(R.drawable.bg_chip);
        mField.setPadding(Ui.dp(this, 16), 0, Ui.dp(this, 16), 0);
        mField.setOnEditorActionListener((v, actionId, event) -> {
            search(mField.getText().toString());
            return true;
        });
        LinearLayout.LayoutParams fieldParams = new LinearLayout.LayoutParams(0, Ui.dp(this, 40), 1f);
        fieldParams.setMarginEnd(Ui.dp(this, 4));
        bar.addView(mField, fieldParams);

        ImageView go = new ImageView(this);
        go.setImageResource(R.drawable.ic_search);
        go.setPadding(Ui.dp(this, 14), Ui.dp(this, 14), Ui.dp(this, 14), Ui.dp(this, 14));
        go.setOnClickListener(v -> search(mField.getText().toString()));
        bar.addView(go, new LinearLayout.LayoutParams(Ui.dp(this, 52), Ui.dp(this, 52)));
        root.addView(bar);

        HorizontalScrollView scroll = new HorizontalScrollView(this);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.setPadding(Ui.dp(this, 12), Ui.dp(this, 4), Ui.dp(this, 12), Ui.dp(this, 8));
        scroll.setClipToPadding(false);
        LinearLayout chipRow = new LinearLayout(this);
        for (int i = 0; i < FILTER_TITLES.length; i++) {
            final int index = i;
            TextView chip = new TextView(this);
            chip.setText(FILTER_TITLES[i]);
            chip.setTextSize(14);
            chip.setTypeface(Typeface.DEFAULT_BOLD);
            chip.setPadding(Ui.dp(this, 12), Ui.dp(this, 7), Ui.dp(this, 12), Ui.dp(this, 7));
            chip.setOnClickListener(v -> {
                mFilterIndex = index;
                updateChips();
                if (!TextUtils.isEmpty(mField.getText())) {
                    search(mField.getText().toString());
                }
            });
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            params.setMarginEnd(Ui.dp(this, 8));
            chipRow.addView(chip, params);
            mChips.add(chip);
        }
        scroll.addView(chipRow);
        root.addView(scroll);
        updateChips();

        mTitle = new TextView(this);
        mTitle.setTextColor(getColor(R.color.yt_text));
        mTitle.setTextSize(16);
        mTitle.setTypeface(Typeface.DEFAULT_BOLD);
        mTitle.setPadding(Ui.dp(this, 16), Ui.dp(this, 4), Ui.dp(this, 16), Ui.dp(this, 8));
        mTitle.setVisibility(View.GONE);
        root.addView(mTitle);

        FrameLayout content = new FrameLayout(this);
        mList = new RecyclerView(this);
        mList.setLayoutManager(new LinearLayoutManager(this));
        mAdapter = new RowAdapter();
        mList.setAdapter(mAdapter);
        mList.setItemAnimator(null);
        mList.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(RecyclerView recyclerView, int newState) {
                if (newState == RecyclerView.SCROLL_STATE_DRAGGING) {
                    hideKeyboard();
                }
            }
        });
        content.addView(mList, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        mProgress = new ProgressBar(this);
        mProgress.setVisibility(View.GONE);
        content.addView(mProgress, new FrameLayout.LayoutParams(Ui.dp(this, 40), Ui.dp(this, 40), Gravity.CENTER));

        mMessage = new TextView(this);
        mMessage.setTextColor(getColor(R.color.yt_text_secondary));
        mMessage.setGravity(Gravity.CENTER);
        mMessage.setPadding(Ui.dp(this, 32), 0, Ui.dp(this, 32), 0);
        mMessage.setVisibility(View.GONE);
        content.addView(mMessage, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));

        root.addView(content, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        return root;
    }

    private void updateChips() {
        for (int i = 0; i < mChips.size(); i++) {
            boolean selected = i == mFilterIndex;
            TextView chip = mChips.get(i);
            chip.setBackgroundResource(selected ? R.drawable.bg_chip_selected : R.drawable.bg_chip);
            chip.setTextColor(getColor(selected ? R.color.yt_bg : R.color.yt_text));
        }
    }

    private void search(String query) {
        if (TextUtils.isEmpty(query) || TextUtils.isEmpty(query.trim())) {
            return;
        }

        hideKeyboard();
        mTracksMode = false;
        mTitle.setVisibility(View.GONE);
        mLastQuery = query.trim();
        final MusicApi.Filter filter = FILTERS[mFilterIndex];
        load(() -> MusicApi.search(mLastQuery, filter), "Inga träffar");
    }

    private void openTracks(MusicApi.Item album) {
        mTracksMode = true;
        mTitle.setText(album.title);
        mTitle.setVisibility(View.VISIBLE);
        load(() -> MusicApi.browse(album.browseId), "Hittade inga låtar");
    }

    private interface Loader {
        List<MusicApi.Item> run() throws Exception;
    }

    private void load(Loader loader, String emptyText) {
        final int request = ++mRequest;
        mItems.clear();
        mAdapter.notifyDataSetChanged();
        mMessage.setVisibility(View.GONE);
        mProgress.setVisibility(View.VISIBLE);

        mExecutor.execute(() -> {
            List<MusicApi.Item> result = null;
            String error = null;
            try {
                result = loader.run();
            } catch (Exception e) {
                error = "Kunde inte hämta musik. Kontrollera nätverket och försök igen.";
            }

            final List<MusicApi.Item> items = result;
            final String message = error != null ? error : (items == null || items.isEmpty() ? emptyText : null);
            runOnUiThread(() -> {
                if (isDestroyed() || request != mRequest) {
                    return;
                }
                mProgress.setVisibility(View.GONE);
                if (items != null) {
                    mItems.addAll(items);
                }
                mAdapter.notifyDataSetChanged();
                mMessage.setText(message);
                mMessage.setVisibility(message != null ? View.VISIBLE : View.GONE);
            });
        });
    }

    private void onItemClicked(MusicApi.Item item) {
        hideKeyboard();
        switch (item.type) {
            case MusicApi.ARTIST:
                ChannelPresenter.instance(this).openChannel(item.browseId);
                break;
            case MusicApi.ALBUM:
            case MusicApi.PLAYLIST:
                openTracks(item);
                break;
            default:
                play(item);
        }
    }

    /** The song plays as sound, the songs after it in the list become the queue (music only). */
    private void play(MusicApi.Item clicked) {
        List<Video> queue = new ArrayList<>();
        Video start = null;
        for (MusicApi.Item item : mItems) {
            if (item.type != MusicApi.SONG || item.videoId == null) {
                continue;
            }
            Video video = Video.from(item.videoId);
            video.title = item.title;
            video.author = item.author;
            video.secondTitle = item.subtitle;
            video.cardImageUrl = item.thumb;
            queue.add(video);
            if (item == clicked) {
                start = video;
            }
        }
        if (start == null) {
            return;
        }

        Playlist playlist = Playlist.instance();
        playlist.clear();
        playlist.addAll(queue);
        playlist.setCurrent(start);

        PlaybackActivity.setMusicContext(true);
        PlaybackActivity.setAudioModeForNextPlayback(true);
        PlaybackPresenter.instance(this).openVideo(start);
    }

    private void hideKeyboard() {
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null) {
            imm.hideSoftInputFromWindow(mField.getWindowToken(), 0);
        }
        mField.clearFocus();
    }

    @Override
    public void onBackPressed() {
        if (mTracksMode && mLastQuery != null) {
            search(mLastQuery); // back from an album to the results
            return;
        }
        super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        mExecutor.shutdownNow();
    }

    private class RowAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            LinearLayout row = new LinearLayout(parent.getContext());
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(Ui.dp(parent.getContext(), 16), Ui.dp(parent.getContext(), 6), Ui.dp(parent.getContext(), 16), Ui.dp(parent.getContext(), 6));
            row.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            ImageView cover = new ImageView(parent.getContext());
            cover.setScaleType(ImageView.ScaleType.CENTER_CROP);
            row.addView(cover, new LinearLayout.LayoutParams(Ui.dp(parent.getContext(), 56), Ui.dp(parent.getContext(), 56)));

            LinearLayout texts = new LinearLayout(parent.getContext());
            texts.setOrientation(LinearLayout.VERTICAL);
            texts.setPadding(Ui.dp(parent.getContext(), 12), 0, 0, 0);
            TextView title = new TextView(parent.getContext());
            title.setTextColor(getColor(R.color.yt_text));
            title.setTextSize(15);
            title.setMaxLines(1);
            title.setEllipsize(TextUtils.TruncateAt.END);
            texts.addView(title);
            TextView subtitle = new TextView(parent.getContext());
            subtitle.setTextColor(getColor(R.color.yt_text_secondary));
            subtitle.setTextSize(13);
            subtitle.setMaxLines(1);
            subtitle.setEllipsize(TextUtils.TruncateAt.END);
            texts.addView(subtitle);
            row.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            return new RecyclerView.ViewHolder(row) {
            };
        }

        @Override
        public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
            MusicApi.Item item = mItems.get(position);
            LinearLayout row = (LinearLayout) holder.itemView;
            ImageView cover = (ImageView) row.getChildAt(0);
            LinearLayout texts = (LinearLayout) row.getChildAt(1);
            ((TextView) texts.getChildAt(0)).setText(item.title);
            ((TextView) texts.getChildAt(1)).setText(item.subtitle);

            if (item.type == MusicApi.ARTIST) {
                Ui.makeCircle(cover);
            } else {
                Ui.makeRounded(cover, Ui.dp(MusicSearchActivity.this, 6));
            }
            if (item.thumb != null) {
                Glide.with(cover).load(item.thumb).into(cover);
            } else {
                cover.setImageDrawable(null);
            }
            row.setOnClickListener(v -> onItemClicked(item));
        }

        @Override
        public int getItemCount() {
            return mItems.size();
        }
    }
}
