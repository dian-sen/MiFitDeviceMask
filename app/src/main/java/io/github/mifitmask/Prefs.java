package io.github.mifitmask;

import android.content.SharedPreferences;

/**
 * 设置键定义（libxposed RemotePreferences，框架自动同步到目标进程）。
 * 设置页写、hook 侧读；所有读取必须带默认值（首启无值时行为确定）。
 */
public final class Prefs {

    public static final String GROUP = "mifit_mask_settings";

    /** 总开关（默认开） */
    public static final String KEY_ENABLE_ALL = "enable_all";
    /** 目标机型预设 id（DeviceProfiles），默认小米手环 9 Pro */
    public static final String KEY_MASK_TARGET = "mask_target";
    /** 当前机型 model 字符串（值匹配用；空 = 观察模式，只记录不改写） */
    public static final String KEY_ORIG_MODEL = "orig_model";
    /** 当前机型 productId（值匹配用；空 = 观察模式） */
    public static final String KEY_ORIG_PRODUCT_ID = "orig_product_id";
    /** 自定义机型 model 字符串（非空时优先于预设） */
    public static final String KEY_CUSTOM_MODEL = "custom_model";
    /** 自定义 productId（可选，未确认前留空） */
    public static final String KEY_CUSTOM_PRODUCT_ID = "custom_product_id";
    /** 自定义 deviceSource（可选，未确认前留空） */
    public static final String KEY_CUSTOM_DEVICE_SOURCE = "custom_device_source";
    /** 第 2 层：表盘商店请求参数重写（实验，默认关） */
    public static final String KEY_ENABLE_NET_REWRITE = "enable_net_rewrite";
    /** 危险开关：改写蓝牙设备名（默认关——破坏 bltNamePrefix 与广播名匹配，导致添加/连接失败） */
    public static final String KEY_REWRITE_BT_NAME = "rewrite_bt_name";
    /**
     * 危险开关：本地机型改写总闸（默认关——Product 目录/Device 标识与连接、设备页渲染强耦合，
     * 改写值还会被 App 写入本地库造成持久污染，v1.1.6 真机实证）。默认仅观察。
     */
    public static final String KEY_REWRITE_CONNECTED = "rewrite_connected";
    /** 表盘请求参数改写（应用层明文参数值匹配，默认开；不触本地身份/连接/持久化） */
    public static final String KEY_REWRITE_FACE_REQUEST = "rewrite_face_request";
    /** 本地表盘安装：中继文件 Uri（模块 App 自有 Provider 提供） */
    public static final String KEY_INSTALL_URI = "install_uri";
    /** 本地表盘安装：faceId（用户填写，或默认文件名去扩展名） */
    public static final String KEY_INSTALL_FACE_ID = "install_face_id";
    /** 本地表盘安装：请求令牌（设置页递增，目标进程轮询检测） */
    public static final String KEY_INSTALL_TOKEN = "install_token";
    /** 本地表盘安装：执行结果（目标进程写回，设置页轮询显示） */
    public static final String KEY_INSTALL_RESULT = "install_result";
    /** 调试/探测日志（默认开） */
    public static final String KEY_DEBUG_LOG = "debug_log";

    public static final String DEFAULT_TARGET = "mi_band_9_pro";

    private Prefs() {
    }

    public static boolean isEnabled(SharedPreferences p, String key) {
        return p != null && p.getBoolean(key, true);
    }

    public static boolean isEnabled(SharedPreferences p, String key, boolean def) {
        return p != null && p.getBoolean(key, def);
    }

    public static String getString(SharedPreferences p, String key) {
        return p == null ? "" : p.getString(key, "");
    }

    public static int getInt(SharedPreferences p, String key, int def) {
        return p == null ? def : p.getInt(key, def);
    }

    /** profile 缓存键：原始设置组合，变更即失效缓存。 */
    public static String profileKey(SharedPreferences p) {
        if (p == null) {
            return "null";
        }
        return getString(p, KEY_MASK_TARGET) + '|'
                + getString(p, KEY_CUSTOM_MODEL) + '|'
                + getString(p, KEY_CUSTOM_PRODUCT_ID) + '|'
                + getString(p, KEY_CUSTOM_DEVICE_SOURCE) + '|'
                + getString(p, KEY_ORIG_MODEL) + '|'
                + getString(p, KEY_ORIG_PRODUCT_ID);
    }
}
