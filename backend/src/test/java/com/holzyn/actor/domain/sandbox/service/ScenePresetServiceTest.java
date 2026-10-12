package com.holzyn.actor.domain.sandbox.service;

import tools.jackson.databind.ObjectMapper;
import com.holzyn.actor.common.BizException;
import com.holzyn.actor.domain.sandbox.entity.ActorSandboxScene;
import com.holzyn.actor.domain.sandbox.entity.ActorScenePlacement;
import com.holzyn.actor.domain.sandbox.repository.ActorSandboxSceneRepository;
import com.holzyn.actor.domain.sandbox.repository.ActorScenePlacementRepository;
import com.holzyn.actor.domain.sandbox.vo.ScenePresetApplyVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 场景预设服务单元测试（世界沙盘 S3 补全，domain/sandbox/service，同包）。
 * <p>职责：验证——内置预设（青禾村 qinghe-village-v1）应用到场景：layout_json 写回
 * （scene 元信息 + rivers/roads + placements）、摆放表重建（先清空再插入）、幂等重跑不重复、
 * 归属校验 404、预设名/预设缺失 404、非法尺寸/越界摆放/缺 assetId/河流越界 400。</p>
 * <p>设计依据：主方案 §8.1 坐标口径 + SC5 示例村庄一键创建入口 + S3 摆放表。</p>
 */
class ScenePresetServiceTest {

    private static final long PROJECT_ID = 37L;
    private static final long SCENE_ID = 33L;

    private ActorSandboxSceneRepository sceneRepository;
    private ActorScenePlacementRepository placementRepository;
    private ScenePresetService service;

    private final List<ActorSandboxScene> sceneStore = new ArrayList<>();
    private final List<ActorScenePlacement> placementStore = new ArrayList<>();

    @BeforeEach
    void setUp() {
        sceneRepository = Mockito.mock(ActorSandboxSceneRepository.class);
        placementRepository = Mockito.mock(ActorScenePlacementRepository.class);
        service = new ScenePresetService(sceneRepository, placementRepository, new ObjectMapper(), "scene-presets");

        // 场景仓库桩（id=33 属于项目 37）
        when(sceneRepository.findById(anyLong())).thenAnswer(inv -> sceneStore.stream()
                .filter(s -> s.getId().equals(inv.getArgument(0))).findFirst());
        when(sceneRepository.save(any(ActorSandboxScene.class))).thenAnswer(inv -> {
            ActorSandboxScene s = inv.getArgument(0);
            if (s.getId() == null) {
                s.setId((long) (sceneStore.size() + 1));
            }
            sceneStore.removeIf(x -> x.getId().equals(s.getId()));
            sceneStore.add(s);
            return s;
        });

        // 摆放仓库桩（deleteBySceneId 清空 + saveAll 追加）
        Mockito.doAnswer(inv -> {
            placementStore.removeIf(p -> p.getSceneId().equals(inv.getArgument(0)));
            return null;
        }).when(placementRepository).deleteBySceneId(anyLong());
        when(placementRepository.saveAll(any())).thenAnswer(inv -> {
            List<ActorScenePlacement> rows = inv.getArgument(0);
            for (ActorScenePlacement p : rows) {
                if (p.getId() == null) {
                    p.setId((long) (placementStore.size() + 1));
                }
                placementStore.removeIf(x -> x.getId().equals(p.getId()));
                placementStore.add(p);
            }
            return rows;
        });
        when(placementRepository.findBySceneIdOrderBySortOrderAscIdAsc(anyLong())).thenAnswer(inv ->
                placementStore.stream().filter(p -> p.getSceneId().equals(inv.getArgument(0))).toList());
    }

    private ActorSandboxScene newScene(Long id, Long projectId) {
        ActorSandboxScene s = new ActorSandboxScene();
        s.setId(id);
        s.setProjectId(projectId);
        s.setName("青禾村·主场景");
        s.setSceneSize(500);
        s.setStyleCode("hills");
        s.setTemplateCode("village");
        s.setSeed(1L);
        s.setVersion(1);
        s.setLayoutJson("{}");
        return s;
    }

    @Test
    void applyPreset_qingheVillage_writesLayoutAndRebuildsPlacements() {
        sceneStore.add(newScene(SCENE_ID, PROJECT_ID));

        ScenePresetApplyVO vo = service.applyPreset(PROJECT_ID, SCENE_ID, "qinghe-village-v1");

        assertEquals(SCENE_ID, vo.getSceneId());
        assertEquals(500, vo.getSceneSize());
        assertEquals("hills", vo.getStyleCode());
        assertEquals(20260914L, vo.getSeed());
        assertTrue(vo.getPlacementCount() >= 40, "青禾村预设应有足够摆放");
        assertEquals(1, vo.getRiverCount());  // 灵溪河
        assertEquals(3, vo.getRoadCount());   // 主街 + 东街 + 西街
        assertTrue(vo.getAssetIds().contains("building_gate_01"));
        assertTrue(vo.getAssetIds().contains("building_temple_01"));

        // 场景行写回
        ActorSandboxScene saved = sceneStore.get(0);
        assertEquals(2, saved.getVersion());
        assertNotNull(saved.getLayoutJson());
        assertTrue(saved.getLayoutJson().contains("\"rivers\""));
        assertTrue(saved.getLayoutJson().contains("\"roads\""));
        assertTrue(saved.getLayoutJson().contains("\"placements\""));
        assertTrue(saved.getLayoutJson().contains("building_gate_01"));

        // 摆放表重建（先清空再插入）
        verify(placementRepository).deleteBySceneId(SCENE_ID);
        assertEquals(vo.getPlacementCount(), placementStore.size());
        assertEquals(0, placementStore.get(0).getSortOrder());
        assertTrue(placementStore.stream().allMatch(p ->
                p.getProjectId().equals(PROJECT_ID) && p.getSceneId().equals(SCENE_ID)));
    }

    @Test
    void applyPreset_isIdempotent_reapplyKeepsSingleSet() {
        sceneStore.add(newScene(SCENE_ID, PROJECT_ID));

        ScenePresetApplyVO first = service.applyPreset(PROJECT_ID, SCENE_ID, "qinghe-village-v1");
        ScenePresetApplyVO second = service.applyPreset(PROJECT_ID, SCENE_ID, "qinghe-village-v1");

        assertEquals(first.getPlacementCount(), second.getPlacementCount());
        assertEquals(first.getPlacementCount(), placementStore.size(), "重跑不产生重复摆放");
    }

    @Test
    void applyPreset_sceneNotInProject_returns404() {
        sceneStore.add(newScene(SCENE_ID, 99L));
        BizException ex = assertThrows(BizException.class,
                () -> service.applyPreset(PROJECT_ID, SCENE_ID, "qinghe-village-v1"));
        assertEquals(404, ex.getCode());
    }

    @Test
    void applyPreset_unknownPreset_returns404() {
        sceneStore.add(newScene(SCENE_ID, PROJECT_ID));
        BizException ex = assertThrows(BizException.class,
                () -> service.applyPreset(PROJECT_ID, SCENE_ID, "no-such-preset"));
        assertEquals(404, ex.getCode());
    }

    @Test
    void applyPreset_illegalPresetName_returns400() {
        sceneStore.add(newScene(SCENE_ID, PROJECT_ID));
        assertThrows(BizException.class, () -> service.applyPreset(PROJECT_ID, SCENE_ID, "../etc/passwd"));
        assertThrows(BizException.class, () -> service.applyPreset(PROJECT_ID, SCENE_ID, "a/b"));
    }

    @Test
    void applyPreset_invalidSize_returns400() {
        sceneStore.add(newScene(SCENE_ID, PROJECT_ID));
        BizException ex = assertThrows(BizException.class,
                () -> service.applyPreset(PROJECT_ID, SCENE_ID, "invalid-size"));
        assertEquals(400, ex.getCode());
    }

    @Test
    void applyPreset_placementOverflow_returns400() {
        sceneStore.add(newScene(SCENE_ID, PROJECT_ID));
        BizException ex = assertThrows(BizException.class,
                () -> service.applyPreset(PROJECT_ID, SCENE_ID, "invalid-overflow"));
        assertEquals(400, ex.getCode());
    }

    @Test
    void applyPreset_missingAssetId_returns400() {
        sceneStore.add(newScene(SCENE_ID, PROJECT_ID));
        BizException ex = assertThrows(BizException.class,
                () -> service.applyPreset(PROJECT_ID, SCENE_ID, "invalid-missing-asset"));
        assertEquals(400, ex.getCode());
    }

    @Test
    void applyPreset_riverOverflow_returns400() {
        sceneStore.add(newScene(SCENE_ID, PROJECT_ID));
        BizException ex = assertThrows(BizException.class,
                () -> service.applyPreset(PROJECT_ID, SCENE_ID, "invalid-river-overflow"));
        assertEquals(400, ex.getCode());
    }

    @Test
    void applyPreset_qingheVillageV100_writes100mCompactLayout() {
        sceneStore.add(newScene(SCENE_ID, PROJECT_ID));

        ScenePresetApplyVO vo = service.applyPreset(PROJECT_ID, SCENE_ID, "qinghe-village-v100");

        assertEquals(SCENE_ID, vo.getSceneId());
        assertEquals(100, vo.getSceneSize(), "100m 测试规模应合法（尺寸校验已放宽到 100 的整数倍）");
        assertEquals("hills", vo.getStyleCode());
        assertEquals(20260914L, vo.getSeed());
        assertEquals(68, vo.getPlacementCount(), "100m 紧凑布局应有 68 个摆放");
        assertEquals(1, vo.getRiverCount());  // 灵溪河
        assertEquals(4, vo.getRoadCount());   // 主街 + 东街 + 西街 + 山道
        assertTrue(vo.getAssetIds().contains("building_gate_01"));
        assertTrue(vo.getAssetIds().contains("building_temple_01"));
        assertTrue(vo.getAssetIds().contains("building_bridge_01"));
        assertTrue(vo.getAssetIds().contains("building_wharf_01"));

        // 场景行写回：scene_size=100 + layout_json 含摆放
        ActorSandboxScene saved = sceneStore.get(0);
        assertEquals(2, saved.getVersion());
        assertEquals(100, saved.getSceneSize());
        assertTrue(saved.getLayoutJson().contains("\"size\":100"));
        assertTrue(saved.getLayoutJson().contains("building_gate_01"));
        assertTrue(saved.getLayoutJson().contains("building_wharf_01"));

        // 摆放表：坐标全部落在 0..100 内（紧凑村落不允许越界）
        assertEquals(68, placementStore.size());
        assertTrue(placementStore.stream().allMatch(p ->
                p.getPosX() >= 0 && p.getPosX() <= 100 && p.getPosZ() >= 0 && p.getPosZ() <= 100),
                "100m 布局摆放坐标应全部在 0..100 内");
    }

    @Test
    void applyPreset_v100_isIdempotent_reapplyKeepsSingleSet() {
        sceneStore.add(newScene(SCENE_ID, PROJECT_ID));

        ScenePresetApplyVO first = service.applyPreset(PROJECT_ID, SCENE_ID, "qinghe-village-v100");
        ScenePresetApplyVO second = service.applyPreset(PROJECT_ID, SCENE_ID, "qinghe-village-v100");

        assertEquals(first.getPlacementCount(), second.getPlacementCount());
        assertEquals(first.getPlacementCount(), placementStore.size(), "重跑不产生重复摆放");
    }

    @Test
    void listPresets_containsQingheVillage() {
        List<Map<String, Object>> presets = service.listPresets();
        assertTrue(presets.stream().anyMatch(p -> "qinghe-village-v1".equals(p.get("name"))),
                "内置预设应包含青禾村（清单：" + presets + "）");
    }
}
