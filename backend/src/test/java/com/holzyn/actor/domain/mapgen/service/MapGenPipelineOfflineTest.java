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

package com.holzyn.actor.domain.mapgen.service;

import com.holzyn.actor.ai.spring.AiSpringChatGateway;
import com.holzyn.actor.domain.account.CurrentUserProvider;
import com.holzyn.actor.domain.mapgen.algo.MapNetwork;
import com.holzyn.actor.domain.mapgen.algo.MapPlacer;
import com.holzyn.actor.domain.mapgen.algo.RoadGrade;
import com.holzyn.actor.domain.mapgen.algo.TerrainParams;
import com.holzyn.actor.domain.mapgen.config.MapGenProperties;
import com.holzyn.actor.domain.mapgen.entity.ActorWorldMap;
import com.holzyn.actor.domain.mapgen.model.MapAssetType;
import com.holzyn.actor.domain.mapgen.model.MapProfile;
import com.holzyn.actor.domain.mapgen.model.WorldMapBrief;
import com.holzyn.actor.domain.settings.service.PromptTemplateService;
import com.holzyn.actor.domain.usage.service.UsageLogService;
import com.holzyn.actor.domain.world.repository.ActorWorldSettingRepository;
import com.holzyn.actor.domain.world.repository.WorldLocationRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 地图管线 V1.1 的<b>离线全链路</b>回归（{@code mapgen.ai-enabled=false}，零 AI、零 Spring 上下文）。
 *
 * <p><b>为什么这个测试必须存在</b>：S0/S2/S3 各有一堆 AI 依赖，一旦开启真实调用，单测就变成
 * 「网络抖动决定红绿」。而这条管线的真正风险不在 AI，在<b>阶段的衔接</b>：S1 的地形参数是不是
 * S0 的那一份？S3 的端点表有没有丢地标？S4 的落位结果是不是<b>整体</b>替换掉了旧数据？
 * 落库顺序有没有外键悬空？这些都是纯逻辑，用假的持久化层就能测——而且测出来的是「编排对不对」，
 * 不是「模型今天心情好不好」。</p>
 *
 * <p>断言的是三条硬门（与 {@code MapgenConnectivityTest} 在算法层守的是同一组事实，
 * 这里守的是「经过编排之后仍然成立」）：</p>
 * <ol>
 *   <li>全流程跑完必须 {@code markReady} 且<b>没有</b> {@code markFailed}；</li>
 *   <li>落库的落位结果必须有建筑、有路，且<b>路网只有一个连通分量</b>；</li>
 *   <li>从 S3 单阶段重跑时，<b>不得</b>重新调用 S0/S2 的构建（那意味着白花两次 AI 调用）。</li>
 * </ol>
 */
class MapGenPipelineOfflineTest {

    /** 测试用种子（与 MapgenConnectivityTest 同一颗，便于横向对照） */
    private static final long SEED = 20261016L;

    /**
     * 测试用边长：<b>必须与线上一致（1024）</b>。
     * <p>踩过的坑：一开始用 512 想跑快点，结果农田只长出 1 片——不是算法坏，而是
     * {@code MapPlacer} 的内部尺度常量（镇半径下限 90 米、田块 56~250 米宽）是按「1 公里见方的
     * 一个镇子」标定的；地图缩到 512 米时，镇子占满了整座岛，田区被推到岛外。
     * 也就是说：<b>测尺度不能妥协</b>，否则测的就不是线上那条管线。</p>
     */
    private static final int SIZE = 1024;

    /**
     * 离线全链路：S0 → S5 跑完，地图 ready，落库的落位结果结构正确。
     */
    @Test
    @DisplayName("离线全链路：ai-enabled=false 时 S0-S5 跑完并 markReady，路网单分量")
    void offlinePipelineProducesReadyMap() {
        Harness h = new Harness();
        h.orchestrator.run(h.mapId, h.projectId, 0);

        // ① 阶段全部落库
        verify(h.persist).saveProfile(eq(h.mapId), any(MapProfile.class), eq(SEED));
        verify(h.persist).saveTerrain(eq(h.mapId), anyString(), any(Map.class));
        verify(h.persist).saveAssets(eq(h.mapId), eq(h.projectId), any());
        verify(h.persist).markReady(eq(h.mapId), any(Map.class));
        verify(h.persist, never()).markFailed(any(), any(), any());
        verify(h.hub).complete(eq(h.projectId), any());

        // ② 落位结果：有建筑、有路、单分量
        ArgumentCaptor<MapPlacer.Result> cap = ArgumentCaptor.forClass(MapPlacer.Result.class);
        verify(h.persist).savePlacement(eq(h.mapId), cap.capture());
        MapPlacer.Result placed = cap.getValue();
        assertFalse(placed.features().isEmpty(), "落位结果不应为空");
        assertFalse(placed.roads().isEmpty(), "路网不应为空");
        long buildings = placed.features().stream().filter(f -> "building".equals(f.kind())).count();
        long residents = placed.features().stream().filter(f -> f.residency() == 1).count();
        long fields = placed.features().stream().filter(f -> "field".equals(f.kind())).count();
        long fieldBlocks = placed.groups().stream().filter(g -> "field".equals(g.kind())).count();
        // 数量必须落在「契约预算」的区间里，而不是「铺满」时代的 80+：
        // 12 户 × (1 主屋 + 1.2 附属) + 9 个契约设施 ≈ 35~45 栋，这才是真实村落的体量。
        assertTrue(buildings >= 25 && buildings <= 70,
                "12 户村落的建筑数应落在 25~70，实际 " + buildings);
        assertTrue(residents >= 8, "住人的主屋数量应与户数同量级，实际 " + residents);
        assertTrue(fields >= 12, "农田块数应为几十块，实际 " + fields);
        assertTrue(fieldBlocks >= 1, "至少要有成片的田区，实际 " + fieldBlocks);
        // 道路必须全部连通（含 S4 事后长出来的田埂土路）
        assertEquals(1, MapNetwork.groupCount(placed.roads()),
                "落库前路网必须是一个连通分量（含田埂土路）");
        // 等级必须在合法区间内（防止 AI/兜底给的宽度串味）
        for (MapPlacer.RoadSpec r : placed.roads()) {
            assertTrue(r.width() >= 1.6 && r.width() <= 8.0, "道路宽度越界：" + r.code() + "=" + r.width());
            assertTrue(r.grade() >= RoadGrade.TRACK && r.grade() <= RoadGrade.TRUNK,
                    "道路等级越界：" + r.code() + "=" + r.grade());
        }

        // ③ 进度必须落库且单调不减（页面刷新后靠 DB 恢复进度条）
        ArgumentCaptor<String> stages = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Integer> percents = ArgumentCaptor.forClass(Integer.class);
        verify(h.persist, org.mockito.Mockito.atLeast(6))
                .updateProgress(eq(h.mapId), stages.capture(), percents.capture());
        assertEquals(List.of("S0", "S1", "S2", "S3", "S4", "S5"), stages.getAllValues());
        List<Integer> pc = percents.getAllValues();
        for (int i = 1; i < pc.size(); i++) {
            assertTrue(pc.get(i) >= pc.get(i - 1), "进度倒退了：" + pc);
        }
        assertTrue(pc.get(pc.size() - 1) >= 84, "S5 起点进度应 ≥ 84");
        verify(h.hub, org.mockito.Mockito.atLeast(6)).stage(eq(h.projectId), anyString(), anyString(),
                anyInt(), anyInt());
    }

    /**
     * 从 S3 单阶段重跑：沿用已落库的档案与资产类型，不重新构建 S0/S2。
     */
    @Test
    @DisplayName("从 S3 单阶段重跑：不重新调用 S0/S2（已落库档案与资产类型直接复用）")
    void rerunFromS3ReusesPersistedProfileAndAssets() {
        Harness h = new Harness();
        MapProfile profile = MapProfile.fallback(SEED, SIZE);
        when(h.persist.loadProfile(h.mapId)).thenReturn(profile);
        when(h.persist.loadAssetTypes(h.mapId)).thenReturn(MapAssetType.fallbackIsland());

        h.orchestrator.run(h.mapId, h.projectId, 3);

        verify(h.persist, never()).saveProfile(any(), any(), anyLong());
        verify(h.persist, never()).saveAssets(any(), any(), any());
        verify(h.persist).savePlacement(eq(h.mapId), any(MapPlacer.Result.class));
        verify(h.persist).markReady(eq(h.mapId), any(Map.class));
        verify(h.persist, never()).markFailed(any(), any(), any());
    }

    /**
     * 读回档案失败时必须失败退出（而不是拿一张空参数的地图硬跑下去）。
     */
    @Test
    @DisplayName("从 S4 重跑但档案读不回来：标 failed，不落位")
    void rerunWithoutProfileFailsLoudly() {
        Harness h = new Harness();
        when(h.persist.loadProfile(h.mapId)).thenReturn(null);

        h.orchestrator.run(h.mapId, h.projectId, 4);

        verify(h.persist).markFailed(eq(h.mapId), eq("S0"), any());
        verify(h.persist, never()).savePlacement(any(), any());
        verify(h.hub).fail(eq(h.projectId), any());
    }

    /**
     * 测试脚手架：真算法 + 假持久化/假贴图/假 SSE。
     */
    private static final class Harness {

        final Long projectId = 1L;
        final Long mapId = 7L;
        final MapPersistService persist = mock(MapPersistService.class);
        final MapArtifactStore artifacts = mock(MapArtifactStore.class);
        final MapGenProgressHub hub = mock(MapGenProgressHub.class);
        final MapBundleQueryService query = mock(MapBundleQueryService.class);
        final MapGenOrchestrator orchestrator;

        Harness() {
            MapGenProperties props = new MapGenProperties();
            props.setAiEnabled(false);
            props.setSize(SIZE);
            props.setMetersPerCell(1.0);

            // 三个阶段服务都用「AI 关闭」的同一份配置：build() 会在第一时间返回确定性兜底，
            // 因此它们身上的 AI 依赖可以是 mock（永不被调用）。
            MapGenAiCaller aiCaller = new MapGenAiCaller(mock(AiSpringChatGateway.class),
                    mock(UsageLogService.class), mock(CurrentUserProvider.class), props);
            WorldMapProfileService profileSvc = new WorldMapProfileService(aiCaller,
                    mock(PromptTemplateService.class),
                    mock(ActorWorldSettingRepository.class), mock(WorldLocationRepository.class), props);
            MapAssetPolicyService assetSvc = new MapAssetPolicyService(aiCaller,
                    mock(PromptTemplateService.class), props);
            MapRoadPlanService roadSvc = new MapRoadPlanService(aiCaller,
                    mock(PromptTemplateService.class), props);
            // S0b 契约：离线跑时直接给「通用兜底契约」（只含户数，不含任何世界观内容）
            MapBriefService briefSvc = mock(MapBriefService.class);
            when(briefSvc.build(any(), anyLong())).thenReturn(WorldMapBrief.genericFallback(12));

            ActorWorldMap row = new ActorWorldMap();
            row.setId(mapId);
            row.setProjectId(projectId);
            row.setSeed(SEED);
            row.setSize(SIZE);
            row.setMetersPerCell(1.0);
            row.setStatus(ActorWorldMap.STATUS_RUNNING);
            when(persist.findMap(mapId)).thenReturn(row);
            try {
                when(artifacts.writeTerrain(any(), any())).thenReturn(mapId + "/terrain.png");
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }

            orchestrator = new MapGenOrchestrator(props, profileSvc, briefSvc, assetSvc, roadSvc,
                    persist, artifacts, hub, query,
                    // 日程/环境在世界数据缺席时返回空表/纪元起点，不影响出图
                    mock(MapRoutineService.class),
                    mock(com.holzyn.actor.domain.world.repository.ActorWorldClockRepository.class));
        }
    }

    /**
     * 兜底档案本身要合法（这是「AI 挂了也能出图」的最后一道保险）。
     */
    @Test
    @DisplayName("兜底档案：参数合法、禁忌清单非空、地标为空间语义")
    void fallbackProfileIsSane() {
        MapProfile p = MapProfile.fallback(SEED, SIZE);
        assertNotNull(p.mapName());
        assertFalse(p.aiGenerated());
        TerrainParams t = p.params();
        assertEquals(SIZE, t.size);
        assertEquals(SEED, t.seed);
        assertTrue(t.plainFraction > 0.2, "陆地必须留出足够平地");
        assertFalse(p.forbidden().isEmpty(), "禁忌清单不能为空（否则 S2 三道闸门少一道）");
        assertFalse(p.landmarks().isEmpty());
        for (MapProfile.Landmark m : p.landmarks()) {
            assertNotNull(m.sit(), "地标必须给空间语义");
        }
        assertTrue(p.summary().contains("禁忌"), "摘要必须带禁忌，S2 依赖它");
    }

    /**
     * 端点数（S3 的连通性自检口径）：锚点数与道路条数都是正数，且分量统计不抛异常。
     */
    @Test
    @DisplayName("统计口径：全量兜底资产库覆盖六类，权重在合法区间")
    void fallbackAssetsCoverAllCategories() {
        List<MapAssetType> all = MapAssetType.fallbackIsland();
        for (String cat : List.of("民居", "商铺", "工坊", "公共", "生产", "水工", "自然")) {
            assertTrue(all.stream().anyMatch(t -> cat.equals(t.category())), "兜底资产库缺大类：" + cat);
        }
        for (MapAssetType t : all) {
            assertTrue(t.weight() >= 1 && t.weight() <= 20, "权重越界：" + t.code());
            assertTrue(t.baseW() >= 3 && t.baseW() <= 40, "面宽越界：" + t.code());
            assertTrue(t.floorsMax() >= t.floorsMin(), "层数区间反了：" + t.code());
            assertTrue(MapAssetType.ROOF_SHAPES.contains(t.roofShape()), "屋顶形制非法：" + t.code());
        }
        assertNotNull(MapAssetType.fallbackIsland().get(0).toPlacerSpec());
    }

    /**
     * 进度必须落库（页面刷新后靠 DB 恢复进度条），且单调不减。
     * <p>（该断言已并入 {@link #offlinePipelineProducesReadyMap()}，避免为了断言多跑一遍 1024 的整条管线。）</p>
     */
}
