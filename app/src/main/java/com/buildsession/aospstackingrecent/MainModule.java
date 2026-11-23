package com.buildsession.aospstackingrecent;

import android.util.Log;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Xposed 模块的入口类，负责识别目标 Launcher 进程并分发 Hook 逻辑。
 */
public class MainModule implements IXposedHookLoadPackage {

    static {
        XposedBridge.log("AOSPStackingRecent: MainModule class loaded in static block");
    }

    private static final String PIXEL_LAUNCHER_PKG = "com.google.android.apps.nexuslauncher";
    private static final String AOSP_LAUNCHER_PKG = "com.android.launcher3";
    // Lawnchair (github: app.lawnchair, market: ch.deletescape.lawnchair.plah, plus ".debug" variants).
    // Lawnchair is a Launcher3/Quickstep fork that keeps the com.android.quickstep.* class names,
    // so with QuickSwitch its own process draws Recents and the same hooks apply.
    private static final String LAWNCHAIR_PKG_PREFIX = "app.lawnchair";
    private static final String LAWNCHAIR_MARKET_PKG_PREFIX = "ch.deletescape.lawnchair";

    private static boolean isTargetPackage(String pkgName) {
        return pkgName.equals(PIXEL_LAUNCHER_PKG)
                || pkgName.equals(AOSP_LAUNCHER_PKG)
                || pkgName.startsWith(LAWNCHAIR_PKG_PREFIX)
                || pkgName.startsWith(LAWNCHAIR_MARKET_PKG_PREFIX);
    }

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        String pkgName = lpparam.packageName;
        String processName = lpparam.processName;

        // 我们只关注 Pixel Launcher 或 AOSP Launcher3
        if (isTargetPackage(pkgName)) {
            // 进一步过滤进程：通常 Recents 逻辑运行在主进程或 :quickstep 进程中
            boolean isMainProcess = processName.equals(pkgName);
            boolean isQuickstepProcess = processName.endsWith(":quickstep");

            if (isMainProcess || isQuickstepProcess) {
                XposedBridge.log("AOSPStackingRecent: Target launcher detected: " + pkgName + " (Process: " + processName + ")");
                new RecentsHook(lpparam.classLoader).initHooks();
                new NavBarHook(lpparam.classLoader).initHooks();
                new RecentsBlurHook(lpparam.classLoader).initHooks();
            } else {
                // 可选：记录忽略的进程，方便调试
                XposedBridge.log("AOSPStackingRecent: Ignoring process " + processName + " for package " + pkgName);
            }
        }
    }
}
