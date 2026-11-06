import com.kanfan.player.Split;

import java.util.List;

/**
 * TXT 章节切分用例（不依赖 Android）：
 *   javac -d out/tests app/java/com/kanfan/player/Split.java tests/SplitTest.java
 *   java -cp out/tests SplitTest
 */
public class SplitTest {

    private static int pass = 0, fail = 0;

    public static void main(String[] args) {
        // ---- 标准中文标题 ----
        String t1 = "第一章 开始\n正文A\n还有一行\n第二章 相遇\n正文B\n";
        List<Split.Chapter> c1 = Split.chapters(t1);
        check("中文标题/章数", c1.size(), 2);
        check("中文标题/首章名", c1.get(0).title, "第一章 开始");
        check("中文标题/次章名", c1.get(1).title, "第二章 相遇");
        check("中文标题/首章内容", t1.substring(c1.get(0).start, c1.get(0).end).trim(),
                "第一章 开始\n正文A\n还有一行");
        check("中文标题/偏移连续", c1.get(0).end, c1.get(1).start);

        // ---- 首章之前有序言 ----
        String t2 = "这是序言\n随便写点\n第一章 开端\n正文\n第二章 发展\n正文\n";
        List<Split.Chapter> c2 = Split.chapters(t2);
        check("序言/章数", c2.size(), 3);
        check("序言/第一章名", c2.get(0).title, "开始");
        check("序言/从 0 开始", c2.get(0).start, 0);
        check("序言/内容", t2.substring(c2.get(0).start, c2.get(0).end).trim(), "这是序言\n随便写点");

        // ---- 各种标题形式 ----
        String t3 = "第12节 数字\nA\n第1卷 风起\nB\nChapter 3 英文\nC\n番外 小剧场\nD\n";
        List<Split.Chapter> c3 = Split.chapters(t3);
        check("多种标题/条数", c3.size(), 4);
        check("多种标题/第12节", c3.get(0).title, "第12节 数字");
        check("多种标题/第1卷", c3.get(1).title, "第1卷 风起");
        check("多种标题/Chapter", c3.get(2).title, "Chapter 3 英文");
        check("多种标题/番外", c3.get(3).title, "番外 小剧场");

        // ---- 不是标题的行不能被误判 ----
        String t4 = "第一章 开始\n他翻到第一章的内容，读了很久。\n第二天，他走了。\n这是一句很长的话，不应该被当成章节标题\n第二章 结束\n完\n";
        List<Split.Chapter> c4 = Split.chapters(t4);
        check("误判/章数", c4.size(), 2);
        check("误判/第二章名", c4.get(1).title, "第二章 结束");

        // ---- 没有章节标记：按长度切块 ----
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 400; i++) sb.append("这是一段正文，用来凑长度。\n");
        List<Split.Chapter> c5 = Split.chapters(sb.toString());
        check("切块/块数>1", c5.size() > 1, true);
        check("切块/首块名", c5.get(0).title, "第 1 节");
        int total = 0;
        for (Split.Chapter ch : c5) total += ch.end - ch.start;
        check("切块/覆盖全文", total, sb.length());

        // ---- 空输入 ----
        check("空文本", Split.chapters("").size(), 0);
        check("null", Split.chapters(null).size(), 0);
        check("只有空白", Split.chapters("   \n\n ").size(), 1);

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
