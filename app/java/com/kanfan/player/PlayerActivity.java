package com.kanfan.player;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

public class PlayerActivity extends Activity implements SurfaceHolder.Callback,
        MediaPlayer.OnPreparedListener, MediaPlayer.OnCompletionListener,
        MediaPlayer.OnErrorListener, DanmakuView.Clock {

    private FrameLayout root;
    private SurfaceView surface;
    private View controls;
    private LinearLayout topBar, bottomBar;
    private TextView titleView, timeCur, timeTotal, speedChip, seekPreview, bigHint;
    private TextView subtitleView, subChip, dmChip;
    private DanmakuView danmakuView;
    private ImageView playBtn, lockBtn;
    private SeekBar seekBar;

    // 外挂字幕 / 弹幕
    private static final int REQ_SUB = 3001, REQ_DM = 3002;
    private String fileName = "";
    private final List<Sub.Cue> cues = new ArrayList<>();
    private final List<String[]> sidecars = new ArrayList<>(); // {文件名, uri}
    private boolean subOn, dmOn;
    private String subName = "", dmName = "";
    private int lastCue = -2;

    private MediaPlayer mp;
    private boolean prepared = false;
    private boolean surfaceReady = false;
    private boolean controlsVisible = true;
    private boolean locked = false;
    private boolean finished = false;
    private int videoW = 0, videoH = 0;

    private Uri uri;
    private String title = "";
    private String bucket = "";
    private String treeUri = "";
    private String relPath = "";
    private List<Entry> playlist = new ArrayList<>();
    private int index = -1;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private float speed = 1.0f;
    private boolean tempSpeed = false;
    private static final float[] SPEEDS = {0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f};

    // 手势
    private static final int MODE_NONE = 0, MODE_SEEK = 1, MODE_BRIGHT = 2, MODE_VOL = 3;
    private int mode = MODE_NONE;
    private float downX, downY;
    private long downPos;
    private long pendingSeek = -1;
    private float startBrightness = -1f;
    private int startVolume;
    private int maxVolume;
    private AudioManager audio;

    private GestureDetector gestures;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        audio = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        maxVolume = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        startVolume = audio.getStreamVolume(AudioManager.STREAM_MUSIC);

        uri = Uri.parse(getIntent().getStringExtra("uri"));
        title = getIntent().getStringExtra("title");
        bucket = getIntent().getStringExtra("bucket");
        treeUri = getIntent().getStringExtra("tree");
        relPath = getIntent().getStringExtra("rel");
        fileName = getIntent().getStringExtra("name");
        if (title == null) title = "";
        if (bucket == null) bucket = "";
        if (treeUri == null) treeUri = "";
        if (relPath == null) relPath = "";
        if (fileName == null) fileName = "";

        buildUi();
        loadPlaylist();
        immerse();
        startPlay();
        loadSidecars();
    }

    private void immerse() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    // ---------------- UI ----------------

    private void buildUi() {
        root = new TouchLayer(this);
        root.setBackgroundColor(Color.BLACK);

        surface = new SurfaceView(this);
        surface.getHolder().addCallback(this);
        FrameLayout.LayoutParams slp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        slp.gravity = Gravity.CENTER;
        root.addView(surface, slp);

        // 弹幕层：不接收触摸，手势仍然落到 root 上
        danmakuView = new DanmakuView(this);
        danmakuView.setClock(this);
        danmakuView.setVisibility(View.GONE);
        root.addView(danmakuView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // 字幕层：底部居中，带描边阴影保证任何画面上都能看清
        subtitleView = new TextView(this);
        subtitleView.setTextColor(Color.WHITE);
        subtitleView.setTextSize(17f);
        subtitleView.setGravity(Gravity.CENTER);
        subtitleView.setMaxLines(3);
        subtitleView.setLineSpacing(Ui.dp(this, 3), 1f);
        subtitleView.setShadowLayer(Math.max(2f, Ui.dp(this, 3)), 0, Ui.dp(this, 1), 0xE6000000);
        subtitleView.setPadding(Ui.dp(this, 28), 0, Ui.dp(this, 28), 0);
        subtitleView.setVisibility(View.GONE);
        FrameLayout.LayoutParams subLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM);
        subLp.bottomMargin = Ui.dp(this, 118);

        controls = buildControls();
        root.addView(controls, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // 字幕放在控制条之上，避免被底部渐变压暗
        root.addView(subtitleView, subLp);

        seekPreview = new TextView(this);
        seekPreview.setTextColor(Color.WHITE);
        seekPreview.setTextSize(15f);
        seekPreview.setTypeface(Typeface.MONOSPACE);
        seekPreview.setPadding(Ui.dp(this, 16), Ui.dp(this, 9), Ui.dp(this, 16), Ui.dp(this, 9));
        seekPreview.setBackground(Ui.round(this, 0xD9000000, 10f));
        seekPreview.setVisibility(View.GONE);
        FrameLayout.LayoutParams plp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER);
        root.addView(seekPreview, plp);

        bigHint = new TextView(this);
        bigHint.setTextColor(Color.WHITE);
        bigHint.setTextSize(17f);
        bigHint.setGravity(Gravity.CENTER);
        bigHint.setPadding(Ui.dp(this, 20), Ui.dp(this, 12), Ui.dp(this, 20), Ui.dp(this, 12));
        bigHint.setBackground(Ui.round(this, 0xD9000000, 12f));
        bigHint.setVisibility(View.GONE);
        FrameLayout.LayoutParams hlp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER);
        root.addView(bigHint, hlp);

        setContentView(root);

        gestures = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public void onLongPress(MotionEvent e) {
                if (locked || !prepared || mode != MODE_NONE) return;
                tempSpeed = true;
                setTempSpeed(true);
                showHintSticky("▶▶ 2× 倍速中");
            }

            @Override
            public boolean onSingleTapConfirmed(MotionEvent e) {
                if (!locked) toggleControls();
                return true;
            }

            @Override
            public boolean onDoubleTap(MotionEvent e) {
                if (locked || !prepared || mp == null) return true;
                boolean wasPlaying = mp.isPlaying();
                togglePlay();
                showHint(wasPlaying ? "⏸  已暂停" : "▶  继续播放");
                return true;
            }
        });
    }

    private View buildControls() {
        FrameLayout layer = new FrameLayout(this);
        layer.setBackgroundColor(0x00000000);

        // 顶部
        topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setPadding(Ui.dp(this, 16), Ui.dp(this, 14), Ui.dp(this, 16), Ui.dp(this, 26));
        topBar.setBackground(gradientTop());

        ImageView back = iconBtn(R.drawable.ic_back);
        back.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        topBar.addView(back, new LinearLayout.LayoutParams(Ui.dp(this, 40), Ui.dp(this, 40)));

        titleView = new TextView(this);
        titleView.setText(title);
        titleView.setTextColor(Color.WHITE);
        titleView.setTextSize(15f);
        titleView.setMaxLines(1);
        titleView.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tlp.setMargins(Ui.dp(this, 12), 0, Ui.dp(this, 12), 0);
        topBar.addView(titleView, tlp);

        lockBtn = iconBtn(R.drawable.ic_unlock);
        lockBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                setLocked(!locked);
            }
        });
        topBar.addView(lockBtn, new LinearLayout.LayoutParams(Ui.dp(this, 40), Ui.dp(this, 40)));

        FrameLayout.LayoutParams tbar = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP);
        layer.addView(topBar, tbar);

        // 中间不放任何控件，避免挡住画面；播放控制统一放右下角
        bottomBar = new LinearLayout(this);
        bottomBar.setOrientation(LinearLayout.VERTICAL);
        bottomBar.setPadding(Ui.dp(this, 20), Ui.dp(this, 26), Ui.dp(this, 20), Ui.dp(this, 16));
        bottomBar.setBackground(gradientBottom());

        LinearLayout seekRow = new LinearLayout(this);
        seekRow.setOrientation(LinearLayout.HORIZONTAL);
        seekRow.setGravity(Gravity.CENTER_VERTICAL);

        timeCur = new TextView(this);
        timeCur.setTextColor(Color.WHITE);
        timeCur.setTextSize(12f);
        timeCur.setTypeface(Typeface.MONOSPACE);
        timeCur.setText("00:00");
        seekRow.addView(timeCur);

        seekBar = new SeekBar(this, null, android.R.attr.seekBarStyle);
        seekBar.setMax(1000);
        seekBar.setProgressTintList(ColorStateList.valueOf(getColor(R.color.accent)));
        seekBar.setThumbTintList(ColorStateList.valueOf(getColor(R.color.accent)));
        seekBar.setProgressBackgroundTintList(ColorStateList.valueOf(0x44FFFFFF));
        LinearLayout.LayoutParams slp2 = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        slp2.setMargins(Ui.dp(this, 12), 0, Ui.dp(this, 12), 0);
        seekRow.addView(seekBar, slp2);

        timeTotal = new TextView(this);
        timeTotal.setTextColor(0xCCFFFFFF);
        timeTotal.setTextSize(12f);
        timeTotal.setTypeface(Typeface.MONOSPACE);
        timeTotal.setText("--:--");
        seekRow.addView(timeTotal);

        bottomBar.addView(seekRow);

        LinearLayout chipRow = new LinearLayout(this);
        chipRow.setOrientation(LinearLayout.HORIZONTAL);
        chipRow.setGravity(Gravity.CENTER_VERTICAL);
        chipRow.setPadding(0, Ui.dp(this, 12), 0, 0);

        speedChip = new TextView(this);
        speedChip.setText("1.0×");
        speedChip.setTextColor(Color.WHITE);
        speedChip.setTextSize(13f);
        speedChip.setTypeface(Typeface.DEFAULT_BOLD);
        speedChip.setPadding(Ui.dp(this, 14), Ui.dp(this, 6), Ui.dp(this, 14), Ui.dp(this, 6));
        speedChip.setBackground(Ui.roundStroke(this, 0x22FFFFFF, 20f, 0x55FFFFFF, 1f));
        speedChip.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                cycleSpeed();
            }
        });
        chipRow.addView(speedChip);

        subChip = sideChip("字幕");
        subChip.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showSubMenu();
            }
        });
        LinearLayout.LayoutParams sclp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sclp.setMargins(Ui.dp(this, 8), 0, 0, 0);
        chipRow.addView(subChip, sclp);

        dmChip = sideChip("弹幕");
        dmChip.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showDmMenu();
            }
        });
        LinearLayout.LayoutParams dclp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dclp.setMargins(Ui.dp(this, 8), 0, 0, 0);
        chipRow.addView(dmChip, dclp);

        TextView tip = new TextView(this);
        tip.setText("双击播放/暂停 · 长按 2× 倍速");
        tip.setTextColor(0x88FFFFFF);
        tip.setTextSize(10.5f);
        tip.setPadding(Ui.dp(this, 10), 0, 0, 0);
        tip.setSingleLine(true);
        tip.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams tipLp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        chipRow.addView(tip, tipLp);

        ImageView prev = roundBtn(R.drawable.ic_prev);
        prev.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                playSibling(-1);
            }
        });
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(Ui.dp(this, 38), Ui.dp(this, 38));
        blp.setMargins(Ui.dp(this, 6), 0, Ui.dp(this, 8), 0);
        chipRow.addView(prev, blp);

        playBtn = roundBtn(R.drawable.ic_pause);
        playBtn.setBackground(Ui.circle(this, Ui.alpha(getColor(R.color.accent), 0.90f)));
        playBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                togglePlay();
            }
        });
        LinearLayout.LayoutParams plp2 = new LinearLayout.LayoutParams(Ui.dp(this, 46), Ui.dp(this, 46));
        plp2.setMargins(0, 0, Ui.dp(this, 8), 0);
        playBtn.setPadding(Ui.dp(this, 12), Ui.dp(this, 12), Ui.dp(this, 12), Ui.dp(this, 12));
        chipRow.addView(playBtn, plp2);

        ImageView next = roundBtn(R.drawable.ic_next);
        next.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                playSibling(1);
            }
        });
        chipRow.addView(next, new LinearLayout.LayoutParams(Ui.dp(this, 38), Ui.dp(this, 38)));

        bottomBar.addView(chipRow);

        FrameLayout.LayoutParams blp2 = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM);
        layer.addView(bottomBar, blp2);

        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                if (fromUser && prepared) {
                    long t = (long) (duration() * progress / 1000.0);
                    timeCur.setText(Ui.fmtDur(t));
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar sb) {
                ui.removeCallbacks(ticker);
            }

            @Override
            public void onStopTrackingTouch(SeekBar sb) {
                if (prepared) {
                    seekTo((long) (duration() * sb.getProgress() / 1000.0));
                }
                ui.post(ticker);
            }
        });

        return layer;
    }

    private android.graphics.drawable.Drawable gradientTop() {
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xB3000000, 0x00000000});
        return g;
    }

    private android.graphics.drawable.Drawable gradientBottom() {
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.BOTTOM_TOP,
                new int[]{0xCC000000, 0x00000000});
        return g;
    }

    private ImageView iconBtn(int res) {
        ImageView iv = new ImageView(this);
        iv.setImageResource(res);
        iv.setPadding(Ui.dp(this, 9), Ui.dp(this, 9), Ui.dp(this, 9), Ui.dp(this, 9));
        iv.setAlpha(0.95f);
        return iv;
    }

    private ImageView roundBtn(int res) {
        ImageView iv = new ImageView(this);
        iv.setImageResource(res);
        iv.setPadding(Ui.dp(this, 9), Ui.dp(this, 9), Ui.dp(this, 9), Ui.dp(this, 9));
        iv.setBackground(Ui.circle(this, 0x33FFFFFF));
        return iv;
    }

    // ---------------- 播放 ----------------

    private void startPlay() {
        release();
        finished = false;
        prepared = false;
        try {
            mp = new MediaPlayer();
            mp.setAudioStreamType(AudioManager.STREAM_MUSIC);
            mp.setDataSource(this, uri);
            mp.setOnPreparedListener(this);
            mp.setOnCompletionListener(this);
            mp.setOnErrorListener(this);
            attachSurface();
            mp.prepareAsync();
            updatePlayIcon(true);
        } catch (Exception e) {
            Toast.makeText(this, "无法播放：" + e.getMessage(), Toast.LENGTH_LONG).show();
            finish();
        }
    }

    /** 只有在 Surface 真正可用时才交给播放器；否则等 surfaceCreated 再挂 */
    private void attachSurface() {
        if (mp == null || !surfaceReady) return;
        SurfaceHolder h = surface.getHolder();
        if (h == null || h.getSurface() == null || !h.getSurface().isValid()) return;
        try {
            mp.setDisplay(h);
        } catch (Exception ignored) {
        }
    }

    @Override
    public void onPrepared(MediaPlayer m) {
        prepared = true;
        videoW = m.getVideoWidth();
        videoH = m.getVideoHeight();
        fitSurface();
        root.post(new Runnable() {
            @Override
            public void run() {
                fitSurface();
            }
        });
        timeTotal.setText(Ui.fmtDur(m.getDuration()));
        attachSurface();
        m.start();
        applySpeed();

        long saved = Store.getPos(this, uri.toString());
        if (saved > 5000 && saved < m.getDuration() - 8000) {
            m.seekTo((int) saved);
            showHint("继续上次进度\n" + Ui.fmtDur(saved));
        }
        ui.post(ticker);
        ui.post(subTicker);
        syncDanmaku();
        scheduleHide();
    }

    private void fitSurface() {
        if (videoW <= 0 || videoH <= 0) return;
        int vw = root.getWidth(), vh = root.getHeight();
        if (vw == 0 || vh == 0) return;
        float vr = (float) videoW / videoH;
        float rr = (float) vw / vh;
        int w, h;
        if (vr > rr) {
            w = vw;
            h = (int) (vw / vr);
        } else {
            h = vh;
            w = (int) (vh * vr);
        }
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) surface.getLayoutParams();
        if (lp.width == w && lp.height == h) return;
        lp.width = w;
        lp.height = h;
        lp.gravity = Gravity.CENTER;
        surface.setLayoutParams(lp);
    }

    private long duration() {
        return prepared && mp != null ? mp.getDuration() : 0;
    }

    private long getPosition() {
        return prepared && mp != null ? mp.getCurrentPosition() : 0;
    }

    private long clamp(long v) {
        long d = duration();
        if (d <= 0) return Math.max(0, v);
        return Math.max(0, Math.min(d, v));
    }

    private void seekTo(long ms) {
        if (!prepared || mp == null) return;
        mp.seekTo((int) clamp(ms));
        updateSeekUi();
        if (danmakuView != null) danmakuView.invalidate();
        updateSubtitle(clamp(ms));
    }

    private void togglePlay() {
        if (!prepared || mp == null) return;
        if (mp.isPlaying()) {
            mp.pause();
            updatePlayIcon(true);
            showControls();
        } else {
            if (finished) {
                finished = false;
                mp.seekTo(0);
            }
            mp.start();
            updatePlayIcon(false);
            scheduleHide();
        }
        syncDanmaku();
    }

    private void updatePlayIcon(boolean paused) {
        if (playBtn == null) return;
        playBtn.setImageResource(paused ? R.drawable.ic_play : R.drawable.ic_pause);
        int p = Ui.dp(this, paused ? 13 : 12);
        playBtn.setPadding(p, p, p, p);
    }

    private void cycleSpeed() {
        int idx = 0;
        for (int i = 0; i < SPEEDS.length; i++) {
            if (Math.abs(SPEEDS[i] - speed) < 0.001f) {
                idx = i;
                break;
            }
        }
        idx = (idx + 1) % SPEEDS.length;
        speed = SPEEDS[idx];
        String s = String.valueOf(speed);
        if (s.endsWith(".0")) s = s.substring(0, s.length() - 2);
        speedChip.setText(s + "×");
        applySpeed();
    }

    private void applySpeed() {
        if (mp == null || !prepared) return;
        if (Build.VERSION.SDK_INT >= 23) {
            try {
                boolean playing = mp.isPlaying();
                android.media.PlaybackParams pp = mp.getPlaybackParams();
                pp.setSpeed(speed);
                mp.setPlaybackParams(pp);
                if (!playing) mp.pause();
            } catch (Exception ignored) {
            }
        }
    }

    /** 长按期间的临时 2×，松手回到用户设定的倍速 */
    private void setTempSpeed(boolean on) {
        if (mp == null || !prepared || Build.VERSION.SDK_INT < 23) return;
        try {
            android.media.PlaybackParams pp = mp.getPlaybackParams();
            pp.setSpeed(on ? 2.0f : speed);
            mp.setPlaybackParams(pp);
        } catch (Exception ignored) {
        }
    }

    private void playSibling(int dir) {
        if (index < 0) {
            showHint("播放列表加载中…");
            return;
        }
        if (playlist.size() < 2) {
            showHint("没有其他集了");
            return;
        }
        int ni = index + dir;
        if (ni < 0 || ni >= playlist.size()) {
            showHint(dir > 0 ? "已经是最后一集" : "已经是第一集");
            return;
        }
        Entry v = playlist.get(ni);
        intentTo(v);
    }

    private void intentTo(Entry v) {
        if (danmakuView != null) danmakuView.setRunning(false);
        release();
        uri = v.uri;
        title = v.title;
        fileName = v.name;
        index = playlist.indexOf(v);
        titleView.setText(title);
        timeTotal.setText("--:--");
        timeCur.setText("00:00");
        seekBar.setProgress(0);
        cues.clear();
        subName = "";
        subOn = false;
        setChipActive(subChip, false);
        if (danmakuView != null) {
            danmakuView.setData(null);
            danmakuView.setVisibility(View.GONE);
        }
        dmName = "";
        dmOn = false;
        setChipActive(dmChip, false);
        sidecars.clear();
        startPlay();
        loadSidecars();   // 换集后重新找这一集的外挂字幕/弹幕
    }

    @Override
    public void onCompletion(MediaPlayer m) {
        finished = true;
        Store.clearPos(this, uri.toString());
        if (index >= 0 && index + 1 < playlist.size()) {
            playSibling(1);
        } else {
            updatePlayIcon(true);
            showControls();
            showHint("播放完毕");
        }
    }

    @Override
    public boolean onError(MediaPlayer m, int what, int extra) {
        Toast.makeText(this, "播放出错（" + what + "/" + extra + "），可能是编码不支持",
                Toast.LENGTH_LONG).show();
        return true;
    }

    // ---------------- 进度 ----------------

    private int tickCount = 0;

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            if (prepared && mp != null) {
                updateSeekUi();
                tickCount++;
                if (mp.isPlaying() && tickCount % 5 == 0) {
                    Store.savePos(PlayerActivity.this, uri.toString(), mp.getCurrentPosition());
                }
            }
            ui.postDelayed(this, 900);
        }
    };

    private void updateSeekUi() {
        long d = duration();
        long p = getPosition();
        if (d > 0) {
            timeCur.setText(Ui.fmtDur(p));
            timeTotal.setText(Ui.fmtDur(d));
            if (!seekBar.isPressed()) {
                seekBar.setProgress((int) (p * 1000 / d));
            }
        }
    }

    // ---------------- 控制条显隐 ----------------

    private void toggleControls() {
        if (controlsVisible) hideControls();
        else showControls();
    }

    private void showControls() {
        controlsVisible = true;
        controls.setVisibility(View.VISIBLE);
        controls.animate().alpha(1f).setDuration(160).start();
        layoutSubtitle(true);
        scheduleHide();
    }

    /** 控制条出现时把字幕往上顶，隐藏时回到底部 */
    private void layoutSubtitle(boolean controlsUp) {
        if (subtitleView == null || subtitleView.getLayoutParams() == null) return;
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) subtitleView.getLayoutParams();
        int m = Ui.dp(this, controlsUp ? 118 : 48);
        if (lp.bottomMargin != m) {
            lp.bottomMargin = m;
            subtitleView.setLayoutParams(lp);
        }
    }

    private void hideControls() {
        if (locked) return;
        controlsVisible = false;
        layoutSubtitle(false);
        controls.animate().alpha(0f).setDuration(200).withEndAction(new Runnable() {
            @Override
            public void run() {
                if (!controlsVisible) controls.setVisibility(View.INVISIBLE);
            }
        }).start();
    }

    private void scheduleHide() {
        ui.removeCallbacks(hideTask);
        if (prepared && mp != null && mp.isPlaying() && !locked) {
            ui.postDelayed(hideTask, 3500);
        }
    }

    private final Runnable hideTask = new Runnable() {
        @Override
        public void run() {
            if (!locked) hideControls();
        }
    };

    private void setLocked(boolean v) {
        locked = v;
        lockBtn.setImageResource(v ? R.drawable.ic_lock : R.drawable.ic_unlock);
        if (v) {
            hideControls();
            showHint("已锁定，点右下角解锁");
        } else {
            showControls();
        }
    }

    private void showHint(String s) {
        bigHint.setText(s);
        bigHint.setVisibility(View.VISIBLE);
        bigHint.setAlpha(1f);
        ui.removeCallbacks(hideHint);
        ui.postDelayed(hideHint, 1100);
    }

    /** 一直显示，不自动消失（长按倍速用） */
    private void showHintSticky(String s) {
        ui.removeCallbacks(hideHint);
        bigHint.animate().cancel();
        bigHint.setText(s);
        bigHint.setVisibility(View.VISIBLE);
        bigHint.setAlpha(1f);
    }

    private void hideBigHint() {
        ui.removeCallbacks(hideHint);
        bigHint.animate().cancel();
        bigHint.animate().alpha(0f).setDuration(180).withEndAction(new Runnable() {
            @Override
            public void run() {
                bigHint.setVisibility(View.GONE);
            }
        }).start();
    }

    private final Runnable hideHint = new Runnable() {
        @Override
        public void run() {
            hideBigHint();
        }
    };

    // ---------------- 播放列表 ----------------

    private void loadPlaylist() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                List<Entry> list = new ArrayList<>();
                if (treeUri.length() > 0) {
                    // 手动添加的文件夹：只在这个目录里找同系列，避免跨文件夹串播
                    try {
                        list = Scan.childrenOf(PlayerActivity.this, Uri.parse(treeUri), relPath);
                    } catch (Exception ignored) {
                    }
                }
                final String myKey = Ui.seriesKey(title);
                List<Entry> sameSeries = new ArrayList<>();
                for (Entry v : list) {
                    if (Ui.seriesKey(v.title).equals(myKey)) sameSeries.add(v);
                }
                if (!sameSeries.isEmpty()) list = sameSeries;

                Collections.sort(list, new java.util.Comparator<Entry>() {
                    @Override
                    public int compare(Entry a, Entry b) {
                        return Ui.NATURAL.compare(a.title, b.title);
                    }
                });
                final List<Entry> fl = list;
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        playlist.clear();
                        playlist.addAll(fl);
                        index = -1;
                        for (int i = 0; i < playlist.size(); i++) {
                            if (playlist.get(i).uri.equals(uri)) {
                                index = i;
                                break;
                            }
                        }
                        if (index >= 0 && playlist.size() > 1) {
                            titleView.setText(title + "   " + (index + 1) + "/" + playlist.size());
                        }
                    }
                });
            }
        }).start();
    }

    // ---------------- 手势触摸层 ----------------

    private class TouchLayer extends FrameLayout {
        TouchLayer(Context c) {
            super(c);
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            if (gestures != null) gestures.onTouchEvent(e);

            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = e.getX();
                    downY = e.getY();
                    downPos = getPosition();
                    mode = MODE_NONE;
                    tempSpeed = false;
                    pendingSeek = -1;
                    startBrightness = getWindow().getAttributes().screenBrightness;
                    if (startBrightness < 0) startBrightness = 0.5f;
                    startVolume = audio.getStreamVolume(AudioManager.STREAM_MUSIC);
                    return true;

                case MotionEvent.ACTION_MOVE:
                    if (locked || !prepared || tempSpeed) return true;
                    float dx = e.getX() - downX;
                    float dy = e.getY() - downY;
                    if (mode == MODE_NONE) {
                        if (Math.abs(dx) > Ui.dp(PlayerActivity.this, 12)
                                || Math.abs(dy) > Ui.dp(PlayerActivity.this, 12)) {
                            if (Math.abs(dx) > Math.abs(dy)) {
                                mode = MODE_SEEK;
                            } else {
                                mode = (downX < getWidth() / 2f) ? MODE_BRIGHT : MODE_VOL;
                            }
                        }
                    }
                    if (mode == MODE_SEEK) {
                        long d = duration();
                        if (d > 0) {
                            long delta = (long) ((dx / Math.max(1, getWidth())) * d * 0.5);
                            pendingSeek = clamp(downPos + delta);
                            seekPreview.setVisibility(View.VISIBLE);
                            seekPreview.setText(String.format(Locale.US, "%s / %s   %s%d:%02d",
                                    Ui.fmtDur(pendingSeek), Ui.fmtDur(d),
                                    delta >= 0 ? "+" : "-",
                                    Math.abs(delta) / 60000, (Math.abs(delta) / 1000) % 60));
                        }
                    } else if (mode == MODE_BRIGHT) {
                        float f = -dy / (getHeight() * 0.7f);
                        float b = Math.max(0.05f, Math.min(1f, startBrightness + f));
                        WindowManager.LayoutParams lp = getWindow().getAttributes();
                        lp.screenBrightness = b;
                        getWindow().setAttributes(lp);
                        showHint(String.format(Locale.US, "亮度 %d%%", (int) (b * 100)));
                    } else if (mode == MODE_VOL) {
                        int safeMax = Math.max(1, maxVolume);
                        int v = (int) (startVolume - (dy / (getHeight() * 0.7f)) * safeMax);
                        v = Math.max(0, Math.min(safeMax, v));
                        audio.setStreamVolume(AudioManager.STREAM_MUSIC, v, 0);
                        showHint("音量 " + (int) (v * 100f / safeMax) + "%");
                    }
                    return true;

                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (tempSpeed) {
                        tempSpeed = false;
                        setTempSpeed(false);
                        hideBigHint();
                        return true;
                    }
                    if (mode == MODE_SEEK && pendingSeek >= 0) {
                        seekTo(pendingSeek);
                        if (locked) {
                        } else {
                            showControls();
                        }
                    }
                    seekPreview.setVisibility(View.GONE);
                    pendingSeek = -1;
                    mode = MODE_NONE;
                    return true;
            }
            return true;
        }
    }

    // ---------------- 生命周期 ----------------

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        surfaceReady = true;
        attachSurface();
        fitSurface();
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        attachSurface();
        fitSurface();
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        surfaceReady = false;
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (prepared && mp != null) {
            Store.savePos(this, uri.toString(), mp.getCurrentPosition());
            if (mp.isPlaying()) mp.pause();
        }
        ui.removeCallbacks(ticker);
        ui.removeCallbacks(hideTask);
    }

    @Override
    protected void onResume() {
        super.onResume();
        immerse();
        if (prepared && mp != null) {
            ui.post(ticker);
            scheduleHide();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        ui.removeCallbacksAndMessages(null);
        if (danmakuView != null) danmakuView.setRunning(false);
        release();
    }

    private void release() {
        try {
            if (mp != null) {
                mp.reset();
                mp.release();
            }
        } catch (Exception ignored) {
        }
        mp = null;
        prepared = false;
    }

    // ---------------- 外挂字幕 / 弹幕 ----------------

    private TextView sideChip(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(12f);
        t.setPadding(Ui.dp(this, 12), Ui.dp(this, 6), Ui.dp(this, 12), Ui.dp(this, 6));
        setChipActive(t, false);
        return t;
    }

    private void setChipActive(TextView t, boolean on) {
        if (t == null) return;
        if (on) {
            t.setTextColor(getColor(R.color.bg));
            t.setBackground(Ui.round(this, getColor(R.color.accent), 20f));
        } else {
            t.setTextColor(0xCCFFFFFF);
            t.setBackground(Ui.roundStroke(this, 0x22FFFFFF, 20f, 0x55FFFFFF, 1f));
        }
    }

    private final Runnable subTicker = new Runnable() {
        @Override
        public void run() {
            if (prepared && mp != null) updateSubtitle(mp.getCurrentPosition());
            ui.postDelayed(this, 120);
        }
    };

    private void updateSubtitle(long ms) {
        if (subtitleView == null) return;
        if (!subOn || cues.isEmpty()) {
            subtitleView.setText("");
            subtitleView.setVisibility(View.GONE);
            lastCue = -2;
            return;
        }
        int idx = Sub.indexAt(cues, ms);
        if (idx == lastCue) return;
        lastCue = idx;
        if (idx < 0) {
            subtitleView.setText("");
            subtitleView.setVisibility(View.GONE);
        } else {
            subtitleView.setText(cues.get(idx).text);
            subtitleView.setVisibility(View.VISIBLE);
        }
    }

    @Override
    public long position() {
        try {
            return mp == null ? 0 : mp.getCurrentPosition();
        } catch (Exception e) {
            return 0;
        }
    }

    @Override
    public boolean playing() {
        try {
            return mp != null && mp.isPlaying();
        } catch (Exception e) {
            return false;
        }
    }

    private void syncDanmaku() {
        if (danmakuView == null) return;
        danmakuView.setDanmakuEnabled(dmOn);
        danmakuView.setVisibility(dmOn ? View.VISIBLE : View.GONE);
        danmakuView.setRunning(dmOn && playing());
    }

    private String baseName(String n) {
        if (n == null || n.isEmpty()) return title;
        int dot = n.lastIndexOf('.');
        return dot > 0 ? n.substring(0, dot) : n;
    }

    /** 文件名是否以视频名为前缀（避免 xxx01 匹配到 xxx010） */
    private boolean sameBase(String name, String base) {
        if (name == null || base == null || base.isEmpty()) return false;
        String a = name.toLowerCase(Locale.US), b = base.toLowerCase(Locale.US);
        if (!a.startsWith(b)) return false;
        if (a.length() == b.length()) return true;
        char c = a.charAt(b.length());
        return c == '.' || c == '_' || c == '-' || c == ' ' || c == '['
                || c == '(' || c == '（' || c == '【';
    }

    /** 打开视频后，在同一个目录里找同名的字幕 / 弹幕 */
    private void loadSidecars() {
        if (treeUri == null || treeUri.isEmpty()) return;
        final Uri tree = Uri.parse(treeUri);
        final String rel = relPath;
        final String base = baseName(fileName);
        new Thread(new Runnable() {
            @Override
            public void run() {
                final List<String[]> files = Scan.siblings(PlayerActivity.this, tree, rel);
                String su = null, sn = "", du = null, dn = "";
                for (String[] f : files) {
                    if (!sameBase(f[0], base)) continue;
                    String low = f[0].toLowerCase(Locale.US);
                    if (su == null && Sub.isSubtitleName(low)) {
                        su = f[1];
                        sn = f[0];
                    } else if (du == null && low.endsWith(".xml")) {
                        du = f[1];
                        dn = f[0];
                    }
                }
                final String fsu = su, fsn = sn, fdu = du, fdn = dn;
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        sidecars.clear();
                        sidecars.addAll(files);
                        if (fsu != null) loadSubFrom(Uri.parse(fsu), fsn);
                        if (fdu != null) loadDmFrom(Uri.parse(fdu), fdn);
                    }
                });
            }
        }).start();
    }

    private void loadSubFrom(final Uri u, final String name) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                String txt = Scan.readText(PlayerActivity.this, u, 8 * 1024 * 1024);
                final List<Sub.Cue> list = Sub.parse(txt, name);
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        cues.clear();
                        cues.addAll(list);
                        subOn = !cues.isEmpty();
                        subName = subOn ? name : "";
                        lastCue = -2;
                        setChipActive(subChip, subOn);
                        updateSubtitle(position());
                        Toast.makeText(PlayerActivity.this,
                                subOn ? "字幕：" + name + "（" + cues.size() + " 条）"
                                        : "没从这个文件里解析到字幕",
                                Toast.LENGTH_SHORT).show();
                    }
                });
            }
        }).start();
    }

    private void loadDmFrom(final Uri u, final String name) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                String txt = Scan.readText(PlayerActivity.this, u, 24 * 1024 * 1024);
                final List<Danmaku.Item> list = Danmaku.parse(txt, name);
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        danmakuView.setData(list);
                        dmOn = !list.isEmpty();
                        dmName = dmOn ? name : "";
                        setChipActive(dmChip, dmOn);
                        syncDanmaku();
                        danmakuView.invalidate();
                        Toast.makeText(PlayerActivity.this,
                                dmOn ? "弹幕：" + name + "（" + list.size() + " 条）"
                                        : "没从这个文件里解析到弹幕",
                                Toast.LENGTH_SHORT).show();
                    }
                });
            }
        }).start();
    }

    private void showSubMenu() {
        final List<String[]> cand = new ArrayList<>();
        for (String[] f : sidecars) {
            if (Sub.isSubtitleName(f[0])) cand.add(f);
        }
        final List<String> labels = new ArrayList<>();
        labels.add(subOn ? "关闭字幕（当前：" + subName + "）" : "关闭字幕");
        for (String[] f : cand) labels.add("加载：" + f[0]);
        labels.add("从文件选择…");

        new AlertDialog.Builder(this)
                .setTitle("外挂字幕（SRT / ASS / VTT）")
                .setItems(labels.toArray(new String[labels.size()]),
                        new android.content.DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(android.content.DialogInterface d, int which) {
                                if (which == 0) {
                                    cues.clear();
                                    subOn = false;
                                    subName = "";
                                    setChipActive(subChip, false);
                                    updateSubtitle(position());
                                } else if (which == labels.size() - 1) {
                                    pickFile(REQ_SUB);
                                } else {
                                    String[] f = cand.get(which - 1);
                                    loadSubFrom(Uri.parse(f[1]), f[0]);
                                }
                            }
                        })
                .show();
    }

    private void showDmMenu() {
        final List<String[]> cand = new ArrayList<>();
        for (String[] f : sidecars) {
            String low = f[0].toLowerCase(Locale.US);
            if (low.endsWith(".xml") || low.endsWith(".json")) cand.add(f);
        }
        final List<String> labels = new ArrayList<>();
        labels.add(dmOn ? "关闭弹幕（当前：" + dmName + "）" : "关闭弹幕");
        for (String[] f : cand) labels.add("加载：" + f[0]);
        labels.add("从文件选择（.xml / .ass）…");

        new AlertDialog.Builder(this)
                .setTitle("外挂弹幕")
                .setItems(labels.toArray(new String[labels.size()]),
                        new android.content.DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(android.content.DialogInterface d, int which) {
                                if (which == 0) {
                                    dmOn = false;
                                    dmName = "";
                                    setChipActive(dmChip, false);
                                    syncDanmaku();
                                } else if (which == labels.size() - 1) {
                                    pickFile(REQ_DM);
                                } else {
                                    String[] f = cand.get(which - 1);
                                    loadDmFrom(Uri.parse(f[1]), f[0]);
                                }
                            }
                        })
                .show();
    }

    private void pickFile(int req) {
        try {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("*/*");
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivityForResult(i, req);
        } catch (Exception e) {
            Toast.makeText(this, "系统没有可用的文件选择器", Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri u = data.getData();
        String nm = Scan.displayName(this, u);
        if (requestCode == REQ_SUB) loadSubFrom(u, nm);
        else if (requestCode == REQ_DM) loadDmFrom(u, nm);
    }
}
