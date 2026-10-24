package com.liskovsoft.smartyoutubetv2.mobile.ui.music;

import android.app.Activity;
import android.content.Context;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.PagerSnapHelper;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.ChannelPresenter;
import com.liskovsoft.smartyoutubetv2.mobile.R;
import com.liskovsoft.smartyoutubetv2.mobile.ui.widgets.Ui;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The music start page, like the YouTube Music app: mood chips (with Poddar first), then rows from YouTube Music's
 * own start page: "Snabbval" as pages of four songs, and rows of albums, mixes, playlists, artists and videos.
 * Anonymous, so the rows are YouTube Music's general picks for the country, not personal ones.
 */
public final class MusicHomeView extends FrameLayout {
    public interface Listener {
        void onPodcasts();

        void onSearch();

        void onLibrary();

        void onSettings();
    }

    private static final long CACHE_MS = 15 * 60_000;
    private static MusicApi.Home sCache; // the unfiltered page, shown at once when the view comes back
    private static long sCacheTime;

    private final Activity mActivity;
    private final Listener mListener;
    private final ExecutorService mExecutor = Executors.newSingleThreadExecutor();
    private final List<Object> mRows = new ArrayList<>();
    private final List<MusicApi.Chip> mChips = new ArrayList<>();
    private final RecyclerView mList;
    private final RowAdapter mAdapter;
    private final ProgressBar mProgress;
    private final LinearLayout mHeader;
    private LinearLayout mChipRow;
    private MusicApi.Chip mSelectedChip;
    private String mContinuation;
    private boolean mLoadingMore;
    private int mRequest;
    private boolean mReleased;

    private static final class Message {
        final String text;

        Message(String text) {
            this.text = text;
        }
    }

    public MusicHomeView(Activity activity, Listener listener) {
        super(activity);
        mActivity = activity;
        mListener = listener;
        setBackgroundColor(activity.getColor(R.color.yt_bg));
        setClickable(true); // shown over the video feed: touches must not reach it

        mHeader = createHeader();
        mList = new RecyclerView(activity);
        mList.setLayoutManager(new LinearLayoutManager(activity));
        mList.setItemAnimator(null);
        mAdapter = new RowAdapter();
        mList.setAdapter(mAdapter);
        mList.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(RecyclerView recyclerView, int dx, int dy) {
                LinearLayoutManager manager = (LinearLayoutManager) recyclerView.getLayoutManager();
                if (dy > 0 && manager != null && manager.findLastVisibleItemPosition() >= mRows.size() - 3) {
                    loadMore();
                }
            }
        });
        addView(mList, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        mProgress = new ProgressBar(activity);
        mProgress.setVisibility(GONE);
        addView(mProgress, new LayoutParams(Ui.dp(activity, 40), Ui.dp(activity, 40), Gravity.CENTER));
    }

    /** Shown (again): the stored page at once if it is fresh, otherwise a new one. */
    public void onShown() {
        if (mSelectedChip == null && sCache != null && SystemClock.elapsedRealtime() - sCacheTime < CACHE_MS) {
            if (mRows.isEmpty()) {
                apply(sCache, false);
            }
            return;
        }
        load(mSelectedChip);
    }

    public void release() {
        mReleased = true;
        mExecutor.shutdownNow();
    }

    ///////////////////// header: logo, icons, chips

    private LinearLayout createHeader() {
        Context c = mActivity;
        LinearLayout header = new LinearLayout(c);
        header.setOrientation(LinearLayout.VERTICAL);

        LinearLayout bar = new LinearLayout(c);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(Ui.dp(c, 16), 0, Ui.dp(c, 4), 0);
        ImageView logo = new ImageView(c);
        logo.setImageResource(R.drawable.jotube_logo);
        LinearLayout.LayoutParams logoParams = new LinearLayout.LayoutParams(Ui.dp(c, 30), Ui.dp(c, 30));
        logoParams.setMarginEnd(Ui.dp(c, 6));
        bar.addView(logo, logoParams);
        TextView title = new TextView(c);
        title.setText("Musik");
        title.setTextColor(c.getColor(R.color.yt_text));
        title.setTextSize(22);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        bar.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        bar.addView(icon(R.drawable.ic_save, v -> mListener.onLibrary()));
        bar.addView(icon(R.drawable.ic_search, v -> mListener.onSearch()));
        bar.addView(icon(R.drawable.ic_settings, v -> mListener.onSettings()));
        bar.setMinimumHeight(Ui.dp(c, 56));
        header.addView(bar);

        HorizontalScrollView scroll = new HorizontalScrollView(c);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.setClipToPadding(false);
        scroll.setPadding(Ui.dp(c, 12), Ui.dp(c, 2), Ui.dp(c, 12), Ui.dp(c, 6));
        mChipRow = new LinearLayout(c);
        scroll.addView(mChipRow);
        header.addView(scroll);
        rebuildChips();
        return header;
    }

    private ImageView icon(int res, OnClickListener click) {
        ImageView view = new ImageView(mActivity);
        view.setImageResource(res);
        view.setPadding(Ui.dp(mActivity, 12), Ui.dp(mActivity, 12), Ui.dp(mActivity, 12), Ui.dp(mActivity, 12));
        view.setBackground(Ui.roundRipple());
        view.setOnClickListener(click);
        view.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(mActivity, 48), Ui.dp(mActivity, 48)));
        return view;
    }

    private void rebuildChips() {
        mChipRow.removeAllViews();
        mChipRow.addView(chip("Poddar", false, v -> mListener.onPodcasts()));
        for (MusicApi.Chip c : mChips) {
            boolean selected = mSelectedChip != null && TextUtils.equals(mSelectedChip.params, c.params);
            mChipRow.addView(chip(c.title, selected, v -> {
                mSelectedChip = selected ? null : c; // a second tap goes back to everything
                rebuildChips();
                load(mSelectedChip);
            }));
        }
    }

    private TextView chip(String label, boolean selected, OnClickListener click) {
        Context c = mActivity;
        TextView chip = new TextView(c);
        chip.setText(label);
        chip.setTextSize(14);
        chip.setTypeface(Typeface.DEFAULT_BOLD);
        chip.setTextColor(c.getColor(selected ? R.color.yt_bg : R.color.yt_text));
        chip.setBackground(Ui.pill(c, selected ? c.getColor(R.color.yt_chip_selected) : 0x1FFFFFFF));
        chip.setPadding(Ui.dp(c, 14), Ui.dp(c, 8), Ui.dp(c, 14), Ui.dp(c, 8));
        chip.setOnClickListener(click);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.setMarginEnd(Ui.dp(c, 8));
        chip.setLayoutParams(params);
        return chip;
    }

    @Override
    public void setPadding(int left, int top, int right, int bottom) {
        if (mHeader == null) { // from the View constructor
            super.setPadding(left, top, right, bottom);
            return;
        }
        // the top inset goes into the header (the page scrolls under the status bar like the video feed)
        mHeader.setPadding(left, top, right, 0);
        super.setPadding(0, 0, 0, bottom);
    }

    ///////////////////// data

    private void load(MusicApi.Chip chip) {
        final int request = ++mRequest;
        mContinuation = null;
        mLoadingMore = false;
        mRows.clear();
        mAdapter.notifyDataSetChanged();
        mProgress.setVisibility(VISIBLE);
        mExecutor.execute(() -> {
            MusicApi.Home home;
            try {
                home = MusicApi.home(chip);
            } catch (Exception e) {
                home = null;
            }
            final MusicApi.Home result = home;
            mActivity.runOnUiThread(() -> {
                if (mReleased || request != mRequest) {
                    return;
                }
                mProgress.setVisibility(GONE);
                if (result == null || result.shelves.isEmpty()) {
                    mRows.clear();
                    mRows.add(new Message(result == null ? "Kunde inte hämta musik. Tryck för att försöka igen." : "Inget att visa här just nu."));
                    mAdapter.notifyDataSetChanged();
                    return;
                }
                if (chip == null) {
                    sCache = result;
                    sCacheTime = SystemClock.elapsedRealtime();
                }
                apply(result, false);
            });
        });
    }

    private void apply(MusicApi.Home home, boolean append) {
        if (!append) {
            mRows.clear();
            if (!home.chips.isEmpty()) {
                mChips.clear();
                mChips.addAll(home.chips);
                rebuildChips();
            }
            mList.scrollToPosition(0);
        }
        mRows.addAll(home.shelves);
        mContinuation = home.continuation;
        mAdapter.notifyDataSetChanged();
    }

    private void loadMore() {
        if (mLoadingMore || mContinuation == null) {
            return;
        }
        mLoadingMore = true;
        final int request = mRequest;
        final String continuation = mContinuation;
        mExecutor.execute(() -> {
            MusicApi.Home home;
            try {
                home = MusicApi.homeMore(continuation);
            } catch (Exception e) {
                home = null;
            }
            final MusicApi.Home result = home;
            mActivity.runOnUiThread(() -> {
                if (mReleased || request != mRequest) {
                    return;
                }
                mLoadingMore = false;
                if (result == null) {
                    return; // the next scroll tries again
                }
                if (result.shelves.isEmpty()) {
                    mContinuation = result.continuation;
                    return;
                }
                apply(result, true);
                if (mSelectedChip == null && sCache != null && continuation.equals(sCache.continuation)) {
                    sCache.shelves.addAll(result.shelves);
                    sCache.continuation = result.continuation;
                }
            });
        });
    }

    ///////////////////// clicks

    private void onItemClicked(MusicApi.Item item, MusicApi.Shelf shelf) {
        switch (item.type) {
            case MusicApi.ARTIST:
                if (item.browseId != null && item.browseId.startsWith("UC")) {
                    ChannelPresenter.instance(mActivity).openChannel(item.browseId);
                } else {
                    Toast.makeText(mActivity, "Artistsidan finns inte som kanal", Toast.LENGTH_SHORT).show();
                }
                break;
            case MusicApi.ALBUM:
            case MusicApi.PLAYLIST:
                MusicSearchActivity.openPage(mActivity, item);
                break;
            case MusicApi.MIX:
                MusicMenu.playMix(mActivity, item.playlistId, item.title);
                break;
            default:
                if (item.videoId != null) {
                    MusicMenu.startRadio(mActivity, MusicMenu.toVideo(item), false);
                }
        }
    }

    private void onItemLongClicked(MusicApi.Item item, MusicApi.Shelf shelf) {
        if (item.type == MusicApi.SONG && item.videoId != null) {
            MusicMenu.show(mActivity, MusicMenu.toVideo(item), null, null);
        } else {
            onItemClicked(item, shelf);
        }
    }

    ///////////////////// list

    private static final int TYPE_HEADER = 0, TYPE_SHELF = 1, TYPE_MESSAGE = 2;

    private final class RowAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        @Override
        public int getItemCount() {
            return mRows.size() + 1;
        }

        @Override
        public int getItemViewType(int position) {
            if (position == 0) {
                return TYPE_HEADER;
            }
            return mRows.get(position - 1) instanceof Message ? TYPE_MESSAGE : TYPE_SHELF;
        }

        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            Context c = parent.getContext();
            View view;
            if (viewType == TYPE_HEADER) {
                if (mHeader.getParent() instanceof ViewGroup) {
                    ((ViewGroup) mHeader.getParent()).removeView(mHeader);
                }
                view = mHeader;
            } else if (viewType == TYPE_MESSAGE) {
                TextView message = new TextView(c);
                message.setTextColor(c.getColor(R.color.yt_text_secondary));
                message.setGravity(Gravity.CENTER);
                message.setPadding(Ui.dp(c, 32), Ui.dp(c, 80), Ui.dp(c, 32), Ui.dp(c, 80));
                view = message;
            } else {
                LinearLayout shelf = new LinearLayout(c);
                shelf.setOrientation(LinearLayout.VERTICAL);
                shelf.setPadding(0, Ui.dp(c, 14), 0, Ui.dp(c, 10));
                TextView strapline = new TextView(c);
                strapline.setTextColor(c.getColor(R.color.yt_text_secondary));
                strapline.setTextSize(12);
                strapline.setAllCaps(true);
                strapline.setPadding(Ui.dp(c, 16), 0, Ui.dp(c, 16), 0);
                shelf.addView(strapline);
                TextView title = new TextView(c);
                title.setTextColor(c.getColor(R.color.yt_text));
                title.setTextSize(22);
                title.setTypeface(Typeface.DEFAULT_BOLD);
                title.setMaxLines(1);
                title.setEllipsize(TextUtils.TruncateAt.END);
                title.setPadding(Ui.dp(c, 16), 0, Ui.dp(c, 16), Ui.dp(c, 12));
                shelf.addView(title);
                RecyclerView row = new RecyclerView(c);
                row.setLayoutManager(new LinearLayoutManager(c, LinearLayoutManager.HORIZONTAL, false));
                row.setClipToPadding(false);
                row.setPadding(Ui.dp(c, 16), 0, Ui.dp(c, 8), 0);
                row.setItemAnimator(null);
                row.setNestedScrollingEnabled(false);
                shelf.addView(row, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                view = shelf;
            }
            view.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            return new RecyclerView.ViewHolder(view) {
            };
        }

        @Override
        public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
            if (position == 0) {
                return;
            }
            Object row = mRows.get(position - 1);
            if (row instanceof Message) {
                TextView message = (TextView) holder.itemView;
                message.setText(((Message) row).text);
                message.setOnClickListener(v -> load(mSelectedChip));
                return;
            }
            MusicApi.Shelf shelf = (MusicApi.Shelf) row;
            LinearLayout box = (LinearLayout) holder.itemView;
            TextView strapline = (TextView) box.getChildAt(0);
            strapline.setText(shelf.strapline);
            strapline.setVisibility(shelf.strapline != null && !shelf.strapline.isEmpty() ? VISIBLE : GONE);
            ((TextView) box.getChildAt(1)).setText(shelf.title);
            RecyclerView list = (RecyclerView) box.getChildAt(2);
            Object snap = list.getTag();
            if (shelf.listRows && snap == null) {
                PagerSnapHelper helper = new PagerSnapHelper();
                helper.attachToRecyclerView(list);
                list.setTag(helper);
            } else if (!shelf.listRows && snap instanceof PagerSnapHelper) {
                ((PagerSnapHelper) snap).attachToRecyclerView(null);
                list.setTag(null);
            }
            list.setAdapter(shelf.listRows ? new ColumnAdapter(shelf) : new CardAdapter(shelf));
        }
    }

    /** "Snabbval": pages of four song rows, a bit narrower than the screen so the next page shows. */
    private final class ColumnAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        private static final int PER_COLUMN = 4;
        private final MusicApi.Shelf mShelf;

        ColumnAdapter(MusicApi.Shelf shelf) {
            mShelf = shelf;
        }

        @Override
        public int getItemCount() {
            return (mShelf.items.size() + PER_COLUMN - 1) / PER_COLUMN;
        }

        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            Context c = parent.getContext();
            LinearLayout column = new LinearLayout(c);
            column.setOrientation(LinearLayout.VERTICAL);
            int width = c.getResources().getDisplayMetrics().widthPixels - Ui.dp(c, 56);
            column.setLayoutParams(new RecyclerView.LayoutParams(width, ViewGroup.LayoutParams.WRAP_CONTENT));
            for (int i = 0; i < PER_COLUMN; i++) {
                column.addView(createSongRow(c));
            }
            return new RecyclerView.ViewHolder(column) {
            };
        }

        @Override
        public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
            LinearLayout column = (LinearLayout) holder.itemView;
            for (int i = 0; i < PER_COLUMN; i++) {
                int index = position * PER_COLUMN + i;
                View row = column.getChildAt(i);
                if (index >= mShelf.items.size()) {
                    row.setVisibility(INVISIBLE);
                    continue;
                }
                row.setVisibility(VISIBLE);
                bindSongRow(row, mShelf.items.get(index), mShelf);
            }
        }
    }

    private View createSongRow(Context c) {
        LinearLayout row = new LinearLayout(c);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, Ui.dp(c, 5), 0, Ui.dp(c, 5));
        ImageView cover = new ImageView(c);
        cover.setScaleType(ImageView.ScaleType.CENTER_CROP);
        cover.setBackgroundColor(c.getColor(R.color.yt_surface));
        Ui.makeRounded(cover, Ui.dp(c, 4));
        row.addView(cover, new LinearLayout.LayoutParams(Ui.dp(c, 52), Ui.dp(c, 52)));
        LinearLayout texts = new LinearLayout(c);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setPadding(Ui.dp(c, 12), 0, Ui.dp(c, 4), 0);
        TextView title = new TextView(c);
        title.setTextColor(c.getColor(R.color.yt_text));
        title.setTextSize(15);
        title.setMaxLines(1);
        title.setEllipsize(TextUtils.TruncateAt.END);
        texts.addView(title);
        TextView subtitle = new TextView(c);
        subtitle.setTextColor(c.getColor(R.color.yt_text_secondary));
        subtitle.setTextSize(13);
        subtitle.setMaxLines(1);
        subtitle.setEllipsize(TextUtils.TruncateAt.END);
        texts.addView(subtitle);
        row.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        ImageView more = new ImageView(c);
        more.setImageResource(R.drawable.ic_more);
        more.setPadding(Ui.dp(c, 12), Ui.dp(c, 12), Ui.dp(c, 12), Ui.dp(c, 12));
        more.setBackground(Ui.roundRipple());
        row.addView(more, new LinearLayout.LayoutParams(Ui.dp(c, 44), Ui.dp(c, 44)));
        return row;
    }

    private void bindSongRow(View view, MusicApi.Item item, MusicApi.Shelf shelf) {
        LinearLayout row = (LinearLayout) view;
        ImageView cover = (ImageView) row.getChildAt(0);
        LinearLayout texts = (LinearLayout) row.getChildAt(1);
        ((TextView) texts.getChildAt(0)).setText(item.title);
        ((TextView) texts.getChildAt(1)).setText(item.subtitle);
        loadImage(cover, item.thumb);
        row.setOnClickListener(v -> onItemClicked(item, shelf));
        row.setOnLongClickListener(v -> {
            onItemLongClicked(item, shelf);
            return true;
        });
        row.getChildAt(2).setOnClickListener(v -> onItemLongClicked(item, shelf));
    }

    /** Cards: square for albums, mixes and playlists, round for artists, wide for videos. */
    private final class CardAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        private final MusicApi.Shelf mShelf;

        CardAdapter(MusicApi.Shelf shelf) {
            mShelf = shelf;
        }

        @Override
        public int getItemCount() {
            return mShelf.items.size();
        }

        @Override
        public int getItemViewType(int position) {
            return mShelf.items.get(position).wide ? 1 : 0;
        }

        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            Context c = parent.getContext();
            boolean wide = viewType == 1;
            int width = Ui.dp(c, wide ? 256 : 150);
            int height = wide ? width * 9 / 16 : width;
            LinearLayout card = new LinearLayout(c);
            card.setOrientation(LinearLayout.VERTICAL);
            RecyclerView.LayoutParams params = new RecyclerView.LayoutParams(width, ViewGroup.LayoutParams.WRAP_CONTENT);
            params.setMarginEnd(Ui.dp(c, 12));
            card.setLayoutParams(params);
            ImageView cover = new ImageView(c);
            cover.setScaleType(ImageView.ScaleType.CENTER_CROP);
            cover.setBackgroundColor(c.getColor(R.color.yt_surface));
            card.addView(cover, new LinearLayout.LayoutParams(width, height));
            TextView title = new TextView(c);
            title.setTextColor(c.getColor(R.color.yt_text));
            title.setTextSize(14);
            title.setMaxLines(2);
            title.setEllipsize(TextUtils.TruncateAt.END);
            title.setPadding(0, Ui.dp(c, 8), 0, 0);
            card.addView(title);
            TextView subtitle = new TextView(c);
            subtitle.setTextColor(c.getColor(R.color.yt_text_secondary));
            subtitle.setTextSize(13);
            subtitle.setMaxLines(2);
            subtitle.setEllipsize(TextUtils.TruncateAt.END);
            subtitle.setPadding(0, Ui.dp(c, 2), 0, 0);
            card.addView(subtitle);
            return new RecyclerView.ViewHolder(card) {
            };
        }

        @Override
        public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
            MusicApi.Item item = mShelf.items.get(position);
            LinearLayout card = (LinearLayout) holder.itemView;
            ImageView cover = (ImageView) card.getChildAt(0);
            TextView title = (TextView) card.getChildAt(1);
            TextView subtitle = (TextView) card.getChildAt(2);
            boolean artist = item.type == MusicApi.ARTIST;
            if (artist) {
                Ui.makeCircle(cover);
            } else {
                Ui.makeRounded(cover, Ui.dp(card.getContext(), 6));
            }
            title.setGravity(artist ? Gravity.CENTER_HORIZONTAL : Gravity.START);
            subtitle.setGravity(artist ? Gravity.CENTER_HORIZONTAL : Gravity.START);
            title.setText(item.title);
            subtitle.setText(item.subtitle);
            subtitle.setVisibility(item.subtitle != null && !item.subtitle.isEmpty() ? VISIBLE : GONE);
            loadImage(cover, item.thumb);
            card.setOnClickListener(v -> onItemClicked(item, mShelf));
            card.setOnLongClickListener(v -> {
                onItemLongClicked(item, mShelf);
                return true;
            });
        }
    }

    private static void loadImage(ImageView view, String url) {
        if (url != null) {
            Glide.with(view).load(url).into(view);
        } else {
            Glide.with(view).clear(view);
            view.setImageDrawable(null);
        }
    }
}
