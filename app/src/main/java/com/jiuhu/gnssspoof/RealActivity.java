package com.jiuhu.gnssspoof;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.location.GnssStatus;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 真实卫星检测页。
 *
 * <p>难点在于：本模块的钩子挂在 system_server，所有应用读 GnssStatus 拿到的都是伪造数据，
 * 包括这个 Activity 自己。要看到真实值只有一条路——临时把伪造关掉。
 *
 * <p>流程：记录当前开关 → 置 0 → 等缓存过期 → 注册监听取一帧 → 恢复原开关。
 * 整个过程约 3 秒，期间其他应用也会读到真实数据，页面顶部有说明。
 */
public class RealActivity extends Activity {

    private static final int REQ_PERM = 1001;
    /**
     * 关掉伪造后要等够配置缓存（3 秒）过期，否则收到的仍是伪造值。
     * 这个等待发生在引擎已经活跃之后，所以副作用时间短。
     */
    private static final long SPOOF_OFF_SETTLE = 3200L;
    /** 预热超时：注册监听后多久还没收到第一帧就判定引擎唤不醒。 */
    private static final long WARMUP_TIMEOUT = 12000L;
    /** 取真实帧的超时。 */
    private static final long REAL_TIMEOUT = 8000L;

    private static final int PHASE_IDLE = 0;
    private static final int PHASE_WARMUP = 1;
    private static final int PHASE_WAIT_REAL = 2;

    private LocationManager locationManager;
    private GnssStatus.Callback callback;
    private TextView tvSummary;
    private Button btnSample;
    private ArrayAdapter<String> adapter;
    private final List<String> rows = new ArrayList<>();
    private final Handler handler = new Handler(Looper.getMainLooper());

    private int phase = PHASE_IDLE;
    private String originalEnable = "1";
    /** 早于这个时间戳收到的帧仍是伪造值，丢弃。 */
    private long realFrameAfter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_real);

        tvSummary = findViewById(R.id.tvSummary);
        btnSample = findViewById(R.id.btnSample);
        ListView list = findViewById(R.id.lvSats);
        adapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, rows);
        list.setAdapter(adapter);

        locationManager = (LocationManager) getSystemService(LOCATION_SERVICE);

        findViewById(R.id.btnBack).setOnClickListener(v -> finish());
        btnSample.setOnClickListener(v -> startSample());
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        handler.removeCallbacksAndMessages(null);
        if (phase != PHASE_IDLE) {
            // 页面被销毁时务必把开关恢复，不能让它停留在关闭状态
            phase = PHASE_IDLE;
            stopProbe();
            restoreEnable();
        }
    }

    private boolean hasPermission() {
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    private final Runnable warmupTimeoutRunner = this::onWarmupTimeout;
    private final Runnable realTimeoutRunner = this::onRealTimeout;

    /**
     * 只注册卫星监听并不会让引擎真正上电——室内或空闲时它照样停着，
     * 而我们的钩子会把「看起来有上报」这件事伪装出来。所以同时请求位置更新，
     * 强制把 GNSS 引擎拉起来，采样结束再撤掉。
     */
    private final android.location.LocationListener probeListener = location -> {
    };

    private void startProbe() {
        try {
            locationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER, 1000L, 0f, probeListener, handler.getLooper());
        } catch (Throwable ignored) {
        }
    }

    private void stopProbe() {
        try {
            locationManager.removeUpdates(probeListener);
        } catch (Throwable ignored) {
        }
    }

    private void startSample() {
        if (phase != PHASE_IDLE) {
            return;
        }
        if (!hasPermission()) {
            requestPermissions(new String[]{
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION}, REQ_PERM);
            return;
        }

        phase = PHASE_WARMUP;
        btnSample.setEnabled(false);
        btnSample.setText("等待 GNSS 引擎…");
        rows.clear();
        adapter.notifyDataSetChanged();
        tvSummary.setText("第一步：等待引擎上报。\n"
                + "此时仍开着伪造，收到的帧只用来确认引擎已激活，不采用。");

        startProbe();

        // 记住原状态，采样结束原样恢复（原本就是关闭的话不会被误开启）
        String cur = ConfigStore.prop("persist.gnssspoof.enable");
        originalEnable = (cur == null || cur.isEmpty()) ? "1" : cur;

        callback = new GnssStatus.Callback() {
            @Override
            public void onSatelliteStatusChanged(GnssStatus status) {
                onFrame(status);
            }
        };
        try {
            locationManager.registerGnssStatusCallback(callback, handler);
        } catch (Throwable t) {
            tvSummary.setText("注册监听失败：" + t);
            endSample(false);
            return;
        }
        handler.postDelayed(warmupTimeoutRunner, WARMUP_TIMEOUT);
    }

    private void onFrame(GnssStatus status) {
        if (phase == PHASE_WARMUP) {
            // 引擎已经在上报，现在才关伪造——副作用窗口从这一刻才开始
            phase = PHASE_WAIT_REAL;
            handler.removeCallbacks(warmupTimeoutRunner);
            realFrameAfter = System.currentTimeMillis() + SPOOF_OFF_SETTLE;
            setPropViaRoot("enable", "0");
            btnSample.setText("取真实数据…");
            tvSummary.setText("第二步：引擎已激活，已关闭伪造。\n"
                    + "等待约 3 秒让配置缓存过期，之后收到的帧才是真实数据。");
            handler.postDelayed(realTimeoutRunner, SPOOF_OFF_SETTLE + REAL_TIMEOUT);
            return;
        }
        if (phase == PHASE_WAIT_REAL) {
            if (System.currentTimeMillis() < realFrameAfter) {
                return;   // 仍是伪造帧，丢弃
            }
            phase = PHASE_IDLE;
            render(status);
            endSample(true);
        }
    }

    private void onWarmupTimeout() {
        if (phase != PHASE_WARMUP) {
            return;
        }
        phase = PHASE_IDLE;
        tvSummary.setText("超时：12 秒内 GNSS 引擎没有上报卫星状态。\n"
                + "请确认系统定位总开关已打开，并在能收到卫星的地方再试一次。");
        endSample(false);
    }

    private void onRealTimeout() {
        if (phase != PHASE_WAIT_REAL) {
            return;
        }
        phase = PHASE_IDLE;
        tvSummary.setText("超时：关闭伪造后没有等到新的真实帧。\n"
                + "引擎可能短暂空闲，再点一次采样通常就能拿到。");
        endSample(false);
    }

    private void endSample(boolean success) {
        phase = PHASE_IDLE;
        handler.removeCallbacks(warmupTimeoutRunner);
        handler.removeCallbacks(realTimeoutRunner);
        try {
            if (callback != null) {
                locationManager.unregisterGnssStatusCallback(callback);
            }
        } catch (Throwable ignored) {
        }
        callback = null;
        stopProbe();
        restoreEnable();
        btnSample.setEnabled(true);
        btnSample.setText("再次采样");
        if (!success) {
            tvSummary.append("\n\n已恢复原开关设置。");
        }
    }

    private void restoreEnable() {
        setPropViaRoot("enable", originalEnable);
    }

    private void render(GnssStatus status) {
        int n = status.getSatelliteCount();
        int[] perConstellation = new int[8];
        int used = 0;
        for (int i = 0; i < n; i++) {
            int c = status.getConstellationType(i);
            if (c >= 0 && c < perConstellation.length) {
                perConstellation[c]++;
            }
            if (status.usedInFix(i)) {
                used++;
            }
        }

        StringBuilder sb = new StringBuilder();
        sb.append("真实总数 : ").append(n).append(" 颗，其中 ").append(used).append(" 颗参与定位").append('\n');
        sb.append("星座构成 : ");
        boolean first = true;
        for (int c = 0; c < perConstellation.length; c++) {
            if (perConstellation[c] > 0) {
                if (!first) {
                    sb.append(" / ");
                }
                sb.append(ConfigStore.constellationShort(c)).append(' ').append(perConstellation[c]);
                first = false;
            }
        }
        if (first) {
            sb.append("无");
        }
        sb.append('\n');
        sb.append("采样时间 : ").append(new java.text.SimpleDateFormat("HH:mm:ss", Locale.US)
                .format(new java.util.Date())).append('\n');
        sb.append("伪造已恢复：").append("1".equals(originalEnable) ? "启用" : "保持关闭");
        tvSummary.setText(sb.toString());

        rows.clear();
        for (int i = 0; i < n; i++) {
            boolean fix = status.usedInFix(i);
            rows.add(String.format(Locale.US,
                    "%s%-3s%2d  %4.1f  %2.0f°/%-3.0f°%s",
                    fix ? "★ " : "· ",
                    ConfigStore.constellationShort(status.getConstellationType(i)),
                    status.getSvid(i),
                    status.getCn0DbHz(i),
                    status.getElevationDegrees(i),
                    status.getAzimuthDegrees(i),
                    fix ? " 定位" : ""));
        }
        adapter.notifyDataSetChanged();
    }

    private void setPropViaRoot(String name, String value) {
        final String cmd = "setprop persist.gnssspoof." + name + " " + value;
        new Thread(() -> {
            try {
                new ProcessBuilder("su", "-c", cmd).start().waitFor();
            } catch (Throwable t) {
                runOnUiThread(() -> Toast.makeText(RealActivity.this,
                        "写属性失败，采样结果可能不是真实数据", Toast.LENGTH_LONG).show());
            }
        }).start();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == REQ_PERM && hasPermission()) {
            startSample();
        }
    }
}
