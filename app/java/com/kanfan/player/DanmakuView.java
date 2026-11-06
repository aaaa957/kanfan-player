package com.kanfan.player;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.view.View;

import java.util.List;

/**
 * 弹幕渲染层：自己按帧重绘，位置直接从播放器读取，不做时间累积，因此拖动进度后立即对齐。
 */
public class DanmakuView extends View {

    /** 播放器向弹幕层提供当前进度 */
    public interface Clock {
        long position();

        boolean playing();
    }

    /** 弹幕只占画面上方，避免压住底部字幕 */
    private static final float AREA = 0.42f;

    private List<Danmaku.Item> items;
    private Clock clock;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float density;
    private final float speedPx;
    private final float laneH;
    private int laneCount = 1;
    private boolean laidOut;
    private boolean running;
    private boolean enabled = true;
    private long lastPosition = -1;

    public DanmakuView(Context c) {
        super(c);
        density = c.getResources().getDisplayMetrics().density;
        float size = 14f * c.getResources().getDisplayMetrics().scaledDensity;

        fill.setTextSize(size);
        fill.setTypeface(Typeface.DEFAULT_BOLD);
        fill.setColor(Color.WHITE);

        stroke.setTextSize(size);
        stroke.setTypeface(Typeface.DEFAULT_BOLD);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(3f * density);
        stroke.setColor(0xCC000000);

        speedPx = 108f * density;   // 每秒移动的距离
        laneH = 23f * density;
        setWillNotDraw(false);
    }

    public void setClock(Clock c) {
        clock = c;
    }

    public void setData(List<Danmaku.Item> list) {
        items = list;
        laidOut = false;
        invalidate();
    }

    public boolean hasData() {
        return items != null && !items.isEmpty();
    }

    public void setDanmakuEnabled(boolean on) {
        enabled = on;
        invalidate();
    }

    public void setRunning(boolean r) {
        running = r;
        removeCallbacks(frame);
        if (r) postDelayed(frame, 16);
    }

    private final Runnable frame = new Runnable() {
        @Override
        public void run() {
            invalidate();
            if (running) postDelayed(this, 16);
        }
    };

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        laidOut = false;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (!enabled || clock == null || items == null || items.isEmpty()) return;
        int w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;
        if (!laidOut) prepare(w, h);

        long pos = clock.position();
        lastPosition = pos;

        for (int i = lowerBound(pos - 25000L); i < items.size(); i++) {
            Danmaku.Item it = items.get(i);
            if (it.time > pos) break;
            if (it.lane < 0) continue;
            float tw = fill.measureText(it.text);
            long elapsed = pos - it.time;

            if (it.mode == Danmaku.SCROLL) {
                long life = (long) ((w + tw) / speedPx * 1000f);
                if (elapsed > life) continue;
                float x = w - elapsed * speedPx / 1000f;
                drawItem(canvas, it, x, it.lane * laneH + laneH * 0.78f);
            } else {
                if (elapsed > 4000) continue;
                float x = (w - tw) / 2f;
                float y = it.mode == Danmaku.TOP
                        ? it.lane * laneH + laneH * 0.78f
                        : h * AREA - it.lane * laneH - laneH * 0.2f;
                drawItem(canvas, it, x, y);
            }
        }
    }

    private void drawItem(Canvas canvas, Danmaku.Item it, float x, float y) {
        fill.setColor(0xFF000000 | (it.color & 0xFFFFFF));
        canvas.drawText(it.text, x, y, stroke);
        canvas.drawText(it.text, x, y, fill);
    }

    /**
     * 轨道分配：滚动弹幕要求上一条的尾巴已经离开右边缘再进下一条，
     * 固定弹幕（顶/底）占用同一条轨道 4 秒。
     */
    private void prepare(int w, int h) {
        int lanes = Math.max(1, (int) (h * AREA / laneH));
        laneCount = lanes;
        long[] freeScroll = new long[lanes];
        long[] freeTop = new long[lanes];
        long[] freeBottom = new long[lanes];

        for (Danmaku.Item it : items) {
            it.lane = -1;
            float tw = fill.measureText(it.text);
            if (it.mode == Danmaku.SCROLL) {
                for (int l = 0; l < lanes; l++) {
                    if (freeScroll[l] <= it.time) {
                        it.lane = l;
                        freeScroll[l] = it.time + (long) ((tw + 14 * density) / speedPx * 1000f);
                        break;
                    }
                }
            } else {
                long[] arr = it.mode == Danmaku.TOP ? freeTop : freeBottom;
                for (int l = 0; l < lanes; l++) {
                    if (arr[l] <= it.time) {
                        it.lane = l;
                        arr[l] = it.time + 4000;
                        break;
                    }
                }
            }
        }
        laidOut = true;
    }

    /** 第一个 time >= t 的下标 */
    private int lowerBound(long t) {
        int lo = 0, hi = items.size() - 1, ans = items.size();
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (items.get(mid).time >= t) {
                ans = mid;
                hi = mid - 1;
            } else {
                lo = mid + 1;
            }
        }
        return ans;
    }

    @Override
    protected void onDetachedFromWindow() {
        removeCallbacks(frame);
        super.onDetachedFromWindow();
    }
}
