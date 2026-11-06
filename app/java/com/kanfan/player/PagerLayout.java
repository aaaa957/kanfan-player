package com.kanfan.player;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.widget.Scroller;

/**
 * 横向翻页容器：手指跟手拖动、松手吸附、到头阻尼回弹。
 *
 * 固定三个子 View（ZoomView），只承载「上一张 / 当前 / 下一张」，
 * 翻页后循环复用，所以 20 张图也只有 3 个 View、最多 3 份解码结果在外面。
 */
public class PagerLayout extends ViewGroup {

    public interface Source {
        int count();

        /** 已解码好的返回 drawable，没有则返回 null（之后由 {@link #update} 补上） */
        Drawable get(int index);

        /** 请求异步解码，完成后调用 update(page, drawable) */
        void request(int index);

        /** 页码变化（手指松手吸附结束后） */
        void onPageChanged(int index);

        /** 已经在第一张/最后一张还要继续翻 */
        void onEdgeTry(int direction);
    }

    public interface Listener {
        void onSingleTap();
    }

    private static final int SLOT_COUNT = 3;

    private final ZoomView[] slots = new ZoomView[SLOT_COUNT];
    private final Scroller scroller;
    private final int dragSlop;
    private final int minFling;

    private Source src;
    private Listener listener;

    private int index = 0;
    private float offset = 0f;      // 视觉位移（已含边缘阻尼）
    private float downRaw = 0f;     // 按下时的视觉位移，作为拖动基准
    private float downX, downY;
    private boolean dragging;
    private boolean settling;
    private int targetIndex = -1;
    private VelocityTracker vt;

    public PagerLayout(Context c) {
        super(c);
        setBackgroundColor(0xFF000000);
        scroller = new Scroller(c);
        ViewConfiguration vc = ViewConfiguration.get(c);
        dragSlop = vc.getScaledTouchSlop();
        minFling = vc.getScaledMinimumFlingVelocity();

        for (int i = 0; i < SLOT_COUNT; i++) {
            ZoomView z = new ZoomView(c);
            z.setTag(Integer.valueOf(-100 - i));
            z.setListener(new ZoomView.Listener() {
                @Override
                public void onSingleTap() {
                    if (listener != null) listener.onSingleTap();
                }
            });
            slots[i] = z;
            addView(z);
        }
    }

    public void setSource(Source s) {
        src = s;
    }

    public void setListener(Listener l) {
        listener = l;
    }

    public int index() {
        return index;
    }

    private int count() {
        return src == null ? 0 : src.count();
    }

    /** 重置到某一页（数据刚准备好时用），无动画 */
    public void reset(int start) {
        int n = count();
        index = n == 0 ? 0 : Math.max(0, Math.min(start, n - 1));
        offset = 0f;
        abortSettle();
        bindPages();
        layoutChildren();
        if (src != null) src.onPageChanged(index);
        invalidate();
    }

    /** 解码完成回填：任何正持有该页的槽位都换上 */
    public void update(int page, Drawable d) {
        if (d == null) return;
        boolean hit = false;
        for (int i = 0; i < SLOT_COUNT; i++) {
            if (pageOf(slots[i]) == page) {
                slots[i].setDrawable(d);
                hit = true;
            }
        }
        if (hit) invalidate();
    }

    // ---------------- 布局 ----------------

    private int pageOf(View v) {
        Object t = v.getTag();
        return t instanceof Integer ? ((Integer) t).intValue() : Integer.MIN_VALUE;
    }

    /** 槽位 0 = 上一张，1 = 当前，2 = 下一张 */
    private void bindPages() {
        int n = count();
        for (int i = 0; i < SLOT_COUNT; i++) {
            int page = index + i - 1;
            ZoomView z = slots[i];
            z.setTag(Integer.valueOf(page));
            if (page < 0 || page >= n || src == null) {
                z.setDrawable(null);
                continue;
            }
            Drawable d = src.get(page);
            z.setDrawable(d);
            if (d == null) src.request(page);
        }
    }

    private void layoutChildren() {
        int w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;
        for (int i = 0; i < SLOT_COUNT; i++) {
            ZoomView z = slots[i];
            int x = Math.round((pageOf(z) - index) * (float) w + offset);
            z.layout(x, 0, x + w, h);
        }
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int w = MeasureSpec.getSize(widthSpec);
        int h = MeasureSpec.getSize(heightSpec);
        setMeasuredDimension(w, h);
        int cw = MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY);
        int ch = MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY);
        for (int i = 0; i < SLOT_COUNT; i++) slots[i].measure(cw, ch);
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        layoutChildren();
    }

    @Override
    public void computeScroll() {
        if (!settling) return;
        if (scroller.computeScrollOffset()) {
            offset = scroller.getCurrX();
            layoutChildren();
            postInvalidateOnAnimation();
        }
        if (scroller.isFinished()) {
            settling = false;
            offset = 0f;
            if (targetIndex >= 0 && targetIndex != index) index = targetIndex;
            targetIndex = -1;
            bindPages();
            layoutChildren();
            if (src != null) src.onPageChanged(index);
            invalidate();
        }
    }

    // ---------------- 手势 ----------------

    private ZoomView curZoom() {
        return slots[1];
    }

    private void track(MotionEvent e) {
        if (vt == null) vt = VelocityTracker.obtain();
        vt.addMovement(e);
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = e.getX();
                downY = e.getY();
                downRaw = offset;
                dragging = false;
                abortSettle();
                if (vt != null) {
                    vt.recycle();
                    vt = null;
                }
                track(e);
                return false;
            case MotionEvent.ACTION_POINTER_DOWN:
                return false;
            case MotionEvent.ACTION_MOVE: {
                track(e);
                if (count() <= 1) return false;
                // 放大状态把事件留给子 View 做拖动
                if (curZoom().isZoomed()) return false;
                float dx = e.getX() - downX;
                float dy = e.getY() - downY;
                if (Math.abs(dx) > dragSlop && Math.abs(dx) > Math.abs(dy) * 1.2f) {
                    dragging = true;
                    return true;
                }
                return false;
            }
            default:
                return false;
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = e.getX();
                downY = e.getY();
                downRaw = offset;
                dragging = false;
                abortSettle();
                if (vt != null) {
                    vt.recycle();
                    vt = null;
                }
                track(e);
                return true;
            case MotionEvent.ACTION_MOVE: {
                if (dragging) track(e);
                if (curZoom().isZoomed()) return true;
                float dx = e.getX() - downX;
                if (!dragging && Math.abs(dx) > dragSlop) dragging = true;
                if (dragging) {
                    offset = Swipe.damp(downRaw + dx, index, count());
                    layoutChildren();
                    invalidate();
                }
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                float vx = 0f;
                if (vt != null) {
                    track(e);
                    vt.computeCurrentVelocity(1000);
                    vx = vt.getXVelocity();
                    vt.recycle();
                    vt = null;
                }
                if (dragging) {
                    settle(vx);
                } else if (e.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                    offset = 0f;
                    layoutChildren();
                }
                dragging = false;
                return true;
            }
            default:
                return true;
        }
    }

    /** 松手后决定停在原地还是翻到邻页 */
    private void settle(float vx) {
        int n = count();
        int w = Math.max(1, getWidth());
        if (n <= 1) {
            animateOffsetTo(0f, -1);
            return;
        }

        int dir = Swipe.decide(offset, vx, w, index, n, minFling);

        if (dir == 0) {
            // 已经在头/尾还继续拖，给个提示
            if (src != null && Math.abs(offset) > Ui.dp(getContext(), 48)) {
                if (index == 0 && offset > 0) src.onEdgeTry(-1);
                else if (index == n - 1 && offset < 0) src.onEdgeTry(1);
            }
            animateOffsetTo(0f, -1);
        } else {
            animateOffsetTo(Swipe.target(dir, w), index + dir);
        }
    }

    private void animateOffsetTo(float target, int newIndex) {
        abortSettle();
        int w = Math.max(1, getWidth());
        float dist = Math.abs(target - offset);
        int dur = Swipe.duration(dist, w);
        scroller.startScroll(Math.round(offset), 0, Math.round(target - offset), 0, dur);
        settling = true;
        targetIndex = newIndex;
        postInvalidateOnAnimation();
    }

    private void abortSettle() {
        if (!scroller.isFinished()) scroller.forceFinished(true);
        settling = false;
        targetIndex = -1;
    }

    /** 供外部判断当前页是否有图 */
    public boolean hasImage() {
        return src != null && src.get(index) != null;
    }

    /** 切到别的模式时把三张图都放掉，别占着内存 */
    public void release() {
        abortSettle();
        for (int i = 0; i < SLOT_COUNT; i++) {
            slots[i].setTag(Integer.valueOf(-100 - i));
            slots[i].setDrawable(null);
        }
        offset = 0f;
        invalidate();
    }
}
