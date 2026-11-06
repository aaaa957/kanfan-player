package com.kanfan.player;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.TextView;

import java.util.Locale;

/**
 * 跑 TyranoBuilder 移植版：把一个 SAF 目录用本地 HTTP 服务喂给 WebView。
 *
 * 端口按游戏路径算，固定下来——WebView 的 localStorage 是按 origin（含端口）隔离的，
 * 端口一变存档就“丢”了。
 */
public class TyranoActivity extends Activity {

    private static final String TAG = "Tyrano";

    private WebView web;
    private GameServer server;
    private String treeUri = "", rel = "", name = "";
    private TextView loading, exitBtn;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private volatile boolean dead;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        treeUri = getIntent().getStringExtra("tree");
        rel = getIntent().getStringExtra("rel");
        name = getIntent().getStringExtra("name");
        if (treeUri == null) treeUri = "";
        if (rel == null) rel = "";
        if (name == null) name = "";

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        buildUi();
        startServer();
    }

    private void buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF000000);

        web = new WebView(this);
        web.setBackgroundColor(0xFF000000);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        if (Build.VERSION.SDK_INT >= 21) {
            s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        }
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onConsoleMessage(String m, int line, String src) {
                Log.d(TAG, m + " @" + line);
            }
        });
        web.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView v, String url) {
                if (loading != null) loading.setVisibility(View.GONE);
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView v, String url) {
                if (url == null) return true;
                if (url.startsWith("http://127.0.0.1:") || url.startsWith("about:")) return false;
                // 游戏里的外链交给浏览器，不在本地服务里乱跳
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
                } catch (Exception ignored) {
                }
                return true;
            }
        });
        root.addView(web, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        loading = new TextView(this);
        loading.setText("正在启动「" + name + "」…");
        loading.setTextColor(0xFFCCCCCC);
        loading.setTextSize(14f);
        loading.setGravity(Gravity.CENTER);
        root.addView(loading, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER));

        exitBtn = new TextView(this);
        exitBtn.setText("×");
        exitBtn.setTextColor(0xCCFFFFFF);
        exitBtn.setTextSize(20f);
        exitBtn.setTypeface(Typeface.DEFAULT_BOLD);
        exitBtn.setGravity(Gravity.CENTER);
        exitBtn.setBackground(Ui.round(this, 0x66000000, 18f));
        exitBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                confirmExit();
            }
        });
        FrameLayout.LayoutParams elp = new FrameLayout.LayoutParams(
                Ui.dp(this, 36), Ui.dp(this, 36));
        elp.setMargins(Ui.dp(this, 10), Ui.dp(this, 10), 0, 0);
        elp.gravity = Gravity.TOP | Gravity.LEFT;
        root.addView(exitBtn, elp);

        setContentView(root);
        immersive();
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

    // ---------------- 起服务 ----------------

    private void startServer() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                int base = 18700 + Math.abs((treeUri + "|" + rel).hashCode()) % 200;
                GameServer gs = null;
                Exception last = null;
                for (int i = 0; i < 24 && gs == null; i++) {
                    try {
                        gs = new GameServer(TyranoActivity.this,
                                Uri.parse(treeUri), rel, base + i);
                    } catch (Exception e) {
                        last = e;
                    }
                }
                if (gs == null) {
                    final String m = last == null ? "端口被占用" : last.getMessage();
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            fail("本地服务起不来：" + m);
                        }
                    });
                    return;
                }
                final GameServer opened = gs;
                server = opened;
                final String url = "http://127.0.0.1:" + opened.port() + "/index.html";
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        if (dead) return;
                        loading.setText("正在载入…");
                        web.loadUrl(url);
                        // 给引擎 3 秒；还停在白屏就把提示换成可操作的建议
                        ui.postDelayed(new Runnable() {
                            @Override
                            public void run() {
                                if (!dead && loading.getVisibility() == View.VISIBLE) {
                                    loading.setText("载入较慢…\n如果一直黑屏，点左上角 × 退出");
                                }
                            }
                        }, 3000);
                    }
                });
            }
        }, "tyrano-boot").start();
    }

    private void fail(String msg) {
        loading.setText(msg + "\n\n点左上角 × 退出");
        loading.setTextColor(Color.parseColor("#E88A8A"));
    }

    @Override
    public void onBackPressed() {
        if (web != null && web.canGoBack()) {
            web.goBack();
            return;
        }
        confirmExit();
    }

    private void confirmExit() {
        new AlertDialog.Builder(this)
                .setMessage("退出游戏？\n\n存档在应用内，下次从同一入口进来会继续。")
                .setNegativeButton("继续玩", null)
                .setPositiveButton("退出", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        finish();
                    }
                })
                .show();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (web != null) web.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        immersive();
        if (web != null) web.onResume();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        dead = true;
        if (server != null) {
            server.close();
            server = null;
        }
        if (web != null) {
            try {
                web.stopLoading();
                web.loadUrl("about:blank");
                web.destroy();
            } catch (Throwable ignored) {
            }
            web = null;
        }
    }
}
