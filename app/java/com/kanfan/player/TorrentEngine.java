package com.kanfan.player;

import android.content.Context;
import android.content.SharedPreferences;

import com.frostwire.jlibtorrent.AddTorrentParams;
import com.frostwire.jlibtorrent.SessionHandle;
import com.frostwire.jlibtorrent.SessionManager;
import com.frostwire.jlibtorrent.Sha1Hash;
import com.frostwire.jlibtorrent.TorrentHandle;
import com.frostwire.jlibtorrent.TorrentInfo;
import com.frostwire.jlibtorrent.TorrentStatus;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * BT 引擎的外壳：jlibtorrent（libtorrent 的 Java/JNI 绑定）。
 *
 * 只做三件事：加任务、读状态、存任务列表。真正的协议、DHT、加密都在 .so 里。
 * 全进程单例，Service 与界面共用。
 */
public final class TorrentEngine {

    public static final int ST_WAIT = 0;    // 刚加进来，等元数据
    public static final int ST_META = 1;    // 正在取 magnet 元数据
    public static final int ST_DOWN = 2;    // 下载中
    public static final int ST_CHECK = 3;   // 校验已有文件
    public static final int ST_DONE = 4;    // 下完了（不做种）
    public static final int ST_PAUSED = 5;
    public static final int ST_ERROR = 6;
    public static final int ST_SEED = 7;    // 做种中

    public static final class Task {
        public String source = "";     // magnet / 本地 .torrent 路径
        public String name = "";
        public String hash = "";
        public long done, total;
        public int rate;
        public int up;
        public int peers, seeds;
        public int state = ST_WAIT;
        public boolean paused;
        public String error;

        transient TorrentHandle handle;

        public int percent() {
            if (total <= 0) return state == ST_DONE ? 100 : 0;
            return (int) Math.min(100, done * 100 / total);
        }

        public Task copy() {
            Task t = new Task();
            t.source = source;
            t.name = name;
            t.hash = hash;
            t.done = done;
            t.total = total;
            t.rate = rate;
            t.up = up;
            t.peers = peers;
            t.seeds = seeds;
            t.state = state;
            t.paused = paused;
            t.error = error;
            return t;
        }
    }

    private static TorrentEngine inst;

    public static synchronized TorrentEngine get(Context c) {
        if (inst == null) inst = new TorrentEngine(c.getApplicationContext());
        return inst;
    }

    private final Context app;
    private final List<Task> tasks = new ArrayList<Task>();
    private SessionManager sm;
    private SessionHandle sh;
    private boolean started;
    private boolean seeding;
    private File root;

    private TorrentEngine(Context c) {
        app = c;
        root = downloadRoot(c);
        seeding = sp().getBoolean("seeding", false);
        load();
    }

    /** 下完是否继续做种（默认否） */
    public boolean seeding() {
        return seeding;
    }

    /** 切换做种：关掉时把已完成的任务立刻停掉，打开时让它们重新跑起来 */
    public synchronized void setSeeding(boolean on) {
        seeding = on;
        sp().edit().putBoolean("seeding", on).apply();
        for (Task t : tasks) {
            if (t.handle == null) continue;
            try {
                if (!on && t.state == ST_SEED) {
                    t.handle.pause();
                    t.paused = true;
                    t.state = ST_DONE;
                } else if (on && t.state == ST_DONE && t.handle.status().isFinished()) {
                    t.handle.resume();
                    t.paused = false;
                    t.state = ST_SEED;
                }
            } catch (Throwable ignored) {
            }
        }
    }

    public static File downloadRoot(Context c) {
        File base = c.getExternalFilesDir("downloads");
        if (base == null) base = new File(c.getFilesDir(), "downloads");
        if (!base.exists()) base.mkdirs();
        return base;
    }

    public File root() {
        return root;
    }

    public synchronized boolean isStarted() {
        return started;
    }

    /** 建 session；已经有就直接返回 */
    public synchronized void start() {
        if (started) return;
        sm = new SessionManager();
        sm.start();
        sh = new SessionHandle(sm.swig());
        started = true;
    }

    /** 停 session，会在后台线程里做（可能要一两秒） */
    public void stopAsync() {
        final SessionManager m;
        synchronized (this) {
            m = sm;
            sm = null;
            sh = null;
            started = false;
        }
        if (m == null) return;
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    m.stop();
                } catch (Throwable ignored) {
                }
            }
        }, "torrent-stop").start();
    }

    public synchronized SessionManager session() {
        return sm;
    }

    // ---------------- 任务 ----------------

    public synchronized List<Task> snapshot() {
        List<Task> out = new ArrayList<Task>(tasks.size());
        for (Task t : tasks) out.add(t.copy());
        return out;
    }

    public synchronized boolean hasActive() {
        for (Task t : tasks) {
            if (t.state != ST_DONE && t.state != ST_SEED
                    && t.state != ST_PAUSED && t.state != ST_ERROR) return true;
        }
        return false;
    }

    public synchronized long totalRate() {
        long sum = 0;
        for (Task t : tasks) sum += t.rate;
        return sum;
    }

    /** magnet 或本地 .torrent 路径；返回 null 表示成功，否则是给用户看的错误 */
    public synchronized String add(String text) {
        if (text == null) return "没有内容";
        text = text.trim();
        if (text.isEmpty()) return "没有内容";
        start();

        AddTorrentParams p;
        Sha1Hash ih;
        String display = "";
        try {
            if (text.startsWith("magnet:")) {
                p = AddTorrentParams.parseMagnetUri(text);
                ih = p.infoHash();
                display = p.name() == null ? "" : p.name();
            } else {
                File f = new File(text);
                if (!f.isFile()) return "找不到这个种子文件";
                TorrentInfo ti = new TorrentInfo(f);
                p = new AddTorrentParams();
                p.torrentInfo(ti);
                ih = ti.infoHash();
                display = ti.name();
            }
        } catch (Throwable t) {
            return "解析失败：" + msg(t);
        }
        if (ih == null || ih.isAllZeros()) return "这个磁力里没有 info hash";

        String hex = ih.toHex();
        for (Task t : tasks) {
            if (t.hash.equalsIgnoreCase(hex)) return "已经在下载列表里了";
        }

        p.savePath(root.getAbsolutePath());
        try {
            sh.asyncAddTorrent(p);
        } catch (Throwable t) {
            return "加不进去：" + msg(t);
        }

        Task t = new Task();
        t.hash = hex;
        t.source = text;
        t.name = display == null ? "" : display;
        t.total = 0;
        t.state = ST_META;
        tasks.add(t);
        save();
        return null;
    }

    /** 从 jlibtorrent 拉一遍状态；Service 每秒调一次 */
    public synchronized void refresh() {
        if (!started || sm == null) return;
        Map<String, TorrentHandle> byHash = new HashMap<String, TorrentHandle>();
        try {
            for (TorrentHandle h : sh.torrents()) {
                byHash.put(h.infoHash().toHex().toLowerCase(Locale.US), h);
            }
        } catch (Throwable ignored) {
        }
        for (Task t : tasks) {
            TorrentHandle h = t.handle;
            if (h == null || !h.isValid()) {
                h = byHash.get(t.hash.toLowerCase(Locale.US));
                t.handle = h;
            }
            if (h == null) {
                if (t.state == ST_WAIT) t.state = ST_META;
                continue;
            }
            try {
                applyStatus(t, h);
            } catch (Throwable ignored) {
            }
        }
    }

    private void applyStatus(Task t, TorrentHandle h) {
        TorrentStatus st = h.status();
        String nm = st.name();
        if (nm != null && !nm.isEmpty()) t.name = nm;
        t.total = st.totalWanted();
        t.done = st.totalWantedDone();
        t.rate = st.downloadRate();
        t.up = st.uploadRate();
        t.peers = st.numPeers();
        t.seeds = st.numSeeds();
        t.error = null;

        if (st.isFinished()) {
            if (t.paused) {
                // 用户手动暂停过，别被做种开关又拉起来
                t.state = ST_PAUSED;
                return;
            }
            if (seeding) {
                t.state = ST_SEED;
            } else {
                // 用户没开做种：下完就停，省电省上行
                t.state = ST_DONE;
                try {
                    h.pause();
                } catch (Throwable ignored) {
                }
                t.paused = true;
            }
            return;
        }
        if (t.paused) {
            t.state = ST_PAUSED;
            return;
        }
        if (!st.hasMetadata()) {
            t.state = ST_META;
            return;
        }
        TorrentStatus.State s = st.state();
        if (s == TorrentStatus.State.CHECKING_FILES
                || s == TorrentStatus.State.CHECKING_RESUME_DATA
                || s == TorrentStatus.State.ALLOCATING) {
            t.state = ST_CHECK;
        } else if (s == TorrentStatus.State.DOWNLOADING
                || s == TorrentStatus.State.FINISHED
                || s == TorrentStatus.State.SEEDING) {
            t.state = ST_DOWN;
        } else {
            t.state = ST_WAIT;
        }
    }

    public synchronized void pause(Task t) {
        t.paused = true;
        t.state = ST_PAUSED;
        t.rate = 0;
        if (t.handle != null) {
            try {
                t.handle.pause();
            } catch (Throwable ignored) {
            }
        }
        save();
    }

    public synchronized void resume(Task t) {
        t.paused = false;
        t.state = ST_WAIT;
        if (t.handle != null) {
            try {
                t.handle.resume();
            } catch (Throwable ignored) {
            }
        }
        save();
    }

    /** 删除任务；deleteFiles 为真时把下到的文件也删掉 */
    public synchronized void remove(Task t, boolean deleteFiles) {
        if (t.handle != null && sm != null) {
            try {
                sm.remove(t.handle);
            } catch (Throwable ignored) {
            }
        }
        tasks.remove(t);
        if (deleteFiles && t.name != null && !t.name.trim().isEmpty()) {
            File dir = new File(root, safe(t.name));
            if (dir.getParentFile() != null && dir.getParentFile().equals(root)) {
                deleteTree(dir);
            }
        }
        save();
    }

    public synchronized void removeAll(boolean deleteFiles) {
        for (Task t : new ArrayList<Task>(tasks)) remove(t, deleteFiles);
    }

    /** 下完的文件列表（只列视频，路径相对任务根） */
    public List<String[]> files(Task t) {
        List<String[]> out = new ArrayList<String[]>();
        File dir = new File(root, safe(t.name));
        walk(dir, dir, out, 0);
        return out;
    }

    private void walk(File base, File dir, List<String[]> out, int depth) {
        if (dir == null || depth > 4) return;
        File[] kids = dir.listFiles();
        if (kids == null) return;
        for (File f : kids) {
            if (f.isDirectory()) {
                walk(base, f, out, depth + 1);
            } else if (isPlayable(f.getName())) {
                out.add(new String[]{f.getAbsolutePath(), f.getName(), String.valueOf(f.length())});
            }
        }
    }

    private static boolean isPlayable(String n) {
        String s = n.toLowerCase(Locale.US);
        return s.endsWith(".mp4") || s.endsWith(".mkv") || s.endsWith(".avi") || s.endsWith(".ts")
                || s.endsWith(".mov") || s.endsWith(".webm") || s.endsWith(".m4v")
                || s.endsWith(".rmvb") || s.endsWith(".flv") || s.endsWith(".mp3")
                || s.endsWith(".flac") || s.endsWith(".m4a") || s.endsWith(".wav");
    }

    // ---------------- 持久化 ----------------

    private SharedPreferences sp() {
        return app.getSharedPreferences("kanfan_torrent", Context.MODE_PRIVATE);
    }

    private void save() {
        try {
            JSONArray arr = new JSONArray();
            for (Task t : tasks) {
                JSONObject o = new JSONObject();
                o.put("source", t.source);
                o.put("name", t.name);
                o.put("hash", t.hash);
                o.put("paused", t.paused);
                arr.put(o);
            }
            sp().edit().putString("tasks", arr.toString()).apply();
        } catch (Throwable ignored) {
        }
    }

    private void load() {
        try {
            String raw = sp().getString("tasks", "");
            if (raw == null || raw.isEmpty()) return;
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                Task t = new Task();
                t.source = o.optString("source", "");
                t.name = o.optString("name", "");
                t.hash = o.optString("hash", "");
                t.paused = o.optBoolean("paused", false);
                t.state = t.paused ? ST_PAUSED : ST_WAIT;
                if (!t.source.isEmpty()) tasks.add(t);
            }
        } catch (Throwable ignored) {
        }
    }

    /** Service 启动时把上次的任务重新加回 session（已下好的文件会被校验续上） */
    public synchronized void restore() {
        start();
        for (Task t : tasks) {
            if (t.hash.isEmpty()) continue;
            try {
                Sha1Hash ih = new Sha1Hash(t.hash);
                if (sm.find(ih) != null) continue;
                AddTorrentParams p;
                if (t.source.startsWith("magnet:")) {
                    p = AddTorrentParams.parseMagnetUri(t.source);
                } else {
                    File f = new File(t.source);
                    if (!f.isFile()) {
                        t.state = ST_ERROR;
                        t.error = "种子文件不见了";
                        continue;
                    }
                    p = new AddTorrentParams();
                    p.torrentInfo(new TorrentInfo(f));
                }
                p.savePath(root.getAbsolutePath());
                sh.asyncAddTorrent(p);
            } catch (Throwable e) {
                t.state = ST_ERROR;
                t.error = msg(e);
            }
        }
    }

    public void deleteFiles(Task t) {
        File dir = new File(root, safe(t.name));
        if (dir.getParentFile() != null && dir.getParentFile().equals(root)) deleteTree(dir);
    }

    // ---------------- 小工具 ----------------

    private static String safe(String name) {
        if (name == null) return "";
        String s = name.replace('/', '_').replace('\\', '_').trim();
        return s.isEmpty() ? "download" : s;
    }

    private static void deleteTree(File f) {
        File[] kids = f.listFiles();
        if (kids != null) {
            for (File k : kids) deleteTree(k);
        }
        f.delete();
    }

    private static String msg(Throwable t) {
        String m = t.getMessage();
        return m == null || m.isEmpty() ? t.getClass().getSimpleName() : m;
    }

    /** 把选中的 .torrent 复制到私有目录，重启后还能用 */
    public File keepTorrent(InputStream in, String suggestName) throws Exception {
        File dir = new File(app.getFilesDir(), "torrents");
        if (!dir.exists()) dir.mkdirs();
        String base = suggestName == null ? "a.torrent" : suggestName.replace('/', '_');
        if (!base.toLowerCase(Locale.US).endsWith(".torrent")) base += ".torrent";
        File out = new File(dir, System.currentTimeMillis() + "-" + base);
        FileOutputStream fo = new FileOutputStream(out);
        try {
            byte[] b = new byte[64 * 1024];
            int n;
            while ((n = in.read(b)) > 0) fo.write(b, 0, n);
        } finally {
            try {
                fo.close();
            } catch (Exception ignored) {
            }
        }
        return out;
    }

    /** 把磁力/链接里的 dn= 之类的名字提出来，做列表占位 */
    public static String guessName(String source) {
        if (source == null) return "";
        int i = source.indexOf("dn=");
        if (i < 0) return "";
        String s = source.substring(i + 3);
        int amp = s.indexOf('&');
        if (amp >= 0) s = s.substring(0, amp);
        try {
            return java.net.URLDecoder.decode(s, "UTF-8");
        } catch (Exception e) {
            return s;
        }
    }
}
