import com.kanfan.player.Danmaku;

import java.util.List;

/**
 * 弹幕解析用例（不依赖 Android）：
 *   javac -d out/tests app/java/com/kanfan/player/Sub.java app/java/com/kanfan/player/Danmaku.java tests/DanmakuTest.java
 *   java -cp out/tests DanmakuTest
 */
public class DanmakuTest {

    private static int pass = 0, fail = 0;

    public static void main(String[] args) {
        // ---- B 站 XML ----
        String xml = "<?xml version=\"1.0\"?><i>"
                + "<d p=\"1.50,1,25,16777215,1600000000,0,abc,1\">第一条</d>"
                + "<d p=\"2.00,5,25,16711680,1600000000,0,abc,2\">顶部红色 &amp; 转义</d>"
                + "<d p=\"3.00,4,25,255,1600000000,0,abc,3\">底部蓝</d>"
                + "</i>";
        List<Danmaku.Item> a = Danmaku.parse(xml, "a.xml");
        check("xml/条数", a.size(), 3);
        check("xml/时间换算", a.get(0).time, 1500L);
        check("xml/滚动模式", a.get(0).mode, Danmaku.SCROLL);
        check("xml/白色", a.get(0).color, 0xFFFFFFFF);
        check("xml/顶部模式", a.get(1).mode, Danmaku.TOP);
        check("xml/颜色 16711680=红", a.get(1).color, 0xFFFF0000);
        check("xml/实体反转义", a.get(1).text, "顶部红色 & 转义");
        check("xml/底部模式", a.get(2).mode, Danmaku.BOTTOM);
        check("xml/颜色 255=蓝", a.get(2).color, 0xFF0000FF);

        // ---- 未知 mode 退回滚动 ----
        String xml2 = "<i><d p=\"1.00,8,25,16777215,0,0,x,1\">未知模式</d></i>";
        check("xml/未知模式归为滚动", Danmaku.parse(xml2, "b.xml").get(0).mode, Danmaku.SCROLL);

        // ---- ASS 弹幕 ----
        String ass = "[Script Info]\nScriptType: v4.00+\n[Events]\n"
                + "Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text\n"
                + "Dialogue: 0,0:00:01.00,0:00:04.00,Default,,0,0,0,,{\\c&H0000FF&}红色弹幕\n"
                + "Dialogue: 0,0:00:02.00,0:00:05.00,Default,,0,0,0,,普通\\h弹幕\n";
        List<Danmaku.Item> b = Danmaku.parse(ass, "c.ass");
        check("ass/条数", b.size(), 2);
        check("ass/开始时间", b.get(0).time, 1000L);
        check("ass/颜色 BBGGRR 转 RGB", b.get(0).color, 0xFFFF0000);
        check("ass/去特效标签", b.get(0).text, "红色弹幕");
        check("ass/\\h 转空格", b.get(1).text, "普通 弹幕");

        // ---- 排序 ----
        String unsorted = "<i>"
                + "<d p=\"5.0,1,25,16777215,0,0,x,1\">e</d>"
                + "<d p=\"1.0,1,25,16777215,0,0,x,2\">a</d>"
                + "<d p=\"3.0,1,25,16777215,0,0,x,3\">c</d></i>";
        List<Danmaku.Item> c = Danmaku.parse(unsorted, "d.xml");
        check("排序/首条最早", c.get(0).text, "a");
        check("排序/末条最晚", c.get(2).text, "e");

        // ---- 边界 ----
        check("空文本", Danmaku.parse("", "x.xml").size(), 0);
        check("null", Danmaku.parse(null, "x.xml").size(), 0);
        check("无关文本", Danmaku.parse("这只是一段说明，没有弹幕", "x.xml").size(), 0);
        check("坏行被跳过", Danmaku.parse("<i><d p=\"abc,1,25,0,0,0,x,1\">坏</d></i>", "y.xml").size(), 0);
        check("空文本弹幕被跳过", Danmaku.parse("<i><d p=\"1.0,1,25,0,0,0,x,1\">   </d></i>", "z.xml").size(), 0);

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
