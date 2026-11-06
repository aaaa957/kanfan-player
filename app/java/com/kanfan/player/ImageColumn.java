package com.kanfan.player;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * 上下滑动看图：整列铺开、连续下滑直到最后一张。
 *
 * 每张图按原始比例算高度，但只有视口前后各一屏内的图会被解码进 ImageView，
 * 远离视口的置空释放引用（Bitmap 仍在 ImageActivity 的 LruCache 里，超出上限自动淘汰），
 * 所以一个几百张图的目录也不会把内存吃爆。
 */
public class ImageColumn extends FrameLayout {

    public interface Host {
        int count();

        /** 已知原始宽高返回 {w,h}，未知返回 null */
        int[] size(int index);

        Drawable get(int index);

        /** 请求异步解码，完成后调用 {@link #update(int, Drawable)} */
        void request(int index);
    }

    private final ScrollView scroll;
    private final LinearLayout box;
    private final TextView footer;
    private final List<ImageView> views = new ArrayList<ImageView>();

    private Host host;
    private int gap;
    private Runnable tapHook;
    private Runnable scrollHook;
    private int pendingScroll = -1;

    public void setTapHook(Runnable r) {
        tapHook = r;
    }

    public void setScrollHook(Runnable r) {
        scrollHook = r;
    }

    public ImageColumn(Context c) {
        super(c);
        gap = Ui.dp(c, 2);

        scroll = new ScrollView(c);
        scroll.setBackgroundColor(0xFF000000);
        scroll.setFillViewport(false);

        box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(box, new ScrollView.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

        addView(scroll, new FrameLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        footer = new TextView(c);
        footer.setTextColor(0x66FFFFFF);
        footer.setTextSize(12f);
        footer.setGravity(android.view.Gravity.CENTER);
        footer.setPadding(0, Ui.dp(c, 28), 0, Ui.dp(c, 36));

        scroll.setOnScrollChangeListener(new View.OnScrollChangeListener() {
            @Override
            public void onScrollChange(View v, int sx, int sy, int osx, int osy) {
                syncWindow();
                if (scrollHook != null) scrollHook.run();
            }
        });
    }

    public void setHost(Host h) {
        host = h;
    }

    public int count() {
        return host == null ? 0 : host.count();
    }

    /** 重建整列（数量或尺寸信息变化时调用） */
    public void rebuild() {
        box.removeAllViews();
        views.clear();
        int n = count();
        int w = getWidth() > 0 ? getWidth() : getResources().getDisplayMetrics().widthPixels;
        int screenH = getResources().getDisplayMetrics().heightPixels;

        for (int i = 0; i < n; i++) {
            ImageView iv = new ImageView(getContext());
            iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
            iv.setBackgroundColor(0xFF000000);
            iv.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (tapHook != null) tapHook.run();
                }
            });

            int h = Math.round(w * 1.2f);
            int[] sz = host.size(i);
            if (sz != null && sz[0] > 0 && sz[1] > 0) {
                h = Math.round(w * (sz[1] / (float) sz[0]));
                int min = Math.round(w * 0.28f);
                int max = Math.round(screenH * 2.6f);
                h = Math.max(min, Math.min(h, max));
            }

            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LayoutParams.MATCH_PARENT, h);
            lp.bottomMargin = gap;
            box.addView(iv, lp);
            views.add(iv);
        }

        if (!views.isEmpty()) {
            box.addView(footer);
            footer.setText(String.format("已经到底了 · 共 %d 张", n));
        }
        post(new Runnable() {
            @Override
            public void run() {
                syncWindow();
            }
        });
    }

    /** 解码完成后回填 */
    public void update(int index, Drawable d) {
        if (d == null || index < 0 || index >= views.size()) return;
        ImageView iv = views.get(index);
        if (iv.getDrawable() == null && inWindow(index)) {
            iv.setImageDrawable(d);
        }
    }

    private boolean inWindow(int i) {
        if (i < 0 || i >= views.size()) return false;
        View c = views.get(i);
        int top = scroll.getScrollY();
        int bottom = top + scroll.getHeight();
        int margin = scroll.getHeight();
        return c.getBottom() >= top - margin && c.getTop() <= bottom + margin;
    }

    /** 只保留视口前后各一屏内的图 */
    private void syncWindow() {
        int n = views.size();
        if (n == 0 || host == null) return;
        int top = scroll.getScrollY();
        int bottom = top + scroll.getHeight();
        int margin = Math.max(1, scroll.getHeight());

        for (int i = 0; i < n; i++) {
            ImageView iv = views.get(i);
            boolean near = iv.getBottom() - top >= -margin && iv.getTop() - bottom <= margin;
            if (!near) {
                if (iv.getDrawable() != null) iv.setImageDrawable(null);
                continue;
            }
            if (iv.getDrawable() != null) continue;
            Drawable d = host.get(i);
            if (d != null) iv.setImageDrawable(d);
            else host.request(i);
        }
    }

    public void scrollToIndex(int index) {
        if (index < 0 || index >= views.size()) return;
        pendingScroll = index;
        applyPendingScroll();
        post(new Runnable() {
            @Override
            public void run() {
                applyPendingScroll();
            }
        });
    }

    /** 视图还没测量时先记着，等 onLayout 再滚过去 */
    private void applyPendingScroll() {
        if (pendingScroll < 0 || pendingScroll >= views.size()) return;
        if (scroll.getHeight() <= 0) return;
        View v = views.get(pendingScroll);
        if (v.getHeight() <= 0) return;
        scroll.scrollTo(0, Math.max(0, v.getTop() - Ui.dp(getContext(), 6)));
        pendingScroll = -1;
        syncWindow();
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        super.onLayout(changed, l, t, r, b);
        if (changed) {
            applyPendingScroll();
            syncWindow();
        }
    }

    /** 当前视口里占面积最大的那一张 */
    public int mostVisibleIndex() {
        int n = views.size();
        if (n == 0) return 0;
        int viewTop = scroll.getScrollY();
        int viewBottom = viewTop + scroll.getHeight();
        int best = 0, bestOverlap = -1;
        for (int i = 0; i < n; i++) {
            View c = views.get(i);
            int overlap = Math.min(c.getBottom(), viewBottom) - Math.max(c.getTop(), viewTop);
            if (overlap > bestOverlap) {
                bestOverlap = overlap;
                best = i;
            }
        }
        return best;
    }

    public ScrollView scrollView() {
        return scroll;
    }

    /** 切到别的模式时把解码结果都放掉 */
    public void releaseAll() {
        for (ImageView iv : views) {
            if (iv.getDrawable() != null) iv.setImageDrawable(null);
        }
    }
}
