package com.kanfan.player;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import javax.xml.parsers.DocumentBuilderFactory;

/**
 * EPUB 解析：容器 → OPF → spine → 各章 XHTML。
 * 只用 java.util.zip + DOM，不依赖 Android，可在 JVM 上跑用例（tests/EpubTest.java）。
 *
 * 只做「按 spine 顺序读正文」这件事：不解析 CSS、不还原版式，
 * 图片交给阅读器用 ImageGetter 从 zip 里就地取。
 */
public final class Epub {

    public static final class Item {
        public final String href;
        public String title = "";

        Item(String href) {
            this.href = href;
        }
    }

    public static final class Book {
        public String title = "";
        public final List<Item> spine = new ArrayList<Item>();
    }

    private Epub() {
    }

    public static Book open(ZipFile zip) {
        Book book = new Book();
        String container = text(zip, "META-INF/container.xml");
        Document cdoc = parse(container);
        String opfPath = null;
        if (cdoc != null) {
            NodeList rf = cdoc.getElementsByTagName("rootfile");
            if (rf.getLength() > 0) opfPath = attr((Element) rf.item(0), "full-path");
        }
        if (opfPath == null) {
            // 兜底：在 zip 里找一个 .opf
            java.util.Enumeration<? extends ZipEntry> en = zip.entries();
            while (en.hasMoreElements()) {
                String n = en.nextElement().getName();
                if (n.toLowerCase(Locale.US).endsWith(".opf")) {
                    opfPath = n;
                    break;
                }
            }
        }
        if (opfPath == null) return book;

        Document opf = parse(text(zip, opfPath));
        if (opf == null) return book;

        String dir = dirOf(opfPath);

        NodeList titleNodes = opf.getElementsByTagName("dc:title");
        if (titleNodes.getLength() == 0) titleNodes = opf.getElementsByTagName("title");
        if (titleNodes.getLength() > 0) {
            String t = titleNodes.item(0).getTextContent();
            if (t != null) book.title = t.trim();
        }

        Map<String, String> idHref = new HashMap<String, String>();
        Map<String, String> idType = new HashMap<String, String>();
        NodeList items = opf.getElementsByTagName("item");
        for (int i = 0; i < items.getLength(); i++) {
            Element e = (Element) items.item(i);
            String id = attr(e, "id");
            String href = attr(e, "href");
            if (id == null || href == null) continue;
            idHref.put(id, resolve(dir, href));
            String type = attr(e, "media-type");
            idType.put(id, type == null ? "" : type.toLowerCase(Locale.US));
        }

        NodeList refs = opf.getElementsByTagName("itemref");
        for (int i = 0; i < refs.getLength(); i++) {
            Element e = (Element) refs.item(i);
            String idref = attr(e, "idref");
            if (idref == null) continue;
            String href = idHref.get(idref);
            if (href == null) continue;
            String type = idType.get(idref);
            if (type != null && type.startsWith("image/")) continue;
            String low = href.toLowerCase(Locale.US);
            if (!(low.endsWith(".xhtml") || low.endsWith(".html") || low.endsWith(".htm"))) continue;
            book.spine.add(new Item(href));
        }

        Map<String, String> toc = tocTitles(zip, opf, dir, idHref, idType);
        for (Item it : book.spine) {
            String t = toc.get(it.href);
            if (t != null) it.title = t;
        }
        if (toc.isEmpty()) {
            int limit = Math.min(book.spine.size(), 120);
            for (int i = 0; i < limit; i++) {
                Item it = book.spine.get(i);
                it.title = htmlTitle(text(zip, it.href));
            }
        }
        for (int i = 0; i < book.spine.size(); i++) {
            Item it = book.spine.get(i);
            if (it.title == null || it.title.trim().isEmpty()) it.title = "第 " + (i + 1) + " 节";
            else it.title = it.title.trim();
        }
        return book;
    }

    /** 目录标题：EPUB2 看 NCX，EPUB3 看 nav.xhtml */
    private static Map<String, String> tocTitles(ZipFile zip, Document opf, String dir,
                                                 Map<String, String> idHref, Map<String, String> idType) {
        Map<String, String> map = new HashMap<String, String>();
        String ncx = null, nav = null;
        NodeList items = opf.getElementsByTagName("item");
        for (int i = 0; i < items.getLength(); i++) {
            Element e = (Element) items.item(i);
            String id = attr(e, "id");
            if (id == null) continue;
            String type = idType.get(id);
            if (ncx == null && "application/x-dtbncx+xml".equals(type)) ncx = idHref.get(id);
            String props = attr(e, "properties");
            if (nav == null && props != null && props.contains("nav")) nav = idHref.get(id);
        }

        if (ncx != null) {
            Document d = parse(text(zip, ncx));
            if (d != null) {
                String ncxDir = dirOf(ncx);
                NodeList pts = d.getElementsByTagName("navPoint");
                for (int i = 0; i < pts.getLength(); i++) {
                    Element p = (Element) pts.item(i);
                    String src = null, label = null;
                    NodeList kids = p.getElementsByTagName("content");
                    if (kids.getLength() > 0) src = attr((Element) kids.item(0), "src");
                    NodeList txt = p.getElementsByTagName("text");
                    if (txt.getLength() > 0) label = txt.item(0).getTextContent();
                    if (src == null) continue;
                    String path = resolve(ncxDir, src);
                    if (label != null && !map.containsKey(path)) map.put(path, label);
                }
            }
        }

        if (map.isEmpty() && nav != null) {
            String html = text(zip, nav);
            String navDir = dirOf(nav);
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("<a[^>]+href=\"([^\"]+)\"[^>]*>(.*?)</a>",
                            java.util.regex.Pattern.DOTALL | java.util.regex.Pattern.CASE_INSENSITIVE)
                    .matcher(html);
            while (m.find()) {
                String path = resolve(navDir, m.group(1));
                String label = m.group(2).replaceAll("<[^>]*>", "").trim();
                if (!label.isEmpty() && !map.containsKey(path)) map.put(path, label);
            }
        }
        return map;
    }

    private static String htmlTitle(String html) {
        if (html == null) return "";
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("<title[^>]*>(.*?)</title>", java.util.regex.Pattern.DOTALL
                        | java.util.regex.Pattern.CASE_INSENSITIVE).matcher(html);
        if (m.find()) return m.group(1).replaceAll("<[^>]*>", "").trim();
        return "";
    }

    /** 取 <body> 内部 HTML，去掉 script/style，交给 Html.fromHtml */
    public static String bodyOf(String html) {
        if (html == null) return "";
        String s = html.replaceAll("(?is)<script[^>]*>.*?</script>", "")
                .replaceAll("(?is)<style[^>]*>.*?</style>", "")
                .replaceAll("(?is)<!--.*?-->", "");
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(?is)<body[^>]*>(.*?)</body>").matcher(s);
        if (m.find()) return m.group(1);
        return s;
    }

    public static String text(ZipFile zip, String href) {
        byte[] b = bytes(zip, href);
        if (b == null) return "";
        String s = new String(b, java.nio.charset.StandardCharsets.UTF_8);
        if (s.indexOf('\uFFFD') >= 0) {
            try {
                s = new String(b, java.nio.charset.Charset.forName("GB18030"));
            } catch (Exception ignored) {
            }
        }
        if (s.startsWith("\uFEFF")) s = s.substring(1);
        return s;
    }

    public static byte[] bytes(ZipFile zip, String href) {
        if (zip == null || href == null || href.isEmpty()) return null;
        String[] tries = {href, href.replace("%20", " "), decode(href)};
        for (String t : tries) {
            ZipEntry e = zip.getEntry(t);
            if (e == null) continue;
            InputStream in = null;
            try {
                in = zip.getInputStream(e);
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
                return bos.toByteArray();
            } catch (Exception ignored) {
            } finally {
                if (in != null) {
                    try {
                        in.close();
                    } catch (Exception ignored) {
                    }
                }
            }
        }
        return null;
    }

    private static String decode(String s) {
        try {
            return java.net.URLDecoder.decode(s.replace("+", "%2B"), "UTF-8");
        } catch (Exception e) {
            return s;
        }
    }

    /** 把 href 按 baseDir 拼成 zip 内的规范路径，处理 ./ 与 ../ */
    public static String resolve(String baseDir, String href) {
        if (href == null) return "";
        String h = decode(href.trim());
        int hash = h.indexOf('#');
        if (hash >= 0) h = h.substring(0, hash);
        if (h.isEmpty()) return "";
        if (h.startsWith("/")) {
            h = h.substring(1);
        } else if (baseDir != null && !baseDir.isEmpty()) {
            h = baseDir + "/" + h;
        }
        ArrayDeque<String> st = new ArrayDeque<String>();
        for (String p : h.split("/")) {
            if (p.isEmpty() || p.equals(".")) continue;
            if (p.equals("..")) {
                if (!st.isEmpty()) st.removeLast();
            } else {
                st.addLast(p);
            }
        }
        StringBuilder sb = new StringBuilder();
        for (String p : st) {
            if (sb.length() > 0) sb.append('/');
            sb.append(p);
        }
        return sb.toString();
    }

    public static String dirOf(String path) {
        if (path == null) return "";
        int i = path.lastIndexOf('/');
        return i < 0 ? "" : path.substring(0, i);
    }

    private static String attr(Element e, String name) {
        if (e == null) return null;
        String v = e.getAttribute(name);
        return v == null || v.isEmpty() ? null : v;
    }

    private static Document parse(String xml) {
        if (xml == null || xml.trim().isEmpty()) return null;
        try {
            // 去掉 XML 声明，避免「声明写 GBK、字节却是 UTF-8」造成乱码
            String s = xml.replaceFirst("(?is)<\\?xml[^>]*\\?>", "");
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setNamespaceAware(false);
            f.setExpandEntityReferences(false);
            return f.newDocumentBuilder().parse(
                    new ByteArrayInputStream(s.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (Exception e) {
            return null;
        }
    }

    /** 便于单元测试：判断一个节点是否是元素 */
    static boolean isElement(Node n) {
        return n != null && n.getNodeType() == Node.ELEMENT_NODE;
    }
}
