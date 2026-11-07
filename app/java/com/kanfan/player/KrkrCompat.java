package com.kanfan.player;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * KRKR 移植版的插件预检。
 *
 * Kirikiroid2 的汉化移植版常在 startup.tjs 里 Plugins.link("xxx.dll")。
 * 引擎内置了十几个（含从 Kirikiroid2 移植的 csvParser/saveStruct 等），其余（krmovie/menu/layerExSave…）还没有，
 * 缺了之后的表现通常是黑屏或一句看不懂的 TJS 报错。这里先扫一遍，把话说明白。
 */
public final class KrkrCompat {

    /**
     * 引擎里静态编进去的插件（krkrsdl2 的 BUILTIN_PLUGIN_ENABLE_* + 从 Kirikiroid2 移植的 kr2plugins）。
     * 名字与插件源码里的 NCB_MODULE_NAME 对应，去掉 .dll 后缀。
     */
    private static final String[] BUILTIN = {
            "extrans", "wutcwf", "wuvorbis", "fstat", "json", "varfile", "krglhwebp",
            // 从 Kirikiroid2 移植（v3.6）
            "csvparser", "savestruct", "getsample", "getabout", "fftgraph", "win32dialog"
    };

    /** 常见的、krkrsdl2 没有的插件：给出更具体的后果说明 */
    private static final String[][] HINTS = {
            {"krmovie", "OP/ED 视频播不了，部分游戏会卡在开场视频"},
            {"krflash", "Flash 动画播不了"},
            {"alfmovie", "AlphaMovie 视频播不了"},
            {"agg", "AGG 动画/视频播不了"},
            {"menu", "游戏内右键菜单可能失效"},
            {"windowex", "窗口控制（全屏/尺寸）会失效"},
            {"layerexsave", "截图/存缩略图会失败，存档界面可能报错"},
            {"dirlist", "列目录会失败（部分选择界面）"},
            {"shrinkcopy", "缩放复制图像会失败"},
            {"packinone", "打包进单个 exe 的资源读不到"},
            {"emoteplayer", "E-mote 立绘动不了"},
            {"psb", "E-mote/PSB 资源读不了"}
    };

    private static final Pattern LINK = Pattern.compile(
            "Plugins\\s*\\.\\s*link\\s*\\(\\s*[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE);

    public static final class Report {
        public final Set<String> builtin = new LinkedHashSet<String>();
        public final Set<String> missing = new LinkedHashSet<String>();
        public boolean scanned;

        public boolean hasProblem() {
            return !missing.isEmpty();
        }

        public String hintOf(String name) {
            for (String[] h : HINTS) {
                if (h[0].equals(name)) return h[1];
            }
            return "";
        }
    }

    private KrkrCompat() {
    }

    /** 从一段脚本里抠出 Plugins.link("xxx.dll") 的名字（小写、去掉 .dll） */
    public static Set<String> linksIn(String text) {
        Set<String> out = new LinkedHashSet<String>();
        if (text == null) return out;
        Matcher m = LINK.matcher(text);
        while (m.find()) {
            String n = normalize(m.group(1));
            if (!n.isEmpty()) out.add(n);
        }
        return out;
    }

    public static String normalize(String raw) {
        if (raw == null) return "";
        String n = raw.trim().toLowerCase(Locale.US).replace('\\', '/');
        int i = n.lastIndexOf('/');
        if (i >= 0) n = n.substring(i + 1);
        if (n.endsWith(".dll") || n.endsWith(".tpm")) n = n.substring(0, n.length() - 4);
        return n;
    }

    public static boolean isBuiltin(String name) {
        for (String b : BUILTIN) {
            if (b.equals(name)) return true;
        }
        // krkrsdl2 里 wuvorbis 也可能以 wuvorbis2 出现；这里不猜，只认精确名
        return false;
    }

    public static Report of(Set<String> names) {
        Report r = new Report();
        if (names != null) {
            for (String n : names) {
                if (isBuiltin(n)) r.builtin.add(n);
                else r.missing.add(n);
            }
        }
        return r;
    }

    /**
     * 扫一个已导入的游戏目录。只看脚本类文本文件，单文件上限 1 MB、总数上限 400 个，
     * 免得一个几 GB 的游戏把启动拖住。
     */
    public static Report scanDir(File dir) {
        Report r = new Report();
        Set<String> names = new LinkedHashSet<String>();
        int[] budget = new int[]{400};
        walk(dir, names, budget, 0);
        r.scanned = budget[0] < 400;
        Report cls = of(names);
        r.builtin.addAll(cls.builtin);
        r.missing.addAll(cls.missing);
        return r;
    }

    private static void walk(File dir, Set<String> out, int[] budget, int depth) {
        if (dir == null || depth > 3 || budget[0] <= 0) return;
        File[] kids = dir.listFiles();
        if (kids == null) return;
        for (File f : kids) {
            if (budget[0] <= 0) return;
            if (f.isDirectory()) {
                walk(f, out, budget, depth + 1);
                continue;
            }
            String n = f.getName().toLowerCase(Locale.US);
            if (!(n.endsWith(".tjs") || n.endsWith(".ks") || n.endsWith(".csv")
                    || n.endsWith(".txt"))) {
                continue;
            }
            if (f.length() <= 0 || f.length() > 1024 * 1024) continue;
            budget[0]--;
            String text = read(f);
            if (text != null) out.addAll(linksIn(text));
        }
    }

    private static String read(File f) {
        FileInputStream in = null;
        try {
            in = new FileInputStream(f);
            byte[] b = new byte[(int) Math.min(f.length(), 1024 * 1024)];
            int off = 0;
            while (off < b.length) {
                int n = in.read(b, off, b.length - off);
                if (n <= 0) break;
                off += n;
            }
            return new String(b, 0, off, "UTF-8");
        } catch (Exception e) {
            return null;
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    /** 给对话框用的多行文本 */
    public static String describe(Report r) {
        if (r == null || !r.hasProblem()) return "";
        StringBuilder sb = new StringBuilder();
        sb.append("这个移植版引用了 ").append(r.missing.size())
                .append(" 个引擎里没有的插件：\n");
        int i = 0;
        for (String n : r.missing) {
            if (i++ >= 8) {
                sb.append("…\n");
                break;
            }
            sb.append("· ").append(n);
            String h = r.hintOf(n);
            if (!h.isEmpty()) sb.append(" —— ").append(h);
            sb.append('\n');
        }
        if (!r.builtin.isEmpty()) {
            sb.append("\n内置支持的：").append(join(r.builtin)).append('\n');
        }
        sb.append("\n缺这些插件时，游戏可能黑屏、卡在开场、或存档界面报错。");
        return sb.toString();
    }

    private static String join(Set<String> s) {
        StringBuilder sb = new StringBuilder();
        for (String x : s) {
            if (sb.length() > 0) sb.append("、");
            sb.append(x);
        }
        return sb.toString();
    }

    public static String[] builtinNames() {
        return Arrays.copyOf(BUILTIN, BUILTIN.length);
    }
}
