package com.kanfan.player;

/**
 * 原生解压层。
 * rar 走 unrar（流式写盘），7z 走 7-Zip 的 C 解码器（整块解到内存）。
 */
public final class Nat {

    /** 逐文件回调：native 把条目解到 tmpFile，Java 拷进目标目录后自己删掉 */
    public interface Sink {
        void onEntry(int index, long size, String name, String tmpFile);

        void onProgress(long bytes);
    }

    private static Boolean loaded;

    public static synchronized boolean ok() {
        if (loaded == null) {
            try {
                System.loadLibrary("kanfan");
                loaded = Boolean.TRUE;
            } catch (Throwable t) {
                loaded = Boolean.FALSE;
            }
        }
        return loaded;
    }

    /** 返回 "OK\nsize\tisdir\tenc\tname..."；失败返回 "E数字" */
    public static native String rarList(String path, String pwd);

    /** 返回 0 成功，否则 unrar 错误码 */
    public static native int rarExtractAll(String path, String pwd, String tmpDir, Sink sink);

    /** 返回 "OK\nsize\tisdir\tname..."；失败返回 "E数字" */
    public static native String szList(String path);

    /** 把第 index 个条目解到 outFile，返回 0 成功（内部会缓存固态块） */
    public static native int szExtractOne(String path, int index, String outFile);

    /** 释放 7z 打开状态与块缓存 */
    public static native void szClose();

    private Nat() {
    }
}
