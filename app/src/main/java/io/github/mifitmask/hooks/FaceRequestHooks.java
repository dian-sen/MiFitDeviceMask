package io.github.mifitmask.hooks;

import android.util.Log;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.HashSet;
import java.util.Set;

import io.github.mifitmask.DeviceProfiles;
import io.github.mifitmask.MaskModule;
import io.github.mifitmask.Prefs;

/**
 * 表盘请求参数改写（v1.2 主功能，应用层——加密前的明文参数）。
 *
 * 依据：真机侦察证实小米运动健康所有业务请求走加密端点（data=密文 + signature），
 * 网络层改写不可行；FaceApiRequestV2（表盘域请求封装，dexdump 实证）的 suspend 方法
 * 参数即明文业务参数，在其 before 处做值匹配替换：
 * - 参数 String ≈ 当前机型代号（支持省略前缀）→ 替换为目标代号；
 * - 参数 Number == 当前机型 productId → 替换为目标 productId（类型对齐）；
 * - 参数中的 FaceParam 对象（字段 model/id_list，无 getter）→ 反射直改 model 字段；
 * 加密与签名基于改后明文，服务器无法区分。本地身份层零接触。
 */
public final class FaceRequestHooks {

    private static final String FACE_REQ_CLASS =
            "com.xiaomi.fitness.watch.face.request.FaceApiRequestV2";
    private static final String FACE_PARAM_CLASS =
            "com.xiaomi.fitness.watch.face.data.FaceParam";

    private static final Set<String> sSeen = new HashSet<>();

    /** FaceParam 类缓存（install 时解析） */
    private static Class<?> sFaceParamCls;
    /** FaceParam.model 字段缓存 */
    private static Field sFaceParamModelField;

    private FaceRequestHooks() {
    }

    public static void install(MaskModule mod, ClassLoader cl) throws Throwable {
        Class<?> c = Class.forName(FACE_REQ_CLASS, false, cl);
        try {
            sFaceParamCls = Class.forName(FACE_PARAM_CLASS, false, cl);
            sFaceParamModelField = sFaceParamCls.getDeclaredField("model");
            sFaceParamModelField.setAccessible(true);
        } catch (Throwable t) {
            mod.log(Log.WARN, MaskModule.TAG, "FaceParam resolve failed", t);
        }
        int hooked = 0;
        for (Method m : c.getDeclaredMethods()) {
            if (Modifier.isStatic(m.getModifiers()) || m.isSynthetic()) {
                continue;
            }
            Class<?>[] ps = m.getParameterTypes();
            if (ps.length == 0) {
                continue;
            }
            m.setAccessible(true);
            mod.hook(m).intercept(chain -> {
                Object[] args = chain.getArgs().toArray(new Object[0]);
                handleFaceParams(mod, args);
                StringBuilder sb = new StringBuilder(m.getName()).append('(');
                for (int i = 0; i < args.length; i++) {
                    if (args[i] != null && args[i].getClass().getName().contains("Continuation")) {
                        continue;
                    }
                    if (sb.charAt(sb.length() - 1) != '(') {
                        sb.append(", ");
                    }
                    sb.append(args[i]);
                }
                sb.append(')');
                String desc = sb.toString();
                if (sSeen.add(desc)) {
                    mod.log(Log.INFO, MaskModule.TAG, "FACE-REQ " + desc);
                }
                if (!mod.enabled() || !mod.rewriteFaceRequest()) {
                    return chain.proceed();
                }
                DeviceProfiles.Profile pf = mod.profile();
                String origModel = Prefs.getString(mod.prefs(), Prefs.KEY_ORIG_MODEL).trim();
                Long origPid = parseLong(Prefs.getString(mod.prefs(), Prefs.KEY_ORIG_PRODUCT_ID));
                if ((origModel.isEmpty() || !pf.hasModel()) && origPid == null) {
                    return chain.proceed();
                }
                Class<?>[] ptypes = m.getParameterTypes();
                Object[] newArgs = args.clone();
                boolean changed = false;
                for (int i = 0; i < newArgs.length; i++) {
                    Object a = newArgs[i];
                    if (a == null) {
                        continue;
                    }
                    if (a instanceof String && !origModel.isEmpty()
                            && LayerProductHooks.modelMatches((String) a, origModel)) {
                        newArgs[i] = pf.model;
                        changed = true;
                    } else if (a instanceof Number && origPid != null && pf.productId != null
                            && ((Number) a).longValue() == origPid.longValue()) {
                        newArgs[i] = (ptypes[i] == long.class || ptypes[i] == Long.class)
                                ? (Object) Long.valueOf(pf.productId.longValue())
                                : (Object) pf.productId;
                        changed = true;
                    }
                }
                if (changed) {
                    if (mod.debugLog()) {
                        StringBuilder after = new StringBuilder(m.getName()).append('(');
                        for (int i = 0; i < newArgs.length; i++) {
                            if (newArgs[i] != null
                                    && newArgs[i].getClass().getName().contains("Continuation")) {
                                continue;
                            }
                            if (after.charAt(after.length() - 1) != '(') {
                                after.append(", ");
                            }
                            after.append(newArgs[i]);
                        }
                        after.append(')');
                        mod.log(Log.INFO, MaskModule.TAG, "FACE-REWRITE " + after);
                    }
                    return chain.proceed(newArgs);
                }
                return chain.proceed();
            });
            hooked++;
        }
        mod.log(Log.INFO, MaskModule.TAG, "face request hooks installed: " + hooked);
    }

    /**
     * 扫描参数中的 FaceParam 实例：观察其 model 字段，并按值匹配改写
     * （FaceParam 无 getter，App 直读字段，改字段即改请求内容）。
     */
    private static void handleFaceParams(MaskModule mod, Object[] args) {
        if (sFaceParamCls == null || sFaceParamModelField == null) {
            return;
        }
        String orig = Prefs.getString(mod.prefs(), Prefs.KEY_ORIG_MODEL).trim();
        DeviceProfiles.Profile pf = mod.profile();
        for (Object a : args) {
            if (a == null || !sFaceParamCls.isInstance(a)) {
                continue;
            }
            try {
                String cur = (String) sFaceParamModelField.get(a);
                if (orig.isEmpty()) {
                    if (cur != null && !cur.isEmpty() && sSeen.add("fp:" + cur)) {
                        mod.log(Log.INFO, MaskModule.TAG, "FACE-PARAM model=\"" + cur + "\"");
                    }
                    continue;
                }
                if (pf.hasModel() && LayerProductHooks.modelMatches(cur, orig)
                        && !cur.equals(pf.model)) {
                    sFaceParamModelField.set(a, pf.model);
                    if (mod.debugLog() && sSeen.add("fpr:" + cur)) {
                        mod.log(Log.INFO, MaskModule.TAG, "FACE-PARAM-REWRITE model: "
                                + cur + " -> " + pf.model);
                    }
                }
            } catch (Throwable t) {
                mod.log(Log.WARN, MaskModule.TAG, "FaceParam handle failed", t);
            }
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
