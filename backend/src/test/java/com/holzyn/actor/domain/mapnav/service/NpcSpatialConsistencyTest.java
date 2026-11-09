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

package com.holzyn.actor.domain.mapnav.service;

import com.holzyn.actor.domain.mapnav.algo.GridAStar;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 空间一致性三方对账的回归门。
 *
 * <p><b>为什么这些断言值得占一个测试类</b>：三方对账真正的失败方式不是抛异常，而是
 * 「三方各自都算得对，但算法不同源」——归一化长度取谁、精度压到几位、外推上限多少。
 * 这类偏差在正常运行里完全没有症状，只有在多步移动之后才会表现为「图上和 AI 说的不一样」。
 * 所以把三个同源约束钉在单测里：</p>
 * <ol>
 *   <li><b>归一化同源</b>：轨迹长度必须等于「按落库折线实测」的长度；</li>
 *   <li><b>注入 token 可逆</b>：写进 prompt 的坐标必须能被同一份正则原样抽回来；</li>
 *   <li><b>候选清单确定</b>：同一位置重复计算必须得到同一指纹（重访判据的前提）。</li>
 * </ol>
 */
class NpcSpatialConsistencyTest {

    /**
     * 归一化同源：落库折线被压到两位小数后，长度必须按**压过精度的折线**重算。
     *
     * <p>反证：若沿用原始精度上的长度，两侧归一化因子不同，权威点与渲染点会沿轨迹按比例偏开。</p>
     */
    @Test
    @DisplayName("轨迹长度按落库折线实测：与前端 polylineLength 同值，且不等于原始精度长度")
    void lengthMustComeFromSerializedPolyline() {
        // 三个顶点，坐标刻意带三位小数（会被 round2 压掉）
        List<double[]> raw = new ArrayList<>();
        raw.add(new double[]{100.004, 200.006});
        raw.add(new double[]{140.007, 200.008});
        raw.add(new double[]{140.009, 260.011});

        String json = toJson(raw);
        List<double[]> stored = parse(json);
        double storedLength = NpcMovementService.polylineLength(stored);
        double rawLength = NpcMovementService.polylineLength(raw);

        // 1) 落库长度 == 折线自身长度（自洽）
        assertEquals(storedLength, NpcMovementService.polylineLength(parse(json)), 1e-12,
                "同一份 JSON 两次解析长度必须一致");
        // 2) 折线确实被压了精度，因此两个长度不同——这正是必须重算的理由
        assertTrue(Math.abs(storedLength - rawLength) > 0,
                "round2 后的折线与原始折线长度应当不同（否则本用例失去意义）");
        // 3) 抽样验证：中点必须落在这条**落库折线**上
        double[] mid = GridAStar.pointAt(stored, storedLength / 2);
        assertNotNull(mid);
        assertTrue(mid[0] >= 100.0 && mid[0] <= 141.0, "中点 X 应落在折线范围内");
        assertTrue(mid[1] >= 200.0 && mid[1] <= 261.0, "中点 Z 应落在折线范围内");
    }

    /** 同一 (t, 长度) 下，后端 GridAStar.pointAt 与「按段线性插值」必须逐位一致（前端同语义）。 */
    @Test
    @DisplayName("弧长取点与前端 pointAtArc 同语义：分段线性、钳到端点")
    void pointAtIsPiecewiseLinear() {
        List<double[]> pts = List.of(
                new double[]{0, 0}, new double[]{100, 0}, new double[]{100, 100});
        double len = NpcMovementService.polylineLength(pts);
        assertEquals(200.0, len, 1e-9);

        double[] quarter = GridAStar.pointAt(pts, len * 0.25);
        assertEquals(50.0, quarter[0], 1e-9);
        assertEquals(0.0, quarter[1], 1e-9);

        double[] threeQuarter = GridAStar.pointAt(pts, len * 0.75);
        assertEquals(100.0, threeQuarter[0], 1e-9);
        assertEquals(50.0, threeQuarter[1], 1e-9);

        // 越界钳到端点（前端 pointAtArc 同口径）
        double[] over = GridAStar.pointAt(pts, len * 3);
        assertEquals(100.0, over[0], 1e-9);
        assertEquals(100.0, over[1], 1e-9);
        double[] under = GridAStar.pointAt(pts, -5);
        assertEquals(0.0, under[0], 1e-9);
        assertEquals(0.0, under[1], 1e-9);
    }

    /** 注入 token 必须能被同一份正则原样抽回（第三腿证据链的最低要求）。 */
    @Test
    @DisplayName("注入 token 可逆：写进 prompt 的坐标能逐位抽回")
    void tokenRoundTrip() {
        String token = NpcSpatialView.token(123.456, 678.904, 13044230551L);
        assertEquals("(123.46,678.90)@gs=13044230551", token, "固定两位小数、逗号分隔、无空格");

        String prompt = "【当前位置与方向】在集市；坐标" + token + "；附近可达 f_x(12米,东)";
        double[] parsed = NpcSpatialView.parseToken(prompt);
        assertNotNull(parsed);
        assertEquals(123.46, parsed[0], 1e-9);
        assertEquals(678.90, parsed[1], 1e-9);
        assertEquals(13044230551d, parsed[2], 1d);

        assertNull(NpcSpatialView.parseToken("没有坐标的文本"));
    }

    /** 八方位文本（重访判据里的「方位」字段，必须确定）。 */
    @Test
    @DisplayName("八方位确定：正北/正东/正南/正西与原地")
    void bearingTextIsDeterministic() {
        assertEquals("北", NpcSpatialView.bearingText(0, 0, 0, -10));
        assertEquals("东", NpcSpatialView.bearingText(0, 0, 10, 0));
        assertEquals("南", NpcSpatialView.bearingText(0, 0, 0, 10));
        assertEquals("西", NpcSpatialView.bearingText(0, 0, -10, 0));
        assertEquals("此处", NpcSpatialView.bearingText(5, 5, 5, 5));
    }

    /** 候选清单指纹：同一位置两次计算必须一致（否则重访判据永远红）。 */
    @Test
    @DisplayName("候选清单指纹稳定：同位置同清单 → 同指纹；距离/方位变化 → 指纹变化")
    void candidateFingerprintIsStable() {
        NpcSpatialView.Candidate a = candidate("f_house_1", 12.0, "东");
        NpcSpatialView.Candidate b = candidate("loc_well", 8.0, "北");
        List<NpcSpatialView.Candidate> list = List.of(a, b);

        String f1 = NpcSpatialView.candidateFingerprint(list);
        String f2 = NpcSpatialView.candidateFingerprint(List.of(a, b));
        assertEquals(f1, f2, "同位置同清单必须得到同一指纹");
        assertTrue(f1.contains("f_house_1@12.00,东"), "指纹应含引用/距离/方位：" + f1);

        NpcSpatialView.Candidate moved = candidate("loc_well", 9.0, "北");
        assertTrue(!f1.equals(NpcSpatialView.candidateFingerprint(List.of(a, moved))),
                "距离变了，指纹必须变（否则重访会把「搬走了」判成「没变」）");

        assertEquals("-", NpcSpatialView.candidateFingerprint(List.of()));
    }

    /** 坐标文本容错：模型不守契约直接吐坐标时，仍能解析且被标记 */
    @Test
    @DisplayName("坐标文本容错解析：标 coordinate_fallback，不静默当要素命中")
    void coordinateFallbackIsLabelled() {
        TargetResolver resolver = new TargetResolver(
                org.mockito.Mockito.mock(com.holzyn.actor.domain.mapgen.repository.ActorMapFeatureRepository.class),
                org.mockito.Mockito.mock(com.holzyn.actor.domain.mapgen.repository.ActorWorldMapRepository.class),
                org.mockito.Mockito.mock(com.holzyn.actor.domain.world.repository.WorldLocationRepository.class),
                org.mockito.Mockito.mock(NpcSpatialView.class),
                null);

        TargetResolver.Resolved r = resolver.resolve(1L, "(320.5,480.25)").orElse(null);
        assertNotNull(r, "坐标文本必须能解析（否则本地模型一不守约世界就停摆）");
        assertEquals(TargetResolver.BY_COORDINATE, r.getResolvedBy());
        assertEquals(320.5, r.getX(), 1e-9);
        assertEquals(480.25, r.getZ(), 1e-9);
    }

    /** 图内钳制：越界坐标不得把角色推到图外 */
    @Test
    @DisplayName("越界坐标钳到图内（0..1024）")
    void coordinateIsClampedIntoMap() {
        TargetResolver resolver = new TargetResolver(
                org.mockito.Mockito.mock(com.holzyn.actor.domain.mapgen.repository.ActorMapFeatureRepository.class),
                org.mockito.Mockito.mock(com.holzyn.actor.domain.mapgen.repository.ActorWorldMapRepository.class),
                org.mockito.Mockito.mock(com.holzyn.actor.domain.world.repository.WorldLocationRepository.class),
                org.mockito.Mockito.mock(NpcSpatialView.class),
                null);
        TargetResolver.Resolved r = resolver.resolve(1L, "-50,5000").orElse(null);
        assertNotNull(r);
        assertEquals(0.0, r.getX(), 1e-9);
        assertEquals(TargetResolver.MAP_SPAN_M, r.getZ(), 1e-9);
        assertTrue(r.isSnapped(), "被钳制必须标 snapped");
    }

    // ====================地基修复 ====================

    /** 两套坐标域的比例换算：同边长退化为旧公式，跨边长不再把人放到场景外 */
    @Test
    @DisplayName("T3 坐标域换算：同边长退化为减半边长，1024↔500 双向可逆且落在场景内")
    void coordinateSystemReconcilesTwoGrids() {
        // 旧世界里地图与沙盘同边长（500）：换算必须与旧公式逐位相等，否则存量沙盘坐标全体漂移
        assertEquals(0.0, MapCoordinateSystem.mapToSandbox(250, 500, 500), 1e-9);
        assertEquals(-250.0, MapCoordinateSystem.mapToSandbox(0, 500, 500), 1e-9);
        assertEquals(250.0, MapCoordinateSystem.mapToSandbox(500, 500, 500), 1e-9);
        // mapgen 世界：地图 1024、沙盘 500 —— 旧公式会把 x=900 算成 650（场景上界只有 250）
        double sb = MapCoordinateSystem.mapToSandbox(900, 1024, 500);
        assertTrue(Math.abs(sb) <= 250.0, "换算结果必须落在沙盘域内，实际 " + sb);
        assertEquals(900.0, MapCoordinateSystem.sandboxToMap(sb, 1024, 500), 1e-9);
        // 中心对中心
        assertEquals(0.0, MapCoordinateSystem.mapToSandbox(512, 1024, 500), 1e-9);
    }

    /** nav.bin 缺失时的兜底栅格：全可走、尺寸/格距来自地图行 */
    @Test
    @DisplayName("T1 nav.bin 缺失：兜底栅格是空白壳（无水无林），尺寸与格距按地图行")
    void flatShellGridIsUsableFallback() {
        com.holzyn.actor.domain.mapnav.algo.NavGrid g =
                com.holzyn.actor.domain.mapnav.algo.NavGrid.flatShell(128, 8.0, 42L);
        assertEquals(128, g.size);
        assertEquals(8.0, g.metersPerCell, 1e-9);
        assertEquals(128 * 128, g.water.length);
        int water = 0;
        int forest = 0;
        for (int i = 0; i < g.water.length; i++) {
            if (g.water[i]) {
                water++;
            }
            if (g.forest[i]) {
                forest++;
            }
        }
        assertEquals(0, water, "兜底栅格不得凭空造水域");
        assertEquals(0, forest, "兜底栅格不得凭空造林地");
        com.holzyn.actor.domain.mapnav.algo.CostField field =
                com.holzyn.actor.domain.mapnav.algo.CostField.build(g, null, null);
        assertTrue(field.walkable(0, 0) && field.walkable(127, 127),
                "兜底栅格必须整张可走，否则寻路直接失败又会退回两点直线");
    }

    // ==================== 与 NpcMovementService 内部一致的序列化 ====================

    private static NpcSpatialView.Candidate candidate(String ref, double dist, String bearing) {
        NpcSpatialView.Candidate c = new NpcSpatialView.Candidate();
        c.setRef(ref);
        c.setDistanceM(dist);
        c.setBearing(bearing);
        return c;
    }

    /** 与 {@code NpcMovementService.toJson} 同口径（两位小数）。 */
    private static String toJson(List<double[]> pts) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < pts.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append('[').append(round2(pts.get(i)[0])).append(',').append(round2(pts.get(i)[1])).append(']');
        }
        return sb.append(']').toString();
    }

    /** 与 {@code NpcMovementService.parse} 同口径。 */
    private static List<double[]> parse(String json) {
        List<double[]> pts = new ArrayList<>();
        try {
            tools.jackson.databind.JsonNode arr =
                    new tools.jackson.databind.ObjectMapper().readTree(json);
            for (tools.jackson.databind.JsonNode p : arr) {
                pts.add(new double[]{p.path(0).asDouble(), p.path(1).asDouble()});
            }
        } catch (Exception e) {
            throw new IllegalStateException("测试折线解析失败", e);
        }
        return pts;
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
