import com.kanfan.player.Nav;

import java.util.List;

/**
 * Nav 的单元验证：不依赖 Android，可直接在 JVM 上跑。
 *
 * 编译运行：
 *   javac -d out/tests app/java/com/kanfan/player/Nav.java tests/NavTest.java
 *   java -cp out/tests NavTest
 */
public class NavTest {

    private static int pass = 0, fail = 0;

    public static void main(String[] args) {
        // 1) 全部在根目录：没有子目录，直属视频 3 个
        String[] flat = {"", "", ""};
        long[] flatSize = {100, 200, 300};
        check("扁平/子目录数", Nav.subdirsOf("", flat, flatSize).size(), 0);
        check("扁平/直属数", Nav.directOf("", flat).length, 3);

        // 2) 一层子目录：A 两个、B 一个、根目录一个
        String[] one = {"", "A", "A", "B"};
        long[] oneSize = {10, 20, 30, 40};
        List<Nav.Dir> d = Nav.subdirsOf("", one, oneSize);
        check("一层/子目录数", d.size(), 2);
        check("一层/第一个名", d.get(0).name, "A");
        check("一层/A 计数", d.get(0).count, 2);
        check("一层/A 体积", (int) d.get(0).size, 50);
        check("一层/A 相对路径", d.get(0).rel, "A");
        check("一层/直属数", Nav.directOf("", one).length, 1);

        // 3) 前缀陷阱："AB" 不在 "A" 之下
        check("边界/AB 不在 A 下", Nav.under("AB", "A"), false);
        check("边界/同层算在内", Nav.under("A", "A"), true);
        check("边界/子层算在内", Nav.under("A/B", "A"), true);

        // 4) 深层：base="A"，A/B 与 A/B/C 都归到子目录 B
        String[] deep = {"A", "A/B", "A/B/C", "AB"};
        long[] zero = {0, 0, 0, 0};
        List<Nav.Dir> d2 = Nav.subdirsOf("A", deep, zero);
        check("深层/子目录数", d2.size(), 1);
        check("深层/子目录名", d2.get(0).name, "B");
        check("深层/B 计数（含更深的 C）", d2.get(0).count, 2);
        check("深层/B 相对路径", d2.get(0).rel, "A/B");
        check("深层/直属数", Nav.directOf("A", deep).length, 1);

        // 5) 两层下钻：base="A/B"
        List<Nav.Dir> d3 = Nav.subdirsOf("A/B", deep, zero);
        check("下钻/子目录名", d3.get(0).name, "C");
        check("下钻/C 相对路径", d3.get(0).rel, "A/B/C");
        check("下钻/直属数", Nav.directOf("A/B", deep).length, 1);
        check("下钻/再往下的直属数", Nav.directOf("A/B/C", deep).length, 1);

        // 7) 视频与书籍分开计数
        String[] mixed = {"", "A", "A"};
        long[] mixedSize = {10, 20, 30};
        int[] mixedKind = {0, 0, 1};
        List<Nav.Dir> d4 = Nav.subdirsOf("", mixed, mixedSize, mixedKind);
        check("分类/A 总数", d4.get(0).count, 2);
        check("分类/A 视频数", d4.get(0).videoCount, 1);
        check("分类/A 书籍数", d4.get(0).bookCount, 1);
        check("分类/A 体积", (int) d4.get(0).size, 50);

        List<Nav.Dir> d5 = Nav.subdirsOf("", mixed, mixedSize);
        check("不传 kinds/视频数", d5.get(0).videoCount, 2);
        check("不传 kinds/书籍数", d5.get(0).bookCount, 0);

        // 图片单独计数（kind=2）
        int[] threeKinds = {0, 1, 2};
        List<Nav.Dir> d6 = Nav.subdirsOf("", new String[]{"A", "A", "A"},
                new long[]{1, 2, 3}, threeKinds);
        check("三类/A 视频", d6.get(0).videoCount, 1);
        check("三类/A 书籍", d6.get(0).bookCount, 1);
        check("三类/A 图片", d6.get(0).imageCount, 1);

        // 压缩包单独计数（kind=3），且不能混进视频数
        int[] fourKinds = {0, 1, 2, 3, 3};
        List<Nav.Dir> d7 = Nav.subdirsOf("", new String[]{"A", "A", "A", "A", "A"},
                new long[]{1, 2, 3, 4, 5}, fourKinds);
        check("四类/A 视频", d7.get(0).videoCount, 1);
        check("四类/A 书籍", d7.get(0).bookCount, 1);
        check("四类/A 图片", d7.get(0).imageCount, 1);
        check("四类/A 压缩包", d7.get(0).archiveCount, 2);
        check("四类/A 总数", d7.get(0).count, 5);

        // 6) 空输入不炸
        check("空输入/子目录", Nav.subdirsOf("", new String[0], new long[0]).size(), 0);
        check("空输入/直属", Nav.directOf("", null).length, 0);
        check("null/rest", Nav.rest(null, null), "");

        System.out.println();
        System.out.println(fail == 0 ? ("全部通过：" + pass + " 项") : ("失败 " + fail + " 项 / 共 " + (pass + fail) + " 项"));
        if (fail != 0) System.exit(1);
    }

    private static void check(String label, Object actual, Object expect) {
        boolean ok = actual == null ? expect == null : actual.equals(expect);
        if (ok) {
            pass++;
        } else {
            fail++;
            System.out.println("  ✗ " + label + "  期望=" + expect + " 实际=" + actual);
        }
    }
}
