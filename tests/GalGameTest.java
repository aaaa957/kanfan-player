import com.kanfan.player.GalGame;

import java.util.ArrayList;
import java.util.List;

/** 移植版引擎识别 */
public class GalGameTest {

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

    static List<String[]> ls(String... kv) {
        List<String[]> out = new ArrayList<String[]>();
        for (String s : kv) {
            int p = s.indexOf('|');
            out.add(new String[]{s.substring(0, p), s.substring(p + 1)});
        }
        return out;
    }

    public static void main(String[] args) {
        // Tyrano：index.html + tyrano 目录
        GalGame.Info t = GalGame.detect(ls(
                "index.html|0", "tyrano|1", "data|1", "audio|1"));
        eq("tyrano 引擎", t.engine, GalGame.TYRANO);
        eq("tyrano 入口", t.entry, "index.html");
        eq("tyrano 标签", t.label, "TyranoBuilder");
        ok("tyrano 可运行", t.supported());

        // 只有 data 目录也算（有些导出没有 tyrano/，把 js 内联了）
        GalGame.Info t2 = GalGame.detect(ls("index.html|0", "data|1"));
        eq("只靠 data 也认", t2.engine, GalGame.TYRANO);

        // 只有 index.html 不算：普通网页/说明页不能被当成游戏
        eq("裸 index.html 不认", GalGame.detect(ls("index.html|0", "readme.txt|0")).engine,
                GalGame.NONE);

        // ONS
        GalGame.Info o = GalGame.detect(ls(
                "arc.nsa|0", "nscript.dat|0", "0.txt|0", "ons.txt|0"));
        eq("ons 引擎", o.engine, GalGame.ONS);
        ok("ons 暂不支持", !o.supported());
        eq("ons 标签", o.label, "ONS");
        ok("ons 命中数", o.hits >= 3);

        // 只有 0.txt 也认得出来
        eq("0.txt 也认", GalGame.detect(ls("0.txt|0")).engine, GalGame.ONS);

        // KRKR
        GalGame.Info k = GalGame.detect(ls("data.xp3|0", "patch.xp3|0", "startup.tjs|0"));
        eq("krkr 引擎", k.engine, GalGame.KRKR);
        eq("krkr 标签", k.label, "KiriKiri");
        ok("krkr 暂不支持", !k.supported());

        // KRKR 里带 index.html（说明页）也还是 KRKR
        eq("xp3 优先于裸 html",
                GalGame.detect(ls("index.html|0", "data.xp3|0")).engine, GalGame.KRKR);

        // 大小写
        eq("大小写不敏感", GalGame.detect(ls("ARC.NSA|0")).engine, GalGame.ONS);
        eq("XP3 大写", GalGame.detect(ls("DATA.XP3|0")).engine, GalGame.KRKR);

        // 目录里的 .nsa 不算（只看直属文件）
        eq("目录不参与命中", GalGame.detect(ls("backup.nsa|1")).engine, GalGame.NONE);

        // 空 / null
        eq("空目录", GalGame.detect(ls()).engine, GalGame.NONE);
        eq("null", GalGame.detect(null).engine, GalGame.NONE);

        // 普通视频文件夹
        eq("视频文件夹不误判",
                GalGame.detect(ls("01.mkv|0", "02.mkv|0", "sub|1")).engine, GalGame.NONE);

        System.out.println("GalGameTest: " + passed + " 通过, " + failed + " 失败");
        if (failed > 0) System.exit(1);
    }
}
