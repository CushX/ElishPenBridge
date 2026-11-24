package com.elish.penbridge;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Set;

import io.github.libxposed.api.XposedModule;

/**
 * 智能外设 app（com.oplus.ipemanager）层 hook。
 *
 * 功能：
 * - 伪造笔的连接状态、电量、MAC、设备类型与二代笔身份
 * - 设置页每次 onResume 注入“已连接/电量”，防止自动退出
 * - 隐藏无硬件支持的功能入口（激光、长捏、息屏速记等）
 * - 设备信息页填入型号、序列号等内容
 *
 * 兼容：C15/C16/C17 三个版本的混淆类名不同，通过候选类名数组逐个尝试 hook。
 * 版本检测写入 elish_ipe_version，供 system_server 选择按键广播策略。
 */
final class IpeHooks {
    private static final String IPACKAGE = "com.oplus.ipemanager";
    private static final String DEVICE_TYPE_CLASS = "com.oplus.ipemanager.btadsorb.data.IpeDeviceType";
    private static final String PENCIL_TYPE_CLASS = "com.oplus.ipemanager.btadsorb.data.PencilType";

    private IpeHooks() {
    }

    static final String KEY_IPE_VERSION = "elish_ipe_version";
    private static volatile int ipeVersion = 17;

    static void install(XposedModule module, ClassLoader classLoader) {
        SettingsHooks.install(module, classLoader);
        detectAndPublishVersion(module, classLoader);
        hookLaserFeature(module);
        hookIpeSettingsWriter(module, classLoader);
        hookNameMapping(module, classLoader);
        hookBatteryPreference(module, classLoader);
        hookDeviceType(module, classLoader);
        hookStatusGetters(module, classLoader);
        hookLifecycle(module, classLoader);
        hookSettingActivity(module, classLoader);
        hookPencilInfo(module, classLoader);
        hideUnsupportedPreferences(module, classLoader);
        Util.e(module, "IPeManager: all hooks installed (settings/device/type/status/activity)");
    }

    // 发布当前智能外设版本给 system_server，用于选择按键广播策略
    private static void detectAndPublishVersion(XposedModule module, ClassLoader classLoader) {
        int version = 17;
        try {
            Class<?> build = Class.forName("com.oplus.os.Build", false, classLoader);
            // 优先用类名特征判断：C15 有 PencilBleManager，C16 是 ble.c0/d0，C17 是 m3.b
            if (Util.findClass(classLoader, "m3.b") != null) {
                version = 17;
            } else if (Util.findClass(classLoader, "com.oplus.ipemanager.btadsorb.ble.PencilBleManager") != null) {
                version = 15;
            } else if (Util.findClass(classLoader, "d2.b") != null) {
                version = 16;
            }
        } catch (Throwable throwable) {
        }
        ipeVersion = version;
        try {
            android.content.Context ctx = null;
            Class<?> th = Class.forName("android.app.ActivityThread");
            Method m = th.getDeclaredMethod("currentApplication");
            m.setAccessible(true);
            Object app = m.invoke(null);
            if (app instanceof android.content.Context) {
                ctx = (android.content.Context) app;
            }
            if (ctx != null) {
                Settings.Global.putInt(ctx.getContentResolver(), KEY_IPE_VERSION, version);
            }
        } catch (Throwable ignored) {
        }
        Util.e(module, "detected IPeManager version=" + version);
    }

    private static Object enumValue(ClassLoader classLoader, String className, String constant) {
        try {
            Class<?> clazz = Class.forName(className, false, classLoader);
            return Enum.valueOf((Class<? extends Enum>) clazz, constant);
        } catch (Throwable throwable) {
            return null;
        }
    }

    private static String detectMac(Context context) {
        String stored = null;
        try {
            stored = Settings.Global.getString(context.getContentResolver(), SettingsHooks.KEY_PENCIL_MAC);
        } catch (Throwable ignored) {
        }
        if (stored != null && stored.matches("(?i)^([0-9a-f]{2}:){5}[0-9a-f]{2}$")) {
            return stored;
        }
        try {
            BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
            if (adapter != null) {
                Set<BluetoothDevice> bonded = adapter.getBondedDevices();
                if (bonded != null) {
                    for (BluetoothDevice device : bonded) {
                        String name = device.getName();
                        if (Util.isXiaomiPenName(name)) {
                            return device.getAddress();
                        }
                    }
                    for (BluetoothDevice device : bonded) {
                        String name = device.getName();
                        if (name != null && (name.toLowerCase().contains("pen") || name.toLowerCase().contains("stylus"))) {
                            return device.getAddress();
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        if (stored != null && !stored.isEmpty()) {
            return stored;
        }
        return "AA:BB:CC:DD:EE:FF";
    }

    private static int readPenBattery(XposedModule module) {
        try {
            BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
            if (adapter == null) {
                return -1;
            }
            Set<BluetoothDevice> bonded = adapter.getBondedDevices();
            if (bonded == null) {
                return -1;
            }
            for (BluetoothDevice device : bonded) {
                if (!Util.isXiaomiPenName(device.getName())) {
                    continue;
                }
                Method getBattery = null;
                try {
                    getBattery = BluetoothDevice.class.getMethod("getBatteryLevel");
                } catch (Throwable ignored) {
                    try {
                        getBattery = BluetoothDevice.class.getDeclaredMethod("getBatteryLevel");
                    } catch (Throwable ignored2) {
                    }
                }
                if (getBattery == null) {
                    return -1;
                }
                getBattery.setAccessible(true);
                Object value = getBattery.invoke(device);
                if (value instanceof Number) {
                    int level = ((Number) value).intValue();
                    if (level >= 0 && level <= 100) {
                        Util.log(module, "readPenBattery from BluetoothDevice: " + level + "% mac=" + device.getAddress());
                        return level;
                    }
                }
            }
        } catch (Throwable throwable) {
            Util.warn(module, "readPenBattery failed", throwable);
        }
        return -1;
    }

    private static void hookLaserFeature(XposedModule module) {
        try {
            Class<?> am = Class.forName("android.app.ApplicationPackageManager");
            for (Method method : am.getDeclaredMethods()) {
                if (!method.getName().equals("hasSystemFeature")) {
                    continue;
                }
                Util.hook(module, method, "hide_laser_" + method.getParameterTypes().length, chain -> {
                    Object arg = chain.getArg(0);
                    if (arg instanceof String && "oplus.software.laser.support".equals(arg)) {
                        return false;
                    }
                    return chain.proceed();
                });
            }
            Util.e(module, "laser feature hooked -> hidden");
        } catch (Throwable throwable) {
            Util.warn(module, "hook laser feature failed", throwable);
        }
    }

    private static void hookIpeSettingsWriter(XposedModule module, ClassLoader classLoader) {
        Class<?> writer = Util.findFirstClass(classLoader, "i2.b", "d2.b", "e2.b");
        Method method = writer == null ? null : Util.findMethod(classLoader, writer.getName(), "a", int.class, String.class);
        Util.hook(module, method, "ipe_settings_writer", chain -> {
            String key = (String) chain.getArg(1);
            int value = (Integer) chain.getArg(0);
            if (SettingsHooks.KEY_PENCIL_ENABLE.equals(key) && value == 0) {
                return chain.proceed(new Object[]{1, key});
            }
            return chain.proceed();
        });
    }

    private static void hookNameMapping(XposedModule module, ClassLoader classLoader) {
        Method commonConfigE = Util.findMethod(classLoader,
                "com.oplus.ipemanager.btadsorb.util.h", "e", String.class);
        Util.hook(module, commonConfigE, "pencil_project_name", chain -> {
            Object result = chain.proceed();
            String name = (String) chain.getArg(0);
            if (result == null && Util.isXiaomiPenName(name)) {
                return "IceLand";
            }
            return result;
        });

        Method modelId = Util.findMethod(classLoader,
                "com.oplus.ipemanager.btadsorb.util.w", "r", String.class);
        Util.hook(module, modelId, "pencil_model_id", chain -> {
            Object result = chain.proceed();
            if (Util.isXiaomiPenName((String) chain.getArg(0))) {
                return "OPN2401";
            }
            return result;
        });

        Method pencilName = Util.findMethod(classLoader,
                "com.oplus.ipemanager.btadsorb.util.w", "v", String.class);
        Util.hook(module, pencilName, "pencil_bt_name", chain -> {
            Object result = chain.proceed();
            if (result == null || String.valueOf(result).isEmpty()) {
                String mac = (String) chain.getArg(0);
                if (mac != null && mac.matches("(?i)^([0-9a-f]{2}:){5}[0-9a-f]{2}$")) {
                    return SettingsHooks.DISPLAY_PENCIL_NAME;
                }
            }
            return result;
        });

        List<Method> pencilTypes = Util.findMethodsByName(classLoader,
                "com.oplus.ipemanager.btadsorb.util.w", "x");
        Util.hookAll(module, pencilTypes, "pencil_type_by_mac", chain -> {
            Object result = chain.proceed();
            if (result == null || "FIRST_GENERATION_PENCIL".equals(String.valueOf(result))) {
                return enumValue(classLoader, PENCIL_TYPE_CLASS, "SECOND_GENERATION_PENCIL");
            }
            return result;
        });
    }

    private static void hookBatteryPreference(XposedModule module, ClassLoader classLoader) {
        Class<?> prefCls = Util.findFirstClass(classLoader, "c2.b", "d2.b", "e2.b");
        Method getter = prefCls == null ? null : Util.findMethod(classLoader, prefCls.getName(), "c", Context.class, int.class, String.class);
        Util.hook(module, getter, "battery_pref_get", chain -> {
            Object result = chain.proceed();
            if (!"pencil_sp_battery_level".equals(chain.getArg(2))) {
                return result;
            }
            int real = readPenBattery(module);
            if (real >= 0) {
                return real;
            }
            if (result instanceof Number && ((Number) result).intValue() >= 0
                    && ((Number) result).intValue() <= 100) {
                return result;
            }
            return 100;
        });
    }

    private static void hookDeviceType(XposedModule module, ClassLoader classLoader) {
        List<Method> cardTypes = Util.findMethodsByName(classLoader,
                "com.oplus.ipemanager.btadsorb.d", "getIpeDeviceType");
        Util.hookAll(module, cardTypes, "ipe_device_type", chain ->
                enumValue(classLoader, DEVICE_TYPE_CLASS, "PENCIL"));

        Class<?> dm = ipeVersion == 16
                ? Util.findFirstClass(classLoader, "t2.b")
                : Util.findFirstClass(classLoader, "m3.b");
        Method deviceManagerType = dm == null ? null : Util.findMethod(classLoader, dm.getName(), "g", String.class);
        Util.hook(module, deviceManagerType, "device_card_type", chain ->
                enumValue(classLoader, DEVICE_TYPE_CLASS, "PENCIL"));

        Method pencilType = dm == null ? null : Util.findMethod(classLoader, dm.getName(), "k", String.class);
        Util.hook(module, pencilType, "device_card_pencil_type", chain ->
                enumValue(classLoader, PENCIL_TYPE_CLASS, "SECOND_GENERATION_PENCIL"));

        Method deviceName = dm == null ? null : Util.findMethod(classLoader, dm.getName(), "j", String.class);
        Util.hook(module, deviceName, "device_card_name", chain -> SettingsHooks.DISPLAY_PENCIL_NAME);
    }

    private static void hookStatusGetters(XposedModule module, ClassLoader classLoader) {
        String[] panelClasses = {
                "com.oplus.ipemanager.btadsorb.ble.d0",
                "com.oplus.ipemanager.btadsorb.ble.e0",
                "com.oplus.ipemanager.btadsorb.ble.c0",
                "com.oplus.ipemanager.btadsorb.ble.PencilBleManager"
        };
        for (String className : panelClasses) {
            for (Method method : Util.findMethodsByName(classLoader, className, "getBatteryLevel")) {
                Util.hook(module, method, "battery_" + className, chain -> {
                    int real = readPenBattery(module);
                    if (real >= 0) {
                        return real;
                    }
                    Object result = chain.proceed();
                    if (result instanceof Number && ((Number) result).intValue() > 0) {
                        return result;
                    }
                    return 100;
                });
            }
            for (Method method : Util.findMethodsByName(classLoader, className, "getChargingState")) {
                Util.hook(module, method, "charging_" + className, chain -> false);
            }
            for (Method method : Util.findMethodsByName(classLoader, className, "getConnectState")) {
                Util.hook(module, method, "connect_" + className, chain -> 2);
            }
            for (Method method : Util.findMethodsByName(classLoader, className, "getPencilTypeByMac")) {
                Util.hook(module, method, "type_" + className, chain ->
                        enumValue(classLoader, PENCIL_TYPE_CLASS, "SECOND_GENERATION_PENCIL"));
            }
            for (Method method : Util.findMethodsByName(classLoader, className, "getAlias")) {
                Util.hook(module, method, "alias_" + className, chain -> SettingsHooks.DISPLAY_PENCIL_NAME);
            }
        }

        String[] uiClasses = {
                "com.oplus.ipemanager.btadsorb.ble.h0",
                "com.oplus.ipemanager.btadsorb.ble.i0",
                "com.oplus.ipemanager.btadsorb.ble.o",
                "com.oplus.ipemanager.btadsorb.ble.z",
                "com.oplus.ipemanager.btadsorb.ble.PencilBleManager"
        };
        for (String className : uiClasses) {
            for (Method method : Util.findMethodsByName(classLoader, className, "getBatteryLevel")) {
                Util.hook(module, method, "ui_battery_" + className, chain -> {
                    int real = readPenBattery(module);
                    return real >= 0 ? real : 100;
                });
            }
            for (Method method : Util.findMethodsByName(classLoader, className, "getConnectState")) {
                Util.hook(module, method, "ui_connect_" + className, chain -> 2);
            }
            for (Method method : Util.findMethodsByName(classLoader, className, "getConnectedPencilMac")) {
                Util.hook(module, method, "ui_mac_" + className, chain -> {
                    Object result = chain.proceed();
                    if (result != null && !String.valueOf(result).isEmpty()) {
                        return result;
                    }
                    Object instance = chain.getThisObject();
                    Context context = instance instanceof Context ? (Context) instance : null;
                    return detectMac(context);
                });
            }
        }

        for (Method method : Util.findMethodsByName(classLoader,
                "com.oplus.ipemanager.btadsorb.ble.f0", "getPencilConnectState")) {
            Util.hook(module, method, "sdk_connect_state", chain -> 2);
        }
    }

    private static void hookLifecycle(XposedModule module, ClassLoader classLoader) {
        String[] classes = {
                "com.oplus.ipemanager.btadsorb.IPeApplication",
                "com.oplus.ipemanager.btadsorb.CoreService"
        };
        for (String className : classes) {
            for (Method method : Util.findMethodsByName(classLoader, className, "onCreate")) {
                Util.hook(module, method, "lifecycle_" + className, chain -> {
                    Object result = chain.proceed();
                    Object instance = chain.getThisObject();
                    Context context = instance instanceof Context ? (Context) instance : null;
                    if (context == null) {
                        return result;
                    }
                    Context app = context.getApplicationContext();
                    String mac = detectMac(app);
                    SettingsHooks.apply(app, mac);

                    Util.e(module, "IPeManager lifecycle " + className
                            + ": settings applied, mac=" + mac);
                    try {
                        Intent intent = new Intent("com.oplus.ipemanager.action.PENCIL_STATUS_CHANGE");
                        intent.putExtra("macAddr", mac);
                        intent.putExtra("present", true);
                        intent.putExtra("deviceType", "pencil");
                        app.sendBroadcast(intent);
                        Util.e(module, "IPeManager: sent PENCIL_STATUS_CHANGE present=true mac=" + mac);
                    } catch (Throwable ignored) {
                        Util.e(module, "IPeManager: send PENCIL_STATUS_CHANGE failed");
                    }
                    return result;
                });
            }
        }
    }

    private static String readPenGeneration(XposedModule module) {
        String node = "/sys/touchpanel/pen_update";
        try {
            java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.FileReader(node));
            String line = reader.readLine();
            reader.close();
            if (line != null) {
                line = line.trim();
                if ("0".equals(line)) {
                    Util.e(module, "pen_update=0 -> Xiaomi Smart Pen (1st gen)");
                    return "Xiaomi Smart Pen";
                }
                if ("1".equals(line)) {
                    Util.e(module, "pen_update=1 -> Xiaomi Smart Pen 2");
                    return "Xiaomi Smart Pen 2";
                }
                Util.e(module, "pen_update unexpected value: " + line);
            }
        } catch (Throwable throwable) {
            Util.e(module, "read " + node + " failed: " + throwable.getMessage());
        }
        return SettingsHooks.DISPLAY_PENCIL_NAME;
    }

    private static String readSerialFromMac(Context context) {
        String mac = detectMac(context);
        if (mac == null || mac.isEmpty()) {
            return "UNKNOWN";
        }
        return mac.replace(":", "").replace("-", "").toUpperCase();
    }

    private static void hookPencilInfo(final XposedModule module, ClassLoader classLoader) {
        final String clazz = "com.oplus.ipemanager.btadsorb.ota.common.PencilInfo";
        String[][] fixedFields = {
                {"getPencilHw", "1.0"},
                {"getPencilFw", "1.0.0"}
        };
        for (String[] field : fixedFields) {
            for (Method method : Util.findMethodsByName(classLoader, clazz, field[0])) {
                Util.hook(module, method, "pencil_info_" + field[0], chain -> field[1]);
            }
        }
        for (Method method : Util.findMethodsByName(classLoader, clazz, "getPencilType")) {
            Util.hook(module, method, "pencil_info_type", chain -> readPenGeneration(module));
        }
        for (Method method : Util.findMethodsByName(classLoader, clazz, "getPencilNum")) {
            Util.hook(module, method, "pencil_info_num", chain -> {
                String mac = null;
                try {
                    Class<?> holder = Util.findFirstClass(classLoader, "o3.a", "p3.a");
                    if (holder == null) { return "UNKNOWN"; }
                    java.lang.reflect.Field field = holder.getDeclaredField("f4121b");
                    field.setAccessible(true);
                    Object ctx = field.get(null);
                    if (ctx instanceof Context) {
                        mac = Settings.Global.getString(
                                ((Context) ctx).getContentResolver(),
                                SettingsHooks.KEY_PENCIL_MAC);
                    }
                } catch (Throwable throwable) {
                    Util.warn(module, "read MAC for serial failed", throwable);
                }
                if (mac == null || mac.isEmpty()) {
                    mac = "DB:04:F7:EC:12:6F";
                }
                return mac.replace(":", "").replace("-", "").toUpperCase();
            });
        }
    }

    private static void hideUnsupportedPreferences(XposedModule module, ClassLoader classLoader) {
        final String[] keys = {
                "touch_feedback",
                "scroll_up_down_menu",
                "scree_off_switch",
                "pencil_update_automatic",
                "long_click",
                "long_click_v2",
                "long_click_jump_v2",
                "long_click_jump_v2_iceland",
                "scribe",
                "learn_to_use_scribe"
        };
        String[] fragments = {
                "com.oplus.ipemanager.btadsorb.setting.fragment.x",
                "com.oplus.ipemanager.btadsorb.pencilPanel.fragment.z"
        };
        for (String fragmentClass : fragments) {
            for (Method method : Util.findMethodsByName(classLoader, fragmentClass, "onResume")) {
                Util.hook(module, method, "hide_unsupported_prefs_" + fragmentClass, chain -> {
                    Object result = chain.proceed();
                    try {
                        Method findPreference = chain.getThisObject().getClass()
                                .getMethod("findPreference", CharSequence.class);
                        findPreference.setAccessible(true);
                        for (String key : keys) {
                            Object pref = findPreference.invoke(chain.getThisObject(), key);
                            if (pref == null) {
                                continue;
                            }
                            Method setVisible = pref.getClass().getMethod("setVisible", boolean.class);
                            setVisible.setAccessible(true);
                            setVisible.invoke(pref, false);
                            Util.e(module, "hidden preference " + key + " in " + fragmentClass);
                        }
                    } catch (Throwable throwable) {
                        Util.warn(module, "hide preferences failed in " + fragmentClass, throwable);
                    }
                    return result;
                });
            }
        }
    }

    private static void hookSettingActivity(XposedModule module, ClassLoader classLoader) {
        final String activityClass = "com.oplus.ipemanager.btadsorb.setting.activity.PencilSettingActivity";
        Class<?> cbCls = Util.findFirstClass(classLoader,
                "com.oplus.ipemanager.btadsorb.setting.activity.i",
                "com.oplus.ipemanager.btadsorb.setting.activity.PencilSettingActivity$b",
                "com.oplus.ipemanager.btadsorb.setting.activity.PencilSettingActivity$1");
        final String callbackClass = cbCls == null ? "com.oplus.ipemanager.btadsorb.setting.activity.i" : cbCls.getName();

        for (Method method : Util.findMethodsByName(classLoader, activityClass, "onResume")) {
            Util.hook(module, method, "setting_activity_resume", chain -> {
                Object result = chain.proceed();
                Object controller = Util.getFieldByType(chain.getThisObject(), callbackClass);
                if (controller == null) {
                    Util.e(module, "PencilSettingActivity.onResume: controller " + callbackClass + " NOT FOUND");
                    return result;
                }
                Context context = chain.getThisObject() instanceof Context ? (Context) chain.getThisObject() : null;
                final String mac = detectMac(context);
                Util.log(module, "PencilSettingActivity.onResume: controller found, mac=" + mac);
                new Handler(Looper.getMainLooper()).postDelayed(() -> {
                    Object r1 = Util.callMethod(controller, "notifyConnectState",
                            new Class<?>[]{int.class, String.class}, 2, mac);
                    int battery = readPenBattery(module);
                    if (battery < 0) {
                        battery = 100;
                    }
                    Object r2 = Util.callMethod(controller, "notifyBatteryLevel",
                            new Class<?>[]{int.class}, battery);
                    Util.log(module, "PencilSettingActivity: injected connect(2," + mac + ") battery(" + battery + "), r1=" + r1 + ", r2=" + r2);
                }, 500L);
                return result;
            });
        }

        for (Method method : Util.findMethodsByName(classLoader, callbackClass, "notifyStateResult")) {
            Util.hook(module, method, "setting_state_result", chain -> {
                Util.e(module, "notifyStateResult forced to true (original=" + chain.getArg(0) + ")");
                return chain.proceed(new Object[]{true});
            });
        }

        try {
            Class<?> activityClazz = Class.forName(activityClass, false, classLoader);
            Method showPage = activityClazz.getDeclaredMethod("c", activityClazz, Integer.class, String.class);
            Util.hook(module, showPage, "setting_show_page", chain -> {
                Object[] args = chain.getArgs().toArray();
                Context context = chain.getArg(0) instanceof Context ? (Context) chain.getArg(0) : null;
                args[1] = 2;
                args[2] = detectMac(context);
                Util.e(module, "PencilSettingActivity.c forced state=2 mac=" + args[2]);
                return chain.proceed(args);
            });
        } catch (Throwable throwable) {
            Util.warn(module, "hook PencilSettingActivity.c failed", throwable);
        }
    }
}
