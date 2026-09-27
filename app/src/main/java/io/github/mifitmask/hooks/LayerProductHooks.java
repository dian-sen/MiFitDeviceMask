package io.github.mifitmask.hooks;

import android.util.Log;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Set;

import io.github.mifitmask.DeviceProfiles;
import io.github.mifitmask.MaskModule;
import io.github.mifitmask.Prefs;

/**
 * 第 1 层强化·产品机型档案覆写（v1.1.0 起的主 hook 点，来源：真机 APK dexdump 实证）。
 *
 * 目标类（当前 App 版本真实存在，非猜测）：
 * - com.xiaomi.fitness.device.manager.bean.Product    机型档案：model/productId/bltNamePrefix
 * - com.xiaomi.fitness.device.manager.bean.DeviceInfo 设备档案：bleName
 *
 * 值匹配规则：
 * - 设置页填「当前机型 model / productId」（不知道留空 = 观察模式）；
 * - getter 返回值 == 当前机型标识 → 替换为目标机型对应值；
 * - v1.1.1 观察增强：getBltNamePrefix 观察时一次性反射读出同一对象的三字段
 *   （bltNamePrefix 命中的 Product 就是"已连接设备"档案），直接锁定当前机型。
 */
public final class LayerProductHooks {

    private static final String PRODUCT_CLASS =
            "com.xiaomi.fitness.device.manager.bean.Product";
    private static final String DEVICE_INFO_CLASS =
            "com.xiaomi.fitness.device.manager.bean.DeviceInfo";
    private static final String DEVICE_CLASS =
            "com.xiaomi.fitness.device.manager.bean.Device";

    /** 观察模式日志去重 */
    private static final Set<String> sSeen = new HashSet<>();

    /** 已连接设备的产品前缀（getBltNamePrefix 首次观察值；设备条目豁免改写的依据） */
    private static volatile String sDevicePrefix;

    private LayerProductHooks() {
    }

    public static void install(MaskModule mod, ClassLoader cl) throws Throwable {
        Class<?> product = Class.forName(PRODUCT_CLASS, false, cl);
        // v1.1.7：本地机型改写（Product 目录 + Device 标识）统一收进危险开关
        // rewriteLocalIdentity（默认关）——目录条目与连接/设备页强耦合，改写值会被
        // App 持久化（v1.1.6 真机实证：关闭模块后设备页仍为空）。默认仅观察。
        boolean rw = mod.rewriteLocalIdentity();
        hookBltNamePrefix(mod, product);
        hookStringGetter(mod, product, "getModel", rw);
        hookIntGetter(mod, product, "getProductId", rw);

        Class<?> deviceInfo = Class.forName(DEVICE_INFO_CLASS, false, cl);
        hookStringGetter(mod, deviceInfo, "getBleName", rw);

        // v1.1.3：已连接设备 bean（App 运行时真正读取的设备标识来源）。
        // v1.1.4：其覆写默认关闭（改写 Device.getModel 曾导致设备身份不匹配、无法连接，
        // 真机实证 Device.getModel 真实值 = miwear.watch.n67cn 即手环 9 Pro）；
        // 需在设置页打开「改写已连接设备标识」危险开关；观察模式始终可用。
        try {
            Class<?> device = Class.forName(DEVICE_CLASS, false, cl);
            hookStringGetter(mod, device, "getModel", rw);
            hookIntGetter(mod, device, "getProductId", rw);
        } catch (Throwable t) {
            mod.log(Log.WARN, MaskModule.TAG, "device bean hook skipped", t);
        }
    }

    /** 是否为"已连接设备"的目录条目（其 bltNamePrefix 字段 == 运行时记录的设备前缀）。 */
    private static boolean isDeviceEntry(Object self) {
        if (self == null || sDevicePrefix == null) {
            return false;
        }
        try {
            Field f = self.getClass().getDeclaredField("bltNamePrefix");
            f.setAccessible(true);
            Object v = f.get(self);
            return sDevicePrefix.equals(v == null ? null : v.toString());
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * bltNamePrefix：App 用它匹配已连接设备的蓝牙名 → 命中的 Product 即用户设备档案。
     * 观察模式：一并输出同对象的 model/productId 字段（反射直读，一次锁定当前机型）。
     */
    private static void hookBltNamePrefix(MaskModule mod, Class<?> clazz) throws Throwable {
        for (Method m : clazz.getDeclaredMethods()) {
            if (!"getBltNamePrefix".equals(m.getName())
                    || m.getParameterTypes().length != 0
                    || m.getReturnType() != String.class) {
                continue;
            }
            m.setAccessible(true);
            mod.hook(m).intercept(chain -> {
                Object real = chain.proceed();
                if (!mod.enabled()) {
                    return real;
                }
                String prefix = real == null ? null : real.toString();
                DeviceProfiles.Profile pf = mod.profile();
                String orig = Prefs.getString(mod.prefs(), Prefs.KEY_ORIG_MODEL).trim();
                if (orig.isEmpty()) {
                    if (prefix != null && !prefix.isEmpty()
                            && sSeen.add("blt:" + prefix)) {
                        sDevicePrefix = prefix;
                        String dump = dumpProductFields(chain.getThisObject());
                        mod.log(Log.INFO, MaskModule.TAG, "OBSERVE-DEVICE obj="
                                + Integer.toHexString(System.identityHashCode(chain.getThisObject()))
                                + " " + prefix + (dump.isEmpty() ? "" : "  fields=" + dump));
                    }
                    return real;
                }
                // v1.1.4：bltNamePrefix 改写同样受"改写蓝牙设备名"危险开关约束
                // （前缀被改会导致 App 无法匹配到已连接设备）
                if (mod.rewriteBtName() && pf.hasModel() && orig.equals(prefix)) {
                    if (mod.debugLog() && sSeen.add("rblt:" + prefix)) {
                        mod.log(Log.INFO, MaskModule.TAG, "REWRITE getBltNamePrefix: "
                                + prefix + " -> " + pf.model);
                    }
                    return pf.model;
                }
                return real;
            });
            mod.logd("hooked " + clazz.getSimpleName() + ".getBltNamePrefix");
        }
    }

    /** 反射直读 Product 的 model/productId/bltNamePrefix 字段（观察模式一次拿全；异常可见，不静默）。 */
    private static String dumpProductFields(Object self) {
        if (self == null) {
            return "(self=null)";
        }
        StringBuilder sb = new StringBuilder();
        for (String fn : new String[]{"model", "productId", "bltNamePrefix"}) {
            try {
                Field f = self.getClass().getDeclaredField(fn);
                f.setAccessible(true);
                Object v = f.get(self);
                if (sb.length() > 0) {
                    sb.append(", ");
                }
                sb.append(fn).append('=').append(v);
            } catch (Throwable t) {
                if (sb.length() > 0) {
                    sb.append(", ");
                }
                sb.append(fn).append("=<err ").append(t.getClass().getSimpleName())
                        .append(": ").append(t.getMessage()).append('>');
            }
        }
        return sb.toString();
    }

    /**
     * String getter：值匹配当前机型（支持省略 miwear.watch. 前缀）→ 改写；未配置时观察记录。
     * allowRewrite=false 时仅观察（危险改写开关未开的层）。
     */
    private static void hookStringGetter(MaskModule mod, Class<?> clazz, String method,
                                         boolean allowRewrite) throws Throwable {
        for (Method m : clazz.getDeclaredMethods()) {
            if (!method.equals(m.getName())
                    || m.getParameterTypes().length != 0
                    || m.getReturnType() != String.class) {
                continue;
            }
            m.setAccessible(true);
            mod.hook(m).intercept(chain -> {
                Object real = chain.proceed();
                if (!mod.enabled()) {
                    return real;
                }
                String value = real == null ? null : real.toString();
                DeviceProfiles.Profile pf = mod.profile();
                String orig = Prefs.getString(mod.prefs(), Prefs.KEY_ORIG_MODEL).trim();
                if (orig.isEmpty()) {
                    if (value != null && !value.isEmpty()
                            && sSeen.add("s:" + clazz.getSimpleName() + "." + method + "=" + value)) {
                        mod.log(Log.INFO, MaskModule.TAG, "OBSERVE " + clazz.getSimpleName()
                                + "." + method + "() = \"" + value + "\" obj="
                                + Integer.toHexString(System.identityHashCode(chain.getThisObject())));
                    }
                    return real;
                }
                // v1.1.5：跳过"已连接设备"的目录条目——改它会让 App 按 model 找不到
                // 设备档案，设备页显示为空（v1.1.4 真机回归）。
                if (isDeviceEntry(chain.getThisObject())) {
                    if (mod.debugLog() && sSeen.add("skipdev:" + value)) {
                        mod.log(Log.INFO, MaskModule.TAG, "SKIP device entry ("
                                + clazz.getSimpleName() + "." + method + "=" + value + ")");
                    }
                    return real;
                }
                if (allowRewrite && modelMatches(value, orig) && pf.hasModel()) {
                    if (mod.debugLog() && sSeen.add("r:" + value + "->" + pf.model)) {
                        mod.log(Log.INFO, MaskModule.TAG, "REWRITE " + clazz.getSimpleName()
                                + "." + method + ": " + value + " -> " + pf.model);
                    }
                    return pf.model;
                }
                return real;
            });
            mod.logd("hooked " + clazz.getSimpleName() + "." + method
                    + (allowRewrite ? "" : " (observe-only)"));
        }
    }

    /** 值匹配：完全相等，或去掉 miwear.watch./mijia.watch. 等前缀后相等（用户可填短代号）。 */
    static boolean modelMatches(String value, String orig) {        if (value == null || value.isEmpty()) {
            return false;
        }
        if (value.equals(orig)) {
            return true;
        }
        int dot = value.lastIndexOf('.');
        String shortName = dot >= 0 ? value.substring(dot + 1) : value;
        return shortName.equalsIgnoreCase(orig);
    }

    /** int getter：值匹配当前机型 productId → 改写（allowRewrite 时）；未配置时观察记录。 */
    private static void hookIntGetter(MaskModule mod, Class<?> clazz, String method,
                                      boolean allowRewrite) throws Throwable {
        for (Method m : clazz.getDeclaredMethods()) {
            if (!method.equals(m.getName()) || m.getParameterTypes().length != 0) {
                continue;
            }
            Class<?> ret = m.getReturnType();
            if (ret != int.class && ret != Integer.class && ret != long.class && ret != Long.class) {
                continue;
            }
            boolean isLong = ret == long.class || ret == Long.class;
            m.setAccessible(true);
            mod.hook(m).intercept(chain -> {
                Object real = chain.proceed();
                if (!mod.enabled() || real == null) {
                    return real;
                }
                long value = ((Number) real).longValue();
                DeviceProfiles.Profile pf = mod.profile();
                Long orig = parseLong(Prefs.getString(mod.prefs(), Prefs.KEY_ORIG_PRODUCT_ID));
                Integer target = pf.productId;
                if (orig == null) {
                    if (sSeen.add("i:" + clazz.getSimpleName() + "." + method + "=" + value)) {
                        mod.log(Log.INFO, MaskModule.TAG, "OBSERVE " + clazz.getSimpleName()
                                + "." + method + "() = " + value + " obj="
                                + Integer.toHexString(System.identityHashCode(chain.getThisObject())));
                    }
                    return real;
                }
                if (allowRewrite && target != null && orig == value) {
                    if (mod.debugLog() && sSeen.add("ri:" + value)) {
                        mod.log(Log.INFO, MaskModule.TAG, "REWRITE " + clazz.getSimpleName()
                                + "." + method + ": " + value + " -> " + target);
                    }
                    return isLong ? (Object) target.longValue() : (Object) target;
                }
                return real;
            });
            mod.logd("hooked " + clazz.getSimpleName() + "." + method
                    + (allowRewrite ? "" : " (observe-only)"));
        }
    }

    private static Long parseLong(String s) {
        if (s == null) {
            return null;
        }
        s = s.trim();
        if (s.isEmpty()) {
            return null;
        }
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
