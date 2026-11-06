package com.kanfan.player;

/**
 * PDF 的排版算术：每页按视口宽度等比缩放，页面之间留 gap，纵向累加。
 *
 * 所有坐标都是「内容坐标」——宽度等于视口宽度、缩放系数为 1 时的坐标系。
 * 实际绘制时再整体乘 zoom、减去 pan，这样缩放不会影响页与页的相对位置。
 * 纯 Java，可用 JVM 单测。
 */
public final class PdfLayout {

    public final int count;
    public final float width;
    public final float total;
    private final float[] tops;
    private final float[] heights;

    /**
     * @param pw        每页的原始宽度（PDF 点）
     * @param ph        每页的原始高度
     * @param viewWidth 视口宽度（像素）
     * @param gap       页间距（像素）
     */
    public PdfLayout(int[] pw, int[] ph, float viewWidth, float gap) {
        int n = pw == null ? 0 : pw.length;
        if (ph == null || ph.length < n) n = ph == null ? 0 : ph.length;
        count = n;
        width = Math.max(1f, viewWidth);
        tops = new float[n];
        heights = new float[n];
        float y = 0;
        for (int i = 0; i < n; i++) {
            int w = pw[i] > 0 ? pw[i] : 1;
            int h = ph[i] > 0 ? ph[i] : 1;
            tops[i] = y;
            heights[i] = width * h / w;
            y += heights[i] + (i == n - 1 ? 0 : gap);
        }
        total = n == 0 ? 0 : y;
    }

    public float topOf(int page) {
        if (page < 0) return 0;
        if (page >= count) return total;
        return tops[page];
    }

    public float heightOf(int page) {
        if (page < 0 || page >= count) return 0;
        return heights[page];
    }

    /** 内容坐标 y 落在第几页；越界夹到首/尾 */
    public int pageAt(float y) {
        if (count == 0) return 0;
        if (y <= 0) return 0;
        if (y >= total) return count - 1;
        int lo = 0, hi = count - 1;
        while (lo < hi) {
            int mid = (lo + hi + 1) >>> 1;
            if (tops[mid] <= y) lo = mid;
            else hi = mid - 1;
        }
        return lo;
    }

    /** 可视区 [top, bottom] 覆盖到的页范围：{首页, 末页}（含） */
    public int[] visible(float top, float bottom) {
        if (count == 0) return new int[]{0, -1};
        if (bottom < top) {
            float t = top;
            top = bottom;
            bottom = t;
        }
        int first = pageAt(Math.max(0, top));
        int last = pageAt(Math.min(total, Math.max(top, bottom)));
        if (last < first) last = first;
        return new int[]{first, last};
    }

    /** 让第 page 页顶部贴住视口顶部时要的 panY */
    public float panForPage(int page) {
        return topOf(page);
    }

    /** 把 pan 夹在 [0, 内容 - 视口/zoom] 里，避免拖出边界 */
    public static float clampPan(float pan, float viewSize, float contentSize, float zoom) {
        float visible = viewSize / Math.max(0.01f, zoom);
        float max = contentSize - visible;
        if (max <= 0) return 0f;
        if (pan < 0) return 0f;
        return pan > max ? max : pan;
    }

    /** 以焦点为中心缩放：返回新的 pan（内容坐标） */
    public static float zoomPan(float pan, float focus, float oldZoom, float newZoom) {
        if (newZoom <= 0 || oldZoom <= 0) return pan;
        return focus / oldZoom + pan - focus / newZoom;
    }

    /** 当前应渲染的位图宽度：屏幕宽度 × 缩放，封顶免得 OOM */
    public static int renderWidth(float viewWidth, float zoom) {
        float w = viewWidth * Math.max(1f, zoom);
        if (w > 2400f) w = 2400f;
        if (w < 320f) w = 320f;
        return (int) Math.ceil(w);
    }
}
