package com.kanfan.player;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 只服务一个 SAF 目录的极简 HTTP 服务（127.0.0.1）。
 *
 * 为什么要它：Tyrano 这类网页引擎要靠 XHR 读剧本/图片，file:// 在 WebView 里被 CORS 挡死；
 * 起个本地 http 就都通了，而且不用把几百 MB 的游戏拷进应用目录。
 */
public final class GameServer implements Closeable {

    private final Context app;
    private final Uri tree;
    private final String rootRel;
    private final ServerSocket server;
    private final ExecutorService pool;
    // 6 个线程同时取资源，必须用并发容器
    private final Map<String, String> docCache = new ConcurrentHashMap<String, String>();
    private final Map<String, Map<String, String>> listCache =
            new ConcurrentHashMap<String, Map<String, String>>();
    private volatile boolean closed;

    public GameServer(Context c, Uri tree, String rootRel, int port) throws IOException {
        this.app = c.getApplicationContext();
        this.tree = tree;
        this.rootRel = rootRel == null ? "" : rootRel;
        this.server = new ServerSocket(port, 50, InetAddress.getByName("127.0.0.1"));
        this.pool = Executors.newFixedThreadPool(6);
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                acceptLoop();
            }
        }, "game-http");
        t.setDaemon(true);
        t.start();
    }

    public int port() {
        return server.getLocalPort();
    }

    private void acceptLoop() {
        while (!closed) {
            try {
                final Socket s = server.accept();
                pool.execute(new Runnable() {
                    @Override
                    public void run() {
                        handle(s);
                    }
                });
            } catch (Throwable e) {
                if (closed) return;
            }
        }
    }

    // ---------------- 请求处理 ----------------

    private void handle(Socket s) {
        try {
            s.setSoTimeout(8000);
            java.io.BufferedReader in = new java.io.BufferedReader(
                    new java.io.InputStreamReader(s.getInputStream(), "ISO-8859-1"));
            String line = in.readLine();
            if (line == null) {
                s.close();
                return;
            }
            String[] parts = line.split(" ");
            if (parts.length < 2) {
                s.close();
                return;
            }
            String method = parts[0];
            String rawPath = parts[1];
            // 读完请求头
            String range = null;
            String h;
            while ((h = in.readLine()) != null && !h.isEmpty()) {
                // 只需要 Range
                if (h.toLowerCase(Locale.US).startsWith("range:")) range = h;
            }

            if (!"GET".equals(method) && !"HEAD".equals(method)) {
                respond(s, 405, "text/plain", "method not allowed".getBytes("UTF-8"), 0, -1, false);
                return;
            }
            String path = decode(rawPath);
            int q = path.indexOf('?');
            if (q >= 0) path = path.substring(0, q);
            while (path.startsWith("/")) path = path.substring(1);
            if (path.isEmpty()) path = "index.html";
            if (!safe(path)) {
                respond(s, 403, "text/plain", "forbidden".getBytes("UTF-8"), 0, -1, false);
                return;
            }

            String docId = docIdOf(path);
            if (docId == null) {
                respond(s, 404, "text/plain", "not found".getBytes("UTF-8"), 0, -1, false);
                return;
            }
            Uri doc = DocumentsContract.buildDocumentUriUsingTree(tree, docId);
            long size = sizeOf(doc);
            InputStream is = app.getContentResolver().openInputStream(doc);
            if (is == null) {
                respond(s, 500, "text/plain", "cannot open".getBytes("UTF-8"), 0, -1, false);
                return;
            }
            try {
                long start = 0, end = size - 1;
                boolean partial = false;
                if (range != null && size > 0) {
                    long[] r = parseRange(range, size);
                    if (r != null) {
                        start = r[0];
                        end = r[1];
                        partial = true;
                        skipFully(is, start);
                    }
                }
                long len = size <= 0 ? -1 : (end - start + 1);
                String mime = mimeOf(path);
                String head = "HTTP/1.1 " + (partial ? "206 Partial Content" : "200 OK") + "\r\n"
                        + "Content-Type: " + mime + "\r\n"
                        + (len >= 0 ? "Content-Length: " + len + "\r\n" : "")
                        + (partial ? "Content-Range: bytes " + start + "-" + end + "/" + size + "\r\n" : "")
                        + "Accept-Ranges: bytes\r\n"
                        + "Cache-Control: max-age=30\r\n"
                        + "Connection: close\r\n\r\n";
                OutputStream os = s.getOutputStream();
                os.write(head.getBytes("ISO-8859-1"));
                if (!"HEAD".equals(method)) {
                    byte[] buf = new byte[64 * 1024];
                    long left = len < 0 ? Long.MAX_VALUE : len;
                    int n;
                    while (left > 0 && (n = is.read(buf, 0, (int) Math.min(buf.length, left))) > 0) {
                        os.write(buf, 0, n);
                        left -= n;
                    }
                }
                os.flush();
            } finally {
                try {
                    is.close();
                } catch (Exception ignored) {
                }
            }
        } catch (Throwable ignored) {
        } finally {
            try {
                s.close();
            } catch (Exception ignored) {
            }
        }
    }

    private static void respond(Socket s, int code, String mime, byte[] body,
                                long start, long end, boolean partial) {
        try {
            String head = "HTTP/1.1 " + code + " X\r\nContent-Type: " + mime
                    + "\r\nContent-Length: " + body.length + "\r\nConnection: close\r\n\r\n";
            OutputStream os = s.getOutputStream();
            os.write(head.getBytes("ISO-8859-1"));
            os.write(body);
            os.flush();
        } catch (Exception ignored) {
        }
    }

    private static long[] parseRange(String h, long size) {
        try {
            int i = h.indexOf('=');
            if (i < 0) return null;
            String v = h.substring(i + 1).trim();
            int dash = v.indexOf('-');
            if (dash < 0) return null;
            String a = v.substring(0, dash).trim();
            String b = v.substring(dash + 1).trim();
            long start = a.isEmpty() ? Math.max(0, size - Long.parseLong(b)) : Long.parseLong(a);
            long end = b.isEmpty() ? size - 1 : Long.parseLong(b);
            if (start < 0) start = 0;
            if (end >= size) end = size - 1;
            if (start > end) return null;
            return new long[]{start, end};
        } catch (Exception e) {
            return null;
        }
    }

    private static void skipFully(InputStream in, long n) throws IOException {
        long left = n;
        while (left > 0) {
            long s = in.skip(left);
            if (s <= 0) {
                if (in.read() < 0) return;
                s = 1;
            }
            left -= s;
        }
    }

    // ---------------- 路径 → SAF ----------------

    private static boolean safe(String path) {
        String[] seg = path.split("/");
        for (String s : seg) {
            if (s.equals("..") || s.equals(".")) return false;
        }
        return true;
    }

    private String docIdOf(String rel) {
        String key = rootRel.isEmpty() ? rel : rootRel + "/" + rel;
        String cached = docCache.get(key);
        if (cached != null) return cached;
        int slash = rel.lastIndexOf('/');
        String dir = slash < 0 ? "" : rel.substring(0, slash);
        String name = slash < 0 ? rel : rel.substring(slash + 1);
        Map<String, String> items = list(dir);
        String id = items.get(name);
        if (id != null) docCache.put(key, id);
        return id;
    }

    /** 列一个目录：名字 → docId；带缓存，一次页面加载只查几轮 */
    private Map<String, String> list(String rel) {
        Map<String, String> cached = listCache.get(rel);
        if (cached != null) return cached;
        Map<String, String> out = new ConcurrentHashMap<String, String>();
        String dirRel = rel.isEmpty() ? rootRel : (rootRel.isEmpty() ? rel : rootRel + "/" + rel);
        String dirId = dirRel.isEmpty() ? null : Scan.docIdOf(app, tree, dirRel);
        if (dirId == null && dirRel.isEmpty()) {
            try {
                dirId = DocumentsContract.getTreeDocumentId(tree);
            } catch (Exception ignored) {
            }
        }
        if (dirId == null) {
            listCache.put(rel, out);
            return out;
        }
        Cursor cur = null;
        try {
            Uri kids = DocumentsContract.buildChildDocumentsUriUsingTree(tree, dirId);
            cur = app.getContentResolver().query(kids, new String[]{
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME}, null, null, null);
            if (cur != null) {
                while (cur.moveToNext()) {
                    out.put(cur.getString(1), cur.getString(0));
                }
            }
        } catch (Exception ignored) {
        } finally {
            if (cur != null) cur.close();
        }
        listCache.put(rel, out);
        return out;
    }

    private long sizeOf(Uri doc) {
        Cursor cur = null;
        try {
            cur = app.getContentResolver().query(doc,
                    new String[]{DocumentsContract.Document.COLUMN_SIZE}, null, null, null);
            if (cur != null && cur.moveToFirst() && !cur.isNull(0)) return cur.getLong(0);
        } catch (Exception ignored) {
        } finally {
            if (cur != null) cur.close();
        }
        return -1;
    }

    private static String decode(String s) {
        try {
            return java.net.URLDecoder.decode(s.replace("+", "%2B"), "UTF-8");
        } catch (Exception e) {
            return s;
        }
    }

    private static String mimeOf(String path) {
        String p = path.toLowerCase(Locale.US);
        int dot = p.lastIndexOf('.');
        String e = dot < 0 ? "" : p.substring(dot + 1);
        if (e.equals("html") || e.equals("htm")) return "text/html; charset=utf-8";
        if (e.equals("js")) return "application/javascript; charset=utf-8";
        if (e.equals("css")) return "text/css; charset=utf-8";
        if (e.equals("json")) return "application/json; charset=utf-8";
        if (e.equals("ks") || e.equals("tjs") || e.equals("txt") || e.equals("csv")) {
            return "text/plain; charset=utf-8";
        }
        if (e.equals("png")) return "image/png";
        if (e.equals("jpg") || e.equals("jpeg")) return "image/jpeg";
        if (e.equals("gif")) return "image/gif";
        if (e.equals("webp")) return "image/webp";
        if (e.equals("svg")) return "image/svg+xml";
        if (e.equals("mp3")) return "audio/mpeg";
        if (e.equals("ogg") || e.equals("oga")) return "audio/ogg";
        if (e.equals("m4a") || e.equals("aac")) return "audio/mp4";
        if (e.equals("wav")) return "audio/wav";
        if (e.equals("mp4")) return "video/mp4";
        if (e.equals("webm")) return "video/webm";
        if (e.equals("woff")) return "font/woff";
        if (e.equals("woff2")) return "font/woff2";
        if (e.equals("ttf")) return "font/ttf";
        if (e.equals("otf")) return "font/otf";
        return "application/octet-stream";
    }

    @Override
    public void close() {
        closed = true;
        try {
            server.close();
        } catch (Exception ignored) {
        }
        pool.shutdownNow();
    }
}
