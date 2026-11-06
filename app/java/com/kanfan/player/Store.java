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

    /** 阅读方式：0 分页 / 1 连续滚动 */
    public static int getReadMode(Context c) {
        return sp(c).getInt("r_mode", 0);
    }

    public static void setReadMode(Context c, int mode) {
        sp(c).edit().putInt("r_mode", mode).apply();
    }
}
