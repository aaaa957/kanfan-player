package com.kanfan.player;

import android.content.Context;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;

/**
 * 启动失败的现场保存。
 *
 * KRKR 是原生引擎，崩在 C++ 里时 Java 层拿不到任何回调，用户只看到「闪退」。
 * 这里做两件事：
 *   1. 启动前写一个哨兵文件，正常活过 5 秒再删掉 —— 哨兵还在就说明上次没活下来
 *   2. 把原生日志、logcat 尾部、Java 堆栈落到应用外部目录（Android/data/.../files/diag），
 *      用户用文件管理器就能看到，也能直接发出来
 */
public final class Diag {

    private Diag() {
    }

    public static File dir(Context c) {
        File d = c.getExternalFilesDir("diag");
        if (d == null) d = new File(c.getFilesDir(), "diag");
        if (!d.exists()) {
            //noinspection ResultOfMethodCallIgnored
            d.mkdirs();
        }
        return d;
    }

    public static File javaCrash(Context c) {
        return new File(dir(c), "krkr-java-crash.txt");
    }

    public static File nativeLog(Context c) {
        return new File(dir(c), "krkr-native.log");
    }

    public static File sentinel(Context c) {
        return new File(dir(c), "krkr-start.mark");
    }

    public static void write(File f, String text) {
        FileOutputStream o = null;
        try {
            o = new FileOutputStream(f, false);
            o.write(text.getBytes("UTF-8"));
        } catch (Throwable ignored) {
        } finally {
            closeQuietly(o);
        }
    }

    public static void append(File f, String text) {
        FileOutputStream o = null;
        try {
            o = new FileOutputStream(f, true);
            o.write(text.getBytes("UTF-8"));
            o.write('\n');
        } catch (Throwable ignored) {
        } finally {
            closeQuietly(o);
        }
    }

    private static void closeQuietly(FileOutputStream o) {
        if (o != null) {
            try {
                o.close();
            } catch (Throwable ignored) {
            }
        }
    }

    public static void clear(File f) {
        if (f != null && f.exists()) {
            //noinspection ResultOfMethodCallIgnored
            f.delete();
        }
    }

    /** 读文件尾部最多 maxBytes 字节；编码坏了也不抛 */
    public static String tail(File f, int maxBytes) {
        if (f == null || !f.exists()) return "";
        FileInputStream in = null;
        try {
            long len = f.length();
            long skip = Math.max(0, len - maxBytes);
            in = new FileInputStream(f);
            long skipped = 0;
            while (skipped < skip) {
                long s = in.skip(skip - skipped);
                if (s <= 0) break;
                skipped += s;
            }
            byte[] buf = new byte[(int) Math.min(maxBytes, len)];
            int n = 0;
            while (n < buf.length) {
                int r = in.read(buf, n, buf.length - n);
                if (r <= 0) break;
                n += r;
            }
            return new String(buf, 0, n, "UTF-8");
        } catch (Throwable t) {
            return "";
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    /** 自己进程的 logcat 尾部（Android 上应用只能读到自己的，正好够用） */
    public static String logcat(int lines) {
        Process p = null;
        try {
            p = Runtime.getRuntime().exec(
                    new String[]{"logcat", "-d", "-t", String.valueOf(lines)});
            BufferedReader r = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String s;
            int guard = 0;
            while ((s = r.readLine()) != null && guard < lines * 2) {
                sb.append(s).append('\n');
                guard++;
            }
            return sb.toString();
        } catch (Throwable t) {
            return "(读不到 logcat：" + t + ")";
        } finally {
            if (p != null) p.destroy();
        }
    }

    private static boolean handlerInstalled = false;

    /** 全局未捕获异常：Java 层崩了也留下堆栈 */
    public static void installHandler(final Context ctx) {
        if (handlerInstalled) return;
        handlerInstalled = true;
        final Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override
            public void uncaughtException(Thread th, Throwable ex) {
                try {
                    append(javaCrash(ctx), "=== uncaught in " + th.getName() + " ===\n"
                            + Log.getStackTraceString(ex));
                } catch (Throwable ignored) {
                }
                if (prev != null) prev.uncaughtException(th, ex);
            }
        });
    }

    /** 把现场打包成一段可直接阅读的文字 */
    public static String report(Context c, String head) {
        StringBuilder sb = new StringBuilder();
        sb.append(head).append("\n\n");

        String nat = tail(nativeLog(c), 6000);
        sb.append("— 原生日志 —\n")
                .append(nat.trim().isEmpty() ? "(空：还没进到引擎就退出了)" : nat)
                .append("\n\n");

        String jc = tail(javaCrash(c), 4000);
        if (!jc.trim().isEmpty()) {
            sb.append("— Java 堆栈 —\n").append(jc).append("\n\n");
        }

        sb.append("— logcat —\n").append(logcat(400));
        return sb.toString();
    }
}
