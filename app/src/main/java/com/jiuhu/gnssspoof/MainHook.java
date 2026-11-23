package com.jiuhu.gnssspoof;

import android.location.GnssStatus;
import android.os.Parcel;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class MainHook implements IXposedHookLoadPackage {

    private static final String TAG = "[GnssSpoof]";

    // 卫星参数不再在类里写死：数量、星座配比、仰角、载噪比全部由 SvTableGenerator
    // 按物理模型生成；运行期可通过 debug.gnssspoof.* 系统属性调整（见 README 诊断章节）。

    // svidWithFlags 编码常量（运行时反射读取，带默认值兜底）
    private static int SV_SHIFT = 12;                     // svid 左移位数
    private static int CONST_SHIFT = 8;                   // 星座左移位数
    private static int FLAGS_FULL = 0x01 | 0x02 | 0x04 | 0x08; // 星历|历书|使用中|载波
    /** 「参与定位」标志位的位值，单独存一份，便于把它从部分卫星身上摘掉。 */
    private static int USED_IN_FIX_BIT = 0x04;

    /** 配置缓存，避免每次钩子调用都去读系统属性。 */
    private static SvTableGenerator.Config sConfig;
    private static long sConfigAt;
    private static boolean sEnabled = true;
    /** 模块界面写入的配置；首次读取时才创建。 */
    private static Object sPrefs;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        // 只注入 system_server：LSPosed 里作用域只勾「系统框架」
        if (!"android".equals(lpparam.processName)) {
            return;
        }
        XposedBridge.log(TAG + " injected into " + lpparam.processName);
        setProp("debug.gnssspoof.injected", "1");
        hookAll(lpparam.classLoader);
    }

    private void hookAll(ClassLoader cl) {
        // 从运行时读取编码常量，防不同 Android 版本差异
        try {
            SV_SHIFT = XposedHelpers.getStaticIntField(GnssStatus.class, "SVID_SHIFT_WIDTH");
            CONST_SHIFT = XposedHelpers.getStaticIntField(GnssStatus.class, "CONSTELLATION_TYPE_SHIFT_WIDTH");
            int f1 = XposedHelpers.getStaticIntField(GnssStatus.class, "SVID_FLAGS_HAS_EPHEMERIS_DATA");
            int f2 = XposedHelpers.getStaticIntField(GnssStatus.class, "SVID_FLAGS_HAS_ALMANAC_DATA");
            int f3 = XposedHelpers.getStaticIntField(GnssStatus.class, "SVID_FLAGS_USED_IN_FIX");
            int f4 = XposedHelpers.getStaticIntField(GnssStatus.class, "SVID_FLAGS_HAS_CARRIER_FREQUENCY");
            FLAGS_FULL = f1 | f2 | f3 | f4;
            USED_IN_FIX_BIT = f3;
            setProp("debug.gnssspoof.const_ok", SV_SHIFT + "/" + CONST_SHIFT + "/" + FLAGS_FULL);
        } catch (Throwable t) {
            setProp("debug.gnssspoof.const_err", shortErr(t));
        }

        // GnssStatusProvider（Android 12+ 的 GNSS 状态分发器）
        Class<?> provider = null;
        try {
            provider = XposedHelpers.findClass(
                    "com.android.server.location.gnss.GnssStatusProvider", cl);
        } catch (Throwable t) {
            setProp("debug.gnssspoof.provider_err", shortErr(t));
        }

        // 通道 1：分发层替换（HAL 上报时把真实卫星换成假的）
        if (provider != null) {
            try {
                XposedBridge.hookAllMethods(provider, "onReportSvStatus", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        try {
                            if (!isSpoofEnabled()) {
                                return;   // enable=0：放行真实卫星数据
                            }
                            Object fake = buildFakeGnssStatus();
                            if (fake != null) {
                                param.args[0] = fake;
                                setProp("debug.gnssspoof.replaced", String.valueOf(System.currentTimeMillis()));
                            }
                        } catch (Throwable t) {
                            XposedBridge.log(TAG + " replace failed: " + t);
                        }
                    }
                });
                setProp("debug.gnssspoof.hooked1", "1");
            } catch (Throwable t) {
                setProp("debug.gnssspoof.hookerr1", shortErr(t));
            }

            // 通道 3：主动推送（虚拟定位停掉 GNSS 上报时的救命通道）
            // App 注册卫星回调(addListener)时，立刻主动把 8 颗假卫星推给它
            try {
                XposedBridge.hookAllMethods(provider, "addListener", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            if (!isSpoofEnabled()) {
                                return;   // enable=0：不主动推送
                            }
                            if (param.args != null && param.args.length > 1
                                    && param.args[1] != null) {
                                Object listener = param.args[1]; // IGnssStatusListener
                                Object fake = buildFakeGnssStatus();
                                XposedHelpers.callMethod(listener, "onSvStatusChanged", fake);
                                setProp("debug.gnssspoof.push", String.valueOf(System.currentTimeMillis()));
                            }
                        } catch (Throwable t) {
                            XposedBridge.log(TAG + " push failed: " + t);
                        }
                    }
                });
                setProp("debug.gnssspoof.hooked3", "1");
            } catch (Throwable t) {
                setProp("debug.gnssspoof.hookerr3", shortErr(t));
            }
        }

        // 通道 2：跨进程序列化拦截（主力，绕开一切上游差异）
        // GnssStatus.writeToParcel 格式：
        //   writeInt(svCount) → 每颗: writeInt(svidWithFlags) writeFloat(cn0) writeFloat(elev)
        //         writeFloat(azimuth) writeFloat(carrierFreq) writeFloat(basebandCn0)
        try {
            XposedBridge.hookAllMethods(GnssStatus.class, "writeToParcel", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        if (!isSpoofEnabled()) {
                            return;                        // enable=0：原实现继续，透传真实数据
                        }
                        Parcel p = (Parcel) param.args[0];
                        SvTableGenerator.Table table = currentTable();
                        int n = table.count;
                        p.writeInt(n);
                        for (int i = 0; i < n; i++) {
                            int flags = (i < table.usedInFix)
                                    ? FLAGS_FULL
                                    : (FLAGS_FULL & ~USED_IN_FIX_BIT);
                            p.writeInt(SvIdCodec.encode(table.svid[i], table.constellation[i],
                                    SV_SHIFT, CONST_SHIFT, flags));
                            p.writeFloat(table.cn0[i]);        // cn0
                            p.writeFloat(table.elevation[i]);  // elevation
                            p.writeFloat(table.azimuth[i]);    // azimuth
                            p.writeFloat(table.carrierFreq[i]); // carrier freq
                            p.writeFloat(table.cn0[i]);        // baseband cn0 与 cn0 同源
                        }
                        param.setResult(null);                         // 跳过原实现
                        setProp("debug.gnssspoof.w2p", String.valueOf(System.currentTimeMillis()));
                        setProp("debug.gnssspoof.svcount_live", String.valueOf(n));
                    } catch (Throwable t) {
                        XposedBridge.log(TAG + " w2p failed: " + t);
                    }
                }
            });
            setProp("debug.gnssspoof.hooked2", "1");
        } catch (Throwable t) {
            setProp("debug.gnssspoof.hookerr2", shortErr(t));
        }
    }

    /** 读一次配置并缓存，3 秒内不重复读——钩子可能一秒被调上百次。 */
    private static void refreshConfigIfNeeded() {
        long now = System.currentTimeMillis();
        if (sConfig != null && now - sConfigAt < 3000) {
            return;
        }
        SvTableGenerator.Config c = new SvTableGenerator.Config();

        // 优先读模块界面写入的 SharedPreferences；读不到再退回系统属性。
        // 这样两种调整方式都能用：点界面、或者 adb setprop。
        boolean fromPrefs = readPrefs(c);
        if (!fromPrefs) {
            c.fixedCount = getIntProp("debug.gnssspoof.svcount", 0);
            c.svMin = getIntProp("debug.gnssspoof.svmin", c.svMin);
            c.svMax = getIntProp("debug.gnssspoof.svmax", c.svMax);
            c.fixCount = getIntProp("debug.gnssspoof.fixcount", c.fixCount);
            sEnabled = getIntProp("debug.gnssspoof.enable", 1) != 0;
        }

        sConfig = c;
        sConfigAt = now;
    }

    /**
     * 从模块的 SharedPreferences 读取配置。
     *
     * @return true 表示读到了；false 表示尚未配置过或读取失败，调用方应回退到系统属性。
     */
    private static boolean readPrefs(SvTableGenerator.Config c) {
        try {
            de.robv.android.xposed.XSharedPreferences prefs;
            if (sPrefs == null) {
                prefs = new de.robv.android.xposed.XSharedPreferences(
                        "com.jiuhu.gnssspoof", "gnssspoof");
                prefs.makeWorldReadable();
                sPrefs = prefs;
                setProp("debug.gnssspoof.pref_ok", "1");
            } else {
                prefs = (de.robv.android.xposed.XSharedPreferences) sPrefs;
            }
            prefs.reload();
            if (!prefs.contains("svmin")) {
                return false;   // 用户还没在界面上保存过
            }
            int mode = prefs.getInt("mode", 0);
            c.fixedCount = mode == 1 ? prefs.getInt("svcount", 67) : 0;
            c.svMin = prefs.getInt("svmin", c.svMin);
            c.svMax = prefs.getInt("svmax", c.svMax);
            c.fixCount = prefs.getInt("fixcount", c.fixCount);
            sEnabled = prefs.getInt("enable", 1) != 0;
            return true;
        } catch (Throwable t) {
            setProp("debug.gnssspoof.pref_err", shortErr(t));
            return false;
        }
    }

    /** 是否启用伪造。`enable=0` 表示完全透传真实数据。 */
    private static boolean isSpoofEnabled() {
        refreshConfigIfNeeded();
        return sEnabled;
    }

    /** 当前时刻的卫星表。所有通道共用同一份。 */
    private static SvTableGenerator.Table currentTable() {
        refreshConfigIfNeeded();
        return SvTableGenerator.generate(System.currentTimeMillis(), sConfig);
    }

    /** 读整数型系统属性；未设置或读失败时返回默认值。 */
    private static int getIntProp(String key, int def) {
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            java.lang.reflect.Method m = sp.getMethod("get", String.class, String.class);
            Object raw = m.invoke(null, key, null);
            if (raw == null) {
                return def;
            }
            String v = String.valueOf(raw).trim();
            if (v.isEmpty()) {
                return def;
            }
            return Integer.parseInt(v);
        } catch (Throwable t) {
            return def;
        }
    }

    private Object buildFakeGnssStatus() {
        SvTableGenerator.Table table = currentTable();
        int n = table.count;
        int[] svidWithFlags = new int[n];
        float[] cn0 = new float[n];
        float[] elev = new float[n];
        float[] azim = new float[n];
        float[] carrierFreq = new float[n];
        float[] basebandCn0 = new float[n];

        for (int i = 0; i < n; i++) {
            int flags = (i < table.usedInFix)
                    ? FLAGS_FULL
                    : (FLAGS_FULL & ~USED_IN_FIX_BIT);
            svidWithFlags[i] = SvIdCodec.encode(table.svid[i], table.constellation[i],
                    SV_SHIFT, CONST_SHIFT, flags);
            cn0[i] = table.cn0[i];
            elev[i] = table.elevation[i];
            azim[i] = table.azimuth[i];
            carrierFreq[i] = table.carrierFreq[i];
            basebandCn0[i] = table.cn0[i];
        }

        return XposedHelpers.callStaticMethod(
                GnssStatus.class, "wrap",
                n, svidWithFlags, cn0, elev, azim, carrierFreq, basebandCn0);
    }

    private static String shortErr(Throwable t) {
        String s = t.toString();
        return s.length() > 140 ? s.substring(0, 140) : s;
    }

    private static void setProp(String key, String value) {
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            java.lang.reflect.Method m = sp.getMethod("set", String.class, String.class);
            m.invoke(null, key, value);
        } catch (Throwable ignored) {
        }
    }
}