/*
 * Copyright (c) 2026 HONGYUN
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package com.holzyn.actor.domain.mapnav.algo;

/**
 * 运行期导航栅格（S1 地形的「可寻路切片」）。
 *
 * <p><b>为什么要有这一层</b>：{@code TerrainData} 是 S1 生成期的内存对象，进程一重启就没了；
 * 而角色走路是<b>运行期每秒都在发生</b>的事。把整张地形（16 个 float 场）落盘既没必要也太大，
 * 真正决定「能不能走」的只有三件事：<b>哪是水、哪太陡、哪是陆地</b>——所以只切这三份出来。</p>
 *
 * <p><b>为什么水用 {@code TerrainData.waterCell()}</b>：内陆湖的水面高程高于海平面，
 * 按 {@code height <= seaLevel} 判会把湖当旱地（AGENTS.md 8.1 第 6 条）。这里只做搬运，
 * 判定仍然在 S1 那一刻完成，运行期不再重算——一份水陆事实，两处各自解释就会长出两个 bug。</p>
 *
 * <p>索引约定与 {@code TerrainData} 一致：{@code i = z * size + x}，坐标单位米，1 米 1 格。</p>
 */
public final class NavGrid {

    /** 文件魔数（{@code nav.bin} 头 4 字节）。 */
    public static final String MAGIC = "HNAV";

    /** 格式版本（加字段必须升版本，读侧按版本判断）。 */
    public static final int VERSION = 1;

    /** 边长（格）。 */
    public final int size;

    /** 海平面（归一化高度；仅用于海拔换算与调试展示）。 */
    public final double seaLevel;

    /** 每格米数（当前恒为 1.0，留作换尺度时的唯一入口）。 */
    public final double metersPerCell;

    /** 地形种子（用于判断产物是否与当前地图同源）。 */
    public final long seed;

    /** 1 = 水面（湖/河/海，来自 {@code TerrainData.waterCell}）。 */
    public final boolean[] water;

    /** 坡度 0~255（映射 0~1；行走代价用）。 */
    public final byte[] slope;

    /** 海拔（分米；渲染不出，但用于「门是否悬空」「坡上能不能站」的兜底判断）。 */
    public final short[] elevation;

    /** 1 = 林下（密林/疏林；行走加价、且前端「可建」预览里视为不可建）。 */
    public final boolean[] forest;

    /**
     * 构造。
     *
     * @param size         边长
     * @param seaLevel     海平面
     * @param metersPerCell 每格米数
     * @param seed         地形种子
     * @param water        水面标记
     * @param slope        坡度
     * @param elevation    海拔（分米）
     * @param forest       林下标记
     */
    public NavGrid(int size, double seaLevel, double metersPerCell, long seed,
                   boolean[] water, byte[] slope, short[] elevation, boolean[] forest) {
        int n = size * size;
        if (water == null || water.length != n || slope == null || slope.length != n
                || elevation == null || elevation.length != n
                || forest == null || forest.length != n) {
            throw new IllegalArgumentException("导航栅格数组长度必须为 size*size");
        }
        this.size = size;
        this.seaLevel = seaLevel;
        this.metersPerCell = metersPerCell <= 0 ? 1.0 : metersPerCell;
        this.seed = seed;
        this.water = water;
        this.slope = slope;
        this.elevation = elevation;
        this.forest = forest;
    }

    /**
     * 从 S1 地形切出导航栅格（生成期调用一次，之后只读文件）。
     *
     * @param terrain S1 地形
     * @param seed    地图种子（与 {@code actor_world_map.seed} 同源）
     * @return 导航栅格
     */
    public static NavGrid fromTerrain(com.holzyn.actor.domain.mapgen.algo.TerrainData terrain, long seed) {
        int size = terrain.size;
        int n = size * size;
        boolean[] water = new boolean[n];
        boolean[] forest = new boolean[n];
        byte[] slope = new byte[n];
        short[] elevation = new short[n];
        double h2m = terrain.heightToMeters <= 0 ? 1.0 : terrain.heightToMeters;
        for (int i = 0; i < n; i++) {
            // 水陆唯一入口：不在这里重写 height <= seaLevel（内陆湖会判错）
            water[i] = terrain.waterCell(i);
            forest[i] = terrain.biome[i] == com.holzyn.actor.domain.mapgen.algo.Biomes.FOREST_DENSE
                    || terrain.biome[i] == com.holzyn.actor.domain.mapgen.algo.Biomes.FOREST_LIGHT;
            float s = Math.max(0f, Math.min(1f, terrain.slope[i]));
            slope[i] = (byte) Math.round(s * 255f);
            // 分米：一栋房子地基差 10 厘米在图上就是「悬空」，用米太粗
            long dm = Math.round(terrain.height[i] * h2m * 10.0);
            elevation[i] = (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, dm));
        }
        return new NavGrid(size, terrain.seaLevel, 1.0, seed, water, slope, elevation, forest);
    }

    /** 索引。 */
    public int idx(int x, int z) {
        return z * size + x;
    }

    /** 是否界内。 */
    public boolean inside(int x, int z) {
        return x >= 0 && z >= 0 && x < size && z < size;
    }

    /** 是否水（越界视为水：界外不该走）。 */
    public boolean isWater(int x, int z) {
        return !inside(x, z) || water[idx(x, z)];
    }

    /** 是否水（按索引，越界越界调用方保证）。 */
    public boolean isWater(int i) {
        return water[i];
    }

    /** 坡度 0~1。 */
    public double slopeAt(int x, int z) {
        return inside(x, z) ? (slope[idx(x, z)] & 0xFF) / 255.0 : 1.0;
    }

    /** 是否林下（越界视为林：界外不该走）。 */
    public boolean isForest(int x, int z) {
        return !inside(x, z) || forest[idx(x, z)];
    }

    /** 海拔（米）。 */
    public double elevationMeters(int x, int z) {
        return inside(x, z) ? elevation[idx(x, z)] / 10.0 : 0.0;
    }

    /**
     * 米坐标 → 格（向下取整，钳制到界内）。
     *
     * @param meters 米
     * @return 格坐标
     */
    public int cellOf(double meters) {
        int c = (int) Math.floor(meters);
        return Math.max(0, Math.min(size - 1, c));
    }

    /** 格中心 → 米（画点、算朝向用）。 */
    public double centerOf(int cell) {
        return cell + 0.5;
    }
}
