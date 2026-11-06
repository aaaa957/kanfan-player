package com.kanfan.player;

import android.app.Activity;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 看图：两种模式由用户选。
 * - 左右切换（默认）：跟手拖动 + 松手吸附，翻到同目录的下一张/上一张
 * - 连续下滑：整列铺开，一直往下滑就会不断出现新图，直到最后一张
 */
public class ImageActivity extends Activity
        implements PagerLayout.Source, PagerLayout.Listener, ImageColumn.Host {

    private FrameLayout root;
    private PagerLayout pager;
    private ImageColumn column;
    private LinearLayout topBar;
    private TextView nameView, posView, modeChip;

    private String treeUri = "";
    private String relPath = "";
    private String curName = "";

    private final List<String[]> images = new ArrayList<String[]>(); // {name, uri}
    private int[][] dims = new int[0][];

    private int mode = 0;             // 0 左右切换 / 1 连续下滑
    private boolean barsVisible = true;
    private boolean probed = false;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService pool = Executors.newSingleThreadExecutor();
    private final Set<String> loading = new HashSet<String>();

    private final LruCache<String, Drawable> cache = new LruCache<String, Drawable>(64 * 1024) {
        @Override
        protected int sizeOf(String k, Drawable d) {
            if (d instanceof BitmapDrawable && ((BitmapDrawable) d).getBitmap() != null) {
                return Math.max(1, ((BitmapDrawable) d).getBitmap().getByteCount() / 1024);
            }
            return 256; // 动图按固定权重
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        treeUri = getIntent().getStringExtra("tree");
        relPath = getIntent().getStringExtra("rel");
        curName = getIntent().getStringExtra("name");
        String uriStr = getIntent().getStringExtra("uri");
        if (treeUri == null) treeUri = "";
        if (relPath == null) relPath = "";
        if (curName == null) curName = "";

        buildUi();
        if (uriStr == null) {
            finish();
            return;
        }
        final Uri uri = Uri.parse(uriStr);
        images.add(new String[]{curName, uriStr});   // 先保证当前这张能显示
        pager.setSource(this);
        pager.reset(0);

        final String want = uriStr;
        new Thread(new Runnable() {
            @Override
            public void run() {
                List<String[]> picked = new ArrayList<String[]>();
                if (!treeUri.isEmpty()) {
                    List<String[]> all = Scan.siblings(ImageActivity.this, Uri.parse(treeUri), relPath);
                    for (String[] f : all) {
                        if (Scan.isImage(f[0])) picked.add(f);
                    }
                    Collections.sort(picked, new java.util.Comparator<String[]>() {
                        @Override
                        public int compare(String[] a, String[] b) {
                            return Ui.NATURAL.compare(a[0], b[0]);
                        }
                    });
                }
                if (picked.size() < 2) picked = null;
                final List<String[]> fp = picked;
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        if (fp != null) {
                            images.clear();
                            images.addAll(fp);
                            dims = new int[images.size()][];
                            probed = false;
                        }
                        pager.reset(indexOfUri(want));
                        applyMode(Store.getImgMode(ImageActivity.this));
                        updateBar();
                    }
                });
            }
        }).start();
    }

    private void buildUi() {
        root = new FrameLayout(this);
        root.setBackgroundColor(0xFF000000);

        pager = new PagerLayout(this);
        pager.setListener(this);
        root.addView(pager, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        column = new ImageColumn(this);
        column.setHost(this);
        column.setTapHook(new Runnable() {
            @Override
            public void run() {
                toggleBars();
            }
        });
        column.setScrollHook(new Runnable() {
            @Override
            public void run() {
                if (mode == 1) updateBar();
            }
        });
        column.setVisibility(View.GONE);
        root.addView(column, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setPadding(Ui.dp(this, 8), Ui.dp(this, 10), Ui.dp(this, 14), Ui.dp(this, 14));
        topBar.setBackgroundColor(0x99000000);

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

        nameView = new TextView(this);
        nameView.setTextColor(Color.WHITE);
        nameView.setTextSize(14f);
        nameView.setTypeface(Typeface.DEFAULT_BOLD);
        nameView.setSingleLine(true);
        nameView.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        nameView.setPadding(Ui.dp(this, 6), 0, Ui.dp(this, 10), 0);
        topBar.addView(nameView, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        posView = new TextView(this);
        posView.setTextColor(0xCCFFFFFF);
        posView.setTextSize(12f);
        posView.setPadding(0, 0, Ui.dp(this, 10), 0);
        topBar.addView(posView);

        modeChip = new TextView(this);
        modeChip.setTextSize(12f);
        modeChip.setTextColor(0xFF7EF0C8);
        modeChip.setPadding(Ui.dp(this, 12), Ui.dp(this, 7), Ui.dp(this, 12), Ui.dp(this, 7));
        modeChip.setBackground(Ui.round(this, Ui.alpha(0xFF7EF0C8, 0.16f), 18f));
        modeChip.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                int next = mode == 0 ? 1 : 0;
                Store.setImgMode(ImageActivity.this, next);
                applyMode(next);
                Toast.makeText(ImageActivity.this,
                        next == 1 ? "已切换为上下滑动" : "已切换为左右切换",
                        Toast.LENGTH_SHORT).show();
            }
        });
        topBar.addView(modeChip);

        FrameLayout.LayoutParams tlp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP);
        root.addView(topBar, tlp);

        setContentView(root);
    }

    // ---------------- 模式 ----------------

    private void applyMode(int m) {
        // 切换前先把「现在停在哪一张」记下来，两种模式的坐标体系不同
        int from;
        if (m == 1) from = pager.index();
        else from = (mode == 1) ? column.mostVisibleIndex() : pager.index();

        mode = m;
        boolean vert = m == 1;

        pager.setVisibility(vert ? View.GONE : View.VISIBLE);
        column.setVisibility(vert ? View.VISIBLE : View.GONE);

        if (vert) {
            pager.release();
            column.setHost(this);
            column.rebuild();
            column.scrollToIndex(from);
            if (!probed) {
                probed = true;
                probeAllAsync();
            }
        } else {
            column.releaseAll();
            pager.reset(from);
        }
        modeChip.setText(m == 0 ? "左右切换" : "上下滑动");
        updateBar();
    }

    private int indexOfUri(String uriStr) {
        for (int i = 0; i < images.size(); i++) {
            if (images.get(i)[1].equals(uriStr)) return i;
        }
        return 0;
    }

    private void toggleBars() {
        barsVisible = !barsVisible;
        topBar.setVisibility(barsVisible ? View.VISIBLE : View.GONE);
    }

    // ---------------- 数据 ----------------

    private void updateBar() {
        if (images.isEmpty()) return;
        int i = mode == 1 ? column.mostVisibleIndex() : pager.index();
        i = Math.max(0, Math.min(i, images.size() - 1));
        String name = images.get(i)[0];
        nameView.setText(name);
        setTitle(name);
        boolean ready = cache.get(images.get(i)[1]) != null;
        if (ready) {
            posView.setText(String.format(Locale.US, "%d / %d", i + 1, images.size()));
        } else {
            posView.setText("载入中…");
        }
    }

    @Override
    public int count() {
        return images.size();
    }

    @Override
    public Drawable get(int index) {
        if (index < 0 || index >= images.size()) return null;
        return cache.get(images.get(index)[1]);
    }

    @Override
    public int[] size(int index) {
        if (index < 0 || index >= dims.length) return null;
        return dims[index];
    }

    @Override
    public void request(final int index) {
        if (index < 0 || index >= images.size()) return;
        final String key = images.get(index)[1];
        Drawable hit = cache.get(key);
        if (hit != null) {
            pager.update(index, hit);
            column.update(index, hit);
            return;
        }
        if (!loading.add(key)) return;

        pool.execute(new Runnable() {
            @Override
            public void run() {
                final Drawable d = decode(Uri.parse(key));
                if (d != null) cache.put(key, d);
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        loading.remove(key);
                        if (d != null) {
                            pager.update(index, d);
                            column.update(index, d);
                            if (index == currentIndex()) updateBar();
                        } else if (index == currentIndex()) {
                            Toast.makeText(ImageActivity.this, "这张图读不出来",
                                    Toast.LENGTH_SHORT).show();
                        }
                    }
                });
            }
        });
    }

    private int currentIndex() {
        return mode == 1 ? column.mostVisibleIndex() : pager.index();
    }

    /** 预先量一遍原始宽高，上下模式排版才知道每张该多高 */
    private void probeAllAsync() {
        pool.execute(new Runnable() {
            @Override
            public void run() {
                final int n = images.size();
                final int[][] out = new int[n][];
                for (int i = 0; i < n; i++) {
                    if (i < dims.length && dims[i] != null) {
                        out[i] = dims[i];
                        continue;
                    }
                    if (i >= images.size()) break;
                    out[i] = probe(Uri.parse(images.get(i)[1]));
                }
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        dims = out;
                        if (mode == 1) {
                            int keep = column.mostVisibleIndex();
                            column.rebuild();
                            column.scrollToIndex(keep);
                        }
                    }
                });
            }
        });
    }

    private int[] probe(Uri uri) {
        InputStream in = null;
        try {
            in = getContentResolver().openInputStream(uri);
            if (in == null) return null;
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeStream(in, null, o);
            if (o.outWidth > 0 && o.outHeight > 0) return new int[]{o.outWidth, o.outHeight};
        } catch (Throwable ignored) {
        } finally {
            try {
                if (in != null) in.close();
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    private Drawable decode(Uri uri) {
        try {
            int maxW = getResources().getDisplayMetrics().widthPixels;
            int maxH = getResources().getDisplayMetrics().heightPixels;
            String low = uri.toString().toLowerCase(Locale.US);
            boolean gif = low.contains(".gif");

            if (gif && Build.VERSION.SDK_INT >= 28) {
                android.graphics.ImageDecoder.Source src =
                        android.graphics.ImageDecoder.createSource(getContentResolver(), uri);
                Drawable d = android.graphics.ImageDecoder.decodeDrawable(src,
                        new android.graphics.ImageDecoder.OnHeaderDecodedListener() {
                            @Override
                            public void onHeaderDecoded(android.graphics.ImageDecoder decoder,
                                                        android.graphics.ImageDecoder.ImageInfo info,
                                                        android.graphics.ImageDecoder.Source source) {
                                int w = info.getSize().getWidth();
                                int h = info.getSize().getHeight();
                                int sample = 1;
                                while (w / (sample * 2) >= maxW || h / (sample * 2) >= maxH) sample *= 2;
                                if (sample > 1) decoder.setTargetSampleSize(sample);
                            }
                        });
                return d;
            }

            InputStream in = getContentResolver().openInputStream(uri);
            if (in == null) return null;
            BitmapFactory.Options probe = new BitmapFactory.Options();
            probe.inJustDecodeBounds = true;
            BitmapFactory.decodeStream(in, null, probe);
            in.close();
            if (probe.outWidth <= 0 || probe.outHeight <= 0) return null;

            int sample = 1;
            while (probe.outWidth / (sample * 2) >= maxW || probe.outHeight / (sample * 2) >= maxH) {
                sample *= 2;
            }
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inSampleSize = sample;
            in = getContentResolver().openInputStream(uri);
            Bitmap bm = BitmapFactory.decodeStream(in, null, o);
            if (in != null) in.close();
            if (bm == null) return null;
            return new BitmapDrawable(getResources(), bm);
        } catch (Throwable t) {
            return null;
        }
    }

    // ---------------- 回调 ----------------

    @Override
    public void onPageChanged(int index) {
        updateBar();
    }

    @Override
    public void onEdgeTry(int direction) {
        Toast.makeText(this, direction > 0 ? "已经是最后一张了" : "已经是第一张了",
                Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onSingleTap() {
        toggleBars();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        pool.shutdownNow();
        cache.evictAll();
    }
}
