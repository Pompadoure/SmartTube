package com.liskovsoft.smartyoutubetv2.mobile.ui.music;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
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
import android.widget.Toast;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.ChannelPresenter;
import com.liskovsoft.smartyoutubetv2.mobile.R;
import com.liskovsoft.smartyoutubetv2.mobile.ui.main.MobileActivity;
import com.liskovsoft.smartyoutubetv2.mobile.ui.widgets.Ui;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Search in the music part, like the YouTube Music app: suggestions and history while typing, then "Alla" (a big
 * top result and everything mixed: albums, songs, artists, playlists) or one kind at a time. An album or a playlist
 * opens as a page with its cover, Spela / Blanda / Spara and the tracks. The home page opens pages here too
 * ({@link #openPage}).
 */
public class MusicSearchActivity extends MobileActivity {
    private static final String EXTRA_BROWSE_ID = "browse_id";
    private static final String EXTRA_TITLE = "title";
    private static final String EXTRA_THUMB = "thumb";
    private static final String PREFS = "music_search";
    private static final String KEY_HISTORY = "history";
    private static final int MAX_HISTORY = 20;

    private static final MusicApi.Filter[] FILTERS = {null, MusicApi.Filter.SONGS, MusicApi.Filter.ALBUMS, MusicApi.Filter.ARTISTS, MusicApi.Filter.PLAYLISTS, MusicApi.Filter.VIDEOS};
    private static final String[] FILTER_TITLES = {"Alla", "Låtar", "Album", "Artister", "Spellistor", "Videor"};

    private static final int TYPE_ROW = 0, TYPE_TOP = 1, TYPE_SUGGESTION = 2, TYPE_PAGE_HEADER = 3, TYPE_LABEL = 4;

    /** A suggestion or a history entry while typing. */
    private static final class Suggestion {
        final String text;
        final boolean history;

        Suggestion(String text, boolean history) {
            this.text = text;
            this.history = history;
        }
    }

    private static final class Top {
        final MusicApi.Item item;

        Top(MusicApi.Item item) {
            this.item = item;
        }
    }

    private final ExecutorService mExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService mSuggestExecutor = Executors.newSingleThreadExecutor();
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final List<Object> mRows = new ArrayList<>();
    private final List<MusicApi.Item> mSongs = new ArrayList<>(); // the playable songs of what is shown
    private final List<TextView> mChips = new ArrayList<>();
    private EditText mField;
    private View mRoot; // takes the focus from the field (on API < 28 clearFocus gives it straight back)
    private ImageView mClear;
    private View mChipScroll;
    private ProgressBar mProgress;
    private TextView mMessage;
    private RecyclerView mList;
    private RecyclerView.Adapter<RecyclerView.ViewHolder> mAdapter;
    private int mFilterIndex;
    private int mRequest;
    private int mSuggestRequest;
    private boolean mPageMode; // an album / playlist
    private boolean mOpenedAsPage; // started from the home page: back closes
    private String mLastQuery;
    private MusicApi.Page mPage;
    private MusicApi.Item mPageItem;
    private boolean mIgnoreText;

    /** Opens an album or a playlist page ("MPREb_...", "VL..."). */
    public static void openPage(Activity activity, MusicApi.Item item) {
        if (item == null || item.browseId == null) {
            return;
        }
        activity.startActivity(new Intent(activity, MusicSearchActivity.class)
                .putExtra(EXTRA_BROWSE_ID, item.browseId)
                .putExtra(EXTRA_TITLE, item.title)
                .putExtra(EXTRA_THUMB, item.thumb));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(createContent());

        String browseId = getIntent().getStringExtra(EXTRA_BROWSE_ID);
        if (browseId != null) {
            mOpenedAsPage = true;
            mRoot.requestFocus(); // not the search field: it would show the suggestions over the page
            MusicApi.Item item = new MusicApi.Item();
            item.type = browseId.startsWith("MPRE") ? MusicApi.ALBUM : MusicApi.PLAYLIST;
            item.browseId = browseId;
            item.title = getIntent().getStringExtra(EXTRA_TITLE);
            item.thumb = getIntent().getStringExtra(EXTRA_THUMB);
            openPage(item);
        } else {
            mField.requestFocus();
            showSuggestions("");
        }
    }

    private View createContent() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(getColor(R.color.yt_bg));
        root.setFitsSystemWindows(true);
        root.setFocusableInTouchMode(true);
        mRoot = root;

        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(Ui.dp(this, 4), Ui.dp(this, 6), Ui.dp(this, 12), Ui.dp(this, 6));

        // the search field is one rounded pill with the back arrow inside, like the app
        LinearLayout pill = new LinearLayout(this);
        pill.setGravity(Gravity.CENTER_VERTICAL);
        pill.setBackground(Ui.pill(this, getColor(R.color.yt_chip)));

        ImageView back = new ImageView(this);
        back.setImageResource(R.drawable.ic_back);
        back.setPadding(Ui.dp(this, 12), Ui.dp(this, 12), Ui.dp(this, 12), Ui.dp(this, 12));
        back.setOnClickListener(v -> onBackPressed());
        pill.addView(back, new LinearLayout.LayoutParams(Ui.dp(this, 48), Ui.dp(this, 48)));

        mField = new EditText(this);
        mField.setHint("Sök låtar, album, artister");
        mField.setHintTextColor(getColor(R.color.yt_text_secondary));
        mField.setTextColor(getColor(R.color.yt_text));
        mField.setTextSize(17);
        mField.setSingleLine(true);
        mField.setBackground(null);
        mField.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        mField.setPadding(Ui.dp(this, 4), 0, Ui.dp(this, 4), 0);
        mField.setOnEditorActionListener((v, actionId, event) -> {
            search(mField.getText().toString());
            return true;
        });
        mField.setOnFocusChangeListener((v, focused) -> {
            if (focused && !mIgnoreText) {
                showSuggestions(mField.getText().toString());
            }
        });
        mField.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                mClear.setVisibility(s.length() > 0 ? View.VISIBLE : View.GONE);
                if (!mIgnoreText && mField.hasFocus()) {
                    showSuggestions(s.toString());
                }
            }
        });
        pill.addView(mField, new LinearLayout.LayoutParams(0, Ui.dp(this, 48), 1f));

        mClear = new ImageView(this);
        mClear.setImageResource(R.drawable.ic_close_thin);
        mClear.setImageTintList(android.content.res.ColorStateList.valueOf(getColor(R.color.yt_text_secondary)));
        mClear.setPadding(Ui.dp(this, 14), Ui.dp(this, 14), Ui.dp(this, 14), Ui.dp(this, 14));
        mClear.setVisibility(View.GONE);
        mClear.setOnClickListener(v -> {
            mField.setText("");
            mField.requestFocus();
            InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (imm != null) {
                imm.showSoftInput(mField, 0);
            }
        });
        pill.addView(mClear, new LinearLayout.LayoutParams(Ui.dp(this, 48), Ui.dp(this, 48)));

        bar.addView(pill, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        root.addView(bar);

        HorizontalScrollView scroll = new HorizontalScrollView(this);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.setPadding(Ui.dp(this, 12), Ui.dp(this, 2), Ui.dp(this, 12), Ui.dp(this, 8));
        scroll.setClipToPadding(false);
        LinearLayout chipRow = new LinearLayout(this);
        for (int i = 0; i < FILTER_TITLES.length; i++) {
            final int index = i;
            TextView chip = new TextView(this);
            chip.setText(FILTER_TITLES[i]);
            chip.setTextSize(14);
            chip.setTypeface(Typeface.DEFAULT_BOLD);
            chip.setPadding(Ui.dp(this, 14), Ui.dp(this, 8), Ui.dp(this, 14), Ui.dp(this, 8));
            chip.setOnClickListener(v -> {
                mFilterIndex = index;
                updateChips();
                String text = mLastQuery != null ? mLastQuery : mField.getText().toString();
                if (!TextUtils.isEmpty(text)) {
                    search(text);
                }
            });
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            params.setMarginEnd(Ui.dp(this, 8));
            chipRow.addView(chip, params);
            mChips.add(chip);
        }
        scroll.addView(chipRow);
        mChipScroll = scroll;
        mChipScroll.setVisibility(View.GONE);
        root.addView(scroll);
        updateChips();

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
            chip.setBackground(Ui.pill(this, getColor(selected ? R.color.yt_chip_selected : R.color.yt_chip)));
            chip.setTextColor(getColor(selected ? R.color.yt_bg : R.color.yt_text));
        }
    }

    ///////////////////// suggestions and history

    private List<String> history() {
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        String raw = prefs.getString(KEY_HISTORY, "");
        List<String> result = new ArrayList<>();
        for (String line : raw.split("\n")) {
            if (!line.trim().isEmpty()) {
                result.add(line);
            }
        }
        return result;
    }

    private void remember(String query) {
        List<String> history = history();
        history.remove(query);
        history.add(0, query);
        while (history.size() > MAX_HISTORY) {
            history.remove(history.size() - 1);
        }
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(KEY_HISTORY, TextUtils.join("\n", history)).apply();
    }

    private void forget(String query) {
        List<String> history = history();
        history.remove(query);
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(KEY_HISTORY, TextUtils.join("\n", history)).apply();
    }

    private final Runnable mFetchSuggestions = this::fetchSuggestions;

    private void showSuggestions(String text) {
        mRequest++; // a search still loading must not replace the suggestions
        mProgress.setVisibility(View.GONE);
        mMessage.setVisibility(View.GONE);
        mChipScroll.setVisibility(View.GONE);
        mPageMode = false;

        String query = text.trim();
        List<Object> rows = new ArrayList<>();
        for (String entry : history()) {
            if (query.isEmpty() || entry.toLowerCase().startsWith(query.toLowerCase())) {
                rows.add(new Suggestion(entry, true));
            }
            if (rows.size() >= (query.isEmpty() ? MAX_HISTORY : 3)) {
                break;
            }
        }
        setRows(rows);

        mHandler.removeCallbacks(mFetchSuggestions);
        if (!query.isEmpty()) {
            mHandler.postDelayed(mFetchSuggestions, 200);
        }
    }

    private void fetchSuggestions() {
        final String query = mField.getText().toString().trim();
        if (query.isEmpty()) {
            return;
        }
        final int request = ++mSuggestRequest;
        mSuggestExecutor.execute(() -> {
            List<String> result;
            try {
                result = MusicApi.suggestions(query);
            } catch (Exception e) {
                result = Collections.emptyList();
            }
            final List<String> suggestions = result;
            runOnUiThread(() -> {
                if (isDestroyed() || request != mSuggestRequest || !mField.hasFocus() || mPageMode
                        || !query.equals(mField.getText().toString().trim())) {
                    return;
                }
                List<Object> rows = new ArrayList<>();
                List<String> shown = new ArrayList<>();
                for (Object row : mRows) {
                    if (row instanceof Suggestion && ((Suggestion) row).history) {
                        rows.add(row);
                        shown.add(((Suggestion) row).text);
                    }
                }
                for (String text : suggestions) {
                    if (!shown.contains(text)) {
                        rows.add(new Suggestion(text, false));
                    }
                }
                setRows(rows);
            });
        });
    }

    ///////////////////// search, pages

    private void search(String query) {
        if (TextUtils.isEmpty(query) || TextUtils.isEmpty(query.trim())) {
            return;
        }

        mHandler.removeCallbacks(mFetchSuggestions);
        mSuggestRequest++;
        hideKeyboard();
        mPageMode = false;
        mLastQuery = query.trim();
        remember(mLastQuery);
        if (!mLastQuery.equals(mField.getText().toString())) {
            mIgnoreText = true;
            mField.setText(mLastQuery);
            mField.setSelection(mLastQuery.length());
            mIgnoreText = false;
        }
        mChipScroll.setVisibility(View.VISIBLE);
        final MusicApi.Filter filter = FILTERS[mFilterIndex];
        final String text = mLastQuery;
        load(() -> {
            List<Object> rows = new ArrayList<>();
            if (filter == null) {
                MusicApi.SearchResult result = MusicApi.searchAll(text);
                if (result.top != null) {
                    rows.add(new Top(result.top));
                }
                rows.addAll(result.items);
            } else {
                rows.addAll(MusicApi.search(text, filter));
            }
            return rows;
        }, "Inga träffar");
    }

    private void openPage(MusicApi.Item album) {
        hideKeyboard();
        mPageMode = true;
        mPageItem = album;
        mPage = null;
        mChipScroll.setVisibility(View.GONE);
        load(() -> {
            MusicApi.Page page = MusicApi.page(album.browseId);
            if (page.title == null) {
                page.title = album.title;
            }
            if (page.thumb == null) {
                page.thumb = album.thumb;
            }
            List<Object> rows = new ArrayList<>();
            rows.add(page);
            rows.addAll(page.tracks);
            return rows;
        }, "Hittade inga låtar");
    }

    private interface Loader {
        List<Object> run() throws Exception;
    }

    private void load(Loader loader, String emptyText) {
        final int request = ++mRequest;
        setRows(new ArrayList<>());
        mMessage.setVisibility(View.GONE);
        mProgress.setVisibility(View.VISIBLE);

        mExecutor.execute(() -> {
            List<Object> result = null;
            String error = null;
            try {
                result = loader.run();
            } catch (Exception e) {
                error = "Kunde inte hämta musik. Kontrollera nätverket och försök igen.";
            }

            final List<Object> rows = result;
            final String failure = error;
            runOnUiThread(() -> {
                if (isDestroyed() || request != mRequest) {
                    return;
                }
                mProgress.setVisibility(View.GONE);
                boolean empty = true;
                for (Object row : rows != null ? rows : new ArrayList<>()) {
                    if (row instanceof MusicApi.Item || row instanceof Top) {
                        empty = false;
                    }
                    if (row instanceof MusicApi.Page) {
                        mPage = (MusicApi.Page) row;
                    }
                }
                setRows(rows != null ? rows : new ArrayList<>());
                String message = failure != null ? failure : empty ? emptyText : null;
                mMessage.setText(message);
                mMessage.setVisibility(message != null ? View.VISIBLE : View.GONE);
            });
        });
    }

    private void setRows(List<Object> rows) {
        mRows.clear();
        mRows.addAll(rows);
        mSongs.clear();
        for (Object row : rows) {
            if (row instanceof MusicApi.Item && ((MusicApi.Item) row).type == MusicApi.SONG && ((MusicApi.Item) row).videoId != null) {
                mSongs.add((MusicApi.Item) row);
            }
        }
        mAdapter.notifyDataSetChanged();
        mList.scrollToPosition(0);
    }

    ///////////////////// actions

    private void onItemClicked(MusicApi.Item item) {
        hideKeyboard();
        switch (item.type) {
            case MusicApi.ARTIST:
                if (item.browseId == null || !item.browseId.startsWith("UC")) {
                    Toast.makeText(this, "Artistsidan finns inte som kanal", Toast.LENGTH_SHORT).show();
                    break;
                }
                ChannelPresenter.instance(this).openChannel(item.browseId);
                break;
            case MusicApi.ALBUM:
            case MusicApi.PLAYLIST:
                openPage(item);
                break;
            case MusicApi.MIX:
                MusicMenu.playMix(this, item.playlistId, item.title);
                break;
            default:
                play(item);
        }
    }

    /** In a page: the tracks from this one. In the results: the song and its radio, like the app. */
    private void play(MusicApi.Item clicked) {
        if (mPageMode) {
            List<Video> queue = MusicMenu.toVideos(mSongs);
            int start = mSongs.indexOf(clicked);
            MusicMenu.playQueue(this, queue, Math.max(0, start), mPage != null ? mPage.title : null);
            return;
        }
        MusicMenu.startRadio(this, MusicMenu.toVideo(clicked), false);
    }

    private void playPage(boolean shuffle) {
        if (mSongs.isEmpty()) {
            return;
        }
        List<Video> queue = MusicMenu.toVideos(mSongs);
        if (shuffle) {
            Collections.shuffle(queue);
        }
        MusicMenu.playQueue(this, queue, 0, mPage != null ? mPage.title : null);
    }

    /** "Spara": the album as one of the own playlists. */
    private void savePage() {
        if (mPage == null || mSongs.isEmpty()) {
            return;
        }
        MusicLibrary.Playlist created = MusicLibrary.create(this, mPage.title != null ? mPage.title : "Album");
        for (MusicApi.Item song : mSongs) {
            MusicLibrary.add(this, created.id, MusicMenu.toVideo(song));
        }
        Toast.makeText(this, "Sparad som spellistan " + created.name, Toast.LENGTH_SHORT).show();
    }

    private void showItemMenu(MusicApi.Item item) {
        if (item.type == MusicApi.SONG && item.videoId != null) {
            MusicMenu.show(this, MusicMenu.toVideo(item), null, null);
        } else if (item.type == MusicApi.ALBUM || item.type == MusicApi.PLAYLIST) {
            openPage(item);
        } else {
            onItemClicked(item);
        }
    }

    private void hideKeyboard() {
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null) {
            imm.hideSoftInputFromWindow(mField.getWindowToken(), 0);
        }
        if (mField.hasFocus()) {
            mRoot.requestFocus();
        }
    }

    @Override
    public void onBackPressed() {
        if (mPageMode && !mOpenedAsPage && mLastQuery != null) {
            search(mLastQuery); // back from an album to the results
            return;
        }
        if (!mPageMode && !mOpenedAsPage && mField.hasFocus() && mLastQuery != null) {
            hideKeyboard(); // back from typing to the results
            search(mLastQuery);
            return;
        }
        super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        mHandler.removeCallbacksAndMessages(null);
        mExecutor.shutdownNow();
        mSuggestExecutor.shutdownNow();
    }

    ///////////////////// rows

    private TextView text(Context c, int sp, int color, boolean bold) {
        TextView view = new TextView(c);
        view.setTextColor(c.getColor(color));
        view.setTextSize(sp);
        if (bold) {
            view.setTypeface(Typeface.DEFAULT_BOLD);
        }
        view.setMaxLines(1);
        view.setEllipsize(TextUtils.TruncateAt.END);
        return view;
    }

    private TextView actionButton(Context c, int icon, String label, boolean primary, View.OnClickListener click) {
        TextView button = new TextView(c);
        button.setText(label);
        button.setTextSize(14);
        button.setTypeface(Typeface.DEFAULT_BOLD);
        button.setGravity(Gravity.CENTER);
        button.setTextColor(c.getColor(primary ? R.color.yt_bg : R.color.yt_text));
        button.setBackground(Ui.pill(c, primary ? c.getColor(R.color.yt_chip_selected) : c.getColor(R.color.yt_chip)));
        button.setPadding(Ui.dp(c, 16), Ui.dp(c, 9), Ui.dp(c, 18), Ui.dp(c, 9));
        android.graphics.drawable.Drawable drawable = c.getDrawable(icon);
        if (drawable != null) {
            drawable = drawable.mutate();
            drawable.setTint(c.getColor(primary ? R.color.yt_bg : R.color.yt_text));
            drawable.setBounds(0, 0, Ui.dp(c, 20), Ui.dp(c, 20));
            button.setCompoundDrawables(drawable, null, null, null);
            button.setCompoundDrawablePadding(Ui.dp(c, 6));
        }
        button.setOnClickListener(click);
        return button;
    }

    private static String typeLabel(MusicApi.Item item) {
        switch (item.type) {
            case MusicApi.ARTIST:
                return "Artist";
            case MusicApi.ALBUM:
                return "Album";
            case MusicApi.PLAYLIST:
                return "Spellista";
            default:
                return "Låt";
        }
    }

    private class RowAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        @Override
        public int getItemViewType(int position) {
            Object row = mRows.get(position);
            if (row instanceof Top) {
                return TYPE_TOP;
            }
            if (row instanceof Suggestion) {
                return TYPE_SUGGESTION;
            }
            if (row instanceof MusicApi.Page) {
                return TYPE_PAGE_HEADER;
            }
            if (row instanceof String) {
                return TYPE_LABEL;
            }
            return TYPE_ROW;
        }

        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            Context c = parent.getContext();
            View view;
            if (viewType == TYPE_SUGGESTION) {
                view = createSuggestion(c);
            } else if (viewType == TYPE_TOP) {
                view = createTop(c);
            } else if (viewType == TYPE_PAGE_HEADER) {
                view = new LinearLayout(c);
            } else if (viewType == TYPE_LABEL) {
                TextView label = text(c, 18, R.color.yt_text, true);
                label.setPadding(Ui.dp(c, 16), Ui.dp(c, 16), Ui.dp(c, 16), Ui.dp(c, 8));
                view = label;
            } else {
                view = createRow(c);
            }
            view.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            return new RecyclerView.ViewHolder(view) {
            };
        }

        private View createSuggestion(Context c) {
            LinearLayout row = new LinearLayout(c);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(Ui.dp(c, 16), 0, Ui.dp(c, 4), 0);
            row.setMinimumHeight(Ui.dp(c, 52));
            ImageView icon = new ImageView(c);
            icon.setImageTintList(android.content.res.ColorStateList.valueOf(c.getColor(R.color.yt_text_secondary)));
            row.addView(icon, new LinearLayout.LayoutParams(Ui.dp(c, 22), Ui.dp(c, 22)));
            TextView label = text(c, 16, R.color.yt_text, false);
            label.setPadding(Ui.dp(c, 20), 0, Ui.dp(c, 8), 0);
            row.addView(label, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            ImageView fill = new ImageView(c);
            fill.setImageResource(R.drawable.ic_north_west);
            fill.setImageTintList(android.content.res.ColorStateList.valueOf(c.getColor(R.color.yt_text_secondary)));
            fill.setPadding(Ui.dp(c, 14), Ui.dp(c, 14), Ui.dp(c, 14), Ui.dp(c, 14));
            row.addView(fill, new LinearLayout.LayoutParams(Ui.dp(c, 48), Ui.dp(c, 48)));
            return row;
        }

        private View createTop(Context c) {
            LinearLayout card = new LinearLayout(c);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setPadding(Ui.dp(c, 16), Ui.dp(c, 8), Ui.dp(c, 16), Ui.dp(c, 16));
            LinearLayout head = new LinearLayout(c);
            head.setGravity(Gravity.CENTER_VERTICAL);
            ImageView cover = new ImageView(c);
            cover.setScaleType(ImageView.ScaleType.CENTER_CROP);
            cover.setBackgroundColor(c.getColor(R.color.yt_surface));
            head.addView(cover, new LinearLayout.LayoutParams(Ui.dp(c, 84), Ui.dp(c, 84)));
            LinearLayout texts = new LinearLayout(c);
            texts.setOrientation(LinearLayout.VERTICAL);
            texts.setPadding(Ui.dp(c, 16), 0, 0, 0);
            TextView title = text(c, 20, R.color.yt_text, true);
            title.setMaxLines(2);
            texts.addView(title);
            TextView subtitle = text(c, 14, R.color.yt_text_secondary, false);
            subtitle.setPadding(0, Ui.dp(c, 4), 0, 0);
            texts.addView(subtitle);
            head.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            card.addView(head);
            LinearLayout buttons = new LinearLayout(c);
            buttons.setPadding(0, Ui.dp(c, 14), 0, 0);
            card.addView(buttons);
            View divider = new View(c);
            divider.setBackgroundColor(c.getColor(R.color.yt_divider));
            LinearLayout.LayoutParams dividerParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1);
            dividerParams.topMargin = Ui.dp(c, 18);
            card.addView(divider, dividerParams);
            return card;
        }

        private View createRow(Context c) {
            LinearLayout row = new LinearLayout(c);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(Ui.dp(c, 16), Ui.dp(c, 7), Ui.dp(c, 4), Ui.dp(c, 7));

            ImageView cover = new ImageView(c);
            cover.setScaleType(ImageView.ScaleType.CENTER_CROP);
            cover.setBackgroundColor(c.getColor(R.color.yt_surface));
            row.addView(cover, new LinearLayout.LayoutParams(Ui.dp(c, 56), Ui.dp(c, 56)));

            LinearLayout texts = new LinearLayout(c);
            texts.setOrientation(LinearLayout.VERTICAL);
            texts.setPadding(Ui.dp(c, 14), 0, Ui.dp(c, 4), 0);
            texts.addView(text(c, 16, R.color.yt_text, false));
            TextView subtitle = text(c, 14, R.color.yt_text_secondary, false);
            subtitle.setPadding(0, Ui.dp(c, 2), 0, 0);
            texts.addView(subtitle);
            row.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            ImageView more = new ImageView(c);
            more.setImageResource(R.drawable.ic_more);
            more.setPadding(Ui.dp(c, 12), Ui.dp(c, 12), Ui.dp(c, 12), Ui.dp(c, 12));
            more.setBackground(Ui.roundRipple());
            row.addView(more, new LinearLayout.LayoutParams(Ui.dp(c, 48), Ui.dp(c, 48)));
            return row;
        }

        @Override
        public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
            Object row = mRows.get(position);
            if (row instanceof Suggestion) {
                bindSuggestion(holder.itemView, (Suggestion) row);
            } else if (row instanceof Top) {
                bindTop(holder.itemView, ((Top) row).item);
            } else if (row instanceof MusicApi.Page) {
                bindPageHeader((LinearLayout) holder.itemView, (MusicApi.Page) row);
            } else if (row instanceof String) {
                ((TextView) holder.itemView).setText((String) row);
            } else {
                bindRow(holder.itemView, (MusicApi.Item) row);
            }
        }

        private void bindSuggestion(View view, Suggestion suggestion) {
            LinearLayout row = (LinearLayout) view;
            ((ImageView) row.getChildAt(0)).setImageResource(suggestion.history ? R.drawable.ic_history : R.drawable.ic_search);
            ((TextView) row.getChildAt(1)).setText(suggestion.text);
            row.setOnClickListener(v -> search(suggestion.text));
            row.setOnLongClickListener(v -> {
                if (!suggestion.history) {
                    return false;
                }
                new android.app.AlertDialog.Builder(MusicSearchActivity.this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                        .setTitle(suggestion.text)
                        .setMessage("Ta bort från sökhistoriken?")
                        .setNegativeButton("Avbryt", null)
                        .setPositiveButton("Ta bort", (d, w) -> {
                            forget(suggestion.text);
                            showSuggestions(mField.getText().toString());
                        })
                        .show();
                return true;
            });
            row.getChildAt(2).setOnClickListener(v -> {
                mField.setText(suggestion.text);
                mField.setSelection(suggestion.text.length());
            });
        }

        private void bindTop(View view, MusicApi.Item item) {
            Context c = view.getContext();
            LinearLayout card = (LinearLayout) view;
            LinearLayout head = (LinearLayout) card.getChildAt(0);
            ImageView cover = (ImageView) head.getChildAt(0);
            LinearLayout texts = (LinearLayout) head.getChildAt(1);
            ((TextView) texts.getChildAt(0)).setText(item.title);
            String subtitle = item.subtitle != null && !item.subtitle.isEmpty() ? item.subtitle : typeLabel(item);
            ((TextView) texts.getChildAt(1)).setText(subtitle);
            if (item.type == MusicApi.ARTIST) {
                Ui.makeCircle(cover);
            } else {
                Ui.makeRounded(cover, Ui.dp(c, 6));
            }
            loadImage(cover, item.thumb);
            head.setOnClickListener(v -> onItemClicked(item));

            LinearLayout buttons = (LinearLayout) card.getChildAt(1);
            buttons.removeAllViews();
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            params.setMarginEnd(Ui.dp(c, 10));
            if (item.type == MusicApi.SONG) {
                buttons.addView(actionButton(c, R.drawable.ic_play, "Spela", true, v -> MusicMenu.startRadio(MusicSearchActivity.this, MusicMenu.toVideo(item), false)), params);
                buttons.addView(actionButton(c, R.drawable.ic_save, "Spara", false, v -> MusicMenu.addToPlaylist(MusicSearchActivity.this, MusicMenu.toVideo(item))), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            } else if (item.type == MusicApi.ALBUM || item.type == MusicApi.PLAYLIST) {
                buttons.addView(actionButton(c, R.drawable.ic_play, "Spela", true, v -> playFromCard(item, false)), params);
                buttons.addView(actionButton(c, R.drawable.ic_shuffle, "Blanda", false, v -> playFromCard(item, true)), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            } else {
                buttons.addView(actionButton(c, R.drawable.ic_chevron_right, "Öppna", false, v -> onItemClicked(item)), params);
                buttons.addView(new View(c), new LinearLayout.LayoutParams(0, 1, 1f));
            }
        }

        private void bindPageHeader(LinearLayout box, MusicApi.Page page) {
            Context c = box.getContext();
            box.removeAllViews();
            box.setOrientation(LinearLayout.VERTICAL);
            box.setGravity(Gravity.CENTER_HORIZONTAL);
            box.setPadding(Ui.dp(c, 24), Ui.dp(c, 8), Ui.dp(c, 24), Ui.dp(c, 12));

            ImageView cover = new ImageView(c);
            cover.setScaleType(ImageView.ScaleType.CENTER_CROP);
            cover.setBackgroundColor(c.getColor(R.color.yt_surface));
            Ui.makeRounded(cover, Ui.dp(c, 8));
            int size = Math.min(Ui.dp(c, 240), c.getResources().getDisplayMetrics().widthPixels - Ui.dp(c, 96));
            box.addView(cover, new LinearLayout.LayoutParams(size, size));
            loadImage(cover, page.thumb);

            TextView title = text(c, 24, R.color.yt_text, true);
            title.setMaxLines(3);
            title.setGravity(Gravity.CENTER);
            title.setPadding(0, Ui.dp(c, 18), 0, 0);
            title.setText(page.title);
            box.addView(title);

            if (page.subtitle != null && !page.subtitle.isEmpty()) {
                TextView subtitle = text(c, 14, R.color.yt_text_secondary, false);
                subtitle.setMaxLines(2);
                subtitle.setGravity(Gravity.CENTER);
                subtitle.setPadding(0, Ui.dp(c, 6), 0, 0);
                subtitle.setText(page.subtitle);
                box.addView(subtitle);
            }
            if (page.secondSubtitle != null && !page.secondSubtitle.isEmpty()) {
                TextView second = text(c, 13, R.color.yt_text_secondary, false);
                second.setGravity(Gravity.CENTER);
                second.setPadding(0, Ui.dp(c, 2), 0, 0);
                second.setText(page.secondSubtitle);
                box.addView(second);
            }

            LinearLayout buttons = new LinearLayout(c);
            buttons.setGravity(Gravity.CENTER);
            buttons.setPadding(0, Ui.dp(c, 18), 0, Ui.dp(c, 4));
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            params.setMargins(Ui.dp(c, 5), 0, Ui.dp(c, 5), 0);
            buttons.addView(actionButton(c, R.drawable.ic_save, "Spara", false, v -> savePage()), params);
            buttons.addView(actionButton(c, R.drawable.ic_play, "Spela", true, v -> playPage(false)), params);
            buttons.addView(actionButton(c, R.drawable.ic_shuffle, "Blanda", false, v -> playPage(true)), params);
            box.addView(buttons);
        }

        private void bindRow(View view, MusicApi.Item item) {
            LinearLayout row = (LinearLayout) view;
            ImageView cover = (ImageView) row.getChildAt(0);
            LinearLayout texts = (LinearLayout) row.getChildAt(1);
            ((TextView) texts.getChildAt(0)).setText(item.title);
            ((TextView) texts.getChildAt(1)).setText(item.subtitle);

            if (item.type == MusicApi.ARTIST) {
                Ui.makeCircle(cover);
            } else {
                Ui.makeRounded(cover, Ui.dp(MusicSearchActivity.this, 4));
            }
            loadImage(cover, item.thumb);
            row.setOnClickListener(v -> onItemClicked(item));
            row.setOnLongClickListener(v -> {
                showItemMenu(item);
                return true;
            });
            row.getChildAt(2).setOnClickListener(v -> showItemMenu(item));
        }

        @Override
        public int getItemCount() {
            return mRows.size();
        }
    }

    /** Spela / Blanda on an album card: fetched first, then played (no page opened). */
    private void playFromCard(MusicApi.Item album, boolean shuffle) {
        Toast.makeText(this, "Hämtar " + album.title + "…", Toast.LENGTH_SHORT).show();
        mExecutor.execute(() -> {
            MusicApi.Page page;
            try {
                page = MusicApi.page(album.browseId);
            } catch (Exception e) {
                page = null;
            }
            final MusicApi.Page result = page;
            runOnUiThread(() -> {
                if (isDestroyed()) {
                    return;
                }
                if (result == null || result.tracks.isEmpty()) {
                    Toast.makeText(this, "Hittade inga låtar", Toast.LENGTH_SHORT).show();
                    return;
                }
                List<Video> queue = MusicMenu.toVideos(result.tracks);
                if (shuffle) {
                    Collections.shuffle(queue);
                }
                MusicMenu.playQueue(this, queue, 0, result.title != null ? result.title : album.title);
            });
        });
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
