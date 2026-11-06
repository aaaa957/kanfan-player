package com.kanfan.player;

import java.util.List;

/**
 * 连续滚动的窗口算术：章节在整段文本里的偏移、当前读到哪里、裁剪后如何平移。
 * 纯 Java，可在 JVM 上跑用例（tests/FlowTest.java）。
 */
public final class Flow {

    public static final class Win {
        public final int chapter;
        public int start;
        public int end;

        public Win(int chapter, int start) {
            this.chapter = chapter;
            this.start = start;
        }
    }

    private Flow() {
    }

    /** offset 落在哪一章；窗口为空返回 -1 */
    public static int chapterAt(List<Win> wins, int offset) {
        if (wins == null || wins.isEmpty()) return -1;
        for (int i = 0; i < wins.size(); i++) {
            if (offset < wins.get(i).end) return wins.get(i).chapter;
        }
        return wins.get(wins.size() - 1).chapter;
    }

    /** offset 在当前章内的比例（0..1）；落在最后一章之后按 1 处理 */
    public static float ratioAt(List<Win> wins, int offset) {
        if (wins == null || wins.isEmpty()) return 0f;
        for (int i = 0; i < wins.size(); i++) {
            Win w = wins.get(i);
            if (offset < w.end) {
                int len = Math.max(1, w.end - w.start);
                float r = (offset - w.start) / (float) len;
                return Math.min(1f, Math.max(0f, r));
            }
        }
        return 1f;
    }

    /**
     * 从顶部裁掉 keepFrom 之前的窗口，剩余窗口的偏移整体左移。
     * 返回被裁掉的字符数（调用方用它换算滚动像素补偿）。
     */
    public static int trimTo(List<Win> wins, int keepFrom) {
        if (wins == null || keepFrom <= 0 || wins.size() <= keepFrom) return 0;
        int cut = wins.get(keepFrom).start;
        if (cut <= 0) return 0;
        for (int i = keepFrom; i < wins.size(); i++) {
            Win w = wins.get(i);
            w.start -= cut;
            w.end -= cut;
        }
        wins.subList(0, keepFrom).clear();
        return cut;
    }
}
