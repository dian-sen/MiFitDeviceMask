package io.github.mifitmask.hooks;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.lang.ref.WeakReference;

import io.github.mifitmask.MaskModule;
import io.github.mifitmask.Prefs;

/**
 * v1.3.1 本地表盘文件直装：绕过商店市场，把任意来源的表盘文件直接推给手环。
 *
 * 约束与实证：
 * - 商店市场由服务端账号绑定决定，参数伪装不可行（v1.2 系列闭环验证）；
 * - 安装通道与市场无关，表盘文件可来自社区（BandBBS 等）；
 * - FaceInstallBleImpl 为抽象类（需设备模型构造）→ 实例来源 = 捕获 App 自身安装时的
 *   thisObject；商店安装走 preInstall 路径（v1.3.0 实证仅 hook doInstall 零命中），
 *   故 doInstall 与 preInstall 都 hook；
 * - FaceInstallPushCallback 为纯接口 → Proxy 动态代理；
 * - 跨进程指令/结果全部经 FileServeProvider（content://io.github.mifitmask.files），
 *   不依赖 RemotePreferences 的跨进程值同步（多进程快照抖动会误触发/丢结果，v1.3.0 实证）；
 * - 仅 :device 进程执行安装（蓝牙通道所在；其他进程仅捕获，避免结果互相覆盖）。
 */
public final class LocalInstallHooks {

    private static final String BLE_IMPL = "com.xiaomi.fitness.watch.face.install.FaceInstallBleImpl";
    private static final String HUAMI_IMPL = "com.xiaomi.fitness.watch.face.install.FaceInstallHuamiImpl";
    private static final String CALLBACK = "com.xiaomi.fitness.watch.face.install.FaceInstallPushCallback";
    private static final String AUTHORITY = "content://io.github.mifitmask.files";

    private static volatile WeakReference<Object> sImplRef;
    private static volatile boolean sExecuting;
    private static int sLastToken = -1;

    private static Handler sHandler;

    private LocalInstallHooks() {
    }

    public static void install(MaskModule mod, ClassLoader cl, String processName) {
        boolean executor = processName.contains(":device");
        try {
            Class<?> ble = Class.forName(BLE_IMPL, false, cl);
            Class<?> huami = Class.forName(HUAMI_IMPL, false, cl);
            Class<?> cb = Class.forName(CALLBACK, false, cl);
            for (Class<?> impl : new Class<?>[]{ble, huami}) {
                for (Method m : impl.getDeclaredMethods()) {
                    String name = m.getName();
                    boolean isDo = "doInstall".equals(name) && m.getParameterTypes().length == 4;
                    boolean isPre = "preInstall".equals(name) && m.getParameterTypes().length == 9;
                    if (!isDo && !isPre) {
                        continue;
                    }
                    m.setAccessible(true);
                    mod.hook(m).intercept(chain -> {
                        Object self = chain.getThisObject();
                        sImplRef = new WeakReference<>(self);
                        StringBuilder sb = new StringBuilder("INSTALL-CAPTURE impl=")
                                .append(impl.getSimpleName()).append('.').append(name).append('(');
                        Object[] args = chain.getArgs().toArray(new Object[0]);
                        for (int i = 0; i < args.length; i++) {
                            Object a = args[i];
                            if (a != null && a.getClass().getName().contains("Function")) {
                                continue;
                            }
                            sb.append(i == 0 ? "" : ", ").append(a);
                        }
                        sb.append(')');
                        mod.log(Log.INFO, MaskModule.TAG, sb.toString());
                        return chain.proceed();
                    });
                }
            }
            mod.log(Log.INFO, MaskModule.TAG, "install capture hooks ready (executor=" + executor + ")");
            if (executor) {
                sHandler = new Handler(Looper.getMainLooper());
                startPolling(mod, cl, cb);
            }
        } catch (Throwable t) {
            mod.log(Log.ERROR, MaskModule.TAG, "local install init failed", t);
        }
    }

    /** 每 3 秒经 Provider 查询安装指令（token 单调判定，防多进程快照抖动误触发）。 */
    private static void startPolling(MaskModule mod, ClassLoader cl, Class<?> cb) {
        final int[] lastToken = {-1};
        Runnable task = new Runnable() {
            @Override
            public void run() {
                try {
                    String[] cmd = readCommand(mod);
                    if (cmd != null) {
                        int now = parseToken(cmd[0]);
                        if (now > lastToken[0] && !sExecuting) {
                            lastToken[0] = now;
                            sExecuting = true;
                            try {
                                executeInstall(mod, cl, cb, cmd[1], cmd[2]);
                            } finally {
                                sExecuting = false;
                            }
                        }
                    }
                } catch (Throwable t) {
                    mod.log(Log.WARN, MaskModule.TAG, "poll error", t);
                }
                sHandler.postDelayed(this, 3000);
            }
        };
        sHandler.postDelayed(task, 3000);
    }

    /**
     * 经 Provider query 指令：content://io.github.mifitmask.files/command
     * 返回单行单列 = "token|faceId|fileName"；无指令返回 null。
     */
    private static String[] readCommand(MaskModule mod) {
        try {
            android.database.Cursor c = targetContext().getContentResolver()
                    .query(Uri.parse(AUTHORITY + "/command"), null, null, null, null);
            if (c == null) {
                return null;
            }
            try {
                if (c.moveToFirst()) {
                    String v = c.getString(0);
                    if (v != null && !v.isEmpty()) {
                        String[] parts = v.split("\\|");
                        if (parts.length >= 3) {
                            return parts;
                        }
                    }
                }
            } finally {
                c.close();
            }
        } catch (Throwable t) {
            mod.logd("readCommand failed: " + t.getClass().getSimpleName());
        }
        return null;
    }

    private static void executeInstall(MaskModule mod, ClassLoader cl, Class<?> cb,
                                       String faceId, String fileName) {
        Object impl = sImplRef != null ? sImplRef.get() : null;
        if (impl == null) {
            writeResult(mod, "失败：尚未捕获安装器实例，请先在商店正常安装任意一个表盘后重试");
            return;
        }
        Context ctx = targetContext();
        if (ctx == null) {
            writeResult(mod, "失败：无法获取目标进程 Context");
            return;
        }
        try {
            InputStream in = ctx.getContentResolver()
                    .openInputStream(Uri.parse(AUTHORITY + "/" + fileName));
            if (in == null) {
                writeResult(mod, "失败：中继文件不可读（重新在设置页发起）");
                return;
            }
            File dir = ctx.getExternalFilesDir("WatchFace");
            if (dir == null) {
                dir = ctx.getFilesDir();
            }
            if (!dir.exists()) {
                dir.mkdirs();
            }
            File target = new File(dir, "relay_face.bin");
            OutputStream out = new java.io.FileOutputStream(target);
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            out.close();
            in.close();
            if (faceId.isEmpty()) {
                faceId = "relay" + (target.length() % 100000);
            }
            Object callback = Proxy.newProxyInstance(cl, new Class<?>[]{cb},
                    (InvocationHandler) (proxy, method, args) -> {
                        switch (method.getName()) {
                            case "onStart":
                                writeResult(mod, "进行中：已开始推送表盘到手环…");
                                return null;
                            case "onProgress":
                                return null;
                            case "onFinish":
                                boolean ok = args != null && args.length > 0
                                        && Boolean.TRUE.equals(args[0]);
                                writeResult(mod, ok ? "成功：表盘已推送（手环端确认）"
                                        : "失败：手环端拒绝（code=" + (args != null && args.length > 1 ? args[1] : "?")
                                        + "；确认表盘与手环兼容）");
                                return null;
                            default:
                                return null;
                        }
                    });
            Method dm = impl.getClass().getDeclaredMethod("doInstall",
                    String.class, String.class, Integer.class, cb);
            dm.setAccessible(true);
            writeResult(mod, "进行中：开始安装（faceId=" + faceId + "）");
            dm.invoke(impl, target.getAbsolutePath(), faceId, Integer.valueOf(0), callback);
            mod.log(Log.INFO, MaskModule.TAG, "local install dispatched: " + target.getAbsolutePath()
                    + " id=" + faceId);
        } catch (Throwable t) {
            mod.log(Log.ERROR, MaskModule.TAG, "local install failed", t);
            writeResult(mod, "失败：" + t.getClass().getSimpleName()
                    + (t.getMessage() != null ? " " + t.getMessage() : ""));
        }
    }

    private static int parseToken(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Throwable t) {
            return -1;
        }
    }

    private static Context targetContext() {
        try {
            return (Context) Class.forName("android.app.ActivityThread")
                    .getMethod("currentApplication").invoke(null);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 结果写回：经 Provider insert（设置页同进程直读），prefs 作备份。 */
    private static void writeResult(MaskModule mod, String s) {
        mod.log(Log.INFO, MaskModule.TAG, "INSTALL-RESULT " + s);
        try {
            android.content.ContentValues cv = new android.content.ContentValues();
            cv.put("result", s);
            targetContext().getContentResolver()
                    .insert(Uri.parse(AUTHORITY + "/result"), cv);
        } catch (Throwable t) {
            mod.logd("writeResult provider failed: " + t.getClass().getSimpleName());
        }
        if (mod.prefs() != null) {
            mod.prefs().edit().putString(Prefs.KEY_INSTALL_RESULT, s).apply();
        }
    }
}
