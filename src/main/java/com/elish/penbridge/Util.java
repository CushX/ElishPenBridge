package com.elish.penbridge;

import android.content.ContentResolver;
import android.content.Context;
import android.provider.Settings;
import android.util.Log;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

/**
 * 通用工具：反射查找方法/字段、多版本兼容的类发现、链式 hook 封装、日志开关。
 *
 * 日志默认关闭；需要调试时：
 *   adb shell settings put global elish_pen_verbose_log 1
 * 然后重启相关进程。
 */
final class Util {
    private static final String TAG = "ElishPenBridge";
    private static final String KEY_VERBOSE_LOG = "elish_pen_verbose_log";
    private static volatile Boolean verboseCache = null;

    private Util() {
    }

    private static boolean verboseEnabled() {
        Boolean cached = verboseCache;
        if (cached != null) {
            return cached;
        }
        try {
            Class<?> thread = Class.forName("android.app.ActivityThread");
            Method method = thread.getDeclaredMethod("currentApplication");
            method.setAccessible(true);
            Object app = method.invoke(null);
            if (app instanceof Context) {
                ContentResolver resolver = ((Context) app).getContentResolver();
                boolean enabled = Settings.Global.getInt(resolver, KEY_VERBOSE_LOG, 0) == 1;
                verboseCache = enabled;
                return enabled;
            }
        } catch (Throwable ignored) {
        }
        // 读不到 context 时不要缓存，下次再试；正式版默认不输出日志
        return false;
    }

    static void log(XposedModule module, String message) {
        if (!verboseEnabled()) {
            return;
        }
        try {
            Log.i(TAG, message);
        } catch (Throwable ignored) {
        }
    }

    static void warn(XposedModule module, String message, Throwable throwable) {
        if (!verboseEnabled()) {
            return;
        }
        try {
            if (throwable != null) {
                Log.e(TAG, message, throwable);
            } else {
                Log.e(TAG, message);
            }
        } catch (Throwable ignored) {
        }
    }

    static void e(XposedModule module, String message) {
        if (!verboseEnabled()) {
            return;
        }
        try {
            Log.e(TAG, message);
        } catch (Throwable ignored) {
        }
    }

    static Class<?> findClass(ClassLoader classLoader, String name) {
        try {
            return Class.forName(name, false, classLoader);
        } catch (Throwable throwable) {
            return null;
        }
    }

    // 在一组候选类名中找到第一个实际存在的类，用于跨 ColorOS 版本兼容
    static Class<?> findFirstClass(ClassLoader classLoader, String... names) {
        for (String name : names) {
            Class<?> clazz = findClass(classLoader, name);
            if (clazz != null) {
                return clazz;
            }
        }
        return null;
    }

    // 在一组候选类名中 hook 第一个存在且包含指定方法的类
    static Class<?> hookFirstMatching(XposedModule module, ClassLoader classLoader,
                                      String id, String methodName,
                                      XposedInterface.Hooker hooker, String... names) {
        for (String name : names) {
            Class<?> clazz = findClass(classLoader, name);
            if (clazz == null) {
                continue;
            }
            boolean any = false;
            for (Method method : clazz.getDeclaredMethods()) {
                if (method.getName().equals(methodName)) {
                    hook(module, method, id + "_" + name.replace('.', '_'), hooker);
                    any = true;
                }
            }
            if (any) {
                e(module, "hooked " + name + "." + methodName);
                return clazz;
            }
        }
        return null;
    }

    static Method findMethod(ClassLoader classLoader, String className, String methodName, Class<?>... parameterTypes) {
        Class<?> clazz = findClass(classLoader, className);
        if (clazz == null) {
            return null;
        }
        try {
            return clazz.getDeclaredMethod(methodName, parameterTypes);
        } catch (Throwable throwable) {
            return null;
        }
    }

    static List<Method> findMethodsByName(ClassLoader classLoader, String className, String methodName) {
        List<Method> result = new ArrayList<>();
        Class<?> clazz = findClass(classLoader, className);
        if (clazz == null) {
            return result;
        }
        for (Method method : clazz.getDeclaredMethods()) {
            if (method.getName().equals(methodName)) {
                result.add(method);
            }
        }
        return result;
    }

    static void hook(XposedModule module, Method method, String id, XposedInterface.Hooker hooker) {
        if (method == null) {
            return;
        }
        try {
            module.hook(method)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .setId(TAG + "." + id)
                    .intercept(hooker);
        } catch (Throwable throwable) {
            warn(module, "hook failed: " + method, throwable);
        }
    }

    static void hookAll(XposedModule module, List<Method> methods, String id, XposedInterface.Hooker hooker) {
        for (int i = 0; i < methods.size(); i++) {
            hook(module, methods.get(i), id + "_" + i, hooker);
        }
    }

    static Object adapt(Class<?> returnType, Object value) {
        if (returnType == void.class || value == null) {
            return null;
        }
        if (returnType.isInstance(value)) {
            return value;
        }
        if (returnType == int.class || returnType == Integer.class) {
            return value instanceof Number ? ((Number) value).intValue() : null;
        }
        if (returnType == long.class || returnType == Long.class) {
            return value instanceof Number ? ((Number) value).longValue() : null;
        }
        if (returnType == boolean.class || returnType == Boolean.class) {
            return value instanceof Boolean ? value : null;
        }
        if (returnType == String.class) {
            return String.valueOf(value);
        }
        if (returnType.isEnum() && value instanceof String) {
            try {
                return Enum.valueOf((Class<? extends Enum>) returnType, (String) value);
            } catch (Throwable ignored) {
                return null;
            }
        }
        return null;
    }

    static Object callStatic(ClassLoader classLoader, String className, String methodName, Object... args) {
        try {
            Class<?> clazz = Class.forName(className, true, classLoader);
            Class<?>[] types = new Class<?>[args.length];
            for (int i = 0; i < args.length; i++) {
                types[i] = args[i] == null ? Object.class : args[i].getClass();
            }
            Method method = clazz.getDeclaredMethod(methodName, types);
            method.setAccessible(true);
            return method.invoke(null, args);
        } catch (Throwable throwable) {
            return null;
        }
    }

    static Object newInstance(ClassLoader classLoader, String className, Class<?>[] types, Object... args) {
        try {
            Class<?> clazz = Class.forName(className, true, classLoader);
            Constructor<?> constructor = clazz.getDeclaredConstructor(types);
            constructor.setAccessible(true);
            return constructor.newInstance(args);
        } catch (Throwable throwable) {
            return null;
        }
    }

    static Object getField(Object instance, String fieldName) {
        try {
            Class<?> clazz = instance.getClass();
            while (clazz != null) {
                try {
                    Field field = clazz.getDeclaredField(fieldName);
                    field.setAccessible(true);
                    return field.get(instance);
                } catch (NoSuchFieldException ignored) {
                    clazz = clazz.getSuperclass();
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    static Object getFieldByType(Object instance, String typeName) {
        if (instance == null) {
            return null;
        }
        try {
            Class<?> clazz = instance.getClass();
            while (clazz != null) {
                for (Field field : clazz.getDeclaredFields()) {
                    if (field.getType().getName().equals(typeName)) {
                        field.setAccessible(true);
                        return field.get(instance);
                    }
                }
                clazz = clazz.getSuperclass();
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    static Object callMethod(Object instance, String methodName, Class<?>[] types, Object... args) {
        try {
            Method method = instance.getClass().getDeclaredMethod(methodName, types);
            method.setAccessible(true);
            return method.invoke(instance, args);
        } catch (Throwable throwable) {
            return null;
        }
    }

    static boolean isXiaomiPenName(String name) {
        if (name == null) {
            return false;
        }
        String lower = name.toLowerCase();
        return (lower.contains("xiaomi") && (lower.contains("pen") || lower.contains("stylus")))
                || lower.contains("smart pen")
                || lower.contains("灵感触控笔");
    }
}
