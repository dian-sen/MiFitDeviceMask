package io.github.mifitmask;

import android.app.Application;

import java.util.concurrent.CopyOnWriteArraySet;

import io.github.libxposed.service.XposedService;
import io.github.libxposed.service.XposedServiceHelper;

/**
 * 模块 App：连接 LSPosed 框架服务，供设置页读写 RemotePreferences。
 * XposedServiceHelper 每进程注册一次；Activity 通过 Listener 接收绑定结果。
 */
public class MaskApp extends Application implements XposedServiceHelper.OnServiceListener {

    /** 设置页关心的服务状态回调。 */
    public interface Listener {
        void onServiceReady(XposedService service);

        void onServiceLost();
    }

    private final CopyOnWriteArraySet<Listener> mListeners = new CopyOnWriteArraySet<>();

    private volatile XposedService mService;

    @Override
    public void onCreate() {
        super.onCreate();
        XposedServiceHelper.registerListener(this);
    }

    public void addListener(Listener l, boolean notifyIfReady) {
        mListeners.add(l);
        XposedService s = mService;
        if (notifyIfReady && s != null) {
            l.onServiceReady(s);
        }
    }

    public void removeListener(Listener l) {
        mListeners.remove(l);
    }

    @Override
    public void onServiceBind(XposedService service) {
        mService = service;
        for (Listener l : mListeners) {
            l.onServiceReady(service);
        }
    }

    @Override
    public void onServiceDied(XposedService service) {
        mService = null;
        for (Listener l : mListeners) {
            l.onServiceLost();
        }
    }
}
