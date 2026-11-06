package com.kanfan.player;

import android.net.Uri;

/** 片库里的一条内容：视频或书籍（txt / epub） */
public class Entry {

    public static final int VIDEO = 0;
    public static final int BOOK = 1;
    public static final int IMAGE = 2;

    public long id;
    public int kind = VIDEO;
    public String name = "";
    public String title = "";
    /** 直属文件夹的显示名（根目录下的文件则为根目录名） */
    public String bucket = "";
    /** 相对所属授权目录的路径，"" 表示就在根目录下 */
    public String relPath = "";
    /** 所属的授权目录（tree uri 字符串），用于区分多个已添加的文件夹 */
    public String tree = "";
    public long duration;
    public long size;
    public Uri uri;

    public String key() {
        return uri == null ? "" : uri.toString();
    }

    public boolean isBook() {
        return kind == BOOK;
    }

    public boolean isImage() {
        return kind == IMAGE;
    }
}
