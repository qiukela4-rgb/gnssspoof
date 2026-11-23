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
    private static final int FAKE_SV_COUNT = 8;

    private static final float CN0_BASE = 32.0f;
    private static final float CN0_STEP = 1.0f;
    private static final int CN0_PERIOD = 7;
    private static final float ELEVATION = 45.0f;
    private static final float AZIMUTH_STEP = 22.5f;
    private static final float GPS_L1_FREQ = 1575.42f;

    // svidWithFlags 编码常量（运行时反射读取，带默认值兜底）
    private static int SV_SHIFT = 12;                     // svid 左移位数
    private static int CONST_SHIFT = 8;                   // 星座左移位数
    private static int FLAGS_FULL = 0x01 | 0x02 | 0x04 | 0x08; // 星历|历书|使用中|载波

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
            // App 注册卫星回调(addListener)时，立刻主动把 16 颗假卫星推给它
            try {
                XposedBridge.hookAllMethods(provider, "addListener", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        try {
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
                        Parcel p = (Parcel) param.args[0];
                        int n = FAKE_SV_COUNT;
                        int[] svidWithFlags = new int[n];
                        float[] cn0 = new float[n];
                        float[] elev = new float[n];
                        float[] azim = new float[n];
                        float[] carrierFreq = new float[n];
                        float[] basebandCn0 = new float[n];
                        fillFakeArrays(n, svidWithFlags, cn0, elev, azim, carrierFreq, basebandCn0);
                        p.writeInt(n);
                        for (int i = 0; i < n; i++) {
                            p.writeInt(svidWithFlags[i]);              // svidWithFlags(GPS)
                            p.writeFloat(cn0[i]);                      // cn0
                            p.writeFloat(elev[i]);                     // elevation
                            p.writeFloat(azim[i]);                     // azimuth
                            p.writeFloat(carrierFreq[i]);              // carrier freq
                            p.writeFloat(basebandCn0[i]);              // baseband cn0
                        }
                        param.setResult(null);                         // 跳过原实现
                        setProp("debug.gnssspoof.w2p", String.valueOf(System.currentTimeMillis()));
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

    // 正确编码 svidWithFlags：svid<<SV_SHIFT | constellation<<CONST_SHIFT | flags
    private static int encodeSvidFlags(int svid, int constellation) {
        return (svid << SV_SHIFT) | (constellation << CONST_SHIFT) | FLAGS_FULL;
    }

    private static void fillFakeArrays(int n, int[] svidWithFlags, float[] cn0,
                                       float[] elev, float[] azim, float[] carrier,
                                       float[] baseband) {
        for (int i = 0; i < n; i++) {
            svidWithFlags[i] = encodeSvidFlags(i + 1, 1);  // GPS svid 1..16
            cn0[i] = CN0_BASE + (i % CN0_PERIOD) * CN0_STEP;  // 信噪比 32~38
            elev[i] = ELEVATION;
            azim[i] = (i * AZIMUTH_STEP) % 360.0f;             // 360.0f 为角度圆周（非可调参数），不参与常量区
            carrier[i] = GPS_L1_FREQ;
            baseband[i] = cn0[i];
        }
    }

    private Object buildFakeGnssStatus() {
        int n = FAKE_SV_COUNT;
        int[] svidWithFlags = new int[n];
        float[] cn0 = new float[n];
        float[] elev = new float[n];
        float[] azim = new float[n];
        float[] carrierFreq = new float[n];
        float[] basebandCn0 = new float[n];

        fillFakeArrays(n, svidWithFlags, cn0, elev, azim, carrierFreq, basebandCn0);

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