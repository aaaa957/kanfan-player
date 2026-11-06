package com.kanfan.player;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
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
    private static final String KEY_TREES = "trees";

    private static final int T_HEADER = 0, T_VIDEO = 1, T_DIR = 2;

    private ListView list;
    private TextView subtitle, manageBtn, addBtn, titleView;
    private ImageView backBtn;
    private TextView empty;
    private LinearLayout root;

    private final List<Row> rows = new ArrayList<>();
    private final List<VideoItem> all = new ArrayList<>();
    private final List<String[]> folders = new ArrayList<>(); // {uri, name}
    private final List<Crumb> stack = new ArrayList<>();      // 导航栈，空 = 根层级
    private Adapter adapter;
    private long lastBack;

    private final ExecutorService pool = Executors.newFixedThreadPool(4);
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final LruCache<String, Bitmap> thumbs = new LruCache<String, Bitmap>(60) {
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
        VideoItem item;
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
        try {
            for (String s : sp().getStringSet(KEY_TREES, new java.util.LinkedHashSet<String>())) {
                int p = s.indexOf("||");
                if (p <= 0) continue;
                folders.add(new String[]{s.substring(0, p), s.substring(p + 2)});
            }
        } catch (Exception ignored) {
        }
    }

    private void saveFolders() {
        java.util.LinkedHashSet<String> set = new java.util.LinkedHashSet<>();
        for (String[] f : folders) set.add(f[0] + "||" + f[1]);
        sp().edit().putStringSet(KEY_TREES, set).apply();
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

    private void showManage() {
        if (folders.isEmpty()) {
            Toast.makeText(this, "还没有添加文件夹", Toast.LENGTH_SHORT).show();
            return;
        }
        final String[] names = new String[folders.size()];
        for (int i = 0; i < folders.size(); i++) names[i] = folders.get(i)[1];
        new AlertDialog.Builder(this)
                .setTitle("已添加的文件夹（点选可移除）")
                .setItems(names, new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int which) {
                        final int idx = which;
                        new AlertDialog.Builder(MainActivity.this)
                                .setMessage("移除「" + names[idx] + "」？\n只是不再显示，不会删除手机上的文件。")
                                .setNegativeButton("取消", null)
                                .setPositiveButton("移除", new android.content.DialogInterface.OnClickListener() {
                                    @Override
                                    public void onClick(android.content.DialogInterface d2, int w2) {
                                        try {
                                            getContentResolver().releasePersistableUriPermission(
                                                    Uri.parse(folders.get(idx)[0]),
                                                    Intent.FLAG_GRANT_READ_URI_PERMISSION);
                                        } catch (Exception ignored) {
                                        }
                                        folders.remove(idx);
                                        saveFolders();
                                        stack.clear();
                                        rescan();
                                    }
                                }).show();
                    }
                })
                .setNegativeButton("关闭", null)
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
                showManage();
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
                if (r.type == T_DIR) {
                    stack.add(new Crumb(r.tree, r.rel, r.label));
                    rebuildRows();
                    list.setSelection(0);
                } else if (r.type == T_VIDEO && r.item != null) {
                    open(r.item);
                }
            }
        });
        root.addView(list, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

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
        boolean atRoot = stack.isEmpty();
        backBtn.setVisibility(atRoot ? View.GONE : View.VISIBLE);
        addBtn.setVisibility(atRoot ? View.VISIBLE : View.GONE);
        manageBtn.setVisibility(atRoot && !folders.isEmpty() ? View.VISIBLE : View.GONE);
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
                final List<VideoItem> found = new ArrayList<>();
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
        final List<VideoItem> snapshot = new ArrayList<>(all);
        final AtomicInteger done = new AtomicInteger(0);
        final int total = snapshot.size();
        for (final VideoItem v : snapshot) {
            pool.execute(new Runnable() {
                @Override
                public void run() {
                    if (v.duration <= 0) {
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
        for (String[] f : folders) {
            int count = 0;
            long size = 0;
            for (VideoItem v : all) {
                if (f[0].equals(v.tree)) {
                    count++;
                    size += v.size;
                }
            }
            Row r = new Row();
            r.type = T_DIR;
            r.label = f[1];
            r.tree = f[0];
            r.rel = "";
            r.sub = count == 0 ? "空文件夹"
                    : String.format(Locale.US, "%d 个视频 · %s", count, sizeText(size));
            rows.add(r);
        }
    }

    private void buildDirRows() {
        Crumb cur = stack.get(stack.size() - 1);

        List<VideoItem> scoped = new ArrayList<>();
        for (VideoItem v : all) {
            if (cur.tree.equals(v.tree)) scoped.add(v);
        }
        String[] rels = new String[scoped.size()];
        long[] sizes = new long[scoped.size()];
        for (int i = 0; i < scoped.size(); i++) {
            rels[i] = scoped.get(i).relPath;
            sizes[i] = scoped.get(i).size;
        }

        List<Nav.Dir> dirs = Nav.subdirsOf(cur.rel, rels, sizes);
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
                r.sub = String.format(Locale.US, "%d 个视频 · %s", d.count, sizeText(d.size));
                rows.add(r);
            }
        }

        List<VideoItem> videos = new ArrayList<>();
        for (int idx : Nav.directOf(cur.rel, rels)) videos.add(scoped.get(idx));
        Collections.sort(videos, new java.util.Comparator<VideoItem>() {
            @Override
            public int compare(VideoItem a, VideoItem b) {
                return Ui.NATURAL.compare(a.title, b.title);
            }
        });
        if (!videos.isEmpty()) {
            Row h = new Row();
            h.type = T_HEADER;
            h.label = "视频";
            rows.add(h);
            for (VideoItem v : videos) {
                Row r = new Row();
                r.type = T_VIDEO;
                r.item = v;
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
            empty.setText("添加的文件夹里没找到视频\n\n支持 mp4 / mkv / avi / ts / mov 等常见格式");
        } else {
            empty.setText("这个文件夹里没有视频\n\n点左上角箭头返回上一级");
        }
    }

    /** 标题、副标题、右上角按钮的显隐，全部随当前层级刷新 */
    private void updateHeader() {
        refreshHeaderState();
        long size = 0;
        int count = 0;
        if (stack.isEmpty()) {
            titleView.setText("看番");
            for (VideoItem v : all) size += v.size;
            count = all.size();
            if (folders.isEmpty()) subtitle.setText("还没有添加文件夹");
            else subtitle.setText(String.format(Locale.US, "%d 个文件夹 · %d 个视频 · %s",
                    folders.size(), count, sizeText(size)));
            return;
        }
        Crumb cur = stack.get(stack.size() - 1);
        titleView.setText(cur.name);
        for (VideoItem v : all) {
            if (cur.tree.equals(v.tree) && Nav.under(v.relPath, cur.rel)) {
                count++;
                size += v.size;
            }
        }
        subtitle.setText(String.format(Locale.US, "%d 个视频 · %s", count, sizeText(size)));
    }

    private void goUp() {
        if (stack.isEmpty()) return;
        stack.remove(stack.size() - 1);
        rebuildRows();
        list.setSelection(0);
    }

    @Override
    public void onBackPressed() {
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

    private void open(VideoItem v) {
        Intent it = new Intent(this, PlayerActivity.class);
        it.putExtra("uri", v.uri.toString());
        it.putExtra("title", v.title);
        it.putExtra("bucket", v.bucket);
        it.putExtra("tree", v.tree);
        it.putExtra("rel", v.relPath);
        startActivity(it);
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
            return 3;
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
            return itemView(r.item);
        }
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

    private View itemView(final VideoItem v) {
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

    private void loadThumb(final VideoItem v, final ImageView target) {
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
