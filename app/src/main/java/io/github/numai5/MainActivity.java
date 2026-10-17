package io.github.numai5;

import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.DragEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import java.util.ArrayList;
import java.util.List;
import io.github.numai5.api.ApiError;
import io.github.numai5.api.ApiService;
import io.github.numai5.data.ChatManager;
import io.github.numai5.data.ConfigManager;
import io.github.numai5.data.ImageGenManager;
import io.github.numai5.data.MessageManager;
import io.github.numai5.data.StreamSession;
import io.github.numai5.model.Chat;
import io.github.numai5.model.Config;
import io.github.numai5.model.Message;
import io.github.numai5.model.Role;
import io.github.numai5.model.ThinkingLevel;
import io.github.numai5.provider.ModelCatalog;
import io.github.numai5.provider.ModelDef;
import io.github.numai5.provider.Provider;
import io.github.numai5.ui.MessageAdapter;
import io.github.numai5.ui.ModelSelectorSheet;
import io.github.numai5.vision.ImagePicker;

/**
 * 主界面（步骤10-12）。
 * 状态机：defaultModel（持久化默认）/ pendingModel·pendingLevel（待发送，可临时切换）/
 * 发送瞬间锁定并传入请求（回合内不变，回合结束后下一条可再切换）。
 * 流式刷新：每 200ms 轮询 assistantMsg.streamRev，有变化才重绘。
 * 生命周期：旋转等重建由进程级 StreamSession 保留执行状态；进程被杀不伪装仍在生成。
 */
public class MainActivity extends AppCompatActivity implements StreamSession.Listener, ImageGenManager.Listener {

    private static final String STATE_CHAT_ID = "chatId";
    private static final String STATE_PENDING_MODEL = "pendingModelId";
    private static final String STATE_PENDING_LEVEL = "pendingLevel";
    private static final String STATE_PENDING_IMAGES = "pendingImages";
    private static final long POLL_INTERVAL_MS = 200L;

    private RecyclerView messageList;
    private MessageAdapter adapter;
    private EditText inputText;
    private Button btnModel;
    private Button btnThinking;
    private ImageButton btnSend;
    private ImageButton btnImage;
    private TextView btnRemoveImage;
    private LinearLayout toolBar;

    private ChatManager chatManager;
    private ConfigManager configManager;
    private StreamSession streamSession;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private int lastRev = -1;
    private final Runnable pollRunnable = new Runnable() {
        @Override
        public void run() {
            Message msg = streamSession.getActiveMsg();
            if (msg != null && msg.streamRev != lastRev) {
                lastRev = msg.streamRev;
                notifyStreamingItem();
            }
            handler.postDelayed(this, POLL_INTERVAL_MS);
        }
    };

    /** 默认模型（来自配置，经「设为默认」修改） */
    private ModelDef defaultModel;
    /** 待发送选择（临时切换不影响默认模型） */
    private ModelDef pendingModel;
    private ThinkingLevel pendingLevel = ThinkingLevel.DEFAULT;
    private final List<String> pendingImages = new ArrayList<>();

    /** 生图（AI 绘图）对话框状态 */
    private io.github.numai5.provider.ModelDef imageGenModel;
    private String imageGenSize = "1024x1024";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        chatManager = ChatManager.getInstance(this);
        configManager = ConfigManager.getInstance(this);
        streamSession = StreamSession.getInstance(this);
        streamSession.setListener(this);

        messageList = findViewById(R.id.messageList);
        messageList.setLayoutManager(new LinearLayoutManager(this));
        inputText = findViewById(R.id.inputText);
        btnModel = findViewById(R.id.btnModel);
        btnThinking = findViewById(R.id.btnThinking);
        btnSend = findViewById(R.id.btnSend);
        btnImage = findViewById(R.id.btnImage);
        btnRemoveImage = findViewById(R.id.btnRemoveImage);
        toolBar = findViewById(R.id.toolBar);

        restoreState(savedInstanceState);
        bindListeners();
        loadCurrentChat();

        // 按钮顺序（长按拖动排序，持久化）在绑定监听后应用
        applyToolOrder(configManager.getToolOrder());
        enableToolDrag();

        // 旋转等重建后请求仍在进行：恢复轮询（进程被杀后 StreamSession 随进程消失，不会误恢复）
        if (streamSession.isGenerating() && streamSession.getActiveMsg() != null) {
            startPolling();
        }

        // 上次进程被系统回收时若有未完成的生图任务，标记为中断，避免状态卡住
        ImageGenManager imageGenManager = ImageGenManager.getInstance(this);
        if (imageGenManager.hasStaleTask()) {
            imageGenManager.discardStaleTask();
        }
    }

    // ===== 输入栏按钮拖动排序 =====

    private String toolName(View v) {
        int id = v.getId();
        if (id == R.id.btnImage) return "btnImage";
        if (id == R.id.btnThinking) return "btnThinking";
        if (id == R.id.btnModel) return "btnModel";
        return null;
    }

    private View toolView(String name) {
        if ("btnImage".equals(name)) return btnImage;
        if ("btnThinking".equals(name)) return btnThinking;
        if ("btnModel".equals(name)) return btnModel;
        return null;
    }

    /** 按已保存顺序重排按钮（保留各自的布局参数，如模型按钮的伸展权重） */
    private void applyToolOrder(String order) {
        if (order == null || order.length() == 0) return;
        java.util.Map<String, ViewGroup.LayoutParams> params = new java.util.HashMap<>();
        java.util.Map<String, View> views = new java.util.HashMap<>();
        for (String name : new String[]{"btnImage", "btnThinking", "btnModel"}) {
            View v = toolView(name);
            if (v != null) {
                views.put(name, v);
                params.put(name, v.getLayoutParams());
            }
        }
        toolBar.removeAllViews();
        for (String name : order.split(",")) {
            String key = name.trim();
            View v = views.remove(key);
            if (v != null) toolBar.addView(v, params.get(key));
        }
        // 兜底：未出现在保存顺序里的按钮追加到末尾
        for (String name : new String[]{"btnImage", "btnThinking", "btnModel"}) {
            View v = views.remove(name);
            if (v != null) toolBar.addView(v, params.get(name));
        }
    }

    private void saveToolOrder() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < toolBar.getChildCount(); i++) {
            String name = toolName(toolBar.getChildAt(i));
            if (name == null) continue;
            if (sb.length() > 0) sb.append(',');
            sb.append(name);
        }
        configManager.updateToolOrder(sb.toString());
    }

    /** 长按任意工具按钮开始拖动，松手到另一个按钮上即交换位置 */
    private void enableToolDrag() {
        for (int i = 0; i < toolBar.getChildCount(); i++) {
            View child = toolBar.getChildAt(i);
            child.setOnLongClickListener(v -> {
                v.startDragAndDrop(null, new View.DragShadowBuilder(v), v, 0);
                toast(getString(R.string.tool_drag_hint));
                return true;
            });
            child.setOnDragListener((v, event) -> {
                if (event.getAction() == DragEvent.ACTION_DROP) {
                    View dragged = (View) event.getLocalState();
                    if (dragged != null && dragged != v) {
                        swapToolViews(dragged, v);
                        saveToolOrder();
                    }
                }
                return true;
            });
        }
    }

    private void swapToolViews(View a, View b) {
        int ia = toolBar.indexOfChild(a);
        int ib = toolBar.indexOfChild(b);
        if (ia < 0 || ib < 0 || ia == ib) return;
        ViewGroup.LayoutParams pa = a.getLayoutParams();
        ViewGroup.LayoutParams pb = b.getLayoutParams();
        toolBar.removeView(a);
        toolBar.removeView(b);
        if (ia < ib) {
            toolBar.addView(b, ia, pb);
            toolBar.addView(a, ib, pa);
        } else {
            toolBar.addView(a, ib, pa);
            toolBar.addView(b, ia, pb);
        }
    }

    private void restoreState(Bundle savedInstanceState) {
        if (savedInstanceState != null) {
            String chatId = savedInstanceState.getString(STATE_CHAT_ID);
            if (chatId != null) {
                for (Chat c : chatManager.getChats()) {
                    if (chatId.equals(c.id)) {
                        chatManager.setCurrentChat(c);
                        break;
                    }
                }
            }
            String modelId = savedInstanceState.getString(STATE_PENDING_MODEL);
            if (modelId != null) {
                pendingModel = ModelCatalog.getInstance().getModel(modelId);
            }
            String levelName = savedInstanceState.getString(STATE_PENDING_LEVEL);
            if (levelName != null) {
                pendingLevel = ThinkingLevel.fromName(levelName);
            }
            ArrayList<String> images = savedInstanceState.getStringArrayList(STATE_PENDING_IMAGES);
            if (images != null) {
                pendingImages.addAll(images);
            }
        }
        refreshDefaultModel();
        if (pendingModel == null) {
            pendingModel = defaultModel; // 未选择过临时模型时跟随默认
        }
    }

    private void bindListeners() {
        btnSend.setOnClickListener(v -> {
            if (streamSession.isGenerating()) {
                streamSession.cancel(); // 生成中再次点击 = 取消（图标已切换为暂停样式）
            } else {
                doSend();
            }
        });
        btnImage.setOnClickListener(v -> showImageMenu());
        btnRemoveImage.setOnClickListener(v -> {
            pendingImages.clear();
            updateRemoveImageButton();
        });
        btnModel.setOnClickListener(v -> showModelSelector());
        btnThinking.setOnClickListener(v -> showThinkingSelector());
        inputText.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                doSend();
                return true;
            }
            return false;
        });
    }

    // ===== 发送 =====

    private void doSend() {
        if (streamSession.isGenerating()) {
            toast(getString(R.string.generating));
            return;
        }
        String text = inputText.getText().toString().trim();
        List<String> images = new ArrayList<>(pendingImages);
        if (text.length() == 0 && images.isEmpty()) return;

        // 发送瞬间锁定当前选择（回合内不变；临时选择不修改默认模型）
        ModelDef lockedModel = pendingModel;
        ThinkingLevel lockedLevel = pendingLevel;
        if (lockedModel == null) {
            toast(getString(R.string.select_model_first));
            return;
        }
        // 发送前提是「所选模型所属的供应商」已配置地址与 Key；
        // 不要求先设置默认模型（默认模型只决定初始选择，临时选择同样可以发送）
        Provider sendProvider = ModelCatalog.getInstance().findProviderOfModel(lockedModel.uid());
        if (sendProvider == null) {
            toast(getString(R.string.provider_missing_for_model));
            return;
        }
        if (!sendProvider.isValidForFetch()) {
            toast(getString(R.string.provider_not_ready_format, sendProvider.displayName()));
            return;
        }
        if (!images.isEmpty() && !lockedModel.supportsVision) {
            toast(getString(R.string.no_vision));
            return;
        }

        Chat chat = chatManager.getCurrentChat();
        Message userMsg = MessageManager.createUserMessage(text, images.isEmpty() ? null : images);
        Message assistantMsg = MessageManager.createAssistantMessage(
                lockedModel.id, providerName(lockedModel), lockedLevel.name());

        int insertAt = chat.messages.size();
        chat.messages.add(userMsg);
        chatManager.onMessageAdded(chat); // 先持久化用户消息（含自动标题）
        chat.messages.add(assistantMsg);
        adapter.notifyItemRangeInserted(insertAt, 2);
        scrollToBottom();
        updateTitle();

        inputText.setText("");
        pendingImages.clear();
        updateRemoveImageButton();

        streamSession.start(chat, userMsg, assistantMsg, lockedModel, lockedLevel);
        startPolling();
    }

    private String providerName(ModelDef model) {
        Provider provider = ModelCatalog.getInstance().findProvider(model.providerId);
        return provider != null ? provider.displayName() : model.providerId;
    }

    /**
     * 重试失败的一轮：清空该条 assistant 的残留内容，用同一个用户消息重新发起请求。
     * 用户消息仍在历史中，不会重复发送；模型沿用当前选择（失败后可能已切换）。
     */
    private void retryFailedMessage(Message failed) {
        if (streamSession.isGenerating()) {
            toast(getString(R.string.generating));
            return;
        }
        Chat chat = chatManager.getCurrentChat();
        if (chat == null || failed == null) return;
        int index = chat.messages.indexOf(failed);
        if (index <= 0) return;

        Message userMsg = null;
        for (int i = index - 1; i >= 0; i--) {
            if (chat.messages.get(i).getRoleEnum() == Role.USER) {
                userMsg = chat.messages.get(i);
                break;
            }
        }
        if (userMsg == null) return;

        ModelDef model = pendingModel != null ? pendingModel : defaultModel;
        if (model == null) {
            toast(getString(R.string.retry_need_model));
            return;
        }
        Provider provider = ModelCatalog.getInstance().findProviderOfModel(model.uid());
        if (provider == null || !provider.isValidForFetch()) {
            toast(getString(R.string.provider_not_ready_format,
                    provider != null ? provider.displayName() : model.providerId));
            return;
        }

        failed.content = "";
        failed.reasoningRaw = null;
        failed.isError = false;
        failed.invalidateCache();
        failed.model = model.id;
        failed.provider = providerName(model);
        failed.thinkingLevel = pendingLevel.name();
        chatManager.onMessageAdded(chat);
        adapter.notifyItemChanged(index);
        scrollToBottom();

        toast(getString(R.string.retry_running));
        streamSession.start(chat, userMsg, failed, model, pendingLevel);
        startPolling();
    }

    // ===== 流式刷新 =====

    private void startPolling() {
        lastRev = -1;
        handler.removeCallbacks(pollRunnable);
        handler.post(pollRunnable);
        btnSend.setImageResource(android.R.drawable.ic_media_pause);
    }

    private void stopPolling() {
        handler.removeCallbacks(pollRunnable);
        btnSend.setImageResource(android.R.drawable.ic_menu_send);
    }

    private void notifyStreamingItem() {
        int count = adapter.getItemCount();
        if (count > 0) {
            adapter.notifyItemChanged(count - 1);
            messageList.scrollToPosition(count - 1);
        }
    }

    /** 刷新列表最后一条（流式结束 / 生图完成共用） */
    private void refreshLastItem() {
        int count = adapter.getItemCount();
        if (count > 0) {
            adapter.notifyItemChanged(count - 1);
            messageList.scrollToPosition(count - 1);
        }
    }

    // ===== 生图（AI 绘图）=====

    private void showImageGenDialog() {
        final List<ModelDef> models = ModelCatalog.getInstance().getImageGenModels();
        if (models.isEmpty()) {
            toast(getString(R.string.imagegen_no_model));
            return;
        }
        if (ImageGenManager.getInstance(this).isRunning()) {
            toast(getString(R.string.imagegen_busy));
            return;
        }

        View content = LayoutInflater.from(this).inflate(R.layout.dialog_image_gen, null, false);
        final Button btnModel = content.findViewById(R.id.btnImageGenModel);
        final Button btnSize = content.findViewById(R.id.btnImageGenSize);
        final EditText editPrompt = content.findViewById(R.id.editImageGenPrompt);

        if (imageGenModel == null || !containsModel(models, imageGenModel.uid())) {
            imageGenModel = models.get(0);
        }
        btnModel.setText(imageGenModel.displayName);
        btnSize.setText(imageGenSize);

        btnModel.setOnClickListener(v -> {
            String[] labels = new String[models.size()];
            for (int i = 0; i < models.size(); i++) {
                ModelDef m = models.get(i);
                Provider p = ModelCatalog.getInstance().findProviderOfModel(m.uid());
                labels[i] = (p != null ? p.displayName() + " · " : "") + m.displayName;
            }
            new AlertDialog.Builder(this)
                    .setTitle(R.string.imagegen_model)
                    .setItems(labels, (d, which) -> {
                        imageGenModel = models.get(which);
                        btnModel.setText(imageGenModel.displayName);
                    })
                    .show();
        });
        btnSize.setOnClickListener(v -> {
            final String[] sizes = getResources().getStringArray(R.array.imagegen_sizes);
            new AlertDialog.Builder(this)
                    .setTitle(R.string.imagegen_size)
                    .setItems(sizes, (d, which) -> {
                        imageGenSize = sizes[which];
                        btnSize.setText(imageGenSize);
                    })
                    .show();
        });

        new AlertDialog.Builder(this)
                .setTitle(R.string.ai_image_gen)
                .setView(content)
                .setPositiveButton(R.string.imagegen_start, (d, which) ->
                        submitImageGen(editPrompt.getText().toString().trim()))
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private boolean containsModel(List<ModelDef> models, String uid) {
        for (ModelDef m : models) {
            if (uid != null && uid.equals(m.uid())) return true;
        }
        return false;
    }

    private void submitImageGen(String prompt) {
        if (prompt == null || prompt.length() == 0) {
            toast(getString(R.string.imagegen_prompt_empty));
            return;
        }
        if (imageGenModel == null) {
            toast(getString(R.string.imagegen_no_model));
            return;
        }
        Provider provider = ModelCatalog.getInstance().findProviderOfModel(imageGenModel.uid());
        if (provider == null || !provider.isValidForFetch()) {
            toast(getString(R.string.imagegen_provider_invalid));
            return;
        }
        Chat chat = chatManager.getCurrentChat();
        if (chat == null) return;

        String messageId = ImageGenManager.getInstance(this)
                .submit(this, chat, provider, imageGenModel, prompt, imageGenSize, 1);
        if (messageId == null) {
            toast(getString(R.string.imagegen_busy));
            return;
        }
        int index = chat.messages.size() - 1;
        adapter.notifyItemInserted(index);
        scrollToBottom();
        toast(getString(R.string.imagegen_started));
    }

    @Override
    public void onImageGenFinished(String messageId, boolean success, String detail) {
        refreshLastItem();
        if (success) {
            toast(getString(R.string.imagegen_done));
        } else {
            toast(getString(R.string.imagegen_failed_format,
                    detail != null ? detail : ""));
        }
    }

    @Override
    public void onStreamFinished(Message assistantMsg, ApiService.ChatCompletionResult result, ApiError error) {
        stopPolling();
        int count = adapter.getItemCount();
        if (count > 0) {
            adapter.notifyItemChanged(count - 1);
            messageList.scrollToPosition(count - 1);
        }
        if (error != null) {
            String message = error.getMessage();
            if (message == null || message.length() == 0) {
                message = getString(R.string.interrupted);
            }
            toast(message);
        } else if (result != null) {
            if (result.notice != null) toast(result.notice);
            if (result.truncated) toast(getString(R.string.truncated));
            if (result.filtered) toast(getString(R.string.content_filtered));
            if (result.trimmed) toast(getString(R.string.ctx_trimmed));
        }
        updateTitle();
    }

    // ===== 界面 =====

    private void loadCurrentChat() {
        Chat chat = chatManager.getCurrentChat();
        if (chat == null) {
            chatManager.startNewChat();
            chat = chatManager.getCurrentChat();
        }
        adapter = new MessageAdapter(chat.messages);
        adapter.setRetryListener(this::retryFailedMessage);
        messageList.setAdapter(adapter);
        updateTitle();
        updateModelButton();
        updateThinkingButton();
        updateRemoveImageButton();
        scrollToBottom();
    }

    private void updateTitle() {
        Chat chat = chatManager.getCurrentChat();
        if (chat != null && chat.title != null && chat.title.length() > 0) {
            setTitle(chat.title);
        } else {
            setTitle(R.string.app_name);
        }
    }

    private void updateModelButton() {
        if (pendingModel == null) {
            btnModel.setText(R.string.model_not_selected);
            return;
        }
        boolean isDefault = defaultModel != null && pendingModel.uid().equals(defaultModel.uid());
        String status = getString(isDefault ? R.string.default_label : R.string.temp_label);
        Provider provider = ModelCatalog.getInstance().findProvider(pendingModel.providerId);
        String providerLabel = provider != null ? provider.displayName() : pendingModel.providerId;
        btnModel.setText(providerLabel + " · " + pendingModel.displayName + " · " + status);
    }

    private void updateThinkingButton() {
        btnThinking.setText("🧠 " + pendingLevel.getDisplay());
    }

    private void updateRemoveImageButton() {
        if (pendingImages.isEmpty()) {
            btnRemoveImage.setVisibility(View.GONE);
        } else {
            // 步骤15：显示当前附件数量，点击清空
            btnRemoveImage.setText(getString(R.string.remove_images_format, pendingImages.size()));
            btnRemoveImage.setVisibility(View.VISIBLE);
        }
    }

    private void refreshDefaultModel() {
        Config config = configManager.getConfig();
        defaultModel = ModelCatalog.getInstance().getModel(config.defaultModelId);
    }

    private void scrollToBottom() {
        int count = adapter.getItemCount();
        if (count > 0) {
            messageList.scrollToPosition(count - 1);
        }
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    // ===== 选择器与图片 =====

    private void showModelSelector() {
        ModelSelectorSheet sheet = new ModelSelectorSheet();
        sheet.setListener(model -> {
            pendingModel = model;
            updateModelButton();
        });
        sheet.show(getSupportFragmentManager(), "model");
    }

    private void showThinkingSelector() {
        final ThinkingLevel[] levels = ThinkingLevel.values();
        String[] labels = new String[levels.length];
        int checked = 0;
        for (int i = 0; i < levels.length; i++) {
            labels[i] = levels[i].getDisplay();
            if (levels[i] == pendingLevel) checked = i;
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.thinking_level)
                .setSingleChoiceItems(labels, checked, (dialog, which) -> {
                    pendingLevel = levels[which];
                    updateThinkingButton();
                    dialog.dismiss();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void showImageMenu() {
        if (pendingModel != null && !pendingModel.supportsVision) {
            toast(getString(R.string.no_vision));
            return;
        }
        new AlertDialog.Builder(this)
                .setItems(new CharSequence[]{
                        getString(R.string.from_gallery),
                        getString(R.string.from_camera)
                }, (dialog, which) -> {
                    if (which == 0) {
                        ImagePicker.pickImage(this);
                    } else {
                        ImagePicker.takePhoto(this);
                    }
                })
                .show();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == ImagePicker.REQ_PICK || requestCode == ImagePicker.REQ_CAMERA) {
            String filename = ImagePicker.onActivityResult(this, requestCode, resultCode, data);
            if (filename != null) {
                pendingImages.add(filename);
                updateRemoveImageButton();
                toast(getString(R.string.images_attached_format, pendingImages.size()));
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == ImagePicker.PERM_CAMERA) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                ImagePicker.takePhoto(this);
            } else {
                toast(getString(R.string.camera_permission_denied));
            }
        }
    }

    // ===== 菜单 =====

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_main, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_new_chat) {
            if (checkNotGenerating()) {
                chatManager.startNewChat();
                chatManager.saveAll();
                loadCurrentChat();
            }
            return true;
        }
        if (id == R.id.action_history) {
            if (checkNotGenerating()) {
                showHistoryDialog();
            }
            return true;
        }
        if (id == R.id.action_settings) {
            startActivity(new Intent(this, SettingsActivity.class));
            return true;
        }
        if (id == R.id.action_set_default) {
            setPendingAsDefault();
            return true;
        }
        if (id == R.id.action_image_gen) {
            showImageGenDialog();
            return true;
        }
        if (id == R.id.action_about) {
            showAbout();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    /** 关于：版本信息；若上次发生崩溃，一并展示堆栈便于反馈（可长按复制） */
    private void showAbout() {
        String message = getString(R.string.about_version_format, BuildConfig.VERSION_NAME);
        final String crash = NumAi5App.readCrash(this);
        if (crash != null && crash.length() > 0) {
            message = message + "\n\n" + getString(R.string.about_last_crash) + "\n\n" + crash;
        }

        float density = getResources().getDisplayMetrics().density;
        int pad = (int) (density * 20);
        TextView text = new TextView(this);
        text.setTextIsSelectable(true);
        text.setTextSize(12f);
        text.setText(message);
        text.setPadding(pad, pad, pad, pad);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(text, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        scroll.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, (int) (density * 420)));

        AlertDialog.Builder builder = new AlertDialog.Builder(this)
                .setTitle(R.string.about)
                .setView(scroll)
                .setPositiveButton(R.string.ok, null);
        if (crash != null && crash.length() > 0) {
            builder.setNeutralButton(R.string.clear_crash_log,
                    (d, w) -> NumAi5App.clearCrash(this));
        }
        builder.show();
    }

    /** 生成期间禁止新建/切换会话（步骤11） */
    private boolean checkNotGenerating() {
        if (streamSession.isGenerating()) {
            toast(getString(R.string.generating));
            return false;
        }
        return true;
    }

    private void showHistoryDialog() {
        final List<Chat> chats = chatManager.getSortedChats();
        String[] titles = new String[chats.size()];
        for (int i = 0; i < chats.size(); i++) {
            String title = chats.get(i).title;
            titles[i] = (title == null || title.length() == 0) ? getString(R.string.new_chat) : title;
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.history)
                .setItems(titles, (dialog, which) -> {
                    chatManager.setCurrentChat(chats.get(which));
                    loadCurrentChat();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void setPendingAsDefault() {
        if (pendingModel == null) {
            toast(getString(R.string.select_model_first));
            return;
        }
        configManager.updateDefaultModel(pendingModel.uid());
        refreshDefaultModel();
        updateModelButton();
        toast(getString(R.string.set_default_success));
    }

    // ===== 生命周期 =====

    @Override
    protected void onResume() {
        super.onResume();
        // 设置页返回：若此前跟随默认模型则更新为新默认；已有临时选择则保留
        ModelDef previousDefault = defaultModel;
        refreshDefaultModel();
        if (pendingModel == null || (previousDefault != null && pendingModel.uid().equals(previousDefault.uid()))) {
            pendingModel = defaultModel;
        }
        updateModelButton();
        updateThinkingButton();
        ImageGenManager.getInstance(this).addListener(this);
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        Chat chat = chatManager.getCurrentChat();
        if (chat != null) {
            outState.putString(STATE_CHAT_ID, chat.id);
        }
        if (pendingModel != null) {
            outState.putString(STATE_PENDING_MODEL, pendingModel.uid());
        }
        outState.putString(STATE_PENDING_LEVEL, pendingLevel.name());
        outState.putStringArrayList(STATE_PENDING_IMAGES, new ArrayList<>(pendingImages));
        // 注意：不保存 isGenerating——执行状态由进程级 StreamSession 承载，
        // 进程被杀后不应伪装成仍在生成（步骤12 修正）
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopPolling();
        streamSession.setListener(null);
        ImageGenManager.getInstance(this).removeListener(this);
    }
}
