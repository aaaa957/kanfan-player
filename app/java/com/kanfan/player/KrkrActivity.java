package com.kanfan.player;

import org.libsdl.app.SDLActivity;

/**
 * KiriKiri 运行器：SDLActivity 负责 Surface / 输入 / 加载 .so，
 * 这里只把游戏目录作为参数传给 native 的 main()。
 */
public class KrkrActivity extends SDLActivity {

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
}
