import com.kanfan.player.Zip;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;
import java.util.Arrays;
import java.util.Random;

/**
 * 分卷 zip：把一个完整 zip 按字节切成 .001/.002，验证 MultiSrc 能跨卷读。
 * 切割点故意挑在非 4 字节对齐的位置，顺带验证跨卷的短读能拼接。
 */
public class ZipVolTest {

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
        File dir = new File(args.length > 0 ? args[0] : "D:\\tmp\\zipvol");
        if (!dir.exists() && !dir.mkdirs()) throw new IllegalStateException("建不了 " + dir);

        // ---- 造一个 3 条内容的 zip（其中一条 300KB，必然横跨卷边界） ----
        byte[] big = new byte[300 * 1024];
        new Random(7).nextBytes(big);
        File whole = new File(dir, "whole.zip");
        FileOutputStream fo = new FileOutputStream(whole);
        java.util.zip.ZipOutputStream zo = new java.util.zip.ZipOutputStream(fo);
        zo.putNextEntry(new java.util.zip.ZipEntry("a.txt"));
        zo.write("hello 分卷\n".getBytes("UTF-8"));
        zo.closeEntry();
        zo.putNextEntry(new java.util.zip.ZipEntry("big.bin"));
        zo.write(big);
        zo.closeEntry();
        zo.putNextEntry(new java.util.zip.ZipEntry("sub/b.txt"));
        zo.write("末条".getBytes("UTF-8"));
        zo.closeEntry();
        zo.close();

        long total = whole.length();
        byte[] all = readAll(whole);

        // ---- 切成两卷，切点故意错开 4 字节 ----
        int cut = (int) (total / 2) + 3;
        File p1 = new File(dir, "split.zip.001");
        File p2 = new File(dir, "split.zip.002");
        write(p1, Arrays.copyOfRange(all, 0, cut));
        write(p2, Arrays.copyOfRange(all, cut, all.length));

        Zip.Src[] parts = new Zip.Src[]{
                new Zip.ChannelSrc(new RandomAccessFile(p1, "r").getChannel()),
                new Zip.ChannelSrc(new RandomAccessFile(p2, "r").getChannel())
        };
        Zip z = Zip.open(new Zip.MultiSrc(parts), null);
        eq("两卷条目数", z.entries.size(), 3);
        eq("两卷 a.txt", readEntry(z, "a.txt", null), "hello 分卷\n");
        ok("两卷 big.bin 一致", Arrays.equals(readEntryBytes(z, "big.bin", null), big));
        eq("两卷 sub/b.txt", readEntry(z, "sub/b.txt", null), "末条");
        z.close();

        // ---- 切成三卷 ----
        File[] trio = new File[]{
                new File(dir, "tri.zip.001"),
                new File(dir, "tri.zip.002"),
                new File(dir, "tri.zip.003")
        };
        int c1 = 100, c2 = cut + 17;
        write(trio[0], Arrays.copyOfRange(all, 0, c1));
        write(trio[1], Arrays.copyOfRange(all, c1, c2));
        write(trio[2], Arrays.copyOfRange(all, c2, all.length));
        Zip.Src[] tp = new Zip.Src[trio.length];
        for (int i = 0; i < trio.length; i++) {
            tp[i] = new Zip.ChannelSrc(new RandomAccessFile(trio[i], "r").getChannel());
        }
        Zip z3 = Zip.open(new Zip.MultiSrc(tp), null);
        eq("三卷条目数", z3.entries.size(), 3);
        ok("三卷 big.bin 一致", Arrays.equals(readEntryBytes(z3, "big.bin", null), big));
        z3.close();

        // ---- 少一卷必须报错，而不是读出垃圾 ----
        Zip.Src[] shortParts = new Zip.Src[]{
                new Zip.ChannelSrc(new RandomAccessFile(p1, "r").getChannel())
        };
        boolean threw = false;
        try {
            Zip.open(new Zip.MultiSrc(shortParts), null).close();
        } catch (Exception e) {
            threw = true;
        }
        ok("缺卷时报错", threw);

        // ---- 单卷仍走老路径 ----
        Zip zs = Zip.open(whole);
        ok("单卷 big.bin 一致", Arrays.equals(readEntryBytes(zs, "big.bin", null), big));
        zs.close();

        System.out.println("ZipVolTest: " + passed + " 通过, " + failed + " 失败");
        if (failed > 0) System.exit(1);
    }

    static byte[] readEntryBytes(Zip z, String name, String pwd) throws Exception {
        for (Zip.Entry e : z.entries) {
            if (e.name.endsWith(name)) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                z.extract(e, pwd == null ? "" : pwd, out);
                return out.toByteArray();
            }
        }
        return null;
    }

    static String readEntry(Zip z, String name, String pwd) throws Exception {
        byte[] b = readEntryBytes(z, name, pwd);
        return b == null ? null : new String(b, "UTF-8");
    }

    static byte[] readAll(File f) throws Exception {
        FileInputStream in = new FileInputStream(f);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] b = new byte[65536];
        int n;
        while ((n = in.read(b)) > 0) out.write(b, 0, n);
        in.close();
        return out.toByteArray();
    }

    static void write(File f, byte[] data) throws Exception {
        FileOutputStream o = new FileOutputStream(f);
        o.write(data);
        o.close();
    }
}
