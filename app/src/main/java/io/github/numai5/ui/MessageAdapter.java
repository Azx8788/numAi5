package io.github.numai5.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;
import android.view.ActionMode;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;
import java.io.File;
import java.util.List;
import io.github.numai5.R;
import io.github.numai5.model.Message;
import io.github.numai5.model.Role;
import io.github.numai5.model.ThinkingLevel;
import io.github.numai5.vision.ImageCompressor;

/**
 * 消息列表 Adapter。assistant 气泡显示：思考过程（可折叠）+ 正文 + 生图结果 + 模型/供应商/等级标注。
 * user 气泡右对齐显示内容 + [图片] 占位。
 */
public class MessageAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    private static final int TYPE_USER = 0;
    private static final int TYPE_ASSISTANT = 1;
    private static final int TYPE_TOOL = 2;
    /** 气泡内图片显式最大边长（px 级采样目标） */
    private static final int IMAGE_MAX_SIZE = 720;

    /** 出错消息「点此重试」回调 */
    public interface OnRetryListener {
        void onRetry(Message failedMessage);
    }

    /** 文本选择工具栏里的「存入记忆」 */
    public interface OnMemorySelectionListener {
        void onSaveSelection(String text, boolean stated);
    }

    private List<Message> messages;
    private OnRetryListener retryListener;
    private OnMemorySelectionListener memorySelectionListener;

    public MessageAdapter(List<Message> messages) {
        this.messages = messages;
    }

    public void setRetryListener(OnRetryListener listener) {
        this.retryListener = listener;
    }

    public void setOnMemorySelectionListener(OnMemorySelectionListener listener) {
        this.memorySelectionListener = listener;
    }

    @Override
    public int getItemViewType(int position) {
        Role role = messages.get(position).getRoleEnum();
        switch (role) {
            case USER: return TYPE_USER;
            case ASSISTANT: return TYPE_ASSISTANT;
            default: return TYPE_TOOL;
        }
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inflater = LayoutInflater.from(parent.getContext());
        if (viewType == TYPE_USER) {
            return new UserViewHolder(inflater.inflate(R.layout.item_message_user, parent, false));
        }
        return new AssistantViewHolder(inflater.inflate(R.layout.item_message_assistant, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        Message msg = messages.get(position);
        if (holder instanceof UserViewHolder) {
            ((UserViewHolder) holder).bind(msg, memorySelectionListener);
        } else {
            ((AssistantViewHolder) holder).bind(msg, retryListener, memorySelectionListener);
        }
    }

    @Override
    public int getItemCount() {
        return messages == null ? 0 : messages.size();
    }

    /**
     * 文本选择工具栏：保留系统复制/全选，额外提供「存入记忆」。
     * 选区可能反向拖选（end < start），用 min/max 归一；空选区直接忽略。
     * 取文本必须在 mode.finish() 之前，否则选区被清空。
     */
    static ActionMode.Callback selectionCallback(final TextView textView,
                                                 final OnMemorySelectionListener listener,
                                                 final boolean stated) {
        return new ActionMode.Callback() {
            @Override
            public boolean onCreateActionMode(ActionMode mode, Menu menu) {
                menu.add(Menu.NONE, 1, Menu.NONE, R.string.memory_save_to);
                return true;
            }

            @Override
            public boolean onPrepareActionMode(ActionMode mode, Menu menu) {
                return false;
            }

            @Override
            public boolean onActionItemClicked(ActionMode mode, MenuItem item) {
                if (item.getItemId() != 1) return false;
                int start = textView.getSelectionStart();
                int end = textView.getSelectionEnd();
                if (start >= 0 && end >= 0 && start != end && listener != null) {
                    int s = Math.min(start, end);
                    int e = Math.max(start, end);
                    CharSequence selected = textView.getText().subSequence(s, e);
                    if (selected.length() > 0) {
                        listener.onSaveSelection(selected.toString(), stated);
                    }
                }
                mode.finish();
                return true;
            }

            @Override
            public void onDestroyActionMode(ActionMode mode) {
            }
        };
    }

    static class UserViewHolder extends RecyclerView.ViewHolder {
        TextView content;
        UserViewHolder(View v) {
            super(v);
            content = v.findViewById(R.id.userContentText);
        }
        void bind(final Message msg, final OnMemorySelectionListener listener) {
            String text = msg.content == null ? "" : msg.content;
            if (msg.inputImages != null && !msg.inputImages.isEmpty()) {
                text = text + " [图片]";
            }
            content.setText(text);
            content.setTextIsSelectable(true);
            content.setCustomSelectionActionModeCallback(selectionCallback(content, listener, true));
        }
    }

    static class AssistantViewHolder extends RecyclerView.ViewHolder {
        View thinkingContainer;
        TextView thinkingLabel;
        TextView thinkingContent;
        LinearLayout contentContainer;
        TextView metaText;
        LinearLayout imageContainer;
        boolean thinkingCollapsed = false;

        AssistantViewHolder(View v) {
            super(v);
            thinkingContainer = v.findViewById(R.id.thinkingContainer);
            thinkingLabel = v.findViewById(R.id.thinkingLabel);
            thinkingContent = v.findViewById(R.id.thinkingContent);
            contentContainer = v.findViewById(R.id.contentContainer);
            metaText = v.findViewById(R.id.metaText);
            imageContainer = v.findViewById(R.id.imageContainer);
            // 公式以图片渲染，复制时把占位符还原为 LaTeX 原文（每个文本块单独挂）
            MathSpan.installCopyFix(thinkingContent);
        }

        void bind(final Message msg, final OnRetryListener retryListener,
                  final OnMemorySelectionListener memoryListener) {
            Context context = itemView.getContext();

            // 思考过程
            String thinking = msg.getThinkingRaw();
            if (thinking != null && thinking.length() > 0) {
                thinkingContainer.setVisibility(View.VISIBLE);
                thinkingLabel.setText(thinkingCollapsed ? "▶ 思考过程" : "▼ 思考过程");
                thinkingContent.setVisibility(thinkingCollapsed ? View.GONE : View.VISIBLE);
                thinkingContent.setText(MarkdownParser.parse(context, thinking, false,
                        thinkingContent.getCurrentTextColor(), thinkingContent.getTextSize()));
                thinkingLabel.setOnClickListener(v -> {
                    thinkingCollapsed = !thinkingCollapsed;
                    thinkingLabel.setText(thinkingCollapsed ? "▶ 思考过程" : "▼ 思考过程");
                    thinkingContent.setVisibility(thinkingCollapsed ? View.GONE : View.VISIBLE);
                });
            } else {
                thinkingContainer.setVisibility(View.GONE);
            }

            // 正文：分段渲染，超宽公式单独成块（可横向滑动）
            String display = msg.getDisplayRaw();
            if (display == null || display.length() == 0) display = msg.content == null ? "" : msg.content;
            bindContent(context, display, memoryListener);

            // 生图结果
            bindGeneratedImages(context, msg);

            // 错误标记（B4 断流 / 生图失败）：可点击重试
            if (msg.isError) {
                metaText.setText(context.getString(R.string.retry_hint));
                metaText.setVisibility(View.VISIBLE);
                boolean canRetry = retryListener != null
                        && (msg.generatedImages == null || msg.generatedImages.isEmpty());
                if (canRetry) {
                    metaText.setOnClickListener(v -> retryListener.onRetry(msg));
                } else {
                    metaText.setOnClickListener(null);
                }
            } else {
                StringBuilder meta = new StringBuilder();
                if (msg.provider != null) {
                    meta.append(msg.provider).append(" · ");
                }
                if (msg.model != null) meta.append(msg.model);
                if (msg.thinkingLevel != null) {
                    meta.append(" · ").append(ThinkingLevel.fromName(msg.thinkingLevel).getDisplay());
                }
                metaText.setText(meta.toString());
                metaText.setOnClickListener(null);
            }
        }

        /**
         * 渲染正文。普通文本用 TextView，超宽公式单独放在 HorizontalScrollView 里按原始尺寸显示，
         * 避免长公式被等比压缩到屏宽后「又小又长」且超出部分看不到。
         */
        private void bindContent(Context context, String display,
                                 final OnMemorySelectionListener listener) {
            contentContainer.removeAllViews();
            float textSize = contentContainer.getResources().getDisplayMetrics().scaledDensity * 15f;
            int color = ContextCompat.getColor(context, R.color.text_assistant);

            List<MarkdownParser.DisplayBlock> blocks =
                    MarkdownParser.parseBlocks(context, display, color, textSize);
            if (blocks.isEmpty()) return;

            int gap = Math.round(4 * context.getResources().getDisplayMetrics().density);
            for (MarkdownParser.DisplayBlock block : blocks) {
                if (block.type == MarkdownParser.DisplayBlock.TYPE_TEXT) {
                    TextView tv = new TextView(context);
                    tv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 15f);
                    tv.setTextColor(color);
                    tv.setTextIsSelectable(true);
                    tv.setText(block.text);
                    tv.setCustomSelectionActionModeCallback(selectionCallback(tv, listener, false));
                    MathSpan.installCopyFix(tv);
                    contentContainer.addView(tv);
                } else {
                    Drawable drawable = MathRenderer.render(context, block.latex, block.display,
                            textSize, 0, color);
                    if (drawable == null) {
                        TextView tv = new TextView(context);
                        tv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 15f);
                        tv.setTextColor(color);
                        tv.setTextIsSelectable(true);
                        tv.setText(block.raw);
                        tv.setCustomSelectionActionModeCallback(selectionCallback(tv, listener, false));
                        contentContainer.addView(tv);
                    } else {
                        ImageView image = new ImageView(context);
                        image.setImageDrawable(drawable);
                        image.setAdjustViewBounds(true);
                        image.setScaleType(ImageView.ScaleType.FIT_START);
                        image.setPadding(0, gap, 0, gap);

                        HorizontalScrollView scroll = new HorizontalScrollView(context);
                        scroll.setHorizontalScrollBarEnabled(true);
                        scroll.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);
                        scroll.addView(image, new LinearLayout.LayoutParams(
                                LinearLayout.LayoutParams.WRAP_CONTENT,
                                LinearLayout.LayoutParams.WRAP_CONTENT));
                        contentContainer.addView(scroll, new LinearLayout.LayoutParams(
                                LinearLayout.LayoutParams.MATCH_PARENT,
                                LinearLayout.LayoutParams.WRAP_CONTENT));
                    }
                }
            }
        }

        private void bindGeneratedImages(Context context, Message msg) {
            imageContainer.removeAllViews();
            if (msg.generatedImages == null || msg.generatedImages.isEmpty()) {
                imageContainer.setVisibility(View.GONE);
                return;
            }
            imageContainer.setVisibility(View.VISIBLE);
            int size = (int) (context.getResources().getDisplayMetrics().density * 8);
            // 点击任意一张打开全屏预览（可缩放、保存到相册），多图一次带入可左右切换的那张起始位置
            final java.util.ArrayList<String> previewFiles = new java.util.ArrayList<>();
            for (String filename : msg.generatedImages) {
                previewFiles.add(ImageCompressor.imageFile(context, filename).getAbsolutePath());
            }
            for (int i = 0; i < msg.generatedImages.size(); i++) {
                File file = ImageCompressor.imageFile(context, msg.generatedImages.get(i));
                Bitmap bitmap = ImageLoader.load(file, IMAGE_MAX_SIZE);
                if (bitmap == null) continue;
                ImageView imageView = new ImageView(context);
                imageView.setImageBitmap(bitmap);
                imageView.setAdjustViewBounds(true);
                imageView.setScaleType(ImageView.ScaleType.FIT_CENTER);
                LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                params.topMargin = size / 2;
                imageView.setLayoutParams(params);
                final int index = i;
                imageView.setOnClickListener(v -> {
                    android.content.Intent intent = new android.content.Intent(
                            context, io.github.numai5.ImagePreviewActivity.class);
                    intent.putStringArrayListExtra(io.github.numai5.ImagePreviewActivity.EXTRA_FILES, previewFiles);
                    intent.putExtra(io.github.numai5.ImagePreviewActivity.EXTRA_INDEX, index);
                    context.startActivity(intent);
                });
                imageContainer.addView(imageView);
            }
            if (imageContainer.getChildCount() == 0) {
                imageContainer.setVisibility(View.GONE);
            }
        }
    }
}
