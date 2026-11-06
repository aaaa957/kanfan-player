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
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import android.media.MediaMetadataRetriever;

import java.io.InputStream;
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
    private static final String PREF = "kanfan_folders";
    private static final String KEY_TREES = "trees";        // 旧格式：StringSet（无序）
    private static final String KEY_ORDER = "trees_order";  // 新格式：按行存储，保留顺序

    private static final int T_HEADER = 0, T_VIDEO = 1, T_DIR = 2, T_BOOK = 3, T_IMAGE = 4;

    private ListView list;
    private TextView subtitle, manageBtn, addBtn, titleView;
    private TextView allBtn, delBtn, doneBtn;
    private LinearLayout manageBar;
    private ImageView backBtn;
    private TextView empty;
    private LinearLayout root;

    private final List<Row> rows = new ArrayList<>();
    private final List<Entry> all = new ArrayList<>();
    private final List<String[]> folders = new ArrayList<>(); // {uri, name}，顺序即展示顺序
    private final List<Crumb> stack = new ArrayList<>();      // 导航栈，空 = 根层级
    private Adapter adapter;
    private long lastBack;

    // 管理（多选删除 / 拖动排序）
    private boolean manageMode;
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
        if (requestCode != REQ_TREE || resultCode != RESULT_OK || data == null) return;
        Uri tree = data.getData();
        if (tree == null) return;
        try {
            getContentResolver().takePersistableUriPermission(tree,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION);
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
        while (!stack.isEmpty()) stack.remove(stack.size() - 1);
        manageMode = true;
        selected.clear();
        dragPos = -1;
        rebuildRows();
        Toast.makeText(this, "点圆圈选择，长按可拖动排序", Toast.LENGTH_SHORT).show();
    }

    private void exitManage() {
        manageMode = false;
        selected.clear();
        dragPos = -1;
        rebuildRows();
    }

    private void toggleSelect(int index) {
        if (index < 0 || index >= folders.size()) return;
        String uri = folders.get(index)[0];
        if (!selected.remove(uri)) selected.add(uri);
        rebuildRows();
    }

    private void toggleSelectAll() {
        if (selected.size() >= folders.size()) {
            selected.clear();
        } else {
            for (String[] f : folders) selected.add(f[0]);
        }
        rebuildRows();
    }

    private void deleteSelected() {
        if (selected.isEmpty()) {
            Toast.makeText(this, "先点圆圈选中要移除的文件夹", Toast.LENGTH_SHORT).show();
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
                if (manageMode && r.type == T_DIR) {
                    toggleSelect(r.index);
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
                }
            }
        });

        // 管理模式下长按拖动排序
        list.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            @Override
            public boolean onItemLongClick(AdapterView<?> parent, View view, int position, long id) {
                if (!manageMode || position < 0 || position >= rows.size()) return false;
                if (rows.get(position).type != T_DIR) return false;
                dragPos = position;
                view.setAlpha(0.45f);
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

        setContentView(root);
        refreshHeaderState();
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
        boolean atRoot = stack.isEmpty() && !manageMode;
        backBtn.setVisibility(atRoot ? View.GONE : View.VISIBLE);
        addBtn.setVisibility(atRoot ? View.VISIBLE : View.GONE);
        manageBtn.setVisibility(atRoot && !folders.isEmpty() ? View.VISIBLE : View.GONE);

        if (manageBar != null) manageBar.setVisibility(manageMode ? View.VISIBLE : View.GONE);
        if (manageMode) {
            allBtn.setText(selected.size() >= folders.size() ? "取消全选" : "全选");
            delBtn.setText(selected.isEmpty() ? "删除" : "删除(" + selected.size() + ")");
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
                        all.addAll(found);
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
            int videos = 0, books = 0, images = 0;
            long size = 0;
            for (Entry v : all) {
                if (!f[0].equals(v.tree)) continue;
                if (v.isBook()) books++;
                else if (v.isImage()) images++;
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
            r.sub = subText(videos, books, images, size);
            rows.add(r);
        }
    }

    /** 「52 个视频 · 3 本 · 13.4 GB」这样的副标题 */
    private String subText(int videos, int books, int images, long size) {
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
                r.sub = subText(d.videoCount, d.bookCount, d.imageCount, d.size);
                rows.add(r);
            }
        }

        List<Entry> videos = new ArrayList<>();
        List<Entry> books = new ArrayList<>();
        List<Entry> pics = new ArrayList<>();
        for (int idx : Nav.directOf(cur.rel, rels)) {
            Entry e = scoped.get(idx);
            if (e.isBook()) books.add(e);
            else if (e.isImage()) pics.add(e);
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
            subtitle.setText(String.format(Locale.US, "已选 %d / %d 个文件夹 · 长按可拖动排序",
                    selected.size(), folders.size()));
            return;
        }
        long size = 0;
        int videos = 0, books = 0, images = 0;
        if (stack.isEmpty()) {
            titleView.setText("看番");
            for (Entry v : all) {
                size += v.size;
                if (v.isBook()) books++;
                else if (v.isImage()) images++;
                else videos++;
            }
            if (folders.isEmpty()) subtitle.setText("还没有添加文件夹");
            else {
                String cnt = subText(videos, books, images, size);
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
                else videos++;
                size += v.size;
            }
        }
        subtitle.setText(subText(videos, books, images, size));
    }

    private void goUp() {
        if (stack.isEmpty()) return;
        stack.remove(stack.size() - 1);
        rebuildRows();
        list.setSelection(0);
    }

    @Override
    public void onBackPressed() {
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

    /** 图片行：方形缩略图 + 文件名 + 体积 */
    private View imageView(final Entry v) {
        int pad = Ui.dp(this, 5);
        FrameLayout wrap = new FrameLayout(this);
        wrap.setPadding(0, 0, 0, pad);

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(Ui.dp(this, 9), Ui.dp(this, 9), Ui.dp(this, 12), Ui.dp(this, 9));
        card.setBackground(Ui.round(this, getColor(R.color.surface), 14f));

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
            return 5;
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
            if (r.type == T_BOOK) return bookView(r.item);
            if (r.type == T_IMAGE) return imageView(r.item);
            return itemView(r.item);
        }
    }

    /** 书籍行：书本图标 + 书名 + 格式与体积（有阅读记录就标出来） */
    private View bookView(final Entry v) {
        int pad = Ui.dp(this, 5);
        FrameLayout wrap = new FrameLayout(this);
        wrap.setPadding(0, 0, 0, pad);

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(Ui.dp(this, 12), Ui.dp(this, 11), Ui.dp(this, 12), Ui.dp(this, 11));
        card.setBackground(Ui.round(this, getColor(R.color.surface), 14f));

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

    private View itemView(final Entry v) {
        int pad = Ui.dp(this, 5);
        FrameLayout wrap = new FrameLayout(this);
        wrap.setPadding(0, 0, 0, pad);

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(Ui.dp(this, 9), Ui.dp(this, 9), Ui.dp(this, 12), Ui.dp(this, 9));
        card.setBackground(Ui.round(this, getColor(R.color.surface), 14f));

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
