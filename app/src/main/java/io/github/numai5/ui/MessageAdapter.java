package io.github.numai5.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.annotation.NonNull;
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

    private List<Message> messages;

    public MessageAdapter(List<Message> messages) {
        this.messages = messages;
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
            ((UserViewHolder) holder).bind(msg);
        } else {
            ((AssistantViewHolder) holder).bind(msg);
        }
    }

    @Override
    public int getItemCount() {
        return messages == null ? 0 : messages.size();
    }

    static class UserViewHolder extends RecyclerView.ViewHolder {
        TextView content;
        UserViewHolder(View v) {
            super(v);
            content = v.findViewById(R.id.userContentText);
        }
        void bind(Message msg) {
            String text = msg.content == null ? "" : msg.content;
            if (msg.inputImages != null && !msg.inputImages.isEmpty()) {
                text = text + " [图片]";
            }
            content.setText(text);
        }
    }

    static class AssistantViewHolder extends RecyclerView.ViewHolder {
        View thinkingContainer;
        TextView thinkingLabel;
        TextView thinkingContent;
        TextView contentText;
        TextView metaText;
        LinearLayout imageContainer;
        boolean thinkingCollapsed = false;

        AssistantViewHolder(View v) {
            super(v);
            thinkingContainer = v.findViewById(R.id.thinkingContainer);
            thinkingLabel = v.findViewById(R.id.thinkingLabel);
            thinkingContent = v.findViewById(R.id.thinkingContent);
            contentText = v.findViewById(R.id.contentText);
            metaText = v.findViewById(R.id.metaText);
            imageContainer = v.findViewById(R.id.imageContainer);
        }

        void bind(Message msg) {
            Context context = itemView.getContext();

            // 思考过程
            String thinking = msg.getThinkingRaw();
            if (thinking != null && thinking.length() > 0) {
                thinkingContainer.setVisibility(View.VISIBLE);
                thinkingLabel.setText(thinkingCollapsed ? "▶ 思考过程" : "▼ 思考过程");
                thinkingContent.setVisibility(thinkingCollapsed ? View.GONE : View.VISIBLE);
                thinkingContent.setText(MarkdownParser.parse(context, thinking, false));
                thinkingLabel.setOnClickListener(v -> {
                    thinkingCollapsed = !thinkingCollapsed;
                    thinkingLabel.setText(thinkingCollapsed ? "▶ 思考过程" : "▼ 思考过程");
                    thinkingContent.setVisibility(thinkingCollapsed ? View.GONE : View.VISIBLE);
                });
            } else {
                thinkingContainer.setVisibility(View.GONE);
            }

            // 正文
            String display = msg.getDisplayRaw();
            if (display == null || display.length() == 0) display = msg.content == null ? "" : msg.content;
            contentText.setText(MarkdownParser.parse(context, display, false));

            // 生图结果
            bindGeneratedImages(context, msg);

            // 错误标记（B4 断流 / 生图失败）
            if (msg.isError) {
                metaText.setText("⚠ 生成中断");
                metaText.setVisibility(View.VISIBLE);
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
            for (String filename : msg.generatedImages) {
                File file = ImageCompressor.imageFile(context, filename);
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
                imageContainer.addView(imageView);
            }
            if (imageContainer.getChildCount() == 0) {
                imageContainer.setVisibility(View.GONE);
            }
        }
    }
}
