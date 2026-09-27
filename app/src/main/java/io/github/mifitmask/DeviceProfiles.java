package io.github.mifitmask;

import android.content.SharedPreferences;

/**
 * 机型预设表。model 取社区通用蓝牙名（Gadgetbridge 等开源实现一致口径）；
 * productId / deviceSource 为 App 内部数值标识，未确认前置空（null = 不重写该字段，
 * 只改已知字段，避免破坏请求）。数值映射待真机抓包/探测后补充（见 ROADMAP v1.1）。
 */
public final class DeviceProfiles {

    public static class Profile {
        public final String id;
        public final String displayName;
        /** 蓝牙名/model 字符串，如 "Xiaomi Smart Band 9 Pro" */
        public final String model;
        public final Integer productId;
        public final Integer deviceSource;

        public Profile(String id, String displayName, String model,
                       Integer productId, Integer deviceSource) {
            this.id = id;
            this.displayName = displayName;
            this.model = model;
            this.productId = productId;
            this.deviceSource = deviceSource;
        }

        public boolean hasModel() {
            return model != null && !model.isEmpty();
        }
    }

    public static final Profile EMPTY = new Profile("", "未设置", "", null, null);

    /**
     * 预设表。model = App 内部代号，值匹配/改写/商店直取共用。
     * 代号表来自官方商店开源工具 get-mi-watchface 的权威映射（首字母=发布年份
     * M=2023 N=2024 O=2025；数字第二位 6=手环标准/NFC 7=手环 Pro 2=Watch S 5=Redmi Watch）：
     * 手环 8=m66cn、8 Pro=lchz.watch.m67、9=n66cn、9 Pro=n67cn（真机实证）、
     * 9 Active=n69cn、10=o66cn、Watch S3=mijia.watch.n62、Watch S4=o62、
     * Redmi Watch 4=lchz.watch.n65、Redmi Watch 5=o65。
     */
    public static final Profile[] PRESETS = {
            new Profile("mi_band_8", "小米手环 8", "miwear.watch.m66cn", null, null),
            new Profile("mi_band_8_pro", "小米手环 8 Pro", "lchz.watch.m67", null, null),
            new Profile("mi_band_9", "小米手环 9", "miwear.watch.n66cn", null, null),
            new Profile("mi_band_9_pro", "小米手环 9 Pro", "miwear.watch.n67cn", null, null),
            new Profile("mi_band_9_active", "小米手环 9 Active", "miwear.watch.n69cn", null, null),
            new Profile("mi_band_10", "小米手环 10", "miwear.watch.o66cn", null, null),
            new Profile("watch_s3", "Xiaomi Watch S3", "mijia.watch.n62", null, null),
            new Profile("watch_s4", "Xiaomi Watch S4", "mijia.watch.o62", null, null),
            new Profile("redmi_watch_4", "Redmi Watch 4", "lchz.watch.n65", null, null),
            new Profile("redmi_watch_5", "Redmi Watch 5", "miwear.watch.o65", null, null),
    };

    private DeviceProfiles() {
    }

    public static Profile byId(String id) {
        if (id == null) {
            return null;
        }
        for (Profile p : PRESETS) {
            if (id.equals(p.id)) {
                return p;
            }
        }
        return null;
    }

    /**
     * 解析生效机型：自定义 model 非空时优先（自定义数值字段有值才覆写），
     * 否则用预设；预设缺失时回退默认值。
     */
    public static Profile resolve(SharedPreferences p) {
        String customModel = Prefs.getString(p, Prefs.KEY_CUSTOM_MODEL).trim();
        if (!customModel.isEmpty()) {
            return new Profile("custom", "自定义", customModel,
                    parseInt(Prefs.getString(p, Prefs.KEY_CUSTOM_PRODUCT_ID)),
                    parseInt(Prefs.getString(p, Prefs.KEY_CUSTOM_DEVICE_SOURCE)));
        }
        String id = Prefs.getString(p, Prefs.KEY_MASK_TARGET).trim();
        Profile preset = byId(id);
        if (preset != null) {
            return preset;
        }
        Profile fallback = byId(Prefs.DEFAULT_TARGET);
        return fallback != null ? fallback : EMPTY;
    }

    private static Integer parseInt(String s) {
        if (s == null) {
            return null;
        }
        s = s.trim();
        if (s.isEmpty()) {
            return null;
        }
        try {
            return Integer.valueOf(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
