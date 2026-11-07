import com.kanfan.player.KrkrCompat;

import java.io.File;
import java.io.FileOutputStream;
import java.util.LinkedHashSet;
import java.util.Set;

/** KRKR 插件预检：脚本解析与内置/缺失分类 */
public class KrkrCompatTest {

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

    public static void main(String[] args) throws Exception {
        // 1) 常见写法都要能抠出来
        Set<String> s = KrkrCompat.linksIn(
                "Plugins.link(\"krmovie.dll\");\n"
                        + "Plugins . link ( 'menu.dll' ) ;\n"
                        + "// Plugins.link(\"commented.dll\")\n"
                        + "Plugins.link(\"sub/dir/layerExSave.dll\");");
        ok("抠到 krmovie", s.contains("krmovie"));
        ok("抠到 menu", s.contains("menu"));
        ok("斜杠路径只留文件名", s.contains("layerexsave"));
        ok("带空格的写法也认", s.size() >= 3);

        // 2) 归一化
        eq("大写去 dll", KrkrCompat.normalize("KRMovie.DLL"), "krmovie");
        eq("反斜杠路径", KrkrCompat.normalize("plugin\\menu.dll"), "menu");
        eq("tpm 也剥掉", KrkrCompat.normalize("extrans.tpm"), "extrans");
        eq("null", KrkrCompat.normalize(null), "");

        // 3) 分类：内置 vs 缺失
        Set<String> names = new LinkedHashSet<String>();
        names.add("wuvorbis");
        names.add("json");
        names.add("krmovie");
        names.add("menu");
        KrkrCompat.Report r = KrkrCompat.of(names);
        eq("内置数", r.builtin.size(), 3);
        eq("缺失数", r.missing.size(), 1);
        ok("有问题", r.hasProblem());
        ok("menu 有提示", !r.hintOf("menu").isEmpty());

        // 4) 只有内置时不算问题
        Set<String> only = new LinkedHashSet<String>();
        only.add("extrans");
        only.add("fstat");
        ok("全内置没问题", !KrkrCompat.of(only).hasProblem());

        // 4b) 从 Kirikiroid2 移植过来的插件，必须算内置（否则预检会白报缺失）
        // of() 只认 normalize 过的名字（scanDir 走的就是这条路），
        // 所以这里先用原始写法走一遍 normalize，别拿驼峰名直接喂进去
        Set<String> ported = new LinkedHashSet<String>();
        for (String raw : new String[]{"csvParser.dll", "saveStruct.dll", "getSample.dll",
                "getAbout.dll", "fftGraph.dll", "win32Dialog.dll"}) {
            ported.add(KrkrCompat.normalize(raw));
        }
        eq("归一后是 6 个", ported.size(), 6);
        KrkrCompat.Report pr = KrkrCompat.of(ported);
        eq("移植插件全算内置", pr.builtin.size(), 6);
        eq("移植插件不算缺失", pr.missing.size(), 0);
        ok("移植插件不报问题", !pr.hasProblem());

        // 5) 描述文本要列出缺失的，且不超过 8 条
        StringBuilder many = new StringBuilder();
        for (int i = 0; i < 12; i++) many.append("p").append(i);
        Set<String> lots = new LinkedHashSet<String>();
        for (int i = 0; i < 12; i++) lots.add("plug" + i);
        String desc = KrkrCompat.describe(KrkrCompat.of(lots));
        ok("描述含标题", desc.contains("引擎里没有的插件"));
        ok("描述有省略", desc.contains("…"));
        ok("空报告不产生文本", KrkrCompat.describe(KrkrCompat.of(only)).isEmpty());

        // 6) 扫目录：能读到 .tjs / .ks，忽略大文件与其他后缀
        File dir = new File("D:\\tmp\\krkrtest");
        dir.mkdirs();
        write(new File(dir, "startup.tjs"),
                "Plugins.link(\"krmovie.dll\");\nPlugins.link(\"wuvorbis.dll\");\n"
                        + "Plugins.link(\"menu.dll\");");
        write(new File(dir, "scene.ks"), "@bg storage=\"bg01\"");
        write(new File(dir, "readme.md"), "Plugins.link(\"ignored.dll\")");
        KrkrCompat.Report d = KrkrCompat.scanDir(dir);
        ok("扫到了脚本", d.scanned);
        ok("krmovie 现在算内建", d.builtin.contains("krmovie"));
        ok("krmovie 不再报缺失", !d.missing.contains("krmovie"));
        ok("menu 在缺失里", d.missing.contains("menu"));
        ok("wuvorbis 在内置里", d.builtin.contains("wuvorbis"));
        ok("不扫 md", !d.missing.contains("ignored"));

        // 7) 空目录不崩
        File empty = new File(dir, "empty");
        empty.mkdirs();
        KrkrCompat.Report e = KrkrCompat.scanDir(empty);
        ok("空目录无问题", !e.hasProblem());

        System.out.println("KrkrCompatTest: " + passed + " 通过, " + failed + " 失败");
        if (failed > 0) System.exit(1);
    }

    static void write(File f, String s) throws Exception {
        FileOutputStream o = new FileOutputStream(f);
        o.write(s.getBytes("UTF-8"));
        o.close();
    }
}
