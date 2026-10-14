package com.liskovsoft.smartyoutubetv2.mobile.ui.dialogs;

import android.graphics.Typeface;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.liskovsoft.smartyoutubetv2.common.app.models.playback.ui.OptionCategory;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.ui.OptionItem;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.AppDialogPresenter;
import com.liskovsoft.smartyoutubetv2.common.app.views.AppDialogView;
import com.liskovsoft.smartyoutubetv2.mobile.R;
import com.liskovsoft.smartyoutubetv2.mobile.ui.main.MobileActivity;
import com.liskovsoft.smartyoutubetv2.mobile.ui.widgets.Ui;

import com.bumptech.glide.Glide;
import com.liskovsoft.mediaserviceinterfaces.data.CommentGroup;
import com.liskovsoft.mediaserviceinterfaces.data.CommentItem;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.ui.CommentsReceiver;
import android.widget.ImageView;

import java.util.ArrayList;
import java.util.List;

/**
 * Every menu / settings dialog of the presenters, as a bottom sheet: a list of categories, a category opens a list of
 * its options (radio, checkboxes, buttons). Back goes one level up.
 */
public class AppDialogActivity extends MobileActivity implements AppDialogView {
    private AppDialogPresenter mPresenter;
    private FrameLayout mRoot;
    private LinearLayout mContent;
    private TextView mTitleView;
    private List<OptionCategory> mCategories = new ArrayList<>();
    private CharSequence mTitle;
    private OptionCategory mOpenCategory;
    private boolean mOverlay;
    private boolean mTransparent;
    private boolean mPaused;
    private boolean mShown;
    private boolean mFinished;
    private int mId;
    private CommentsReceiver mComments;
    private final List<CommentGroup> mCommentGroups = new ArrayList<>();
    private TextView mCommentsStatus;
    private boolean mExpandable = true;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(createRoot());

        mPresenter = AppDialogPresenter.instance(this);
        mPresenter.setView(this);
        mPresenter.onViewInitialized();
    }

    private View createRoot() {
        FrameLayout root = new FrameLayout(this);
        mRoot = root;
        root.setBackgroundColor(0x99000000);
        root.setOnClickListener(v -> finish());

        LinearLayout sheet = new LinearLayout(this);
        sheet.setOrientation(LinearLayout.VERTICAL);
        sheet.setBackgroundResource(R.drawable.bg_sheet);
        sheet.setOnClickListener(v -> {
        });

        mTitleView = new TextView(this);
        mTitleView.setTextColor(getColor(R.color.yt_text));
        mTitleView.setTextSize(18);
        mTitleView.setTypeface(Typeface.DEFAULT_BOLD);
        mTitleView.setPadding(Ui.dp(this, 20), Ui.dp(this, 18), Ui.dp(this, 20), Ui.dp(this, 10));
        sheet.addView(mTitleView);

        ScrollView scroll = new ScrollView(this);
        mContent = new LinearLayout(this);
        mContent.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(mContent);
        sheet.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        int maxHeight = Math.round(getResources().getDisplayMetrics().heightPixels * 0.7f);
        FrameLayout.LayoutParams sheetParams = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
        root.addView(new MaxHeightHolder(this, sheet, maxHeight), sheetParams);

        return root;
    }

    /** Wraps the sheet and limits its height. */
    private static class MaxHeightHolder extends FrameLayout {
        private final int mMaxHeight;

        MaxHeightHolder(android.content.Context context, View child, int maxHeight) {
            super(context);
            mMaxHeight = maxHeight;
            addView(child, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(mMaxHeight, MeasureSpec.AT_MOST));
        }
    }

    ///////////////////// AppDialogView

    @Override
    public void show(List<OptionCategory> categories, CharSequence title, boolean isExpandable, boolean isTransparent, boolean isOverlay, int id) {
        mCategories = categories != null ? categories : new ArrayList<>();
        mTitle = title;
        mExpandable = isExpandable;
        mTransparent = isTransparent;
        mOverlay = isOverlay;
        mId = id;
        mShown = true;
        mOpenCategory = null;

        if (isTransparent || isOverlay) {
            mRoot.setBackgroundColor(0x33000000);
        }

        if (mCategories.size() == 1 || !isExpandable) {
            openCategory(mCategories.get(0), false);
        } else {
            showCategoryList();
        }
    }

    private void showCategoryList() {
        mOpenCategory = null;
        mTitleView.setText(mTitle);
        mTitleView.setVisibility(TextUtils.isEmpty(mTitle) ? View.GONE : View.VISIBLE);
        mContent.removeAllViews();

        for (OptionCategory category : mCategories) {
            // single switches / buttons have no title: they are the option itself
            if (category.type == OptionCategory.TYPE_SINGLE_SWITCH || category.type == OptionCategory.TYPE_SINGLE_BUTTON) {
                for (OptionItem item : category.options) {
                    addRow(item.getTitle(), item.getDescription(), category.type == OptionCategory.TYPE_SINGLE_SWITCH ? (item.isSelected() ? "✓ " : "") : "", () -> {
                        item.onSelect(category.type == OptionCategory.TYPE_SINGLE_SWITCH ? !item.isSelected() : true);
                        if (category.type == OptionCategory.TYPE_SINGLE_SWITCH) {
                            showCategoryList();
                        }
                    });
                }
            } else {
                addRow(category.title, null, "", () -> openCategory(category, true));
            }
        }
    }

    private void openCategory(OptionCategory category, boolean fromList) {
        mOpenCategory = fromList ? category : null;
        CharSequence title = category.title != null ? category.title : mTitle;
        mTitleView.setText(title);
        mTitleView.setVisibility(TextUtils.isEmpty(title) ? View.GONE : View.VISIBLE);
        renderCategory(category);
    }

    private void renderCategory(OptionCategory category) {
        mContent.removeAllViews();

        if (category.type == OptionCategory.TYPE_COMMENTS && !category.options.isEmpty() && category.options.get(0).getCommentsReceiver() != null) {
            startComments(category.options.get(0).getCommentsReceiver());
            return;
        }

        if (category.type == OptionCategory.TYPE_LONG_TEXT || category.type == OptionCategory.TYPE_CHAT || category.type == OptionCategory.TYPE_COMMENTS) {
            for (OptionItem item : category.options) {
                TextView text = new TextView(this);
                text.setText(item.getDescription() != null ? item.getDescription() : item.getTitle());
                text.setTextColor(getColor(R.color.yt_text));
                text.setTextSize(14);
                text.setPadding(Ui.dp(this, 20), Ui.dp(this, 8), Ui.dp(this, 20), Ui.dp(this, 20));
                mContent.addView(text);
            }
            return;
        }

        int selectedRadio = -1;
        if (category.type == OptionCategory.TYPE_RADIO_LIST) {
            for (int i = 0; i < category.options.size(); i++) {
                if (category.options.get(i).isSelected()) {
                    selectedRadio = i;
                    break;
                }
            }
        }

        final int radioSelected = selectedRadio;

        for (int i = 0; i < category.options.size(); i++) {
            final int index = i;
            final OptionItem item = category.options.get(i);
            String mark = "";

            if (category.type == OptionCategory.TYPE_RADIO_LIST) {
                mark = index == radioSelected ? "● " : "○ ";
            } else if (category.type == OptionCategory.TYPE_CHECKBOX_LIST) {
                mark = item.isSelected() ? "☑ " : "☐ ";
            }

            addRow(item.getTitle(), item.getDescription(), mark, () -> {
                if (category.type == OptionCategory.TYPE_RADIO_LIST) {
                    item.onSelect(true);
                    renderRadio(category, index);
                } else if (category.type == OptionCategory.TYPE_CHECKBOX_LIST) {
                    item.onSelect(!item.isSelected());
                    renderCategory(category);
                } else {
                    item.onSelect(true);
                }
            });
        }
    }

    private static final class CommentsBackup implements CommentsReceiver.Backup {
        final List<CommentGroup> groups;

        CommentsBackup(List<CommentGroup> groups) {
            this.groups = new ArrayList<>(groups);
        }
    }

    private void startComments(CommentsReceiver receiver) {
        mComments = receiver;
        mCommentGroups.clear();
        mCommentsStatus = new TextView(this);
        mCommentsStatus.setText(receiver.getLoadingMessage());
        mCommentsStatus.setTextColor(getColor(R.color.yt_text_secondary));
        mCommentsStatus.setPadding(Ui.dp(this, 20), Ui.dp(this, 16), Ui.dp(this, 20), Ui.dp(this, 16));
        mContent.addView(mCommentsStatus);

        receiver.setCallback(new CommentsReceiver.Callback() {
            @Override
            public void onCommentGroup(CommentGroup group) {
                runOnUiThread(() -> {
                    if (group != null) {
                        mCommentGroups.add(group);
                    }
                    renderComments();
                });
            }

            @Override
            public void onBackup(CommentsReceiver.Backup backup) {
                runOnUiThread(() -> {
                    if (backup instanceof CommentsBackup) {
                        mCommentGroups.clear();
                        mCommentGroups.addAll(((CommentsBackup) backup).groups);
                    }
                    renderComments();
                });
            }

            @Override
            public void onSync(CommentItem commentItem) {
                runOnUiThread(AppDialogActivity.this::renderComments);
            }
        });
        receiver.onStart();
    }

    private void renderComments() {
        if (mComments == null) {
            return;
        }

        mContent.removeAllViews();
        int count = 0;
        CommentGroup last = null;

        for (CommentGroup group : mCommentGroups) {
            last = group;
            if (group.getComments() == null) {
                continue;
            }

            for (CommentItem item : group.getComments()) {
                mContent.addView(createCommentRow(item));
                count++;
            }
        }

        if (count == 0) {
            TextView empty = new TextView(this);
            empty.setText(mComments.getErrorMessage());
            empty.setTextColor(getColor(R.color.yt_text_secondary));
            empty.setPadding(Ui.dp(this, 20), Ui.dp(this, 16), Ui.dp(this, 20), Ui.dp(this, 16));
            mContent.addView(empty);
            return;
        }

        if (last != null && last.getNextCommentsKey() != null) {
            final CommentGroup more = last;
            TextView button = new TextView(this);
            button.setText("Visa fler kommentarer");
            button.setTextColor(0xFF3EA6FF);
            button.setPadding(Ui.dp(this, 20), Ui.dp(this, 16), Ui.dp(this, 20), Ui.dp(this, 24));
            button.setOnClickListener(v -> {
                button.setText(mComments.getLoadingMessage());
                mComments.onLoadMore(more);
            });
            mContent.addView(button);
        }
    }

    private View createCommentRow(CommentItem item) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(Ui.dp(this, 16), Ui.dp(this, 10), Ui.dp(this, 16), Ui.dp(this, 10));

        ImageView avatar = new ImageView(this);
        avatar.setBackgroundResource(R.drawable.bg_avatar);
        avatar.setScaleType(ImageView.ScaleType.CENTER_CROP);
        Ui.makeCircle(avatar);
        LinearLayout.LayoutParams avatarParams = new LinearLayout.LayoutParams(Ui.dp(this, 32), Ui.dp(this, 32));
        avatarParams.setMarginEnd(Ui.dp(this, 12));
        row.addView(avatar, avatarParams);
        if (item.getAuthorPhoto() != null) {
            Glide.with(avatar).load(item.getAuthorPhoto()).into(avatar);
        }

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);

        TextView header = new TextView(this);
        header.setTextColor(getColor(R.color.yt_text_secondary));
        header.setTextSize(12);
        String date = item.getPublishedDate();
        header.setText((item.getAuthorName() != null ? item.getAuthorName() : "") + (date != null ? " • " + date : ""));
        texts.addView(header);

        TextView message = new TextView(this);
        message.setTextColor(getColor(R.color.yt_text));
        message.setTextSize(14);
        message.setText(item.getMessage());
        message.setPadding(0, Ui.dp(this, 2), 0, Ui.dp(this, 4));
        texts.addView(message);

        StringBuilder footer = new StringBuilder();
        footer.append(item.isLiked() ? "♥ " : "♡ ");
        if (item.getLikeCount() != null) {
            footer.append(item.getLikeCount());
        }
        if (item.getReplyCount() != null && !item.getReplyCount().isEmpty()) {
            footer.append("     💬 ").append(item.getReplyCount());
        }
        TextView footerView = new TextView(this);
        footerView.setTextColor(getColor(R.color.yt_text_secondary));
        footerView.setTextSize(12);
        footerView.setText(footer);
        texts.addView(footerView);

        row.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        row.setOnClickListener(v -> mComments.onCommentClicked(item)); // replies
        row.setOnLongClickListener(v -> {
            mComments.onCommentLongClicked(item); // like
            return true;
        });
        return row;
    }

    private void renderRadio(OptionCategory category, int selected) {
        mContent.removeAllViews();

        for (int i = 0; i < category.options.size(); i++) {
            final int index = i;
            final OptionItem item = category.options.get(i);
            addRow(item.getTitle(), item.getDescription(), index == selected ? "● " : "○ ", () -> {
                item.onSelect(true);
                renderRadio(category, index);
            });
        }
    }

    private void addRow(CharSequence title, CharSequence description, String mark, Runnable onClick) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(Ui.dp(this, 20), Ui.dp(this, 14), Ui.dp(this, 20), Ui.dp(this, 14));

        TextView titleView = new TextView(this);
        titleView.setText(mark + (title != null ? title : ""));
        titleView.setTextColor(getColor(R.color.yt_text));
        titleView.setTextSize(16);
        row.addView(titleView);

        if (!TextUtils.isEmpty(description)) {
            TextView descView = new TextView(this);
            descView.setText(description);
            descView.setTextColor(getColor(R.color.yt_text_secondary));
            descView.setTextSize(13);
            row.addView(descView);
        }

        row.setOnClickListener(v -> onClick.run());
        mContent.addView(row);
    }

    @Override
    public void finish() {
        if (!mFinished) {
            mFinished = true;
            if (mComments != null) {
                mComments.onFinish(new CommentsBackup(mCommentGroups));
                mComments = null;
            }
            mShown = false;
            if (mPresenter != null) {
                mPresenter.onFinish();
            }
        }

        super.finish();
    }

    @Override
    public void onBackPressed() {
        if (canGoBack()) {
            goBack();
        } else {
            finish();
        }
    }

    @Override
    public void goBack() {
        if (mOpenCategory != null) {
            showCategoryList();
        } else {
            finish();
        }
    }

    @Override
    public void clearBackstack() {
        mOpenCategory = null;
    }

    @Override
    public boolean canGoBack() {
        return mOpenCategory != null;
    }

    @Override
    public boolean isShown() {
        return mShown;
    }

    @Override
    public boolean isTransparent() {
        return mTransparent;
    }

    @Override
    public boolean isOverlay() {
        return mOverlay;
    }

    @Override
    public boolean isPaused() {
        return mPaused;
    }

    @Override
    public int getViewId() {
        return mId;
    }

    @Override
    protected void onResume() {
        super.onResume();
        mPaused = false;
    }

    @Override
    protected void onPause() {
        super.onPause();
        mPaused = true;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (mPresenter != null && mPresenter.getView() == this) {
            mPresenter.onViewDestroyed();
        }
    }
}
