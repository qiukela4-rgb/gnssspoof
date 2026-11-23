package com.jiuhu.gnssspoof;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * SvIdCodec 单元测试。
 *
 * 覆盖 spec 5.2.1.3 / 5.2.1.4 / 5.2.1.5 与 6.2.1 -- 6.2.4：
 * 样例位布局、移位不可对调、卫星号边界、星座隔离、标志位组合、移位数可替换。
 * 断言中不得出现把 svid 与 constellation 写反的期望值；所有期望值按
 * (svid << svShift) | (constellation << constShift) | flags 复算。
 */
public class SvIdCodecTest {

    private static final int GPS = 1;
    private static final int SV_SHIFT = 12;
    private static final int CONST_SHIFT = 8;
    private static final int FLAGS_FULL = 0xF;

    // 测试场景：样例位布局，(1,1,12,8,0xF) 必须等于 0x110F（4367），对应 spec 6.2.5
    @Test
    public void testSampleLayout() {
        int actual = SvIdCodec.encode(1, GPS, SV_SHIFT, CONST_SHIFT, FLAGS_FULL);

        assertEquals(0x110F, actual); // 十进制 4367
    }

    // 测试场景：移位不可对调，svid=5 与 constellation=1 数值不同，写反时结果会变成 0x150F
    @Test
    public void testShiftNotSwapped() {
        int actual = SvIdCodec.encode(5, GPS, SV_SHIFT, CONST_SHIFT, FLAGS_FULL);

        assertEquals(0x510F, actual); // 十进制 20751
        // 星座位段固定为 GPS=1，卫星号占高位数段
        assertEquals(GPS, (actual >> CONST_SHIFT) & 0xF);
        assertEquals(5, actual >> SV_SHIFT);
    }

    // 测试场景：卫星号边界 32 与 64 均不得侵入星座标识位段，星座仍为 1、标志仍为 0xF
    @Test
    public void testSvidBoundary() {
        int at32 = SvIdCodec.encode(32, GPS, SV_SHIFT, CONST_SHIFT, FLAGS_FULL);
        int at64 = SvIdCodec.encode(64, GPS, SV_SHIFT, CONST_SHIFT, FLAGS_FULL);

        assertEquals(0x2010F, at32); // 十进制 131343
        assertEquals(0x4010F, at64); // 十进制 262415

        assertEquals(GPS, (at32 >> CONST_SHIFT) & 0xF);
        assertEquals(GPS, (at64 >> CONST_SHIFT) & 0xF);
        assertEquals(FLAGS_FULL, at32 & 0xF);
        assertEquals(FLAGS_FULL, at64 & 0xF);
        assertEquals(32, at32 >> SV_SHIFT);
        assertEquals(64, at64 >> SV_SHIFT);
    }

    // 测试场景：星座隔离，(1,5,12,8,0xF) 等于 0x150F，证明该值属于星座 5 而非卫星 5
    @Test
    public void testConstellationIsolation() {
        int actual = SvIdCodec.encode(1, 5, SV_SHIFT, CONST_SHIFT, FLAGS_FULL);

        assertEquals(0x150F, actual); // 十进制 5391
        assertEquals(5, (actual >> CONST_SHIFT) & 0xF);
        assertEquals(1, actual >> SV_SHIFT);
        assertEquals(FLAGS_FULL, actual & 0xF);
    }

    // 测试场景：标志位组合仅影响低 4 位，取 0x0 / 0x5 / 0xF 时高位编码保持 0x1100 不变
    @Test
    public void testFlagCombinations() {
        assertEquals(0x1100, SvIdCodec.encode(1, GPS, SV_SHIFT, CONST_SHIFT, 0x0));
        assertEquals(0x1105, SvIdCodec.encode(1, GPS, SV_SHIFT, CONST_SHIFT, 0x5));
        assertEquals(0x110F, SvIdCodec.encode(1, GPS, SV_SHIFT, CONST_SHIFT, 0xF));

        assertEquals(0x0, SvIdCodec.encode(1, GPS, SV_SHIFT, CONST_SHIFT, 0x0) & 0xF);
        assertEquals(0x5, SvIdCodec.encode(1, GPS, SV_SHIFT, CONST_SHIFT, 0x5) & 0xF);
        assertEquals(0xF, SvIdCodec.encode(1, GPS, SV_SHIFT, CONST_SHIFT, 0xF) & 0xF);
    }

    // 测试场景：移位数由形参决定，(3,2,10,6,0x3) 不得回落到默认 12/8，证明可运行时替换
    @Test
    public void testCustomShiftWidths() {
        int actual = SvIdCodec.encode(3, 2, 10, 6, 0x3);

        assertEquals(0xC83, actual); // 十进制 3203
        assertEquals(2, (actual >> 6) & 0xF);
        assertEquals(3, actual >> 10);
        assertEquals(0x3, actual & 0xF);
    }

    // 测试场景：8 颗卫星编码互不相同，对应 spec 6.2.4 的编码唯一性
    @Test
    public void testAllEightSvidUnique() {
        int previous = -1;
        for (int i = 1; i <= 8; i++) {
            int encoded = SvIdCodec.encode(i, GPS, SV_SHIFT, CONST_SHIFT, FLAGS_FULL);
            assertEquals(i, encoded >> SV_SHIFT);
            assertEquals(GPS, (encoded >> CONST_SHIFT) & 0xF);
            assertEquals(FLAGS_FULL, encoded & 0xF);
            assertTrue("编码值重复: svid=" + i, encoded != previous);
            previous = encoded;
        }
    }
}