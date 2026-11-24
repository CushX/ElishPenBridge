package com.elish.penbridge;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

/**
 * 模块入口：基于 libxposed API 102。
 *
 * 作用于两个进程：
 * 1. system_server —— 注入按键桥接（把手写笔的两个实体按键转成 ColorOS 手势广播）
 * 2. com.oplus.ipemanager —— 智能外设 app（伪造笔的连接状态、电量、设备类型，
 *    让设置页正常点亮并隐藏无硬件支持的功能项）
 */
public class Main extends XposedModule {
    private String processName = "";

    /** 模块被某个进程加载时调用，记录进程名便于排查。 */
    @Override
    public void onModuleLoaded(XposedModuleInterface.ModuleLoadedParam param) {
        processName = param.getProcessName();
        Util.e(this, "module loaded in process=" + processName + ", systemServer=" + param.isSystemServer());
    }

    /** system_server 启动阶段：安装 Settings 守护与手写笔按键拦截。 */
    @Override
    public void onSystemServerStarting(XposedModuleInterface.SystemServerStartingParam param) {
        try {
            SystemHooks.install(this, param.getClassLoader());
            Util.e(this, "system_server hooks installed OK");
        } catch (Throwable throwable) {
            Util.warn(this, "system install failed", throwable);
        }
    }

    /** 目标 app 就绪时安装 IPeManager 相关 hook（仅在 com.oplus.ipemanager 生效）。 */
    @Override
    public void onPackageReady(XposedModuleInterface.PackageReadyParam param) {
        String packageName = param.getPackageName();
        if ("com.oplus.ipemanager".equals(packageName)) {
            try {
                IpeHooks.install(this, param.getClassLoader());
                Util.e(this, "IPeManager hooks installed OK in process " + processName);
            } catch (Throwable throwable) {
                Util.warn(this, "IPeManager install failed", throwable);
            }
        }
    }
}
