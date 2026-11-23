package com.jiuhu.gnssspoof;

/**
 * 伪造卫星表的参数生成器。
 *
 * <p>设计目标：产出「看起来像真实接收机」的卫星表，而不是一组摆得整整齐齐的假数字。
 *
 * <p>三条物理约束，决定了这里的全部算法：
 * <ul>
 *   <li>仰角服从球面均匀分布——天顶方向的卫星少、低仰角的多，用 acos 变换实现；</li>
 *   <li>载噪比与仰角正相关——低仰角信号穿过更厚的大气，必然更弱；</li>
 *   <li>卫星沿轨道运动，可见性是缓慢变化的——用独立周期的正弦函数模拟，不用随机数。</li>
 * </ul>
 *
 * <p>所有输出都是 (svid, constellation, 时间戳) 的确定性函数：同一时刻反复调用结果完全一致，
 * 不需要保存任何状态，也不启动线程。这一点对 system_server 内的钩子至关重要。
 */
public final class SvTableGenerator {

    /** 星座标识，取值与 {@code android.location.GnssStatus} 的 CONSTELLATION_* 一致。 */
    public static final int CONST_GPS = 1;
    public static final int CONST_GLONASS = 3;
    public static final int CONST_QZSS = 4;
    public static final int CONST_BEIDOU = 5;
    public static final int CONST_GALILEO = 6;

    /** 每颗卫星的最小/最大俯仰角（度）。低于 5° 实际不可用，85° 以上几乎在天顶。 */
    private static final double ELEV_MIN_DEG = 5.0;
    private static final double ELEV_MAX_DEG = 85.0;

    /** 载噪比模型：25 + 18·sin(仰角) ± 抖动，落在 15–48 dB-Hz。 */
    private static final double CN0_BASE = 25.0;
    private static final double CN0_SPAN = 18.0;
    private static final double CN0_JITTER = 3.0;

    /** 载波频率（MHz），按星座区分——全填 1575.42 等于自报只认识 GPS。 */
    private static final float FREQ_GPS_L1 = 1575.42f;
    private static final float FREQ_GLONASS_L1 = 1602.00f;
    private static final float FREQ_BEIDOU_B1 = 1561.098f;
    private static final float FREQ_QZSS_L1 = 1575.42f;
    private static final float FREQ_GALILEO_E1 = 1575.42f;

    /** 各星座的卫星号上限（AOSP 定义）。生成时不得越界。 */
    private static final int MAX_SVID_GPS = 32;
    private static final int MAX_SVID_GLONASS = 24;
    private static final int MAX_SVID_QZSS = 10;
    private static final int MAX_SVID_BEIDOU = 63;
    private static final int MAX_SVID_GALILEO = 36;

    /**
     * 星座配比权重。真实手机在开阔地的典型构成：GPS 最多、北斗与伽利略次之、
     * 格洛纳斯较少、QZSS 只有几颗（区域增强，本来就少）。
     */
    private static final int[] CONSTELLATIONS = {
            CONST_GPS, CONST_GALILEO, CONST_BEIDOU, CONST_GLONASS, CONST_QZSS
    };
    private static final int[] WEIGHTS = {24, 18, 16, 10, 3};

    private SvTableGenerator() {
    }

    /** 生成参数。字段均为不可变的普通数组，调用方不再修改。 */
    public static final class Config {
        /** 数量下限。 */
        public int svMin = 58;
        /** 数量上限。 */
        public int svMax = 82;
        /** 大于 0 时固定该数量，不做波动。 */
        public int fixedCount = 0;
        /** 数量波动周期（毫秒）。 */
        public long countPeriodMs = 180_000L;
        /** 标记为「参与定位」的卫星数上限。 */
        public int fixCount = 22;
    }

    /** 一张卫星表。svid 与 constellation 一一对应，其余数组同长度。 */
    public static final class Table {
        public final int count;
        public final int[] svid;
        public final int[] constellation;
        public final float[] cn0;
        public final float[] elevation;
        public final float[] azimuth;
        public final float[] carrierFreq;
        /** 前 usedInFix 颗标记为参与定位，其余只算「看得见」。 */
        public final int usedInFix;

        Table(int count, int[] svid, int[] constellation, float[] cn0,
              float[] elevation, float[] azimuth, float[] carrierFreq, int usedInFix) {
            this.count = count;
            this.svid = svid;
            this.constellation = constellation;
            this.cn0 = cn0;
            this.elevation = elevation;
            this.azimuth = azimuth;
            this.carrierFreq = carrierFreq;
            this.usedInFix = usedInFix;
        }
    }

    /** 当前时刻应有的卫星总数。设了 fixedCount 就直接用它。 */
    public static int countAt(long timeMillis, Config cfg) {
        if (cfg.fixedCount > 0) {
            return cfg.fixedCount;
        }
        if (cfg.svMax <= cfg.svMin) {
            return Math.max(0, cfg.svMin);
        }
        double phase = (timeMillis % cfg.countPeriodMs) / (double) cfg.countPeriodMs;
        double wave = 0.5 + 0.5 * Math.sin(2 * Math.PI * phase);
        int n = cfg.svMin + (int) Math.round((cfg.svMax - cfg.svMin) * wave);
        return Math.max(0, Math.min(cfg.svMax, n));
    }

    /** 某颗卫星在某一时刻的「可见度」，取值 0–1，周期与相位由它自己决定。 */
    static double visibility(int svid, int constellation, long timeMillis) {
        long seed = mix(svid, constellation);
        long periodMs = 240_000L + Math.abs(seed % 660_000L);   // 4–15 分钟
        double phase = ((seed >>> 13) & 0xFFFF) / 65536.0 * 2 * Math.PI;
        return 0.5 + 0.5 * Math.sin(2 * Math.PI * timeMillis / periodMs + phase);
    }

    /**
     * 生成某一时刻的完整卫星表。
     *
     * <p>做法：先按配额铺一个比目标数量更大的候选池，按可见度排序取前 N 颗。
     * 这样数量波动时，边界上的卫星会逐个进出，而不是整批跳变。
     */
    public static Table generate(long timeMillis, Config cfg) {
        int target = countAt(timeMillis, cfg);
        if (target <= 0) {
            return new Table(0, new int[0], new int[0], new float[0],
                    new float[0], new float[0], new float[0], 0);
        }

        // 候选池比目标大 15%，保证排序后仍有足够卫星可挑
        int poolSize = target + Math.max(8, target / 7);
        int[] poolSvid = new int[poolSize];
        int[] poolConst = new int[poolSize];
        double[] poolScore = new double[poolSize];

        int filled = 0;
        for (int i = 0; i < CONSTELLATIONS.length && filled < poolSize; i++) {
            int constId = CONSTELLATIONS[i];
            int quota = quotaFor(i, poolSize);
            int maxSvid = maxSvidOf(constId);
            for (int k = 0; k < quota && filled < poolSize; k++) {
                // 卫星号从 1 起，跳过 0 号（不存在）
                int svid = (k % maxSvid) + 1;
                poolSvid[filled] = svid;
                poolConst[filled] = constId;
                poolScore[filled] = visibility(svid, constId, timeMillis);
                filled++;
            }
        }

        // 取可见度最高的 target 颗：用部分选择排序，避免整池全排序的开销
        int n = Math.min(target, filled);
        for (int i = 0; i < n; i++) {
            int best = i;
            for (int j = i + 1; j < filled; j++) {
                if (poolScore[j] > poolScore[best]) {
                    best = j;
                }
            }
            swap(poolScore, i, best);
            swapInt(poolSvid, i, best);
            swapInt(poolConst, i, best);
        }

        int[] svid = new int[n];
        int[] constellation = new int[n];
        float[] cn0 = new float[n];
        float[] elev = new float[n];
        float[] azim = new float[n];
        float[] freq = new float[n];

        for (int i = 0; i < n; i++) {
            svid[i] = poolSvid[i];
            constellation[i] = poolConst[i];
            elev[i] = elevationOf(poolSvid[i], poolConst[i], timeMillis);
            cn0[i] = cn0Of(elev[i], poolSvid[i], poolConst[i], timeMillis);
            azim[i] = azimuthOf(poolSvid[i], poolConst[i], timeMillis);
            freq[i] = freqOf(poolConst[i]);
        }

        // 参与定位的卫星取 CN0 最高的一批，按降序重排前缀，便于调用方直接按索引标记
        int fix = Math.min(Math.max(0, cfg.fixCount), n);
        for (int i = 0; i < fix; i++) {
            int best = i;
            for (int j = i + 1; j < n; j++) {
                if (cn0[j] > cn0[best]) {
                    best = j;
                }
            }
            if (best != i) {
                float t0 = cn0[i]; cn0[i] = cn0[best]; cn0[best] = t0;
                float t1 = elev[i]; elev[i] = elev[best]; elev[best] = t1;
                float t2 = azim[i]; azim[i] = azim[best]; azim[best] = t2;
                float t3 = freq[i]; freq[i] = freq[best]; freq[best] = t3;
                int t4 = svid[i]; svid[i] = svid[best]; svid[best] = t4;
                int t5 = constellation[i]; constellation[i] = constellation[best]; constellation[best] = t5;
            }
        }

        return new Table(n, svid, constellation, cn0, elev, azim, freq, fix);
    }

    /** 俯仰角：球面均匀分布 + 缓慢漂移。 */
    static float elevationOf(int svid, int constellation, long timeMillis) {
        long seed = mix(svid, constellation ^ 0x5DEECE6D);
        double u = unit(seed);
        double minRad = Math.toRadians(ELEV_MIN_DEG);
        double maxRad = Math.toRadians(ELEV_MAX_DEG);
        // acos 变换：使 cos(仰角) 在 [cos(max), cos(min)] 上均匀，等价于球面上均匀落点
        double cosElev = Math.cos(maxRad) + u * (Math.cos(minRad) - Math.cos(maxRad));
        double elevRad = Math.acos(clamp(cosElev, -1.0, 1.0));
        // 每个周期 ±0.6° 的缓慢漂移，模拟轨道运动
        double drift = 0.6 * Math.sin(2 * Math.PI * timeMillis / 900_000.0 + u * 6.283);
        return (float) clamp(Math.toDegrees(elevRad) + drift, ELEV_MIN_DEG, ELEV_MAX_DEG);
    }

    /** 载噪比：与仰角正相关，叠加小幅抖动。 */
    static float cn0Of(float elevationDeg, int svid, int constellation, long timeMillis) {
        double rad = Math.toRadians(elevationDeg);
        long seed = mix(svid, constellation ^ 0x27D4EB2F);
        double jitter = (unit(seed) - 0.5) * 2 * CN0_JITTER;
        // 缓慢起伏，模拟多径与遮蔽
        jitter += 1.2 * Math.sin(2 * Math.PI * timeMillis / 420_000.0 + unit(seed) * 6.283);
        double value = CN0_BASE + CN0_SPAN * Math.sin(rad) + jitter;
        return (float) clamp(value, 15.0, 48.0);
    }

    /** 方位角：起始值随机，随时间缓慢旋转。 */
    static float azimuthOf(int svid, int constellation, long timeMillis) {
        long seed = mix(svid, constellation ^ 0x1B873593);
        double base = unit(seed) * 360.0;
        double rotation = 360.0 * timeMillis / 600_000.0;   // 10 分钟一圈
        double value = (base + rotation) % 360.0;
        return (float) (value < 0 ? value + 360.0 : value);
    }

    static float freqOf(int constellation) {
        switch (constellation) {
            case CONST_GLONASS:
                return FREQ_GLONASS_L1;
            case CONST_BEIDOU:
                return FREQ_BEIDOU_B1;
            case CONST_QZSS:
                return FREQ_QZSS_L1;
            case CONST_GALILEO:
                return FREQ_GALILEO_E1;
            case CONST_GPS:
            default:
                return FREQ_GPS_L1;
        }
    }

    static int maxSvidOf(int constellation) {
        switch (constellation) {
            case CONST_GLONASS:
                return MAX_SVID_GLONASS;
            case CONST_QZSS:
                return MAX_SVID_QZSS;
            case CONST_BEIDOU:
                return MAX_SVID_BEIDOU;
            case CONST_GALILEO:
                return MAX_SVID_GALILEO;
            case CONST_GPS:
            default:
                return MAX_SVID_GPS;
        }
    }

    /** 按权重把 poolSize 分给第 index 个星座，至少 1 颗。 */
    static int quotaFor(int index, int poolSize) {
        int totalWeight = 0;
        for (int w : WEIGHTS) {
            totalWeight += w;
        }
        int quota = (int) Math.round(poolSize * (double) WEIGHTS[index] / totalWeight);
        return Math.max(1, Math.min(quota, maxSvidOf(CONSTELLATIONS[index])));
    }

    /** 64 位混合哈希，把 (svid, 星座) 打散成一个分布均匀的种子。 */
    static long mix(int a, int b) {
        long h = (a * 0x9E3779B97F4A7C15L) ^ (b * 0xC2B2AE3D27D4EB4FL);
        h ^= h >>> 33;
        h *= 0xFF51AFD7ED558CCDL;
        h ^= h >>> 33;
        h *= 0xC4CEB9FE1A85EC53L;
        h ^= h >>> 33;
        return h;
    }

    /** 把种子映射到 [0,1)。 */
    static double unit(long seed) {
        return (seed & 0x1FFFFFFFFFFFFFL) / (double) 0x20000000000000L;
    }

    private static double clamp(double v, double lo, double hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    private static void swap(double[] a, int i, int j) {
        double t = a[i];
        a[i] = a[j];
        a[j] = t;
    }

    private static void swapInt(int[] a, int i, int j) {
        int t = a[i];
        a[i] = a[j];
        a[j] = t;
    }
}
