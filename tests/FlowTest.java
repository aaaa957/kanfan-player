import com.kanfan.player.Flow;

import java.util.ArrayList;
import java.util.List;

/**
 * 连续滚动窗口的算术用例（不依赖 Android）：
 *   javac -d out/tests app/java/com/kanfan/player/Flow.java tests/FlowTest.java
 *   java -cp out/tests FlowTest
 */
public class FlowTest {

    private static int pass = 0, fail = 0;

    public static void main(String[] args) {
        List<Flow.Win> wins = new ArrayList<Flow.Win>();
        Flow.Win a = new Flow.Win(0, 0);
        a.end = 100;
        Flow.Win b = new Flow.Win(1, 100);
        b.end = 250;
        wins.add(a);
        wins.add(b);

        // ---- 章节定位 ----
        check("首字符", Flow.chapterAt(wins, 0), 0);
        check("首章末尾前", Flow.chapterAt(wins, 99), 0);
        check("边界归下一章", Flow.chapterAt(wins, 100), 1);
        check("次章中间", Flow.chapterAt(wins, 200), 1);
        check("超出末尾归最后一章", Flow.chapterAt(wins, 9999), 1);
        check("空列表", Flow.chapterAt(new ArrayList<Flow.Win>(), 0), -1);

        // ---- 章内比例 ----
        check("章首比例", Flow.ratioAt(wins, 0), 0f);
        check("章中比例", Flow.ratioAt(wins, 50), 0.5f);
        check("下一章章首", Flow.ratioAt(wins, 100), 0f);
        check("超末尾按 1", Flow.ratioAt(wins, 9999), 1f);
        check("空列表比例", Flow.ratioAt(new ArrayList<Flow.Win>(), 0), 0f);

        // ---- 裁剪后平移 ----
        List<Flow.Win> w2 = new ArrayList<Flow.Win>();
        for (int i = 0; i < 3; i++) {
            Flow.Win w = new Flow.Win(i, i * 100);
            w.end = (i + 1) * 100;   // 窗口首尾相接
            w2.add(w);
        }
        int cut = Flow.trimTo(w2, 1);
        check("裁剪字符数", cut, 100);
        check("剩余窗口数", w2.size(), 2);
        check("剩余首章", w2.get(0).chapter, 1);
        check("剩余首章 start 归零", w2.get(0).start, 0);
        check("剩余首章 end 平移", w2.get(0).end, 100);
        check("剩余次章 start 平移", w2.get(1).start, 100);
        check("剩余次章 end 平移", w2.get(1).end, 200);

        // 裁剪后再定位，偏移应对应新坐标
        check("裁剪后定位/首章", Flow.chapterAt(w2, 10), 1);
        check("裁剪后定位/次章", Flow.chapterAt(w2, 160), 2);

        // ---- 无效裁剪 ----
        List<Flow.Win> w3 = new ArrayList<Flow.Win>();
        Flow.Win x = new Flow.Win(0, 0);
        x.end = 10;
        w3.add(x);
        check("keepFrom=0 不裁", Flow.trimTo(w3, 0), 0);
        check("越界不裁", Flow.trimTo(w3, 5), 0);
        check("不裁后仍在", w3.size(), 1);
        check("空列表裁剪", Flow.trimTo(new ArrayList<Flow.Win>(), 3), 0);

        System.out.println();
        System.out.println(fail == 0 ? ("全部通过：" + pass + " 项") : ("失败 " + fail + " 项 / 共 " + (pass + fail) + " 项"));
        if (fail != 0) System.exit(1);
    }

    private static void check(String label, Object actual, Object expect) {
        boolean ok = actual == null ? expect == null : actual.equals(expect);
        if (ok) pass++;
        else {
            fail++;
            System.out.println("  x " + label + "  expect=" + expect + " actual=" + actual);
        }
    }
}
