package io.github.mifitmask.hooks;

import android.bluetooth.BluetoothDevice;
import android.util.Log;

import java.lang.reflect.Method;

import io.github.mifitmask.DeviceProfiles;
import io.github.mifitmask.MaskModule;

/**
 * 探测日志（调试开关开时启用，随设置即时生效需重启目标进程）。
 *
 * 目的：机型档案的精确混淆类名/字段名随 App 版本漂移，真机跑一次探测日志、
 * 把命中的类/方法/取值回流到 WORKLOG，即可把有效点固化进 DeviceIdentityHooks 候选表。
 *
 * 输出：
 * 1. 候选类存在性与设备相关零参方法签名清单；
 * 2. BluetoothDevice.getName() 原始返回值（观察真实设备名）；
 * 3. FaceHelperImpl（表盘域已验证类）字符串 getter 的返回值观察。
 */
public final class ProbeHooks {

    private static final String[] CANDIDATE_CLASSES = {
            "com.xiaomi.wearable.core.export.DeviceHelper",
            "com.xiaomi.wearable.core.export.DeviceInfoHelper",
            "com.xiaomi.fitness.device.export.DeviceHelper",
            "com.xiaomi.fitness.device.export.DeviceInfoHelper",
            "com.xiaomi.fitness.device.DeviceInfoHelper",
            "com.xiaomi.fitness.devicemanager.export.DeviceManager",
            "com.xiaomi.bluetooth.DeviceInfo",
            "com.xiaomi.miot.device.DeviceInfo",
            "com.xiaomi.fitness.watch.face.export.FaceHelperImpl",
            "com.xiaomi.fitness.watch.face.install.FaceInstallBleImpl",
            "com.xiaomi.fitness.watch.face.install.FaceInstallHuamiImpl",
    };

    private static final String[] FACE_CLASS = {
            "com.xiaomi.fitness.watch.face.export.FaceHelperImpl",
    };

    /** 观察去重：已记录过的蓝牙名 */
    private static final java.util.Set<String> sSeenNames = new java.util.HashSet<>();

    private ProbeHooks() {
    }

    public static void install(MaskModule mod, ClassLoader cl) throws Throwable {
        // 1) 候选类清单 + 设备相关方法签名
        for (String name : CANDIDATE_CLASSES) {
            try {
                Class<?> c = Class.forName(name, false, cl);
                StringBuilder sb = new StringBuilder("probe class ").append(name).append(" ->");
                int found = 0;
                for (Method m : c.getDeclaredMethods()) {
                    if (m.getParameterTypes().length == 0 && looksDeviceRelated(m.getName())) {
                        sb.append(' ').append(m.getName()).append("():").append(m.getReturnType().getSimpleName());
                        found++;
                        if (found >= 25) {
                            sb.append(" ...(truncated)");
                            break;
                        }
                    }
                }
                mod.log(Log.INFO, MaskModule.TAG, sb.toString());
            } catch (Throwable absent) {
                mod.logd("probe absent: " + name);
            }
        }

        // 2) 蓝牙名观察（v1.1.0：同值去重，防刷屏；只记录形似穿戴设备的名字）
        Method btGetName = BluetoothDevice.class.getMethod("getName");
        mod.hook(btGetName).intercept(chain -> {
            Object real = chain.proceed();
            String name = real == null ? null : real.toString();
            if (name != null && !name.isEmpty()
                    && DeviceIdentityHooks.looksLikeWearable(name)
                    && sSeenNames.add(name)) {
                DeviceProfiles.Profile pf = mod.profile();
                mod.log(Log.INFO, MaskModule.TAG, "probe BT name = " + name
                        + " (mask=" + (pf != null ? pf.model : "n/a") + ")");
            }
            return real;
        });

        // 3) 表盘域已验证类的字符串 getter 观察
        for (String name : FACE_CLASS) {
            try {
                Class<?> c = Class.forName(name, false, cl);
                for (Method m : c.getDeclaredMethods()) {
                    if (m.getParameterTypes().length != 0 || m.getReturnType() != String.class) {
                        continue;
                    }
                    final Method fm = m;
                    fm.setAccessible(true);
                    mod.hook(fm).intercept(chain -> {
                        Object real = chain.proceed();
                        mod.logd("probe " + name.substring(name.lastIndexOf('.') + 1)
                                + '.' + fm.getName() + "() = " + real);
                        return real;
                    });
                }
            } catch (Throwable t) {
                mod.logd("probe face class absent: " + name);
            }
        }
    }

    /** 方法名是否与设备信息相关（宽松匹配，仅用于日志采样）。 */
    private static boolean looksDeviceRelated(String methodName) {
        String n = methodName.toLowerCase();
        return n.contains("device") || n.contains("model") || n.contains("product")
                || n.contains("name") || n.contains("source") || n.contains("version")
                || n.contains("face") || n.contains("firmware");
    }
}
