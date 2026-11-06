package com.kanfan.player;

import android.content.ContentResolver;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;

import java.io.OutputStream;
import java.util.Locale;

/**
 * SAF 写入：在用户授权的目录里建子目录、建文件。
 * 需要目录的「写入」权限，只有读权限时 createDocument 会抛 SecurityException。
 */
public class Saf {

    /** 文件所在目录的 document uri */
    public static Uri parentDocument(Uri tree, Uri doc) {
        try {
            String id = DocumentsContract.getDocumentId(doc);
            int slash = id.lastIndexOf('/');
            if (slash > 0) {
                return DocumentsContract.buildDocumentUriUsingTree(tree, id.substring(0, slash));
            }
            int colon = id.indexOf(':');
            if (colon < 0) return tree;
            // "primary:xxx.zip" 的父目录是卷根，也就是授权目录本身
            return tree;
        } catch (Exception e) {
            return tree;
        }
    }

    public static Uri findChild(ContentResolver cr, Uri tree, Uri parent, String name) {
        Cursor c = null;
        try {
            String pid = DocumentsContract.getDocumentId(parent);
            Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, pid);
            c = cr.query(children, new String[]{
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME}, null, null, null);
            if (c != null) {
                while (c.moveToNext()) {
                    String n = c.getString(1);
                    if (name.equals(n)) {
                        return DocumentsContract.buildDocumentUriUsingTree(tree, c.getString(0));
                    }
                }
            }
        } catch (Exception ignored) {
        } finally {
            if (c != null) c.close();
        }
        return null;
    }

    /** 有就复用，没有就建 */
    public static Uri ensureDir(ContentResolver cr, Uri tree, Uri parent, String name)
            throws Exception {
        Uri found = findChild(cr, tree, parent, name);
        if (found != null) return found;
        return DocumentsContract.createDocument(cr, parent,
                DocumentsContract.Document.MIME_TYPE_DIR, name);
    }

    /** 建文件；同名已存在则先删掉再建 */
    public static Uri createFile(ContentResolver cr, Uri tree, Uri parent, String name)
            throws Exception {
        Uri existing = findChild(cr, tree, parent, name);
        if (existing != null) {
            try {
                DocumentsContract.deleteDocument(cr, existing);
            } catch (Exception ignored) {
            }
        }
        return DocumentsContract.createDocument(cr, parent, mimeOf(name), name);
    }

    public static OutputStream openOut(ContentResolver cr, Uri doc) throws Exception {
        return cr.openOutputStream(doc, "w");
    }

    public static String mimeOf(String name) {
        if (name == null) return "application/octet-stream";
        String n = name.toLowerCase(Locale.US);
        String ext = n.contains(".") ? n.substring(n.lastIndexOf('.') + 1) : "";
        if (ext.equals("mp4") || ext.equals("m4v")) return "video/mp4";
        if (ext.equals("mkv")) return "video/x-matroska";
        if (ext.equals("avi")) return "video/x-msvideo";
        if (ext.equals("ts")) return "video/mp2t";
        if (ext.equals("mov")) return "video/quicktime";
        if (ext.equals("webm")) return "video/webm";
        if (ext.equals("flv")) return "video/x-flv";
        if (ext.equals("mp3")) return "audio/mpeg";
        if (ext.equals("flac")) return "audio/flac";
        if (ext.equals("m4a")) return "audio/mp4";
        if (ext.equals("wav")) return "audio/wav";
        if (ext.equals("ogg")) return "audio/ogg";
        if (ext.equals("jpg") || ext.equals("jpeg")) return "image/jpeg";
        if (ext.equals("png")) return "image/png";
        if (ext.equals("gif")) return "image/gif";
        if (ext.equals("webp")) return "image/webp";
        if (ext.equals("bmp")) return "image/bmp";
        if (ext.equals("heic") || ext.equals("heif")) return "image/heic";
        if (ext.equals("txt")) return "text/plain";
        if (ext.equals("epub")) return "application/epub+zip";
        if (ext.equals("pdf")) return "application/pdf";
        if (ext.equals("zip")) return "application/zip";
        if (ext.equals("7z")) return "application/x-7z-compressed";
        if (ext.equals("rar")) return "application/vnd.rar";
        if (ext.equals("ass") || ext.equals("ssa")) return "text/x-ssa";
        if (ext.equals("srt")) return "application/x-subrip";
        if (ext.equals("xml")) return "text/xml";
        if (ext.equals("json")) return "application/json";
        if (ext.equals("html") || ext.equals("htm")) return "text/html";
        return "application/octet-stream";
    }
}
