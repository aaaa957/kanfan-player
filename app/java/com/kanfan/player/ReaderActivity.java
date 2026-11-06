package com.kanfan.player;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Html;
import android.text.Spanned;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipFile;

/**
 * 阅读器：TXT 与 EPUB 共用一套排版。
 * TXT 按章节切分后整章渲染；EPUB 按 spine 逐章渲染，图片从 zip 里就地解码。
 */
public class ReaderActivity extends Activity {

    private LinearLayout root;
    private TextView topTitle, btnChapter, btnAa, footTitle, footProgress;
    private ScrollView scroll;
    private TextView content;
    private LinearLayout topBar, bottomBar;

    private Uri uri;
    private String fileName = "";
    private String bookKey = "";

    private boolean isEpub;
    private String txt = "";
    private List<Split.Chapter> chapters = new ArrayList<Split.Chapter>();
    private Epub.Book book;
    private ZipFile epubZip;
    private File epubCache;

    private int cur = 0;
    private int fontSize = 18;
    private int theme = 0;
    private int renderToken = 0;
    private boolean barsVisible = true;

    private final Handler ui = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        uri = Uri.parse(getIntent().getStringExtra("uri"));
        fileName = getIntent().getStringExtra("name");
        if (fileName == null) fileName = "";
        bookKey = uri.toString();
        fontSize = Store.getFontSize(this);
        theme = Store.getTheme(this);

        buildUi();
        applyTheme();
        startLoad();
    }

    // ---------------- UI ----------------

    private void buildUi() {
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);

        topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setPadding(Ui.dp(this, 8), Ui.dp(this, 10), Ui.dp(this, 8), Ui.dp(this, 8));

        ImageView back = new ImageView(this);
        back.setImageResource(R.drawable.ic_back);
        back.setPadding(Ui.dp(this, 10), Ui.dp(this, 10), Ui.dp(this, 10), Ui.dp(this, 10));
        back.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        topBar.addView(back, new LinearLayout.LayoutParams(Ui.dp(this, 40), Ui.dp(this, 40)));

        topTitle = new TextView(this);
        topTitle.setTextSize(15f);
        topTitle.setTypeface(Typeface.DEFAULT_BOLD);
        topTitle.setSingleLine(true);
        topTitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
        topTitle.setPadding(Ui.dp(this, 6), 0, Ui.dp(this, 6), 0);
        topBar.addView(topTitle, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        btnChapter = barButton("目录");
        btnChapter.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showChapters();
            }
        });
        topBar.addView(btnChapter);

        btnAa = barButton("Aa");
        btnAa.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showSettings();
            }
        });
        LinearLayout.LayoutParams aalp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        aalp.setMargins(Ui.dp(this, 8), 0, 0, 0);
        topBar.addView(btnAa, aalp);

        root.addView(topBar);

        scroll = new ScrollView(this);
        scroll.setFillViewport(false);
        scroll.setVerticalScrollBarEnabled(false);

        content = new TextView(this);
        content.setTextSize(fontSize);
        content.setLineSpacing(0, 1.65f);
        content.setPadding(Ui.dp(this, 22), Ui.dp(this, 16), Ui.dp(this, 22), Ui.dp(this, 40));
        content.setTextIsSelectable(false);
        content.setMovementMethod(null);
        scroll.addView(content, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        bottomBar = new LinearLayout(this);
        bottomBar.setOrientation(LinearLayout.HORIZONTAL);
        bottomBar.setGravity(Gravity.CENTER_VERTICAL);
        bottomBar.setPadding(Ui.dp(this, 16), Ui.dp(this, 8), Ui.dp(this, 16), Ui.dp(this, 10));

        TextView prev = barButton("上一章");
        prev.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                jump(-1);
            }
        });
        bottomBar.addView(prev);

        LinearLayout mid = new LinearLayout(this);
        mid.setOrientation(LinearLayout.VERTICAL);
        mid.setGravity(Gravity.CENTER);
        footTitle = new TextView(this);
        footTitle.setTextSize(12.5f);
        footTitle.setSingleLine(true);
        footTitle.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        footTitle.setGravity(Gravity.CENTER);
        footProgress = new TextView(this);
        footProgress.setTextSize(10.5f);
        footProgress.setGravity(Gravity.CENTER);
        mid.addView(footTitle);
        mid.addView(footProgress);
        LinearLayout.LayoutParams midLp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        midLp.setMargins(Ui.dp(this, 10), 0, Ui.dp(this, 10), 0);
        bottomBar.addView(mid, midLp);

        TextView next = barButton("下一章");
        next.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                jump(1);
            }
        });
        bottomBar.addView(next);

        root.addView(bottomBar);
        setContentView(root);

        // 点击正文区域显隐上下栏，滑动不受影响
        scroll.setOnTouchListener(new View.OnTouchListener() {
            float downX, downY;
            long downAt;

            @Override
            public boolean onTouch(View v, MotionEvent e) {
                if (e.getAction() == MotionEvent.ACTION_DOWN) {
                    downX = e.getX();
                    downY = e.getY();
                    downAt = System.currentTimeMillis();
                } else if (e.getAction() == MotionEvent.ACTION_UP) {
                    float dx = Math.abs(e.getX() - downX), dy = Math.abs(e.getY() - downY);
                    if (dx < Ui.dp(ReaderActivity.this, 8) && dy < Ui.dp(ReaderActivity.this, 8)
                            && System.currentTimeMillis() - downAt < 300) {
                        toggleBars();
                    }
                }
                return false;
            }
        });

        scroll.setOnScrollChangeListener(new View.OnScrollChangeListener() {
            @Override
            public void onScrollChange(View v, int sx, int sy, int ox, int oy) {
                updateFoot();
            }
        });
    }

    private TextView barButton(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(12.5f);
        int p = Ui.dp(this, 10);
        t.setPadding(Ui.dp(this, 12), p, Ui.dp(this, 12), p);
        return t;
    }

    private void applyTheme() {
        int bg = bgColor(), fg = textColor(), dim = dimColor();
        root.setBackgroundColor(bg);
        scroll.setBackgroundColor(bg);
        content.setTextColor(fg);
        content.setLinkTextColor(0xFF4C9AFF);
        topBar.setBackgroundColor(bg);
        bottomBar.setBackgroundColor(bg);
        topTitle.setTextColor(fg);
        footTitle.setTextColor(dim);
        footProgress.setTextColor(dim);
        tintButton(btnChapter, fg);
        tintButton(btnAa, fg);
        for (int i = 0; i < bottomBar.getChildCount(); i++) {
            View c = bottomBar.getChildAt(i);
            if (c instanceof TextView && c != footTitle && c != footProgress) tintButton((TextView) c, fg);
        }
        if (Build.VERSION.SDK_INT >= 21) {
            getWindow().setStatusBarColor(bg);
        }
        if (Build.VERSION.SDK_INT >= 23) {
            View dv = getWindow().getDecorView();
            int flags = dv.getSystemUiVisibility();
            if (theme == 0) flags &= ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            else flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            dv.setSystemUiVisibility(flags);
        }
    }

    private void tintButton(TextView t, int color) {
        if (t == null) return;
        t.setTextColor(color);
    }

    private int bgColor() {
        return theme == 0 ? 0xFF11141A : (theme == 1 ? 0xFFF3E9D6 : 0xFFFFFFFF);
    }

    private int textColor() {
        return theme == 0 ? 0xFFE4E7EC : (theme == 1 ? 0xFF3B3028 : 0xFF1A1A1A);
    }

    private int dimColor() {
        return theme == 0 ? 0x88FFFFFF : 0x882A241E;
    }

    private void toggleBars() {
        barsVisible = !barsVisible;
        int v = barsVisible ? View.VISIBLE : View.GONE;
        topBar.setVisibility(v);
        bottomBar.setVisibility(v);
    }

    // ---------------- 载入 ----------------

    private void startLoad() {
        content.setText("正在载入…");
        final String low = fileName.toLowerCase(Locale.US);
        isEpub = low.endsWith(".epub");
        new Thread(new Runnable() {
            @Override
            public void run() {
                if (isEpub) loadEpub();
                else loadTxt();
            }
        }).start();
    }

    private void loadTxt() {
        final String text = Scan.readText(this, uri, 64 * 1024 * 1024);
        final List<Split.Chapter> chs = Split.chapters(text);
        ui.post(new Runnable() {
            @Override
            public void run() {
                if (text.trim().isEmpty()) {
                    content.setText("这个文件读不出内容。\n\n可能是编码不支持，或者文件是空的。");
                    return;
                }
                txt = text;
                chapters = chs;
                topTitle.setText(baseName(fileName));
                restoreAndShow();
            }
        });
    }

    private void loadEpub() {
        try {
            epubCache = new File(getCacheDir(), "epub_" + Math.abs(bookKey.hashCode()) + ".epub");
            if (!epubCache.exists() || epubCache.length() == 0) {
                InputStream in = getContentResolver().openInputStream(uri);
                if (in == null) throw new Exception("打不开文件");
                FileOutputStream out = new FileOutputStream(epubCache);
                byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                out.close();
                in.close();
            }
            epubZip = new ZipFile(epubCache);
            final Epub.Book b = Epub.open(epubZip);
            ui.post(new Runnable() {
                @Override
                public void run() {
                    if (b.spine.isEmpty()) {
                        content.setText("这本 EPUB 里没解析出正文。\n\n可能不是标准 EPUB，或正文不是 XHTML。");
                        return;
                    }
                    book = b;
                    chapters.clear();
                    topTitle.setText(b.title.isEmpty() ? baseName(fileName) : b.title);
                    restoreAndShow();
                }
            });
        } catch (final Exception e) {
            ui.post(new Runnable() {
                @Override
                public void run() {
                    content.setText("打开 EPUB 失败：" + e.getMessage());
                }
            });
        }
    }

    private String baseName(String n) {
        if (n == null) return "";
        int dot = n.lastIndexOf('.');
        return dot > 0 ? n.substring(0, dot) : n;
    }

    private int chapterCount() {
        return isEpub ? (book == null ? 0 : book.spine.size()) : chapters.size();
    }

    private String chapterTitle(int i) {
        if (isEpub) {
            if (book == null || i < 0 || i >= book.spine.size()) return "";
            return book.spine.get(i).title;
        }
        if (i < 0 || i >= chapters.size()) return "";
        return chapters.get(i).title;
    }

    private void restoreAndShow() {
        int[] pos = Store.getBookPos(this, bookKey);
        int idx = 0;
        float ratio = 0f;
        if (pos != null) {
            idx = pos[0];
            ratio = pos[1] / 1000f;
        }
        if (idx < 0 || idx >= chapterCount()) idx = 0;
        showChapter(idx, ratio);
        if (pos != null && idx > 0) {
            Toast.makeText(this, "已跳到上次读到的地方", Toast.LENGTH_SHORT).show();
        }
    }

    // ---------------- 章节 ----------------

    private void showChapter(final int index, final float ratio) {
        if (index < 0 || index >= chapterCount()) return;
        cur = index;
        updateFoot();

        if (!isEpub) {
            String body = txt.substring(chapters.get(index).start, chapters.get(index).end);
            content.setText(body.trim());
            scrollTo(ratio);
            return;
        }

        content.setText("正在排版…");
        final int token = ++renderToken;
        final Epub.Item item = book.spine.get(index);
        new Thread(new Runnable() {
            @Override
            public void run() {
                final Spanned sp = renderHtml(Epub.text(epubZip, item.href), Epub.dirOf(item.href));
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        if (token != renderToken || isFinishing()) return;
                        content.setText(sp);
                        scrollTo(ratio);
                    }
                });
            }
        }).start();
    }

    private void scrollTo(final float ratio) {
        scroll.post(new Runnable() {
            @Override
            public void run() {
                int max = maxScroll();
                scroll.scrollTo(0, (int) (max * Math.max(0f, Math.min(1f, ratio))));
                updateFoot();
            }
        });
    }

    private int maxScroll() {
        if (content == null || scroll == null) return 0;
        return Math.max(0, content.getHeight() - scroll.getHeight());
    }

    private float currentRatio() {
        int max = maxScroll();
        if (max <= 0) return 0f;
        return Math.max(0f, Math.min(1f, scroll.getScrollY() / (float) max));
    }

    private void jump(int delta) {
        int n = cur + delta;
        if (n < 0) {
            Toast.makeText(this, "已经是第一章", Toast.LENGTH_SHORT).show();
            return;
        }
        if (n >= chapterCount()) {
            Toast.makeText(this, "已经是最后一章", Toast.LENGTH_SHORT).show();
            return;
        }
        savePos();
        showChapter(n, 0f);
    }

    private void updateFoot() {
        if (footTitle == null) return;
        footTitle.setText(chapterTitle(cur));
        int pct = Math.round(currentRatio() * 100);
        footProgress.setText(String.format(Locale.US, "%d / %d 章 · %d%%",
                cur + 1, Math.max(1, chapterCount()), pct));
    }

    private void savePos() {
        if (chapterCount() == 0) return;
        Store.saveBookPos(this, bookKey, cur, currentRatio());
    }

    @Override
    protected void onPause() {
        super.onPause();
        savePos();
    }

    @Override
    public void onBackPressed() {
        savePos();
        super.onBackPressed();
    }

    // ---------------- EPUB 渲染 ----------------

    private Spanned renderHtml(final String html, final String baseDir) {
        Html.ImageGetter getter = new Html.ImageGetter() {
            @Override
            public Drawable getDrawable(String source) {
                try {
                    if (epubZip == null || source == null) return null;
                    byte[] data = Epub.bytes(epubZip, Epub.resolve(baseDir, source));
                    if (data == null) return null;
                    Bitmap bm = decode(data);
                    if (bm == null) return null;
                    BitmapDrawable d = new BitmapDrawable(getResources(), bm);
                    d.setBounds(0, 0, bm.getWidth(), bm.getHeight());
                    return d;
                } catch (Throwable t) {
                    return null;
                }
            }
        };
        String body = Epub.bodyOf(html);
        try {
            if (Build.VERSION.SDK_INT >= 24) {
                return Html.fromHtml(body, Html.FROM_HTML_MODE_LEGACY, getter, null);
            }
            return Html.fromHtml(body, getter, null);
        } catch (Throwable t) {
            return new android.text.SpannableString(body.replaceAll("<[^>]*>", ""));
        }
    }

    private Bitmap decode(byte[] data) {
        try {
            BitmapFactory.Options probe = new BitmapFactory.Options();
            probe.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(data, 0, data.length, probe);
            int max = Math.max(Ui.dp(this, 120), contentWidth());
            int sample = 1;
            while (probe.outWidth / (sample * 2) >= max && sample < 8) sample *= 2;

            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inSampleSize = sample;
            Bitmap bm = BitmapFactory.decodeByteArray(data, 0, data.length, o);
            if (bm == null) return null;
            if (bm.getWidth() > max) {
                int h = Math.max(1, bm.getHeight() * max / bm.getWidth());
                Bitmap sc = Bitmap.createScaledBitmap(bm, max, h, true);
                if (sc != bm) bm.recycle();
                bm = sc;
            }
            return bm;
        } catch (Throwable t) {
            return null;
        }
    }

    private int contentWidth() {
        int w = getResources().getDisplayMetrics().widthPixels - Ui.dp(this, 44);
        return Math.max(Ui.dp(this, 200), w);
    }

    // ---------------- 目录 / 设置 ----------------

    private void showChapters() {
        final int n = chapterCount();
        if (n == 0) {
            Toast.makeText(this, "还没有解析出章节", Toast.LENGTH_SHORT).show();
            return;
        }
        String[] items = new String[n];
        for (int i = 0; i < n; i++) items[i] = chapterTitle(i);
        new AlertDialog.Builder(this)
                .setTitle("目录（共 " + n + " 章）")
                .setItems(items, new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int which) {
                        savePos();
                        showChapter(which, 0f);
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void showSettings() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        int p = Ui.dp(this, 20);
        panel.setPadding(p, p, p, p);

        LinearLayout sizeRow = new LinearLayout(this);
        sizeRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView label = new TextView(this);
        label.setText("字号");
        label.setTextSize(14f);
        sizeRow.addView(label, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        final TextView value = new TextView(this);
        value.setText(String.valueOf(fontSize));
        value.setTextSize(14f);
        value.setGravity(Gravity.CENTER);
        value.setPadding(Ui.dp(this, 14), 0, Ui.dp(this, 14), 0);
        sizeRow.addView(value);

        TextView minus = barButton("A－");
        minus.setTextSize(15f);
        TextView plus = barButton("A＋");
        plus.setTextSize(15f);
        minus.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                fontSize = Math.max(13, fontSize - 1);
                value.setText(String.valueOf(fontSize));
                content.setTextSize(fontSize);
                Store.setFontSize(ReaderActivity.this, fontSize);
                scrollTo(currentRatio());
            }
        });
        plus.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                fontSize = Math.min(30, fontSize + 1);
                value.setText(String.valueOf(fontSize));
                content.setTextSize(fontSize);
                Store.setFontSize(ReaderActivity.this, fontSize);
                scrollTo(currentRatio());
            }
        });
        sizeRow.addView(minus);
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        plp.setMargins(Ui.dp(this, 6), 0, 0, 0);
        sizeRow.addView(plus, plp);
        panel.addView(sizeRow);

        LinearLayout themeRow = new LinearLayout(this);
        themeRow.setGravity(Gravity.CENTER_VERTICAL);
        themeRow.setPadding(0, Ui.dp(this, 18), 0, 0);
        TextView tl = new TextView(this);
        tl.setText("背景");
        tl.setTextSize(14f);
        themeRow.addView(tl, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        String[] names = {"深色", "米黄", "浅色"};
        int[] colors = {0xFF11141A, 0xFFF3E9D6, 0xFFFFFFFF};
        for (int i = 0; i < names.length; i++) {
            final int idx = i;
            TextView chip = new TextView(this);
            chip.setText(names[i]);
            chip.setTextSize(12.5f);
            chip.setGravity(Gravity.CENTER);
            chip.setPadding(Ui.dp(this, 14), Ui.dp(this, 8), Ui.dp(this, 14), Ui.dp(this, 8));
            chip.setBackground(Ui.roundStroke(this, colors[i], 16f,
                    i == theme ? 0xFF4C9AFF : 0x66888888, i == theme ? 2f : 1f));
            chip.setTextColor(i == 0 ? 0xFFE4E7EC : 0xFF3B3028);
            chip.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    theme = idx;
                    Store.setTheme(ReaderActivity.this, theme);
                    applyTheme();
                }
            });
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            clp.setMargins(Ui.dp(this, 8), 0, 0, 0);
            themeRow.addView(chip, clp);
        }
        panel.addView(themeRow);

        new AlertDialog.Builder(this)
                .setTitle("阅读设置")
                .setView(panel)
                .setPositiveButton("好", null)
                .show();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        try {
            if (epubZip != null) epubZip.close();
        } catch (Exception ignored) {
        }
        epubZip = null;
    }
}
