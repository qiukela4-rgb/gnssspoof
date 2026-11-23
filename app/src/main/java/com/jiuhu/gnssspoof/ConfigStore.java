package com.jiuhu.gnssspoof;

import java.lang.reflect.Method;

/**
 * 在 App 侧读取模块实际使用的配置。
 *
 * <p>与 {@link MainHook} 保持同一套优先级：{@code persist.gnssspoof.X} 优先，
 * 其次 {@code debug.gnssspoof.X}。这样界面上看到的数字与 system_server 里真正生效的一致。
 */
final class ConfigStore {

    private ConfigStore() {
    }

    static int get(String shortName, int def) {
        String v = prop("persist.gnssspoof." + shortName);
        if (v == null || v.isEmpty()) {
            v = prop("debug.gnssspoof." + shortName);
        }
        if (v == null || v.isEmpty()) {
            return def;
        }
        try {
            return Integer.parseInt(v.trim());
        } catch (Throwable t) {
            return def;
        }
    }

    static boolean enabled() {
        return get("enable", 1) != 0;
    }

    /** 组装成生成器需要的配置对象。 */
    static SvTableGenerator.Config read() {
        SvTableGenerator.Config c = new SvTableGenerator.Config();
        c.fixedCount = get("svcount", 0);
        c.svMin = get("svmin", c.svMin);
        c.svMax = get("svmax", c.svMax);
        c.fixCount = get("fixcount", c.fixCount);
        return c;
    }

    static String prop(String key) {
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            Method get = sp.getMethod("get", String.class);
            Object v = get.invoke(null, key);
            return v == null ? null : String.valueOf(v);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 星座短名，用于列表显示。 */
    static String constellationShort(int id) {
        switch (id) {
            case SvTableGenerator.CONST_GPS:
                return "GPS";
            case SvTableGenerator.CONST_GLONASS:
                return "GLO";
            case SvTableGenerator.CONST_QZSS:
                return "QZS";
            case SvTableGenerator.CONST_BEIDOU:
                return "BDS";
            case SvTableGenerator.CONST_GALILEO:
                return "GAL";
            default:
                return "?";
        }
    }
}
