package com.kanfan.player;

import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import java.util.zip.CRC32;
import java.util.zip.Deflater;

/**
 * 最小的 zip 写入器：流式写、支持 Zip64、UTF-8 文件名、可取消。
 *
 * 不依赖 Android，可用真实文件在 JVM 上验证（7z t 校验）。
 * 只写不加密；解压侧的加密支持在 Zip.java。
 */
public final class ZipWrite implements Closeable {

    /** 解压侧能读到的最大能力；写入这边不做加密 */
    public interface Source {
        /** 归档里的路径，用 / 分隔 */
        String name();

        /** 原文件大小，-1 表示未知 */
        long size();

        /** 修改时间（毫秒），0 表示用当前时间 */
        long time();

        InputStream open() throws IOException;
    }

    public interface Listener {
        void onProgress(String name, long done, long total);

        boolean cancelled();
    }

    /** 测试用：把 Zip64 的门槛调低，才能在几 MB 的样本里走到 Zip64 分支 */
    static long zip64Limit = 0xFFFFFFFFL;

    private static final class Cd {
        byte[] name;
        int method;
        int flags;
        int dosTime, dosDate;
        long crc, csize, usize, offset;
        boolean zip64;
    }

    private final OutputStream out;
    private final int level;
    private final Listener listener;
    private final List<Cd> cds = new ArrayList<Cd>();
    private long written;
    private long totalBytes;
    private long grandTotal = -1;
    private boolean finished;
    private final byte[] buf = new byte[64 * 1024];
    private final byte[] zbuf = new byte[64 * 1024];

    public ZipWrite(OutputStream out, int level, Listener listener) {
        this.out = out;
        this.level = level < 0 ? 6 : (level > 9 ? 9 : level);
        this.listener = listener;
    }

    /** 先把所有来源报一遍，用于进度条总长 */
    public void setTotal(long total) {
        this.grandTotal = total;
    }

    public void add(Source src) throws IOException {
        String name = clean(src.name());
        if (name.isEmpty()) return;
        if (listener != null && listener.cancelled()) throw new IOException("已取消");

        byte[] nameBytes = name.getBytes(StandardCharsets.UTF_8);
        long declared = src.size();
        boolean zip64 = declared < 0 || declared >= zip64Limit;
        int method = (level == 0 || incompressible(name)) ? 0 : 8;
        int flags = 0x0800 | 0x0008;    // UTF-8 名 + 数据描述符
        long t = src.time() > 0 ? src.time() : System.currentTimeMillis();
        int[] dt = dosTime(t);

        long start = written;
        le32(0x04034b50);
        le16(zip64 ? 45 : 20);
        le16(flags);
        le16(method);
        le16(dt[0]);
        le16(dt[1]);
        le32(0);                                   // crc 留到描述符
        le32(zip64 ? 0xFFFFFFFFL : 0);             // 压缩后大小
        le32(zip64 ? 0xFFFFFFFFL : 0);             // 原始大小
        le16(nameBytes.length);
        le16(zip64 ? 20 : 0);
        write(nameBytes, 0, nameBytes.length);
        if (zip64) {
            le16(0x0001);
            le16(16);
            le64(0);                               // 原始大小（占位）
            le64(0);                               // 压缩后大小（占位）
        }

        CRC32 crc = new CRC32();
        long usize = 0, csize = 0;
        InputStream in = src.open();
        Deflater def = method == 8 ? new Deflater(level, true) : null;
        try {
            int n;
            while ((n = in.read(buf)) > 0) {
                if (listener != null && listener.cancelled()) throw new IOException("已取消");
                crc.update(buf, 0, n);
                usize += n;
                if (def == null) {
                    write(buf, 0, n);
                    csize += n;
                } else {
                    def.setInput(buf, 0, n);
                    while (!def.needsInput()) {
                        int k = def.deflate(zbuf, 0, zbuf.length);
                        if (k <= 0) break;
                        write(zbuf, 0, k);
                        csize += k;
                    }
                }
                totalBytes += n;
                if (listener != null && (usize & 0xFFFF) < n) {
                    listener.onProgress(name, totalBytes,
                            grandTotal > 0 ? grandTotal : -1);
                }
            }
            if (def != null) {
                def.finish();
                while (!def.finished()) {
                    int k = def.deflate(zbuf, 0, zbuf.length);
                    if (k <= 0) break;
                    write(zbuf, 0, k);
                    csize += k;
                }
            }
        } finally {
            try {
                in.close();
            } catch (Exception ignored) {
            }
            if (def != null) def.end();
        }

        if (!zip64 && (usize >= zip64Limit || csize >= zip64Limit)) {
            throw new IOException("文件在压缩过程中变大了，请重试：" + name);
        }

        long crcVal = crc.getValue();
        boolean desc64 = zip64 || usize >= zip64Limit || csize >= zip64Limit;
        le32(0x08074b50);
        le32(crcVal);
        if (desc64) {
            le64(csize);
            le64(usize);
        } else {
            le32(csize);
            le32(usize);
        }

        Cd cd = new Cd();
        cd.name = nameBytes;
        cd.method = method;
        cd.flags = flags;
        cd.dosTime = dt[0];
        cd.dosDate = dt[1];
        cd.crc = crcVal;
        cd.csize = csize;
        cd.usize = usize;
        cd.offset = start;
        cd.zip64 = desc64;
        cds.add(cd);
        if (listener != null) listener.onProgress(name, totalBytes, grandTotal > 0 ? grandTotal : -1);
    }

    private void writeCentral() throws IOException {
        long cdStart = written;
        for (Cd c : cds) {
            int extra = 0;
            boolean bigU = c.usize >= zip64Limit;
            boolean bigC = c.csize >= zip64Limit;
            boolean bigO = c.offset >= zip64Limit;
            if (bigU) extra += 8;
            if (bigC) extra += 8;
            if (bigO) extra += 8;
            if (extra > 0) extra += 4;

            le32(0x02014b50);
            le16(c.zip64 || extra > 0 ? 45 : 20);      // version made by
            le16(c.zip64 || extra > 0 ? 45 : 20);      // version needed
            le16(c.flags);
            le16(c.method);
            le16(c.dosTime);
            le16(c.dosDate);
            le32(c.crc);
            le32(bigC ? 0xFFFFFFFFL : c.csize);
            le32(bigU ? 0xFFFFFFFFL : c.usize);
            le16(c.name.length);
            le16(extra);
            le16(0);                                   // comment
            le16(0);                                   // disk start
            le16(0);                                   // internal attr
            le32(0);                                   // external attr
            le32(bigO ? 0xFFFFFFFFL : c.offset);
            write(c.name, 0, c.name.length);
            if (extra > 0) {
                le16(0x0001);
                le16(extra - 4);
                if (bigU) le64(c.usize);
                if (bigC) le64(c.csize);
                if (bigO) le64(c.offset);
            }
        }
        long cdSize = written - cdStart;

        boolean needZip64 = cdStart >= zip64Limit || cdSize >= zip64Limit
                || cds.size() > 0xFFFE;
        if (needZip64) {
            long z64At = written;
            le32(0x06064b50);
            le64(44);
            le16(45);
            le16(45);
            le32(0);
            le32(0);
            le64(cds.size());
            le64(cds.size());
            le64(cdSize);
            le64(cdStart);

            le32(0x07064b50);
            le32(0);
            le64(z64At);
            le32(1);
        }
        le32(0x06054b50);
        le16(0);
        le16(0);
        le16((int) Math.min(cds.size(), 0xFFFF));
        le16((int) Math.min(cds.size(), 0xFFFF));
        le32(needZip64 ? 0xFFFFFFFFL : cdSize);
        le32(needZip64 ? 0xFFFFFFFFL : cdStart);
        le16(0);
    }

    @Override
    public void close() throws IOException {
        try {
            finish();
        } finally {
            out.close();
        }
    }

    public void finish() throws IOException {
        if (finished) return;
        finished = true;
        writeCentral();
        out.flush();
    }

    // ---------------- 小工具 ----------------

    private static String clean(String name) {
        if (name == null) return "";
        String n = name.replace('\\', '/');
        while (n.startsWith("/")) n = n.substring(1);
        return n;
    }

    /** 已经是压缩格式的，再 deflate 只烧 CPU 不省体积 */
    public static boolean incompressible(String name) {
        if (name == null) return false;
        String n = name.toLowerCase(Locale.US);
        int dot = n.lastIndexOf('.');
        if (dot < 0) return false;
        String ext = n.substring(dot + 1);
        String[] skip = {
                "mp4", "mkv", "avi", "ts", "mov", "webm", "flv", "m4v", "wmv", "rmvb",
                "mpg", "mpeg", "3gp", "m2ts", "mts",
                "jpg", "jpeg", "png", "gif", "webp", "heic", "heif", "avif",
                "mp3", "m4a", "aac", "flac", "ogg", "opus", "wma", "ape",
                "zip", "rar", "7z", "gz", "bz2", "xz", "zst", "apk", "epub", "cab", "iso"
        };
        for (String e : skip) {
            if (ext.equals(e)) return true;
        }
        return false;
    }

    private static int[] dosTime(long ms) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(ms);
        int year = c.get(Calendar.YEAR);
        if (year < 1980) year = 1980;
        int dosTime = (c.get(Calendar.HOUR_OF_DAY) << 11)
                | (c.get(Calendar.MINUTE) << 5)
                | (c.get(Calendar.SECOND) >> 1);
        int dosDate = ((year - 1980) << 9)
                | ((c.get(Calendar.MONTH) + 1) << 5)
                | c.get(Calendar.DAY_OF_MONTH);
        return new int[]{dosTime, dosDate};
    }

    private void write(byte[] b, int off, int len) throws IOException {
        out.write(b, off, len);
        written += len;
    }

    private void le16(int v) throws IOException {
        out.write(v & 0xFF);
        out.write((v >>> 8) & 0xFF);
        written += 2;
    }

    private void le32(long v) throws IOException {
        out.write((int) (v & 0xFF));
        out.write((int) ((v >>> 8) & 0xFF));
        out.write((int) ((v >>> 16) & 0xFF));
        out.write((int) ((v >>> 24) & 0xFF));
        written += 4;
    }

    private void le64(long v) throws IOException {
        le32(v & 0xFFFFFFFFL);
        le32((v >>> 32) & 0xFFFFFFFFL);
    }

    /** 方便测试与本地打包：直接pack一个文件 */
    public static Source fileSource(final File f, final String entryName) {
        return new Source() {
            @Override
            public String name() {
                return entryName != null ? entryName : f.getName();
            }

            @Override
            public long size() {
                return f.length();
            }

            @Override
            public long time() {
                return f.lastModified();
            }

            @Override
            public InputStream open() throws IOException {
                return new FileInputStream(f);
            }
        };
    }

    public static String humanSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format(Locale.US, "%.1f KB", bytes / 1024.0);
        if (bytes < 1024L * 1024 * 1024) {
            return String.format(Locale.US, "%.1f MB", bytes / 1048576.0);
        }
        return String.format(Locale.US, "%.2f GB", bytes / 1073741824.0);
    }
}
