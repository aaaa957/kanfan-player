package com.kanfan.player;

import android.net.Uri;

public class VideoItem {
    public long id;
    public String name = "";
    public String title = "";
    public String bucket = "";
    public long duration;
    public long size;
    public Uri uri;

    public String key() {
        return uri == null ? "" : uri.toString();
    }
}
