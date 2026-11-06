package com.kanfan.player;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 下载页：贴 magnet / 选 .torrent → 交给 {@link TorrentService} 里的 BT 引擎。
 * 只负责展示与操作，状态每秒从 {@link TorrentEngine#snapshot()} 拉一次。
 */
public class TorrentActivity extends Activity {

    private static final int REQ_TORRENT = 3101;
    private static final int REQ_NOTIF = 3102;

    private LinearLayout root;
    private ListView list;
    private TextView empty, footer, allBtn;
    private EditText input;
    private TorrentEngine engine;
    private final List<TorrentEngine.Task> rows = new ArrayList<TorrentEngine.Task>();

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            reload();
            ui.postDelayed(this, 800);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        engine = TorrentEngine.get(this);
        buildUi();
        reload();
        maybeAskNotif();
        handleIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleIntent(intent);
    }

    private void handleIntent(Intent it) {
        if (it == null) return;
        String data = it.getDataString();
        if (data != null && data.startsWith("magnet:")) {
            input.setText(data);
            addCurrent();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        ui.post(tick);
    }

    @Override
    protected void onPause() {
        super.onPause();
        ui.removeCallbacks(tick);
    }

    // ---------------- 界面 ----------------

    private void buildUi() {
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(getColor(R.color.bg));

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        int pad = Ui.dp(this, 16);
        top.setPadding(Ui.dp(this, 6), Ui.dp(this, 12), pad, Ui.dp(this, 8));

        ImageView back = new ImageView(this);
        back.setImageResource(R.drawable.ic_back);
        back.setColorFilter(getColor(R.color.text));
        back.setPadding(Ui.dp(this, 10), Ui.dp(this, 10), Ui.dp(this, 10), Ui.dp(this, 10));
        back.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        int bs = Ui.dp(this, 40);
        top.addView(back, new LinearLayout.LayoutParams(bs, bs));

        TextView title = new TextView(this);
        title.setText("下载");
        title.setTextColor(getColor(R.color.text));
        title.setTextSize(22f);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tlp.setMargins(Ui.dp(this, 6), 0, 0, 0);
        top.addView(title, tlp);

        allBtn = chip("全部暂停");
        allBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                boolean anyActive = false;
                for (TorrentEngine.Task t : engine.snapshot()) {
                    if (!t.paused) anyActive = true;
                }
                for (TorrentEngine.Task t : engine.snapshot()) {
                    if (anyActive) engine.pause(t);
                    else engine.resume(t);
                }
                reload();
            }
        });
        top.addView(allBtn);
        root.addView(top);

        // 添加区
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(pad, 0, pad, Ui.dp(this, 6));
        box.setBackground(Ui.round(this, getColor(R.color.surface), 14f));

        LinearLayout boxPad = new LinearLayout(this);
        boxPad.setOrientation(LinearLayout.VERTICAL);
        boxPad.setPadding(Ui.dp(this, 12), Ui.dp(this, 12), Ui.dp(this, 12), Ui.dp(this, 12));

        input = new EditText(this);
        input.setHint("magnet:?xt=urn:btih:… 或种子链接");
        input.setHintTextColor(getColor(R.color.dim));
        input.setTextColor(getColor(R.color.text));
        input.setTextSize(13f);
        input.setSingleLine(true);
        input.setMaxLines(1);
        boxPad.addView(input);

        LinearLayout btns = new LinearLayout(this);
        btns.setOrientation(LinearLayout.HORIZONTAL);
        btns.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.topMargin = Ui.dp(this, 8);

        TextView add = chipPrimary("添加");
        add.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                addCurrent();
            }
        });
        btns.addView(add);

        TextView pick = chip("选种子文件");
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        plp.setMargins(Ui.dp(this, 8), 0, 0, 0);
        pick.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pickTorrent();
            }
        });
        btns.addView(pick, plp);

        TextView paste = chip("粘贴");
        LinearLayout.LayoutParams xlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        xlp.setMargins(Ui.dp(this, 8), 0, 0, 0);
        paste.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                android.content.ClipboardManager cm =
                        (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                if (cm != null && cm.hasPrimaryClip()
                        && cm.getPrimaryClip().getItemCount() > 0) {
                    CharSequence s = cm.getPrimaryClip().getItemAt(0).coerceToText(TorrentActivity.this);
                    if (s != null) input.setText(s.toString().trim());
                }
            }
        });
        btns.addView(paste, xlp);
        boxPad.addView(btns, blp);

        TextView hint = new TextView(this);
        hint.setText("保存到 " + TorrentEngine.downloadRoot(this).getAbsolutePath());
        hint.setTextColor(getColor(R.color.dim));
        hint.setTextSize(10.5f);
        hint.setSingleLine(true);
        hint.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hlp.topMargin = Ui.dp(this, 6);
        boxPad.addView(hint, hlp);

        box.addView(boxPad);
        LinearLayout.LayoutParams boxLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        boxLp.setMargins(pad, 0, pad, Ui.dp(this, 8));
        root.addView(box, boxLp);

        FrameLayout content = new FrameLayout(this);
        list = new ListView(this);
        list.setDivider(null);
        list.setDividerHeight(0);
        list.setSelector(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
        list.setClipToPadding(false);
        list.setPadding(pad, 0, pad, Ui.dp(this, 8));
        list.setAdapter(adapter);
        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                if (position >= 0 && position < rows.size()) actions(rows.get(position));
            }
        });
        list.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            @Override
            public boolean onItemLongClick(AdapterView<?> parent, View view, int position, long id) {
                if (position >= 0 && position < rows.size()) actions(rows.get(position));
                return true;
            }
        });
        content.addView(list, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        empty = new TextView(this);
        empty.setText("还没有下载任务\n\n粘贴 magnet 链接点「添加」，\n或者选一个 .torrent 文件");
        empty.setTextColor(getColor(R.color.dim));
        empty.setTextSize(13.5f);
        empty.setGravity(Gravity.CENTER);
        empty.setLineSpacing(0, 1.6f);
        content.addView(empty, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER));

        root.addView(content, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        footer = new TextView(this);
        footer.setTextColor(getColor(R.color.dim));
        footer.setTextSize(11f);
        footer.setPadding(pad, Ui.dp(this, 6), pad, Ui.dp(this, 14));
        root.addView(footer);

        setContentView(root);
    }

    private TextView chip(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(12.5f);
        t.setTextColor(getColor(R.color.accent));
        t.setBackground(Ui.roundStroke(this, Ui.alpha(getColor(R.color.accent), 0.10f),
                18f, Ui.alpha(getColor(R.color.accent), 0.35f), 1f));
        t.setPadding(Ui.dp(this, 12), Ui.dp(this, 6), Ui.dp(this, 12), Ui.dp(this, 6));
        return t;
    }

    private TextView chipPrimary(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(12.5f);
        t.setTextColor(getColor(R.color.bg));
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setBackground(Ui.round(this, getColor(R.color.accent), 18f));
        t.setPadding(Ui.dp(this, 16), Ui.dp(this, 7), Ui.dp(this, 16), Ui.dp(this, 7));
        return t;
    }

    // ---------------- 交互 ----------------

    private void addCurrent() {
        String text = input.getText().toString().trim();
        if (text.isEmpty()) {
            Toast.makeText(this, "先粘贴 magnet 链接", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!text.startsWith("magnet:") && !text.startsWith("/")) {
            // 可能是 http 的 .torrent 链接：交给系统下载？这里只提示
            if (text.startsWith("http")) {
                Toast.makeText(this, "暂只支持 magnet 与本地 .torrent 文件", Toast.LENGTH_LONG).show();
                return;
            }
        }
        TorrentService.ensure(this);
        String err = engine.add(text);
        if (err == null) {
            input.setText("");
            Toast.makeText(this, "已加入下载", Toast.LENGTH_SHORT).show();
            reload();
        } else {
            Toast.makeText(this, err, Toast.LENGTH_LONG).show();
        }
    }

    private void pickTorrent() {
        try {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("*/*");
            startActivityForResult(i, REQ_TORRENT);
        } catch (Exception e) {
            Toast.makeText(this, "打不开文件选择器", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ_TORRENT || res != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        String name = "a.torrent";
        Cursor c = null;
        try {
            c = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME},
                    null, null, null);
            if (c != null && c.moveToFirst()) {
                String n = c.getString(0);
                if (n != null && !n.isEmpty()) name = n;
            }
        } catch (Exception ignored) {
        } finally {
            if (c != null) c.close();
        }
        try {
            InputStream in = getContentResolver().openInputStream(uri);
            if (in == null) throw new Exception("读不到文件");
            java.io.File kept = engine.keepTorrent(in, name);
            try {
                in.close();
            } catch (Exception ignored) {
            }
            TorrentService.ensure(this);
            String err = engine.add(kept.getAbsolutePath());
            if (err == null) {
                Toast.makeText(this, "已加入下载", Toast.LENGTH_SHORT).show();
                reload();
            } else {
                Toast.makeText(this, err, Toast.LENGTH_LONG).show();
            }
        } catch (Exception e) {
            Toast.makeText(this, "加不进去：" + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void actions(final TorrentEngine.Task t) {
        final boolean done = t.state == TorrentEngine.ST_DONE;
        final List<String> items = new ArrayList<String>();
        items.add(t.paused ? "继续下载" : "暂停");
        if (done) items.add("打开文件");
        items.add("复制磁力/来源");
        items.add("删除任务");
        new AlertDialog.Builder(this)
                .setTitle(t.name == null || t.name.isEmpty() ? "任务" : t.name)
                .setItems(items.toArray(new String[0]), new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        String what = items.get(which);
                        if (what.equals("暂停")) {
                            engine.pause(t);
                            reload();
                        } else if (what.equals("继续下载")) {
                            engine.resume(t);
                            TorrentService.ensure(TorrentActivity.this);
                            reload();
                        } else if (what.equals("打开文件")) {
                            showFiles(t);
                        } else if (what.equals("复制磁力/来源")) {
                            android.content.ClipboardManager cm =
                                    (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                            if (cm != null) {
                                cm.setPrimaryClip(android.content.ClipData.newPlainText("magnet", t.source));
                                Toast.makeText(TorrentActivity.this, "已复制", Toast.LENGTH_SHORT).show();
                            }
                        } else {
                            confirmDelete(t);
                        }
                    }
                })
                .show();
    }

    private void showFiles(final TorrentEngine.Task t) {
        final List<String[]> files = engine.files(t);
        if (files.isEmpty()) {
            Toast.makeText(this, "还没下完，或者里面没有可播放的文件", Toast.LENGTH_SHORT).show();
            return;
        }
        String[] names = new String[files.size()];
        for (int i = 0; i < files.size(); i++) {
            names[i] = files.get(i)[1] + "  ·  " + Ui.fmtSize(Long.parseLong(files.get(i)[2]));
        }
        new AlertDialog.Builder(this)
                .setTitle("选择要播放的文件")
                .setItems(names, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        play(files.get(which)[0], files.get(which)[1]);
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void play(String path, String name) {
        Intent it = new Intent(this, PlayerActivity.class);
        it.putExtra("uri", Uri.fromFile(new java.io.File(path)).toString());
        it.putExtra("name", name);
        it.putExtra("title", Ui.prettyTitle(name));
        it.putExtra("bucket", "下载");
        it.putExtra("tree", "");
        it.putExtra("rel", "");
        startActivity(it);
    }

    private void confirmDelete(final TorrentEngine.Task t) {
        new AlertDialog.Builder(this)
                .setTitle("删除任务")
                .setMessage("「" + (t.name == null || t.name.isEmpty() ? "这个任务" : t.name)
                        + "」\n\n只删任务，还是连下载的文件一起删？")
                .setNegativeButton("取消", null)
                .setNeutralButton("保留文件", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        engine.remove(t, false);
                        reload();
                    }
                })
                .setPositiveButton("连文件一起删", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        engine.remove(t, true);
                        reload();
                    }
                })
                .show();
    }

    // ---------------- 列表 ----------------

    private void reload() {
        rows.clear();
        rows.addAll(engine.snapshot());
        adapter.notifyDataSetChanged();
        boolean has = !rows.isEmpty();
        list.setVisibility(has ? View.VISIBLE : View.GONE);
        empty.setVisibility(has ? View.GONE : View.VISIBLE);
        allBtn.setVisibility(has ? View.VISIBLE : View.GONE);
        allBtn.setText(anyActive() ? "全部暂停" : "全部继续");

        long rate = engine.totalRate();
        long dht = 0;
        try {
            dht = engine.session() == null ? 0 : engine.session().dhtNodes();
        } catch (Throwable ignored) {
        }
        footer.setText(String.format(Locale.US, "↓ %s/s · DHT %d 节点 · %d 个任务",
                TorrentService.fmt(rate), dht, rows.size()));
    }

    private boolean anyActive() {
        for (TorrentEngine.Task t : rows) {
            if (!t.paused && t.state != TorrentEngine.ST_DONE) return true;
        }
        return false;
    }

    private final BaseAdapter adapter = new BaseAdapter() {
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
        public View getView(int position, View convert, ViewGroup parent) {
            TorrentEngine.Task t = rows.get(position);
            LinearLayout card = new LinearLayout(TorrentActivity.this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setPadding(Ui.dp(TorrentActivity.this, 12), Ui.dp(TorrentActivity.this, 11),
                    Ui.dp(TorrentActivity.this, 12), Ui.dp(TorrentActivity.this, 11));
            card.setBackground(Ui.round(TorrentActivity.this, getColor(R.color.surface), 14f));
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            clp.bottomMargin = Ui.dp(TorrentActivity.this, 10);
            card.setLayoutParams(clp);

            TextView name = new TextView(TorrentActivity.this);
            name.setText(t.name == null || t.name.isEmpty()
                    ? (t.hash.isEmpty() ? "等待元数据…" : t.hash.substring(0, 8))
                    : t.name);
            name.setTextColor(getColor(R.color.text));
            name.setTextSize(14f);
            name.setMaxLines(2);
            name.setEllipsize(TextUtils.TruncateAt.END);
            card.addView(name);

            TextView meta = new TextView(TorrentActivity.this);
            meta.setText(stateText(t));
            meta.setTextColor(getColor(R.color.dim));
            meta.setTextSize(11.5f);
            meta.setPadding(0, Ui.dp(TorrentActivity.this, 4), 0, Ui.dp(TorrentActivity.this, 8));
            card.addView(meta);

            ProgressBar bar = new ProgressBar(TorrentActivity.this, null,
                    android.R.attr.progressBarStyleHorizontal);
            bar.setMax(1000);
            bar.setProgress(t.state == TorrentEngine.ST_DONE ? 1000 : t.percent() * 10);
            card.addView(bar, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(TorrentActivity.this, 5)));
            return card;
        }
    };

    private String stateText(TorrentEngine.Task t) {
        String size = Ui.fmtSize(t.total);
        switch (t.state) {
            case TorrentEngine.ST_DONE:
                return "已完成 · " + size;
            case TorrentEngine.ST_PAUSED:
                return "已暂停 · " + t.percent() + "%" + (size.isEmpty() ? "" : " · " + size);
            case TorrentEngine.ST_ERROR:
                return "出错" + (t.error == null ? "" : "：" + t.error);
            case TorrentEngine.ST_META:
                return "正在获取元数据 · " + t.peers + " 个 peer";
            case TorrentEngine.ST_CHECK:
                return "正在校验已有文件…";
            case TorrentEngine.ST_DOWN:
                return String.format(Locale.US, "%d%% · %s/s · %d 个 peer · %s",
                        t.percent(), TorrentService.fmt(t.rate), t.peers, size);
            default:
                return "连接中 · " + t.peers + " 个 peer";
        }
    }

    // ---------------- 权限 ----------------

    private void maybeAskNotif() {
        if (Build.VERSION.SDK_INT < 33) return;
        if (checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                == PackageManager.PERMISSION_GRANTED) return;
        requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, REQ_NOTIF);
    }
}
