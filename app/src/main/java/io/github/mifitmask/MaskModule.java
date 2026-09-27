package io.github.mifitmask;

import android.content.SharedPreferences;
import android.util.Log;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam;
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam;
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam;
import io.github.mifitmask.hooks.DeviceIdentityHooks;
import io.github.mifitmask.hooks.FaceRequestHooks;
import io.github.mifitmask.hooks.LayerProductHooks;
import io.github.mifitmask.hooks.LocalInstallHooks;
import io.github.mifitmask.hooks.ProbeHooks;
import io.github.mifitmask.hooks.StoreParamHooks;

/**
 * MiFit 机型伪装 - libxposed API 102 入口。
 *
 * 生命周期：
 * - onModuleLoaded: 模块加载进目标进程（每进程一次），此处取得 RemotePreferences（只读视图）
 * - onPackageLoaded: 非目标包直接 detach()，停止后续回调
 * - onPackageReady: 目标包 classloader 就绪，安装各层 hook（单个 hook 失败不连累其他）
 *
 * 伪装分层（详见 docs/ROADMAP.md）：
 * - 第 1 层 本地设备档案（默认开）：设备名/model getter 多候选覆写
 * - 第 2 层 表盘商店请求参数重写（实验，默认关）：OkHttp 拦截器改写请求参数
 * - 探测日志：调试开关开时输出候选类/方法/取值，供真机验证后固化 hook 点
 */
public class MaskModule extends XposedModule {

    public static final String TAG = "MifitMask";

    /** 小米运动健康：国内包名 */
    public static final String PKG_CN = "com.mi.health";
    /** 小米运动健康：国际包名 */
    public static final String PKG_GLOBAL = "com.xiaomi.wearable";

    private SharedPreferences mPrefs;
    private volatile String mProfileKey = "";
    private volatile DeviceProfiles.Profile mProfile = DeviceProfiles.EMPTY;

    public static boolean isTargetPackage(String pkg) {
        return PKG_CN.equals(pkg) || PKG_GLOBAL.equals(pkg);
    }

    /** 真实进程名（onModuleLoaded 时记录；ApplicationInfo.processName 多进程下不可靠） */
    private static volatile String sProcessName = "";

    public static String processName() {
        return sProcessName;
    }

    @Override
    public void onModuleLoaded(ModuleLoadedParam param) {
        sProcessName = param.getProcessName();
        mPrefs = getRemotePreferences(Prefs.GROUP);
        log(Log.INFO, TAG, "module loaded: process=" + param.getProcessName()
                + ", api=" + getApiVersion()
                + ", framework=" + getFrameworkName() + " " + getFrameworkVersion());
    }

    @Override
    public void onPackageLoaded(PackageLoadedParam param) {
        if (!isTargetPackage(param.getPackageName())) {
            detach();
        }
    }

    @Override
    public void onPackageReady(PackageReadyParam param) {
        if (!isTargetPackage(param.getPackageName())) {
            return;
        }
        log(Log.INFO, TAG, "target ready: " + param.getPackageName());
        tryHook("product identity (v1.1 main)", () ->
                LayerProductHooks.install(this, param.getClassLoader()));
        tryHook("face request params (v1.2 main)", () ->
                FaceRequestHooks.install(this, param.getClassLoader()));
        tryHook("local install (v1.3)", () ->
                LocalInstallHooks.install(this, param.getClassLoader(), processName()));
        tryHook("device identity (layer 1)", () ->
                DeviceIdentityHooks.install(this, param.getClassLoader()));
        tryHook("store param rewrite (layer 2)", () ->
                StoreParamHooks.install(this, param.getClassLoader()));
        tryHook("probe logger", () ->
                ProbeHooks.install(this, param.getClassLoader()));
    }

    /** 安装期统一包装：单个 hook 失败只记日志，不连累其他 hook。 */
    public interface HookInstall {
        void install() throws Throwable;
    }

    public void tryHook(String what, HookInstall install) {
        try {
            install.install();
            log(Log.INFO, TAG, "hooked: " + what);
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "hook failed: " + what, t);
        }
    }

    public SharedPreferences prefs() {
        return mPrefs;
    }

    /** 总开关（默认开）。 */
    public boolean enabled() {
        return Prefs.isEnabled(mPrefs, Prefs.KEY_ENABLE_ALL);
    }

    /** 第 2 层网络参数重写（实验，默认关；加密请求下已证不可行，保留开关兼容）。 */
    public boolean netRewriteEnabled() {
        return enabled() && Prefs.isEnabled(mPrefs, Prefs.KEY_ENABLE_NET_REWRITE, false);
    }

    /** 表盘请求参数改写（应用层明文参数，v1.2 主功能，默认开；不触本地身份与连接）。 */
    public boolean rewriteFaceRequest() {
        return enabled() && Prefs.isEnabled(mPrefs, Prefs.KEY_REWRITE_FACE_REQUEST, true);
    }

    /** 危险开关：蓝牙名改写（默认关，v1.1.4——曾导致添加/连接失败）。 */
    public boolean rewriteBtName() {
        return enabled() && Prefs.isEnabled(mPrefs, Prefs.KEY_REWRITE_BT_NAME, false);
    }

    /**
     * 危险开关：本地机型改写总闸（Product 目录 + Device 标识，v1.1.7 起默认关）。
     * 目录条目与连接/设备页强耦合，改写值会被 App 写入本地库造成持久污染（v1.1.6 真机实证）。
     */
    public boolean rewriteLocalIdentity() {
        return enabled() && Prefs.isEnabled(mPrefs, Prefs.KEY_REWRITE_CONNECTED, false);
    }

    /** 调试日志（默认开，测试阶段便于回流探测信息；稳定后建议关闭）。 */
    public boolean debugLog() {
        return Prefs.isEnabled(mPrefs, Prefs.KEY_DEBUG_LOG, true);
    }

    public void logd(String msg) {
        if (debugLog()) {
            log(Log.INFO, TAG, msg);
        }
    }

    /** 当前生效机型（设置变更即时生效，带缓存）。 */
    public DeviceProfiles.Profile profile() {
        String key = Prefs.profileKey(mPrefs);
        if (!key.equals(mProfileKey)) {
            mProfile = DeviceProfiles.resolve(mPrefs);
            mProfileKey = key;
        }
        return mProfile;
    }
}
