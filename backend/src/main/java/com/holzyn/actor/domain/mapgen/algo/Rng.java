/*
 * Copyright (c) 2026 HONGYUN
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package com.holzyn.actor.domain.mapgen.algo;

/**
 * 确定性随机源（SplitMix64）——地图生成的唯一随机入口。
 * <p>铁律：同一 seed 必然得到同一张地图；<b>禁止</b>在管线内使用 {@link java.util.Random}
 * 或 {@link Math#random()}（不可复现）。各阶段用 {@code derive(seed, salt)} 派生独立子流，
 * 保证「只重跑某阶段」时该阶段结果不变。</p>
 */
public final class Rng {

    /** SplitMix64 增量常量。 */
    private static final long GAMMA = 0x9E3779B97F4A7C15L;

    private long state;

    /**
     * 构造。
     *
     * @param seed 种子（0 会被替换为固定非零值）
     */
    public Rng(long seed) {
        this.state = seed == 0L ? GAMMA : seed;
    }

    /**
     * 派生独立子流（同 seed + 同 salt → 同子流）。
     *
     * @param seed 主种子
     * @param salt 子流标识
     * @return 子随机源
     */
    public static Rng derive(long seed, long salt) {
        return new Rng(seed * 0x2545F4914F6CDD1DL + salt * 0x9E3779B97F4A7C15L + 0x1234_5678L);
    }

    /**
     * 下一个 64 位随机数。
     *
     * @return 伪随机长整型
     */
    public long nextLong() {
        long z = (state += GAMMA);
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    /**
     * [0,1) 均匀分布。
     *
     * @return 双精度随机数
     */
    public double nextDouble() {
        return (nextLong() >>> 11) * 0x1.0p-53;
    }

    /**
     * [lo,hi) 均匀分布。
     *
     * @param lo 下界
     * @param hi 上界
     * @return 双精度随机数
     */
    public double nextDouble(double lo, double hi) {
        return lo + (hi - lo) * nextDouble();
    }

    /**
     * [0,n) 整数。
     *
     * @param n 上界（不含）
     * @return 整型随机数
     */
    public int nextInt(int n) {
        return (int) Math.floorMod(nextLong(), (long) Math.max(1, n));
    }
}
