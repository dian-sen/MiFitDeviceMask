package io.github.mifitmask.hooks;

import android.bluetooth.BluetoothDevice;
import android.util.Log;

import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Set;

import io.github.mifitmask.DeviceProfiles;
import io.github.mifitmask.MaskModule;

/**
 * 第 1 层·本地设备档案伪装（默认开，主方案）。
 *
 * 小米运动健康在表盘商店等界面按"已连接设备机型"取数。本层在 App 进程内把
 * 设备名/model 的读取点覆写为目标机型：
 *
 * 1. BluetoothDevice.getName()：App 获取已连接手环蓝牙名的主要入口（通用系统类）。
 * 2. 设备档案候选类的零参 getter：类名随 App 版本混淆漂移，按多候选策略逐个尝试，
 *    单点失败只记日志；真机探测日志（ProbeHooks）回流后把命中点固化进候选表。
 * 3. 数值型标识（productId/deviceSource 等）仅在机型表给了值时覆写，未确认字段不动。
 *
 * 所有覆写均为"先原样执行再决定返回值"，不影响原调用链其他观察者。
 */
public final class DeviceIdentityHooks {

    /** 设备档案候选类（类名漂移风险高，靠探测日志迭代固化） */
    private static final String[] CANDIDATE_CLASSES = {
            "com.xiaomi.wearable.core.export.DeviceHelper",
            "com.xiaomi.wearable.core.export.DeviceInfoHelper",
            "com.xiaomi.fitness.device.export.DeviceHelper",
            "com.xiaomi.fitness.device.export.DeviceInfoHelper",
            "com.xiaomi.fitness.device.DeviceInfoHelper",
            "com.xiaomi.fitness.devicemanager.export.DeviceManager",
            "com.xiaomi.bluetooth.DeviceInfo",
            "com.xiaomi.miot.device.DeviceInfo",
    };

    /** 返回 model 字符串的候选 getter 名 */
    private static final String[] MODEL_METHODS = {
            "getDeviceModel", "getModel", "getProductModel",
            "getDeviceProductName", "getProductName",
    };

    /** 返回设备名（蓝牙名）的候选 getter 名 */
    private static final String[] NAME_METHODS = {
            "getDeviceName", "getBtName", "getBluetoothName",
    };

    /** 返回数值/字符串标识的候选 getter 名（仅机型表有值时覆写） */
    private static final String[] ID_METHODS = {
            "getProductCode", "getProductId", "getDeviceSource",
            "getProductType", "getDeviceType",
    };

    private DeviceIdentityHooks() {
    }

    /** 名字是否形似小米/穿戴设备（宽松正则，宁缺勿滥：手机/家电名不受影响）。 */
    static boolean looksLikeWearable(String name) {
        String n = name.toLowerCase();
        return n.startsWith("xiaomi") || n.startsWith("redmi")
                || n.startsWith("mi band") || n.startsWith("mi watch")
                || n.contains("smart band") || n.contains("smartband")
                || n.contains("watch") || n.contains("band")
                || name.contains("手环") || name.contains("手表");
    }

    public static void install(MaskModule mod, ClassLoader cl) throws Throwable {
        // 1) 蓝牙设备名改写：v1.1.4 起默认关闭（改写广播名会破坏 bltNamePrefix 匹配，
        //    导致添加/连接失败——真机实证）。需在设置页打开「改写蓝牙设备名」危险开关。
        Method btGetName = BluetoothDevice.class.getMethod("getName");
        mod.hook(btGetName).intercept(chain -> {
            Object real = chain.proceed();
            DeviceProfiles.Profile pf = mod.profile();
            if (!mod.rewriteBtName() || !pf.hasModel()) {
                return real;
            }
            String name = real == null ? null : real.toString();
            if (name == null || !looksLikeWearable(name)) {
                return real;
            }
            if (mod.debugLog()) {
                mod.logd("BluetoothDevice.getName: " + name + " -> " + pf.model);
            }
            return pf.model;
        });

        // 2) 设备档案候选类 getter（多候选，失败隔离）
        Set<String> hooked = new HashSet<>();
        for (String clsName : CANDIDATE_CLASSES) {
            final Class<?> clazz;
            try {
                clazz = Class.forName(clsName, false, cl);
            } catch (Throwable notFound) {
                mod.logd("candidate absent: " + clsName);
                continue;
            }
            mod.logd("candidate found: " + clsName);
            hookStringGetters(mod, clazz, hooked, MODEL_METHODS, true);
            hookStringGetters(mod, clazz, hooked, NAME_METHODS, true);
            hookIdGetters(mod, clazz, hooked);
        }
    }

    /** 覆写返回 String 的零参 getter（isModel=true 用 model 值，否则也用 model 作为设备名）。 */
    private static void hookStringGetters(MaskModule mod, Class<?> clazz, Set<String> hooked,
                                          String[] methodNames, boolean useModel) throws Throwable {
        for (String methodName : methodNames) {
            for (Method m : clazz.getDeclaredMethods()) {
                if (!methodName.equals(m.getName())
                        || m.getParameterTypes().length != 0
                        || m.getReturnType() != String.class) {
                    continue;
                }
                if (!hooked.add(clazz.getName() + '.' + methodName)) {
                    continue;
                }
                m.setAccessible(true);
                mod.hook(m).intercept(chain -> {
                    Object real = chain.proceed();
                    DeviceProfiles.Profile pf = mod.profile();
                    if (!mod.enabled() || !pf.hasModel()) {
                        return real;
                    }
                    if (mod.debugLog()) {
                        mod.logd(clazz.getSimpleName() + '.' + methodName
                                + ": " + real + " -> " + pf.model);
                    }
                    return pf.model;
                });
            }
        }
    }

    /**
     * 覆写数值/字符串标识 getter：仅当机型表提供了对应值。
     * deviceSource 类候选（getDeviceSource/getDeviceType）取 profile.deviceSource，
     * productCode 类候选（getProductCode/getProductId/getProductType）取 profile.productId。
     * 原始返回类型兼容 int/Integer/long/Long/String。
     */
    private static void hookIdGetters(MaskModule mod, Class<?> clazz, Set<String> hooked) throws Throwable {
        for (String methodName : ID_METHODS) {
            for (Method m : clazz.getDeclaredMethods()) {
                if (!methodName.equals(m.getName()) || m.getParameterTypes().length != 0) {
                    continue;
                }
                Class<?> ret = m.getReturnType();
                boolean numeric = ret == int.class || ret == Integer.class
                        || ret == long.class || ret == Long.class;
                if (!numeric && ret != String.class) {
                    continue;
                }
                if (!hooked.add(clazz.getName() + '.' + methodName)) {
                    continue;
                }
                final boolean isDeviceSource = methodName.contains("DeviceSource")
                        || methodName.contains("DeviceType");
                m.setAccessible(true);
                mod.hook(m).intercept(chain -> {
                    Object real = chain.proceed();
                    DeviceProfiles.Profile pf = mod.profile();
                    if (!mod.enabled()) {
                        return real;
                    }
                    Integer value = isDeviceSource ? pf.deviceSource : pf.productId;
                    if (value == null) {
                        return real;
                    }
                    if (mod.debugLog()) {
                        mod.logd(clazz.getSimpleName() + '.' + methodName
                                + ": " + real + " -> " + value);
                    }
                    if (ret == String.class) {
                        return String.valueOf(value);
                    }
                    return ret == long.class || ret == Long.class ? value.longValue() : value;
                });
            }
        }
    }
}
