import com.kanfan.player.Zip;
import com.kanfan.player.ZipWrite;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

/**
 * 压缩器验证：自己写、自己读、再交给 7-Zip 校验。
 * 覆盖 存储/压缩、Zip64、UTF-8 名、取消、进度。
 */
public class ZipWriteTest {

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
        File dir = new File(args.length > 0 ? args[0] : "D:\\tmp\\zipwrite");
        if (!dir.exists() && !dir.mkdirs()) throw new IllegalStateException("建不了 " + dir);

        // ---- 素材 ----
        File text = new File(dir, "文本.txt");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 4000; i++) sb.append("第 ").append(i).append(" 行：刀剑神域\n");
        write(text, sb.toString().getBytes("UTF-8"));

        File bin = new File(dir, "random.bin");
        byte[] rnd = new byte[700 * 1024];
        new Random(11).nextBytes(rnd);
        write(bin, rnd);

        File empty = new File(dir, "empty.txt");
        write(empty, new byte[0]);

        // ---- 1) 标准压缩 ----
        File z1 = new File(dir, "deflate.zip");
        int[] calls = new int[]{0};
        ZipWrite w = new ZipWrite(new FileOutputStream(z1), 6, listener(calls, false));
        w.setTotal(text.length() + bin.length() + empty.length());
        w.add(ZipWrite.fileSource(text, "文本.txt"));
        w.add(ZipWrite.fileSource(bin, "sub/random.bin"));
        w.add(ZipWrite.fileSource(empty, "empty.txt"));
        w.close();

        ok("压缩包不应为空", z1.length() > 0);
        ok("进度回调有调用", calls[0] > 0);
        ok("文本被压小", z1.length() < text.length() + bin.length() + 1024);

        Zip z = Zip.open(z1);
        eq("条目数", z.entries.size(), 3);
        ok("文本内容一致", Arrays.equals(read(z, "文本.txt"), sb.toString().getBytes("UTF-8")));
        ok("二进制内容一致", Arrays.equals(read(z, "sub/random.bin"), rnd));
        ok("空文件一致", Arrays.equals(read(z, "empty.txt"), new byte[0]));
        ok("名字是 UTF-8", find(z, "文本.txt") != null && find(z, "文本.txt").flags != 0);
        z.close();
        ok("7z 校验 deflate.zip", sevenZipTest(z1) == 0);

        // ---- 2) 存储（level 0） ----
        File z2 = new File(dir, "store.zip");
        ZipWrite w2 = new ZipWrite(new FileOutputStream(z2), 0, null);
        w2.add(ZipWrite.fileSource(bin, "random.bin"));
        w2.close();
        Zip zz = Zip.open(z2);
        Zip.Entry e = find(zz, "random.bin");
        ok("存储模式 method=0", e != null && e.method == 0);
        ok("存储模式大小不变", e != null && e.compressedSize == rnd.length);
        zz.close();
        ok("7z 校验 store.zip", sevenZipTest(z2) == 0);

        // ---- 3) Zip64 分支（把门槛调低，几 MB 样本就能走到） ----
        File z3 = new File(dir, "zip64.zip");
        setZip64Limit(200 * 1024L);
        ZipWrite w3 = new ZipWrite(new FileOutputStream(z3), 6, null);
        w3.add(ZipWrite.fileSource(bin, "big.bin"));
        w3.add(ZipWrite.fileSource(text, "t.txt"));
        w3.close();
        setZip64Limit(0xFFFFFFFFL);

        Zip z64 = Zip.open(z3);
        Zip.Entry be = find(z64, "big.bin");
        ok("Zip64 条目能读", be != null);
        ok("Zip64 内容一致", Arrays.equals(read(z64, "big.bin"), rnd));
        z64.close();
        ok("7z 校验 zip64.zip", sevenZipTest(z3) == 0);

        // ---- 4) 取消 ----
        File z4 = new File(dir, "cancel.zip");
        boolean cancelled = false;
        try {
            ZipWrite w4 = new ZipWrite(new FileOutputStream(z4), 6, listener(calls, true));
            w4.add(ZipWrite.fileSource(bin, "big.bin"));
            w4.close();
        } catch (IOException ex) {
            cancelled = true;
        }
        ok("取消会中断", cancelled);

        // ---- 5) 名字清洗：反斜杠、前导斜杠 ----
        File z5 = new File(dir, "names.zip");
        ZipWrite w5 = new ZipWrite(new FileOutputStream(z5), 6, null);
        w5.add(ZipWrite.fileSource(text, "\\a\\b\\中文 名.txt"));
        w5.close();
        Zip zn = Zip.open(z5);
        ok("反斜杠被归一", find(zn, "a/b/中文 名.txt") != null);
        zn.close();

        // ---- 5b) 已经是压缩格式的直接存储，别白烧 CPU ----
        File z5b = new File(dir, "video.zip");
        ZipWrite w5b = new ZipWrite(new FileOutputStream(z5b), 6, null);
        w5b.add(ZipWrite.fileSource(bin, "第01话.mp4"));
        w5b.close();
        Zip zv = Zip.open(z5b);
        Zip.Entry ve = find(zv, "第01话.mp4");
        ok("mp4 走存储", ve != null && ve.method == 0);
        ok("mp4 内容一致", Arrays.equals(read(zv, "第01话.mp4"), rnd));
        zv.close();
        ok("7z 校验 video.zip", sevenZipTest(z5b) == 0);

        // ---- 5c) 大小未知也要能写（SAF 有时不报 size） ----
        File z5c = new File(dir, "unknown.zip");
        ZipWrite w5c = new ZipWrite(new FileOutputStream(z5c), 6, null);
        w5c.add(new ZipWrite.Source() {
            public String name() {
                return "unknown.bin";
            }

            public long size() {
                return -1;
            }

            public long time() {
                return 0;
            }

            public InputStream open() {
                return new java.io.ByteArrayInputStream(rnd);
            }
        });
        w5c.close();
        Zip zu = Zip.open(z5c);
        ok("未知大小内容一致", Arrays.equals(read(zu, "unknown.bin"), rnd));
        zu.close();
        ok("7z 校验 unknown.zip", sevenZipTest(z5c) == 0);

        // ---- 6) 两万个小条目：中央目录计数正确 ----
        File z6 = new File(dir, "many.zip");
        ZipWrite w6 = new ZipWrite(new FileOutputStream(z6), 0, null);
        final byte[] tiny = "x".getBytes("UTF-8");
        for (int i = 0; i < 20000; i++) {
            final int k = i;
            w6.add(new ZipWrite.Source() {
                public String name() {
                    return "d/" + k + ".txt";
                }

                public long size() {
                    return tiny.length;
                }

                public long time() {
                    return 0;
                }

                public InputStream open() {
                    return new java.io.ByteArrayInputStream(tiny);
                }
            });
        }
        w6.close();
        Zip zm = Zip.open(z6);
        eq("两万条目", zm.entries.size(), 20000);
        ok("末条目在", find(zm, "d/19999.txt") != null);
        zm.close();
        ok("7z 校验 many.zip", sevenZipTest(z6) == 0);

        // ---- 7) 空包也要是合法的 zip ----
        File z7 = new File(dir, "empty.zip");
        new ZipWrite(new FileOutputStream(z7), 6, null).close();
        ok("空包 7z 能识别", sevenZipTest(z7) == 0);

        System.out.println("ZipWriteTest: " + passed + " 通过, " + failed + " 失败");
        if (failed > 0) System.exit(1);
    }

    static ZipWrite.Listener listener(final int[] calls, final boolean cancel) {
        return new ZipWrite.Listener() {
            public void onProgress(String name, long done, long total) {
                calls[0]++;
            }

            public boolean cancelled() {
                return cancel;
            }
        };
    }

    static void setZip64Limit(long v) throws Exception {
        java.lang.reflect.Field f = ZipWrite.class.getDeclaredField("zip64Limit");
        f.setAccessible(true);
        f.setLong(null, v);
    }

    static Zip.Entry find(Zip z, String name) {
        for (Zip.Entry e : z.entries) {
            if (e.name.equals(name)) return e;
        }
        return null;
    }

    static byte[] read(Zip z, String name) throws Exception {
        Zip.Entry e = find(z, name);
        if (e == null) return null;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        z.extract(e, "", out);
        return out.toByteArray();
    }

    /** 调 7z t 校验，返回退出码 */
    static int sevenZipTest(File f) throws Exception {
        String exe = "D:\\应用\\7-Zip\\7z.exe";
        if (!new File(exe).exists()) return 0;   // 别的机器上没有就跳过
        ProcessBuilder pb = new ProcessBuilder(exe, "t", f.getAbsolutePath());
        pb.redirectErrorStream(true);
        Process p = pb.start();
        InputStream in = p.getInputStream();
        byte[] b = new byte[8192];
        while (in.read(b) > 0) {
            // 读干净，避免管道塞满
        }
        return p.waitFor();
    }

    static void write(File f, byte[] data) throws Exception {
        OutputStream o = new FileOutputStream(f);
        o.write(data);
        o.close();
    }
}
