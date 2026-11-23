package com.jiuhu.gnssspoof;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * SvTableGenerator 的单元测试。
 *
 * <p>验收思路：不是"跑通就行"，而是逐条验证「看起来像真实接收机」这件事的每一项都有据可查。
 * 特别是三条容易做错的：数量必须落在区间、相邻帧不能跳变、载噪比必须随仰角走。
 */
public class SvTableGeneratorTest {

    private static SvTableGenerator.Config cfg() {
        return new SvTableGenerator.Config();
    }

    /** 数量必须落在 [svMin, svMax] 内，扫一个完整周期。 */
    @Test
    public void testCountWithinRange() {
        SvTableGenerator.Config c = cfg();
        long start = 1_700_000_000_000L;
        for (int i = 0; i < 60; i++) {
            long t = start + i * 3_000L;
            int n = SvTableGenerator.countAt(t, c);
            assertTrue("数量越下界: " + n, n >= c.svMin);
            assertTrue("数量越上界: " + n, n <= c.svMax);
        }
    }

    /** 设了 fixedCount 就必须固定，不随波动变化。 */
    @Test
    public void testFixedCount() {
        SvTableGenerator.Config c = cfg();
        c.fixedCount = 67;
        for (int i = 0; i < 10; i++) {
            assertEquals(67, SvTableGenerator.countAt(1_700_000_000_000L + i * 60_000L, c));
        }
    }

    /** 数量确实会动，不能是一条直线。 */
    @Test
    public void testCountActuallyVaries() {
        SvTableGenerator.Config c = cfg();
        long start = 1_700_000_000_000L;
        int min = Integer.MAX_VALUE;
        int max = Integer.MIN_VALUE;
        for (int i = 0; i < 120; i++) {
            int n = SvTableGenerator.countAt(start + i * 1_500L, c);
            min = Math.min(min, n);
            max = Math.max(max, n);
        }
        assertTrue("数量几乎没有波动: " + min + "~" + max, max - min >= 10);
    }

    /** 同一时刻反复调用必须完全一致（钩子可能一秒内被调多次）。 */
    @Test
    public void testDeterministic() {
        long t = 1_700_000_000_000L;
        SvTableGenerator.Table a = SvTableGenerator.generate(t, cfg());
        SvTableGenerator.Table b = SvTableGenerator.generate(t, cfg());
        assertEquals(a.count, b.count);
        for (int i = 0; i < a.count; i++) {
            assertEquals("svid 不一致 i=" + i, a.svid[i], b.svid[i]);
            assertEquals("星座不一致 i=" + i, a.constellation[i], b.constellation[i]);
            assertEquals("CN0 不一致 i=" + i, a.cn0[i], b.cn0[i], 0.001f);
            assertEquals("仰角不一致 i=" + i, a.elevation[i], b.elevation[i], 0.001f);
            assertEquals("方位角不一致 i=" + i, a.azimuth[i], b.azimuth[i], 0.001f);
        }
    }

    /** 相邻两帧之间，进出的卫星数必须很少——否则看起来像"掉了一片卫星"。 */
    @Test
    public void testNoMassiveJumpBetweenFrames() {
        SvTableGenerator.Config c = cfg();
        long t = 1_700_000_000_000L;
        SvTableGenerator.Table a = SvTableGenerator.generate(t, c);
        SvTableGenerator.Table b = SvTableGenerator.generate(t + 1_000L, c);

        java.util.Set<String> setA = keySet(a);
        java.util.Set<String> setB = keySet(b);
        java.util.Set<String> gone = new java.util.HashSet<>(setA);
        gone.removeAll(setB);
        java.util.Set<String> added = new java.util.HashSet<>(setB);
        added.removeAll(setA);

        assertTrue("1 秒内消失过多: " + gone.size(), gone.size() <= 3);
        assertTrue("1 秒内新增过多: " + added.size(), added.size() <= 3);
    }

    /** 每颗卫星的编号不得超过所属星座的上限，否则系统侧解析会出问题。 */
    @Test
    public void testSvidWithinConstellationLimit() {
        SvTableGenerator.Config c = cfg();
        c.fixedCount = 82;
        long t = 1_700_000_000_000L;
        SvTableGenerator.Table table = SvTableGenerator.generate(t, c);
        for (int i = 0; i < table.count; i++) {
            int max = SvTableGenerator.maxSvidOf(table.constellation[i]);
            assertTrue("svid 越界: svid=" + table.svid[i] + " 星座=" + table.constellation[i]
                    + " 上限=" + max, table.svid[i] >= 1 && table.svid[i] <= max);
        }
    }

    /** 仰角必须落在 5–85 度。 */
    @Test
    public void testElevationRange() {
        SvTableGenerator.Config c = cfg();
        long t = 1_700_000_000_000L;
        SvTableGenerator.Table table = SvTableGenerator.generate(t, c);
        for (int i = 0; i < table.count; i++) {
            assertTrue("仰角越界: " + table.elevation[i],
                    table.elevation[i] >= 5.0f && table.elevation[i] <= 85.0f);
        }
    }

    /**
     * 最重要的一条：载噪比必须与仰角正相关。
     * 如果两者无关（或负相关），数据的物理意义就是错的，比"数字好看"更严重。
     */
    @Test
    public void testCn0CorrelatesWithElevation() {
        SvTableGenerator.Config c = cfg();
        long t = 1_700_000_000_000L;
        SvTableGenerator.Table table = SvTableGenerator.generate(t, c);

        int n = table.count;
        double sumX = 0, sumY = 0, sumXY = 0, sumX2 = 0, sumY2 = 0;
        for (int i = 0; i < n; i++) {
            double x = table.elevation[i];
            double y = table.cn0[i];
            sumX += x;
            sumY += y;
            sumXY += x * y;
            sumX2 += x * x;
            sumY2 += y * y;
        }
        double num = n * sumXY - sumX * sumY;
        double den = Math.sqrt((n * sumX2 - sumX * sumX) * (n * sumY2 - sumY * sumY));
        double r = den == 0 ? 0 : num / den;

        assertTrue("CN0 与仰角的相关系数过低: r=" + r, r > 0.6);
    }

    /** 载噪比整体落在 15–48 dB-Hz。 */
    @Test
    public void testCn0Range() {
        SvTableGenerator.Config c = cfg();
        long t = 1_700_000_000_000L;
        SvTableGenerator.Table table = SvTableGenerator.generate(t, c);
        for (int i = 0; i < table.count; i++) {
            assertTrue("CN0 越界: " + table.cn0[i],
                    table.cn0[i] >= 15.0f && table.cn0[i] <= 48.0f);
        }
    }

    /** 方位角落在 [0, 360)，且不能出现等距分布（那是人工痕迹）。 */
    @Test
    public void testAzimuthRangeAndNotEquidistant() {
        SvTableGenerator.Config c = cfg();
        c.fixedCount = 60;
        long t = 1_700_000_000_000L;
        SvTableGenerator.Table table = SvTableGenerator.generate(t, c);

        float[] az = new float[table.count];
        System.arraycopy(table.azimuth, 0, az, 0, table.count);
        for (float v : az) {
            assertTrue("方位角越界: " + v, v >= 0.0f && v < 360.0f);
        }
        java.util.Arrays.sort(az);
        // 相邻差值若全部相等，说明是等距生成，判定失败
        float firstGap = az[1] - az[0];
        boolean allEqual = true;
        for (int i = 1; i < az.length; i++) {
            if (Math.abs((az[i] - az[i - 1]) - firstGap) > 0.01f) {
                allEqual = false;
                break;
            }
        }
        assertTrue("方位角呈等距分布，属人工痕迹", !allEqual);
    }

    /** 载频必须按星座区分，不能全填 1575.42。 */
    @Test
    public void testCarrierFreqPerConstellation() {
        SvTableGenerator.Config c = cfg();
        c.fixedCount = 82;
        long t = 1_700_000_000_000L;
        SvTableGenerator.Table table = SvTableGenerator.generate(t, c);

        for (int i = 0; i < table.count; i++) {
            int constellation = table.constellation[i];
            float expected = SvTableGenerator.freqOf(constellation);
            assertEquals("星座 " + constellation + " 的载频不符",
                    expected, table.carrierFreq[i], 0.001f);
        }
        // 至少出现两种不同载频，才说明真的跨星座了
        java.util.Set<Float> freqs = new java.util.HashSet<>();
        for (float f : table.carrierFreq) {
            freqs.add(f);
        }
        assertTrue("载频只有一种，说明没跨星座: " + freqs, freqs.size() >= 2);
    }

    /** 表里必须真的有多个星座，不能退化成纯 GPS。 */
    @Test
    public void testMultipleConstellations() {
        SvTableGenerator.Config c = cfg();
        long t = 1_700_000_000_000L;
        SvTableGenerator.Table table = SvTableGenerator.generate(t, c);

        java.util.Set<Integer> set = new java.util.HashSet<>();
        for (int v : table.constellation) {
            set.add(v);
        }
        assertTrue("星座种类过少: " + set, set.size() >= 3);
    }

    /** 参与定位的数量不能等于总数，也不能为 0。 */
    @Test
    public void testUsedInFixIsSubset() {
        SvTableGenerator.Config c = cfg();
        c.fixCount = 22;
        long t = 1_700_000_000_000L;
        SvTableGenerator.Table table = SvTableGenerator.generate(t, c);

        assertEquals(22, table.usedInFix);
        assertTrue("参与定位数应小于总数", table.usedInFix < table.count);

        // 参与定位的那批（前缀）CN0 应不低于其余卫星
        float minFix = Float.MAX_VALUE;
        for (int i = 0; i < table.usedInFix; i++) {
            minFix = Math.min(minFix, table.cn0[i]);
        }
        for (int i = table.usedInFix; i < table.count; i++) {
            assertTrue("参与定位的卫星 CN0 低于未参与的",
                    table.cn0[i] <= minFix + 0.001f);
        }
    }

    /** 数量为 0 时不能崩。 */
    @Test
    public void testZeroCount() {
        SvTableGenerator.Config c = cfg();
        c.fixedCount = 0;
        c.svMin = 0;
        c.svMax = 0;
        SvTableGenerator.Table table = SvTableGenerator.generate(1_700_000_000_000L, c);
        assertEquals(0, table.count);
        assertEquals(0, table.usedInFix);
    }

    private static java.util.Set<String> keySet(SvTableGenerator.Table table) {
        java.util.Set<String> set = new java.util.HashSet<>();
        for (int i = 0; i < table.count; i++) {
            set.add(table.constellation[i] + ":" + table.svid[i]);
        }
        return set;
    }
}
