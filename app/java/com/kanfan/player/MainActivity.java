package com.kanfan.player;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import android.media.MediaMetadataRetriever;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 片库：第一层只列「已添加的文件夹」，点进去才看里面的子文件夹与视频，
 * 可以逐级返回，避免一个文件夹的内容把首屏塞满。
 */
public class MainActivity extends Activity {

    private static final int REQ_TREE = 2001;
    private static final int REQ_ARCHIVE = 2002;
    private static final String PREF = "kanfan_folders";
    private static final String KEY_TREES = "trees";        // 旧格式：StringSet（无序）
    private static final String KEY_ORDER = "trees_order";  // 新格式：按行存储，保留顺序

    private static final int T_HEADER = 0, T_VIDEO = 1, T_DIR = 2, T_BOOK = 3, T_IMAGE = 4,
            T_ARCHIVE = 5, T_OTHER = 6, T_RESTORE = 7;

    private ListView list;
    private TextView subtitle, manageBtn, addBtn, titleView;
    private TextView allBtn, delBtn, doneBtn, zipBtn;
    private LinearLayout manageBar;
    private ImageView backBtn;
    private TextView empty;
    private LinearLayout root;

    // 压缩进度
    private FrameLayout progressLayer;
    private ProgressBar zipBar;
    private TextView zipTitle, zipFile, zipCancel;
    private volatile boolean zipping, zipCancelled;

    private final List<Row> rows = new ArrayList<>();
    private final List<Entry> all = new ArrayList<>();
    private final List<String[]> folders = new ArrayList<>(); // {uri, name}，顺序即展示顺序
    private final List<Crumb> stack = new ArrayList<>();      // 导航栈，空 = 根层级
    private Adapter adapter;
    private long lastBack;

    // 管理（多选删除 / 拖动排序）
    private boolean manageMode;
    private final java.util.Set<String> hidden = new java.util.LinkedHashSet<>();
    private final java.util.Set<String> selected = new java.util.LinkedHashSet<>();
    private int dragPos = -1;

    private final ExecutorService pool = Executors.newFixedThreadPool(4);
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final LruCache<String, Bitmap> thumbs = new LruCache<String, Bitmap>(4 * 1024) {
        @Override
        protected int sizeOf(String k, Bitmap v) {
            return Math.max(1, v.getByteCount() / 1024);
        }
    };

    /** 一级目录：tree=授权目录，rel=相对路径，name=显示名 */
    static class Crumb {
        final String tree, rel, name;

        Crumb(String tree, String rel, String name) {
            this.tree = tree;
            this.rel = rel;
            this.name = name;
        }
    }

    static class Row {
        int type;
        String key;
        String label;   // 标题 / 文件夹名
        String sub;     // 文件夹副标题
        String tree;
        String rel;
        int index;      // 根层级时的文件夹下标
        boolean checked;
        Entry item;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();
        loadFolders();
        rescan();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (adapter != null) adapter.notifyDataSetChanged();
    }

    // ---------------- 文件夹管理 ----------------

    private SharedPreferences sp() {
        return getSharedPreferences(PREF, MODE_PRIVATE);
    }

    private void loadFolders() {
        folders.clear();
        String raw = sp().getString(KEY_ORDER, null);
        if (raw == null) {
            // 从旧的无序 StringSet 迁移一次
            StringBuilder sb = new StringBuilder();
            try {
                for (String s : sp().getStringSet(KEY_TREES, new java.util.LinkedHashSet<String>())) {
                    if (s.indexOf("||") <= 0) continue;
                    if (sb.length() > 0) sb.append('\n');
                    sb.append(s);
                }
            } catch (Exception ignored) {
            }
            raw = sb.toString();
            sp().edit().putString(KEY_ORDER, raw).apply();
        }
        for (String line : raw.split("\n")) {
            int p = line.indexOf("||");
            if (p <= 0) continue;
            folders.add(new String[]{line.substring(0, p), line.substring(p + 2)});
        }
    }

    /** 按当前顺序整表写回，保证拖动排序能持久化 */
    private void saveFolders() {
        StringBuilder sb = new StringBuilder();
        for (String[] f : folders) {
            if (sb.length() > 0) sb.append('\n');
            sb.append(f[0]).append("||").append(f[1].replace('\n', ' '));
        }
        sp().edit().putString(KEY_ORDER, sb.toString()).apply();
    }

    private void pickFolder() {
        try {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                    | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                    | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
            startActivityForResult(i, REQ_TREE);
        } catch (Exception e) {
            Toast.makeText(this, "系统没有可用的文件选择器", Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_ARCHIVE) {
            if (resultCode == RESULT_OK) rescan();
            return;
        }
        if (requestCode != REQ_TREE || resultCode != RESULT_OK || data == null) return;
        Uri tree = data.getData();
        if (tree == null) return;
        int takeFlags = data.getFlags()
                & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        if (takeFlags == 0) takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION;
        try {
            getContentResolver().takePersistableUriPermission(tree, takeFlags);
        } catch (Exception ignored) {
        }
        String name = Scan.treeName(this, tree);
        for (String[] f : folders) {
            if (f[0].equals(tree.toString())) {
                Toast.makeText(this, "这个文件夹已经添加过了", Toast.LENGTH_SHORT).show();
                return;
            }
        }
        folders.add(new String[]{tree.toString(), name});
        saveFolders();
        stack.clear();
        rescan();
    }

    /** 进入管理：每行左侧出现圆圈，可多选删除、长按拖动排序 */
    private void enterManage() {
        if (folders.isEmpty()) {
            Toast.makeText(this, "还没有添加文件夹", Toast.LENGTH_SHORT).show();
            return;
        }
        manageMode = true;
        selected.clear();
        dragPos = -1;
        rebuildRows();
        Toast.makeText(this, stack.isEmpty()
                ? "点圆圈选择文件夹，长按可拖动排序"
                : "点圆圈选择文件，选好后可以隐藏或压缩", Toast.LENGTH_SHORT).show();
    }

    private void exitManage() {
        manageMode = false;
        selected.clear();
        dragPos = -1;
        rebuildRows();
    }

    private void toggleKey(String key) {
        if (key == null || key.isEmpty()) return;
        if (!selected.remove(key)) selected.add(key);
        rebuildRows();
    }

    /** 当前层能勾选的 key：根目录是各个文件夹，目录内是各个文件 */
    private List<String> selectableKeys() {
        List<String> out = new ArrayList<>();
        if (stack.isEmpty()) {
            for (String[] f : folders) out.add(f[0]);
        } else {
            for (Row r : rows) {
                if (r.item != null) out.add(r.key);
            }
        }
        return out;
    }

    private void toggleSelectAll() {
        List<String> keys = selectableKeys();
        if (!keys.isEmpty() && selected.containsAll(keys)) {
            selected.clear();
        } else {
            selected.clear();
            selected.addAll(keys);
        }
        rebuildRows();
    }

    private void deleteSelected() {
        if (selected.isEmpty()) {
            Toast.makeText(this, stack.isEmpty()
                    ? "先点圆圈选中要移除的文件夹"
                    : "先点圆圈选中要隐藏的文件", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!stack.isEmpty()) {
            hideSelected();
            return;
        }
        final int n = selected.size();
        new AlertDialog.Builder(this)
                .setMessage("从应用里移除选中的 " + n + " 个文件夹？\n\n只是不再显示，手机上的文件不会被删除。")
                .setNegativeButton("取消", null)
                .setPositiveButton("移除", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int w) {
                        for (int i = folders.size() - 1; i >= 0; i--) {
                            String uri = folders.get(i)[0];
                            if (!selected.contains(uri)) continue;
                            try {
                                getContentResolver().releasePersistableUriPermission(
                                        Uri.parse(uri), Intent.FLAG_GRANT_READ_URI_PERMISSION);
                            } catch (Exception ignored) {
                            }
                            folders.remove(i);
                        }
                        selected.clear();
                        manageMode = false;
                        saveFolders();
                        stack.clear();
                        rescan();
                    }
                })
                .show();
    }

    /** 目录内：把选中的文件从片库里隐藏（不动磁盘） */
    private void hideSelected() {
        final int n = selected.size();
        new AlertDialog.Builder(this)
                .setMessage("在片库里隐藏选中的 " + n + " 个文件？\n\n只是不再显示，手机上的文件不会被删除。"
                        + "\n回到首屏可以点「已隐藏」恢复。")
                .setNegativeButton("取消", null)
                .setPositiveButton("隐藏", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int w) {
                        hidden.addAll(selected);
                        saveHidden();
                        selected.clear();
                        manageMode = false;
                        rescan();
                        Toast.makeText(MainActivity.this, "已隐藏 " + n + " 个文件",
                                Toast.LENGTH_SHORT).show();
                    }
                })
                .show();
    }

    private void hideOne(Entry e) {
        String key = docKey(e);
        if (key.isEmpty()) return;
        hidden.add(key);
        saveHidden();
        rescan();
        Toast.makeText(this, "已隐藏，可在首屏底部恢复", Toast.LENGTH_SHORT).show();
    }

    private void restoreHidden() {
        new AlertDialog.Builder(this)
                .setMessage("恢复显示已隐藏的 " + hidden.size() + " 个文件？")
                .setNegativeButton("取消", null)
                .setPositiveButton("恢复", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int w) {
                        hidden.clear();
                        saveHidden();
                        rescan();
                    }
                })
                .show();
    }

    private String docKey(Entry e) {
        if (e == null || e.uri == null) return "";
        try {
            return android.provider.DocumentsContract.getDocumentId(e.uri);
        } catch (Exception ex) {
            return e.uri.toString();
        }
    }

    private void loadHidden() {
        hidden.clear();
        try {
            hidden.addAll(sp().getStringSet("hidden", new java.util.LinkedHashSet<String>()));
        } catch (Exception ignored) {
        }
    }

    private void saveHidden() {
        sp().edit().putStringSet("hidden", new java.util.LinkedHashSet<>(hidden)).apply();
    }

    // ---------------- UI ----------------

    private void buildUi() {
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(getColor(R.color.bg));

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);
        int ph = Ui.dp(this, 20);
        header.setPadding(ph, Ui.dp(this, 24), ph, Ui.dp(this, 8));

        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);

        backBtn = new ImageView(this);
        backBtn.setImageResource(R.drawable.ic_back);
        backBtn.setColorFilter(getColor(R.color.text));
        backBtn.setPadding(Ui.dp(this, 10), Ui.dp(this, 10), Ui.dp(this, 10), Ui.dp(this, 10));
        backBtn.setBackground(Ui.circle(this, Ui.alpha(getColor(R.color.text), 0.06f)));
        backBtn.setVisibility(View.GONE);
        backBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                goUp();
            }
        });
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                Ui.dp(this, 36), Ui.dp(this, 36));
        blp.setMargins(0, 0, Ui.dp(this, 10), 0);
        titleRow.addView(backBtn, blp);

        titleView = new TextView(this);
        titleView.setText("看番");
        titleView.setTextColor(getColor(R.color.text));
        titleView.setTextSize(26f);
        titleView.setTypeface(Typeface.DEFAULT_BOLD);
        titleView.setLetterSpacing(0.06f);
        titleView.setSingleLine(true);
        titleView.setEllipsize(android.text.TextUtils.TruncateAt.END);
        titleRow.addView(titleView, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        manageBtn = chip("管理", false);
        manageBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                enterManage();
            }
        });
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        mlp.setMargins(Ui.dp(this, 8), 0, Ui.dp(this, 8), 0);
        titleRow.addView(manageBtn, mlp);

        addBtn = chip("＋ 添加", true);
        addBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pickFolder();
            }
        });
        titleRow.addView(addBtn);

        allBtn = chip("全选", false);
        allBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toggleSelectAll();
            }
        });

        delBtn = chip("删除", false);
        delBtn.setTextColor(0xFFFFFFFF);
        delBtn.setBackground(Ui.round(this, 0xFFE5484D, 20f));
        delBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                deleteSelected();
            }
        });

        doneBtn = chip("完成", true);
        doneBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                exitManage();
            }
        });

        zipBtn = chip("压缩", false);
        zipBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                compressSelected();
            }
        });

        header.addView(titleRow);

        subtitle = new TextView(this);
        subtitle.setTextColor(getColor(R.color.dim));
        subtitle.setTextSize(12.5f);
        subtitle.setPadding(0, Ui.dp(this, 6), 0, 0);
        subtitle.setText("还没有添加文件夹");
        header.addView(subtitle);

        root.addView(header);

        list = new ListView(this);
        list.setDivider(null);
        list.setDividerHeight(0);
        list.setSelector(new ColorDrawable(Color.TRANSPARENT));
        list.setVerticalScrollBarEnabled(false);
        list.setClipToPadding(false);
        list.setPadding(ph, Ui.dp(this, 4), ph, Ui.dp(this, 24));
        adapter = new Adapter();
        list.setAdapter(adapter);
        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                Row r = rows.get(position);
                if (r.type == T_RESTORE) {
                    restoreHidden();
                    return;
                }
                if (manageMode) {
                    if (r.type == T_HEADER) return;
                    toggleKey(r.key);
                    return;
                }
                if (r.type == T_DIR) {
                    stack.add(new Crumb(r.tree, r.rel, r.label));
                    rebuildRows();
                    list.setSelection(0);
                } else if (r.type == T_VIDEO && r.item != null) {
                    open(r.item);
                } else if (r.type == T_BOOK && r.item != null) {
                    read(r.item);
                } else if (r.type == T_IMAGE && r.item != null) {
                    openImage(r.item);
                } else if (r.type == T_ARCHIVE && r.item != null) {
                    openArchive(r.item);
                } else if (r.type == T_OTHER && r.item != null) {
                    openOther(r.item);
                }
            }
        });

        // 管理模式下长按拖动排序
        list.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            @Override
            public boolean onItemLongClick(AdapterView<?> parent, View view, int position, long id) {
                if (position < 0 || position >= rows.size()) return false;
                Row row = rows.get(position);
                if (manageMode) {
                    if (row.type != T_DIR) return false;
                    dragPos = position;
                    view.setAlpha(0.45f);
                    return true;
                }
                if (row.type == T_HEADER || row.item == null) return false;
                rowMenu(row);
                return true;
            }
        });
        list.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent e) {
                if (dragPos < 0) return false;
                if (e.getAction() == MotionEvent.ACTION_MOVE) {
                    int pos = list.pointToPosition((int) e.getX(), (int) e.getY());
                    if (pos >= 0 && pos < rows.size() && pos != dragPos) {
                        Row from = rows.get(dragPos);
                        Row to = rows.get(pos);
                        if (from.type == T_DIR && to.type == T_DIR
                                && from.index >= 0 && to.index >= 0
                                && from.index < folders.size() && to.index < folders.size()) {
                            String[] moved = folders.remove(from.index);
                            folders.add(to.index, moved);
                            saveFolders();
                            rebuildRows();
                            dragPos = pos;
                        }
                    }
                    return true;
                }
                if (e.getAction() == MotionEvent.ACTION_UP || e.getAction() == MotionEvent.ACTION_CANCEL) {
                    dragPos = -1;
                    rebuildRows();
                    return true;
                }
                return true;
            }
        });

        root.addView(list, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // 管理操作栏固定在底部：全选 …… 删除 / 完成，永远看得见
        manageBar = new LinearLayout(this);
        manageBar.setOrientation(LinearLayout.HORIZONTAL);
        manageBar.setGravity(Gravity.CENTER_VERTICAL);
        manageBar.setPadding(ph, Ui.dp(this, 10), ph, Ui.dp(this, 14));
        manageBar.setBackgroundColor(getColor(R.color.bg));
        manageBar.setVisibility(View.GONE);
        manageBar.addView(allBtn);
        manageBar.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1f));
        LinearLayout.LayoutParams zlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        zlp.setMargins(0, 0, Ui.dp(this, 10), 0);
        manageBar.addView(zipBtn, zlp);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dlp.setMargins(0, 0, Ui.dp(this, 10), 0);
        manageBar.addView(delBtn, dlp);
        manageBar.addView(doneBtn);
        root.addView(manageBar);

        empty = new TextView(this);
        empty.setTextColor(getColor(R.color.dim));
        empty.setTextSize(14f);
        empty.setGravity(Gravity.CENTER);
        empty.setLineSpacing(Ui.dp(this, 4), 1f);
        root.addView(empty, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        FrameLayout shell = new FrameLayout(this);
        shell.addView(root, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        buildZipProgress(shell);
        setContentView(shell);
        refreshHeaderState();
    }

    /** 压缩进度：盖在整页之上，可取消 */
    private void buildZipProgress(FrameLayout shell) {
        progressLayer = new FrameLayout(this);
        progressLayer.setBackgroundColor(0xE6000000);
        progressLayer.setVisibility(View.GONE);
        progressLayer.setClickable(true);

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(Ui.dp(this, 26), Ui.dp(this, 24), Ui.dp(this, 26), Ui.dp(this, 18));
        box.setBackground(Ui.round(this, getColor(R.color.surface), 18f));

        zipTitle = new TextView(this);
        zipTitle.setText("正在压缩");
        zipTitle.setTextColor(getColor(R.color.text));
        zipTitle.setTextSize(15f);
        zipTitle.setTypeface(Typeface.DEFAULT_BOLD);
        box.addView(zipTitle);

        zipBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        zipBar.setMax(100);
        zipBar.setProgress(0);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 6));
        blp.topMargin = Ui.dp(this, 14);
        box.addView(zipBar, blp);

        zipFile = new TextView(this);
        zipFile.setTextColor(getColor(R.color.dim));
        zipFile.setTextSize(12f);
        zipFile.setSingleLine(true);
        zipFile.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        flp.topMargin = Ui.dp(this, 10);
        box.addView(zipFile, flp);

        zipCancel = new TextView(this);
        zipCancel.setText("取消");
        zipCancel.setTextColor(getColor(R.color.text));
        zipCancel.setTextSize(13.5f);
        zipCancel.setGravity(Gravity.CENTER);
        zipCancel.setPadding(Ui.dp(this, 18), Ui.dp(this, 9), Ui.dp(this, 18), Ui.dp(this, 9));
        zipCancel.setBackground(Ui.round(this, getColor(R.color.surface2), 18f));
        zipCancel.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                zipCancelled = true;
                zipTitle.setText("正在停止…");
            }
        });
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.topMargin = Ui.dp(this, 16);
        clp.gravity = Gravity.END;
        box.addView(zipCancel, clp);

        progressLayer.addView(box, new FrameLayout.LayoutParams(
                Ui.dp(this, 300), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        shell.addView(progressLayer, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    // ---------------- 压缩 ----------------

    private void compressSelected() {
        if (zipping) return;
        if (selected.isEmpty()) {
            Toast.makeText(this, "先点圆圈选中要压缩的文件", Toast.LENGTH_SHORT).show();
            return;
        }
        final List<Entry> picked = new ArrayList<>();
        for (Row r : rows) {
            if (r.item != null && selected.contains(r.key)) picked.add(r.item);
        }
        if (picked.isEmpty()) {
            Toast.makeText(this, "压缩要在文件夹里选文件", Toast.LENGTH_SHORT).show();
            return;
        }

        final String folder = stack.isEmpty() ? "压缩包" : stack.get(stack.size() - 1).name;
        final EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setText(suggestZipName(folder));
        input.setSelection(input.getText().length());
        input.setTextColor(getColor(R.color.text));
        input.setHintTextColor(getColor(R.color.dim));
        input.setHint("压缩包名字");

        final CheckBox storeOnly = new CheckBox(this);
        storeOnly.setText("仅打包，不压缩（更快）");
        storeOnly.setTextColor(getColor(R.color.dim));
        storeOnly.setTextSize(13f);
        storeOnly.setChecked(false);

        TextView note = new TextView(this);
        note.setText("格式为 ZIP，任何设备都能打开。\n"
                + "rar 的压缩算法是专有授权，只能解不能压；7z 对视频也没有压缩收益。");
        note.setTextColor(getColor(R.color.dim));
        note.setTextSize(11.5f);
        note.setLineSpacing(0, 1.4f);

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int p = Ui.dp(this, 20);
        box.setPadding(p, Ui.dp(this, 6), p, 0);
        box.addView(input);
        box.addView(storeOnly);
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        nlp.topMargin = Ui.dp(this, 4);
        box.addView(note, nlp);

        new AlertDialog.Builder(this)
                .setTitle("压缩 " + picked.size() + " 个文件")
                .setView(box)
                .setNegativeButton("取消", null)
                .setPositiveButton("开始", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int which) {
                        String name = input.getText().toString().trim();
                        if (name.isEmpty()) name = suggestZipName(folder);
                        if (!name.toLowerCase(Locale.US).endsWith(".zip")) name += ".zip";
                        final String outName = name;
                        String rel = stack.isEmpty() ? "" : stack.get(stack.size() - 1).rel;
                        if (nameTaken(outName, rel)) {
                            new AlertDialog.Builder(MainActivity.this)
                                    .setMessage("已经有「" + outName + "」了，要覆盖吗？")
                                    .setNegativeButton("取消", null)
                                    .setPositiveButton("覆盖", new android.content.DialogInterface.OnClickListener() {
                                        @Override
                                        public void onClick(android.content.DialogInterface d2, int w2) {
                                            startZip(picked, outName, storeOnly.isChecked());
                                        }
                                    })
                                    .show();
                            return;
                        }
                        startZip(picked, outName, storeOnly.isChecked());
                    }
                })
                .show();
    }

    /** 默认名：文件夹名.zip；同一目录里重名就加 (2) */
    private String suggestZipName(String folder) {
        String base = folder == null ? "压缩包" : folder.trim();
        if (base.isEmpty()) base = "压缩包";
        String rel = stack.isEmpty() ? "" : stack.get(stack.size() - 1).rel;
        String name = base + ".zip";
        for (int i = 2; i <= 99; i++) {
            if (!nameTaken(name, rel)) return name;
            name = base + " (" + i + ").zip";
        }
        return base + ".zip";
    }

    private boolean nameTaken(String name, String rel) {
        for (Entry e : all) {
            if (name.equalsIgnoreCase(e.name) && rel.equals(e.relPath)) return true;
        }
        return false;
    }

    private void startZip(final List<Entry> picked, final String outName, final boolean storeOnly) {
        zipping = true;
        zipCancelled = false;
        progressLayer.setVisibility(View.VISIBLE);
        zipTitle.setText("正在压缩");
        zipBar.setProgress(0);
        zipFile.setText(outName);

        final long total;
        long sum = 0;
        for (Entry e : picked) sum += Math.max(0, e.size);
        total = sum;

        new Thread(new Runnable() {
            @Override
            public void run() {
                Uri doc = null;
                String err = null;
                Uri tree = Uri.parse(picked.get(0).tree);
                try {
                    Uri parent = Saf.parentDocument(tree, picked.get(0).uri);
                    doc = Saf.createFile(getContentResolver(), tree, parent, outName);
                    if (doc == null) throw new IOException("建不了文件");
                    OutputStream os = Saf.openOut(getContentResolver(), doc);
                    ZipWrite w = new ZipWrite(os, storeOnly ? 0 : 6, new ZipWrite.Listener() {
                        private long last;

                        @Override
                        public void onProgress(final String name, final long done, final long all) {
                            long now = System.currentTimeMillis();
                            if (now - last < 120) return;
                            last = now;
                            ui.post(new Runnable() {
                                @Override
                                public void run() {
                                    if (!zipping) return;
                                    zipFile.setText(name);
                                    int pct = all > 0 ? (int) (done * 100 / all) : 0;
                                    zipBar.setProgress(Math.min(99, pct));
                                }
                            });
                        }

                        @Override
                        public boolean cancelled() {
                            return zipCancelled;
                        }
                    });
                    w.setTotal(total);
                    for (Entry e : picked) {
                        if (zipCancelled) throw new IOException("已取消");
                        w.add(sourceOf(e));
                    }
                    w.close();
                } catch (Throwable t) {
                    err = t.getMessage() == null ? "压缩失败" : t.getMessage();
                    if (doc != null) {
                        try {
                            android.provider.DocumentsContract.deleteDocument(
                                    getContentResolver(), doc);
                        } catch (Exception ignored) {
                        }
                    }
                }
                final String fe = err;
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        zipping = false;
                        progressLayer.setVisibility(View.GONE);
                        if (fe != null) {
                            Toast.makeText(MainActivity.this,
                                    zipCancelled ? "已取消，未生成压缩包" : fe,
                                    Toast.LENGTH_LONG).show();
                            return;
                        }
                        selected.clear();
                        manageMode = false;
                        rescan();
                        Toast.makeText(MainActivity.this, "已压缩成 " + outName,
                                Toast.LENGTH_SHORT).show();
                    }
                });
            }
        }).start();
    }

    private ZipWrite.Source sourceOf(final Entry e) {
        return new ZipWrite.Source() {
            @Override
            public String name() {
                return e.name;
            }

            @Override
            public long size() {
                return e.size > 0 ? e.size : -1;
            }

            @Override
            public long time() {
                return 0;
            }

            @Override
            public InputStream open() throws IOException {
                InputStream in = getContentResolver().openInputStream(e.uri);
                if (in == null) throw new IOException("读不到 " + e.name);
                return in;
            }
        };
    }

    private TextView chip(String text, boolean primary) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(13f);
        if (primary) {
            t.setTextColor(getColor(R.color.bg));
            t.setBackground(Ui.round(this, getColor(R.color.accent), 20f));
            t.setTypeface(Typeface.DEFAULT_BOLD);
        } else {
            t.setTextColor(getColor(R.color.accent));
            t.setBackground(Ui.roundStroke(this, Ui.alpha(getColor(R.color.accent), 0.10f),
                    20f, Ui.alpha(getColor(R.color.accent), 0.35f), 1f));
        }
        t.setPadding(Ui.dp(this, 14), Ui.dp(this, 7), Ui.dp(this, 14), Ui.dp(this, 7));
        return t;
    }

    private void refreshHeaderState() {
        boolean atRoot = stack.isEmpty();
        backBtn.setVisibility(atRoot && !manageMode ? View.GONE : View.VISIBLE);
        addBtn.setVisibility(atRoot && !manageMode ? View.VISIBLE : View.GONE);
        manageBtn.setVisibility(!manageMode && !folders.isEmpty() ? View.VISIBLE : View.GONE);

        if (manageBar != null) manageBar.setVisibility(manageMode ? View.VISIBLE : View.GONE);
        if (zipBtn != null) {
            zipBtn.setVisibility(manageMode && !atRoot ? View.VISIBLE : View.GONE);
            zipBtn.setText(selected.isEmpty() ? "压缩" : "压缩(" + selected.size() + ")");
        }
        if (manageMode) {
            List<String> keys = selectableKeys();
            allBtn.setText(!keys.isEmpty() && selected.containsAll(keys) ? "取消全选" : "全选");
            String verb = stack.isEmpty() ? "删除" : "隐藏";
            delBtn.setText(selected.isEmpty() ? verb : verb + "(" + selected.size() + ")");
        }
    }

    // ---------------- 扫描 ----------------

    private void rescan() {
        refreshHeaderState();
        if (folders.isEmpty()) {
            all.clear();
            stack.clear();
            rebuildRows();
            return;
        }
        subtitle.setText("正在扫描…");
        final List<String[]> snapshot = new ArrayList<>(folders);
        pool.execute(new Runnable() {
            @Override
            public void run() {
                final List<Entry> found = new ArrayList<>();
                for (String[] f : snapshot) {
                    try {
                        found.addAll(Scan.scanTree(MainActivity.this, Uri.parse(f[0])));
                    } catch (Exception ignored) {
                    }
                }
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        all.clear();
                        for (Entry e : found) {
                            if (!hidden.contains(docKey(e))) all.add(e);
                        }
                        pruneStack();
                        rebuildRows();
                        measureDurations();
                    }
                });
            }
        });
    }

    /** 若当前所在目录已不存在（文件夹被移除），退回根层级 */
    private void pruneStack() {
        if (stack.isEmpty()) return;
        String tree = stack.get(0).tree;
        boolean alive = false;
        for (String[] f : folders) {
            if (f[0].equals(tree)) {
                alive = true;
                break;
            }
        }
        if (!alive) stack.clear();
    }

    private void measureDurations() {
        if (all.isEmpty()) return;
        final List<Entry> snapshot = new ArrayList<>(all);
        final AtomicInteger done = new AtomicInteger(0);
        final int total = snapshot.size();
        for (final Entry v : snapshot) {
            pool.execute(new Runnable() {
                @Override
                public void run() {
                    if (!v.isBook() && v.duration <= 0) {
                        v.duration = Scan.durationOf(MainActivity.this, v.uri);
                    }
                    if (done.incrementAndGet() >= total) {
                        ui.post(new Runnable() {
                            @Override
                            public void run() {
                                adapter.notifyDataSetChanged();
                            }
                        });
                    }
                }
            });
        }
    }

    // ---------------- 行构建 ----------------

    private void rebuildRows() {
        rows.clear();
        if (stack.isEmpty()) buildRootRows();
        else buildDirRows();
        decorateSelection();

        adapter.notifyDataSetChanged();
        boolean has = !rows.isEmpty();
        list.setVisibility(has ? View.VISIBLE : View.GONE);
        empty.setVisibility(has ? View.GONE : View.VISIBLE);
        if (!has) setEmptyText();
        updateHeader();
    }

    private void buildRootRows() {
        for (int i = 0; i < folders.size(); i++) {
            String[] f = folders.get(i);
            int videos = 0, books = 0, images = 0, archives = 0, others = 0;
            long size = 0;
            for (Entry v : all) {
                if (!f[0].equals(v.tree)) continue;
                if (v.isBook()) books++;
                else if (v.isImage()) images++;
                else if (v.isArchive()) archives++;
                else if (v.isOther()) others++;
                else videos++;
                size += v.size;
            }
            Row r = new Row();
            r.type = T_DIR;
            r.index = i;
            r.checked = selected.contains(f[0]);
            r.label = f[1];
            r.tree = f[0];
            r.rel = "";
            r.sub = subText(videos, books, images, archives, others, size);
            rows.add(r);
        }
        if (!hidden.isEmpty()) {
            Row r = new Row();
            r.type = T_RESTORE;
            r.label = "已隐藏 " + hidden.size() + " 个文件 \u00b7 点此恢复";
            rows.add(r);
        }
    }

    /** 「52 个视频 · 3 本 · 13.4 GB」这样的副标题 */
    private String subText(int videos, int books, int images, int archives, int others, long size) {
        StringBuilder sb = new StringBuilder();
        if (videos > 0) sb.append(videos).append(" 个视频");
        if (books > 0) {
            if (sb.length() > 0) sb.append(" · ");
            sb.append(books).append(" 本");
        }
        if (images > 0) {
            if (sb.length() > 0) sb.append(" · ");
            sb.append(images).append(" 张图");
        }
        if (archives > 0) {
            if (sb.length() > 0) sb.append(" · ");
            sb.append(archives).append(" 个压缩包");
        }
        if (others > 0) {
            if (sb.length() > 0) sb.append(" · ");
            sb.append(others).append(" 个其他文件");
        }
        if (sb.length() == 0) return "空文件夹";
        return sb.append(" · ").append(sizeText(size)).toString();
    }

    private void buildDirRows() {
        Crumb cur = stack.get(stack.size() - 1);

        List<Entry> scoped = new ArrayList<>();
        for (Entry v : all) {
            if (cur.tree.equals(v.tree)) scoped.add(v);
        }
        String[] rels = new String[scoped.size()];
        long[] sizes = new long[scoped.size()];
        int[] kinds = new int[scoped.size()];
        for (int i = 0; i < scoped.size(); i++) {
            Entry e = scoped.get(i);
            rels[i] = e.relPath;
            sizes[i] = e.size;
            kinds[i] = e.kind;
        }

        List<Nav.Dir> dirs = Nav.subdirsOf(cur.rel, rels, sizes, kinds);
        Collections.sort(dirs, new java.util.Comparator<Nav.Dir>() {
            @Override
            public int compare(Nav.Dir a, Nav.Dir b) {
                return Ui.NATURAL.compare(a.name, b.name);
            }
        });

        if (!dirs.isEmpty()) {
            Row h = new Row();
            h.type = T_HEADER;
            h.label = "文件夹";
            rows.add(h);
            for (Nav.Dir d : dirs) {
                Row r = new Row();
                r.type = T_DIR;
                r.label = d.name;
                r.tree = cur.tree;
                r.rel = d.rel;
                r.sub = subText(d.videoCount, d.bookCount, d.imageCount, d.archiveCount, d.otherCount, d.size);
                rows.add(r);
            }
        }

        List<Entry> videos = new ArrayList<>();
        List<Entry> books = new ArrayList<>();
        List<Entry> pics = new ArrayList<>();
        List<Entry> zips = new ArrayList<>();
        List<Entry> others = new ArrayList<>();
        for (int idx : Nav.directOf(cur.rel, rels)) {
            Entry e = scoped.get(idx);
            if (e.isBook()) books.add(e);
            else if (e.isImage()) pics.add(e);
            else if (e.isArchive()) zips.add(e);
            else if (e.isOther()) others.add(e);
            else videos.add(e);
        }
        java.util.Comparator<Entry> byTitle = new java.util.Comparator<Entry>() {
            @Override
            public int compare(Entry a, Entry b) {
                return Ui.NATURAL.compare(a.title, b.title);
            }
        };
        Collections.sort(videos, byTitle);
        Collections.sort(books, byTitle);
        Collections.sort(pics, byTitle);
        Collections.sort(zips, byTitle);
        Collections.sort(others, byTitle);

        if (!videos.isEmpty()) {
            Row h = new Row();
            h.type = T_HEADER;
            h.label = "视频";
            rows.add(h);
            for (Entry v : videos) {
                Row r = new Row();
                r.type = T_VIDEO;
                r.item = v;
                rows.add(r);
            }
        }

        if (!books.isEmpty()) {
            Row h = new Row();
            h.type = T_HEADER;
            h.label = "书籍";
            rows.add(h);
            for (Entry b : books) {
                Row r = new Row();
                r.type = T_BOOK;
                r.item = b;
                rows.add(r);
            }
        }

        if (!pics.isEmpty()) {
            Row h = new Row();
            h.type = T_HEADER;
            h.label = "图片";
            rows.add(h);
            for (Entry im : pics) {
                Row r = new Row();
                r.type = T_IMAGE;
                r.item = im;
                rows.add(r);
            }
        }

        if (!zips.isEmpty()) {
            Row h = new Row();
            h.type = T_HEADER;
            h.label = "压缩包";
            rows.add(h);
            for (Entry z : zips) {
                Row r = new Row();
                r.type = T_ARCHIVE;
                r.item = z;
                rows.add(r);
            }
        }

        if (!others.isEmpty()) {
            Row h = new Row();
            h.type = T_HEADER;
            h.label = "其他文件";
            rows.add(h);
            for (Entry o : others) {
                Row r = new Row();
                r.type = T_OTHER;
                r.item = o;
                rows.add(r);
            }
        }
    }

    /** 体积文案：0 也要显示成「0 MB」，避免副标题出现空尾巴 */
    private String sizeText(long b) {
        return b <= 0 ? "0 MB" : Ui.fmtSize(b);
    }

    private void setEmptyText() {
        if (folders.isEmpty()) {
            empty.setText("还没有添加文件夹\n\n点右上角「＋ 添加」，选中存放视频的文件夹（比如 Movies）\n之后这个应用就只看那里，不会再扫相册和截图");
        } else if (stack.isEmpty()) {
            empty.setText("添加的文件夹里没找到视频或书籍\n\n视频支持 mp4 / mkv / avi / ts / mov，书籍支持 txt / epub");
        } else {
            empty.setText("这个文件夹里没有视频或书籍\n\n点左上角箭头返回上一级");
        }
    }

    /** 标题、副标题、右上角按钮的显隐，全部随当前层级刷新 */
    private void updateHeader() {
        refreshHeaderState();
        if (manageMode) {
            titleView.setText("看番");
            if (stack.isEmpty()) {
                subtitle.setText(String.format(Locale.US, "已选 %d / %d 个文件夹 · 长按可拖动排序",
                        selected.size(), folders.size()));
            } else {
                int n = 0;
                for (Row r : rows) {
                    if (r.item != null) n++;
                }
                subtitle.setText(String.format(Locale.US, "已选 %d / %d 个文件 · 可隐藏或压缩",
                        selected.size(), n));
            }
            return;
        }
        long size = 0;
        int videos = 0, books = 0, images = 0, archives = 0, others = 0;
        if (stack.isEmpty()) {
            titleView.setText("看番");
            for (Entry v : all) {
                size += v.size;
                if (v.isBook()) books++;
                else if (v.isImage()) images++;
                else if (v.isArchive()) archives++;
                else if (v.isOther()) others++;
                else videos++;
            }
            if (folders.isEmpty()) subtitle.setText("还没有添加文件夹");
            else {
                String cnt = subText(videos, books, images, archives, others, size);
                subtitle.setText(String.format(Locale.US, "%d 个文件夹 · %s",
                        folders.size(), cnt));
            }
            return;
        }
        Crumb cur = stack.get(stack.size() - 1);
        titleView.setText(cur.name);
        for (Entry v : all) {
            if (cur.tree.equals(v.tree) && Nav.under(v.relPath, cur.rel)) {
                if (v.isBook()) books++;
                else if (v.isImage()) images++;
                else if (v.isArchive()) archives++;
                else if (v.isOther()) others++;
                else videos++;
                size += v.size;
            }
        }
        subtitle.setText(subText(videos, books, images, archives, others, size));
    }

    private void goUp() {
        if (stack.isEmpty()) return;
        stack.remove(stack.size() - 1);
        rebuildRows();
        list.setSelection(0);
    }

    @Override
    public void onBackPressed() {
        if (zipping) {
            zipCancelled = true;
            zipTitle.setText("正在停止…");
            return;
        }
        if (manageMode) {
            exitManage();
            return;
        }
        if (!stack.isEmpty()) {
            goUp();
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastBack < 2000) {
            super.onBackPressed();
            return;
        }
        lastBack = now;
        Toast.makeText(this, "再按一次退出", Toast.LENGTH_SHORT).show();
    }

    private void open(Entry v) {
        Intent it = new Intent(this, PlayerActivity.class);
        it.putExtra("uri", v.uri.toString());
        it.putExtra("title", v.title);
        it.putExtra("name", v.name);
        it.putExtra("bucket", v.bucket);
        it.putExtra("tree", v.tree);
        it.putExtra("rel", v.relPath);
        startActivity(it);
    }

    private void read(Entry v) {
        Intent it = new Intent(this, ReaderActivity.class);
        it.putExtra("uri", v.uri.toString());
        it.putExtra("name", v.name);
        startActivity(it);
    }

    private void openImage(Entry v) {
        Intent it = new Intent(this, ImageActivity.class);
        it.putExtra("uri", v.uri.toString());
        it.putExtra("name", v.name);
        it.putExtra("tree", v.tree);
        it.putExtra("rel", v.relPath);
        startActivity(it);
    }

    private void openArchive(Entry v) {
        Intent it = new Intent(this, ArchiveActivity.class);
        it.putExtra("uri", v.uri.toString());
        it.putExtra("name", v.name);
        it.putExtra("tree", v.tree);
        it.putExtra("rel", v.relPath);
        startActivityForResult(it, REQ_ARCHIVE);
    }

    private void openOther(Entry v) {
        try {
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(v.uri, Saf.mimeOf(v.name));
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(i);
        } catch (Exception e) {
            Toast.makeText(this, "没有能打开它的应用", Toast.LENGTH_SHORT).show();
        }
    }

    private View otherView(Row row) {
        final Entry v = row.item;
        int pad = Ui.dp(this, 5);
        FrameLayout wrap = new FrameLayout(this);
        wrap.setPadding(0, 0, 0, pad);

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(Ui.dp(this, 9), Ui.dp(this, 9), Ui.dp(this, 14), Ui.dp(this, 9));
        card.setBackground(Ui.round(this, getColor(R.color.surface), 14f));

        if (manageMode) addDot(card, row.checked);

        FrameLayout box = new FrameLayout(this);
        box.setBackground(Ui.round(this, Ui.alpha(getColor(R.color.dim), 0.14f), 12f));
        ImageView ic = new ImageView(this);
        ic.setImageResource(R.drawable.ic_file);
        ic.setColorFilter(getColor(R.color.dim));
        int is = Ui.dp(this, 22);
        box.addView(ic, new FrameLayout.LayoutParams(is, is, Gravity.CENTER));

        int side = Ui.dp(this, 46);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(side, side);
        blp.setMargins(0, 0, Ui.dp(this, 12), 0);
        card.addView(box, blp);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        card.addView(col, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView name = new TextView(this);
        name.setText(v.title);
        name.setTextColor(getColor(R.color.text));
        name.setTextSize(14f);
        name.setMaxLines(2);
        name.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        col.addView(name);

        TextView meta = new TextView(this);
        String ext = v.ext().isEmpty() ? "文件" : v.ext().toUpperCase(Locale.US);
        meta.setText(ext + " · " + Ui.fmtSize(v.size) + " · 长按可重命名");
        meta.setTextColor(getColor(R.color.dim));
        meta.setTextSize(11.5f);
        meta.setPadding(0, Ui.dp(this, 4), 0, 0);
        col.addView(meta);

        wrap.addView(card, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return wrap;
    }

    // ---------------- 重命名 ----------------

    private void rowMenu(final Row row) {
        final Entry item = row.item;
        if (item == null) return;
        final String[] items = {"重命名", "用其他应用打开", "在片库中隐藏", "彻底删除文件"};
        new AlertDialog.Builder(this)
                .setTitle(item.name)
                .setItems(items, new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int which) {
                        if (which == 0) askRename(item);
                        else if (which == 1) openOther(item);
                        else if (which == 2) hideOne(item);
                        else confirmDelete(item);
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void confirmDelete(final Entry item) {
        new AlertDialog.Builder(this)
                .setTitle("彻底删除文件？")
                .setMessage(item.name + "\n\n\u26a0\ufe0f 会从手机存储里真正删掉，无法恢复。\n"
                        + "如果只是不想在片库里看到它，退回上一步选「在片库中隐藏」。")
                .setNegativeButton("取消", null)
                .setPositiveButton("删除", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int which) {
                        try {
                            if (android.provider.DocumentsContract.deleteDocument(
                                    getContentResolver(), item.uri)) {
                                Toast.makeText(MainActivity.this, "已删除", Toast.LENGTH_SHORT).show();
                                hidden.remove(docKey(item));
                                saveHidden();
                                rescan();
                            } else {
                                Toast.makeText(MainActivity.this, "删不掉这个文件",
                                        Toast.LENGTH_SHORT).show();
                            }
                        } catch (SecurityException se) {
                            new AlertDialog.Builder(MainActivity.this)
                                    .setTitle("没有写入权限")
                                    .setMessage("这个文件夹当初只授权了读取。请到「管理」里移除它，"
                                            + "再重新添加一次（重新添加会同时获得写入权限）。")
                                    .setPositiveButton("知道了", null)
                                    .show();
                        } catch (Exception e) {
                            Toast.makeText(MainActivity.this, "删除失败：" + e.getMessage(),
                                    Toast.LENGTH_LONG).show();
                        }
                    }
                })
                .show();
    }

    private void askRename(final Entry item) {
        final EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setText(item.name);
        input.setTextColor(getColor(R.color.text));
        int dot = item.name.lastIndexOf('.');
        if (dot > 0) input.setSelection(0, dot);       // 默认只选中主文件名，扩展名不动
        else input.setSelection(0, item.name.length());

        int pad = Ui.dp(this, 20);
        FrameLayout wrap = new FrameLayout(this);
        wrap.setPadding(pad, Ui.dp(this, 8), pad, 0);
        wrap.addView(input, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        new AlertDialog.Builder(this)
                .setTitle("重命名")
                .setMessage("改扩展名也能用：比如把 .mp4 改成 .zip 就能当压缩包打开。")
                .setView(wrap)
                .setNegativeButton("取消", null)
                .setPositiveButton("确定", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int which) {
                        doRename(item, input.getText().toString());
                    }
                })
                .show();
    }

    private void doRename(final Entry item, String newName) {
        newName = newName == null ? "" : newName.trim();
        if (newName.isEmpty()) {
            Toast.makeText(this, "名字不能为空", Toast.LENGTH_SHORT).show();
            return;
        }
        if (newName.indexOf('/') >= 0) {
            Toast.makeText(this, "名字里不能有 /", Toast.LENGTH_SHORT).show();
            return;
        }
        if (newName.equals(item.name)) return;
        try {
            Uri out = android.provider.DocumentsContract.renameDocument(
                    getContentResolver(), item.uri, newName);
            if (out == null) {
                Toast.makeText(this, "改不了这个名字", Toast.LENGTH_SHORT).show();
                return;
            }
            Toast.makeText(this, "已改名为 " + newName, Toast.LENGTH_SHORT).show();
            rescan();
        } catch (SecurityException se) {
            new AlertDialog.Builder(this)
                    .setTitle("没有写入权限")
                    .setMessage("这个文件夹当初只授权了读取。请到「管理」里移除它，"
                            + "再重新添加一次（重新添加会同时获得写入权限）。")
                    .setPositiveButton("知道了", null)
                    .show();
        } catch (Exception e) {
            Toast.makeText(this, "改名失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    /** 图片行：方形缩略图 + 文件名 + 体积 */
    private View imageView(Row row) {
        final Entry v = row.item;
        int pad = Ui.dp(this, 5);
        FrameLayout wrap = new FrameLayout(this);
        wrap.setPadding(0, 0, 0, pad);

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(Ui.dp(this, 9), Ui.dp(this, 9), Ui.dp(this, 12), Ui.dp(this, 9));
        card.setBackground(Ui.round(this, getColor(R.color.surface), 14f));

        if (manageMode) addDot(card, row.checked);

        FrameLayout box = new FrameLayout(this);
        box.setBackground(Ui.round(this, getColor(R.color.surface2), 10f));
        box.setClipToOutline(true);
        ImageView img = new ImageView(this);
        img.setScaleType(ImageView.ScaleType.CENTER_CROP);
        box.addView(img, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        ImageView ph = new ImageView(this);
        ph.setImageResource(R.drawable.ic_image);
        ph.setColorFilter(Ui.alpha(getColor(R.color.dim), 0.6f));
        int ps = Ui.dp(this, 22);
        box.addView(ph, new FrameLayout.LayoutParams(ps, ps, Gravity.CENTER));

        int side = Ui.dp(this, 56);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(side, side);
        blp.setMargins(0, 0, Ui.dp(this, 12), 0);
        card.addView(box, blp);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        card.addView(col, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView name = new TextView(this);
        name.setText(v.title);
        name.setTextColor(getColor(R.color.text));
        name.setTextSize(14.5f);
        name.setMaxLines(2);
        name.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        col.addView(name);

        TextView meta = new TextView(this);
        String ext = v.name.contains(".")
                ? v.name.substring(v.name.lastIndexOf('.') + 1).toUpperCase(Locale.US) : "图片";
        meta.setText(ext + " · " + Ui.fmtSize(v.size));
        meta.setTextColor(getColor(R.color.dim));
        meta.setTextSize(11.5f);
        meta.setPadding(0, Ui.dp(this, 4), 0, 0);
        col.addView(meta);

        wrap.addView(card, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        Bitmap cached = thumbs.get(v.key());
        if (cached != null) {
            img.setImageBitmap(cached);
            ph.setVisibility(View.GONE);
        } else {
            img.setImageDrawable(null);
            loadImageThumb(v, img, ph);
        }
        return wrap;
    }

    private View archiveView(Row row) {
        final Entry v = row.item;
        int pad = Ui.dp(this, 5);
        FrameLayout wrap = new FrameLayout(this);
        wrap.setPadding(0, 0, 0, pad);

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(Ui.dp(this, 9), Ui.dp(this, 9), Ui.dp(this, 14), Ui.dp(this, 9));
        card.setBackground(Ui.round(this, getColor(R.color.surface), 14f));

        if (manageMode) addDot(card, row.checked);

        FrameLayout box = new FrameLayout(this);
        box.setBackground(Ui.round(this, Ui.alpha(getColor(R.color.accent), 0.13f), 12f));
        ImageView ic = new ImageView(this);
        ic.setImageResource(R.drawable.ic_archive);
        int is = Ui.dp(this, 24);
        box.addView(ic, new FrameLayout.LayoutParams(is, is, Gravity.CENTER));

        int side = Ui.dp(this, 46);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(side, side);
        blp.setMargins(0, 0, Ui.dp(this, 12), 0);
        card.addView(box, blp);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        card.addView(col, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView name = new TextView(this);
        name.setText(v.title);
        name.setTextColor(getColor(R.color.text));
        name.setTextSize(14.5f);
        name.setMaxLines(2);
        name.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        col.addView(name);

        TextView meta = new TextView(this);
        String ext = v.name.contains(".")
                ? v.name.substring(v.name.lastIndexOf('.') + 1).toUpperCase(Locale.US) : "ZIP";
        meta.setText(ext + " · " + Ui.fmtSize(v.size));
        meta.setTextColor(getColor(R.color.dim));
        meta.setTextSize(11.5f);
        meta.setPadding(0, Ui.dp(this, 4), 0, 0);
        col.addView(meta);

        ImageView chev = new ImageView(this);
        chev.setImageResource(R.drawable.ic_chevron);
        chev.setColorFilter(Ui.alpha(getColor(R.color.dim), 0.7f));
        card.addView(chev, new LinearLayout.LayoutParams(Ui.dp(this, 18), Ui.dp(this, 18)));

        wrap.addView(card, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return wrap;
    }

    private void loadImageThumb(final Entry v, final ImageView target, final ImageView placeholder) {
        target.setTag(v.key());
        pool.execute(new Runnable() {
            @Override
            public void run() {
                Bitmap bm = null;
                try {
                    int size = Ui.dp(MainActivity.this, 56);
                    InputStream in = getContentResolver().openInputStream(v.uri);
                    BitmapFactory.Options probe = new BitmapFactory.Options();
                    probe.inJustDecodeBounds = true;
                    BitmapFactory.decodeStream(in, null, probe);
                    if (in != null) in.close();
                    if (probe.outWidth <= 0) return;
                    int sample = 1;
                    while (probe.outWidth / (sample * 2) >= size
                            || probe.outHeight / (sample * 2) >= size) {
                        sample *= 2;
                    }
                    BitmapFactory.Options o = new BitmapFactory.Options();
                    o.inSampleSize = sample;
                    in = getContentResolver().openInputStream(v.uri);
                    bm = BitmapFactory.decodeStream(in, null, o);
                    if (in != null) in.close();
                } catch (Throwable ignored) {
                }
                if (bm == null) return;
                bm = centerCrop(bm, Ui.dp(MainActivity.this, 56), Ui.dp(MainActivity.this, 56));
                thumbs.put(v.key(), bm);
                final Bitmap fb = bm;
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        if (v.key().equals(target.getTag())) {
                            target.setImageBitmap(fb);
                            placeholder.setVisibility(View.GONE);
                        }
                    }
                });
            }
        });
    }

    // ---------------- Adapter ----------------

    private class Adapter extends BaseAdapter {
        @Override
        public int getCount() {
            return rows.size();
        }

        @Override
        public Object getItem(int position) {
            return rows.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public int getViewTypeCount() {
            return 8;
        }

        @Override
        public int getItemViewType(int position) {
            return rows.get(position).type;
        }

        @Override
        public boolean isEnabled(int position) {
            return rows.get(position).type != T_HEADER;
        }

        @Override
        public View getView(int position, View convert, ViewGroup parent) {
            Row r = rows.get(position);
            if (r.type == T_HEADER) return headerView(r);
            if (r.type == T_DIR) return dirView(r);
            if (r.type == T_BOOK) return bookView(r);
            if (r.type == T_IMAGE) return imageView(r);
            if (r.type == T_ARCHIVE) return archiveView(r);
            if (r.type == T_OTHER) return otherView(r);
            if (r.type == T_RESTORE) return restoreView(r);
            return itemView(r);
        }
    }

    /** 书籍行：书本图标 + 书名 + 格式与体积（有阅读记录就标出来） */
    private View bookView(Row row) {
        final Entry v = row.item;
        int pad = Ui.dp(this, 5);
        FrameLayout wrap = new FrameLayout(this);
        wrap.setPadding(0, 0, 0, pad);

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(Ui.dp(this, 12), Ui.dp(this, 11), Ui.dp(this, 12), Ui.dp(this, 11));
        card.setBackground(Ui.round(this, getColor(R.color.surface), 14f));

        if (manageMode) addDot(card, row.checked);

        FrameLayout iconBox = new FrameLayout(this);
        iconBox.setBackground(Ui.round(this, Ui.alpha(getColor(R.color.accent), 0.13f), 12f));
        ImageView ic = new ImageView(this);
        ic.setImageResource(R.drawable.ic_book);
        ic.setColorFilter(getColor(R.color.accent));
        int is = Ui.dp(this, 22);
        iconBox.addView(ic, new FrameLayout.LayoutParams(is, is, Gravity.CENTER));
        card.addView(iconBox, new LinearLayout.LayoutParams(Ui.dp(this, 46), Ui.dp(this, 46)));

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams colLp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        colLp.setMargins(Ui.dp(this, 12), 0, 0, 0);
        card.addView(col, colLp);

        TextView name = new TextView(this);
        name.setText(v.title);
        name.setTextColor(getColor(R.color.text));
        name.setTextSize(15f);
        name.setMaxLines(2);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        col.addView(name);

        String ext = v.name.toLowerCase(Locale.US).endsWith(".epub") ? "EPUB" : "TXT";
        int[] pos = Store.getBookPos(this, v.key());
        String read = pos == null ? "" : String.format(Locale.US, " · 上次读到第 %d 章", pos[0] + 1);
        TextView meta = new TextView(this);
        meta.setText(ext + " · " + Ui.fmtSize(v.size) + read);
        meta.setTextColor(getColor(R.color.dim));
        meta.setTextSize(11.5f);
        meta.setPadding(0, Ui.dp(this, 4), 0, 0);
        col.addView(meta);

        wrap.addView(card, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return wrap;
    }

    /** 管理模式下每行左侧的圆圈 */
    private void addDot(LinearLayout card, boolean checked) {
        FrameLayout box = new FrameLayout(this);
        if (checked) {
            box.setBackground(Ui.circle(this, getColor(R.color.accent)));
            ImageView ck = new ImageView(this);
            ck.setImageResource(R.drawable.ic_check);
            ck.setColorFilter(getColor(R.color.bg));
            int cs = Ui.dp(this, 14);
            box.addView(ck, new FrameLayout.LayoutParams(cs, cs, Gravity.CENTER));
        } else {
            GradientDrawable ring = Ui.circle(this, 0x00000000);
            ring.setStroke(Ui.dp(this, 1.5f), Ui.alpha(getColor(R.color.dim), 0.55f));
            box.setBackground(ring);
        }
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                Ui.dp(this, 22), Ui.dp(this, 22));
        blp.setMargins(0, 0, Ui.dp(this, 11), 0);
        card.addView(box, blp);
    }

    /** 统一算好每行的 key 与勾选状态，视图只负责画 */
    private void decorateSelection() {
        for (Row r : rows) {
            if (r.item != null) {
                r.key = docKey(r.item);
                r.checked = manageMode && selected.contains(r.key);
            } else if (r.type == T_DIR) {
                r.key = r.tree;
                r.checked = manageMode && selected.contains(r.tree);
            }
        }
    }

    private View restoreView(Row r) {
        TextView t = new TextView(this);
        t.setText(r.label);
        t.setTextColor(getColor(R.color.accent));
        t.setTextSize(12.5f);
        t.setGravity(Gravity.CENTER);
        t.setPadding(0, Ui.dp(this, 16), 0, Ui.dp(this, 16));
        return t;
    }

    private View headerView(Row r) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.HORIZONTAL);
        box.setGravity(Gravity.CENTER_VERTICAL);
        box.setPadding(Ui.dp(this, 4), Ui.dp(this, 14),
                Ui.dp(this, 4), Ui.dp(this, 6));

        TextView name = new TextView(this);
        name.setText(r.label);
        name.setTextColor(getColor(R.color.dim));
        name.setTextSize(12f);
        name.setTypeface(Typeface.DEFAULT_BOLD);
        name.setLetterSpacing(0.05f);
        box.addView(name);
        return box;
    }

    /** 文件夹行：图标 + 名称 + 统计 + 箭头 */
    private View dirView(Row r) {
        int pad = Ui.dp(this, 5);
        FrameLayout wrap = new FrameLayout(this);
        wrap.setPadding(0, 0, 0, pad);

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(Ui.dp(this, 12), Ui.dp(this, 11), Ui.dp(this, 12), Ui.dp(this, 11));
        card.setBackground(Ui.round(this, getColor(R.color.surface), 14f));

        // 管理模式下左侧出现圆圈
        if (manageMode) {
            FrameLayout box = new FrameLayout(this);
            if (r.checked) {
                box.setBackground(Ui.circle(this, getColor(R.color.accent)));
            } else {
                GradientDrawable ring = Ui.circle(this, 0x00000000);
                ring.setStroke(Ui.dp(this, 1.5f), Ui.alpha(getColor(R.color.dim), 0.55f));
                box.setBackground(ring);
            }
            if (r.checked) {
                ImageView ck = new ImageView(this);
                ck.setImageResource(R.drawable.ic_check);
                ck.setColorFilter(getColor(R.color.bg));
                int cs = Ui.dp(this, 14);
                box.addView(ck, new FrameLayout.LayoutParams(cs, cs, Gravity.CENTER));
            }
            LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                    Ui.dp(this, 22), Ui.dp(this, 22));
            blp.setMargins(0, 0, Ui.dp(this, 11), 0);
            card.addView(box, blp);
        }

        FrameLayout iconBox = new FrameLayout(this);
        iconBox.setBackground(Ui.round(this, Ui.alpha(getColor(R.color.accent), 0.13f), 12f));
        ImageView ic = new ImageView(this);
        ic.setImageResource(R.drawable.ic_folder);
        ic.setColorFilter(getColor(R.color.accent));
        int is = Ui.dp(this, 22);
        FrameLayout.LayoutParams ilp = new FrameLayout.LayoutParams(is, is, Gravity.CENTER);
        iconBox.addView(ic, ilp);
        card.addView(iconBox, new LinearLayout.LayoutParams(Ui.dp(this, 46), Ui.dp(this, 46)));

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams colLp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        colLp.setMargins(Ui.dp(this, 12), 0, 0, 0);
        card.addView(col, colLp);

        TextView name = new TextView(this);
        name.setText(r.label);
        name.setTextColor(getColor(R.color.text));
        name.setTextSize(15f);
        name.setTypeface(Typeface.DEFAULT_BOLD);
        name.setMaxLines(1);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        col.addView(name);

        TextView meta = new TextView(this);
        meta.setText(r.sub);
        meta.setTextColor(getColor(R.color.dim));
        meta.setTextSize(11.5f);
        meta.setPadding(0, Ui.dp(this, 4), 0, 0);
        col.addView(meta);

        ImageView chev = new ImageView(this);
        chev.setImageResource(R.drawable.ic_chevron);
        chev.setColorFilter(Ui.alpha(getColor(R.color.dim), 0.75f));
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                Ui.dp(this, 18), Ui.dp(this, 18));
        clp.setMargins(Ui.dp(this, 8), 0, 0, 0);
        card.addView(chev, clp);

        wrap.addView(card, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return wrap;
    }

    private View itemView(Row row) {
        final Entry v = row.item;
        int pad = Ui.dp(this, 5);
        FrameLayout wrap = new FrameLayout(this);
        wrap.setPadding(0, 0, 0, pad);

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(Ui.dp(this, 9), Ui.dp(this, 9), Ui.dp(this, 12), Ui.dp(this, 9));
        card.setBackground(Ui.round(this, getColor(R.color.surface), 14f));

        if (manageMode) addDot(card, row.checked);

        FrameLayout thumbBox = new FrameLayout(this);
        int tw = Ui.dp(this, 108), th = Ui.dp(this, 61);
        GradientDrawable tb = Ui.round(this, getColor(R.color.surface2), 10f);
        thumbBox.setBackground(tb);
        thumbBox.setClipToOutline(true);

        ImageView img = new ImageView(this);
        img.setScaleType(ImageView.ScaleType.CENTER_CROP);
        thumbBox.addView(img, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        TextView dur = new TextView(this);
        dur.setText(Ui.fmtDur(v.duration));
        dur.setTextColor(Color.WHITE);
        dur.setTextSize(9.5f);
        dur.setPadding(Ui.dp(this, 5), Ui.dp(this, 1), Ui.dp(this, 5), Ui.dp(this, 2));
        dur.setBackground(Ui.round(this, 0xCC000000, 4f));
        FrameLayout.LayoutParams dlp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.END | Gravity.BOTTOM);
        dlp.setMargins(0, 0, Ui.dp(this, 4), Ui.dp(this, 4));
        thumbBox.addView(dur, dlp);

        card.addView(thumbBox, new LinearLayout.LayoutParams(tw, th));

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams colLp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        colLp.setMargins(Ui.dp(this, 11), 0, 0, 0);
        card.addView(col, colLp);

        TextView name = new TextView(this);
        name.setText(v.title);
        name.setTextColor(getColor(R.color.text));
        name.setTextSize(14f);
        name.setMaxLines(2);
        name.setLineSpacing(Ui.dp(this, 1), 1f);
        col.addView(name);

        TextView meta = new TextView(this);
        meta.setTextColor(getColor(R.color.dim));
        meta.setTextSize(11.5f);
        meta.setPadding(0, Ui.dp(this, 4), 0, 0);
        col.addView(meta);

        long pos = Store.getPos(this, v.key());
        boolean watched = v.duration > 0 && pos > 3000;
        String pct = watched ? String.format(Locale.US, " · 已看 %d%%",
                (int) (pos * 100 / v.duration)) : "";
        meta.setText(Ui.fmtSize(v.size) + pct);

        ProgressBar pb = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        pb.setMax(1000);
        pb.setProgress(watched ? (int) (pos * 1000 / v.duration) : 0);
        pb.setProgressTintList(android.content.res.ColorStateList.valueOf(getColor(R.color.accent)));
        pb.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(
                Ui.alpha(getColor(R.color.dim), 0.18f)));
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 3));
        plp.setMargins(0, Ui.dp(this, 7), 0, 0);
        col.addView(pb, plp);

        wrap.addView(card, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        Bitmap cached = thumbs.get(v.key());
        if (cached != null) {
            img.setImageBitmap(cached);
        } else {
            img.setImageDrawable(null);
            loadThumb(v, img);
        }
        return wrap;
    }

    private void loadThumb(final Entry v, final ImageView target) {
        target.setTag(v.key());
        final int tw = Ui.dp(this, 108), th = Ui.dp(this, 61);
        pool.execute(new Runnable() {
            @Override
            public void run() {
                Bitmap bm = null;
                MediaMetadataRetriever r = null;
                try {
                    r = new MediaMetadataRetriever();
                    r.setDataSource(MainActivity.this, v.uri);
                    long t = v.duration > 8000 ? v.duration / 8 : 4000;
                    bm = r.getFrameAtTime(t * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
                } catch (Throwable ignored) {
                } finally {
                    if (r != null) {
                        try {
                            r.release();
                        } catch (Exception ignored) {
                        }
                    }
                }
                if (bm != null) {
                    bm = centerCrop(bm, tw, th);
                    thumbs.put(v.key(), bm);
                    final Bitmap fb = bm;
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            if (v.key().equals(target.getTag())) {
                                target.setImageBitmap(fb);
                            }
                        }
                    });
                }
            }
        });
    }

    private Bitmap centerCrop(Bitmap src, int tw, int th) {
        try {
            int w = src.getWidth(), h = src.getHeight();
            if (w <= 0 || h <= 0) return src;
            float s = Math.max((float) tw / w, (float) th / h);
            int sw = Math.max(tw, Math.round(w * s));
            int sh = Math.max(th, Math.round(h * s));
            Bitmap scaled = Bitmap.createScaledBitmap(src, sw, sh, true);
            Bitmap out = Bitmap.createBitmap(scaled, (sw - tw) / 2, (sh - th) / 2, tw, th);
            if (scaled != out) scaled.recycle();
            if (src != out) src.recycle();
            return out;
        } catch (Throwable t) {
            return src;
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        pool.shutdownNow();
    }
}
