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
}
