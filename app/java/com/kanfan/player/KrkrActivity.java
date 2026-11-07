package com.kanfan.player;

import android.os.Bundle;
import android.os.SystemClock;
import android.util.Log;

import org.libsdl.app.SDLActivity;

import java.io.File;

/**
 * KiriKiri 运行器：SDLActivity 负责 Surface / 输入 / 加载 .so，
 * 这里只把游戏目录作为参数传给 native 的 main()。
 *
 * 引擎崩在 C++ 里时 Java 收不到，所以用「哨兵文件」判断这次是否活下来：
 * 起来 5 秒后删掉哨兵；没删掉，片库下次回来就把现场摊开给用户看。
 */
public class KrkrActivity extends SDLActivity {

    private File sentinel;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // 有些 Kirikiroid2 移植版把 system/bin/*.xp3 提到了根目录，脚本却还按老路径找。
        // 先补好这套软链，不然 initialize.tjs 会死在 "Cannot find storage .../system/bin/video.xp3"。
        prepareLayout(getIntent() == null ? null : getIntent().getStringExtra("path"));

        sentinel = Diag.sentinel(this);
        Diag.write(sentinel, String.valueOf(SystemClock.elapsedRealtime()));

        // 先自己加载一遍：失败能落在 Java 能捕获的地方，不至于只给一个闪退
        try {
            System.loadLibrary("SDL2");
            System.loadLibrary("main");
        } catch (Throwable t) {
            Diag.append(Diag.javaCrash(this),
                    "加载原生库失败：" + Log.getStackTraceString(t));
            finish();
            return;
        }

        try {
            super.onCreate(savedInstanceState);
        } catch (Throwable t) {
            Diag.append(Diag.javaCrash(this),
                    "SDLActivity.onCreate 失败：" + Log.getStackTraceString(t));
            finish();
            return;
        }

        try {
            getWindow().getDecorView().postDelayed(new Runnable() {
                @Override
                public void run() {
                    Diag.clear(sentinel);
                }
            }, 5000);
        } catch (Throwable ignored) {
        }
    }

    @Override
    protected String[] getArguments() {
        String path = getIntent().getStringExtra("path");
        if (path == null || path.isEmpty()) return new String[0];
        return new String[]{path};
    }

    @Override
    protected String[] getLibraries() {
        return new String[]{"SDL2", "main"};
    }

    /**
     * 补 system/bin 下的软链。
     *
     * “kirikiroid 专版”这类移植版会把原本放在 system/bin/ 的 video.xp3、others.xp3
     * 提到游戏根目录，但脚本还是按 system/bin/xxx.xp3 去加载。
     * 这里给根目录每个 .xp3 建一个 system/bin/ 下的软链，脚本就找得到了。
     *
     * 只对自己有写权限的目录有效（应用内部存储可以，/sdcard 的 FUSE 不支持软链，
     * 失败就退回硬链，再失败就静默跳过 —— 大不了回到原来的报错）。
     */
    private void prepareLayout(String path) {
        if (path == null || path.isEmpty()) return;
        try {
            File dir = new File(path);
            if (!dir.isDirectory()) return;

            File bin = new File(dir, "system/bin");
            if (!bin.isDirectory() && !bin.mkdirs()) return;

            File[] kids = dir.listFiles();
            if (kids == null) return;

            int made = 0;
            for (File f : kids) {
                if (!f.isFile()) continue;
                String name = f.getName();
                if (!name.toLowerCase(java.util.Locale.US).endsWith(".xp3")) continue;

                File link = new File(bin, name);
                if (link.exists()) continue;
                try {
                    android.system.Os.symlink("../../" + name, link.getAbsolutePath());
                    made++;
                } catch (Throwable t1) {
                    try {
                        android.system.Os.link(f.getAbsolutePath(), link.getAbsolutePath());
                        made++;
                    } catch (Throwable t2) {
                        // 这个目录不支持链接，算了
                    }
                }
            }
            if (made > 0) {
                Diag.append(Diag.javaCrash(this), "linked " + made + " xp3 into system/bin");
            }
        } catch (Throwable ignored) {
        }
    }
}
