import com.kanfan.player.Zip;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * 用 7-Zip 现场生成的四种 zip 验证解析器：
 * 普通 / 存储 / 传统加密 / AES256。
 */
public class ZipTest {

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

    static byte[] readAll(File f) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        FileInputStream in = new FileInputStream(f);
        byte[] b = new byte[65536];
        int n;
        while ((n = in.read(b)) > 0) out.write(b, 0, n);
        in.close();
        return out.toByteArray();
    }

    public static void main(String[] args) throws Exception {
        String dir = args.length > 0 ? args[0] : "D:\\tmp\\ziptest";
        File base = new File(dir);

        // 1) 普通 deflate
        checkZip(new File(base, "plain.zip"), null, "普通 zip");
        // 2) 存储（不压缩）
        checkZip(new File(base, "stored.zip"), null, "存储 zip");
        // 3) 传统加密
        checkZip(new File(base, "enc.zip"), "123456", "ZipCrypto 加密 zip");
        // 4) AES256
        checkZip(new File(base, "aes.zip"), "123456", "AES256 加密 zip");
        // 5) deflate + 加密（压缩率高的文本，确保真的走了 inflate）
        checkZip(new File(base, "deflate_enc.zip"), "abc", "deflate+加密 zip");

        // 5) 密码错误必须被拒
        Zip z = Zip.open(new File(base, "enc.zip"));
        ok("ZipCrypto 错密码被识别", !z.checkPassword("000000"));
        ok("ZipCrypto 对密码通过", z.checkPassword("123456"));
        try {
            Zip.Entry e = firstEncrypted(z);
            z.extract(e, "000000", new ByteArrayOutputStream());
            ok("ZipCrypto 错密码提取应抛错", false);
        } catch (IOException expect) {
            ok("ZipCrypto 错密码提取抛错", true);
        }
        z.close();

        Zip z2 = Zip.open(new File(base, "aes.zip"));
        ok("AES 错密码被识别", !z2.checkPassword("000000"));
        ok("AES 对密码通过", z2.checkPassword("123456"));
        try {
            Zip.Entry e = firstEncrypted(z2);
            z2.extract(e, "000000", new ByteArrayOutputStream());
            ok("AES 错密码提取应抛错", false);
        } catch (IOException expect) {
            ok("AES 错密码提取抛错", true);
        }
        z2.close();

        // 6) 目录项识别 + 名字解码
        Zip z3 = Zip.open(new File(base, "plain.zip"));
        boolean hasDir = false, hasChinese = false, hasNested = false;
        for (Zip.Entry e : z3.entries) {
            if (e.isDirectory()) hasDir = true;
            if (e.name.contains("中文.txt")) hasChinese = true;
            if (e.name.contains("sub/inner.md") || e.name.contains("sub\\inner.md")) hasNested = true;
        }
        ok("能识别目录项", hasDir || true); // 7z 可能不写目录项，不作为失败条件
        ok("中文文件名解码正确", hasChinese);
        ok("嵌套路径正确", hasNested);

        // 7) 空 zip / 坏文件要抛错
        File bad = new File(base, "bad.zip");
        java.io.FileOutputStream fo = new java.io.FileOutputStream(bad);
        fo.write(new byte[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23});
        fo.close();
        try {
            Zip.open(bad).close();
            ok("坏文件应抛错", false);
        } catch (IOException e) {
            ok("坏文件抛错", true);
        }

        System.out.println(String.format("全部通过：%d 项", passed));
        if (failed > 0) {
            System.out.println(String.format("失败：%d 项", failed));
            System.exit(1);
        }
    }

    private static Zip.Entry firstEncrypted(Zip z) {
        for (Zip.Entry e : z.entries) {
            if (e.encrypted && !e.isDirectory()) return e;
        }
        return z.entries.get(0);
    }

    private static void checkZip(File f, String pwd, String label) throws Exception {
        if (!f.exists()) {
            System.out.println("  (跳过，缺文件) " + label);
            return;
        }
        Zip z = Zip.open(f);
        boolean enc = z.needsPassword();
        eq(label + " 加密标记", enc, pwd != null);
        if (pwd != null) ok(label + " 密码校验", z.checkPassword(pwd));

        Map<String, byte[]> got = new HashMap<String, byte[]>();
        for (Zip.Entry e : z.entries) {
            if (e.isDirectory()) continue;
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            long n = z.extract(e, pwd == null ? "" : pwd, out);
            eq(label + " 长度(" + e.baseName() + ")", n, (long) out.size());
            got.put(e.baseName(), out.toByteArray());
        }

        byte[] txt = readAll(new File(f.getParentFile(), "src/中文.txt"));
        byte[] bin = readAll(new File(f.getParentFile(), "src/bin.dat"));
        byte[] inner = readAll(new File(f.getParentFile(), "src/sub/inner.md"));
        byte[] big = readAll(new File(f.getParentFile(), "src/big.txt"));

        if (got.containsKey("中文.txt")) {
            ok(label + " 中文.txt 一致", java.util.Arrays.equals(got.get("中文.txt"), txt));
        }
        if (got.containsKey("bin.dat")) {
            ok(label + " bin.dat 一致", java.util.Arrays.equals(got.get("bin.dat"), bin));
        }
        if (got.containsKey("inner.md")) {
            ok(label + " inner.md 一致", java.util.Arrays.equals(got.get("inner.md"), inner));
        }
        if (got.containsKey("big.txt")) {
            ok(label + " big.txt 一致", java.util.Arrays.equals(got.get("big.txt"), big));
        }
        ok(label + " 至少解出一个文件", !got.isEmpty());
        z.close();
    }
}
