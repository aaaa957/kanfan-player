package com.kanfan.player;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/**
 * 只扫描用户手动添加的文件夹（SAF 目录授权），不碰系统相册索引。
 */
public class Scan {

    private static final String DIR_MIME = DocumentsContract.Document.MIME_TYPE_DIR;

    private static final String[] PROJ = {
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE
    };

    private static final String[] VIDEO_EXT = {
            ".mp4", ".mkv", ".avi", ".ts", ".mov", ".webm",
            ".flv", ".m4v", ".wmv", ".rmvb", ".mpg", ".mpeg", ".3gp"
    };

    private static final String[] BOOK_EXT = {".txt", ".epub"};

    private static final String[] IMAGE_EXT = {
            ".jpg", ".jpeg", ".png", ".webp", ".gif", ".bmp", ".heic", ".heif", ".avif"
    };

    public static boolean isVideo(String name, String mime) {
        if (mime != null && mime.toLowerCase(Locale.US).startsWith("video/")) return true;
        if (name == null) return false;
        String n = name.toLowerCase(Locale.US);
        for (String e : VIDEO_EXT) {
            if (n.endsWith(e)) return true;
        }
        return false;
    }

    public static boolean isImage(String name) {
        if (name == null) return false;
        String n = name.toLowerCase(Locale.US);
        for (String e : IMAGE_EXT) {
            if (n.endsWith(e)) return true;
        }
        return false;
    }

    public static boolean isBook(String name) {
        if (name == null) return false;
        String n = name.toLowerCase(Locale.US);
        for (String e : BOOK_EXT) {
            if (n.endsWith(e)) return true;
        }
        return false;
    }

    /** 取用户所选目录本身的名字 */
    public static String treeName(Context c, Uri tree) {
        Cursor cur = null;
        try {
            Uri doc = DocumentsContract.buildDocumentUriUsingTree(
                    tree, DocumentsContract.getTreeDocumentId(tree));
            cur = c.getContentResolver().query(doc,
                    new String[]{DocumentsContract.Document.COLUMN_DISPLAY_NAME},
                    null, null, null);
            if (cur != null && cur.moveToFirst()) {
                String n = cur.getString(0);
                if (n != null && n.length() > 1) return n;
            }
        } catch (Exception ignored) {
        } finally {
            if (cur != null) cur.close();
        }
        return "视频";
    }

    /** 递归扫描整棵树，每个条目都带上相对路径（relPath）与所属根（tree） */
    public static List<Entry> scanTree(Context c, Uri tree) {
        List<Entry> out = new ArrayList<>();
        String root = treeName(c, tree);
        try {
            walk(c, tree, DocumentId(tree), root, "", out, 0);
        } catch (Exception ignored) {
        }
        return out;
    }

    private static String DocumentId(Uri tree) {
        try {
            return DocumentsContract.getTreeDocumentId(tree);
        } catch (Exception e) {
            return null;
        }
    }

    /** 路径最后一段作为文件夹显示名 */
    private static String leaf(String rel, String fallback) {
        if (rel == null || rel.isEmpty()) return fallback;
        int i = rel.lastIndexOf('/');
        return i < 0 ? rel : rel.substring(i + 1);
    }

    private static void walk(Context c, Uri tree, String docId, String rootName, String rel,
                             List<Entry> out, int depth) {
        if (docId == null || depth > 8) return;
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId);
        Cursor cur = null;
        List<String[]> dirs = new ArrayList<>();
        try {
            cur = c.getContentResolver().query(children, PROJ, null, null, null);
            if (cur == null) return;
            int iId = 0, iName = 1, iMime = 2, iSize = 3;
            while (cur.moveToNext()) {
                String id = cur.getString(iId);
                String name = cur.getString(iName);
                String mime = cur.getString(iMime);
                long size = cur.isNull(iSize) ? 0 : cur.getLong(iSize);
                if (DIR_MIME.equals(mime)) {
                    dirs.add(new String[]{id, name});
                } else if (isVideo(name, mime) || isBook(name) || isImage(name)) {
                    Entry v = new Entry();
                    if (isVideo(name, mime)) v.kind = Entry.VIDEO;
                    else if (isBook(name)) v.kind = Entry.BOOK;
                    else v.kind = Entry.IMAGE;
                    v.name = name == null ? "" : name;
                    v.title = Ui.prettyTitle(v.name);
                    v.relPath = rel;
                    v.tree = tree.toString();
                    v.bucket = leaf(rel, rootName);
                    v.size = size;
                    v.uri = DocumentsContract.buildDocumentUriUsingTree(tree, id);
                    out.add(v);
                }
            }
        } catch (Exception ignored) {
        } finally {
            if (cur != null) cur.close();
        }
        for (String[] d : dirs) {
            String n = d[1] == null ? "" : d[1];
            if (n.isEmpty()) continue;
            String childRel = rel.isEmpty() ? n : rel + "/" + n;
            walk(c, tree, d[0], rootName, childRel, out, depth + 1);
        }
    }

    /** 只取某个目录（直属）里的视频，用于播放器的连播列表。relPath 为 "" 表示根目录 */
    public static List<Entry> childrenOf(Context c, Uri tree, String relPath) {
        List<Entry> out = new ArrayList<>();
        try {
            String docId = resolve(c, tree, relPath);
            if (docId == null) return out;
            String rel = relPath == null ? "" : relPath;
            String rootName = treeName(c, tree);

            Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId);
            Cursor cur = null;
            try {
                cur = c.getContentResolver().query(children, PROJ, null, null, null);
                if (cur == null) return out;
                while (cur.moveToNext()) {
                    String id = cur.getString(0);
                    String name = cur.getString(1);
                    String mime = cur.getString(2);
                    long size = cur.isNull(3) ? 0 : cur.getLong(3);
                    if (DIR_MIME.equals(mime)) continue;
                    if (!isVideo(name, mime)) continue;
                    Entry v = new Entry();
                    v.name = name == null ? "" : name;
                    v.title = Ui.prettyTitle(v.name);
                    v.relPath = rel;
                    v.tree = tree.toString();
                    v.bucket = leaf(rel, rootName);
                    v.size = size;
                    v.uri = DocumentsContract.buildDocumentUriUsingTree(tree, id);
                    out.add(v);
                }
            } finally {
                if (cur != null) cur.close();
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    /** 按 "A/B/C" 逐层解析出 documentId，找不到返回 null */
    private static String resolve(Context c, Uri tree, String relPath) {
        String docId = DocumentId(tree);
        if (docId == null) return null;
        if (relPath == null || relPath.isEmpty()) return docId;
        for (String seg : relPath.split("/")) {
            if (seg.isEmpty()) continue;
            String next = childDir(c, tree, docId, seg);
            if (next == null) return null;
            docId = next;
        }
        return docId;
    }

    /** 只在直属子目录里找一个同名目录 */
    private static String childDir(Context c, Uri tree, String docId, String name) {
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId);
        Cursor cur = null;
        try {
            cur = c.getContentResolver().query(children, PROJ, null, null, null);
            if (cur == null) return null;
            while (cur.moveToNext()) {
                String id = cur.getString(0);
                String n = cur.getString(1);
                String mime = cur.getString(2);
                if (DIR_MIME.equals(mime) && name.equals(n)) return id;
            }
        } catch (Exception ignored) {
        } finally {
            if (cur != null) cur.close();
        }
        return null;
    }

    /** 量视频时长（毫秒），失败返回 0 */
    public static long durationOf(Context c, Uri uri) {
        android.media.MediaMetadataRetriever r = null;
        try {
            r = new android.media.MediaMetadataRetriever();
            r.setDataSource(c, uri);
            String d = r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION);
            if (d != null) return Long.parseLong(d);
        } catch (Throwable ignored) {
        } finally {
            if (r != null) {
                try {
                    r.release();
                } catch (Exception ignored) {
                }
            }
        }
        return 0;
    }

    // ---------------- 外挂字幕 / 弹幕的辅助 ----------------

    /** 列出某目录下的全部文件（不含子目录），返回 {文件名, uri}，用于找同名外挂文件 */
    public static List<String[]> siblings(Context c, Uri tree, String relPath) {
        List<String[]> out = new ArrayList<>();
        try {
            String docId = resolve(c, tree, relPath);
            if (docId == null) return out;
            Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId);
            Cursor cur = null;
            try {
                cur = c.getContentResolver().query(children, PROJ, null, null, null);
                if (cur == null) return out;
                while (cur.moveToNext()) {
                    String id = cur.getString(0);
                    String name = cur.getString(1);
                    String mime = cur.getString(2);
                    if (DIR_MIME.equals(mime) || name == null) continue;
                    out.add(new String[]{name, DocumentsContract.buildDocumentUriUsingTree(tree, id).toString()});
                }
            } finally {
                if (cur != null) cur.close();
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    /** 读取文本文件；先按 UTF-8 严格解码，失败再退回 GBK（中文字幕常见编码） */
    public static String readText(Context c, Uri uri, int maxBytes) {
        java.io.InputStream in = null;
        try {
            in = c.getContentResolver().openInputStream(uri);
            if (in == null) return "";
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n, total = 0;
            while ((n = in.read(buf)) > 0) {
                total += n;
                if (total > maxBytes) break;
                bos.write(buf, 0, n);
            }
            return bytesToText(bos.toByteArray());
        } catch (Exception e) {
            return "";
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    private static String bytesToText(byte[] data) {
        if (data == null || data.length == 0) return "";
        if (data.length >= 3 && (data[0] & 0xFF) == 0xEF
                && (data[1] & 0xFF) == 0xBB && (data[2] & 0xFF) == 0xBF) {
            return new String(data, 3, data.length - 3, StandardCharsets.UTF_8);
        }
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(data)).toString();
        } catch (CharacterCodingException e) {
            try {
                return new String(data, Charset.forName("GBK"));
            } catch (Exception e2) {
                return new String(data, StandardCharsets.UTF_8);
            }
        }
    }

    /** 取文件的显示名（含扩展名） */
    public static String displayName(Context c, Uri uri) {
        Cursor cur = null;
        try {
            cur = c.getContentResolver().query(uri,
                    new String[]{DocumentsContract.Document.COLUMN_DISPLAY_NAME}, null, null, null);
            if (cur != null && cur.moveToFirst()) {
                String n = cur.getString(0);
                if (n != null && !n.isEmpty()) return n;
            }
        } catch (Exception ignored) {
        } finally {
            if (cur != null) cur.close();
        }
        String seg = uri.getLastPathSegment();
        return seg == null ? "" : seg;
    }
}
