package com.kanfan.player;

/**
 * 翻页手势的纯算术：边缘阻尼与松手后的落点判定。
 * 不依赖 Android，可直接在 JVM 上跑用例。
 *
 * 约定：offset 为当前页的视觉位移，负值表示正被拖向左（即看下一张）。
 */
public class Swipe {

    /** 拖过屏宽的 28% 就翻页 */
    public static final float PAGE_RATIO = 0.28f;
    /** 有速度时，位移只要超过 6% 屏宽就认翻页 */
    public static final float FLING_RATIO = 0.06f;
    /** 到头时的阻尼系数 */
    public static final float EDGE_DAMP = 0.35f;
    /** 只有一张图时的阻尼系数 */
    public static final float SINGLE_DAMP = 0.22f;

    /**
     * 松手后停在哪儿。
     *
     * @return -1 上一张，+1 下一张，0 弹回原页
     */
    public static int decide(float offset, float vx, int width, int index, int count, float minFling) {
        if (count <= 1 || width <= 0) return 0;
        float threshold = width * PAGE_RATIO;
        float flingEdge = width * FLING_RATIO;

        int dir = 0;
        if (offset < -threshold || (offset < -flingEdge && vx < -minFling)) dir = 1;
        else if (offset > threshold || (offset > flingEdge && vx > minFling)) dir = -1;

        // 到头了就不翻，交给阻尼回弹
        if (dir != 0 && (index + dir < 0 || index + dir >= count)) return 0;
        return dir;
    }

    /** 视觉位移：越界方向按系数压缩，拖起来有「拉不动」的手感 */
    public static float damp(float raw, int index, int count) {
        if (count <= 1) return raw * SINGLE_DAMP;
        if (index <= 0 && raw > 0) return raw * EDGE_DAMP;
        if (index >= count - 1 && raw < 0) return raw * EDGE_DAMP;
        return raw;
    }

    /** 松手吸附动画的目标位移 */
    public static float target(int dir, int width) {
        return -dir * (float) width;
    }

    /** 吸附动画时长：走得越远越久，但有上下限 */
    public static int duration(float distance, int width) {
        if (width <= 0) return 0;
        float ratio = Math.min(1f, Math.abs(distance) / width);
        return (int) Math.min(320, 150 + 220 * ratio);
    }
}
