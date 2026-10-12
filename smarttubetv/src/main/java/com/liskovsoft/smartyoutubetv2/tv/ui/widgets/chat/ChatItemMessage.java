package com.liskovsoft.smartyoutubetv2.tv.ui.widgets.chat;

import android.graphics.Color;
import android.graphics.Typeface;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;
import android.text.style.TypefaceSpan;
import android.content.Context;
import android.text.TextUtils;

import com.liskovsoft.mediaserviceinterfaces.data.ChatItem;
import com.liskovsoft.mediaserviceinterfaces.data.CommentItem;
import com.liskovsoft.sharedutils.helpers.Helpers;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.common.utils.Utils;
import com.liskovsoft.smartyoutubetv2.tv.R;
import com.liskovsoft.googlecommon.common.helpers.ServiceHelper;
import com.stfalcon.chatkit.commons.models.IMessage;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;

public class ChatItemMessage implements IMessage {
    private static final int MAX_LENGTH = 700;
    private static final int LINE_LENGTH = 30;
    private String mId;
    private CharSequence mText;
    private ChatItemAuthor mAuthor;
    private Date mCreatedAt;
    private CommentItem mCommentItem;

    public static ChatItemMessage from(ChatItem chatItem) {
        ChatItemMessage message = new ChatItemMessage();
        message.mId = chatItem.getId();
        if (chatItem.getMessage() != null && !chatItem.getMessage().trim().isEmpty()) {
            message.mText = TextUtils.concat(Utils.bold(chatItem.getAuthorName()), ": ", chatItem.getMessage());
        }
        message.mAuthor = ChatItemAuthor.from(chatItem);
        message.mCreatedAt = new Date();

        return message;
    }

    public static ChatItemMessage from(Context context, CommentItem commentItem) {
        ChatItemMessage message = new ChatItemMessage();
        message.mId = commentItem.getId();
        if (commentItem.getMessage() != null && !commentItem.getMessage().trim().isEmpty()) {
            message.mText = createCommentText(context, commentItem);
        }
        message.mAuthor = ChatItemAuthor.from(commentItem);
        message.mCreatedAt = new Date();
        message.mCommentItem = commentItem;

        return message;
    }

    /**
     * JoTube M3: a modern, airy comment. Author (bold) and date (dim) on top, the text below with room to breathe,
     * likes and replies (dim) at the bottom. Regular font (the layout's condensed one is hard to read).
     */
    private static CharSequence createCommentText(Context context, CommentItem item) {
        SpannableStringBuilder text = new SpannableStringBuilder();
        int dim = Color.parseColor("#AAAAAA");

        String author = item.getAuthorName();
        if (author != null) {
            int start = text.length();
            text.append(author);
            text.setSpan(new StyleSpan(Typeface.BOLD), start, text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }

        if (item.getPublishedDate() != null) {
            int start = text.length();
            text.append("   ").append(item.getPublishedDate());
            text.setSpan(new ForegroundColorSpan(dim), start, text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            text.setSpan(new RelativeSizeSpan(0.85f), start, text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }

        text.append("\n").append(Utils.createSmallNewLine()).append(item.getMessage());

        CharSequence footer = ServiceHelper.combineItems(
                "   ",
                item.getLikeCount() != null ? String.format("%s %s", Helpers.THUMB_UP, item.getLikeCount()) : null,
                item.getReplyCount(),
                item.isLiked() ? String.format("(%s)", context.getString(R.string.you_liked)) : null);

        if (footer != null && footer.length() > 0) {
            text.append("\n").append(Utils.createSmallNewLine());
            int start = text.length();
            text.append(footer);
            text.setSpan(new ForegroundColorSpan(dim), start, text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            text.setSpan(new RelativeSizeSpan(0.85f), start, text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }

        // Overrides the condensed font of the message layout
        text.setSpan(new TypefaceSpan("sans-serif"), 0, text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);

        return text;
    }

    public static List<ChatItemMessage> fromSplit(Context context, CommentItem commentItem) {
        if (shouldSplit(commentItem)) {
            List<String> comments = Helpers.splitStringBySize(commentItem.getMessage(), getRealMaxLen(commentItem.getMessage()));
            List<ChatItemMessage> result = new ArrayList<>();
            for (int i = 0; i < comments.size(); i++) {
                String prefix = i > 0 ? "..." : "";
                String postfix = i < (comments.size() - 1) ? "..." : "";
                String comment = prefix + comments.get(i) + postfix;
                result.add(from(context, new CommentItem() {
                    public String getId() {
                        return String.valueOf(comment.hashCode());
                    }

                    public String getMessage() {
                        return comment;
                    }

                    public String getAuthorName() {
                        return commentItem.getAuthorName();
                    }

                    public String getAuthorPhoto() {
                        return commentItem.getAuthorPhoto();
                    }

                    public String getPublishedDate() {
                        return commentItem.getPublishedDate();
                    }

                    public String getNestedCommentsKey() {
                        return commentItem.getNestedCommentsKey();
                    }

                    public boolean isLiked() {
                        return commentItem.isLiked();
                    }

                    public String getLikeCount() {
                        return commentItem.getLikeCount();
                    }

                    public String getReplyCount() {
                        return commentItem.getReplyCount();
                    }

                    @Override
                    public boolean isEmpty() {
                        return commentItem.isEmpty();
                    }
                }));
            }
            return result;
        }

        return Collections.singletonList(from(context, commentItem));
    }

    public static boolean shouldSplit(CommentItem commentItem) {
        return commentItem != null && commentItem.getMessage() != null && commentItem.getMessage().length() > getRealMaxLen(commentItem.getMessage());
    }

    private static int getRealMaxLen(String text) {
        if (text == null) {
            return -1;
        }

        String[] split = text.split("\n");

        if (split.length == 1) {
            return MAX_LENGTH;
        }

        List<String> splitNoLongLines = new ArrayList<>();
        for (String line : split) {
            while (line.length() > LINE_LENGTH) {
                int breakPoint = line.lastIndexOf(' ', LINE_LENGTH);

                if (breakPoint == -1) {
                    breakPoint = LINE_LENGTH;
                }

                splitNoLongLines.add(line.substring(0, breakPoint));
                line = line.substring(breakPoint).trim();
            }
            splitNoLongLines.add(line);
        }
        split = splitNoLongLines.toArray(new String[0]);

        int realCount = 0;
        int fakeCount = 0;

        for (String part : split) {
            realCount += part.length();
            fakeCount += Math.max(part.length(), LINE_LENGTH);

            if (fakeCount > MAX_LENGTH) {
                return realCount;
            }
        }

        return MAX_LENGTH;
    }

    @Override
    public String getId() {
        return mId;
    }

    @Override
    public CharSequence getText() {
        return mText;
    }

    @Override
    public ChatItemAuthor getUser() {
        return mAuthor;
    }

    @Override
    public Date getCreatedAt() {
        return mCreatedAt;
    }

    public CommentItem getCommentItem() {
        return mCommentItem;
    }
}
