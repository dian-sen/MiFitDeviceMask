package io.github.mifitmask;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import java.io.File;

/**
 * 本地表盘安装的中继 Provider：
 * - openFile：relay/ 目录下按文件名的只读访问（表盘文件）；
 * - query(command)：返回安装指令（token|faceId|fileName，设置页写入 command.json）；
 * - insert(result)：接收目标进程写回的安装结果（进程内存 + result.txt）；
 * - query(result)：返回最新安装结果（设置页轮询显示）。
 */
public class FileServeProvider extends ContentProvider {

    /** 最新安装结果（Provider 与设置页同进程，直接内存可见） */
    private static volatile String sLastResult = "";

    private File relayDir() {
        return new File(getContext().getCacheDir(), "relay");
    }

    private File commandFile() {
        return new File(relayDir(), "command.json");
    }

    private File resultFile() {
        return new File(relayDir(), "result.txt");
    }

    private String readText(File f) {
        try {
            byte[] b = java.nio.file.Files.readAllBytes(f.toPath());
            return new String(b, java.nio.charset.StandardCharsets.UTF_8);
        } catch (Throwable t) {
            return "";
        }
    }

    private void writeText(File f, String s) {
        try {
            if (!relayDir().exists()) {
                relayDir().mkdirs();
            }
            java.nio.file.Files.write(f.toPath(), s.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (Throwable ignored) {
        }
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) {
        String name = uri.getLastPathSegment();
        if (name == null || name.isEmpty() || name.contains("/") || name.contains("\\")
                || name.contains("..")) {
            throw new IllegalArgumentException("invalid file name");
        }
        File f = new File(relayDir(), name);
        if (!f.exists()) {
            throw new IllegalArgumentException("file not found: " + name);
        }
        try {
            return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        String path = uri.getLastPathSegment();
        if ("command".equals(path)) {
            String cmd = readText(commandFile());
            MatrixCursor mc = new MatrixCursor(new String[]{"value"});
            if (!cmd.isEmpty()) {
                mc.addRow(new Object[]{cmd});
            }
            return mc;
        }
        if ("result".equals(path)) {
            String res = sLastResult;
            if (res.isEmpty()) {
                res = readText(resultFile());
            }
            MatrixCursor mc = new MatrixCursor(new String[]{"value"});
            mc.addRow(new Object[]{res == null ? "" : res});
            return mc;
        }
        return null;
    }

    @Override
    public String getType(Uri uri) {
        return "application/octet-stream";
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        if ("result".equals(uri.getLastPathSegment()) && values != null) {
            String r = values.getAsString("result");
            if (r != null) {
                sLastResult = r;
                writeText(resultFile(), r);
            }
        }
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}
