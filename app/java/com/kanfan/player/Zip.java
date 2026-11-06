package com.kanfan.player;

import java.io.Closeable;
import java.io.EOFException;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.zip.CRC32;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * 最小的 zip 读取器：支持普通 zip、传统加密（ZipCrypto）与 WinZip AES（AE-1/AE-2）。
 *
 * 不依赖 Android，可用真实文件在 JVM 上验证。
 * 只读，不负责写 zip。
 */
public class Zip implements Closeable {

    public static class Entry {
        public String name = "";
        public long size;            // 解压后大小
        public long compressedSize;  // 压缩后大小（加密时含 salt/头/校验码）
        public long headerOffset;
        public int method;           // 0 存储 / 8 deflate
        public int flags;
        public long crc;
        public boolean encrypted;
        public int aesStrength;      // 0 非 AES；1/2/3 = 128/192/256
        public int aesMethod = -1;   // AES 时的真实压缩方法；-1 表示没有该字段

        public boolean isDirectory() {
            return name.endsWith("/") || name.endsWith("\\");
        }

        /** 名字最后一段，用于创建文件 */
        public String baseName() {
            String n = name.replace('\\', '/');
            int p = n.lastIndexOf('/');
            return p < 0 ? n : n.substring(p + 1);
        }

        /** 目录部分，不含文件名 */
        public String parentPath() {
            String n = name.replace('\\', '/');
            int p = n.lastIndexOf('/');
            return p < 0 ? "" : n.substring(0, p);
        }
    }

    private static final int SIG_EOCD = 0x06054b50;
    private static final int SIG_EOCD64 = 0x06064b50;
    private static final int SIG_EOCD64_LOC = 0x07064b50;
    private static final int SIG_CEN = 0x02014b50;
    private static final int SIG_LOC = 0x04034b50;

    private final Src src;
    private final Closeable extra;
    public final List<Entry> entries = new ArrayList<Entry>();

    private Zip(Src src, Closeable extra) {
        this.src = src;
        this.extra = extra;
    }

    /** 随机读的来源：单文件，或多卷拼起来的逻辑文件 */
    public interface Src extends Closeable {
        long size() throws IOException;

        int readAt(long pos, byte[] b, int off, int len) throws IOException;
    }

    /** 一个普通文件 */
    public static final class ChannelSrc implements Src {
        private final FileChannel ch;

        public ChannelSrc(FileChannel ch) {
            this.ch = ch;
        }

        @Override
        public long size() throws IOException {
            return ch.size();
        }

        @Override
        public int readAt(long pos, byte[] b, int off, int len) throws IOException {
            int done = 0;
            while (done < len) {
                int n = ch.read(ByteBuffer.wrap(b, off + done, len - done), pos + done);
                if (n <= 0) break;
                done += n;
            }
            return done;
        }

        @Override
        public void close() throws IOException {
            ch.close();
        }
    }

    /**
     * 多卷按顺序拼成一个逻辑文件。
     * 分卷 zip（.z01+.z02+...+zip）里各记录的偏移都是相对整包起点的，
     * 所以只要把卷拼起来，目录解析完全不用改。
     */
    public static final class MultiSrc implements Src {
        private final Src[] parts;
        private final long[] starts;
        private final long total;

        public MultiSrc(Src[] parts) throws IOException {
            if (parts == null || parts.length == 0) throw new IOException("没有分卷");
            this.parts = parts;
            this.starts = new long[parts.length + 1];
            for (int i = 0; i < parts.length; i++) {
                starts[i + 1] = starts[i] + parts[i].size();
            }
            this.total = starts[parts.length];
        }

        @Override
        public long size() {
            return total;
        }

        @Override
        public int readAt(long pos, byte[] b, int off, int len) throws IOException {
            if (pos < 0) throw new IOException("读取越界");
            int done = 0;
            while (done < len && pos + done < total) {
                int i = partOf(pos + done);
                long local = pos + done - starts[i];
                long cap = starts[i + 1] - (pos + done);
                int want = (int) Math.min(len - done, cap);
                int n = parts[i].readAt(local, b, off + done, want);
                if (n <= 0) break;
                done += n;
            }
            return done;
        }

        private int partOf(long pos) {
            int lo = 0, hi = parts.length - 1;
            while (lo < hi) {
                int mid = (lo + hi + 1) >>> 1;
                if (starts[mid] <= pos) lo = mid;
                else hi = mid - 1;
            }
            return lo;
        }

        @Override
        public void close() {
            for (Src s : parts) {
                try {
                    if (s != null) s.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    public static Zip open(File f) throws IOException {
        FileChannel c = new RandomAccessFile(f, "r").getChannel();
        Zip z = new Zip(new ChannelSrc(c), c);
        try {
            z.readCentralDirectory();
        } catch (IOException e) {
            z.close();
            throw e;
        }
        return z;
    }

    /** Android 上从 SAF 的 fd 打开，closer 用于一起释放 */
    public static Zip open(FileChannel c, Closeable closer) throws IOException {
        return open(new ChannelSrc(c), closer);
    }

    public static Zip open(Src s, Closeable closer) throws IOException {
        Zip z = new Zip(s, closer);
        try {
            z.readCentralDirectory();
        } catch (IOException e) {
            z.close();
            throw e;
        }
        return z;
    }

    @Override
    public void close() {
        try {
            if (src != null) src.close();
        } catch (Exception ignored) {
        }
        try {
            if (extra != null && extra != src) extra.close();
        } catch (Exception ignored) {
        }
    }

    // ---------------- 目录解析 ----------------

    private void readCentralDirectory() throws IOException {
        long len = src.size();
        if (len < 22) throw new IOException("文件太小，不是 zip");
        int tail = (int) Math.min(len, 65557);
        byte[] buf = readAt(len - tail, tail);

        int eocd = -1;
        for (int i = tail - 22; i >= 0; i--) {
            if (u32(buf, i) == SIG_EOCD) {
                eocd = i;
                break;
            }
        }
        if (eocd < 0) throw new IOException("不是有效的 zip（找不到文件尾记录）");

        long total = u16(buf, eocd + 10);
        long cdSize = u32(buf, eocd + 12);
        long cdOff = u32(buf, eocd + 16);

        if (total == 0xFFFF || cdSize == 0xFFFFFFFFL || cdOff == 0xFFFFFFFFL) {
            long[] z64 = readZip64(len - tail + eocd);
            if (z64 != null) {
                total = z64[0];
                cdSize = z64[1];
                cdOff = z64[2];
            }
        }
        if (cdOff <= 0 || cdSize <= 0) throw new IOException("zip 目录为空或已损坏");
        if (cdSize > 64L * 1024 * 1024) throw new IOException("zip 目录过大");

        byte[] cd = readAt(cdOff, (int) cdSize);
        int p = 0;
        for (long i = 0; i < total && p + 46 <= cd.length; i++) {
            if (u32(cd, p) != SIG_CEN) break;
            int flags = (int) u16(cd, p + 8);
            int method = (int) u16(cd, p + 10);
            long crc = u32(cd, p + 16);
            long csize = u32(cd, p + 20);
            long usize = u32(cd, p + 24);
            int nameLen = (int) u16(cd, p + 28);
            int extraLen = (int) u16(cd, p + 30);
            int commentLen = (int) u16(cd, p + 32);
            long lho = u32(cd, p + 42);

            Entry e = new Entry();
            e.flags = flags;
            e.method = method;
            e.crc = crc;
            e.compressedSize = csize;
            e.size = usize;
            e.headerOffset = lho;
            e.encrypted = (flags & 1) != 0;
            e.name = decodeName(cd, p + 46, nameLen, flags);

            // 扩展字段
            int x = p + 46 + nameLen;
            int xEnd = x + extraLen;
            while (x + 4 <= xEnd) {
                int id = (int) u16(cd, x);
                int sz = (int) u16(cd, x + 2);
                int d = x + 4;
                if (d + sz > xEnd) break;
                if (id == 0x0001) {           // ZIP64
                    int q = d;
                    if (usize == 0xFFFFFFFFL && q + 8 <= d + sz) { usize = u64(cd, q); q += 8; }
                    if (csize == 0xFFFFFFFFL && q + 8 <= d + sz) { csize = u64(cd, q); q += 8; }
                    if (lho == 0xFFFFFFFFL && q + 8 <= d + sz) { lho = u64(cd, q); q += 8; }
                } else if (id == 0x9901 && sz >= 7) {   // WinZip AES
                    int strength = cd[d + 4] & 0xff;
                    int realMethod = (int) u16(cd, d + 5);
                    e.aesStrength = strength;
                    e.aesMethod = realMethod;
                }
                x = d + sz;
            }

            e.size = usize;
            e.compressedSize = csize;
            e.headerOffset = lho;
            if (e.aesStrength > 0 && e.aesMethod >= 0) e.method = e.aesMethod;
            entries.add(e);

            p += 46 + nameLen + extraLen + commentLen;
        }
        if (entries.isEmpty()) throw new IOException("zip 里没有文件");
    }

    /** ZIP64 尾部，返回 {条目数, 目录大小, 目录偏移} */
    private long[] readZip64(long eocdPos) {
        try {
            if (eocdPos < 20) return null;
            byte[] loc = readAt(eocdPos - 20, 20);
            if (u32(loc, 0) != SIG_EOCD64_LOC) return null;
            long off = u64(loc, 8);
            if (off <= 0 || off > src.size()) return null;
            byte[] rec = readAt(off, 56);
            if (u32(rec, 0) != SIG_EOCD64) return null;
            return new long[]{u64(rec, 32), u64(rec, 40), u64(rec, 48)};
        } catch (Exception e) {
            return null;
        }
    }

    // ---------------- 解压 ----------------

    public boolean needsPassword() {
        for (Entry e : entries) {
            if (e.encrypted) return true;
        }
        return false;
    }

    /** 只读文件头判断密码对不对，不解压整包 */
    public boolean checkPassword(String pwd) {
        if (pwd == null) pwd = "";
        for (Entry e : entries) {
            if (!e.encrypted) continue;
            try {
                if (!checkOne(e, pwd)) return false;
            } catch (IOException ex) {
                return false;
            }
        }
        return true;
    }

    private boolean checkOne(Entry e, String pwd) throws IOException {
        long[] lh = localHeader(e);
        long ds = lh[0];
        int modTime = (int) lh[1];

        if (e.aesStrength > 0) {
            int keyLen = aesKeyLen(e.aesStrength);
            int saltLen = keyLen / 2;
            byte[] head = readAt(ds, saltLen + 2);
            byte[] salt = Arrays.copyOfRange(head, 0, saltLen);
            byte[] derived = pbkdf2(pwd.getBytes(StandardCharsets.UTF_8), salt, 1000, keyLen * 2 + 2);
            return derived[derived.length - 2] == head[saltLen]
                    && derived[derived.length - 1] == head[saltLen + 1];
        }

        byte[] head = readAt(ds, 12);
        ZipCrypto zc = new ZipCrypto(pwd.getBytes(StandardCharsets.UTF_8));
        int last = 0;
        for (int i = 0; i < 12; i++) last = zc.decrypt(head[i] & 0xff);

        int expect = ((e.flags & 8) != 0) ? ((modTime >>> 8) & 0xff) : (int) ((e.crc >>> 24) & 0xff);
        return last == expect;
    }

    /** 解压单个条目到 out，返回写入字节数 */
    public long extract(Entry e, String pwd, OutputStream out) throws IOException {
        if (pwd == null) pwd = "";
        long[] lh = localHeader(e);
        long ds = lh[0];
        int modTime = (int) lh[1];

        InputStream plain;
        long payloadLen;
        Mac mac = null;
        byte[] authExpect = null;

        if (e.aesStrength > 0) {
            int keyLen = aesKeyLen(e.aesStrength);
            int saltLen = keyLen / 2;
            byte[] head = readAt(ds, saltLen + 2);
            byte[] salt = Arrays.copyOfRange(head, 0, saltLen);
            byte[] derived = pbkdf2(pwd.getBytes(StandardCharsets.UTF_8), salt, 1000, keyLen * 2 + 2);
            byte[] encKey = Arrays.copyOfRange(derived, 0, keyLen);
            byte[] authKey = Arrays.copyOfRange(derived, keyLen, keyLen * 2);
            if (derived[derived.length - 2] != head[saltLen]
                    || derived[derived.length - 1] != head[saltLen + 1]) {
                throw new IOException("密码不正确");
            }
            payloadLen = e.compressedSize - saltLen - 2 - 10;
            if (payloadLen < 0) throw new IOException("zip 条目已损坏");
            authExpect = readAt(ds + e.compressedSize - 10, 10);

            try {
                mac = Mac.getInstance("HmacSHA1");
                mac.init(new SecretKeySpec(authKey, "HmacSHA1"));
            } catch (Exception ex) {
                throw new IOException("HMAC 初始化失败");
            }
            InputStream ct = new SliceStream(ds + saltLen + 2, payloadLen);
            plain = new AesCtrStream(ct, encKey, mac);
        } else if (e.encrypted) {
            byte[] head = readAt(ds, 12);
            ZipCrypto zc = new ZipCrypto(pwd.getBytes(StandardCharsets.UTF_8));
            int last = 0;
            for (int i = 0; i < 12; i++) last = zc.decrypt(head[i] & 0xff);
            int expect = ((e.flags & 8) != 0) ? ((modTime >>> 8) & 0xff)
                    : (int) ((e.crc >>> 24) & 0xff);
            if (last != expect) throw new IOException("密码不正确");
            payloadLen = e.compressedSize - 12;
            plain = new ZipCryptoStream(new SliceStream(ds + 12, payloadLen), zc);
        } else {
            payloadLen = e.compressedSize;
            plain = new SliceStream(ds, payloadLen);
        }

        InputStream data = plain;
        if (e.method == 8) {
            data = new InflaterInputStream(plain, new Inflater(true), 64 * 1024);
        } else if (e.method != 0) {
            throw new IOException("不支持的压缩方式：" + e.method);
        }

        CRC32 crc = new CRC32();
        byte[] buf = new byte[64 * 1024];
        long total = 0;
        int n;
        while ((n = data.read(buf)) > 0) {
            out.write(buf, 0, n);
            crc.update(buf, 0, n);
            total += n;
        }
        // 让 MAC 吃到剩余密文（deflate 可能提前结束）
        while (plain.read(buf) > 0) {
            // 丢弃，只为推进校验
        }
        data.close();

        if (mac != null && authExpect != null) {
            byte[] got = mac.doFinal();
            if (!Arrays.equals(Arrays.copyOf(got, 10), authExpect)) {
                throw new IOException("校验失败：密码错误或文件损坏");
            }
        }
        if (e.crc != 0 && crc.getValue() != (e.crc & 0xFFFFFFFFL)) {
            throw new IOException("CRC 校验失败，文件可能损坏");
        }
        return total;
    }

    /** 返回 {数据起始偏移, 本地头里的修改时间} */
    private long[] localHeader(Entry e) throws IOException {
        byte[] h = readAt(e.headerOffset, 30);
        if (u32(h, 0) != SIG_LOC) throw new IOException("本地文件头损坏：" + e.name);
        int modTime = (int) u16(h, 10);
        int nameLen = (int) u16(h, 26);
        int extraLen = (int) u16(h, 28);
        return new long[]{e.headerOffset + 30 + nameLen + extraLen, modTime};
    }

    private byte[] readAt(long pos, int n) throws IOException {
        if (n <= 0) return new byte[0];
        if (pos < 0 || pos + n > src.size()) throw new EOFException("读取越界");
        byte[] b = new byte[n];
        int done = 0;
        while (done < n) {
            int r = src.readAt(pos + done, b, done, n - done);
            if (r <= 0) throw new EOFException("读取越界");
            done += r;
        }
        return b;
    }

    // ---------------- 流 ----------------

    /** 文件里的一段 */
    private final class SliceStream extends InputStream {
        private long pos, end;
        private final byte[] one = new byte[1];

        SliceStream(long start, long len) {
            pos = start;
            end = start + len;
        }

        @Override
        public int read() throws IOException {
            int n = read(one, 0, 1);
            return n < 0 ? -1 : one[0] & 0xff;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (pos >= end) return -1;
            int want = (int) Math.min(len, end - pos);
            int n = src.readAt(pos, b, off, want);
            if (n <= 0) return -1;
            pos += n;
            return n;
        }
    }

    private static final class ZipCryptoStream extends InputStream {
        private final InputStream src;
        private final ZipCrypto zc;

        ZipCryptoStream(InputStream src, ZipCrypto zc) {
            this.src = src;
            this.zc = zc;
        }

        @Override
        public int read() throws IOException {
            int c = src.read();
            return c < 0 ? -1 : zc.decrypt(c);
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int n = src.read(b, off, len);
            if (n <= 0) return n;
            for (int i = 0; i < n; i++) b[off + i] = (byte) zc.decrypt(b[off + i] & 0xff);
            return n;
        }
    }

    /** WinZip AES：AES-CTR（计数器小端），同时把密文喂给 HMAC */
    private static final class AesCtrStream extends InputStream {
        private final InputStream src;
        private final Cipher cipher;
        private final Mac mac;
        private final byte[] ctr = new byte[16];
        private final byte[] ks = new byte[16];
        private int ksPos = 16;
        private boolean macDone;

        AesCtrStream(InputStream src, byte[] key, Mac mac) throws IOException {
            this.src = src;
            this.mac = mac;
            try {
                cipher = Cipher.getInstance("AES/ECB/NoPadding");
                cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"));
            } catch (Exception e) {
                throw new IOException("设备不支持 AES 解密");
            }
            ctr[0] = 1;   // 计数器从 1 开始，小端
        }

        private void nextBlock() throws IOException {
            try {
                cipher.update(ctr, 0, 16, ks, 0);
            } catch (Exception e) {
                throw new IOException("AES 解密失败");
            }
            for (int i = 0; i < 16; i++) {
                ctr[i]++;
                if (ctr[i] != 0) break;
            }
            ksPos = 0;
        }

        @Override
        public int read() throws IOException {
            byte[] one = new byte[1];
            int n = read(one, 0, 1);
            return n < 0 ? -1 : one[0] & 0xff;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int n = src.read(b, off, len);
            if (n <= 0) return n;
            if (!macDone) mac.update(b, off, n);
            for (int i = 0; i < n; i++) {
                if (ksPos >= 16) nextBlock();
                b[off + i] = (byte) (b[off + i] ^ ks[ksPos++]);
            }
            return n;
        }
    }

    // ---------------- 加密原语 ----------------

    /** 传统 zip 加密的密钥状态机 */
    static final class ZipCrypto {
        private int k0 = 0x12345678, k1 = 0x23456789, k2 = 0x34567890;

        ZipCrypto(byte[] pwd) {
            for (byte b : pwd) update(b & 0xff);
        }

        private void update(int b) {
            k0 = CRC_TABLE[(k0 ^ b) & 0xff] ^ (k0 >>> 8);
            k1 = (k1 + (k0 & 0xff)) * 134775813 + 1;
            k2 = CRC_TABLE[(k2 ^ (k1 >>> 24)) & 0xff] ^ (k2 >>> 8);
        }

        private int byteKey() {
            int t = (k2 | 2) & 0xFFFF;
            return (int) (((long) t * (t ^ 1)) >>> 8) & 0xff;
        }

        int decrypt(int c) {
            int p = (c ^ byteKey()) & 0xff;
            update(p);
            return p;
        }
    }

    private static final int[] CRC_TABLE = new int[256];

    static {
        for (int i = 0; i < 256; i++) {
            int c = i;
            for (int k = 0; k < 8; k++) {
                c = ((c & 1) != 0) ? (0xEDB88320 ^ (c >>> 1)) : (c >>> 1);
            }
            CRC_TABLE[i] = c;
        }
    }

    /** PBKDF2-HMAC-SHA1；自己实现，避免不同平台编码差异 */
    static byte[] pbkdf2(byte[] pwd, byte[] salt, int iter, int dkLen) throws IOException {
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(pwd, "HmacSHA1"));
            int hLen = 20;
            int blocks = (dkLen + hLen - 1) / hLen;
            byte[] out = new byte[blocks * hLen];
            for (int i = 1; i <= blocks; i++) {
                mac.update(salt);
                mac.update(new byte[]{(byte) (i >>> 24), (byte) (i >>> 16), (byte) (i >>> 8), (byte) i});
                byte[] u = mac.doFinal();
                byte[] t = u.clone();
                for (int j = 1; j < iter; j++) {
                    u = mac.doFinal(u);
                    for (int k = 0; k < t.length; k++) t[k] ^= u[k];
                }
                System.arraycopy(t, 0, out, (i - 1) * hLen, hLen);
            }
            return Arrays.copyOf(out, dkLen);
        } catch (Exception e) {
            throw new IOException("PBKDF2 计算失败：" + e.getMessage());
        }
    }

    // ---------------- 小工具 ----------------

    /** WinZip AES 的 strength：1=128 / 2=192 / 3=256 位 */
    static int aesKeyLen(int strength) {
        if (strength == 1) return 16;
        if (strength == 2) return 24;
        return 32;
    }

    private static int u16(byte[] b, int i) {
        return (b[i] & 0xff) | ((b[i + 1] & 0xff) << 8);
    }

    private static long u32(byte[] b, int i) {
        return (b[i] & 0xffL) | ((b[i + 1] & 0xffL) << 8)
                | ((b[i + 2] & 0xffL) << 16) | ((b[i + 3] & 0xffL) << 24);
    }

    private static long u64(byte[] b, int i) {
        long v = 0;
        for (int k = 7; k >= 0; k--) v = (v << 8) | (b[i + k] & 0xffL);
        return v;
    }

    private static String decodeName(byte[] b, int off, int len, int flags) {
        if (len <= 0) return "";
        if ((flags & 0x800) != 0) {
            return new String(b, off, len, StandardCharsets.UTF_8);
        }
        try {
            Charset utf8 = StandardCharsets.UTF_8;
            String s = utf8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(b, off, len)).toString();
            return s;
        } catch (Exception ignored) {
        }
        try {
            return new String(b, off, len, Charset.forName("GBK"));
        } catch (Exception e) {
            return new String(b, off, len, StandardCharsets.ISO_8859_1);
        }
    }

    /** 供测试用：算一段字节的 HMAC-SHA1 */
    static byte[] hmac(byte[] key, byte[] data) throws IOException {
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(key, "HmacSHA1"));
            return mac.doFinal(data);
        } catch (Exception e) {
            throw new IOException("HMAC 失败");
        }
    }

    static byte[] sha1(byte[] data) throws IOException {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            return md.digest(data);
        } catch (Exception e) {
            throw new IOException("SHA1 失败");
        }
    }
}
