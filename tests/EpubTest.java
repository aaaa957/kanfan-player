import com.kanfan.player.Epub;

import java.io.File;
import java.io.FileOutputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * EPUB 解析用例（不依赖 Android）：
 *   javac -d out/tests app/java/com/kanfan/player/Epub.java tests/EpubTest.java
 *   java -cp out/tests EpubTest
 */
public class EpubTest {

    private static int pass = 0, fail = 0;

    public static void main(String[] args) throws Exception {
        File f = File.createTempFile("kanfan", ".epub");
        buildNcxEpub(f);
        ZipFile z = new ZipFile(f);
        Epub.Book b = Epub.open(z);

        check("书名", b.title, "测试书");
        check("章节数", b.spine.size(), 2);
        check("首章路径", b.spine.get(0).href, "OEBPS/chap1.xhtml");
        check("首章标题（来自 ncx）", b.spine.get(0).title, "第一章 起");
        check("次章标题（来自 ncx）", b.spine.get(1).title, "第二章 承");

        String html = Epub.text(z, "OEBPS/chap1.xhtml");
        check("能读出正文", Epub.bodyOf(html).contains("正文一"), true);
        check("script 被剔掉", Epub.bodyOf("<body><script>bad()</script>好</body>").contains("bad"), false);
        check("style 被剔掉", Epub.bodyOf("<body><style>.a{color:red}</style>好</body>").contains(".a{"), false);
        check("body 提取", Epub.bodyOf("<html><body><p>X</p></body></html>"), "<p>X</p>");

        byte[] img = Epub.bytes(z, "OEBPS/img/a.png");
        check("图片能取到", img != null && img.length == 4, true);
        check("缺图返回 null", Epub.bytes(z, "OEBPS/none.png") == null, true);

        check("resolve 同目录", Epub.resolve("OEBPS", "img/a.png"), "OEBPS/img/a.png");
        check("resolve 上跳", Epub.resolve("OEBPS/sub", "../img/a.png"), "OEBPS/img/a.png");
        check("resolve 去锚点", Epub.resolve("OEBPS", "chap1.xhtml#top"), "OEBPS/chap1.xhtml");
        check("resolve 绝对", Epub.resolve("OEBPS", "/img/a.png"), "img/a.png");
        check("dirOf", Epub.dirOf("OEBPS/chap1.xhtml"), "OEBPS");
        check("dirOf 根", Epub.dirOf("chap.xhtml"), "");
        z.close();
        f.delete();

        // ---- 没有 ncx：标题回退到 <title> ----
        File f2 = File.createTempFile("kanfan2", ".epub");
        buildNavEpub(f2);
        ZipFile z2 = new ZipFile(f2);
        Epub.Book b2 = Epub.open(z2);
        check("nav 书名", b2.title, "第二本");
        check("nav 章节数", b2.spine.size(), 2);
        check("nav 标题", b2.spine.get(1).title, "第二话");
        z2.close();
        f2.delete();

        System.out.println();
        System.out.println(fail == 0 ? ("全部通过：" + pass + " 项") : ("失败 " + fail + " 项 / 共 " + (pass + fail) + " 项"));
        if (fail != 0) System.exit(1);
    }

    private static void buildNcxEpub(File f) throws Exception {
        Map<String, String> m = new LinkedHashMap<String, String>();
        m.put("mimetype", "application/epub+zip");
        m.put("META-INF/container.xml",
                "<?xml version=\"1.0\"?><container version=\"1.0\" "
                        + "xmlns=\"urn:oasis:names:tc:opendocument:xmlns:container\">"
                        + "<rootfiles><rootfile full-path=\"OEBPS/content.opf\" "
                        + "media-type=\"application/oebps-package+xml\"/></rootfiles></container>");
        m.put("OEBPS/content.opf",
                "<?xml version=\"1.0\" encoding=\"utf-8\"?><package version=\"2.0\">"
                        + "<metadata xmlns:dc=\"http://purl.org/dc/elements/1.1/\">"
                        + "<dc:title>测试书</dc:title></metadata><manifest>"
                        + "<item id=\"ncx\" href=\"toc.ncx\" media-type=\"application/x-dtbncx+xml\"/>"
                        + "<item id=\"c1\" href=\"chap1.xhtml\" media-type=\"application/xhtml+xml\"/>"
                        + "<item id=\"c2\" href=\"chap2.xhtml\" media-type=\"application/xhtml+xml\"/>"
                        + "<item id=\"i1\" href=\"img/a.png\" media-type=\"image/png\"/>"
                        + "</manifest><spine toc=\"ncx\"><itemref idref=\"c1\"/><itemref idref=\"c2\"/>"
                        + "</spine></package>");
        m.put("OEBPS/toc.ncx",
                "<?xml version=\"1.0\"?><ncx version=\"2005-1\"><navMap>"
                        + "<navPoint id=\"n1\"><navLabel><text>第一章 起</text></navLabel>"
                        + "<content src=\"chap1.xhtml\"/></navPoint>"
                        + "<navPoint id=\"n2\"><navLabel><text>第二章 承</text></navLabel>"
                        + "<content src=\"chap2.xhtml\"/></navPoint></navMap></ncx>");
        m.put("OEBPS/chap1.xhtml",
                "<html><head><title>内页标题一</title></head><body><h1>第一章</h1>"
                        + "<p>正文一</p><img src=\"img/a.png\"/></body></html>");
        m.put("OEBPS/chap2.xhtml",
                "<html><head><title>内页标题二</title></head><body><p>正文二</p></body></html>");
        m.put("OEBPS/img/a.png", "");
        writeZip(f, m);
    }

    private static void buildNavEpub(File f) throws Exception {
        Map<String, String> m = new LinkedHashMap<String, String>();
        m.put("mimetype", "application/epub+zip");
        m.put("META-INF/container.xml",
                "<?xml version=\"1.0\"?><container version=\"1.0\">"
                        + "<rootfiles><rootfile full-path=\"content.opf\"/></rootfiles></container>");
        m.put("content.opf",
                "<?xml version=\"1.0\"?><package version=\"3.0\">"
                        + "<metadata xmlns:dc=\"http://purl.org/dc/elements/1.1/\">"
                        + "<dc:title>第二本</dc:title></metadata><manifest>"
                        + "<item id=\"nav\" href=\"nav.xhtml\" media-type=\"application/xhtml+xml\" properties=\"nav\"/>"
                        + "<item id=\"c1\" href=\"a.xhtml\" media-type=\"application/xhtml+xml\"/>"
                        + "<item id=\"c2\" href=\"b.xhtml\" media-type=\"application/xhtml+xml\"/>"
                        + "</manifest><spine><itemref idref=\"c1\"/><itemref idref=\"c2\"/></spine></package>");
        m.put("nav.xhtml",
                "<html><body><nav epub:type=\"toc\"><ol>"
                        + "<li><a href=\"a.xhtml\">第一话</a></li>"
                        + "<li><a href=\"b.xhtml\">第二话</a></li></ol></nav></body></html>");
        m.put("a.xhtml", "<html><head><title>甲</title></head><body><p>A</p></body></html>");
        m.put("b.xhtml", "<html><head><title>乙</title></head><body><p>B</p></body></html>");
        writeZip(f, m);
    }

    private static void writeZip(File f, Map<String, String> entries) throws Exception {
        ZipOutputStream out = new ZipOutputStream(new FileOutputStream(f));
        for (Map.Entry<String, String> e : entries.entrySet()) {
            out.putNextEntry(new ZipEntry(e.getKey()));
            if ("OEBPS/img/a.png".equals(e.getKey())) {
                out.write(new byte[]{1, 2, 3, 4});
            } else {
                out.write(e.getValue().getBytes("UTF-8"));
            }
            out.closeEntry();
        }
        out.close();
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
