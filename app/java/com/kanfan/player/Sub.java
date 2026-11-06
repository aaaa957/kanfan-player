package com.kanfan.player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 外挂字幕解析：SRT / ASS(SSA) / WebVTT。
 * 纯 Java，不依赖 Android，可直接在 JVM 上跑用例（tests/SubTest.java）。
 *
 * 说明：ASS 只取「时间 + 文本」，丢弃样式与排版（字体、定位、特效、卡拉OK）。
 * 对白型字幕够用，复杂特效字幕会退化成普通居中文本。
 */
public final class Sub {

    public static final class Cue {
        public final long start;
        public final long end;
        public final String text;

        Cue(long start, long end, String text) {
            this.start = start;
            this.end = end;
            this.text = text;
        }
    }

    private Sub() {
    }

    public static boolean isSubtitleName(String name) {
        if (name == null) return false;
        String n = name.toLowerCase(Locale.US);
        return n.endsWith(".srt") || n.endsWith(".ass") || n.endsWith(".ssa") || n.endsWith(".vtt");
    }

    /** 按扩展名/内容特征选择解析器 */
    public static List<Cue> parse(String content, String fileName) {
        List<Cue> out = new ArrayList<>();
        if (content == null || content.isEmpty()) return out;
        String c = content.startsWith("\uFEFF") ? content.substring(1) : content;
        String lower = fileName == null ? "" : fileName.toLowerCase(Locale.US);

        if (lower.endsWith(".ass") || lower.endsWith(".ssa")
                || lower.contains("script info") || c.contains("[Events]")) {
            out = parseAss(c);
        } else if (lower.endsWith(".vtt") || c.trim().startsWith("WEBVTT")) {
            out = parseSrt(c); // VTT 与 SRT 结构一致，时间戳解析器已兼容
        } else {
            out = parseSrt(c);
        }
        java.util.Collections.sort(out, new java.util.Comparator<Cue>() {
            @Override
            public int compare(Cue a, Cue b) {
                return a.start < b.start ? -1 : (a.start > b.start ? 1 : 0);
            }
        });
        return out;
    }

    // ---------------- SRT / VTT ----------------

    private static List<Cue> parseSrt(String c) {
        List<Cue> out = new ArrayList<>();
        String[] lines = c.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        int i = 0;
        while (i < lines.length) {
            String line = lines[i].trim();
            int arrow = line.indexOf("-->");
            if (arrow < 0) {
                i++;
                continue;
            }
            long start = parseTs(line.substring(0, arrow));
            String right = line.substring(arrow + 3).trim();
            int sp = right.indexOf(' ');
            if (sp > 0) right = right.substring(0, sp); // 去掉 "line:0%" 之类的参数
            long end = parseTs(right);

            StringBuilder sb = new StringBuilder();
            i++;
            while (i < lines.length && !lines[i].trim().isEmpty()) {
                String t = stripTags(lines[i].trim());
                if (!t.isEmpty()) {
                    if (sb.length() > 0) sb.append('\n');
                    sb.append(t);
                }
                i++;
            }
            if (end > start && sb.length() > 0) out.add(new Cue(start, end, sb.toString()));
        }
        return out;
    }

    // ---------------- ASS / SSA ----------------

    private static List<Cue> parseAss(String c) {
        List<Cue> out = new ArrayList<>();
        int iStart = 1, iEnd = 2, iText = -1, fields = 10;
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
                    else if (k.equals("end")) iEnd = i;
                    else if (k.equals("text")) iText = i;
                }
                continue;
            }
            if (!lower.startsWith("dialogue:")) continue;

            String body = line.substring(9);
            String[] p = body.split(",", fields);
            if (p.length <= Math.max(iStart, iEnd)) continue;
            if (iText < 0) iText = p.length - 1;
            if (iText >= p.length) continue;

            long start = parseTs(p[iStart]);
            long end = parseTs(p[iEnd]);
            String text = cleanAss(p[iText]);
            if (end > start && !text.isEmpty()) out.add(new Cue(start, end, text));
        }
        return out;
    }

    /** 去掉 {\\...} 覆盖标签，处理 \N 换行 */
    private static String cleanAss(String s) {
        String t = s.replaceAll("\\{[^}]*\\}", "");
        t = t.replace("\\N", "\n").replace("\\n", "\n").replace("\\h", " ");
        return t.trim();
    }

    private static String stripTags(String s) {
        String t = s.replaceAll("\\{[^}]*\\}", "");
        t = t.replaceAll("<[^>]*>", "");
        return t.trim();
    }

    // ---------------- 时间戳 ----------------

    /**
     * 支持 00:00:01,000 / 0:00:01.00(ASS 百分秒) / 00:01.000 / 12.5
     * 都把小数点后的部分当「秒的小数」，因此 ASS 的 .50 等于 500ms，语义一致。
     */
    public static long parseTs(String raw) {
        if (raw == null) return 0;
        String s = raw.trim().replace(',', '.');
        if (s.isEmpty()) return 0;
        String[] p = s.split(":");
        long h = 0, m = 0;
        double sec;
        try {
            if (p.length >= 3) {
                h = Long.parseLong(p[p.length - 3].trim());
                m = Long.parseLong(p[p.length - 2].trim());
                sec = Double.parseDouble(p[p.length - 1].trim());
            } else if (p.length == 2) {
                m = Long.parseLong(p[0].trim());
                sec = Double.parseDouble(p[1].trim());
            } else {
                sec = Double.parseDouble(p[0].trim());
            }
        } catch (Exception e) {
            return 0;
        }
        long ms = Math.round((h * 3600 + m * 60 + sec) * 1000);
        return Math.max(0, ms);
    }

    // ---------------- 查询 ----------------

    /** 返回 ms 时刻应显示的字幕下标，没有则 -1 */
    public static int indexAt(List<Cue> cues, long ms) {
        if (cues == null || cues.isEmpty()) return -1;
        int lo = 0, hi = cues.size() - 1, found = -1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (cues.get(mid).start <= ms) {
                found = mid;
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }
        if (found < 0) return -1;
        // 往前找一句仍在显示区间内的（处理时间重叠）
        for (int i = found; i >= 0 && i >= found - 6; i--) {
            Cue c = cues.get(i);
            if (c.start <= ms && ms < c.end) return i;
        }
        return -1;
    }
}
