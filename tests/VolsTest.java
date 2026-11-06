import com.kanfan.player.Vols;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** 分卷命名的识别与排序 */
public class VolsTest {

    private static int passed = 0;
    private static int failed = 0;

    static void ok(String name, boolean v) {
        if (v) passed++;
        else {
            failed++;
            System.out.println("  ✗ " + name);
        }
    }

    static void eq(String name, Object a, Object b) {
        boolean same = (a == null) ? b == null : a.equals(b);
        if (same) passed++;
        else {
            failed++;
            System.out.println("  ✗ " + name + "  期望 " + b + " 实际 " + a);
        }
    }

    static List<String> ls(String... s) {
        return new ArrayList<String>(Arrays.asList(s));
    }

    public static void main(String[] args) {
        // ---- 新式 rar：任意一卷都能归到整组 ----
        List<String> rar = ls("刀剑.part1.rar", "刀剑.part2.rar", "刀剑.part3.rar", "别的.mp4");
        Vols.Set s = Vols.resolve("刀剑.part2.rar", rar);
        eq("part2 → 卷数", s.names.length, 3);
        eq("part2 → 第一卷", s.names[0], "刀剑.part1.rar");
        eq("part2 → 显示名", s.primary, "刀剑.part1.rar");
        eq("part2 → 缺卷", s.gap, null);
        ok("part2 不是主卷", Vols.secondary("刀剑.part2.rar", rar));
        ok("part1 是主卷", !Vols.secondary("刀剑.part1.rar", rar));

        // 数字超过 9 也要按数值排，不能按字符串
        List<String> rar10 = ls("a.part1.rar", "a.part2.rar", "a.part10.rar");
        Vols.Set s10 = Vols.resolve("a.part10.rar", rar10);
        eq("part10 排在最后", s10.names[2], "a.part10.rar");
        eq("part10 缺卷提示", s10.gap, "缺第 3 卷");

        // 少的是第一卷
        List<String> no1 = ls("a.part2.rar", "a.part3.rar");
        eq("缺第一卷", Vols.resolve("a.part2.rar", no1).gap, "缺第 1 卷");
        List<String> zno1 = ls("c.z02", "c.z03", "c.zip");
        eq("zip 缺第一卷", Vols.resolve("c.zip", zno1).gap, "缺第 1 卷");
        List<String> nno1 = ls("d.7z.002", "d.7z.003");
        eq("7z 缺第一卷", Vols.resolve("d.7z.002", nno1).gap, "缺第 1 卷");

        // ---- 旧式 rar：name.rar + name.r00 ----
        List<String> old = ls("b.rar", "b.r00", "b.r01");
        Vols.Set so = Vols.resolve("b.r01", old);
        eq("旧式顺序 0", so.names[0], "b.rar");
        eq("旧式顺序 2", so.names[2], "b.r01");
        eq("旧式主卷", so.primary, "b.rar");
        ok("旧式 r00 被藏", Vols.secondary("b.r00", old));

        // ---- 分卷 zip：z01 先读，.zip 最后读 ----
        List<String> z = ls("c.zip", "c.z01", "c.z02");
        Vols.Set sz = Vols.resolve("c.zip", z);
        eq("zip 卷数", sz.names.length, 3);
        eq("zip 先读 z01", sz.names[0], "c.z01");
        eq("zip 最后读 .zip", sz.names[2], "c.zip");
        eq("zip 显示 .zip", sz.primary, "c.zip");
        ok("z01 被藏", Vols.secondary("c.z01", z));

        // ---- 7-Zip 切卷 ----
        List<String> num = ls("d.7z.001", "d.7z.002", "d.7z.003");
        Vols.Set sn = Vols.resolve("d.7z.002", num);
        eq("7z 卷数", sn.names.length, 3);
        eq("7z 第一卷", sn.names[0], "d.7z.001");
        eq("7z 主卷", sn.primary, "d.7z.001");

        List<String> znum = ls("e.zip.001", "e.zip.002");
        eq("zip.001 归组", Vols.resolve("e.zip.002", znum).names.length, 2);
        List<String> rnum = ls("f.rar.001", "f.rar.002");
        eq("rar.001 归组", Vols.resolve("f.rar.001", rnum).names.length, 2);

        // ---- 单文件不能被误判 ----
        List<String> plain = ls("movie.mp4", "book.epub", "x.zip");
        Vols.Set sp = Vols.resolve("movie.mp4", plain);
        eq("普通文件卷数", sp.names.length, 1);
        eq("普通文件就是自己", sp.names[0], "movie.mp4");
        ok("普通文件不算分卷", !Vols.secondary("movie.mp4", plain));

        Vols.Set single = Vols.resolve("x.zip", plain);
        eq("单 zip 卷数", single.names.length, 1);
        eq("单 zip 显示", single.primary, "x.zip");

        // 000 这种编号不能当分卷（不认识的基名）
        List<String> odd = ls("photo.001", "photo.002");
        eq("未知基名不归组", Vols.resolve("photo.001", odd).names.length, 1);

        // ---- 大小写 ----
        List<String> up = ls("G.PART1.RAR", "G.PART2.RAR");
        Vols.Set su = Vols.resolve("g.part1.rar", up);
        eq("大小写不敏感", su.names.length, 2);

        // ---- 不传兄弟也不能崩 ----
        Vols.Set alone = Vols.resolve("h.part3.rar", null);
        eq("无兄弟仍返回自己", alone.names.length, 1);

        System.out.println("VolsTest: " + passed + " 通过, " + failed + " 失败");
        if (failed > 0) System.exit(1);
    }
}
