package com.elish.penbridge;

import android.content.ContentResolver;
import android.provider.Settings;

import java.lang.reflect.Method;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

/**
 * Settings.Global 守护：强制手写笔相关系统开关为开启状态，
 * 并伪造连接状态与设备名。系统设置类跨版本稳定。
 */
final class SettingsHooks {
    static final String KEY_PENCIL_ENABLE = "settings_enable_oppo_pencil";
    static final String KEY_SWIPE_FUNCTION = "ipe_pencil_swipe_function";
    static final String KEY_PENCIL_CONNECT = "ipe_pencil_connect_state";
    static final String KEY_PENCIL_CONNECT_ALT = "PENCIL_CONNECT_STATE";
    static final String KEY_PENCIL_PRESENT = "ipe_pencil_present";
    static final String KEY_PENCIL_MAC = "ipe_pencil_mac_addr";
    static final String KEY_PENCIL_BT_NAME = "ipe_pencil_bt_device_name";
    static final String KEY_STYLUS_EVER_USED = "stylus_ever_used";
    static final String KEY_SHOW_POINTER = "show_stylus_pointer";

    static final String FAKE_PENCIL_NAME = "OPPO Pen2";
    static final String DISPLAY_PENCIL_NAME = "Xiaomi Smart Pen 2";

    private SettingsHooks() {
    }

    static void install(XposedModule module, ClassLoader classLoader) {
        Class<?> global;
        try {
            global = Class.forName("android.provider.Settings$Global", true, classLoader);
        } catch (Throwable throwable) {
            Util.warn(module, "Settings.Global not found", throwable);
            return;
        }

        try {
            Method putInt = global.getDeclaredMethod("putInt", ContentResolver.class, String.class, int.class);
            Util.hook(module, putInt, "settings_global_put_int", chain -> {
                String key = (String) chain.getArg(1);
                int value = (Integer) chain.getArg(2);
                if (KEY_PENCIL_ENABLE.equals(key)
                        || KEY_PENCIL_PRESENT.equals(key) || KEY_STYLUS_EVER_USED.equals(key)
                        || KEY_SHOW_POINTER.equals(key)) {
                    if (value != 1) {
                        Util.e(module, "Settings.Global.putInt forced " + key + ": " + value + " -> 1");
                        return chain.proceed(new Object[]{chain.getArg(0), key, 1});
                    }
                }
                if ((KEY_PENCIL_CONNECT.equals(key) || KEY_PENCIL_CONNECT_ALT.equals(key)) && value != 2) {
                    Util.e(module, "Settings.Global.putInt forced " + key + ": " + value + " -> 2");
                    return chain.proceed(new Object[]{chain.getArg(0), key, 2});
                }
                return chain.proceed();
            });
        } catch (Throwable throwable) {
            Util.warn(module, "hook Settings.Global.putInt failed", throwable);
        }

        try {
            Method getInt = global.getDeclaredMethod("getInt", ContentResolver.class, String.class, int.class);
            Util.hook(module, getInt, "settings_global_get_int", chain -> {
                String key = (String) chain.getArg(1);
                if (KEY_PENCIL_ENABLE.equals(key)
                        || KEY_PENCIL_PRESENT.equals(key) || KEY_STYLUS_EVER_USED.equals(key)
                        || KEY_SHOW_POINTER.equals(key)) {
                    return 1;
                }
                if (KEY_PENCIL_CONNECT.equals(key) || KEY_PENCIL_CONNECT_ALT.equals(key)) {
                    return 2;
                }
                return chain.proceed();
            });
        } catch (Throwable throwable) {
            Util.warn(module, "hook Settings.Global.getInt failed", throwable);
        }

        try {
            Method putString = global.getDeclaredMethod("putString", ContentResolver.class, String.class, String.class);
            Util.hook(module, putString, "settings_global_put_string", chain -> {
                String key = (String) chain.getArg(1);
                String value = (String) chain.getArg(2);
                if (KEY_PENCIL_BT_NAME.equals(key) && (value == null || value.isEmpty())) {
                    return chain.proceed(new Object[]{chain.getArg(0), key, FAKE_PENCIL_NAME});
                }
                return chain.proceed();
            });
        } catch (Throwable throwable) {
            Util.warn(module, "hook Settings.Global.putString failed", throwable);
        }
        Util.e(module, "Settings.Global hooks ready (pencil enable/swipe/connect)");
    }

    static void apply(android.content.Context context, String mac) {
        if (context == null) {
            return;
        }
        try {
            ContentResolver resolver = context.getContentResolver();
            Settings.Global.putInt(resolver, KEY_PENCIL_ENABLE, 1);
            Settings.Global.putInt(resolver, KEY_PENCIL_PRESENT, 1);
            Settings.Global.putInt(resolver, KEY_STYLUS_EVER_USED, 1);
            Settings.Global.putInt(resolver, KEY_SHOW_POINTER, 1);
            Settings.Global.putInt(resolver, KEY_PENCIL_CONNECT, 2);
            Settings.Global.putInt(resolver, KEY_PENCIL_CONNECT_ALT, 2);
            Settings.Global.putString(resolver, KEY_PENCIL_BT_NAME, FAKE_PENCIL_NAME);
            if (mac != null && !mac.isEmpty()) {
                Settings.Global.putString(resolver, KEY_PENCIL_MAC, mac);
            }
        } catch (Throwable ignored) {
        }
    }
}
