package com.kanfan.player;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 纯逻辑：把「相对路径列表」折叠成某一层目录下的子目录与直属视频。
 * 不依赖 Android，便于在 JVM 上直接跑用例（见 tests/NavTest.java）。
 */
public final class Nav {

    public static final class Dir {
        public final String name;
        public final String rel;
        public int count;
        public long size;
        public int videoCount;
        public int bookCount;
        public int imageCount;

        Dir(String name, String rel) {
            this.name = name;
            this.rel = rel;
        }
    }

    private Nav() {
    }

    /**
     * rel 相对 base 的剩余部分：
     * base 之下返回剩余路径（同一层返回 ""），不在 base 之下返回 null。
     */
    public static String rest(String rel, String base) {
        String r = rel == null ? "" : rel;
        if (base == null || base.isEmpty()) return r;
        if (r.equals(base)) return "";
        if (r.startsWith(base + "/")) return r.substring(base.length() + 1);
        return null;
    }

    public static boolean under(String rel, String base) {
        return rest(rel, base) != null;
    }

    /** 直属子目录（按出现顺序，调用方自行排序） */
    public static List<Dir> subdirsOf(String base, String[] rels, long[] sizes) {
        return subdirsOf(base, rels, sizes, null);
    }

    /** kinds 为 null 时全部按「视频」计数；kind: 0=视频，1=书籍 */
    public static List<Dir> subdirsOf(String base, String[] rels, long[] sizes, int[] kinds) {
        Map<String, Dir> map = new LinkedHashMap<String, Dir>();
        if (rels == null) return new ArrayList<Dir>();
        for (int i = 0; i < rels.length; i++) {
            String rest = rest(rels[i], base);
            if (rest == null || rest.isEmpty()) continue;
            int slash = rest.indexOf('/');
            if (slash >= 0) rest = rest.substring(0, slash);
            if (rest.isEmpty()) continue;

            Dir d = map.get(rest);
            if (d == null) {
                d = new Dir(rest, (base == null || base.isEmpty()) ? rest : base + "/" + rest);
                map.put(rest, d);
            }
            long sz = (sizes != null && i < sizes.length) ? sizes[i] : 0;
            d.count++;
            d.size += sz;
            if (kinds != null && i < kinds.length && kinds[i] == 1) d.bookCount++;
            else if (kinds != null && i < kinds.length && kinds[i] == 2) d.imageCount++;
            else d.videoCount++;
        }
        return new ArrayList<Dir>(map.values());
    }

    /** 位于 base 这一层的视频下标 */
    public static int[] directOf(String base, String[] rels) {
        if (rels == null) return new int[0];
        List<Integer> hit = new ArrayList<Integer>();
        for (int i = 0; i < rels.length; i++) {
            String rest = rest(rels[i], base);
            if (rest != null && rest.isEmpty()) hit.add(i);
        }
        int[] out = new int[hit.size()];
        for (int i = 0; i < out.length; i++) out[i] = hit.get(i);
        return out;
    }
}
