import com.kanfan.player.Sub;

import java.util.List;

/**
 * 字幕解析用例（不依赖 Android）：
 *   javac -d out/tests app/java/com/kanfan/player/Sub.java tests/SubTest.java
 *   java -cp out/tests SubTest
 */
public class SubTest {

    private static int pass = 0, fail = 0;

    public static void main(String[] args) {
        // ---- SRT：基本两条、CRLF、序号 ----
        String srt = "1\r\n00:00:01,000 --> 00:00:03,500\r\n第一句\r\n\r\n"
                + "2\r\n00:00:04,000 --> 00:00:06,000\r\n第二句<i>斜体</i>\r\n第二行\r\n";
        List<Sub.Cue> a = Sub.parse(srt, "a.srt");
        check("srt/条数", a.size(), 2);
        check("srt/首条开始", a.get(0).start, 1000L);
        check("srt/首条结束", a.get(0).end, 3500L);
        check("srt/首条文本", a.get(0).text, "第一句");
        check("srt/去标签", a.get(1).text, "第二句斜体\n第二行");

        // ---- SRT：BOM + 时间轴带位置参数 ----
        String srt2 = "\uFEFF1\n00:00:02,000 --> 00:00:05,000 X1:10 X2:20\n带位置参数\n";
        List<Sub.Cue> b = Sub.parse(srt2, "b.srt");
        check("srt/BOM 条数", b.size(), 1);
        check("srt/位置参数不影响时间", b.get(0).end, 5000L);

        // ---- ASS：自定义 Format 顺序、\N 换行、去掉 {\an8} ----
        String ass = "[Script Info]\nScriptType: v4.00+\n\n[V4+ Styles]\n"
                + "Format: Name, Fontname\nStyle: Default,Arial\n\n[Events]\n"
                + "Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text\n"
                + "Dialogue: 0,0:00:02.00,0:00:04.50,Default,,0,0,0,,{\\an8}你好\\N世界\n"
                + "Dialogue: 0,0:00:05.10,0:00:07.00,Default,,0,0,0,,第二句, 带逗号\n";
        List<Sub.Cue> c = Sub.parse(ass, "c.ass");
        check("ass/条数", c.size(), 2);
        check("ass/首条开始", c.get(0).start, 2000L);
        check("ass/百分秒", c.get(0).end, 4500L);
        check("ass/换行与去标签", c.get(0).text, "你好\n世界");
        check("ass/文本里的逗号保留", c.get(1).text, "第二句, 带逗号");

        // ---- ASS：Format 顺序被打乱 ----
        String ass2 = "[Events]\nFormat: Start, End, Text, Style\n"
                + "Dialogue: 0:00:01.00,0:00:02.00,顺序打乱,Default\n";
        List<Sub.Cue> d = Sub.parse(ass2, "d.ass");
        check("ass/乱序 Format 条数", d.size(), 1);
        check("ass/乱序 Format 文本", d.get(0).text, "顺序打乱");
        check("ass/乱序 Format 开始", d.get(0).start, 1000L);

        // ---- VTT：省略小时 ----
        String vtt = "WEBVTT\n\n00:01.000 --> 00:03.000\n短格式时间\n";
        List<Sub.Cue> e = Sub.parse(vtt, "e.vtt");
        check("vtt/条数", e.size(), 1);
        check("vtt/省略小时", e.get(0).start, 1000L);
        check("vtt/结束", e.get(0).end, 3000L);

        // ---- 时间戳解析 ----
        check("ts/常规", Sub.parseTs("00:01:02,500"), 62500L);
        check("ts/ASS 百分秒", Sub.parseTs("0:00:01.20"), 1200L);
        check("ts/无小时", Sub.parseTs("02:03.000"), 123000L);
        check("ts/纯秒", Sub.parseTs("12.5"), 12500L);
        check("ts/脏数据", Sub.parseTs("xx:yy"), 0L);

        // ---- 时间轴查询 ----
        String q = "1\n00:00:01,000 --> 00:00:02,000\nA\n\n2\n00:00:03,000 --> 00:00:04,000\nB\n";
        List<Sub.Cue> qs = Sub.parse(q, "q.srt");
        check("at/区间前", Sub.indexAt(qs, 500), -1);
        check("at/区间首", Sub.indexAt(qs, 1000), 0);
        check("at/区间中", Sub.indexAt(qs, 1500), 0);
        check("at/右端点已过（结束时间不含）", Sub.indexAt(qs, 2000), -1);
        check("at/第二句", Sub.indexAt(qs, 3500), 1);
        check("at/远超结尾", Sub.indexAt(qs, 99999), -1);

        // ---- 时间重叠：取较新的那条 ----
        String ov = "1\n00:00:01,000 --> 00:00:05,000\n长句\n\n2\n00:00:02,000 --> 00:00:03,000\n短句\n";
        List<Sub.Cue> os = Sub.parse(ov, "o.srt");
        check("at/重叠优先新句", Sub.indexAt(os, 2500), 1);
        check("at/重叠回落到旧句", Sub.indexAt(os, 4000), 0);

        // ---- 边界 ----
        check("空文本", Sub.parse("", "x.srt").size(), 0);
        check("null 文本", Sub.parse(null, "x.srt").size(), 0);
        check("没有时间轴的文本", Sub.parse("这是一段普通文字\n没有任何时间轴", "x.srt").size(), 0);

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
