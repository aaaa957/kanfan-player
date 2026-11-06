package com.kanfan.player;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.pdf.PdfRenderer;
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
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * PDF 阅读：系统 PdfRenderer 逐页渲染，{@link PdfView} 负责滚动与缩放。
 *
 * 只做「看」——PDF 里的文字不可选、不可搜；加密或损坏的文件给明确提示。
 */
public class PdfActivity extends Activity {

    private FrameLayout root;
    private PdfView view;
    private LinearLayout topBar;
    private TextView indicator, loading, errorView;
    private ImageView back;

    private String uriStr = "", name = "";
    private PdfRenderer renderer;
    private ParcelFileDescriptor pfd;
    private File tmpCopy;
    private final Object lock = new Object();
    private volatile boolean closed;
    private boolean barsVisible = true;

    private final Handler ui = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        uriStr = getIntent().getStringExtra("uri");
        name = getIntent().getStringExtra("name");
        if (uriStr == null) uriStr = "";
        if (name == null) name = "";
        buildUi();
        openAsync();
    }

    // ---------------- 界面 ----------------

    private void buildUi() {
        root = new FrameLayout(this);
        root.setBackgroundColor(0xFF101014);

        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        root.addView(column, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        final FrameLayout content = new FrameLayout(this);
        column.addView(content, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        view = new PdfView(this);
        view.setRenderer(new PdfView.Renderer() {
            @Override
            public Bitmap render(int index, int targetWidth) throws Exception {
                return renderPage(index, targetWidth);
            }
        });
        view.setListener(new PdfView.Listener() {
            @Override
            public void onPageChanged(int page, int count) {
                indicator.setText(String.format(java.util.Locale.US, "%d / %d", page + 1, count));
            }

            @Override
            public void onTap() {
                setBarsVisible(!barsVisible);
            }
        });
        content.addView(view, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setBackgroundColor(0xE6151518);
        topBar.setPadding(Ui.dp(this, 4), Ui.dp(this, 10), Ui.dp(this, 12), Ui.dp(this, 10));

        back = new ImageView(this);
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
        topBar.addView(back, new LinearLayout.LayoutParams(bs, bs));

        TextView title = new TextView(this);
        title.setText(Ui.prettyTitle(name));
        title.setTextColor(getColor(R.color.text));
        title.setTextSize(15f);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tlp.setMargins(Ui.dp(this, 6), 0, Ui.dp(this, 8), 0);
        topBar.addView(title, tlp);

        indicator = new TextView(this);
        indicator.setText("— / —");
        indicator.setTextColor(getColor(R.color.dim));
        indicator.setTextSize(12f);
        topBar.addView(indicator);

        TextView jump = new TextView(this);
        jump.setText("跳页");
        jump.setTextColor(getColor(R.color.text));
        jump.setTextSize(12.5f);
        jump.setPadding(Ui.dp(this, 12), Ui.dp(this, 6), Ui.dp(this, 12), Ui.dp(this, 6));
        jump.setBackground(Ui.round(this, getColor(R.color.surface2), 16f));
        LinearLayout.LayoutParams jlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        jlp.setMargins(Ui.dp(this, 10), 0, 0, 0);
        jump.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                askPage();
            }
        });
        topBar.addView(jump, jlp);

        column.addView(topBar, 0, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        loading = new TextView(this);
        loading.setText("正在打开 PDF…");
        loading.setTextColor(getColor(R.color.dim));
        loading.setTextSize(14f);
        loading.setGravity(Gravity.CENTER);
        content.addView(loading, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER));

        setContentView(root);
    }

    private void setBarsVisible(boolean visible) {
        barsVisible = visible;
        topBar.setVisibility(visible ? View.VISIBLE : View.GONE);
    }

    private void toast(String msg) {
        android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show();
    }

    // ---------------- 打开 ----------------

    private void openAsync() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    ParcelFileDescriptor p = getContentResolver()
                            .openFileDescriptor(Uri.parse(uriStr), "r");
                    if (p == null) throw new IOException("打不开这个文件");
                    PdfRenderer r;
                    try {
                        r = new PdfRenderer(p);
                    } catch (Throwable first) {
                        // 有密码的重开也没用，直接报
                        String msg = first.getMessage() == null ? "" : first.getMessage().toLowerCase(java.util.Locale.US);
                        if (msg.contains("password")) throw first;
                        // 有些提供方给的是管道，PdfRenderer 要能随机读
                        safeClose(p);
                        p = openCopy();
                        r = new PdfRenderer(p);
                    }
                    final ParcelFileDescriptor keep = p;
                    final PdfRenderer opened = r;
                    final int n = r.getPageCount();
                    if (n <= 0) throw new IOException("这个 PDF 没有页面");
                    final int[] w = new int[n], h = new int[n];
                    for (int i = 0; i < n; i++) {
                        PdfRenderer.Page pg = r.openPage(i);
                        w[i] = pg.getWidth();
                        h[i] = pg.getHeight();
                        pg.close();
                    }
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            if (isFinishing() || isDestroyed()) {
                                closeQuietly(opened, keep);
                                return;
                            }
                            pfd = keep;
                            renderer = opened;
                            view.setPages(w, h);
                            loading.setVisibility(View.GONE);
                            int last = Store.getPdfPage(PdfActivity.this, uriStr);
                            if (last > 0) view.gotoPage(last - 1);
                            indicator.setText(String.format(java.util.Locale.US, "%d / %d",
                                    view.currentPage() + 1, n));
                        }
                    });
                } catch (final Throwable t) {
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            showError(friendly(t));
                        }
                    });
                }
            }
        }).start();
    }

    private String friendly(Throwable t) {
        String m = t.getMessage() == null ? "" : t.getMessage();
        if (m.toLowerCase(java.util.Locale.US).contains("password")) {
            return "这个 PDF 有密码，暂时打不开。";
        }
        if (t instanceof OutOfMemoryError) return "这个 PDF 太大，内存不够渲染。";
        return "打不开这个 PDF。\n\n" + m;
    }

    private ParcelFileDescriptor openCopy() throws IOException {
        tmpCopy = new File(getCacheDir(), "pdf-open.tmp");
        InputStream in = getContentResolver().openInputStream(Uri.parse(uriStr));
        if (in == null) throw new IOException("读不到这个文件");
        OutputStream out = new FileOutputStream(tmpCopy);
        try {
            byte[] b = new byte[256 * 1024];
            int n;
            while ((n = in.read(b)) > 0) out.write(b, 0, n);
        } finally {
            try {
                in.close();
            } catch (Exception ignored) {
            }
            try {
                out.close();
            } catch (Exception ignored) {
            }
        }
        return ParcelFileDescriptor.open(tmpCopy, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    private void showError(String msg) {
        loading.setVisibility(View.GONE);
        if (errorView != null) return;
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER);
        int p = Ui.dp(this, 30);
        col.setPadding(p, 0, p, Ui.dp(this, 60));

        errorView = new TextView(this);
        errorView.setText(msg);
        errorView.setTextColor(getColor(R.color.dim));
        errorView.setTextSize(13.5f);
        errorView.setGravity(Gravity.CENTER);
        errorView.setLineSpacing(0, 1.5f);
        col.addView(errorView);

        TextView open = new TextView(this);
        open.setText("用其他应用打开");
        open.setTextColor(0xFF06231B);
        open.setTextSize(14f);
        open.setTypeface(Typeface.DEFAULT_BOLD);
        open.setGravity(Gravity.CENTER);
        open.setPadding(Ui.dp(this, 22), Ui.dp(this, 11), Ui.dp(this, 22), Ui.dp(this, 11));
        open.setBackground(Ui.round(this, getColor(R.color.accent), 20f));
        open.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                    Intent i = new Intent(Intent.ACTION_VIEW);
                    i.setDataAndType(Uri.parse(uriStr), "application/pdf");
                    i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    startActivity(i);
                } catch (Exception e) {
                    toast("手机上也没有能打开它的应用");
                }
            }
        });
        LinearLayout.LayoutParams olp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        olp.topMargin = Ui.dp(this, 20);
        olp.gravity = Gravity.CENTER_HORIZONTAL;
        col.addView(open, olp);

        root.addView(col, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.CENTER));
    }

    // ---------------- 渲染 ----------------

    private Bitmap renderPage(int index, int targetWidth) throws Exception {
        synchronized (lock) {
            if (closed || renderer == null) throw new IOException("已关闭");
            PdfRenderer.Page page = renderer.openPage(index);
            try {
                int pw = Math.max(1, page.getWidth());
                int ph = Math.max(1, page.getHeight());
                int w = Math.max(64, Math.min(targetWidth, 1800));
                int h = (int) Math.round((double) w * ph / pw);
                // 一页 4 字节/像素，别让单页位图把堆撑爆
                long maxPx = 4_000_000L;
                if ((long) w * h > maxPx) {
                    double k = Math.sqrt((double) maxPx / ((double) w * h));
                    w = Math.max(64, (int) (w * k));
                    h = Math.max(64, (int) (h * k));
                }
                Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                bmp.eraseColor(Color.WHITE);
                page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                return bmp;
            } finally {
                page.close();
            }
        }
    }

    // ---------------- 跳页 / 生命周期 ----------------

    private void askPage() {
        if (renderer == null) return;
        final int count = view.pageCount();
        if (count <= 0) return;
        final EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        input.setSingleLine(true);
        input.setHint("1 – " + count);
        input.setText(String.valueOf(view.currentPage() + 1));
        input.setSelection(input.getText().length());
        input.setTextColor(getColor(R.color.text));
        input.setHintTextColor(getColor(R.color.dim));
        FrameLayout wrap = new FrameLayout(this);
        int p = Ui.dp(this, 20);
        wrap.setPadding(p, Ui.dp(this, 8), p, 0);
        wrap.addView(input, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        new AlertDialog.Builder(this)
                .setTitle("跳到第几页")
                .setView(wrap)
                .setNegativeButton("取消", null)
                .setPositiveButton("跳转", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        try {
                            int page = Integer.parseInt(input.getText().toString().trim());
                            if (page >= 1 && page <= count) view.gotoPage(page - 1);
                        } catch (Exception ignored) {
                        }
                    }
                })
                .show();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (renderer != null && view.pageCount() > 0) {
            Store.savePdfPage(this, uriStr, view.currentPage() + 1);
        }
    }

    @Override
    public void onBackPressed() {
        if (!barsVisible) {
            setBarsVisible(true);
            return;
        }
        super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        closed = true;
        try {
            view.release();
        } catch (Throwable ignored) {
        }
        ui.post(new Runnable() {
            @Override
            public void run() {
                // 等渲染线程退出后再关 renderer
                synchronized (lock) {
                    closeQuietly(renderer, pfd);
                    renderer = null;
                    pfd = null;
                }
                if (tmpCopy != null) {
                    tmpCopy.delete();
                    tmpCopy = null;
                }
            }
        });
    }

    private static void closeQuietly(PdfRenderer r, ParcelFileDescriptor p) {
        if (r != null) {
            try {
                r.close();
            } catch (Exception ignored) {
            }
        }
        safeClose(p);
    }

    private static void safeClose(ParcelFileDescriptor p) {
        if (p != null) {
            try {
                p.close();
            } catch (Exception ignored) {
            }
        }
    }
}
