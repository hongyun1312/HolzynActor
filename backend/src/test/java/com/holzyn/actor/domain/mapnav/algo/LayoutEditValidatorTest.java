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

import com.holzyn.actor.domain.mapgen.entity.ActorMapFeature;
import com.holzyn.actor.domain.mapgen.entity.ActorMapRoad;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 布局校验的七条规则：「拖到这儿行不行」必须能逐条说清为什么。
 *
 * <p>为什么规则要逐条测：这些规则决定用户能不能把房子挪走。少一条（比如忘了查水），
 * 用户会把整座粮仓拖进湖里、而且服务端还"成功"了——下次打开地图发现少了一栋房，
 * 谁也说不清是 AI 生成丢了还是自己拖没了。</p>
 */
class LayoutEditValidatorTest {

    private static final int SIZE = 64;

    private static final LayoutEditValidator.Limits LIMITS =
            new LayoutEditValidator.Limits(SIZE, 4, 0.3, 0.3, 0.32, 25);

    private static NavGrid grid(int[][] water, int[][] steep) {
        int n = SIZE * SIZE;
        boolean[] w = new boolean[n];
        boolean[] forest = new boolean[n];
        byte[] slope = new byte[n];
        for (int[] c : water) {
            w[c[1] * SIZE + c[0]] = true;
        }
        for (int[] c : steep) {
            slope[c[1] * SIZE + c[0]] = (byte) 255;
        }
        return new NavGrid(SIZE, 0.5, 1.0, 1L, w, slope, new short[n], forest);
    }

    private static ActorMapFeature building(String code, double x, double z, double w, double d, double rot) {
        ActorMapFeature f = new ActorMapFeature();
        f.setCode(code);
        f.setKind(ActorMapFeature.KIND_BUILDING);
        f.setName(code);
        f.setPosX(x);
        f.setPosZ(z);
        f.setSizeW(w);
        f.setSizeD(d);
        f.setRotation(rot);
        return f;
    }

    private static ActorMapRoad road(double x0, double z0, double x1, double z1, double width) {
        ActorMapRoad r = new ActorMapRoad();
        r.setCode("r_" + (int) x0 + "_" + (int) z0);
        r.setName("中街");
        r.setWidth(width);
        r.setPathJson(String.format("[[%s,%s],[%s,%s]]", x0, z0, x1, z1));
        return r;
    }

    private static LayoutEditValidator.Candidate cand(double x, double z) {
        return new LayoutEditValidator.Candidate("f_a", x, z, 8, 6, 0);
    }

    @Test
    @DisplayName("空地 + 远离路 → 合法")
    void plainSpotIsValid() {
        List<LayoutEditValidator.Violation> v = LayoutEditValidator.validate(
                cand(30, 30), grid(new int[0][], new int[0][]), List.of(road(10, 10, 60, 10, 4)),
                List.of(), LIMITS);
        assertTrue(v.isEmpty(), "空地上不该有违规：" + v);
    }

    @Test
    @DisplayName("压到水面 → water 违规（内陆湖也算水）")
    void waterRejected() {
        int[][] water = new int[12][];
        for (int i = 0; i < 12; i++) {
            water[i] = new int[]{30 + (i % 4), 28 + (i / 4)};
        }
        List<LayoutEditValidator.Violation> v = LayoutEditValidator.validate(
                cand(30, 29), grid(water, new int[0][]), List.of(), List.of(), LIMITS);
        assertTrue(v.stream().anyMatch(x -> "water".equals(x.rule())), "应报水：" + v);
    }

    @Test
    @DisplayName("压住道路 → road 违规，且带上路名")
    void roadRejected() {
        List<LayoutEditValidator.Violation> v = LayoutEditValidator.validate(
                cand(30, 10.5), grid(new int[0][], new int[0][]), List.of(road(10, 10, 60, 10, 6)),
                List.of(), LIMITS);
        assertTrue(v.stream().anyMatch(x -> "road".equals(x.rule())), "应报压路：" + v);
        assertTrue(v.stream().anyMatch(x -> x.message().contains("中街")));
    }

    @Test
    @DisplayName("与其他建筑重叠 → overlap 违规")
    void overlapRejected() {
        ActorMapFeature other = building("f_b", 33, 30, 10, 8, 0);
        List<LayoutEditValidator.Violation> v = LayoutEditValidator.validate(
                cand(30, 30), grid(new int[0][], new int[0][]), List.of(), List.of(other), LIMITS);
        assertTrue(v.stream().anyMatch(x -> "overlap".equals(x.rule())), "应报重叠：" + v);
    }

    @Test
    @DisplayName("贴边 → bounds 违规")
    void marginRejected() {
        List<LayoutEditValidator.Violation> v = LayoutEditValidator.validate(
                cand(2, 2), grid(new int[0][], new int[0][]), List.of(), List.of(), LIMITS);
        assertTrue(v.stream().anyMatch(x -> "bounds".equals(x.rule())), "应报越界：" + v);
    }

    @Test
    @DisplayName("陡坡上 → slope 违规")
    void steepRejected() {
        int[][] steep = new int[16][];
        for (int i = 0; i < 16; i++) {
            steep[i] = new int[]{26 + (i % 4), 26 + (i / 4)};
        }
        List<LayoutEditValidator.Violation> v = LayoutEditValidator.validate(
                cand(28, 28), grid(new int[0][], steep), List.of(), List.of(), LIMITS);
        assertTrue(v.stream().anyMatch(x -> "slope".equals(x.rule())), "应报坡度：" + v);
    }

    @Test
    @DisplayName("门到路距离：贴着路算近、跑到角落算远")
    void doorDistance() {
        List<ActorMapRoad> roads = List.of(road(10, 10, 60, 10, 4));
        double near = LayoutEditValidator.nearestRoadDistance(30, 13, roads);
        double far = LayoutEditValidator.nearestRoadDistance(30, 55, roads);
        assertTrue(near < 3, "门口离路应很近：" + near);
        assertTrue(far > 20, "远处应超阈值：" + far);
    }

    @Test
    @DisplayName("OBB 相交判定：不相邻的平行矩形不算重叠")
    void obbBasics() {
        assertTrue(LayoutEditValidator.obbOverlap(0, 0, 10, 10, 0, 3, 0, 10, 10, 0));
        assertFalse(LayoutEditValidator.obbOverlap(0, 0, 10, 10, 0, 30, 0, 10, 10, 0));
    }

    @Test
    @DisplayName("旋转矩形四角：0 度时是轴对齐，90 度时宽高互换")
    void cornersRotate() {
        double[][] a = LayoutEditValidator.corners(0, 0, 10, 4, 0);
        assertEquals(5.0, Math.abs(a[1][0]), 1e-9);
        assertEquals(2.0, Math.abs(a[1][1]), 1e-9);
        double[][] b = LayoutEditValidator.corners(0, 0, 10, 4, 90);
        assertEquals(2.0, Math.abs(b[1][0]), 1e-9);
        assertEquals(5.0, Math.abs(b[1][1]), 1e-9);
    }
}
