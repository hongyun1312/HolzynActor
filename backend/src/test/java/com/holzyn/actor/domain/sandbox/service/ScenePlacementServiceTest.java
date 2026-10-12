package com.holzyn.actor.domain.sandbox.service;

import com.holzyn.actor.common.BizException;
import com.holzyn.actor.domain.sandbox.entity.ActorSandboxScene;
import com.holzyn.actor.domain.sandbox.entity.ActorSceneAsset;
import com.holzyn.actor.domain.sandbox.entity.ActorScenePlacement;
import com.holzyn.actor.domain.sandbox.repository.ActorSandboxSceneRepository;
import com.holzyn.actor.domain.sandbox.repository.ActorSceneAssetRepository;
import com.holzyn.actor.domain.sandbox.repository.ActorScenePlacementRepository;
import com.holzyn.actor.domain.sandbox.vo.ScenePlacementVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 资产摆放服务单元测试（世界沙盘 S3，domain/sandbox/service，同包）。
 * <p>职责：验证——创建（assetId 必填、默认值填充）、按场景列表、更新（仅覆盖非空字段）、
 * 删除、按项目聚合、场景/摆放归属校验（404）、资产登记表信息解析（名称/分类/GLB 路径）。</p>
 * <p>设计依据：S3 摆放表方案（actor_scene_placement，复用 actor_sandbox_scene +
 * actor_sandbox_state）。</p>
 */
class ScenePlacementServiceTest {

    private ActorScenePlacementRepository placementRepository;
    private ActorSandboxSceneRepository sceneRepository;
    private ActorSceneAssetRepository assetRepository;
    private ScenePlacementService service;

    /** 内存摆放仓库模拟 */
    private final List<ActorScenePlacement> placementStore = new ArrayList<>();
    /** 内存场景仓库模拟 */
    private final List<ActorSandboxScene> sceneStore = new ArrayList<>();
    /** 内存资产登记仓库模拟 */
    private final List<ActorSceneAsset> assetStore = new ArrayList<>();

    @BeforeEach
    void setUp() {
        placementRepository = Mockito.mock(ActorScenePlacementRepository.class);
        sceneRepository = Mockito.mock(ActorSandboxSceneRepository.class);
        assetRepository = Mockito.mock(ActorSceneAssetRepository.class);
        service = new ScenePlacementService(placementRepository, sceneRepository, assetRepository);

        when(placementRepository.save(any(ActorScenePlacement.class))).thenAnswer(inv -> {
            ActorScenePlacement p = inv.getArgument(0);
            if (p.getId() == null) {
                p.setId((long) (placementStore.size() + 1));
            }
            placementStore.removeIf(x -> x.getId().equals(p.getId()));
            placementStore.add(p);
            return p;
        });
        Mockito.doAnswer(inv -> {
            placementStore.remove(inv.getArgument(0));
            return null;
        }).when(placementRepository).delete(any(ActorScenePlacement.class));
        when(placementRepository.findById(anyLong()))
                .thenAnswer(inv -> placementStore.stream()
                        .filter(p -> p.getId().equals(inv.getArgument(0))).findFirst());
        when(placementRepository.findBySceneIdOrderBySortOrderAscIdAsc(anyLong()))
                .thenAnswer(inv -> placementStore.stream()
                        .filter(p -> p.getSceneId().equals(inv.getArgument(0)))
                        .sorted(java.util.Comparator
                                .comparing(ActorScenePlacement::getSortOrder,
                                        java.util.Comparator.nullsLast(Integer::compareTo))
                                .thenComparing(ActorScenePlacement::getId))
                        .toList());
        when(placementRepository.findByProjectIdOrderBySceneIdAscSortOrderAscIdAsc(anyLong()))
                .thenAnswer(inv -> placementStore.stream()
                        .filter(p -> p.getProjectId().equals(inv.getArgument(0)))
                        .sorted(java.util.Comparator
                                .comparing(ActorScenePlacement::getSceneId)
                                .thenComparing(ActorScenePlacement::getSortOrder,
                                        java.util.Comparator.nullsLast(Integer::compareTo))
                                .thenComparing(ActorScenePlacement::getId))
                        .toList());

        when(sceneRepository.findById(anyLong()))
                .thenAnswer(inv -> sceneStore.stream()
                        .filter(s -> s.getId().equals(inv.getArgument(0))).findFirst());
        when(assetRepository.findFirstByProjectIdInAndAssetId(any(), any()))
                .thenAnswer(inv -> assetStore.stream()
                        .filter(a -> a.getAssetId().equals(inv.getArgument(1))
                                && ((List<?>) inv.getArgument(0)).contains(a.getProjectId()))
                        .findFirst());
    }

    /** 造一个场景（入库） */
    private ActorSandboxScene scene(Long id, Long projectId) {
        ActorSandboxScene s = new ActorSandboxScene();
        s.setId(id);
        s.setProjectId(projectId);
        s.setName("测试场景");
        s.setSceneSize(500);
        sceneStore.add(s);
        return s;
    }

    /** 造一条资产登记（入库） */
    private ActorSceneAsset asset(Long id, Long projectId, String assetId, String filePath) {
        ActorSceneAsset a = new ActorSceneAsset();
        a.setId(id);
        a.setProjectId(projectId);
        a.setAssetId(assetId);
        a.setName("资产" + assetId);
        a.setCategory("building_public");
        a.setFilePath(filePath);
        assetStore.add(a);
        return a;
    }

    // ==================== 创建 ====================

    @Test
    void create_requiresAssetId() {
        scene(1L, 100L);
        BizException ex = assertThrows(BizException.class,
                () -> service.create(100L, 1L, Map.of()));
        assertEquals(400, ex.getCode());
    }

    @Test
    void create_persistsAndFillsDefaults() {
        scene(1L, 100L);
        ScenePlacementVO vo = service.create(100L, 1L, Map.of(
                "assetId", "building_inn_01",
                "posX", 250.5, "posZ", 120.0, "rotY", 1.57, "sortOrder", 2));
        assertNotNull(vo.getId(), "保存后必须有 ID");
        assertEquals(100L, vo.getProjectId());
        assertEquals(1L, vo.getSceneId());
        assertEquals("building_inn_01", vo.getAssetId());
        assertEquals(250.5, vo.getPosX());
        assertEquals(120.0, vo.getPosZ());
        assertEquals(0d, vo.getPosY(), "posY 缺省为地面高度 0");
        assertEquals(1d, vo.getScaleX(), "缩放缺省 1");
        assertEquals(1d, vo.getScaleY());
        assertEquals(1d, vo.getScaleZ());
        assertEquals(0d, vo.getRotX(), "朝向缺省 0");
        assertEquals(1.57, vo.getRotY());
        assertEquals(0d, vo.getRotZ());
        assertEquals(2, vo.getSortOrder());
    }

    // ==================== 列表 ====================

    @Test
    void list_returnsOnlyThatSceneSortedBySortOrder() {
        scene(1L, 100L);
        scene(2L, 100L);
        service.create(100L, 1L, Map.of("assetId", "a", "sortOrder", 2));
        service.create(100L, 1L, Map.of("assetId", "b", "sortOrder", 0));
        service.create(100L, 2L, Map.of("assetId", "c"));
        List<ScenePlacementVO> list = service.list(100L, 1L);
        assertEquals(2, list.size(), "只返回场景 1 的摆放");
        assertEquals("b", list.get(0).getAssetId(), "按 sortOrder 升序");
        assertEquals("a", list.get(1).getAssetId());
    }

    @Test
    void list_rejectsSceneOfAnotherProject() {
        scene(1L, 100L);
        BizException ex = assertThrows(BizException.class, () -> service.list(999L, 1L));
        assertEquals(404, ex.getCode());
    }

    // ==================== 更新 ====================

    @Test
    void update_appliesOnlyNonNullFields() {
        scene(1L, 100L);
        ScenePlacementVO created = service.create(100L, 1L, Map.of(
                "assetId", "tree_oak_01", "posX", 10.0, "posZ", 20.0));
        ScenePlacementVO updated = service.update(100L, created.getId(),
                Map.of("posZ", 88.0, "scaleY", 2.0));
        assertEquals(10.0, updated.getPosX(), "未传字段保持原值");
        assertEquals(88.0, updated.getPosZ(), "传了字段被覆盖");
        assertEquals(2.0, updated.getScaleY());
        assertEquals(1d, updated.getScaleX(), "其余默认值不变");
        assertEquals("tree_oak_01", updated.getAssetId());
    }

    @Test
    void update_rejectsPlacementOfAnotherProject() {
        scene(1L, 100L);
        ScenePlacementVO created = service.create(100L, 1L, Map.of("assetId", "x"));
        BizException ex = assertThrows(BizException.class,
                () -> service.update(999L, created.getId(), Map.of("name", "n")));
        assertEquals(404, ex.getCode());
    }

    // ==================== 删除 ====================

    @Test
    void delete_removesPlacement() {
        scene(1L, 100L);
        ScenePlacementVO created = service.create(100L, 1L, Map.of("assetId", "x"));
        service.delete(100L, created.getId());
        verify(placementRepository).delete(any(ActorScenePlacement.class));
        assertEquals(0, service.list(100L, 1L).size(), "删除后列表为空");
    }

    // ==================== 项目聚合 + 资产解析 ====================

    @Test
    void listByProject_aggregatesAcrossScenes() {
        scene(1L, 100L);
        scene(2L, 100L);
        service.create(100L, 1L, Map.of("assetId", "a"));
        service.create(100L, 2L, Map.of("assetId", "b"));
        List<ScenePlacementVO> all = service.listByProject(100L);
        assertEquals(2, all.size(), "跨场景聚合全部摆放");
    }

    @Test
    void toVO_resolvesAssetCatalogInfo() {
        scene(1L, 100L);
        asset(1L, 0L, "building_inn_01", "/assets/glb/inn.glb");
        ScenePlacementVO vo = service.create(100L, 1L, Map.of("assetId", "building_inn_01"));
        assertEquals("资产building_inn_01", vo.getAssetName(), "解析资产登记名");
        assertEquals("building_public", vo.getCategory());
        assertEquals("/assets/glb/inn.glb", vo.getFilePath(), "GLB 路径即前端加载 URL");
    }

    @Test
    void toVO_keepsNullAssetInfoWhenNotRegistered() {
        scene(1L, 100L);
        ScenePlacementVO vo = service.create(100L, 1L, Map.of("assetId", "custom_01"));
        assertNull(vo.getAssetName(), "登记表无记录时资产信息为空（自定义标识仍可摆放）");
        assertNull(vo.getFilePath());
    }
}
