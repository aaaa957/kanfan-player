package com.kanfan.player;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentResolver;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 压缩包：列内容、输密码、解压到授权目录。
 * 目前只支持 zip（含 ZipCrypto 与 AES 加密）；7z / rar 会给出说明。
 */
public class ArchiveActivity extends Activity {

    private FrameLayout root;
    private LinearLayout topBar, bottomBar;
    private TextView titleView, subView, extractBtn, tipView;
    private ListView list;
    private FrameLayout progressLayer;
    private ProgressBar bar;
    private TextView progressTitle, progressFile, cancelBtn;

    private String name = "", treeUri = "", relPath = "", uriStr = "";
    private Zip zip;
    private final List<Zip.Entry> entries = new ArrayList<Zip.Entry>();
    private int fileCount = 0;
    private String password;
    private volatile boolean cancelled;

    private final Handler ui = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        name = getIntent().getStringExtra("name");
        treeUri = getIntent().getStringExtra("tree");
        relPath = getIntent().getStringExtra("rel");
        uriStr = getIntent().getStringExtra("uri");
        if (name == null) name = "";
        if (treeUri == null) treeUri = "";
        if (relPath == null) relPath = "";
        if (uriStr == null) uriStr = "";

        buildUi();
        startLoad();
    }

    // ---------------- 界面 ----------------

    private void buildUi() {
        root = new FrameLayout(this);
        root.setBackgroundColor(getColor(R.color.bg));

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);

        // 顶栏
        topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setPadding(Ui.dp(this, 6), Ui.dp(this, 12), Ui.dp(this, 16), Ui.dp(this, 10));

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
        int bsz = Ui.dp(this, 40);
        topBar.addView(back, new LinearLayout.LayoutParams(bsz, bsz));

        LinearLayout tcol = new LinearLayout(this);
        tcol.setOrientation(LinearLayout.VERTICAL);
        tcol.setPadding(Ui.dp(this, 4), 0, 0, 0);
        titleView = new TextView(this);
        titleView.setTextColor(getColor(R.color.text));
        titleView.setTextSize(17f);
        titleView.setTypeface(Typeface.DEFAULT_BOLD);
        titleView.setSingleLine(true);
        titleView.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        tcol.addView(titleView);
        subView = new TextView(this);
        subView.setTextColor(getColor(R.color.dim));
        subView.setTextSize(12f);
        subView.setPadding(0, Ui.dp(this, 3), 0, 0);
        tcol.addView(subView);
        topBar.addView(tcol, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        col.addView(topBar);

        list = new ListView(this);
        list.setDivider(null);
        list.setDividerHeight(0);
        list.setPadding(Ui.dp(this, 10), 0, Ui.dp(this, 10), Ui.dp(this, 8));
        list.setClipToPadding(false);
        list.setAdapter(adapter);
        col.addView(list, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        root.addView(col, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // 底部操作栏
        bottomBar = new LinearLayout(this);
        bottomBar.setOrientation(LinearLayout.HORIZONTAL);
        bottomBar.setGravity(Gravity.CENTER_VERTICAL);
        bottomBar.setPadding(Ui.dp(this, 14), Ui.dp(this, 10), Ui.dp(this, 14), Ui.dp(this, 14));

        tipView = new TextView(this);
        tipView.setTextColor(getColor(R.color.dim));
        tipView.setTextSize(11.5f);
        tipView.setVisibility(View.GONE);
        bottomBar.addView(tipView, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        extractBtn = new TextView(this);
        extractBtn.setText("解压");
        extractBtn.setTextColor(0xFF06231B);
        extractBtn.setTextSize(14.5f);
        extractBtn.setTypeface(Typeface.DEFAULT_BOLD);
        extractBtn.setGravity(Gravity.CENTER);
        extractBtn.setPadding(Ui.dp(this, 26), Ui.dp(this, 12), Ui.dp(this, 26), Ui.dp(this, 12));
        extractBtn.setBackground(Ui.round(this, getColor(R.color.accent), 22f));
        extractBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startFlow();
            }
        });
        bottomBar.addView(extractBtn);
        root.addView(bottomBar, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM));

        buildProgress();
        setContentView(root);
        titleView.setText(name);
        subView.setText("正在读取…");
    }

    private void buildProgress() {
        progressLayer = new FrameLayout(this);
        progressLayer.setBackgroundColor(0xE6000000);
        progressLayer.setVisibility(View.GONE);
        progressLayer.setClickable(true);

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(Ui.dp(this, 26), Ui.dp(this, 24), Ui.dp(this, 26), Ui.dp(this, 18));
        GradientDrawable bg = Ui.round(this, getColor(R.color.surface), 18f);
        box.setBackground(bg);

        progressTitle = new TextView(this);
        progressTitle.setTextColor(getColor(R.color.text));
        progressTitle.setTextSize(15f);
        progressTitle.setTypeface(Typeface.DEFAULT_BOLD);
        box.addView(progressTitle);

        bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(1);
        bar.setProgress(0);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 6));
        blp.topMargin = Ui.dp(this, 14);
        box.addView(bar, blp);

        progressFile = new TextView(this);
        progressFile.setTextColor(getColor(R.color.dim));
        progressFile.setTextSize(12f);
        progressFile.setSingleLine(true);
        progressFile.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        flp.topMargin = Ui.dp(this, 10);
        box.addView(progressFile, flp);

        cancelBtn = new TextView(this);
        cancelBtn.setText("取消");
        cancelBtn.setTextColor(getColor(R.color.text));
        cancelBtn.setTextSize(13.5f);
        cancelBtn.setGravity(Gravity.CENTER);
        cancelBtn.setPadding(Ui.dp(this, 18), Ui.dp(this, 9), Ui.dp(this, 18), Ui.dp(this, 9));
        cancelBtn.setBackground(Ui.round(this, getColor(R.color.surface2), 18f));
        cancelBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                cancelled = true;
                progressTitle.setText("正在停止…");
            }
        });
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.topMargin = Ui.dp(this, 16);
        clp.gravity = Gravity.END;
        box.addView(cancelBtn, clp);

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                Ui.dp(this, 300), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        progressLayer.addView(box, lp);
        root.addView(progressLayer, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    /** 条目列表 */
    private final BaseAdapter adapter = new BaseAdapter() {
        @Override
        public int getCount() {
            return entries.size();
        }

        @Override
        public Object getItem(int position) {
            return entries.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convert, ViewGroup parent) {
            Zip.Entry e = entries.get(position);
            LinearLayout row = new LinearLayout(ArchiveActivity.this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(Ui.dp(ArchiveActivity.this, 8), Ui.dp(ArchiveActivity.this, 11),
                    Ui.dp(ArchiveActivity.this, 8), Ui.dp(ArchiveActivity.this, 11));

            ImageView ic = new ImageView(ArchiveActivity.this);
            ic.setImageResource(e.isDirectory() ? R.drawable.ic_folder : R.drawable.ic_archive);
            ic.setColorFilter(e.isDirectory() ? getColor(R.color.accent)
                    : Ui.alpha(getColor(R.color.dim), 0.9f));
            int is = Ui.dp(ArchiveActivity.this, 16);
            LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(is, is);
            ilp.setMargins(0, 0, Ui.dp(ArchiveActivity.this, 10), 0);
            row.addView(ic, ilp);

            TextView t = new TextView(ArchiveActivity.this);
            t.setText(e.name);
            t.setTextColor(getColor(R.color.text));
            t.setTextSize(13.5f);
            t.setSingleLine(true);
            t.setEllipsize(TextUtils.TruncateAt.MIDDLE);
            row.addView(t, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            if (!e.isDirectory()) {
                TextView sz = new TextView(ArchiveActivity.this);
                sz.setText(Ui.fmtSize(e.size));
                sz.setTextColor(getColor(R.color.dim));
                sz.setTextSize(11f);
                sz.setPadding(Ui.dp(ArchiveActivity.this, 10), 0, 0, 0);
                row.addView(sz);
            }
            return row;
        }
    };

    // ---------------- 读取 ----------------

    private void startLoad() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                Zip z = null;
                String err = null;
                try {
                    ParcelFileDescriptor pfd = getContentResolver()
                            .openFileDescriptor(Uri.parse(uriStr), "r");
                    if (pfd == null) throw new IOException("打不开这个文件");
                    FileChannel ch = new FileInputStream(pfd.getFileDescriptor()).getChannel();
                    z = Zip.open(ch, pfd);
                } catch (Throwable t) {
                    err = t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
                }
                final Zip fz = z;
                final String fe = err;
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        onLoaded(fz, fe);
                    }
                });
            }
        }).start();
    }

    private void onLoaded(Zip z, String err) {
        if (isFinishing()) return;
        if (z == null) {
            subView.setText("");
            showUnsupported(err);
            return;
        }
        zip = z;
        entries.clear();
        entries.addAll(zip.entries);
        fileCount = 0;
        long total = 0;
        for (Zip.Entry e : zip.entries) {
            if (e.isDirectory()) continue;
            fileCount++;
            total += e.size;
        }
        adapter.notifyDataSetChanged();

        String extra = zip.needsPassword() ? " · 已加密" : "";
        subView.setText(String.format(Locale.US, "%d 个文件 · %s%s",
                fileCount, Ui.fmtSize(total), extra));
        extractBtn.setEnabled(true);
        if (fileCount == 0) {
            extractBtn.setEnabled(false);
            tipView.setVisibility(View.VISIBLE);
            tipView.setText("这个压缩包里没有文件");
        }
    }

    /** 不是 zip，或者打不开 */
    private void showUnsupported(String err) {
        extractBtn.setVisibility(View.GONE);
        tipView.setVisibility(View.GONE);
        list.setVisibility(View.GONE);
        bottomBar.setVisibility(View.GONE);

        String low = name.toLowerCase(Locale.US);
        boolean sevenZ = low.endsWith(".7z");
        boolean rar = low.endsWith(".rar");

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER);
        col.setPadding(Ui.dp(this, 34), 0, Ui.dp(this, 34), Ui.dp(this, 60));

        TextView h = new TextView(this);
        h.setTextColor(getColor(R.color.text));
        h.setTextSize(16f);
        h.setTypeface(Typeface.DEFAULT_BOLD);
        h.setGravity(Gravity.CENTER);
        h.setText(sevenZ ? "7z 需要额外的解码器" : (rar ? "rar 需要额外的解码器" : "打不开这个压缩包"));
        col.addView(h);

        TextView d = new TextView(this);
        d.setTextColor(getColor(R.color.dim));
        d.setTextSize(13f);
        d.setGravity(Gravity.CENTER);
        d.setLineSpacing(0, 1.5f);
        d.setPadding(0, Ui.dp(this, 12), 0, 0);
        if (sevenZ || rar) {
            d.setText("Android 系统里没有 " + (sevenZ ? "7z" : "rar") + " 的解码器，"
                    + "这个格式没法在应用内解开。\n\n"
                    + "可以试试点下面的按钮，交给手机上的文件管理器或解压 App 处理；\n"
                    + "也可以先改成 zip 再传过来。");
        } else {
            d.setText("可能是文件损坏，或者不是标准 zip。\n" + (err == null ? "" : err));
        }
        col.addView(d);

        TextView open = new TextView(this);
        open.setText("用其他应用打开");
        open.setTextColor(0xFF06231B);
        open.setTextSize(14f);
        open.setTypeface(Typeface.DEFAULT_BOLD);
        open.setGravity(Gravity.CENTER);
        open.setPadding(Ui.dp(this, 22), Ui.dp(this, 11), Ui.dp(this, 22), Ui.dp(this, 11));
        open.setBackground(Ui.round(this, getColor(R.color.accent), 20f));
        LinearLayout.LayoutParams olp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        olp.topMargin = Ui.dp(this, 22);
        olp.gravity = Gravity.CENTER_HORIZONTAL;
        open.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openWithOther();
            }
        });
        col.addView(open, olp);

        root.addView(col, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.CENTER));
    }

    private void openWithOther() {
        try {
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(Uri.parse(uriStr), Saf.mimeOf(name));
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(i);
        } catch (Exception e) {
            Toast.makeText(this, "手机上也没有能打开它的应用", Toast.LENGTH_LONG).show();
        }
    }

    // ---------------- 解压流程 ----------------

    private void startFlow() {
        if (zip == null) return;
        if (zip.needsPassword() && password == null) {
            askPassword();
            return;
        }
        askDestination();
    }

    private void askPassword() {
        final EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        input.setHint("压缩包密码");
        input.setSingleLine(true);
        input.setTextColor(getColor(R.color.text));
        input.setHintTextColor(getColor(R.color.dim));
        int p = Ui.dp(this, 20);
        FrameLayout wrap = new FrameLayout(this);
        wrap.setPadding(p, Ui.dp(this, 8), p, 0);
        wrap.addView(input, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        new AlertDialog.Builder(this)
                .setTitle("这个压缩包有密码")
                .setView(wrap)
                .setNegativeButton("取消", null)
                .setPositiveButton("确定", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        checkPassword(input.getText().toString());
                    }
                })
                .show();
    }

    private void checkPassword(final String pwd) {
        subView.setText("正在校验密码…");
        new Thread(new Runnable() {
            @Override
            public void run() {
                final boolean ok = zip != null && zip.checkPassword(pwd);
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        if (isFinishing()) return;
                        if (onLoadedSub()) return;
                        if (ok) {
                            password = pwd;
                            askDestination();
                        } else {
                            Toast.makeText(ArchiveActivity.this, "密码不对", Toast.LENGTH_SHORT).show();
                            askPassword();
                        }
                    }
                });
            }
        }).start();
    }

    /** 密码校验会短暂覆盖副标题，这里恢复一下 */
    private boolean onLoadedSub() {
        if (zip == null) return true;
        long total = 0;
        for (Zip.Entry e : zip.entries) {
            if (!e.isDirectory()) total += e.size;
        }
        String extra = zip.needsPassword() ? " · 已加密" : "";
        subView.setText(String.format(Locale.US, "%d 个文件 · %s%s",
                fileCount, Ui.fmtSize(total), extra));
        return false;
    }

    private void askDestination() {
        final String folder = baseName();
        String[] items = {"解压到新建文件夹「" + folder + "」", "解压到当前文件夹"};
        new AlertDialog.Builder(this)
                .setTitle("解压到哪儿")
                .setItems(items, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        beginExtract(which == 0);
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private String baseName() {
        String n = name;
        int dot = n.lastIndexOf('.');
        if (dot > 0) n = n.substring(0, dot);
        return n.trim().isEmpty() ? "解压结果" : n.trim();
    }

    private void beginExtract(final boolean toSubFolder) {
        cancelled = false;
        progressLayer.setVisibility(View.VISIBLE);
        progressTitle.setText("正在解压");
        progressFile.setText("");
        bar.setMax(Math.max(1, fileCount));
        bar.setProgress(0);
        extractBtn.setEnabled(false);

        final Zip z = zip;
        final String pwd = password == null ? "" : password;
        final ContentResolver cr = getContentResolver();
        final Uri tree = Uri.parse(treeUri);
        final Uri doc = Uri.parse(uriStr);
        final Uri parent = Saf.parentDocument(tree, doc);
        final String root = commonRoot();
        final boolean intoFolder = toSubFolder;
        final String folderName = baseName();

        new Thread(new Runnable() {
            @Override
            public void run() {
                int done = 0, failed = 0;
                String firstError = null;
                Uri dest = parent;
                Map<String, Uri> dirs = new HashMap<String, Uri>();

                try {
                    if (intoFolder) dest = Saf.ensureDir(cr, tree, parent, folderName);
                } catch (Exception e) {
                    postFail("没有写入权限，或者建不了文件夹");
                    return;
                }
                if (dest == null) {
                    postFail("没有写入权限，或者建不了文件夹");
                    return;
                }

                for (Zip.Entry e : z.entries) {
                    if (cancelled) break;
                    if (e.isDirectory()) continue;
                    String rel = e.name.replace('\\', '/');
                    if (!root.isEmpty() && rel.startsWith(root + "/")) {
                        rel = rel.substring(root.length() + 1);
                    }
                    int slash = rel.lastIndexOf('/');
                    String dirPath = slash < 0 ? "" : rel.substring(0, slash);
                    String fileName = slash < 0 ? rel : rel.substring(slash + 1);
                    if (fileName.isEmpty()) continue;

                    try {
                        Uri dir = dest;
                        if (dirPath.length() > 0) {
                            StringBuilder keyBuf = new StringBuilder();
                            for (String seg : dirPath.split("/")) {
                                if (seg.isEmpty() || seg.equals(".")) continue;
                                keyBuf.append('/').append(seg);
                                String key = keyBuf.toString();
                                Uri cached = dirs.get(key);
                                if (cached != null) {
                                    dir = cached;
                                    continue;
                                }
                                dir = Saf.ensureDir(cr, tree, dir, seg);
                                dirs.put(key, dir);
                            }
                        }
                        Uri out = Saf.createFile(cr, tree, dir, fileName);
                        OutputStream os = Saf.openOut(cr, out);
                        try {
                            z.extract(e, pwd, os);
                        } finally {
                            os.close();
                        }
                        done++;
                    } catch (SecurityException se) {
                        failed++;
                        if (firstError == null) firstError = "没有写入权限";
                        break;
                    } catch (Exception ex) {
                        failed++;
                        if (firstError == null) {
                            firstError = ex.getMessage() == null ? "未知错误" : ex.getMessage();
                        }
                    }

                    final int d = done;
                    final String fn = fileName;
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            bar.setProgress(d);
                            progressFile.setText(fn);
                        }
                    });
                }

                final int fd = done, ff = failed;
                final String fe = firstError;
                final boolean cancelledFinal = cancelled;
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        finishExtract(fd, ff, fe, cancelledFinal, intoFolder, folderName);
                    }
                });
            }
        }).start();
    }

    private void postFail(final String msg) {
        ui.post(new Runnable() {
            @Override
            public void run() {
                progressLayer.setVisibility(View.GONE);
                extractBtn.setEnabled(true);
                Toast.makeText(ArchiveActivity.this, msg, Toast.LENGTH_LONG).show();
            }
        });
    }

    private void finishExtract(int done, int failed, String firstError,
                               boolean wasCancelled, boolean intoFolder, String folderName) {
        if (isFinishing()) return;
        progressLayer.setVisibility(View.GONE);
        extractBtn.setEnabled(true);

        String where = intoFolder && !folderName.isEmpty()
                ? "「" + folderName + "」" : "当前文件夹";
        if (wasCancelled) {
            Toast.makeText(this, "已取消，已解出 " + done + " 个文件", Toast.LENGTH_LONG).show();
            if (done > 0) setResult(RESULT_OK);
            return;
        }
        if (failed > 0 && done == 0) {
            String msg = "解压失败：" + (firstError == null ? "未知错误" : firstError);
            if (firstError != null && firstError.contains("没有写入")) {
                msg = "这个文件夹当初只授权了读取。请回到片库 →「管理」里移除它，再重新添加一次（重新添加会同时获得写入权限）。";
            }
            new AlertDialog.Builder(this)
                    .setTitle("没能解压")
                    .setMessage(msg)
                    .setPositiveButton("知道了", null)
                    .show();
            return;
        }
        String msg = String.format(Locale.US, "已解压 %d 个文件到%s", done, where);
        if (failed > 0) msg += "，" + failed + " 个失败";
        final String m = msg;
        new AlertDialog.Builder(this)
                .setMessage(m)
                .setPositiveButton("完成", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        setResult(RESULT_OK);
                        finish();
                    }
                })
                .show();
    }

    /** 压缩包里如果所有文件都在同一个顶层目录下，就把它剥掉，避免「番名/番名/01.mkv」 */
    private String commonRoot() {
        String root = null;
        for (Zip.Entry e : zip.entries) {
            String n = e.name.replace('\\', '/');
            if (e.isDirectory()) {
                if (n.endsWith("/")) n = n.substring(0, n.length() - 1);
            }
            if (n.isEmpty()) continue;
            int p = n.indexOf('/');
            if (p < 0) return "";
            String first = n.substring(0, p);
            if (root == null) root = first;
            else if (!root.equals(first)) return "";
        }
        return root == null ? "" : root;
    }

    @Override
    public void onBackPressed() {
        if (progressLayer.getVisibility() == View.VISIBLE) {
            cancelled = true;
            progressTitle.setText("正在停止…");
            return;
        }
        super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        cancelled = true;
        if (zip != null) {
            final Zip z = zip;
            zip = null;
            new Thread(new Runnable() {
                @Override
                public void run() {
                    z.close();
                }
            }).start();
        }
    }
}
