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
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 看图：点开一张图，左右滑翻到同目录的下一张/上一张，直到没有为止。
 * 支持双指缩放、双击缩放、放大后拖动；GIF 在 Android 9+ 上会动。
 */
public class ImageActivity extends Activity implements ZoomView.Listener {

    private FrameLayout root;
    private ZoomView zoom;
    private LinearLayout topBar;
    private TextView nameView, posView;

    private String treeUri = "";
    private String relPath = "";
    private String curName = "";

    private final List<String[]> images = new ArrayList<String[]>(); // {name, uri}
    private int index = 0;
    private boolean barsVisible = true;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService pool = Executors.newSingleThreadExecutor();
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

        new Thread(new Runnable() {
            @Override
            public void run() {
                if (!treeUri.isEmpty()) {
                    List<String[]> all = Scan.siblings(ImageActivity.this, Uri.parse(treeUri), relPath);
                    List<String[]> picked = new ArrayList<String[]>();
                    for (String[] f : all) {
                        if (Scan.isImage(f[0])) picked.add(f);
                    }
                    Collections.sort(picked, new java.util.Comparator<String[]>() {
                        @Override
                        public int compare(String[] a, String[] b) {
                            return Ui.NATURAL.compare(a[0], b[0]);
                        }
                    });
                    if (picked.size() > 1) {
                        final List<String[]> fp = picked;
                        ui.post(new Runnable() {
                            @Override
                            public void run() {
                                images.clear();
                                images.addAll(fp);
                                index = indexOf(uriStr);
                                show(index, 0);
                            }
                        });
                        return;
                    }
                }
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        index = indexOf(uriStr);
                        show(index, 0);
                    }
                });
            }
        }).start();
    }

    private int indexOf(String uriStr) {
        for (int i = 0; i < images.size(); i++) {
            if (images.get(i)[1].equals(uriStr)) return i;
        }
        return 0;
    }

    private void buildUi() {
        root = new FrameLayout(this);
        root.setBackgroundColor(0xFF000000);

        zoom = new ZoomView(this);
        zoom.setListener(this);
        root.addView(zoom, new FrameLayout.LayoutParams(
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
        topBar.addView(posView);

        FrameLayout.LayoutParams tlp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP);
        root.addView(topBar, tlp);

        setContentView(root);
    }

    private void updateBar() {
        if (images.isEmpty()) return;
        String name = images.get(index)[0];
        nameView.setText(name);
        posView.setText(String.format(Locale.US, "%d / %d", index + 1, images.size()));
        setTitle(name);
    }

    private void show(final int idx, int direction) {
        if (images.isEmpty()) return;
        index = Math.max(0, Math.min(idx, images.size() - 1));
        updateBar();

        final String key = images.get(index)[1];
        Drawable cached = cache.get(key);
        if (cached != null) {
            zoom.setDrawable(cached);
            prefetch();
            return;
        }
        posView.setText("载入中…");
        zoom.setDrawable(null);

        final int want = index;
        pool.execute(new Runnable() {
            @Override
            public void run() {
                final Drawable d = decode(Uri.parse(key));
                if (d != null) cache.put(key, d);
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        if (index != want) return;
                        zoom.setDrawable(d);
                        updateBar();
                        if (d == null) {
                            Toast.makeText(ImageActivity.this, "这张图读不出来", Toast.LENGTH_SHORT).show();
                        }
                        prefetch();
                    }
                });
            }
        });
    }

    /** 预解码前后各一张，滑过去就是秒开 */
    private void prefetch() {
        final int[] targets = {index + 1, index - 1};
        for (final int t : targets) {
            if (t < 0 || t >= images.size()) continue;
            final String key = images.get(t)[1];
            if (cache.get(key) != null) continue;
            pool.execute(new Runnable() {
                @Override
                public void run() {
                    Drawable d = decode(Uri.parse(key));
                    if (d != null) cache.put(key, d);
                }
            });
        }
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

    // ---------------- 交互 ----------------

    @Override
    public void onSwipe(int direction) {
        int target = index + direction;
        if (target < 0) {
            Toast.makeText(this, "已经是第一张了", Toast.LENGTH_SHORT).show();
            return;
        }
        if (target >= images.size()) {
            Toast.makeText(this, "已经是最后一张了", Toast.LENGTH_SHORT).show();
            return;
        }
        show(target, direction);
    }

    @Override
    public void onSingleTap() {
        barsVisible = !barsVisible;
        topBar.setVisibility(barsVisible ? View.VISIBLE : View.GONE);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        pool.shutdownNow();
        cache.evictAll();
    }
}
