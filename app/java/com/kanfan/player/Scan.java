package com.kanfan.player;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

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

    public static boolean isVideo(String name, String mime) {
        if (mime != null && mime.toLowerCase(Locale.US).startsWith("video/")) return true;
        if (name == null) return false;
        String n = name.toLowerCase(Locale.US);
        for (String e : VIDEO_EXT) {
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

    /** 递归扫描整棵树 */
    public static List<VideoItem> scanTree(Context c, Uri tree) {
        List<VideoItem> out = new ArrayList<>();
        String root = treeName(c, tree);
        try {
            walk(c, tree, DocumentId(tree), root, out, 0);
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

    private static void walk(Context c, Uri tree, String docId, String bucket,
                             List<VideoItem> out, int depth) {
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
                } else if (isVideo(name, mime)) {
                    VideoItem v = new VideoItem();
                    v.name = name == null ? "" : name;
                    v.title = Ui.prettyTitle(v.name);
                    v.bucket = bucket;
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
            walk(c, tree, d[0], d[1] == null ? bucket : d[1], out, depth + 1);
        }
    }

    /** 只取某个文件夹（直属）里的视频，用于播放器的连播列表 */
    public static List<VideoItem> childrenOf(Context c, Uri tree, String folder) {
        List<VideoItem> out = new ArrayList<>();
        try {
            String root = treeName(c, tree);
            String docId = folder != null && folder.equals(root)
                    ? DocumentId(tree) : findDir(c, tree, DocumentId(tree), folder, 0);
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
                    long size = cur.isNull(3) ? 0 : cur.getLong(3);
                    if (DIR_MIME.equals(mime)) continue;
                    if (!isVideo(name, mime)) continue;
                    VideoItem v = new VideoItem();
                    v.name = name == null ? "" : name;
                    v.title = Ui.prettyTitle(v.name);
                    v.bucket = folder;
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

    private static String findDir(Context c, Uri tree, String docId, String name, int depth) {
        if (docId == null || name == null || depth > 8) return null;
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId);
        Cursor cur = null;
        List<String[]> dirs = new ArrayList<>();
        try {
            cur = c.getContentResolver().query(children, PROJ, null, null, null);
            if (cur == null) return null;
            while (cur.moveToNext()) {
                String id = cur.getString(0);
                String n = cur.getString(1);
                String mime = cur.getString(2);
                if (DIR_MIME.equals(mime)) {
                    if (name.equals(n)) return id;
                    dirs.add(new String[]{id, n});
                }
            }
        } catch (Exception ignored) {
        } finally {
            if (cur != null) cur.close();
        }
        for (String[] d : dirs) {
            String r = findDir(c, tree, d[0], name, depth + 1);
            if (r != null) return r;
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
}
