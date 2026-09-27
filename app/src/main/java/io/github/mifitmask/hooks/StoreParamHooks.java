package io.github.mifitmask.hooks;

import android.util.Log;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.URLEncoder;
import java.util.List;

import io.github.mifitmask.DeviceProfiles;
import io.github.mifitmask.MaskModule;

/**
 * 第 2 层·表盘商店请求参数重写（实验开关，默认关）。
 *
 * 原理：hook OkHttpClient.Builder.build()，在客户端构建前往拦截器列表注入一个
 * 动态代理实现的 okhttp3.Interceptor；命中表盘商店 URL（关键词）的请求被改写
 * query 与 JSON body 中的机型相关字段为目标机型值。
 *
 * 说明：参数名集合为社区抓包常见命名，未经实机全部验证，故默认关闭；
 * 启用前建议先用抓包确认目标 App 实际使用的键名（见 docs/WORKFLOW.md 测试流程）。
 * 实现全程反射 + 异常吞并，任何失败都只记日志、绝不影响原请求。
 */
public final class StoreParamHooks {

    /** URL 命中关键词（出现任一即视为表盘商店请求） */
    private static final String[] URL_KEYWORDS = {
            "watchface", "watch_face", "watch-face", "watchfacestore",
    };

    /** 字符串型参数名（覆写为目标 model） */
    private static final String[] STRING_KEYS = {
            "model", "deviceModel", "device_model", "productModel", "productName",
    };

    /** 数值型参数名（仅机型表有值时覆写） */
    private static final String[] INT_KEYS = {
            "productCode", "productId", "product_id",
            "deviceSource", "device_source",
    };

    private static volatile boolean sInjected;
    private static volatile boolean sRewriteLogged;
    /** 侦察：已记录过的全部请求 URL 去重 */
    private static final java.util.Set<String> sSeenUrls = new java.util.HashSet<>();
    /** 侦察：已记录过的商店请求 URL 去重 */
    private static final java.util.Set<String> sSeenStoreUrls = new java.util.HashSet<>();

    private StoreParamHooks() {
    }

    public static void install(MaskModule mod, ClassLoader cl) throws Throwable {
        // v1.2.2：全局出站侦察——hook Request.Builder.url(...)，覆盖一切 OkHttp client
        // （含 App 早期构建、我们无法注入拦截器的实例），用于判定商店数据来源
        try {
            Class<?> rbCls = Class.forName("okhttp3.Request$Builder", true, cl);
            for (Method m : rbCls.getDeclaredMethods()) {
                if (!"url".equals(m.getName()) || m.getParameterTypes().length != 1
                        || m.getParameterTypes()[0] != String.class) {
                    continue;
                }
                m.setAccessible(true);
                mod.hook(m).intercept(chain -> {
                    Object u = chain.getArg(0);
                    if (u != null) {
                        String url = u.toString();
                        if (sSeenUrls.add(url)) {
                            mod.log(Log.INFO, MaskModule.TAG, "REQ " + url);
                        }
                    }
                    return chain.proceed();
                });
            }
            mod.logd("request builder url recon hooked");
        } catch (Throwable t) {
            mod.log(Log.WARN, MaskModule.TAG, "url recon failed", t);
        }
        Class<?> builderCls = Class.forName("okhttp3.OkHttpClient$Builder", true, cl);
        Method build = builderCls.getDeclaredMethod("build");
        // v1.1.5：注入条件放宽为总开关——侦察模式（记录商店请求 URL）不需要改写开关
        mod.hook(build).intercept(chain -> {
            if (!mod.enabled() || sInjected) {
                return chain.proceed();
            }
            sInjected = true;
            try {
                addInterceptor(mod, chain.getThisObject(), cl);
                mod.logd("okhttp store-rewrite interceptor injected");
            } catch (Throwable t) {
                mod.log(Log.WARN, MaskModule.TAG, "inject interceptor failed", t);
            }
            return chain.proceed();
        });
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void addInterceptor(MaskModule mod, Object builder, ClassLoader cl) throws Throwable {
        Field f = builder.getClass().getDeclaredField("interceptors");
        f.setAccessible(true);
        List interceptors = (List) f.get(builder);
        Class<?> interceptorCls = Class.forName("okhttp3.Interceptor", true, cl);
        Object proxy = Proxy.newProxyInstance(cl, new Class[]{interceptorCls},
                (inv, method, args) -> {
                    String name = method.getName();
                    if ("intercept".equals(name)) {
                        return handleIntercept(mod, inv, args[0]);
                    }
                    if ("equals".equals(name)) {
                        return inv == args[0];
                    }
                    if ("hashCode".equals(name)) {
                        return System.identityHashCode(inv);
                    }
                    if ("toString".equals(name)) {
                        return "MifitMaskStoreInterceptor";
                    }
                    return null;
                });
        interceptors.add(proxy);
    }

    /** 拦截器主体：改写请求后放行；无改写需求时必须用原 request 继续（proceed(null) 会 NPE 崩溃，v1.1.6 修复）。 */
    private static Object handleIntercept(MaskModule mod, Object self, Object chainObj) throws Throwable {
        Object request;
        try {
            request = chainObj.getClass().getMethod("request").invoke(chainObj);
        } catch (Throwable t) {
            // 拿不到 request 无法放行：抛出让 OkHttp 将该请求标记为失败（不崩进程）
            throw new IllegalStateException("MifitMask: cannot read request from chain", t);
        }
        Object newRequest = null;
        try {
            newRequest = rewriteRequest(mod, request);
        } catch (Throwable t) {
            mod.log(Log.WARN, MaskModule.TAG, "rewrite request failed (passthrough)", t);
        }
        return proceedChain(chainObj, newRequest != null ? newRequest : request);
    }

    private static Object proceedChain(Object chainObj, Object requestToUse) throws Throwable {
        Method proceed = null;
        for (Method m : chainObj.getClass().getMethods()) {
            if (!"proceed".equals(m.getName()) || m.getParameterTypes().length != 1) {
                continue;
            }
            proceed = m;
            break;
        }
        if (proceed == null) {
            throw new NoSuchMethodException("RealInterceptorChain.proceed not found");
        }
        try {
            return proceed.invoke(chainObj, requestToUse);
        } catch (InvocationTargetException e) {
            throw e.getCause() != null ? e.getCause() : e;
        }
    }

    /** 返回改写后的 Request；无需改写返回 null（原样放行）。 */
    private static Object rewriteRequest(MaskModule mod, Object request) throws Throwable {
        Class<?> reqCls = request.getClass();
        String urlStr = String.valueOf(reqCls.getMethod("url").invoke(request));
        // v1.1.7 侦察：记录所有去重请求（含 host+path），确保能拿到商店真实参数键名
        if (sSeenUrls.add(urlStr)) {
            mod.log(Log.INFO, MaskModule.TAG, "REQ " + urlStr);
        }
        // v1.1.5 侦察模式：命中商店 URL 即记录完整请求（参数名/值），供确认真实键名
        if (hitUrl(urlStr) && sSeenStoreUrls.add(urlStr)) {
            mod.log(Log.INFO, MaskModule.TAG, "STORE-URL " + urlStr);
        }
        if (!mod.netRewriteEnabled()) {
            return null;
        }
        DeviceProfiles.Profile pf = mod.profile();
        String newUrl = rewriteQuery(urlStr, pf);
        Object body = reqCls.getMethod("body").invoke(request);
        Object newBody = null;
        if (body != null) {
            Object mediaType = body.getClass().getMethod("contentType").invoke(body);
            String contentType = mediaType == null ? null : String.valueOf(mediaType);
            if (contentType != null && contentType.contains("json")) {
                String raw = readBody(body);
                if (raw != null) {
                    String rewritten = rewriteJson(raw, pf);
                    if (!rewritten.equals(raw)) {
                        newBody = createRequestBody(body, rewritten);
                    }
                }
            }
        }
        if (newUrl.equals(urlStr) && newBody == null) {
            return null;
        }
        if (!sRewriteLogged) {
            sRewriteLogged = true;
            mod.logd("store request rewritten: " + urlStr);
        }
        Object builder = reqCls.getMethod("newBuilder").invoke(request);
        if (!newUrl.equals(urlStr)) {
            builder.getClass().getMethod("url", String.class).invoke(builder, newUrl);
        }
        if (newBody != null) {
            String method = String.valueOf(reqCls.getMethod("method").invoke(request));
            Class<?> bodyCls = Class.forName("okhttp3.RequestBody", true, request.getClass().getClassLoader());
            builder.getClass().getMethod("method", String.class, bodyCls)
                    .invoke(builder, method, newBody);
        }
        return builder.getClass().getMethod("build").invoke(builder);
    }

    private static boolean hitUrl(String url) {
        String lower = url.toLowerCase();
        for (String kw : URL_KEYWORDS) {
            if (lower.contains(kw)) {
                return true;
            }
        }
        return false;
    }

    /** query 参数覆写：key=old -> key=new（值 URL 编码）。 */
    private static String rewriteQuery(String url, DeviceProfiles.Profile pf) {
        String result = url;
        String encModel = urlEncode(pf.model);
        for (String key : STRING_KEYS) {
            result = result.replaceAll(
                    "([?&])(" + java.util.regex.Pattern.quote(key) + "=)[^&]*",
                    "$1$2" + java.util.regex.Matcher.quoteReplacement(encModel));
        }
        for (String key : INT_KEYS) {
            Integer v = key.contains("Source") || key.contains("source")
                    ? pf.deviceSource : pf.productId;
            if (v != null) {
                result = result.replaceAll(
                        "([?&])(" + java.util.regex.Pattern.quote(key) + "=)[^&]*",
                        "$1$2" + v);
            }
        }
        return result;
    }

    /** JSON body 字段覆写：兼容 "key":"old" 与 "key":123 两种形态。 */
    private static String rewriteJson(String json, DeviceProfiles.Profile pf) {
        String result = json;
        String quotedModel = java.util.regex.Matcher.quoteReplacement(pf.model);
        for (String key : STRING_KEYS) {
            result = result.replaceAll(
                    "(\"" + java.util.regex.Pattern.quote(key) + "\"\\s*:\\s*\")(?:[^\"\\\\]|\\\\.)*(\"",
                    "$1" + quotedModel + "$2");
        }
        for (String key : INT_KEYS) {
            Integer v = key.contains("Source") || key.contains("source")
                    ? pf.deviceSource : pf.productId;
            if (v != null) {
                result = result.replaceAll(
                        "(\"" + java.util.regex.Pattern.quote(key) + "\"\\s*:\\s*)-?\\d+",
                        "$1" + v);
            }
        }
        return result;
    }

    /** 经 okio.Buffer 读出请求 body 全文（失败返回 null，不影响原请求）。 */
    private static String readBody(Object body) {
        try {
            Class<?> bufferCls = Class.forName("okio.Buffer", true, body.getClass().getClassLoader());
            Object buffer = bufferCls.getDeclaredConstructor().newInstance();
            Class<?> sinkCls = Class.forName("okio.BufferedSink", true, body.getClass().getClassLoader());
            body.getClass().getMethod("writeTo", sinkCls).invoke(body, buffer);
            return (String) bufferCls.getMethod("readUtf8").invoke(buffer);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 以原 contentType 重建 RequestBody（兼容静态 create 与 Kotlin Companion 两种形态）。 */
    private static Object createRequestBody(Object originalBody, String content) {
        try {
            Class<?> bodyCls = Class.forName("okhttp3.RequestBody", true, originalBody.getClass().getClassLoader());
            Object mediaType = originalBody.getClass().getMethod("contentType").invoke(originalBody);
            Class<?> mtCls = Class.forName("okhttp3.MediaType", true, originalBody.getClass().getClassLoader());
            try {
                Method create = bodyCls.getMethod("create", mtCls, String.class);
                return create.invoke(null, mediaType, content);
            } catch (NoSuchMethodException staticMiss) {
                Object companion = bodyCls.getField("Companion").get(null);
                Method create = companion.getClass().getMethod("create", mtCls, String.class);
                return create.invoke(companion, mediaType, content);
            }
        } catch (Throwable t) {
            return null;
        }
    }

    private static String urlEncode(String value) {
        try {
            return URLEncoder.encode(value, "UTF-8");
        } catch (Throwable t) {
            return value;
        }
    }
}
