package com.kanfan.player;

import java.util.List;
import java.util.Locale;

/**
 * 识别 Galgame 移植版用的是哪个引擎。
 *
 * 只看直属目录结构：
 *   TyranoBuilder（HTML5）  index.html + tyrano/ 或 data/
 *   ONS（NScripter）        *.nsa / *.ns2 / *.sar / nscript.dat
 *   KiriKiri（KRKR/TVP）    *.xp3
 *
 * 纯逻辑，可用 JVM 单测。
 */
public final class GalGame {

    public static final int NONE = 0;
    public static final int TYRANO = 1;
    public static final int ONS = 2;
    public static final int KRKR = 3;

    /** 这个引擎能不能直接跑（判断启动卡该画成可用还是灰的） */
    public static boolean runs(int engine) {
        return engine == TYRANO || engine == ONS || engine == KRKR;
    }

    public static final class Info {
        public int engine = NONE;
        /** Tyrano 的入口文件（相对游戏目录） */
        public String entry = "";
        public String label = "";
        /** 命中的特征数量，纯展示用 */
        public int hits;

        public boolean supported() {
            return runs(engine);
        }

        public String why() {
            switch (engine) {
                case TYRANO:
                    return "TyranoBuilder（网页引擎），可以直接运行";
                case ONS:
                    return "ONS（NScripter），内置引擎可直接运行（需要 64 位设备）";
                case KRKR:
                    return "KiriKiri（KRKR / TVP），首次启动要先导入（复制一份到应用目录）";
                default:
                    return "没认出这是哪种移植版";
            }
        }
    }

    /** children 里每项是 {名字, "1" 表示目录} */
    public static Info detect(List<String[]> children) {
        Info info = new Info();
        if (children == null || children.isEmpty()) return info;

        boolean indexHtml = false;
        boolean tyranoDir = false;
        boolean dataDir = false;
        int ns = 0, xp3 = 0;

        for (String[] c : children) {
            if (c == null || c.length < 2) continue;
            String name = c[0] == null ? "" : c[0];
            String low = name.toLowerCase(Locale.US);
            boolean dir = "1".equals(c[1]);

            if (dir) {
                if (low.equals("tyrano")) tyranoDir = true;
                if (low.equals("data")) dataDir = true;
                continue;
            }
            if (low.equals("index.html") || low.equals("index.htm")) indexHtml = true;
            if (low.endsWith(".nsa") || low.endsWith(".ns2") || low.endsWith(".sar")
                    || low.equals("nscript.dat") || low.equals("nscr_sec.dat")
                    || low.equals("0.txt") || low.equals("00.txt")) {
                ns++;
            }
            if (low.endsWith(".xp3")) xp3++;
        }

        // Tyrano 的目录结构最明确，先认它
        if (indexHtml && (tyranoDir || dataDir)) {
            info.engine = TYRANO;
            info.entry = "index.html";
            info.label = "TyranoBuilder";
            info.hits = (tyranoDir ? 1 : 0) + (dataDir ? 1 : 0) + 1;
            return info;
        }
        if (xp3 > 0) {
            info.engine = KRKR;
            info.label = "KiriKiri";
            info.hits = xp3;
            return info;
        }
        if (ns > 0) {
            info.engine = ONS;
            info.label = "ONS";
            info.hits = ns;
            return info;
        }
        return info;
    }
}
