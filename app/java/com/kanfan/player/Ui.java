package com.kanfan.player;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.View;

import java.util.Comparator;
import java.util.Locale;

public class Ui {

    public static int dp(Context c, float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                c.getResources().getDisplayMetrics());
    }

    public static GradientDrawable round(Context c, int color, float radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.RECTANGLE);
        d.setColor(color);
        d.setCornerRadius(dp(c, radiusDp));
        return d;
    }

    public static GradientDrawable roundStroke(Context c, int color, float radiusDp,
                                               int strokeColor, float strokeDp) {
        GradientDrawable d = round(c, color, radiusDp);
        d.setStroke(dp(c, strokeDp), strokeColor);
        return d;
    }

    public static GradientDrawable circle(Context c, int color) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(color);
        return d;
    }

    public static String fmtDur(long ms) {
        if (ms <= 0) return "--:--";
        long s = ms / 1000;
        long h = s / 3600;
        long m = (s % 3600) / 60;
        long sec = s % 60;
        if (h > 0) return String.format(Locale.US, "%d:%02d:%02d", h, m, sec);
        return String.format(Locale.US, "%02d:%02d", m, sec);
    }

    public static String fmtSize(long b) {
        if (b <= 0) return "";
        double gb = b / 1073741824.0;
        if (gb >= 1.0) return String.format(Locale.US, "%.2f GB", gb);
        return String.format(Locale.US, "%.0f MB", b / 1048576.0);
    }

    /** 去掉扩展名，把 _ . 之类整理成空格，便于阅读 */
    public static String prettyTitle(String fileName) {
        if (fileName == null) return "";
        String t = fileName;
        int dot = t.lastIndexOf('.');
        if (dot > 0) t = t.substring(0, dot);
        t = t.replace('_', ' ');
        return t.trim();
    }

    /**
     * 取「剧集前缀」：把结尾的集数（01 / 12.5 / 第03集 / EP05）剥掉，
     * 用来判断哪些文件属于同一部作品，避免同一文件夹里不同作品串播。
     */
    public static String seriesKey(String title) {
        if (title == null) return "";
        String t = title.trim();
        String prev;
        do {
            prev = t;
            // 结尾的纯数字或小数（12 / 12.5 / 01）
            t = t.replaceAll("[\\s\\-_]*[（(]?\\d+(?:\\.\\d+)?[)）]?\\s*$", "");
            // 结尾的「第X集 / 第X话 / EP05 / E05」
            t = t.replaceAll("[\\s\\-_]*(?:第\\s*\\d+(?:\\.\\d+)?\\s*[集话話]|(?i:ep|e)\\s*\\d+)\\s*$", "");
        } while (!t.equals(prev));
        t = t.trim();
        if (t.length() < 2) return title.trim();
        return t;
    }

    public static void setVisible(View v, boolean visible) {
        if (v == null) return;
        v.setVisibility(visible ? View.VISIBLE : View.GONE);
    }

    public static int alpha(int color, float a) {
        int al = (int) (255 * a);
        return Color.argb(al, Color.red(color), Color.green(color), Color.blue(color));
    }

    /** 自然排序：把连续数字当成数值比较，"第2集" 排在 "第10集" 前面 */
    public static final Comparator<String> NATURAL = new Comparator<String>() {
        @Override
        public int compare(String a, String b) {
            if (a == null) a = "";
            if (b == null) b = "";
            int i = 0, j = 0;
            while (i < a.length() && j < b.length()) {
                char ca = a.charAt(i), cb = b.charAt(j);
                if (Character.isDigit(ca) && Character.isDigit(cb)) {
                    int si = i, sj = j;
                    while (si < a.length() && Character.isDigit(a.charAt(si))) si++;
                    while (sj < b.length() && Character.isDigit(b.charAt(sj))) sj++;
                    long na = Long.parseLong(a.substring(i, si));
                    long nb = Long.parseLong(b.substring(j, sj));
                    if (na != nb) return na < nb ? -1 : 1;
                    i = si;
                    j = sj;
                } else {
                    if (ca != cb) return ca < cb ? -1 : 1;
                    i++;
                    j++;
                }
            }
            return Integer.compare(a.length() - i, b.length() - j);
        }
    };
}
