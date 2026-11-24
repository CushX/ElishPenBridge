package com.elish.penbridge;

import android.content.Context;
import android.content.Intent;
import android.provider.Settings;
import android.view.KeyEvent;

import java.lang.reflect.Method;

import io.github.libxposed.api.XposedModule;

/**
 * system_server 层 hook。
 *
 * 核心功能：拦截 Xiaomi Smart Pen 键盘设备的 PAGEUP/PAGEDOWN 按键，
 * 转成 ColorOS 手写笔的单/双击广播。因为这一层在系统进程，
 * 与智能外设 app 的版本无关，所以跨 ColorOS 版本通用。
 *
 * 说明：C15/C16 只有 PENCIL_DOUBLE_CLICK，没有单击广播；
 * 模块通过 elish_ipe_version 区分版本并调整策略。
 */
final class SystemHooks {
    private static final int ACTION_PASS_TO_USER = 0x1;
    private static final int KEYCODE_PAGE_UP = 92;
    private static final int KEYCODE_PAGE_DOWN = 93;
    private static final int KEYCODE_STYLUS_BUTTON_PRIMARY = 308;
    private static final int KEYCODE_STYLUS_BUTTON_SECONDARY = 309;
    private static final String KEY_PRIMARY_CODE = "elish_pen_primary_keycode";
    private static final String KEY_SECONDARY_CODE = "elish_pen_secondary_keycode";
    private static final int SCAN_PAGE_UP = 0x4b;
    private static final int SCAN_PAGE_DOWN = 0x4e;

    private SystemHooks() {
    }

    static void install(XposedModule module, ClassLoader classLoader) {
        SettingsHooks.install(module, classLoader);
        hookKeyInterception(module, classLoader);
        Util.e(module, "system_server Settings+input hooks installed");
    }

    private static void hookKeyInterception(XposedModule module, ClassLoader classLoader) {
        String[] candidates = {
                "com.android.server.wm.PhoneWindowManager",
                "com.android.server.policy.PhoneWindowManager",
                "com.android.server.policy.OplusPhoneWindowManager"
        };
        boolean installed = false;
        for (String className : candidates) {
            Method method = Util.findMethod(classLoader, className, "interceptKeyBeforeQueueing",
                    KeyEvent.class, int.class);
            if (method == null) {
                continue;
            }
            Util.hook(module, method, "intercept_pen_key_" + className, chain -> {
                KeyEvent event = (KeyEvent) chain.getArg(0);
                Object raw = chain.proceed();
                Integer result = raw instanceof Integer ? (Integer) raw : 0;
                if (!isPenKey(event)) {
                    return result;
                }
                int action = event.getAction();
                int keyCode = event.getKeyCode();
                Util.log(module, "PEN KEY detected: keyCode=" + keyCode
                        + ", scanCode=0x" + Integer.toHexString(event.getScanCode())
                        + ", action=" + action + ", repeat=" + event.getRepeatCount());
                int version = ipeVersion();
                if (action == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
                    if (version >= 17) {
                        if (isPrimary(event)) {
                            sendClick(module, "com.oplus.ipemanager.action.PENCIL_SINGLE_CLICK",
                                    "ipe_pencil_single_click", 4);
                        } else {
                            sendClick(module, "com.oplus.ipemanager.action.PENCIL_DOUBLE_CLICK",
                                    "ipe_pencil_double_click", 1);
                        }
                    } else {
                        // C15/C16 只有双击，两个键都映射到 PENCIL_DOUBLE_CLICK，用不同 mode 区分
                        int mode = isPrimary(event) ? 4 : 1;
                        sendClickMode(module, "com.oplus.ipemanager.action.PENCIL_DOUBLE_CLICK", mode);
                    }
                    if (version >= 17) {
                        sendButtonState(module, "down");
                    }
                } else if (action == KeyEvent.ACTION_UP && version >= 17) {
                    sendButtonState(module, "up");
                }
                return result & ~ACTION_PASS_TO_USER;
            });
            installed = true;
            Util.e(module, "hooked " + className + ".interceptKeyBeforeQueueing");
        }
        if (!installed) {
            Util.e(module, "PhoneWindowManager NOT FOUND - pen button bridge INACTIVE");
        }
    }

    private static int ipeVersion() {
        Context context = currentContext();
        if (context == null) {
            return 17;
        }
        try {
            return Settings.Global.getInt(context.getContentResolver(),
                    IpeHooks.KEY_IPE_VERSION, 17);
        } catch (Throwable throwable) {
            return 17;
        }
    }

    private static void sendClickMode(XposedModule module, String action, int mode) {
        Context context = currentContext();
        if (context == null) {
            return;
        }
        try {
            Intent intent = new Intent(action);
            intent.putExtra("action", mode);
            intent.addFlags(Intent.FLAG_RECEIVER_FOREGROUND);
            if (mode == 4) {
                intent.setPackage("com.oplus.healthservice");
            }
            context.sendBroadcast(intent, "com.oplus.ipemanager.permission.receiver.DOUBLE_CLICK");
            Util.log(module, "sent broadcast " + action + " mode=" + mode + " package=" + intent.getPackage());
        } catch (Throwable throwable) {
            Util.warn(module, "send " + action + " failed", throwable);
        }
    }

    private static boolean isPenKey(KeyEvent event) {
        if (event == null) {
            return false;
        }
        String deviceName = "";
        try {
            android.view.InputDevice device = event.getDevice();
            if (device != null) {
                deviceName = device.getName();
            }
        } catch (Throwable ignored) {
        }
        if (deviceName == null || !Util.isXiaomiPenName(deviceName)) {
            return false;
        }
        int keyCode = event.getKeyCode();
        int scanCode = event.getScanCode();
        boolean scanMatch = scanCode == SCAN_PAGE_UP || scanCode == SCAN_PAGE_DOWN
                || scanCode == (SCAN_PAGE_UP | 0x70000) || scanCode == (SCAN_PAGE_DOWN | 0x70000);
        boolean codeMatch = keyCode == KEYCODE_PAGE_UP || keyCode == KEYCODE_PAGE_DOWN
                || keyCode == KEYCODE_STYLUS_BUTTON_PRIMARY || keyCode == KEYCODE_STYLUS_BUTTON_SECONDARY
                || keyCode == settingInt(KEY_PRIMARY_CODE, KEYCODE_PAGE_UP)
                || keyCode == settingInt(KEY_SECONDARY_CODE, KEYCODE_PAGE_DOWN);
        return scanMatch || codeMatch;
    }

    private static boolean isPrimary(KeyEvent event) {
        int keyCode = event.getKeyCode();
        int scanCode = event.getScanCode();
        if (scanCode == SCAN_PAGE_UP || scanCode == (SCAN_PAGE_UP | 0x70000)) {
            return false;
        }
        if (scanCode == SCAN_PAGE_DOWN || scanCode == (SCAN_PAGE_DOWN | 0x70000)) {
            return true;
        }
        return keyCode == KEYCODE_STYLUS_BUTTON_SECONDARY
                || keyCode == settingInt(KEY_SECONDARY_CODE, KEYCODE_PAGE_DOWN);
    }

    private static int settingInt(String key, int fallback) {
        Context context = currentContext();
        if (context == null) {
            return fallback;
        }
        try {
            return Settings.Global.getInt(context.getContentResolver(), key, fallback);
        } catch (Throwable throwable) {
            return fallback;
        }
    }

    private static Context currentContext() {
        try {
            Class<?> thread = Class.forName("android.app.ActivityThread");
            Method method = thread.getDeclaredMethod("currentApplication");
            method.setAccessible(true);
            return (Context) method.invoke(null);
        } catch (Throwable throwable) {
            return null;
        }
    }

    private static void sendClick(XposedModule module, String action, String settingKey, int fallback) {
        Context context = currentContext();
        if (context == null) {
            return;
        }
        try {
            int mode = Settings.Global.getInt(context.getContentResolver(), settingKey, fallback);
            Intent intent = new Intent(action);
            intent.putExtra("action", mode);
            intent.addFlags(Intent.FLAG_RECEIVER_FOREGROUND);
            if (mode == 4) {
                intent.setPackage("com.oplus.healthservice");
            }
            context.sendBroadcast(intent, "com.oplus.ipemanager.permission.receiver.DOUBLE_CLICK");
            Util.log(module, "sent broadcast " + action + " mode=" + mode + " package=" + intent.getPackage());
        } catch (Throwable throwable) {
            Util.warn(module, "send " + action + " failed", throwable);
        }
    }

    private static void sendButtonState(XposedModule module, String status) {
        Context context = currentContext();
        if (context == null) {
            return;
        }
        try {
            Intent intent = new Intent("com.oplus.ipemanager.action.STYLUS_BUTTON_STATE_CHANGED");
            intent.putExtra("stylus_button_status", status);
            intent.setPackage("com.oplus.healthservice");
            context.sendBroadcast(intent, "com.oplus.ipemanager.permission.receiver.DOUBLE_CLICK");
            Util.log(module, "sent STYLUS_BUTTON_STATE_CHANGED status=" + status);
        } catch (Throwable throwable) {
            Util.warn(module, "send stylus button state failed", throwable);
        }
    }
}
