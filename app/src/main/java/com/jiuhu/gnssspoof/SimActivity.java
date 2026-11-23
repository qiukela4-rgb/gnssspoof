package com.jiuhu.gnssspoof;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 虚拟卫星检测页。
 *
 * <p>直接调用 {@link SvTableGenerator}，不经过系统的 GNSS 服务，所以看到的就是
 * 「下一秒会喂给系统的数据」。每秒刷新一次，能直观看出数量波动与卫星进出。
 */
public class SimActivity extends Activity {

    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView tvSummary;
    private ArrayAdapter<String> adapter;
    private final List<String> rows = new ArrayList<>();
    private long lastUpdate;

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            refresh();
            handler.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_sim);

        tvSummary = findViewById(R.id.tvSummary);
        ListView list = findViewById(R.id.lvSats);
        adapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, rows);
        list.setAdapter(adapter);

        findViewById(R.id.btnBack).setOnClickListener(v -> finish());
    }

    @Override
    protected void onResume() {
        super.onResume();
        handler.post(ticker);
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(ticker);
    }

    private void refresh() {
        SvTableGenerator.Config cfg = ConfigStore.read();
        boolean enabled = ConfigStore.enabled();
        SvTableGenerator.Table table =
                SvTableGenerator.generate(System.currentTimeMillis(), cfg);

        int[] perConstellation = new int[8];
        for (int i = 0; i < table.count; i++) {
            int c = table.constellation[i];
            if (c >= 0 && c < perConstellation.length) {
                perConstellation[c]++;
            }
        }

        StringBuilder sb = new StringBuilder();
        sb.append("伪造开关 : ").append(enabled ? "启用" : "已关闭（系统当前透传真实数据）").append('\n');
        sb.append("本次共   : ").append(table.count).append(" 颗，其中 ")
                .append(table.usedInFix).append(" 颗参与定位").append('\n');
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
        sb.append('\n');
        sb.append("配置区间 : ")
                .append(cfg.fixedCount > 0 ? ("固定 " + cfg.fixedCount) : (cfg.svMin + "–" + cfg.svMax))
                .append(" 颗，定位上限 ").append(cfg.fixCount).append(" 颗").append('\n');
        sb.append("刷新时间 : ").append(new java.text.SimpleDateFormat("HH:mm:ss", Locale.US)
                .format(new java.util.Date()));
        tvSummary.setText(sb.toString());

        rows.clear();
        for (int i = 0; i < table.count; i++) {
            boolean fix = i < table.usedInFix;
            rows.add(String.format(Locale.US,
                    "%s%-3s%2d  %4.1f  %2.0f°/%-3.0f°%s",
                    fix ? "★ " : "· ",
                    ConfigStore.constellationShort(table.constellation[i]),
                    table.svid[i],
                    table.cn0[i],
                    table.elevation[i],
                    table.azimuth[i],
                    fix ? " 定位" : ""));
        }
        adapter.notifyDataSetChanged();
        lastUpdate = System.currentTimeMillis();
    }
}
