package com.liskovsoft.smartyoutubetv2.mobile.ui.search;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.speech.RecognizerIntent;
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
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.VideoGroup;
import com.liskovsoft.smartyoutubetv2.common.app.models.search.MediaServiceSearchTagProvider;
import com.liskovsoft.smartyoutubetv2.common.app.models.search.vineyard.Tag;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.SearchPresenter;
import com.liskovsoft.smartyoutubetv2.common.app.views.SearchView;
import com.liskovsoft.smartyoutubetv2.mobile.R;
import com.liskovsoft.smartyoutubetv2.mobile.ui.browse.VideoAdapter;
import com.liskovsoft.smartyoutubetv2.mobile.ui.main.MobileActivity;
import com.liskovsoft.smartyoutubetv2.mobile.ui.widgets.Ui;

import java.util.ArrayList;
import java.util.List;

/**
 * Search like YouTube: a field in the top bar, suggestions while typing, the results as the same cards as the feed.
 */
public class SearchActivity extends MobileActivity implements SearchView {
    private static final int REQUEST_VOICE = 4711;
    private SearchPresenter mPresenter;
    private MediaServiceSearchTagProvider mTagsProvider;
    private EditText mField;
    private LinearLayout mSuggestions;
    private RecyclerView mList;
    private GridLayoutManager mLayoutManager;
    private VideoAdapter mAdapter;
    private ProgressBar mProgress;
    private boolean mSettingText;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(createContent());

        mPresenter = SearchPresenter.instance(this);
        mPresenter.setView(this);
        mPresenter.onViewInitialized();
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

        mField = new EditText(this);
        mField.setHint(R.string.search_hint);
        mField.setHintTextColor(getColor(R.color.yt_text_secondary));
        mField.setTextColor(getColor(R.color.yt_text));
        mField.setTextSize(16);
        mField.setSingleLine(true);
        mField.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        mField.setBackgroundResource(R.drawable.bg_chip);
        mField.setPadding(Ui.dp(this, 16), 0, Ui.dp(this, 16), 0);
        mField.setOnEditorActionListener((v, actionId, event) -> {
            submit(mField.getText().toString());
            return true;
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
                if (!mSettingText) {
                    loadSuggestions(s.toString());
                }
            }
        });
        LinearLayout.LayoutParams fieldParams = new LinearLayout.LayoutParams(0, Ui.dp(this, 40), 1f);
        fieldParams.setMarginEnd(Ui.dp(this, 4));
        bar.addView(mField, fieldParams);

        ImageView mic = new ImageView(this);
        mic.setImageResource(R.drawable.ic_search);
        mic.setPadding(Ui.dp(this, 14), Ui.dp(this, 14), Ui.dp(this, 14), Ui.dp(this, 14));
        mic.setOnClickListener(v -> submit(mField.getText().toString()));
        bar.addView(mic, new LinearLayout.LayoutParams(Ui.dp(this, 52), Ui.dp(this, 52)));

        root.addView(bar);

        FrameLayout content = new FrameLayout(this);

        mAdapter = new VideoAdapter(new VideoAdapter.Listener() {
            @Override
            public void onClick(Video video) {
                hideKeyboard();
                mPresenter.onVideoItemClicked(video);
            }

            @Override
            public void onLongClick(Video video) {
                mPresenter.onVideoItemLongClicked(video);
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
            public void onScrollStateChanged(RecyclerView recyclerView, int newState) {
                if (newState == RecyclerView.SCROLL_STATE_DRAGGING) {
                    hideKeyboard(); // like the Subscriptions menu on TV: the keyboard goes when browsing starts
                }
            }

            @Override
            public void onScrolled(RecyclerView recyclerView, int dx, int dy) {
                if (dy > 0 && mLayoutManager.findLastVisibleItemPosition() >= mAdapter.size() - 6) {
                    Video last = mAdapter.lastVideo();
                    if (last != null) {
                        mPresenter.onScrollEnd(last);
                    }
                }
            }
        });
        content.addView(mList, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        mSuggestions = new LinearLayout(this);
        mSuggestions.setOrientation(LinearLayout.VERTICAL);
        mSuggestions.setBackgroundColor(getColor(R.color.yt_bg));
        android.widget.ScrollView suggestionsScroll = new android.widget.ScrollView(this);
        suggestionsScroll.addView(mSuggestions);
        suggestionsScroll.setTag("suggestions");
        content.addView(suggestionsScroll, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        mProgress = new ProgressBar(this);
        mProgress.setVisibility(View.GONE);
        content.addView(mProgress, new FrameLayout.LayoutParams(Ui.dp(this, 40), Ui.dp(this, 40), Gravity.CENTER));

        root.addView(content, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        return root;
    }

    private void submit(String text) {
        if (TextUtils.isEmpty(text)) {
            return;
        }

        hideKeyboard();
        showSuggestions(false);
        mPresenter.onSearch(text);
    }

    private void loadSuggestions(String query) {
        if (mTagsProvider == null) {
            return;
        }

        mTagsProvider.search(query, results -> runOnUiThread(() -> showTags(results)));
    }

    private void showTags(List<Tag> tags) {
        mSuggestions.removeAllViews();

        if (tags == null || tags.isEmpty()) {
            return;
        }

        showSuggestions(true);

        for (Tag tag : new ArrayList<>(tags)) {
            TextView row = new TextView(this);
            row.setText(tag.tag);
            row.setTextColor(getColor(R.color.yt_text));
            row.setTextSize(16);
            row.setPadding(Ui.dp(this, 16), Ui.dp(this, 14), Ui.dp(this, 16), Ui.dp(this, 14));
            row.setOnClickListener(v -> {
                setText(tag.tag);
                submit(tag.tag);
            });
            row.setOnLongClickListener(v -> {
                mPresenter.onTagLongClicked(tag);
                return true;
            });
            mSuggestions.addView(row);
        }
    }

    private void showSuggestions(boolean show) {
        ((View) mSuggestions.getParent()).setVisibility(show ? View.VISIBLE : View.GONE);
    }

    private void setText(String text) {
        mSettingText = true;
        mField.setText(text);
        mField.setSelection(text != null ? text.length() : 0);
        mSettingText = false;
    }

    private void hideKeyboard() {
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null) {
            imm.hideSoftInputFromWindow(mField.getWindowToken(), 0);
        }
        mField.clearFocus();
    }

    ///////////////////// SearchView

    @Override
    public void updateSearch(VideoGroup group) {
        showSuggestions(false);

        if (group == null) {
            return;
        }

        if (group.getAction() == VideoGroup.ACTION_REPLACE) {
            mAdapter.clear();
        }

        mAdapter.addVideos(group.getVideos());
    }

    @Override
    public void clearSearch() {
        mAdapter.clear();
    }

    @Override
    public void clearSearchTags() {
        mSuggestions.removeAllViews();
    }

    @Override
    public void removeSearchTag(Tag tag) {
        loadSuggestions(mField.getText().toString());
    }

    @Override
    public void setTagsProvider(MediaServiceSearchTagProvider provider) {
        mTagsProvider = provider;
        loadSuggestions(mField.getText().toString());
    }

    @Override
    public void showProgressBar(boolean show) {
        mProgress.setVisibility(show ? View.VISIBLE : View.GONE);
    }

    @Override
    public void startSearch(String searchText) {
        if (TextUtils.isEmpty(searchText)) {
            return;
        }

        setText(searchText);
        submit(searchText);
    }

    @Override
    public String getSearchText() {
        return mField.getText().toString();
    }

    @Override
    public void startVoiceRecognition() {
        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);

        try {
            startActivityForResult(intent, REQUEST_VOICE);
        } catch (RuntimeException e) {
            // no recognizer
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == REQUEST_VOICE && resultCode == Activity.RESULT_OK && data != null) {
            ArrayList<String> results = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
            if (results != null && !results.isEmpty()) {
                startSearch(results.get(0));
            }
        }
    }

    @Override
    public void finishReally() {
        super.finishReally();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        mPresenter.onViewDestroyed();
    }
}
