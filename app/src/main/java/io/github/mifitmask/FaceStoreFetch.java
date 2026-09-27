package io.github.mifitmask;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * v1.4 表盘商店直取：小米官方表盘商店存在免登录公开查询接口
 *   GET https://watch-appstore.iot.mi.com/api/watchface/prize/detail?model=<代号>&id=<数字faceId>
 * 响应含 data.recommend_list[0] = { display_name, icon, config_file(.bin), config_file_v2(.mwz) }。
 * 来源：开源项目 get-mi-watchface（aurysian-yan）反向验证；与账号无关，任意机型代号可用。
 *
 * 流程：查询 detail → 下载 config_file(.bin) → 经 relay+token 进入本地安装通道（LocalInstallHooks）。
 */
public final class FaceStoreFetch {

    private static final String DETAIL_URL =
            "https://watch-appstore.iot.mi.com/api/watchface/prize/detail?model=%s&id=%s";

    private FaceStoreFetch() {
    }

    /** UI 回调（主线程） */
    public interface StatusListener {
        void onStatus(String s);
    }

    private static void post(Handler h, StatusListener l, String s) {
        h.post(() -> l.onStatus(s));
    }

    /**
     * 从官方商店获取指定 faceId 的表盘。
     *
     * @param model        目标机型内部代号（如 miwear.watch.o66cn）
     * @param faceId       表盘数字 ID
     * @param downloadOnly true=仅下载 .bin 到公共 Download 目录（不入安装队列）
     */
    public static void fetchAndEnqueue(Context ctx, PrefsAccess prefs, String model,
                                       String faceId, StatusListener listener, boolean downloadOnly) {
        Handler h = new Handler(Looper.getMainLooper());
        Thread t = new Thread(() -> {
            String displayName = null;
            try {
                String url = String.format(DETAIL_URL, urlEnc(model), urlEnc(faceId));
                post(h, listener, "查询官方商店：model=" + model + " id=" + faceId);
                JSONObject body = httpGetJson(url);
                JSONObject data = body.optJSONObject("data");
                if (data == null) {
                    post(h, listener, "失败：商店响应无 data（faceId 可能无效或该机型无此表盘）");
                    return;
                }
                org.json.JSONArray list = data.optJSONArray("recommend_list");
                if (list == null || list.length() == 0) {
                    post(h, listener, "失败：该 faceId 在此机型下无推荐表盘");
                    return;
                }
                JSONObject item = list.getJSONObject(0);
                displayName = item.optString("display_name", "(未命名)");
                String binUrl = item.optString("config_file", "");
                post(h, listener, "找到表盘：" + displayName + "，下载中…");
                if (binUrl.isEmpty()) {
                    post(h, listener, "失败：响应中无 config_file（.bin）下载地址");
                    return;
                }
                byte[] bin = httpGetBytes(binUrl);
                if (downloadOnly) {
                    String where = saveToDownloads(ctx, faceId, bin);
                    post(h, listener, "成功：已下载 " + displayName + "（" + (bin.length / 1024)
                            + " KB）→ " + where + "（未安装）");
                    return;
                }
                // 入本地安装队列：relay + command.json（token|faceId|fileName）
                File relay = new File(ctx.getCacheDir(), "relay");
                if (!relay.exists()) {
                    relay.mkdirs();
                }
                String name = "store_" + faceId + ".bin";
                try (FileOutputStream os = new FileOutputStream(new File(relay, name))) {
                    os.write(bin);
                }
                post(h, listener, "已下载 " + displayName + "（" + (bin.length / 1024) + " KB），进入本地安装队列…");
                int token = prefs.getInt(Prefs.KEY_INSTALL_TOKEN, 0) + 1;
                String cmd = token + "|" + faceId + "|" + name;
                java.nio.file.Files.write(new File(relay, "command.json").toPath(),
                        cmd.getBytes(StandardCharsets.UTF_8));
                prefs.putInt(Prefs.KEY_INSTALL_TOKEN, token);
                post(h, listener, "已入队（token=" + token + "），等待小米运动健康推送手环…");
            } catch (Throwable ex) {
                post(h, listener, "失败：" + ex.getClass().getSimpleName()
                        + (ex.getMessage() != null ? " " + ex.getMessage() : ""));
            }
        }, "face-store-fetch");
        t.start();
    }

    /** 下载文件保存到公共 Download 目录（API 29+ MediaStore；旧设备回退应用外部目录）。 */
    private static String saveToDownloads(Context ctx, String faceId, byte[] bytes) throws Exception {
        String name = "MiFitFace_" + faceId + "_" + System.currentTimeMillis() + ".bin";
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            android.content.ContentValues cv = new android.content.ContentValues();
            cv.put(android.provider.MediaStore.Downloads.DISPLAY_NAME, name);
            cv.put(android.provider.MediaStore.Downloads.MIME_TYPE, "application/octet-stream");
            Uri uri = ctx.getContentResolver()
                    .insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
            if (uri == null) {
                throw new Exception("MediaStore insert failed");
            }
            try (OutputStream os = ctx.getContentResolver().openOutputStream(uri)) {
                os.write(bytes);
            }
            return "下载目录/" + name;
        }
        File dir = ctx.getExternalFilesDir(null);
        File f = new File(dir, name);
        try (FileOutputStream os = new FileOutputStream(f)) {
            os.write(bytes);
        }
        return "应用外部目录/" + name;
    }

    private static JSONObject httpGetJson(String url) throws Exception {
        String body = httpGetText(url);
        return new JSONObject(body);
    }

    private static String httpGetText(String url) throws Exception {
        byte[] b = httpGetBytes(url);
        return new String(b, StandardCharsets.UTF_8);
    }

    private static byte[] httpGetBytes(String url) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(30000);
        conn.setRequestProperty("User-Agent", "MiFitDeviceMask/1.4");
        int code = conn.getResponseCode();
        if (code != 200) {
            conn.disconnect();
            throw new Exception("HTTP " + code);
        }
        InputStream in = conn.getInputStream();
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) > 0) {
            bos.write(buf, 0, n);
        }
        in.close();
        conn.disconnect();
        return bos.toByteArray();
    }

    private static String urlEnc(String s) {
        try {
            return java.net.URLEncoder.encode(s, "UTF-8");
        } catch (Throwable t) {
            return s;
        }
    }

    /** 设置页使用的 prefs 访问封装（避免直依赖 SettingsActivity）。 */
    public interface PrefsAccess {
        int getInt(String key, int def);

        void putInt(String key, int value);
    }
}
