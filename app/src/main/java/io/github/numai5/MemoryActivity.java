package io.github.numai5;

import android.content.Context;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.switchmaterial.SwitchMaterial;

import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import io.github.numai5.data.ConfigManager;
import io.github.numai5.data.MemoryGuard;
import io.github.numai5.data.MemoryStore;
import io.github.numai5.data.UiSettings;
import io.github.numai5.model.MemoryFile;

/**
 * 本地记忆管理：列表、模板新建、编辑、删除、固定、参与对话开关。
 * 记忆文件是 filesDir/memory 下的真实 .md，读写都由 MemoryStore 负责。
 */
public class MemoryActivity extends AppCompatActivity {

    private MemoryStore store;
    private ConfigManager configManager;
    private RecyclerView listView;
    private TextView emptyView;
    private SwitchMaterial enabledSwitch;
    private MemoryAdapter adapter;

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(UiSettings.wrap(newBase));
    }

    @Override
    protected void onPostCreate(Bundle savedInstanceState) {
        super.onPostCreate(savedInstanceState);
        UiSettings.applyMargins(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        UiSettings.checkGeneration(this);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_memory);

        store = MemoryStore.getInstance(this);
        configManager = ConfigManager.getInstance();

        listView = findViewById(R.id.memList);
        emptyView = findViewById(R.id.memEmpty);
        enabledSwitch = findViewById(R.id.memEnabled);

        findViewById(R.id.memBack).setOnClickListener(v -> finish());
        findViewById(R.id.memAdd).setOnClickListener(v -> showTemplateDialog());

        enabledSwitch.setChecked(configManager.isMemoryEnabled());
        enabledSwitch.setOnCheckedChangeListener((buttonView, isChecked) ->
                configManager.setMemoryEnabled(isChecked));

        listView.setLayoutManager(new LinearLayoutManager(this));
        adapter = new MemoryAdapter();
        listView.setAdapter(adapter);
        refresh();
    }

    private void refresh() {
        store.runOnIo(() -> {
            final List<MemoryFile> files = store.list();
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                adapter.setItems(files);
                emptyView.setVisibility(files.isEmpty() ? View.VISIBLE : View.GONE);
            });
        });
    }

    private void showTemplateDialog() {
        final String[] items = {
                getString(R.string.memory_template_profile),
                getString(R.string.memory_template_preferences),
                getString(R.string.memory_template_topic)
        };
        new AlertDialog.Builder(this)
                .setTitle(R.string.memory_template_title)
                .setItems(items, (dialog, which) -> {
                    if (which == 0) {
                        openExistingOrNew(MemoryFile.PROFILE_PATH);
                    } else if (which == 1) {
                        openExistingOrNew(MemoryFile.PREFERENCES_PATH);
                    } else {
                        showTopicNameDialog();
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void openExistingOrNew(String path) {
        store.runOnIo(() -> {
            final MemoryFile file = store.read(path);
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                showEditDialog(file, path);
            });
        });
    }

    private void showTopicNameDialog() {
        final EditText input = new EditText(this);
        input.setHint(R.string.memory_topic_hint);
        input.setSingleLine(true);
        new AlertDialog.Builder(this)
                .setTitle(R.string.memory_topic_name)
                .setView(input)
                .setPositiveButton(R.string.memory_add, (dialog, which) -> {
                    String name = input.getText().toString().trim();
                    if (name.length() == 0 || name.indexOf('/') >= 0 || name.indexOf('\\') >= 0
                            || name.startsWith(".")) {
                        toast(getString(R.string.memory_invalid_path));
                        return;
                    }
                    String path = MemoryFile.normalizePath("/topics/" + name + ".md");
                    if (path == null) {
                        toast(getString(R.string.memory_invalid_path));
                        return;
                    }
                    openExistingOrNew(path);
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void showEditDialog(final MemoryFile file, final String path) {
        View view = LayoutInflater.from(this).inflate(R.layout.dialog_memory_edit, null);
        final EditText pathInput = view.findViewById(R.id.memPath);
        final EditText titleInput = view.findViewById(R.id.memTitle);
        final EditText descriptionInput = view.findViewById(R.id.memDescription);
        final EditText aliasesInput = view.findViewById(R.id.memAliases);
        final SwitchMaterial pinnedInput = view.findViewById(R.id.memPinned);
        final EditText bodyInput = view.findViewById(R.id.memBody);
        final TextView sizeText = view.findViewById(R.id.memSize);

        final boolean fixed = MemoryFile.PROFILE_PATH.equals(path)
                || MemoryFile.PREFERENCES_PATH.equals(path);
        pathInput.setText(file != null ? file.path : path);
        pathInput.setEnabled(file == null);
        if (file != null) {
            titleInput.setText(file.title());
            descriptionInput.setText(file.description());
            aliasesInput.setText(joinAliases(file.aliases()));
            bodyInput.setText(file.body());
            pinnedInput.setChecked(file.pinned() || fixed);
        } else {
            titleInput.setText(defaultTitle(path));
            descriptionInput.setText(defaultDescription(path));
            bodyInput.setText(defaultBody(path));
            pinnedInput.setChecked(fixed);
        }
        pinnedInput.setEnabled(!fixed);

        final Runnable updateSize = () -> {
            String content = MemoryFile.compose(
                    MemoryFile.normalizePath(pathInput.getText().toString()) != null
                            ? MemoryFile.normalizePath(pathInput.getText().toString()) : pathInput.getText().toString(),
                    titleInput.getText().toString(),
                    descriptionInput.getText().toString(),
                    splitAliases(aliasesInput.getText().toString()),
                    pinnedInput.isChecked(),
                    bodyInput.getText().toString(),
                    file != null ? file.extraFrontmatter() : null);
            int bytes = content.getBytes(StandardCharsets.UTF_8).length;
            int limit = fixed ? MemoryFile.PINNED_MAX_BYTES : MemoryFile.MAX_BYTES;
            sizeText.setText(getString(R.string.memory_size_format, bytes, limit));
            sizeText.setTextColor(bytes > limit
                    ? ContextCompat.getColor(MemoryActivity.this, R.color.text_error)
                    : ContextCompat.getColor(MemoryActivity.this, R.color.text_meta));
        };
        TextWatcher watcher = new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                updateSize.run();
            }
        };
        pathInput.addTextChangedListener(watcher);
        titleInput.addTextChangedListener(watcher);
        descriptionInput.addTextChangedListener(watcher);
        aliasesInput.addTextChangedListener(watcher);
        bodyInput.addTextChangedListener(watcher);
        updateSize.run();

        AlertDialog.Builder builder = new AlertDialog.Builder(this)
                .setTitle(file == null ? R.string.memory_template_title : R.string.memory_title)
                .setView(view)
                .setPositiveButton(R.string.save, null)
                .setNegativeButton(R.string.cancel, null);
        if (file != null) {
            builder.setNeutralButton(R.string.delete, null);
        }
        final AlertDialog dialog = builder.create();
        dialog.setOnShowListener(d -> {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v ->
                    attemptSave(file, dialog, pathInput, titleInput, descriptionInput,
                            aliasesInput, pinnedInput, bodyInput, fixed));
            if (file != null) {
                dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v ->
                        confirmDelete(file, dialog));
            }
        });
        dialog.show();
    }

    private void attemptSave(final MemoryFile file, final AlertDialog dialog,
                             EditText pathInput, EditText titleInput, EditText descriptionInput,
                             EditText aliasesInput, SwitchMaterial pinnedInput, EditText bodyInput,
                             boolean fixed) {
        final String normalized = MemoryFile.normalizePath(pathInput.getText().toString());
        if (normalized == null) {
            toast(getString(R.string.memory_invalid_path));
            return;
        }
        final String content = MemoryFile.compose(
                normalized,
                titleInput.getText().toString(),
                descriptionInput.getText().toString(),
                splitAliases(aliasesInput.getText().toString()),
                pinnedInput.isChecked(),
                bodyInput.getText().toString(),
                file != null ? file.extraFrontmatter() : null);

        int limit = (fixed || MemoryFile.PROFILE_PATH.equals(normalized)
                || MemoryFile.PREFERENCES_PATH.equals(normalized))
                ? MemoryFile.PINNED_MAX_BYTES : MemoryFile.MAX_BYTES;
        if (content.getBytes(StandardCharsets.UTF_8).length > limit) {
            toast(getString(R.string.memory_save_failed,
                    getString(R.string.memory_size_format, content.getBytes(StandardCharsets.UTF_8).length, limit)));
            return;
        }

        String forbidden = MemoryGuard.findForbidden(content);
        if (forbidden != null) {
            new AlertDialog.Builder(this)
                    .setMessage(getString(R.string.memory_guard_warn, forbidden))
                    .setPositiveButton(R.string.memory_guard_save,
                            (d, w) -> doSave(file, normalized, content, dialog))
                    .setNegativeButton(R.string.memory_guard_cancel, null)
                    .show();
            return;
        }
        doSave(file, normalized, content, dialog);
    }

    private void doSave(MemoryFile file, String path, String content, final AlertDialog dialog) {
        final String ifVersion = file != null ? file.version() : "new";
        store.runOnIo(() -> {
            final MemoryStore.WriteResult result = store.write(path, content, ifVersion);
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                if (result.ok()) {
                    dialog.dismiss();
                    toast(getString(R.string.memory_saved));
                    refresh();
                } else if (result.status == MemoryStore.Status.CONFLICT) {
                    toast(getString(R.string.memory_conflict));
                } else {
                    toast(getString(R.string.memory_save_failed,
                            result.reason == null ? "" : result.reason));
                }
            });
        });
    }

    private void confirmDelete(final MemoryFile file, final AlertDialog dialog) {
        new AlertDialog.Builder(this)
                .setMessage(R.string.memory_delete_confirm)
                .setPositiveButton(R.string.delete, (d, w) -> store.runOnIo(() -> {
                    final MemoryStore.WriteResult result = store.delete(file.path, file.version());
                    runOnUiThread(() -> {
                        if (isFinishing() || isDestroyed()) return;
                        if (result.ok()) {
                            dialog.dismiss();
                            refresh();
                        } else {
                            toast(getString(R.string.memory_delete_failed,
                                    result.reason == null ? "" : result.reason));
                        }
                    });
                }))
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private String defaultTitle(String path) {
        if (MemoryFile.PROFILE_PATH.equals(path)) return "关于我";
        if (MemoryFile.PREFERENCES_PATH.equals(path)) return "回复偏好";
        String name = MemoryFile.nameOf(path).replace('-', ' ').replace('_', ' ').trim();
        if (name.length() == 0) return "";
        return Character.toUpperCase(name.charAt(0)) + name.substring(1);
    }

    private String defaultDescription(String path) {
        if (MemoryFile.PROFILE_PATH.equals(path)) return "身份、职业、长期偏好";
        if (MemoryFile.PREFERENCES_PATH.equals(path)) return "格式、长度、语气";
        return "";
    }

    private String defaultBody(String path) {
        if (MemoryFile.PROFILE_PATH.equals(path) || MemoryFile.PREFERENCES_PATH.equals(path)) {
            return "- [stated] ";
        }
        return "";
    }

    private static List<String> splitAliases(String raw) {
        List<String> result = new ArrayList<>();
        if (raw == null) return result;
        String[] parts = raw.split("[,，]");
        for (String part : parts) {
            String item = part.trim();
            if (item.length() > 0 && !result.contains(item)) result.add(item);
        }
        return result;
    }

    private static String joinAliases(List<String> aliases) {
        if (aliases == null || aliases.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (String alias : aliases) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(alias);
        }
        return sb.toString();
    }

    private void toast(String text) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show();
    }

    private class MemoryAdapter extends RecyclerView.Adapter<MemoryAdapter.Holder> {
        private final List<MemoryFile> items = new ArrayList<>();

        void setItems(List<MemoryFile> files) {
            items.clear();
            if (files != null) items.addAll(files);
            notifyDataSetChanged();
        }

        @Override
        public Holder onCreateViewHolder(ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_memory, parent, false);
            return new Holder(view);
        }

        @Override
        public void onBindViewHolder(Holder holder, int position) {
            final MemoryFile file = items.get(position);
            holder.title.setText(file.title());
            holder.path.setText(file.path);
            holder.description.setText(file.description());
            holder.description.setVisibility(file.description().length() == 0 ? View.GONE : View.VISIBLE);
            String time = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
                    .format(new Date(file.updatedAt));
            String meta = getString(R.string.memory_bytes_format, file.byteSize())
                    + " · " + getString(R.string.memory_updated_format, time);
            if (file.pinned()) meta = meta + " · " + getString(R.string.memory_fixed);
            holder.meta.setText(meta);
            holder.itemView.setOnClickListener(v -> showEditDialog(file, file.path));
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        class Holder extends RecyclerView.ViewHolder {
            final TextView title;
            final TextView path;
            final TextView description;
            final TextView meta;

            Holder(View view) {
                super(view);
                title = view.findViewById(R.id.memItemTitle);
                path = view.findViewById(R.id.memItemPath);
                description = view.findViewById(R.id.memItemDesc);
                meta = view.findViewById(R.id.memItemMeta);
            }
        }
    }
}