package com.kanfan.player;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 外挂弹幕解析：B 站 XML（&lt;d p="..."&gt;）与 ASS 弹幕。
 * 纯 Java，不依赖 Android。
 *
 * mode：1 = 滚动，4 = 底部固定，5 = 顶部固定。
 */
public final class Danmaku {

    public static final int SCROLL = 1, BOTTOM = 4, TOP = 5;

    public static final class Item {
        public final long time;
        public final int mode;
        public final int color;
        public final String text;

        /** 渲染时由 DanmakuView 分配，-1 表示本帧不显示 */
        public int lane = -1;

        public Item(long time, int mode, int color, String text) {
            this.time = time;
            this.mode = mode;
            this.color = color;
            this.text = text;
        }
    }

    private static final Pattern XML_D = Pattern.compile(
            "<d\\s+p=\"([^\"]*)\"[^>]*>(.*?)</d>", Pattern.DOTALL);
    private static final Pattern ASS_COLOR = Pattern.compile("\\\\c&H([0-9A-Fa-f]{6})&");

    private Danmaku() {
    }

    public static boolean isDanmakuName(String name) {
        if (name == null) return false;
        String n = name.toLowerCase(Locale.US);
        return n.endsWith(".xml") || n.endsWith(".ass") || n.endsWith(".json");
    }

    public static List<Item> parse(String content, String fileName) {
        List<Item> out = new ArrayList<>();
        if (content == null || content.isEmpty()) return out;
        String c = content.startsWith("\uFEFF") ? content.substring(1) : content;
        String lower = fileName == null ? "" : fileName.toLowerCase(Locale.US);

        if (c.contains("[Events]") || lower.endsWith(".ass") || lower.endsWith(".ssa")) {
            out = parseAss(c);
        } else if (c.contains("<d ") || c.contains("<d\t")) {
            out = parseXml(c);
        } else if (c.trim().startsWith("<") && c.contains("<d")) {
            out = parseXml(c);
        }

        Collections.sort(out, new Comparator<Item>() {
            @Override
            public int compare(Item a, Item b) {
                return a.time < b.time ? -1 : (a.time > b.time ? 1 : 0);
            }
        });
        return out;
    }

    // ---------------- B 站 XML ----------------

    private static List<Item> parseXml(String c) {
        List<Item> out = new ArrayList<>();
        Matcher m = XML_D.matcher(c);
        while (m.find()) {
            String p = m.group(1);
            String text = unescape(m.group(2)).trim();
            if (text.isEmpty()) continue;
            String[] f = p.split(",");
            if (f.length < 4) continue;
            double sec;
            try {
                sec = Double.parseDouble(f[0].trim());
            } catch (Exception e) {
                continue;
            }
            int mode;
            try {
                mode = Integer.parseInt(f[1].trim());
            } catch (Exception e) {
                mode = SCROLL;
            }
            if (mode != SCROLL && mode != BOTTOM && mode != TOP) mode = SCROLL;

            int color = 0xFFFFFFFF;
            try {
                int rgb = Integer.parseInt(f[3].trim());
                color = 0xFF000000 | (rgb & 0xFFFFFF);
            } catch (Exception ignored) {
            }
            out.add(new Item(Math.round(sec * 1000), mode, color, text));
        }
        return out;
    }

    private static String unescape(String s) {
        return s.replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"").replace("&apos;", "'")
                .replace("&#39;", "'").replace("&amp;", "&");
    }

    // ---------------- ASS 弹幕 ----------------

    private static List<Item> parseAss(String c) {
        List<Item> out = new ArrayList<>();
        int iStart = 1, iText = -1, fields = 10;
        boolean inEvents = false;

        for (String raw : c.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1)) {
            String line = raw.trim();
            if (line.isEmpty()) continue;
            if (line.startsWith("[")) {
                inEvents = line.toLowerCase(Locale.US).contains("events");
                continue;
            }
            if (!inEvents) continue;

            String lower = line.toLowerCase(Locale.US);
            if (lower.startsWith("format:")) {
                String[] f = line.substring(7).split(",");
                fields = f.length;
                for (int i = 0; i < f.length; i++) {
                    String k = f[i].trim().toLowerCase(Locale.US);
                    if (k.equals("start")) iStart = i;
                    else if (k.equals("text")) iText = i;
                }
                continue;
            }
            if (!lower.startsWith("dialogue:")) continue;

            String[] p = line.substring(9).split(",", fields);
            if (p.length <= iStart) continue;
            if (iText < 0) iText = p.length - 1;
            if (iText >= p.length) continue;

            long start = Sub.parseTs(p[iStart]);
            String body = p[iText];
            int color = 0xFFFFFFFF;
            Matcher cm = ASS_COLOR.matcher(body);
            if (cm.find()) {
                String hex = cm.group(1); // BBGGRR
                try {
                    int b = Integer.parseInt(hex.substring(0, 2), 16);
                    int g = Integer.parseInt(hex.substring(2, 4), 16);
                    int r = Integer.parseInt(hex.substring(4, 6), 16);
                    color = 0xFF000000 | (r << 16) | (g << 8) | b;
                } catch (Exception ignored) {
                }
            }
            String text = body.replaceAll("\\{[^}]*\\}", "")
                    .replace("\\N", " ").replace("\\n", " ").replace("\\h", " ").trim();
            if (text.isEmpty()) continue;
            out.add(new Item(start, SCROLL, color, text));
        }
        return out;
    }
}
