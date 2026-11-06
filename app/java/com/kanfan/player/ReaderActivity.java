package com.kanfan.player;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Html;
import android.text.Layout;
import android.text.SpannableString;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
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
 *
 * 阅读方式是**连续滚动**：滚到底部自动把下一章接上来，直到全书加载完。
 * 为了不让内存爆掉（EPUB 里的插图是 1384×2048 这一级），同时只保留最近几章：
 * TXT 8 章、EPUB 3 章，超出后从顶部裁掉并把滚动位置等量补偿，视觉上不跳。
 */
public class ReaderActivity extends Activity {

    /** 同时保留的章节数：文本便宜可以多留，图文书必须少留 */
    private static final int MAX_WIN_TXT = 8, MAX_WIN_EPUB = 3;
    /** 与 TextView 的 setLineSpacing 保持一致，分页测量要用 */
    private static final float LINE_SPACING = 1.65f;

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

    private int fontSize = 18;
    private int theme = 0;
    private boolean barsVisible = true;

    // 连续加载窗口
    private final List<Flow.Win> wins = new ArrayList<Flow.Win>();
    private final SpannableStringBuilder body = new SpannableStringBuilder();
    private int loadedFrom = 0, loadedTo = -1;
    private int maxWin = MAX_WIN_TXT;
    private boolean loadingNext;
    private int renderToken = 0;

    // 分页模式（与滚动模式互斥，由用户在设置里选）
    private boolean pageMode;
    private int pageChapter = 0;
    private CharSequence pageSource = "";
    private final ArrayList<Integer> pageStarts = new ArrayList<Integer>();
    private int pageStartsChapter = -1;
    private int pageIndex = 0;
    private float pendingRatio = 0f;

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
        pageMode = Store.getReadMode(this) == 0;

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
        content.setLineSpacing(0, LINE_SPACING);
        content.setPadding(Ui.dp(this, 22), Ui.dp(this, 16), Ui.dp(this, 22), Ui.dp(this, 60));
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

        // 点击显隐上下栏；左右滑动切章；纵向滑动交给 ScrollView
        scroll.setOnTouchListener(new View.OnTouchListener() {
            float downX, downY;
            long downAt;
            boolean moved;

            @Override
            public boolean onTouch(View v, MotionEvent e) {
                if (e.getAction() == MotionEvent.ACTION_DOWN) {
                    downX = e.getX();
                    downY = e.getY();
                    downAt = System.currentTimeMillis();
                    moved = false;
                } else if (e.getAction() == MotionEvent.ACTION_MOVE) {
                    if (Math.abs(e.getX() - downX) > Ui.dp(ReaderActivity.this, 10)
                            || Math.abs(e.getY() - downY) > Ui.dp(ReaderActivity.this, 10)) {
                        moved = true;
                    }
                } else if (e.getAction() == MotionEvent.ACTION_UP) {
                    float dx = e.getX() - downX, dy = e.getY() - downY;
                    boolean horizontal = Math.abs(dx) > Ui.dp(ReaderActivity.this, 56)
                            && Math.abs(dx) > Math.abs(dy) * 1.5f;
                    if (horizontal) {
                        // 只有分页模式响应左右滑；滚动模式专用于连续下拉
                        if (pageMode) {
                            if (dx < 0) nextPage();
                            else prevPage();
                        }
                    } else if (!moved && System.currentTimeMillis() - downAt < 300) {
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
                maybeAppend();
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
            if (c instanceof TextView && c != footTitle && c != footProgress) {
                tintButton((TextView) c, fg);
            }
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
        maxWin = isEpub ? MAX_WIN_EPUB : MAX_WIN_TXT;
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
        openAt(idx, ratio);
        if (pos != null && (idx > 0 || ratio > 0.02f)) {
            Toast.makeText(this, "已跳到上次读到的地方", Toast.LENGTH_SHORT).show();
        }
    }

    // ---------------- 连续滚动 ----------------

    /** 从某一章重新开始，只加载这一章 */
    private void openAt(final int chapter, final float ratio) {
        if (chapter < 0 || chapter >= chapterCount()) return;
        if (pageMode) {
            openPage(chapter, ratio);
            return;
        }
        final int token = ++renderToken;
        wins.clear();
        body.clear();
        loadedFrom = loadedTo = chapter;
        loadingNext = true;

        if (!isEpub) {
            appendChapter(chapter, txtBody(chapter));
            loadingNext = false;
            scrollToRatio(ratio);
            return;
        }

        content.setText("正在排版…");
        final Epub.Item item = book.spine.get(chapter);
        new Thread(new Runnable() {
            @Override
            public void run() {
                final Spanned sp = renderHtml(Epub.text(epubZip, item.href), Epub.dirOf(item.href));
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        if (token != renderToken || isFinishing()) return;
                        appendChapter(chapter, sp);
                        loadingNext = false;
                        scrollToRatio(ratio);
                    }
                });
            }
        }).start();
    }

    private CharSequence txtBody(int index) {
        Split.Chapter c = chapters.get(index);
        String t = txt.substring(c.start, c.end).trim();
        String title = c.title == null ? "" : c.title.trim();
        if (!title.isEmpty() && t.startsWith(title)) {
            t = t.substring(title.length()).trim();
        }
        return t;
    }

    /** 把一章接在已有内容后面 */
    private void appendChapter(int index, CharSequence text) {
        if (!wins.isEmpty()) body.append("\n\n\n");
        int start = body.length();

        String title = chapterTitle(index);
        if (title != null && !title.isEmpty()) {
            SpannableString head = new SpannableString(title + "\n");
            head.setSpan(new StyleSpan(Typeface.BOLD), 0, head.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            head.setSpan(new RelativeSizeSpan(1.18f), 0, head.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            body.append(head);
        }

        body.append(text);

        Flow.Win w = new Flow.Win(index, start);
        w.end = body.length();
        wins.add(w);
        loadedFrom = wins.get(0).chapter;
        loadedTo = Math.max(loadedTo, index);

        content.setText(body);
        trimIfNeeded();
    }

    /** 距离底部不足一屏时，把下一章接上来 */
    private void maybeAppend() {
        if (pageMode || loadingNext || chapterCount() == 0) return;
        if (loadedTo + 1 >= chapterCount()) return;
        int vh = scroll.getHeight();
        if (vh <= 0) return;
        int remaining = content.getHeight() - scroll.getScrollY() - vh;
        if (remaining < vh) appendNext();
    }

    private void appendNext() {
        final int next = loadedTo + 1;
        if (next >= chapterCount()) return;
        loadingNext = true;

        if (!isEpub) {
            appendChapter(next, txtBody(next));
            loadingNext = false;
            return;
        }
        final Epub.Item item = book.spine.get(next);
        final int gen = renderToken;
        new Thread(new Runnable() {
            @Override
            public void run() {
                final Spanned sp = renderHtml(Epub.text(epubZip, item.href), Epub.dirOf(item.href));
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        if (isFinishing() || gen != renderToken) return;
                        appendChapter(next, sp);
                        loadingNext = false;
                    }
                });
            }
        }).start();
    }

    /**
     * 超出窗口就裁掉最上面那一章。
     * 滚动位置按裁掉的高度等量回退，读者不会看到跳动。
     */
    private void trimIfNeeded() {
        while (wins.size() > maxWin && wins.size() > 1) {
            int cut = wins.get(1).start;
            int removedPx = 0;
            Layout lay = content.getLayout();
            if (lay != null && content.length() > 0) {
                int off = Math.min(cut, content.length());
                removedPx = lay.getLineTop(lay.getLineForOffset(off));
            }
            body.delete(0, Math.min(cut, body.length()));
            Flow.trimTo(wins, 1);
            int y = Math.max(0, scroll.getScrollY() - removedPx);
            content.setText(body);
            scroll.scrollTo(0, y);
            loadedFrom = wins.get(0).chapter;
        }
    }

    // ---------------- 分页模式 ----------------

    private void openPage(final int chapter, final float ratio) {
        if (chapter < 0 || chapter >= chapterCount()) return;
        pageChapter = chapter;
        pendingRatio = ratio;
        final int token = ++renderToken;

        if (!isEpub) {
            setPageSource(chapter, txtBody(chapter));
            return;
        }
        content.setText("正在排版…");
        final Epub.Item item = book.spine.get(chapter);
        new Thread(new Runnable() {
            @Override
            public void run() {
                final Spanned sp = renderHtml(Epub.text(epubZip, item.href), Epub.dirOf(item.href));
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        if (token != renderToken || isFinishing()) return;
                        setPageSource(chapter, sp);
                    }
                });
            }
        }).start();
    }

    private void setPageSource(int chapter, CharSequence text) {
        pageChapter = chapter;
        pageSource = buildSource(chapter, text);
        pageStartsChapter = -1;
        pageStarts.clear();
        scroll.post(new Runnable() {
            @Override
            public void run() {
                ensurePages();
                pageIndex = pageIndexForRatio(pendingRatio);
                pendingRatio = 0f;
                showPage();
            }
        });
    }

    /** 章节标题 + 正文，分页以它为源 */
    private CharSequence buildSource(int chapter, CharSequence text) {
        SpannableStringBuilder sb = new SpannableStringBuilder();
        String title = chapterTitle(chapter);
        if (title != null && !title.isEmpty()) {
            SpannableString head = new SpannableString(title + "\n\n");
            head.setSpan(new StyleSpan(Typeface.BOLD), 0, head.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            head.setSpan(new RelativeSizeSpan(1.18f), 0, head.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            sb.append(head);
        }
        sb.append(text);
        return sb;
    }

    private void ensurePages() {
        if (pageStartsChapter == pageChapter && !pageStarts.isEmpty()) return;
        pageStarts.clear();
        pageStartsChapter = pageChapter;
        int w = pageWidth(), h = pageHeight();
        if (w <= 0 || h <= 0 || pageSource == null || pageSource.length() == 0) {
            pageStarts.add(0);
            return;
        }
        int s = 0, guard = 0;
        while (s < pageSource.length() && guard++ < 600) {
            pageStarts.add(s);
            int n = Pager.pageChars(content, pageSource, s, w, h, LINE_SPACING);
            if (n <= 0) n = 1;
            s += n;
        }
        if (pageStarts.isEmpty()) pageStarts.add(0);
    }

    private int pageWidth() {
        return content.getWidth() - content.getPaddingLeft() - content.getPaddingRight();
    }

    private int pageHeight() {
        return scroll.getHeight() - content.getPaddingTop() - content.getPaddingBottom();
    }

    private void showPage() {
        if (pageSource == null || pageStarts.isEmpty()) return;
        if (pageIndex < 0) pageIndex = 0;
        if (pageIndex >= pageStarts.size()) pageIndex = pageStarts.size() - 1;
        int s = pageStarts.get(pageIndex);
        int e = pageIndex + 1 < pageStarts.size() ? pageStarts.get(pageIndex + 1) : pageSource.length();
        if (e <= s) e = Math.min(pageSource.length(), s + 1);
        content.setText(pageSource.subSequence(s, e));
        scroll.scrollTo(0, 0);
        updateFoot();
    }

    private int pageIndexForRatio(float ratio) {
        if (pageStarts.isEmpty() || pageSource == null) return 0;
        int target = (int) (pageSource.length() * Math.max(0f, Math.min(1f, ratio)));
        int idx = 0;
        for (int i = 0; i < pageStarts.size(); i++) {
            if (pageStarts.get(i) <= target) idx = i;
            else break;
        }
        return idx;
    }

    private float currentPageRatio() {
        if (pageSource == null || pageSource.length() == 0 || pageStarts.isEmpty()) return 0f;
        int idx = Math.min(pageIndex, pageStarts.size() - 1);
        return pageStarts.get(idx) / (float) pageSource.length();
    }

    private void nextPage() {
        ensurePages();
        if (pageIndex + 1 < pageStarts.size()) {
            pageIndex++;
            showPage();
            return;
        }
        if (pageChapter + 1 < chapterCount()) {
            openPage(pageChapter + 1, 0f);
        } else {
            Toast.makeText(this, "已经是最后一页", Toast.LENGTH_SHORT).show();
        }
    }

    private void prevPage() {
        if (pageIndex > 0) {
            pageIndex--;
            showPage();
            return;
        }
        if (pageChapter > 0) {
            openPage(pageChapter - 1, 1f);
        } else {
            Toast.makeText(this, "已经是第一页", Toast.LENGTH_SHORT).show();
        }
    }

    // ---------------- 位置 ----------------

    private int currentOffset() {
        Layout lay = content.getLayout();
        if (lay == null) return 0;
        int y = Math.max(0, scroll.getScrollY() - content.getPaddingTop() + Ui.dp(this, 4));
        int line = lay.getLineForVertical(y);
        return lay.getLineStart(Math.max(0, Math.min(line, lay.getLineCount() - 1)));
    }

    private int currentChapter() {
        int ch = Flow.chapterAt(wins, currentOffset());
        return ch < 0 ? 0 : ch;
    }

    private float currentRatio() {
        return Flow.ratioAt(wins, currentOffset());
    }

    private void scrollToRatio(final float ratio) {
        scroll.post(new Runnable() {
            @Override
            public void run() {
                int max = Math.max(0, content.getHeight() - scroll.getHeight());
                scroll.scrollTo(0, (int) (max * Math.max(0f, Math.min(1f, ratio))));
                updateFoot();
            }
        });
    }

    private void jump(int delta) {
        int base = pageMode ? pageChapter : currentChapter();
        int target = base + delta;
        if (target < 0) {
            Toast.makeText(this, "已经是第一章", Toast.LENGTH_SHORT).show();
            return;
        }
        if (target >= chapterCount()) {
            Toast.makeText(this, "已经是最后一章", Toast.LENGTH_SHORT).show();
            return;
        }
        savePos();
        openAt(target, 0f);
        showHintToast(chapterTitle(target));
    }

    private void showHintToast(String title) {
        if (title == null || title.isEmpty()) return;
        Toast.makeText(this, title, Toast.LENGTH_SHORT).show();
    }

    private void updateFoot() {
        if (footTitle == null) return;
        int n = Math.max(1, chapterCount());
        if (pageMode) {
            footTitle.setText(chapterTitle(pageChapter));
            int pages = Math.max(1, pageStarts.size());
            float all = (pageChapter + (pageIndex + 1) / (float) pages) / n;
            footProgress.setText(String.format(Locale.US, "%d / %d 章 · 第 %d / %d 页 · %d%%",
                    pageChapter + 1, n, pageIndex + 1, pages, Math.round(all * 100)));
            return;
        }
        if (wins.isEmpty()) return;
        int ch = currentChapter();
        footTitle.setText(chapterTitle(ch));
        float all = (ch + currentRatio()) / n;
        footProgress.setText(String.format(Locale.US, "%d / %d 章 · %d%%",
                ch + 1, n, Math.round(all * 100)));
    }

    private void savePos() {
        if (chapterCount() == 0) return;
        if (pageMode) {
            Store.saveBookPos(this, bookKey, pageChapter, currentPageRatio());
            return;
        }
        if (wins.isEmpty()) return;
        Store.saveBookPos(this, bookKey, currentChapter(), currentRatio());
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
        String bodyHtml = Epub.bodyOf(html);
        try {
            if (Build.VERSION.SDK_INT >= 24) {
                return Html.fromHtml(bodyHtml, Html.FROM_HTML_MODE_LEGACY, getter, null);
            }
            return Html.fromHtml(bodyHtml, getter, null);
        } catch (Throwable t) {
            return new SpannableString(bodyHtml.replaceAll("<[^>]*>", ""));
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
        for (int i = 0; i < n; i++) items[i] = (i == currentChapter() ? "▶ " : "   ") + chapterTitle(i);
        new AlertDialog.Builder(this)
                .setTitle("目录（共 " + n + " 章）")
                .setItems(items, new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int which) {
                        savePos();
                        openAt(which, 0f);
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

        // 阅读方式：分页 / 滚动，二选一
        LinearLayout modeRow = new LinearLayout(this);
        modeRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView ml = new TextView(this);
        ml.setText("阅读方式");
        ml.setTextSize(14f);
        modeRow.addView(ml, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        String[] modes = {"分页（左滑右滑翻页）", "滚动（一直向下）"};
        for (int i = 0; i < modes.length; i++) {
            final boolean toPage = (i == 0);
            boolean on = (pageMode == toPage);
            TextView chip = new TextView(this);
            chip.setText(modes[i]);
            chip.setTextSize(12f);
            chip.setGravity(Gravity.CENTER);
            chip.setPadding(Ui.dp(this, 12), Ui.dp(this, 8), Ui.dp(this, 12), Ui.dp(this, 8));
            chip.setBackground(Ui.roundStroke(this, on ? 0xFF4C9AFF : 0x22888888, 16f,
                    on ? 0xFF4C9AFF : 0x66888888, on ? 2f : 1f));
            chip.setTextColor(on ? 0xFF10131A : 0xCC888888);
            chip.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    switchMode(toPage);
                }
            });
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            clp.setMargins(Ui.dp(this, 6), 0, 0, 0);
            modeRow.addView(chip, clp);
        }
        panel.addView(modeRow);

        LinearLayout sizeRow = new LinearLayout(this);
        sizeRow.setGravity(Gravity.CENTER_VERTICAL);
        sizeRow.setPadding(0, Ui.dp(this, 18), 0, 0);
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
                applyFontSize(Math.max(13, fontSize - 1), value);
            }
        });
        plus.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                applyFontSize(Math.min(30, fontSize + 1), value);
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

    /** 改字号后保持大致相同的阅读位置 */
    private void applyFontSize(int size, TextView value) {
        if (pageMode) {
            float r = currentPageRatio();
            fontSize = size;
            if (value != null) value.setText(String.valueOf(size));
            content.setTextSize(size);
            Store.setFontSize(this, size);
            pageStartsChapter = -1;
            pageStarts.clear();
            scroll.post(new Runnable() {
                @Override
                public void run() {
                    ensurePages();
                    pageIndex = pageIndexForRatio(r);
                    showPage();
                }
            });
            return;
        }
        int h = content.getHeight();
        float keep = h > 0 ? (scroll.getScrollY() + scroll.getHeight() * 0.3f) / h : 0f;
        fontSize = size;
        if (value != null) value.setText(String.valueOf(size));
        content.setTextSize(size);
        Store.setFontSize(this, size);
        scroll.post(new Runnable() {
            @Override
            public void run() {
                int max = Math.max(0, content.getHeight() - scroll.getHeight());
                scroll.scrollTo(0, (int) (max * Math.max(0f, Math.min(1f, keep))));
                updateFoot();
            }
        });
    }

    /** 切换阅读方式，并保持当前位置 */
    private void switchMode(boolean toPage) {
        if (toPage == pageMode) return;
        savePos();
        int ch = pageMode ? pageChapter : currentChapter();
        float r = pageMode ? currentPageRatio() : currentRatio();
        pageMode = toPage;
        Store.setReadMode(this, toPage ? 0 : 1);
        wins.clear();
        body.clear();
        pageStarts.clear();
        pageStartsChapter = -1;
        openAt(ch, r);
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
