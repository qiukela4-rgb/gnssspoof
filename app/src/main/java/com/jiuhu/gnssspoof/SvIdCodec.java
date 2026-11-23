package com.jiuhu.gnssspoof;

public final class SvIdCodec {

    private SvIdCodec() {
    }

    public static int encode(int svid, int constellation, int svShift, int constShift, int flags) {
        return (svid << svShift) | (constellation << constShift) | flags;
    }
}