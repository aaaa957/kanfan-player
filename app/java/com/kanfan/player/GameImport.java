package com.kanfan.player;

import android.content.Context;
import android.net.Uri;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;

/**
 * 把 SAF 里的游戏目录复制到应用私有目录。
 *
 * KiriKiri 引擎只认真实文件路径（要 open()、要 seek、要写存档），content:// 它接不了，
 * 所以 KRKR 游戏必须先「导入」一份。ONS 引擎支持 content://，不需要这一步。
 */
public final class GameImport {

    public interface Progress {
        void onProgress(String name, int files, long bytes);

        boolean cancelled();
    }

    private GameImport() {
    }

    /** 导入目标目录：games/<名字>-<短哈希>，同名不同路径不会互相覆盖 */
    public static File target(Context c, Uri tree, String rel, String name) {
        File base = c.getExternalFilesDir("games");
        if (base == null) base = new File(c.getFilesDir(), "games");
        String safe = (name == null || name.trim().isEmpty()) ? "game" : name.trim();
        safe = safe.replaceAll("[\\\\/:*?\"<>|]", "_");
        String key = Integer.toHexString((tree.toString() + "|" + rel).hashCode());
        return new File(base, safe + "-" + key);
    }

    /** 已经导入过（有 startup.tjs 或任意 .xp3） */
    public static boolean ready(File dir) {
        if (dir == null || !dir.isDirectory()) return false;
        if (new File(dir, "startup.tjs").isFile()) return true;
        File[] kids = dir.listFiles();
        if (kids == null) return false;
        for (File f : kids) {
            if (f.isFile() && f.getName().toLowerCase().endsWith(".xp3")) return true;
        }
        return false;
    }

    /** 递归复制；返回复制的文件数 */
    public static int copy(Context c, Uri tree, String rel, File dest, Progress p) throws Exception {
        if (!dest.exists() && !dest.mkdirs()) throw new Exception("建不了目录：" + dest);
        return copyDir(c, tree, rel, dest, p, 0);
    }

    private static int copyDir(Context c, Uri tree, String rel, File dest,
                               Progress p, int depth) throws Exception {
        if (depth > 8) return 0;
        List<String[]> kids = Scan.children(c, tree, rel);
        int files = 0;
        for (String[] k : kids) {
            if (p != null && p.cancelled()) throw new Exception("已取消");
            String name = k[0];
            boolean dir = "1".equals(k[1]);
            String childRel = rel == null || rel.isEmpty() ? name : rel + "/" + name;
            File out = new File(dest, name);
            if (dir) {
                if (!out.exists() && !out.mkdirs()) continue;
                files += copyDir(c, tree, childRel, out, p, depth + 1);
                continue;
            }
            Uri doc = docUri(c, tree, childRel);
            if (doc == null) continue;
            InputStream in = null;
            OutputStream os = null;
            try {
                in = c.getContentResolver().openInputStream(doc);
                if (in == null) continue;
                os = new FileOutputStream(out);
                byte[] buf = new byte[128 * 1024];
                int n;
                long bytes = 0;
                while ((n = in.read(buf)) > 0) {
                    os.write(buf, 0, n);
                    bytes += n;
                    if (p != null && p.cancelled()) throw new Exception("已取消");
                }
                files++;
                if (p != null) p.onProgress(name, files, bytes);
            } finally {
                try {
                    if (os != null) os.close();
                } catch (Exception ignored) {
                }
                try {
                    if (in != null) in.close();
                } catch (Exception ignored) {
                }
            }
        }
        return files;
    }

    private static Uri docUri(Context c, Uri tree, String rel) {
        try {
            String id = Scan.docIdOf(c, tree, rel);
            if (id == null) return null;
            return android.provider.DocumentsContract.buildDocumentUriUsingTree(tree, id);
        } catch (Throwable t) {
            return null;
        }
    }

    public static void delete(File dir) {
        if (dir == null) return;
        File[] kids = dir.listFiles();
        if (kids != null) {
            for (File k : kids) delete(k);
        }
        dir.delete();
    }

    // ---------------- 导入记录（移除库文件夹时好把副本一起清掉） ----------------

    private static final String PREF = "kanfan_folders";

    private static android.content.SharedPreferences sp(Context c) {
        return c.getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    private static File baseDir(Context c) {
        File base = c.getExternalFilesDir("games");
        return base != null ? base : new File(c.getFilesDir(), "games");
    }

    /** 记下「这个导入目录属于哪个库文件夹」 */
    public static void remember(Context c, Uri tree, String rel, File dir) {
        if (dir == null) return;
        try {
            sp(c).edit().putString("imp:" + dir.getName(),
                    tree.toString() + "|" + (rel == null ? "" : rel)).apply();
        } catch (Throwable ignored) {
        }
    }

    public static void forget(Context c, File dir) {
        if (dir == null) return;
        try {
            sp(c).edit().remove("imp:" + dir.getName()).apply();
        } catch (Throwable ignored) {
        }
    }

    /** 这个库文件夹下导入过哪些游戏（找不到记录的旧副本会漏掉，删不掉就是了，不会误删） */
    public static java.util.List<File> importsOf(Context c, String treeUri) {
        java.util.List<File> out = new java.util.ArrayList<File>();
        File base = baseDir(c);
        if (base == null || treeUri == null) return out;
        String prefix = treeUri + "|";
        java.util.Set<String> names = new java.util.HashSet<String>();
        try {
            for (java.util.Map.Entry<String, ?> e : sp(c).getAll().entrySet()) {
                String k = e.getKey();
                if (k == null || !k.startsWith("imp:")) continue;
                Object v = e.getValue();
                if (v instanceof String && ((String) v).startsWith(prefix)) {
                    names.add(k.substring(4));
                }
            }
        } catch (Throwable ignored) {
        }
        for (String n : names) {
            File d = new File(base, n);
            if (d.isDirectory()) out.add(d);
            else forget(c, d);
        }
        return out;
    }
}
