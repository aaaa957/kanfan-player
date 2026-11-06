package com.kanfan.player;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;

import com.onscripter.ONScripterView;
import com.onscripter.exception.NativeONSException;

/**
 * ONS（NScripter）运行器：直接托管 ONScripter 引擎的 GLSurfaceView。
 *
 * 引擎用 content:// 直接读 SAF 目录，存档也写回同一个目录（需要写权限）。
 * 只有 arm64 的原生库，32 位设备会明确提示。
 */
public class OnsActivity extends Activity {

    private ONScripterView game;
    private String treeUri = "", rel = "", name = "";
    private boolean exited;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        treeUri = getIntent().getStringExtra("tree");
        rel = getIntent().getStringExtra("rel");
        name = getIntent().getStringExtra("name");
        if (treeUri == null) treeUri = "";
        if (rel == null) rel = "";

        if (!hasArm64()) {
            showFatal("这台设备是 32 位的，ONS 引擎只编了 arm64 版本。");
            return;
        }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        immersive();

        Uri gameUri = gameUri();
        if (gameUri == null) {
            showFatal("找不到游戏目录，可能被移动或权限失效了。");
            return;
        }
        try {
            game = new ONScripterView.Builder(this, gameUri)
                    .useHQAudio()
                    .useRenderOutline()
                    .create();
        } catch (Throwable t) {
            showFatal("引擎没能启动：\n" + t);
            return;
        }
        game.setONScripterEventListener(new ONScripterView.ONScripterEventListener() {
            @Override
            public void autoStateChanged(boolean selected) {
            }

            @Override
            public void skipStateChanged(boolean selected) {
            }

            @Override
            public void singlePageStateChanged(boolean selected) {
            }

            @Override
            public void videoRequested(Uri videoUri, boolean clickToSkip, boolean shouldLoop) {
                // 引擎自带的视频播放回调，交给系统播放器
                try {
                    android.content.Intent i = new android.content.Intent(android.content.Intent.ACTION_VIEW);
                    i.setDataAndType(videoUri, "video/*");
                    i.addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    startActivity(i);
                } catch (Exception ignored) {
                }
            }

            @Override
            public void onNativeError(NativeONSException e, String line, String backtrace) {
                showFatal("引擎报错：\n" + (e == null ? "" : e.getMessage())
                        + (line == null ? "" : "\n" + line));
            }

            @Override
            public void onReady() {
            }

            @Override
            public void onUserMessage(ONScripterView.UserMessage messageId) {
            }

            @Override
            public void onGameFinished() {
                finish();
            }
        });

        FrameLayout root = new FrameLayout(this);
        root.addView(game, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        TextView exit = new TextView(this);
        exit.setText("×");
        exit.setTextColor(0xCCFFFFFF);
        exit.setTextSize(20f);
        exit.setTypeface(Typeface.DEFAULT_BOLD);
        exit.setGravity(Gravity.CENTER);
        exit.setBackground(Ui.round(this, 0x66000000, 18f));
        exit.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                confirmExit();
            }
        });
        FrameLayout.LayoutParams elp = new FrameLayout.LayoutParams(
                Ui.dp(this, 36), Ui.dp(this, 36));
        elp.setMargins(Ui.dp(this, 10), Ui.dp(this, 10), 0, 0);
        elp.gravity = Gravity.TOP | Gravity.LEFT;
        root.addView(exit, elp);

        setContentView(root);
    }

    private boolean hasArm64() {
        if (Build.VERSION.SDK_INT < 21) return false;
        for (String abi : Build.SUPPORTED_64_BIT_ABIS) {
            if ("arm64-v8a".equals(abi)) return true;
        }
        return false;
    }

    private Uri gameUri() {
        try {
            Uri tree = Uri.parse(treeUri);
            String docId = Scan.docIdOf(this, tree, rel);
            if (docId == null) return null;
            return DocumentsContract.buildDocumentUriUsingTree(tree, docId);
        } catch (Throwable t) {
            return null;
        }
    }

    private void immersive() {
        try {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        } catch (Throwable ignored) {
        }
    }

    private void showFatal(String msg) {
        TextView t = new TextView(this);
        t.setText(msg);
        t.setTextColor(0xFFDDDDDD);
        t.setTextSize(14f);
        t.setGravity(Gravity.CENTER);
        t.setLineSpacing(0, 1.5f);
        int p = Ui.dp(this, 28);
        t.setPadding(p, p, p, p);
        t.setBackgroundColor(0xFF101014);
        setContentView(t);
        t.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
    }

    private void confirmExit() {
        new AlertDialog.Builder(this)
                .setMessage("退出游戏？")
                .setNegativeButton("继续玩", null)
                .setPositiveButton("退出", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        quit();
                    }
                })
                .show();
    }

    private void quit() {
        exited = true;
        if (game != null) {
            try {
                game.exitApp();
            } catch (Throwable ignored) {
            }
        }
        finish();
    }

    @Override
    public void onBackPressed() {
        confirmExit();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (game != null) game.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        immersive();
        if (game != null) game.onResume();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (!exited && game != null) {
            try {
                game.exitApp();
            } catch (Throwable ignored) {
            }
        }
        game = null;
    }
}
