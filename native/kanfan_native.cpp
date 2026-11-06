// 看番 · 原生解压层
// rar：unrar 的 DLL 接口，边解边写盘
// 7z ：7-Zip 的 C 解码器，整块解到内存后再落盘
#include <jni.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <unistd.h>
#include <fcntl.h>

#include <string>
#include <vector>

// unrar 的 dll.hpp 需要先定义 _UNIX 才会补上 UINT/LPARAM/HANDLE
#if defined(__ANDROID__) && !defined(_UNIX)
#define _UNIX 1
#endif
#include "dll.hpp"    // unrar

// unrar 会把 UINT/LONG/LPARAM 定义成宏，和 7-Zip 的 typedef 撞车，先撤掉
#undef UINT
#undef LONG
#undef LPARAM

#include "7z.h"       // 7-Zip C 解码器
#include "7zCrc.h"

/* ===================== 公共 ===================== */


static void u16to8(const UInt16* src, char* dst, int dstMax) {
    int o = 0;
    if (dstMax <= 0) return;
    for (int i = 0; src[i] != 0 && o < dstMax - 4; i++) {
        unsigned c = src[i];
        if (c < 0x80) {
            dst[o++] = (char) c;
        } else if (c < 0x800) {
            dst[o++] = (char) (0xC0 | (c >> 6));
            dst[o++] = (char) (0x80 | (c & 0x3F));
        } else {
            dst[o++] = (char) (0xE0 | (c >> 12));
            dst[o++] = (char) (0x80 | ((c >> 6) & 0x3F));
            dst[o++] = (char) (0x80 | (c & 0x3F));
        }
    }
    dst[o] = 0;
}

static jobjectArray toArray(JNIEnv* env, const std::vector<std::string>& items) {
    jclass strCls = env->FindClass("java/lang/String");
    jobjectArray arr = env->NewObjectArray((jsize) items.size(), strCls, NULL);
    for (size_t i = 0; i < items.size(); i++) {
        jstring s = env->NewStringUTF(items[i].c_str());
        env->SetObjectArrayElement(arr, (jsize) i, s);
        env->DeleteLocalRef(s);
    }
    return arr;
}

static std::string jstr(JNIEnv* env, jstring s) {
    if (s == NULL) return std::string();
    const char* p = env->GetStringUTFChars(s, NULL);
    std::string out = p ? p : "";
    if (p) env->ReleaseStringUTFChars(s, p);
    return out;
}

static std::vector<std::string> jstrArray(JNIEnv* env, jobjectArray arr) {
    std::vector<std::string> out;
    if (arr == NULL) return out;
    jsize n = env->GetArrayLength(arr);
    for (jsize i = 0; i < n; i++) {
        jstring s = (jstring) env->GetObjectArrayElement(arr, i);
        out.push_back(jstr(env, s));
        if (s != NULL) env->DeleteLocalRef(s);
    }
    return out;
}

/* ===================== 7z ===================== */

static void* szAlloc(ISzAllocPtr p, size_t size) {
    (void) p;
    return malloc(size);
}

static void szFree(ISzAllocPtr p, void* addr) {
    (void) p;
    free(addr);
}

static const ISzAlloc g_alloc = { szAlloc, szFree };

struct FileIn {
    ILookInStream vt;
    // 分卷：多段 fd 按顺序拼成一个逻辑文件；单卷就是长度 1
    std::vector<int> fds;
    std::vector<Int64> starts;   // starts[i] 起，starts[i+1] 止；最后一项是总长
    Byte* buf;
    size_t pos;
    size_t len;
    Int64 filePos;
    Int64 fileSize;
};

/** 跨段读：从逻辑位置 pos 读 want 字节，遇到卷边界自动换下一卷 */
static ssize_t fileInReadAt(FileIn* s, Byte* buf, size_t want, Int64 pos) {
    size_t done = 0;
    while (done < want && pos < s->fileSize) {
        size_t i = 0;
        while (i + 1 < s->starts.size() && pos >= s->starts[i + 1]) i++;
        if (i >= s->fds.size()) break;
        Int64 off = pos - s->starts[i];
        Int64 cap = s->starts[i + 1] - pos;
        size_t n = want - done;
        if ((Int64) n > cap) n = (size_t) cap;
        if (n == 0) break;
        ssize_t r = pread64(s->fds[i], buf + done, n, (off64_t) off);
        if (r <= 0) break;
        done += (size_t) r;
        pos += r;
    }
    return (ssize_t) done;
}

static SRes fileInRefill(FileIn* s) {
    if (s->filePos >= s->fileSize) {
        s->len = 0;
        s->pos = 0;
        return SZ_OK;
    }
    ssize_t n = fileInReadAt(s, s->buf, 1 << 16, s->filePos);
    if (n < 0) return SZ_ERROR_READ;
    s->len = (size_t) n;
    s->pos = 0;
    s->filePos += (Int64) n;
    return SZ_OK;
}

static SRes fileInLook(ILookInStreamPtr pp, const void** buf, size_t* size) {
    FileIn* s = (FileIn*) pp;
    if (s->pos >= s->len) {
        if (fileInRefill(s) != SZ_OK) {
            *buf = NULL;
            *size = 0;
            return SZ_ERROR_READ;
        }
    }
    *buf = s->buf + s->pos;
    *size = s->len - s->pos;
    return SZ_OK;
}

static SRes fileInRead(ILookInStreamPtr pp, void* dest, size_t* size) {
    FileIn* s = (FileIn*) pp;
    size_t want = *size;
    size_t done = 0;
    Byte* out = (Byte*) dest;
    while (done < want) {
        if (s->pos >= s->len) {
            if (fileInRefill(s) != SZ_OK) break;
            if (s->len == 0) break;
        }
        size_t n = s->len - s->pos;
        if (n > want - done) n = want - done;
        memcpy(out + done, s->buf + s->pos, n);
        s->pos += n;
        done += n;
    }
    *size = done;
    return done == want ? SZ_OK : SZ_ERROR_READ;
}

static SRes fileInSkip(ILookInStreamPtr pp, size_t offset) {
    FileIn* s = (FileIn*) pp;
    size_t left = offset;
    while (left > 0) {
        if (s->pos >= s->len) {
            if (fileInRefill(s) != SZ_OK) return SZ_ERROR_READ;
            if (s->len == 0) break;
        }
        size_t n = s->len - s->pos;
        if (n > left) n = left;
        s->pos += n;
        left -= n;
    }
    return SZ_OK;
}

static SRes fileInSeek(ILookInStreamPtr pp, Int64* pos, ESzSeek origin) {
    FileIn* s = (FileIn*) pp;
    Int64 base = 0;
    if (origin == SZ_SEEK_CUR) base = s->filePos - (Int64) (s->len - s->pos);
    else if (origin == SZ_SEEK_END) base = s->fileSize;
    Int64 target = base + *pos;
    if (target < 0) return SZ_ERROR_PARAM;
    s->filePos = target;
    s->len = 0;
    s->pos = 0;
    *pos = target;
    return SZ_OK;
}

static void fileInInit(FileIn* s, const std::vector<int>& fds) {
    s->fds = fds;
    s->starts.assign(fds.size() + 1, 0);
    for (size_t i = 0; i < fds.size(); i++) {
        off64_t sz = lseek64(fds[i], 0, SEEK_END);
        s->starts[i + 1] = s->starts[i] + (sz < 0 ? 0 : (Int64) sz);
    }
    s->fileSize = s->starts.empty() ? 0 : s->starts[s->starts.size() - 1];
    s->buf = (Byte*) malloc(1 << 16);
    s->pos = 0;
    s->len = 0;
    s->filePos = 0;
    s->vt.Look = fileInLook;
    s->vt.Skip = fileInSkip;
    s->vt.Read = fileInRead;
    s->vt.Seek = fileInSeek;
}

/* ===================== RAR ===================== */

struct RarCtx {
    JNIEnv* env;
    jobject sink;
    jmethodID onEntry;
    jmethodID onProgress;
    long long bytes;
    long long lastReport;
    char name[1024];
};

static int rarProgress(unsigned int msg, long userData, long p1, long p2) {
    (void) p1;
    RarCtx* c = (RarCtx*) userData;
    if (msg != UCM_PROCESSDATA || c == NULL || c->sink == NULL) return 1;
    c->bytes += (long long) p2;
    if (c->onProgress != NULL && c->bytes - c->lastReport >= (8 << 20)) {
        c->lastReport = c->bytes;
        c->env->CallVoidMethod(c->sink, c->onProgress, (jlong) c->bytes);
        if (c->env->ExceptionCheck()) c->env->ExceptionClear();
    }
    return 1;
}

static void mkdirs(const char* path) {
    char tmp[2048];
    size_t n = strlen(path);
    if (n == 0 || n >= sizeof(tmp)) return;
    memcpy(tmp, path, n + 1);
    for (size_t i = 1; i < n; i++) {
        if (tmp[i] == '/') {
            tmp[i] = 0;
            mkdir(tmp, 0777);
            tmp[i] = '/';
        }
    }
    mkdir(tmp, 0777);
}

extern "C" {

JNIEXPORT jstring JNICALL
Java_com_kanfan_player_Nat_rarList(JNIEnv* env, jclass, jstring jpath, jstring jpwd) {
    std::string path = jstr(env, jpath);
    std::string pwd = jstr(env, jpwd);

    RAROpenArchiveDataEx data;
    memset(&data, 0, sizeof(data));
    data.ArcName = (char*) path.c_str();
    // INCSPLIT：分卷要连着列，不然只列到第一卷为止
    data.OpenMode = RAR_OM_LIST | RAR_OM_LIST_INCSPLIT;

    HANDLE h = RAROpenArchiveEx(&data);
    if (h == NULL) {
        char err[32];
        snprintf(err, sizeof(err), "E%d", data.OpenResult);
        return env->NewStringUTF(err);
    }
    if (!pwd.empty()) RARSetPassword(h, (char*) pwd.c_str());

    std::vector<std::string> items;
    RARHeaderDataEx hd;
    memset(&hd, 0, sizeof(hd));
    int rc;
    while ((rc = RARReadHeaderEx(h, &hd)) == 0) {
        // INCSPLIT 会把「续卷头」也返回来，这些是同一个文件的下一段，跳过，
        // 否则一个跨 4 卷的文件会在列表里出现 4 次
        if (hd.Flags & RHDF_SPLITBEFORE) {
            RARProcessFile(h, RAR_SKIP, NULL, NULL);
            continue;
        }
        unsigned long long size = ((unsigned long long) hd.UnpSizeHigh << 32)
                                  | (unsigned long long) hd.UnpSize;
        char head[96];
        snprintf(head, sizeof(head), "%llu\t%d\t%d\t",
                 size, (hd.Flags & RHDF_DIRECTORY) ? 1 : 0,
                 (hd.Flags & RHDF_ENCRYPTED) ? 1 : 0);
        std::string line = head;
        line += hd.FileName;
        items.push_back(line);
        RARProcessFile(h, RAR_SKIP, NULL, NULL);
    }
    RARCloseArchive(h);

    // 用单个字符串返回：第一行是状态，后面每行一个条目
    std::string out = "OK\n";
    for (size_t i = 0; i < items.size(); i++) {
        out += items[i];
        out += "\n";
    }
    return env->NewStringUTF(out.c_str());
}

JNIEXPORT jint JNICALL
Java_com_kanfan_player_Nat_rarExtractAll(JNIEnv* env, jclass, jstring jpath, jstring jpwd,
                                         jstring jtmp, jobject sink) {
    std::string path = jstr(env, jpath);
    std::string pwd = jstr(env, jpwd);
    std::string tmp = jstr(env, jtmp);
    mkdirs(tmp.c_str());

    RarCtx ctx;
    memset(&ctx, 0, sizeof(ctx));
    ctx.env = env;
    ctx.sink = sink;
    if (sink != NULL) {
        jclass cls = env->GetObjectClass(sink);
        ctx.onEntry = env->GetMethodID(cls, "onEntry", "(IJLjava/lang/String;Ljava/lang/String;)V");
        ctx.onProgress = env->GetMethodID(cls, "onProgress", "(J)V");
    }

    RAROpenArchiveDataEx data;
    memset(&data, 0, sizeof(data));
    data.ArcName = (char*) path.c_str();
    data.OpenMode = RAR_OM_EXTRACT;

    HANDLE h = RAROpenArchiveEx(&data);
    if (h == NULL) return -100 - data.OpenResult;
    if (!pwd.empty()) RARSetPassword(h, (char*) pwd.c_str());
    RARSetCallback(h, rarProgress, (long) (size_t) &ctx);

    RARHeaderDataEx hd;
    memset(&hd, 0, sizeof(hd));
    int rc;
    int err = 0;
    int idx = 0;
    while ((rc = RARReadHeaderEx(h, &hd)) == 0) {
        strncpy(ctx.name, hd.FileName, sizeof(ctx.name) - 1);
        ctx.name[sizeof(ctx.name) - 1] = 0;
        if (hd.Flags & RHDF_DIRECTORY) {
            RARProcessFile(h, RAR_SKIP, NULL, NULL);
            idx++;
            continue;
        }
        char flat[64];
        snprintf(flat, sizeof(flat), "kf_%d", idx);
        int r = RARProcessFile(h, RAR_EXTRACT, (char*) tmp.c_str(), flat);
        if (r != 0) {
            err = r;
            break;
        }
        if (sink != NULL && ctx.onEntry != NULL) {
            char full[2048];
            snprintf(full, sizeof(full), "%s/%s", tmp.c_str(), flat);
            jstring jn = env->NewStringUTF(ctx.name);
            jstring jp = env->NewStringUTF(full);
            env->CallVoidMethod(sink, ctx.onEntry, (jint) idx, (jlong) hd.UnpSize, jn, jp);
            env->DeleteLocalRef(jn);
            env->DeleteLocalRef(jp);
            if (env->ExceptionCheck()) {
                env->ExceptionClear();
                unlink(full);
                err = -200;
                break;
            }
            unlink(full);
        }
        idx++;
    }
    RARCloseArchive(h);
    return err;
}

} // extern "C"

/* ===================== 7z 入口 ===================== */

/* 7z：把打开的归档和「固态块缓存」留住，避免每个文件都重解整块 */
struct SzCtx {
    std::string key;   // 各卷路径拼起来，用来判断是否同一个归档
    FileIn fs;
    CSzArEx db;
    bool opened;
    UInt32 blockIndex;
    Byte* outBuf;
    size_t outBufSize;
};

static SzCtx g_sz = { std::string(), {}, {}, false, 0xFFFFFFFF, NULL, 0 };

static void szCloseCtx() {
    if (g_sz.opened) {
        SzArEx_Free(&g_sz.db, &g_alloc);
        g_sz.opened = false;
    }
    if (g_sz.outBuf != NULL) {
        free(g_sz.outBuf);
        g_sz.outBuf = NULL;
    }
    g_sz.outBufSize = 0;
    g_sz.blockIndex = 0xFFFFFFFF;
    if (g_sz.fs.buf != NULL) {
        free(g_sz.fs.buf);
        g_sz.fs.buf = NULL;
    }
    for (size_t i = 0; i < g_sz.fs.fds.size(); i++) {
        if (g_sz.fs.fds[i] >= 0) close(g_sz.fs.fds[i]);
    }
    g_sz.fs.fds.clear();
    g_sz.fs.starts.clear();
    g_sz.key.clear();
}

static SRes szEnsureOpen(const std::vector<std::string>& paths) {
    std::string key;
    for (size_t i = 0; i < paths.size(); i++) {
        key += paths[i];
        key += "\n";
    }
    if (g_sz.opened && g_sz.key == key) return SZ_OK;
    szCloseCtx();
    if (paths.empty()) return SZ_ERROR_READ;

    std::vector<int> fds;
    for (size_t i = 0; i < paths.size(); i++) {
        int fd = open(paths[i].c_str(), O_RDONLY);
        if (fd < 0) {
            for (size_t k = 0; k < fds.size(); k++) close(fds[k]);
            return SZ_ERROR_READ;
        }
        fds.push_back(fd);
    }
    fileInInit(&g_sz.fs, fds);
    SzArEx_Init(&g_sz.db);
    SRes r = SzArEx_Open(&g_sz.db, &g_sz.fs.vt, &g_alloc, &g_alloc);
    if (r != SZ_OK) {
        szCloseCtx();
        return r;
    }
    g_sz.key = key;
    g_sz.opened = true;
    return SZ_OK;
}

extern "C" {

JNIEXPORT jstring JNICALL
Java_com_kanfan_player_Nat_szList(JNIEnv* env, jclass, jobjectArray jpaths) {
    std::vector<std::string> paths = jstrArray(env, jpaths);
    SRes res = szEnsureOpen(paths);
    if (res != SZ_OK) {
        char err[32];
        snprintf(err, sizeof(err), "E%d", (int) res);
        return env->NewStringUTF(err);
    }

    std::string out = "OK\n";
    char buf[2048];
    for (UInt32 i = 0; i < g_sz.db.NumFiles; i++) {
        size_t len = SzArEx_GetFileNameUtf16(&g_sz.db, i, NULL);
        UInt16* name16 = (UInt16*) malloc((len + 1) * sizeof(UInt16));
        if (name16 == NULL) continue;
        SzArEx_GetFileNameUtf16(&g_sz.db, i, name16);
        u16to8(name16, buf, (int) sizeof(buf));
        free(name16);

        UInt64 size = SzArEx_GetFileSize(&g_sz.db, i);
        bool isDir = SzArEx_IsDir(&g_sz.db, i) != 0;
        char head[96];
        snprintf(head, sizeof(head), "%llu\t%d\t", (unsigned long long) size, isDir ? 1 : 0);
        out += head;
        out += buf;
        out += "\n";
    }
    return env->NewStringUTF(out.c_str());
}

JNIEXPORT jint JNICALL
Java_com_kanfan_player_Nat_szExtractOne(JNIEnv* env, jclass, jobjectArray jpaths, jint index,
                                        jstring jdest) {
    std::vector<std::string> paths = jstrArray(env, jpaths);
    std::string dest = jstr(env, jdest);

    SRes res = szEnsureOpen(paths);
    if (res != SZ_OK) return (int) res;
    if (index < 0 || (UInt32) index >= g_sz.db.NumFiles) return -2;

    size_t offset = 0, outSize = 0;
    res = SzArEx_Extract(&g_sz.db, &g_sz.fs.vt, (UInt32) index, &g_sz.blockIndex,
                         &g_sz.outBuf, &g_sz.outBufSize, &offset, &outSize,
                         &g_alloc, &g_alloc);
    if (res != SZ_OK) return (int) res;

    FILE* o = fopen(dest.c_str(), "wb");
    if (o == NULL) return -3;
    size_t w = fwrite(g_sz.outBuf + offset, 1, outSize, o);
    fclose(o);
    if (w != outSize) return -4;
    return 0;
}

JNIEXPORT void JNICALL
Java_com_kanfan_player_Nat_szClose(JNIEnv*, jclass) {
    szCloseCtx();
}

JNIEXPORT jint JNI_OnLoad(JavaVM* vm, void*) {
    (void) vm;
    CrcGenerateTable();
    return JNI_VERSION_1_6;
}

} // extern "C"
