package com.kanfan.player;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * TXT 章节切分。纯 Java，可在 JVM 上直接跑用例（tests/SplitTest.java）。
 *
 * 认得的标题形式：
 *   第X章 / 第X节 / 第X回 / 第X卷 / 第X部 / 第X篇 / 第X集   （数字或中文数字）
 *   Chapter 12 / CHAPTER 12
 *   12、标题   12. 标题
 *   序章 / 楔子 / 引子 / 前言 / 后记 / 尾声 / 终章 / 番外
 * 认不出章节时按 3000 字左右切块，保证不会一口气吐出一整本书。
 */
public final class Split {

    public static final class Chapter {
        public final String title;
        public final int start;
        public final int end;

        Chapter(String title, int start, int end) {
            this.title = title;
            this.start = start;
            this.end = end;
        }
    }

    private static final Pattern CN_NUM = Pattern.compile(
            "^第\\s*[0-9零〇一二三四五六七八九十百千万两]{1,9}\\s*[章节回卷部篇集话話幕]");
    private static final Pattern EN_NUM = Pattern.compile("^(?i)chapter\\s*\\d{1,4}");
    private static final Pattern DIGIT = Pattern.compile("^[0-9]{1,4}\\s*[、.．]\\s*\\S");
    private static final Pattern SPECIAL = Pattern.compile(
            "^(序章|楔子|引子|前言|序言|后记|後記|尾声|尾聲|终章|終章|番外|完本感言|作者的话)");

    private Split() {
    }

    public static List<Chapter> chapters(String text) {
        List<Chapter> out = new ArrayList<Chapter>();
        if (text == null || text.isEmpty()) return out;

        List<int[]> marks = new ArrayList<int[]>();
        List<String> titles = new ArrayList<String>();

        int i = 0, n = text.length();
        while (i < n) {
            int lineEnd = text.indexOf('\n', i);
            if (lineEnd < 0) lineEnd = n;
            int end = lineEnd;
            if (end > i && text.charAt(end - 1) == '\r') end--;
            String line = text.substring(i, end).trim();
            if (isHeading(line)) {
                marks.add(new int[]{i});
                titles.add(line.replaceAll("\\s+", " "));
            }
            i = lineEnd + 1;
        }

        if (marks.size() >= 2) {
            int first = marks.get(0)[0];
            if (first > 0 && text.substring(0, first).trim().length() > 0) {
                out.add(new Chapter("开始", 0, first));
            }
            for (int k = 0; k < marks.size(); k++) {
                int s = marks.get(k)[0];
                int e = k + 1 < marks.size() ? marks.get(k + 1)[0] : n;
                out.add(new Chapter(titles.get(k), s, e));
            }
            return out;
        }

        // 没有章节标记，按长度切块
        int size = 3000;
        int idx = 0, part = 1;
        while (idx < n) {
            int e = Math.min(n, idx + size);
            if (e < n) {
                int nl = text.lastIndexOf('\n', e);
                if (nl > idx + size / 2) e = nl + 1;
            }
            out.add(new Chapter("第 " + part + " 节", idx, e));
            idx = e;
            part++;
        }
        return out;
    }

    private static boolean isHeading(String line) {
        if (line == null) return false;
        String t = line.trim();
        if (t.length() < 2 || t.length() > 30) return false;
        // 以句末标点结尾的通常是正文，不是标题
        char last = t.charAt(t.length() - 1);
        if ("。，,；;！!？".indexOf(last) >= 0) return false;
        return CN_NUM.matcher(t).find() || EN_NUM.matcher(t).find()
                || DIGIT.matcher(t).find() || SPECIAL.matcher(t).find();
    }
}
