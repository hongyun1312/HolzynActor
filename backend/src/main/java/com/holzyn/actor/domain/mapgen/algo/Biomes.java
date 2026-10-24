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
 * 生物群系编码（每格 1 字节，S1 产物 / S5 上色依据）。
 * <p>编码稳定：前端调色板、可建造度、单位通行代价都按这些常量索引，<b>不可重排</b>。</p>
 */
public final class Biomes {

    /** 深海（离岸远、深度大）。 */
    public static final byte DEEP_OCEAN = 0;
    /** 海洋（中等深度）。 */
    public static final byte OCEAN = 1;
    /** 浅海（近岸棚架）。 */
    public static final byte SHALLOW = 2;
    /** 滩涂/沙滩。 */
    public static final byte BEACH = 3;
    /** 湿地/沼泽。 */
    public static final byte WETLAND = 4;
    /** 草地。 */
    public static final byte GRASS = 5;
    /** 草甸/花甸（缓坡、湿润）。 */
    public static final byte MEADOW = 6;
    /** 农田（聚落周边）。 */
    public static final byte FARMLAND = 7;
    /** 疏林。 */
    public static final byte FOREST_LIGHT = 8;
    /** 密林。 */
    public static final byte FOREST_DENSE = 9;
    /** 灌木/荒地。 */
    public static final byte SCRUB = 10;
    /** 岩石（高海拔/陡坡）。 */
    public static final byte ROCK = 11;
    /** 断崖。 */
    public static final byte CLIFF = 12;
    /** 雪线以上。 */
    public static final byte SNOW = 13;
    /** 河面。 */
    public static final byte RIVER = 14;
    /** 湖面/池塘。 */
    public static final byte LAKE = 15;

    /** 全部群系数量。 */
    public static final int COUNT = 16;

    private static final String[] NAMES = {
            "深海", "海洋", "浅海", "沙滩", "湿地", "草地", "草甸", "农田",
            "疏林", "密林", "灌木", "岩石", "断崖", "雪地", "河流", "湖泊"
    };

    private Biomes() {
    }

    /**
     * 是否水面（海洋/浅海/河/湖/湿地）。
     *
     * @param b 群系编码
     * @return true=不可建造
     */
    public static boolean isWater(byte b) {
        return b == DEEP_OCEAN || b == OCEAN || b == SHALLOW || b == RIVER || b == LAKE;
    }

    /**
     * 是否海洋类水面。
     *
     * @param b 群系编码
     * @return true=海
     */
    public static boolean isSea(byte b) {
        return b == DEEP_OCEAN || b == OCEAN || b == SHALLOW;
    }

    /**
     * 名称。
     *
     * @param b 群系编码
     * @return 中文名
     */
    public static String name(byte b) {
        int i = b & 0xFF;
        return i >= 0 && i < NAMES.length ? NAMES[i] : "未知";
    }
}
