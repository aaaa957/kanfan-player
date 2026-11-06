package com.kanfan.player;

import android.content.Context;
import android.content.SharedPreferences;

public class Store {
    private static final String PREF = "kanfan_store";

    private static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    public static void savePos(Context c, String key, long ms) {
        if (key == null || key.length() == 0) return;
        if (ms < 3000) {
            sp(c).edit().remove("p_" + key).apply();
            return;
        }
        sp(c).edit().putLong("p_" + key, ms).apply();
    }

    public static long getPos(Context c, String key) {
        if (key == null) return 0L;
        return sp(c).getLong("p_" + key, 0L);
    }

    public static void clearPos(Context c, String key) {
        if (key == null) return;
        sp(c).edit().remove("p_" + key).apply();
    }

    // ---------------- 阅读器 ----------------

    /** 阅读进度：章节下标 + 章内滚动比例 */
    public static void saveBookPos(Context c, String key, int chapter, float ratio) {
        if (key == null || key.length() == 0) return;
        sp(c).edit().putString("b_" + key, chapter + ":" + ratio).apply();
    }

    /** 返回 {章节下标, 千分比}，没有记录返回 null */
    public static int[] getBookPos(Context c, String key) {
        if (key == null) return null;
        String raw = sp(c).getString("b_" + key, null);
        if (raw == null) return null;
        int p = raw.indexOf(':');
        if (p <= 0) return null;
        try {
            int ch = Integer.parseInt(raw.substring(0, p));
            float ratio = Float.parseFloat(raw.substring(p + 1));
            return new int[]{ch, Math.round(ratio * 1000)};
        } catch (Exception e) {
            return null;
        }
    }

    public static int getFontSize(Context c) {
        return sp(c).getInt("r_font", 18);
    }

    public static void setFontSize(Context c, int sp) {
        sp(c).edit().putInt("r_font", sp).apply();
    }

    /** 0 深色 / 1 米黄 / 2 浅色 */
    public static int getTheme(Context c) {
        return sp(c).getInt("r_theme", 0);
    }

    public static void setTheme(Context c, int theme) {
        sp(c).edit().putInt("r_theme", theme).apply();
    }

    /** 阅读方式：0 分页 / 1 连续滚动（默认滚动） */
    public static int getReadMode(Context c) {
        return sp(c).getInt("r_mode", 1);
    }

    public static void setReadMode(Context c, int mode) {
        sp(c).edit().putInt("r_mode", mode).apply();
    }

    /** 看图方式：0 左右切换（默认）/ 1 连续下滑 */
    public static int getImgMode(Context c) {
        return sp(c).getInt("i_mode", 0);
    }

    public static void setImgMode(Context c, int mode) {
        sp(c).edit().putInt("i_mode", mode).apply();
    }

    /** PDF 读到第几页（从 1 开始；0 表示没记录） */
    public static void savePdfPage(Context c, String key, int page) {
        if (key == null || key.length() == 0) return;
        if (page <= 1) {
            sp(c).edit().remove("d_" + key).apply();
            return;
        }
        sp(c).edit().putInt("d_" + key, page).apply();
    }

    public static int getPdfPage(Context c, String key) {
        if (key == null) return 0;
        return sp(c).getInt("d_" + key, 0);
    }
}
