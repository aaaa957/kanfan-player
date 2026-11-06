package com.kanfan.player;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.LruCache;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.widget.Scroller;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

/**
 * PDF 画布：页面纵向连续排列，自己管滚动 / 缩放 / 惯性。
 *
 * 位图按需渲染（单线程串行，PdfRenderer 不是线程安全的），渲染结果进 LruCache。
 * 排版坐标见 {@link PdfLayout}：内容坐标 × zoom - pan = 屏幕坐标。
 */
public class PdfView extends View {

    /** 页面位图从哪儿来；实现方负责串行化与异常 */
    public interface Renderer {
        Bitmap render(int index, int targetWidth) throws Exception;
    }

    public interface Listener {
        void onPageChanged(int page, int count);

        void onTap();
    }

    private static final int BG = 0xFF101014;
    private static final int PLACEHOLDER = 0xFF23232A;

    private int[] pageW = new int[0];
    private int[] pageH = new int[0];
    private Renderer renderer;
    private Listener listener;

    private PdfLayout layout;
    private float zoom = 1f;
    private float panX, panY;

    private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint bgPaint = new Paint();
    private final Paint phPaint = new Paint();
    private final RectF rect = new RectF();

    private final LruCache<Integer, Bitmap> cache;
    private final Set<Integer> queued = new HashSet<Integer>();
    private final ExecutorService pool;
    private final int gap;

    private final Scroller scroller;
    private final GestureDetector gestures;
    private final ScaleGestureDetector scale;

    private int lastPage = -1;
    private boolean released;

    public PdfView(Context c) {
        super(c);
        setBackgroundColor(BG);
        gap = Ui.dp(c, 8);
        bgPaint.setColor(BG);
        phPaint.setColor(PLACEHOLDER);

        int maxKb = (int) Math.min(64 * 1024, Runtime.getRuntime().maxMemory() / 1024 / 8);
        cache = new LruCache<Integer, Bitmap>(maxKb) {
            @Override
            protected int sizeOf(Integer k, Bitmap v) {
                return Math.max(1, v.getByteCount() / 1024);
            }

            @Override
            protected void entryRemoved(boolean evicted, Integer key, Bitmap old, Bitmap now) {
                if (old != null && !old.isRecycled()) old.recycle();
            }
        };

        pool = Executors.newSingleThreadExecutor(new ThreadFactory() {
            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "pdf-render");
                t.setPriority(Thread.NORM_PRIORITY - 1);
                return t;
            }
        });

        scroller = new Scroller(c);
        gestures = new GestureDetector(c, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDown(MotionEvent e) {
                scroller.forceFinished(true);
                return true;
            }

            @Override
            public boolean onScroll(MotionEvent e1, MotionEvent e2, float dx, float dy) {
                if (layout == null) return true;
                if (scale.isInProgress()) return true;   // 双指时交给缩放，别重复平移
                panX = PdfLayout.clampPan(panX + dx / zoom, getWidth(), layout.width, zoom);
                panY = PdfLayout.clampPan(panY + dy / zoom, getHeight(), layout.total, zoom);
                notifyPage();
                invalidate();
                return true;
            }

            @Override
            public boolean onFling(MotionEvent e1, MotionEvent e2, float vx, float vy) {
                if (layout == null) return false;
                int maxX = (int) Math.max(0, layout.width - getWidth() / zoom);
                int maxY = (int) Math.max(0, layout.total - getHeight() / zoom);
                scroller.fling((int) panX, (int) panY,
                        (int) (-vx / zoom), (int) (-vy / zoom),
                        0, maxX, 0, maxY);
                invalidate();
                return true;
            }

            @Override
            public void onLongPress(MotionEvent e) {
                // 长按留空：以后可以加「复制页码」
            }

            @Override
            public boolean onSingleTapConfirmed(MotionEvent e) {
                if (listener != null) listener.onTap();
                return true;
            }

            @Override
            public boolean onDoubleTap(MotionEvent e) {
                if (layout == null) return false;
                float next = zoom > 1.2f ? 1f : 2.5f;
                zoomAt(next, e.getX(), e.getY());
                return true;
            }
        });

        scale = new ScaleGestureDetector(c, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScale(ScaleGestureDetector d) {
                if (layout == null) return true;
                float next = Math.max(1f, Math.min(5f, zoom * d.getScaleFactor()));
                zoomAt(next, d.getFocusX(), d.getFocusY());
                return true;
            }

            @Override
            public void onScaleEnd(ScaleGestureDetector d) {
                // 放大后按新的渲染宽度重出一遍，避免糊
                refreshResolution();
            }
        });
    }

    // ---------------- 外部接口 ----------------

    public void setPages(int[] w, int[] h) {
        pageW = w == null ? new int[0] : w;
        pageH = h == null ? new int[0] : h;
        rebuild();
    }

    public void setRenderer(Renderer r) {
        renderer = r;
    }

    public void setListener(Listener l) {
        listener = l;
    }

    public int pageCount() {
        return layout == null ? pageW.length : layout.count;
    }

    public int currentPage() {
        if (layout == null) return 0;
        return layout.pageAt(panY + getHeight() / zoom / 2f);
    }

    /** 打开时跳到上次看到的那一页 */
    public void gotoPage(int page) {
        if (layout == null) {
            pendingPage = page;
            return;
        }
        if (page < 0) page = 0;
        if (page >= layout.count) page = Math.max(0, layout.count - 1);
        panY = PdfLayout.clampPan(layout.panForPage(page), getHeight(), layout.total, zoom);
        panX = 0;
        notifyPage();
        invalidate();
    }

    private int pendingPage = -1;

    public float zoom() {
        return zoom;
    }

    public void release() {
        released = true;
        pool.shutdownNow();
        cache.evictAll();
    }

    // ---------------- 布局 ----------------

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        rebuild();
    }

    private void rebuild() {
        if (getWidth() == 0) return;
        int keep = layout != null ? currentPage() : 0;
        float ratio = 0;
        if (layout != null && layout.heightOf(keep) > 0) {
            ratio = (panY - layout.topOf(keep)) / layout.heightOf(keep);
            if (ratio < 0) ratio = 0;
            if (ratio > 1) ratio = 1;
        }
        layout = new PdfLayout(pageW, pageH, getWidth(), gap);
        if (pendingPage >= 0) {
            panY = layout.panForPage(Math.min(pendingPage, Math.max(0, layout.count - 1)));
            panX = 0;
            pendingPage = -1;
        } else if (layout.count > 0) {
            keep = Math.min(keep, layout.count - 1);
            panY = layout.topOf(keep) + ratio * layout.heightOf(keep);
        }
        panY = PdfLayout.clampPan(panY, getHeight(), layout.total, zoom);
        panX = PdfLayout.clampPan(panX, getWidth(), layout.width, zoom);
        notifyPage();
        invalidate();
    }

    // ---------------- 绘制 ----------------

    @Override
    protected void onDraw(Canvas canvas) {
        canvas.drawRect(0, 0, getWidth(), getHeight(), bgPaint);
        if (layout == null || layout.count == 0) return;

        float visH = getHeight() / zoom;
        int[] range = layout.visible(panY, panY + visH);

        canvas.save();
        canvas.translate(-panX * zoom, -panY * zoom);
        canvas.scale(zoom, zoom);
        for (int i = range[0]; i <= range[1]; i++) {
            float top = layout.topOf(i);
            float h = layout.heightOf(i);
            rect.set(0, top, layout.width, top + h);
            Bitmap b = cache.get(i);
            if (b != null && !b.isRecycled()) {
                canvas.drawBitmap(b, null, rect, paint);
            } else {
                canvas.drawRect(rect, phPaint);
                request(i);
            }
        }
        canvas.restore();
    }

    private void request(final int index) {
        if (renderer == null || queued.contains(index) || released) return;
        queued.add(index);
        final int target = PdfLayout.renderWidth(getWidth(), zoom);
        pool.execute(new Runnable() {
            @Override
            public void run() {
                Bitmap b = null;
                try {
                    b = renderer.render(index, target);
                } catch (Throwable ignored) {
                }
                final Bitmap made = b;
                post(new Runnable() {
                    @Override
                    public void run() {
                        queued.remove(index);
                        if (released) {
                            if (made != null && !made.isRecycled()) made.recycle();
                            return;
                        }
                        if (made != null) {
                            cache.put(index, made);
                            invalidate();
                        }
                    }
                });
            }
        });
    }

    /** 缩放停下来后，如果新宽度明显超过已有位图，清缓存重渲 */
    private void refreshResolution() {
        int need = PdfLayout.renderWidth(getWidth(), zoom);
        if (need > renderedWidth + Math.max(48, renderedWidth / 4)) {
            renderedWidth = need;
            cache.evictAll();
            queued.clear();
        }
        invalidate();
    }

    private int renderedWidth = 0;

    private void zoomAt(float next, float fx, float fy) {
        if (layout == null) return;
        panX = PdfLayout.zoomPan(panX, fx, zoom, next);
        panY = PdfLayout.zoomPan(panY, fy, zoom, next);
        zoom = next;
        panX = PdfLayout.clampPan(panX, getWidth(), layout.width, zoom);
        panY = PdfLayout.clampPan(panY, getHeight(), layout.total, zoom);
        notifyPage();
        invalidate();
    }

    private void notifyPage() {
        if (listener == null || layout == null || layout.count == 0) return;
        int p = currentPage();
        if (p != lastPage) {
            lastPage = p;
            listener.onPageChanged(p, layout.count);
        }
    }

    // ---------------- 触摸 ----------------

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        scale.onTouchEvent(event);
        gestures.onTouchEvent(event);
        return true;
    }

    @Override
    public void computeScroll() {
        if (scroller.computeScrollOffset()) {
            panX = scroller.getCurrX();
            panY = scroller.getCurrY();
            if (layout != null) {
                panX = PdfLayout.clampPan(panX, getWidth(), layout.width, zoom);
                panY = PdfLayout.clampPan(panY, getHeight(), layout.total, zoom);
            }
            notifyPage();
            invalidate();
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        scroller.forceFinished(true);
    }
}
