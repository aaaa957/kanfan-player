package com.kanfan.player;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 分卷压缩包的命名识别。
 *
 * 认这几种：
 *   新式 rar   name.part1.rar / name.part01.rar        （缺卷顺序按数字）
 *   旧式 rar   name.rar + name.r00 / name.r01 ...
 *   分卷 zip   name.z01 / name.z02 ... + name.zip      （.zip 是最后一卷）
 *   7-Zip 切卷 name.7z.001 / name.zip.001 / name.rar.001
 *
 * 这里只做「名字 → 卷组」，不碰文件系统；调用方把同目录的兄弟名传进来。
 */
public final class Vols {

    /** 一组分卷：names 按读取顺序排好，primary 是列表里要显示的那一卷 */
    public static final class Set {
        public final String[] names;
        public final String primary;
        /** 缺卷提示；没有缺卷返回 null */
        public final String gap;

        Set(String[] names, String primary, String gap) {
            this.names = names;
            this.primary = primary;
            this.gap = gap;
        }

        public boolean multi() {
            return names.length > 1;
        }

        public int indexOf(String name) {
            for (int i = 0; i < names.length; i++) {
                if (names[i].equalsIgnoreCase(name)) return i;
            }
            return -1;
        }
    }

    /** 单个名字在卷组里的位置 */
    private static final class Part {
        String key;
        long order;
    }

    private static final Pattern P_PART =
            Pattern.compile("^(.*)\\.part(\\d{1,4})\\.rar$", Pattern.CASE_INSENSITIVE);
    private static final Pattern P_Z =
            Pattern.compile("^(.*)\\.z(\\d{2})$", Pattern.CASE_INSENSITIVE);
    private static final Pattern P_R =
            Pattern.compile("^(.*)\\.r(\\d{2})$", Pattern.CASE_INSENSITIVE);
    private static final Pattern P_NUM =
            Pattern.compile("^(.*)\\.(\\d{3,4})$", Pattern.CASE_INSENSITIVE);

    /** 7-Zip 切卷只对这三种格式认 */
    private static boolean knownArchive(String lower) {
        return lower.endsWith(".zip") || lower.endsWith(".7z") || lower.endsWith(".rar");
    }

    /** 光看名字判断它在卷组里的位置；不是分卷返回 null */
    private static Part parse(String name) {
        if (name == null) return null;
        String lower = name.toLowerCase(Locale.US);

        Matcher m = P_PART.matcher(name);
        if (m.matches()) {
            Part p = new Part();
            p.key = "P|" + m.group(1).toLowerCase(Locale.US);
            p.order = parseInt(m.group(2));
            return p;
        }
        m = P_NUM.matcher(name);
        if (m.matches() && knownArchive(m.group(1).toLowerCase(Locale.US))) {
            Part p = new Part();
            p.key = "N|" + m.group(1).toLowerCase(Locale.US);
            p.order = parseInt(m.group(2));
            return p;
        }
        m = P_Z.matcher(name);
        if (m.matches()) {
            Part p = new Part();
            p.key = "Z|" + m.group(1).toLowerCase(Locale.US);
            p.order = parseInt(m.group(2));
            return p;
        }
        m = P_R.matcher(name);
        if (m.matches()) {
            Part p = new Part();
            p.key = "R|" + m.group(1).toLowerCase(Locale.US);
            p.order = parseInt(m.group(2));
            return p;
        }
        if (lower.endsWith(".zip") || lower.endsWith(".rar")) {
            // 主卷（name.zip / name.rar）：要等看到兄弟才知道是不是分卷
            Part p = new Part();
            p.key = (lower.endsWith(".zip") ? "Z|" : "R|") + lower.substring(0, lower.lastIndexOf('.'));
            p.order = lower.endsWith(".zip") ? 100000 : -1;
            return p;
        }
        return null;
    }

    /** 不是分卷就返回 null */
    public static String keyOf(String name) {
        Part p = parse(name);
        return p == null ? null : p.key;
    }

    /** 这一卷在不在卷组里，且不是主卷（列表里应该藏起来） */
    public static boolean secondary(String name, List<String> siblings) {
        Set s = resolve(name, siblings);
        if (s == null || s.names.length < 2) return false;
        return !s.primary.equalsIgnoreCase(name);
    }

    /**
     * 一次算出整个目录里该藏起来的卷名。
     * 逐个调 secondary() 是 O(n²)，一个目录几千个文件时会卡住扫描。
     */
    public static java.util.Set<String> secondaryNames(List<String> siblings) {
        java.util.Set<String> out = new java.util.HashSet<String>();
        if (siblings == null || siblings.isEmpty()) return out;
        java.util.Map<String, List<String>> groups = new java.util.HashMap<String, List<String>>();
        for (String n : siblings) {
            String k = keyOf(n);
            if (k == null) continue;
            List<String> g = groups.get(k);
            if (g == null) {
                g = new ArrayList<String>();
                groups.put(k, g);
            }
            g.add(n);
        }
        for (List<String> g : groups.values()) {
            if (g.size() < 2) continue;
            Set s = resolve(g.get(0), siblings);
            if (s == null) continue;
            for (String n : g) {
                if (!n.equalsIgnoreCase(s.primary)) out.add(n);
            }
        }
        return out;
    }

    /**
     * 把 name 归一到它所在的卷组。siblings 是同一个目录里的所有名字（含 name）。
     * 单文件也返回 size=1 的 Set；完全不像分卷时返回 size=1 的 Set。
     */
    public static Set resolve(String name, List<String> siblings) {
        if (name == null) return null;
        Part self = parse(name);
        if (self == null) return new Set(new String[]{name}, name, null);

        List<String[]> hit = new ArrayList<String[]>();   // {name, order}
        if (siblings != null) {
            for (String s : siblings) {
                if (s == null) continue;
                Part p = parse(s);
                if (p == null || !p.key.equals(self.key)) continue;
                hit.add(new String[]{s, Long.toString(p.order)});
            }
        }
        boolean hasSelf = false;
        for (String[] h : hit) {
            if (h[0].equalsIgnoreCase(name)) hasSelf = true;
        }
        if (!hasSelf) hit.add(new String[]{name, Long.toString(self.order)});

        Collections.sort(hit, new Comparator<String[]>() {
            @Override
            public int compare(String[] a, String[] b) {
                long x = Long.parseLong(a[1]);
                long y = Long.parseLong(b[1]);
                return x < y ? -1 : (x > y ? 1 : a[0].compareToIgnoreCase(b[0]));
            }
        });

        String[] names = new String[hit.size()];
        for (int i = 0; i < hit.size(); i++) names[i] = hit.get(i)[0];

        // 主卷：rar 新式取第一卷；旧式取 .rar；zip 取 .zip；数字切卷取 .001
        String primary = names[0];
        if (self.key.startsWith("Z|")) {
            for (String n : names) {
                if (n.toLowerCase(Locale.US).endsWith(".zip")) primary = n;
            }
        } else if (self.key.startsWith("R|")) {
            for (String n : names) {
                if (n.toLowerCase(Locale.US).endsWith(".rar")) primary = n;
            }
        }
        return new Set(names, primary, gapOf(self.key, names));
    }

    /** 卷号缺了就说一声，别让解码器抛个看不懂的错 */
    private static String gapOf(String key, String[] names) {
        if (names.length < 2) return null;
        int prev = -1;
        int first = -1;
        for (String n : names) {
            Part p = parse(n);
            if (p == null) continue;
            int cur;
            if (key.startsWith("Z|") && n.toLowerCase(Locale.US).endsWith(".zip")) continue; // 最后一卷
            if (key.startsWith("R|") && n.toLowerCase(Locale.US).endsWith(".rar")) continue;  // 第一卷
            cur = (int) p.order;
            if (first < 0) first = cur;
            if (prev >= 0 && cur != prev + 1) {
                return String.format(Locale.US, "缺第 %d 卷", prev + 1);
            }
            prev = cur;
        }
        // 从第 2 卷起才有，说明第一卷丢了
        if (first > 1 && !key.startsWith("R|")) return "缺第 1 卷";
        return null;
    }

    private static int parseInt(String s) {
        try {
            return Integer.parseInt(s);
        } catch (Exception e) {
            return 0;
        }
    }

    public static final int ZIP = 0, RAR = 1, SEVEN_Z = 2;

    /** 归档类型：看扩展名；分卷看的是「去掉卷号后的基名」 */
    public static int kindOf(String name) {
        if (name == null) return ZIP;
        String low = name.toLowerCase(Locale.US);
        if (low.endsWith(".rar")) return RAR;
        if (low.endsWith(".7z")) return SEVEN_Z;
        Matcher m = Pattern.compile("\\.\\d{3,4}$").matcher(low);
        if (m.find()) {
            String base = low.substring(0, m.start());
            if (base.endsWith(".rar")) return RAR;
            if (base.endsWith(".7z")) return SEVEN_Z;
        }
        return ZIP;
    }

    /** 显示用的名字：分卷组只留主卷那一行 */
    public static String displayName(String name, List<String> siblings) {
        Set s = resolve(name, siblings);
        return s == null ? name : s.primary;
    }

    private Vols() {
    }
}
