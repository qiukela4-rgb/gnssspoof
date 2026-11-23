package com.jiuhu.gnssspoof;

import android.app.Activity;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.lang.reflect.Method;

/**
 * 模块的配置界面。
 *
 * <p>它本身不做任何 Hook：只负责把参数写进 SharedPreferences，
 * 由 system_server 内的 {@link MainHook} 通过 XSharedPreferences 读取。
 * 写入后 3 秒内自动生效，不需要重启。
 */
public class MainActivity extends Activity {

    /** 配置文件名，必须与 MainHook 里 XSharedPreferences 使用的一致。 */
    public static final String PREF_NAME = "gnssspoof";

    private static final int COUNT_MAX = 120;
    private static final int FIX_MAX = 80;

    private static final int DEF_MIN = 58;
    private static final int DEF_MAX = 82;
    private static final int DEF_FIXED = 67;
    private static final int DEF_FIX = 22;

    private SharedPreferences prefs;

    private TextView tvStatus;
    private Switch swEnable;
    private RadioGroup rgMode;
    private RadioButton rbWave;
    private RadioButton rbFixed;
    private View boxFixed;
    private View boxWave;
    private TextView tvFixed;
    private SeekBar sbFixed;
    private TextView tvMin;
    private SeekBar sbMin;
    private TextView tvMax;
    private SeekBar sbMax;
    private TextView tvFix;
    private SeekBar sbFix;
    private TextView tvWarning;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        prefs = getSharedPreferences(PREF_NAME, MODE_PRIVATE);

        tvStatus = findViewById(R.id.tvStatus);
        swEnable = findViewById(R.id.swEnable);
        rgMode = findViewById(R.id.rgMode);
        rbWave = findViewById(R.id.rbWave);
        rbFixed = findViewById(R.id.rbFixed);
        boxFixed = findViewById(R.id.boxFixed);
        boxWave = findViewById(R.id.boxWave);
        tvFixed = findViewById(R.id.tvFixed);
        sbFixed = findViewById(R.id.sbFixed);
        tvMin = findViewById(R.id.tvMin);
        sbMin = findViewById(R.id.sbMin);
        tvMax = findViewById(R.id.tvMax);
        sbMax = findViewById(R.id.sbMax);
        tvFix = findViewById(R.id.tvFix);
        sbFix = findViewById(R.id.sbFix);
        tvWarning = findViewById(R.id.tvWarning);

        loadValues();
        bindListeners();
        refreshStatus();
    }

    /** 从存储里读回上次的配置，没有就用默认值。 */
    private void loadValues() {
        swEnable.setChecked(prefs.getInt("enable", 1) != 0);

        int mode = prefs.getInt("mode", 0);   // 0 = 波动，1 = 固定
        rbWave.setChecked(mode == 0);
        rbFixed.setChecked(mode == 1);
        applyModeVisibility(mode);

        sbFixed.setMax(COUNT_MAX - 1);
        sbFixed.setProgress(clamp(prefs.getInt("svcount", DEF_FIXED) - 1, 0, COUNT_MAX - 1));
        sbMin.setMax(COUNT_MAX - 1);
        sbMin.setProgress(clamp(prefs.getInt("svmin", DEF_MIN) - 1, 0, COUNT_MAX - 1));
        sbMax.setMax(COUNT_MAX - 1);
        sbMax.setProgress(clamp(prefs.getInt("svmax", DEF_MAX) - 1, 0, COUNT_MAX - 1));
        sbFix.setMax(FIX_MAX);
        sbFix.setProgress(clamp(prefs.getInt("fixcount", DEF_FIX), 0, FIX_MAX));

        updateLabels();
        updateWarning();
    }

    private void bindListeners() {
        rgMode.setOnCheckedChangeListener((group, checkedId) -> {
            applyModeVisibility(checkedId == R.id.rbFixed ? 1 : 0);
            updateWarning();
        });

        // 三个滑块各用一个监听器：上下限需要互相约束，参与定位只影响提示
        sbMin.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                // 下限被拖到上限之上时，把上限一起顶上去，避免出现「下限 80 / 上限 50」这种矛盾状态
                if (fromUser && progress > sbMax.getProgress()) {
                    sbMax.setProgress(progress);
                }
                updateLabels();
                updateWarning();
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });

        sbMax.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                // 反向同理：上限被拖到下限之下时，把下限一起压下来
                if (fromUser && progress < sbMin.getProgress()) {
                    sbMin.setProgress(progress);
                }
                updateLabels();
                updateWarning();
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });

        SeekBar.OnSeekBarChangeListener simple = new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                updateLabels();
                updateWarning();
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        };
        sbFixed.setOnSeekBarChangeListener(simple);
        sbFix.setOnSeekBarChangeListener(simple);

        findViewById(R.id.btnSave).setOnClickListener(v -> save(true));
        findViewById(R.id.btnReset).setOnClickListener(v -> resetDefaults());
        findViewById(R.id.btnRefresh).setOnClickListener(v -> refreshStatus());
        findViewById(R.id.btnSim).setOnClickListener(v ->
                startActivity(new android.content.Intent(this, SimActivity.class)));
        findViewById(R.id.btnReal).setOnClickListener(v ->
                startActivity(new android.content.Intent(this, RealActivity.class)));
    }

    /**
     * 检查参数是否落在一个「真实的接收机可能出现」的范围内，不合理就亮出来。
     *
     * <p>这里不做强制限制——使用者仍然可以设成任意值，但界面会明确告诉他会出什么效果。
     * 依据：真实手机在开阔地多星座合计 60–80 颗、室内 30 上下；少于 4 颗无法解算位置。
     */
    private void updateWarning() {
        if (tvWarning == null) {
            return;
        }
        int min = sbMin.getProgress() + 1;
        int max = sbMax.getProgress() + 1;
        int fix = sbFix.getProgress();
        int fixed = sbFixed.getProgress() + 1;
        boolean fixedMode = rbFixed.isChecked();

        StringBuilder sb = new StringBuilder();
        if (fixedMode) {
            if (fixed < 20) {
                append(sb, "固定 " + fixed + " 颗偏少：真实环境室内也有 30 上下，少于 4 颗无法解算位置");
            } else if (fixed > 100) {
                append(sb, "固定 " + fixed + " 颗偏多：开阔地通常 60–80 颗，超过 100 已超出常见接收机能力");
            }
            if (fix > fixed) {
                append(sb, "参与定位 " + fix + " 颗大于总数 " + fixed + "：实际会按总数处理");
            }
        } else {
            if (min < 20) {
                append(sb, "下限 " + min + " 颗偏低：真实环境室内也有 30 上下，谷底时会明显不真实");
            }
            if (max > 100) {
                append(sb, "上限 " + max + " 颗偏高：开阔地通常 60–80 颗，超过 100 已超出常见接收机能力");
            }
            if (max - min < 10) {
                append(sb, "波动幅度只有 " + (max - min) + " 颗，几乎看不出卫星进出");
            }
            if (fix > min) {
                append(sb, "参与定位 " + fix + " 颗超过下限 " + min + "：波动到谷底时会全部标记为参与定位");
            }
        }

        if (sb.length() == 0) {
            tvWarning.setVisibility(View.GONE);
        } else {
            tvWarning.setText(sb.toString());
            tvWarning.setVisibility(View.VISIBLE);
        }
    }

    private void append(StringBuilder sb, String line) {
        if (sb.length() > 0) {
            sb.append('\n');
        }
        sb.append("· ").append(line);
    }

    private void applyModeVisibility(int mode) {
        boxFixed.setVisibility(mode == 1 ? View.VISIBLE : View.GONE);
        boxWave.setVisibility(mode == 1 ? View.GONE : View.VISIBLE);
    }

    private void updateLabels() {
        tvFixed.setText("固定颗数：" + (sbFixed.getProgress() + 1));
        tvMin.setText("下限：" + (sbMin.getProgress() + 1) + " 颗");
        tvMax.setText("上限：" + (sbMax.getProgress() + 1) + " 颗");
        tvFix.setText("参与定位：" + sbFix.getProgress() + " 颗");
    }

    /** 保存到 SharedPreferences（供下次打开回显），并通过 root 写系统属性。 */
    private void save(boolean toast) {
        int min = sbMin.getProgress() + 1;
        int max = sbMax.getProgress() + 1;
        if (max < min) {
            // 上限小于下限时自动纠正，避免生成器拿到无意义的区间
            int t = min;
            min = max;
            max = t;
            sbMin.setProgress(min - 1);
            sbMax.setProgress(max - 1);
            updateLabels();
        }

        int mode = rbFixed.isChecked() ? 1 : 0;
        prefs.edit()
                .putInt("enable", swEnable.isChecked() ? 1 : 0)
                .putInt("mode", mode)
                .putInt("svcount", sbFixed.getProgress() + 1)
                .putInt("svmin", min)
                .putInt("svmax", max)
                .putInt("fixcount", sbFix.getProgress())
                .commit();

        // 模块实际读取的是系统属性。走 root 直接写，3 秒内生效。
        // 用 persist. 前缀，重启后配置不会丢。
        final String[][] props = {
                {"persist.gnssspoof.enable", swEnable.isChecked() ? "1" : "0"},
                {"persist.gnssspoof.svcount", String.valueOf(mode == 1 ? sbFixed.getProgress() + 1 : 0)},
                {"persist.gnssspoof.svmin", String.valueOf(min)},
                {"persist.gnssspoof.svmax", String.valueOf(max)},
                {"persist.gnssspoof.fixcount", String.valueOf(sbFix.getProgress())},
        };
        new Thread(() -> {
            final boolean ok = applyProps(props);
            runOnUiThread(() -> {
                if (toast) {
                    Toast.makeText(this,
                            ok ? "已保存，3 秒内生效" : "已保存，但写系统属性失败（需要授予 root 权限）",
                            Toast.LENGTH_LONG).show();
                }
                refreshStatus();
            });
        }).start();
    }

    /** 通过 su 批量写系统属性。返回是否全部成功。 */
    private boolean applyProps(String[][] props) {
        StringBuilder cmd = new StringBuilder();
        for (String[] p : props) {
            cmd.append("setprop ").append(p[0]).append(' ').append(p[1]).append("; ");
        }
        try {
            Process process = new ProcessBuilder("su", "-c", cmd.toString())
                    .redirectErrorStream(true)
                    .start();
            return process.waitFor() == 0;
        } catch (Throwable t) {
            return false;
        }
    }

    private void resetDefaults() {
        swEnable.setChecked(true);
        rbWave.setChecked(true);
        applyModeVisibility(0);
        sbFixed.setProgress(DEF_FIXED - 1);
        sbMin.setProgress(DEF_MIN - 1);
        sbMax.setProgress(DEF_MAX - 1);
        sbFix.setProgress(DEF_FIX);
        updateLabels();
        save(false);
        Toast.makeText(this, "已恢复默认", Toast.LENGTH_SHORT).show();
    }

    /** 读系统属性，展示模块在 system_server 里的实际状态。 */
    private void refreshStatus() {
        StringBuilder sb = new StringBuilder();
        sb.append("注入 system_server : ").append(flag(readProp("debug.gnssspoof.injected"))).append('\n');
        sb.append("分发替换通道       : ").append(flag(readProp("debug.gnssspoof.hooked1"))).append('\n');
        sb.append("序列化拦截通道     : ").append(flag(readProp("debug.gnssspoof.hooked2"))).append('\n');
        sb.append("主动推送通道       : ").append(flag(readProp("debug.gnssspoof.hooked3"))).append('\n');
        sb.append("编码常量           : ").append(nz(readProp("debug.gnssspoof.const_ok"))).append('\n');

        String live = readProp("debug.gnssspoof.svcount_live");
        sb.append("当前实际颗数       : ").append(nz(live)).append('\n');

        String errs = collectErrors();
        sb.append("异常               : ").append(errs.isEmpty() ? "无" : errs);

        tvStatus.setText(sb.toString());
    }

    /** 汇总所有 *_err 属性，非空就说明某条通道出了问题。 */
    private String collectErrors() {
        String[] keys = {
                "debug.gnssspoof.const_err",
                "debug.gnssspoof.provider_err",
                "debug.gnssspoof.hookerr1",
                "debug.gnssspoof.hookerr2",
                "debug.gnssspoof.hookerr3",
        };
        StringBuilder sb = new StringBuilder();
        for (String key : keys) {
            String v = readProp(key);
            if (v != null && !v.isEmpty()) {
                if (sb.length() > 0) {
                    sb.append(" | ");
                }
                sb.append(key.substring(key.lastIndexOf('.') + 1)).append('=').append(v);
            }
        }
        return sb.toString();
    }

    private String flag(String v) {
        if (v == null || v.isEmpty()) {
            return "未生效";
        }
        return "1".equals(v) ? "正常" : v;
    }

    private String nz(String v) {
        return (v == null || v.isEmpty()) ? "—" : v;
    }

    private static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    /** 读系统属性。普通应用进程读 debug.* 是允许的；失败返回 null。 */
    private String readProp(String key) {
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            Method get = sp.getMethod("get", String.class);
            Object v = get.invoke(null, key);
            return v == null ? null : String.valueOf(v);
        } catch (Throwable t) {
            return null;
        }
    }
}
