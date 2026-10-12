package com.holzyn.actor.domain.sandbox.service;

import tools.jackson.databind.ObjectMapper;
import com.holzyn.actor.common.BizException;
import com.holzyn.actor.domain.sandbox.entity.ActorSceneAsset;
import com.holzyn.actor.domain.sandbox.repository.ActorSceneAssetRepository;
import com.holzyn.actor.domain.sandbox.vo.SceneAssetImportVO;
import com.holzyn.actor.domain.sandbox.vo.ScenePackVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * 资产数据包服务单元测试（世界沙盘 S3 补全，domain/sandbox/service，同包）。
 * <p>职责：验证——manifest → actor_sandbox_asset 导入 upsert（新建/更新计数、字段映射、
 * GLB URL 拼装、幂等重跑）、packId 合法性（防路径穿越）、数据包缺失/非法 JSON 的 400/404、
 * 数据包扫描列表。</p>
 * <p>设计依据：主方案 §8.6 资产数据包规范（目录即包，manifest 权威）+ SC1 资产管线。</p>
 */
class SceneAssetServiceTest {

    private ActorSceneAssetRepository assetRepository;
    private SceneAssetService service;
    private Path uploadDir;
    private final List<ActorSceneAsset> assetStore = new ArrayList<>();

    @BeforeEach
    void setUp() throws IOException {
        uploadDir = Files.createTempDirectory("scene-assets-test");
        assetRepository = Mockito.mock(ActorSceneAssetRepository.class);
        service = new SceneAssetService(assetRepository, new ObjectMapper(), uploadDir.toString());

        // 内存仓库桩：按 project+pack+asset 查（新建判定）
        when(assetRepository.findFirstByProjectIdAndPackIdAndAssetId(anyLong(), anyString(), anyString()))
                .thenAnswer(inv -> assetStore.stream()
                        .filter(a -> a.getProjectId().equals(inv.getArgument(0)))
                        .filter(a -> a.getPackId().equals(inv.getArgument(1)))
                        .filter(a -> a.getAssetId().equals(inv.getArgument(2)))
                        .findFirst());
        when(assetRepository.save(any(ActorSceneAsset.class))).thenAnswer(inv -> {
            ActorSceneAsset a = inv.getArgument(0);
            if (a.getId() == null) {
                a.setId((long) (assetStore.size() + 1));
            }
            assetStore.removeIf(x -> x.getId().equals(a.getId()));
            assetStore.add(a);
            return a;
        });
        when(assetRepository.findByPackIdOrderByIdAsc(anyString())).thenAnswer(inv ->
                assetStore.stream().filter(a -> a.getPackId().equals(inv.getArgument(0))).toList());
        when(assetRepository.findByProjectIdInOrderByIdAsc(any())).thenAnswer(inv -> assetStore);
    }

    @AfterEach
    void tearDown() throws IOException {
        try {
            Files.walk(uploadDir).sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                }
            });
        } catch (IOException ignored) {
        }
    }

    /** 写入测试数据包（目录 + manifest.json，UTF-8） */
    private void writePack(String packId, String manifestJson) throws IOException {
        Path dir = uploadDir.resolve("scene-assets").resolve(packId);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("manifest.json"), manifestJson, StandardCharsets.UTF_8);
    }

    private static final String VALID_MANIFEST = """
            {
              "pack": { "id": "test-pack", "name": "测试包", "version": "1.0.0", "author": "t", "unit": "meter" },
              "assets": [
                { "asset_id": "building_inn_01", "name": "客栈", "category": "building_commercial",
                  "description": "测试建筑", "size": {"length":8,"width":6,"height":6},
                  "footprint": {"length":8,"width":6}, "style": ["国风"], "tags": ["客栈","可进入"],
                  "file": "models/building_inn_01.glb", "compressed": true, "preview": "preview/building_inn_01.png" },
                { "asset_id": "plant_pine_01", "name": "苍松", "category": "plant",
                  "file": "models/plant_pine_01.glb" }
              ]
            }
            """;

    @Test
    void importPack_createsAllAssets_andMapsFields() throws IOException {
        writePack("test-pack", VALID_MANIFEST);

        SceneAssetImportVO vo = service.importPack("test-pack");

        assertEquals(2, vo.getTotal());
        assertEquals(2, vo.getRegistered());
        assertEquals(0, vo.getUpdated());
        assertEquals("测试包", vo.getPackName());
        assertEquals(2, assetStore.size());

        ActorSceneAsset inn = assetStore.stream()
                .filter(a -> a.getAssetId().equals("building_inn_01")).findFirst().orElseThrow();
        assertEquals(0L, inn.getProjectId()); // 全局内置包
        assertEquals("test-pack", inn.getPackId());
        assertEquals("客栈", inn.getName());
        assertEquals("building_commercial", inn.getCategory());
        assertEquals("uploads/scene-assets/test-pack/models/building_inn_01.glb", inn.getFilePath());
        assertEquals("uploads/scene-assets/test-pack/preview/building_inn_01.png", inn.getPreview());
        assertEquals(Boolean.TRUE, inn.getCompressed());
        assertTrue(inn.getSizeJson().contains("\"length\""));
        assertTrue(inn.getFootprintJson().contains("\"width\""));

        ActorSceneAsset pine = assetStore.stream()
                .filter(a -> a.getAssetId().equals("plant_pine_01")).findFirst().orElseThrow();
        assertEquals("uploads/scene-assets/test-pack/models/plant_pine_01.glb", pine.getFilePath());
        assertEquals(Boolean.FALSE, pine.getCompressed()); // 缺省 false
    }

    @Test
    void importPack_isIdempotent_reImportUpdatesNotDuplicates() throws IOException {
        writePack("test-pack", VALID_MANIFEST);
        service.importPack("test-pack");
        assertEquals(2, assetStore.size());

        SceneAssetImportVO second = service.importPack("test-pack");

        assertEquals(0, second.getRegistered());
        assertEquals(2, second.getUpdated());
        assertEquals(2, assetStore.size()); // 不重复
    }

    @Test
    void importPack_rejectsInvalidPackId() {
        assertThrows(BizException.class, () -> service.importPack("../evil"));
        assertThrows(BizException.class, () -> service.importPack("a/b"));
        assertThrows(BizException.class, () -> service.importPack(null));
    }

    @Test
    void importPack_missingPack_returns404() {
        BizException ex = assertThrows(BizException.class, () -> service.importPack("no-such-pack"));
        assertEquals(404, ex.getCode());
    }

    @Test
    void importPack_invalidManifestJson_returns400() throws IOException {
        writePack("bad-pack", "{ not valid json ");
        BizException ex = assertThrows(BizException.class, () -> service.importPack("bad-pack"));
        assertEquals(400, ex.getCode());
    }

    @Test
    void listPacks_scansDirectories() throws IOException {
        writePack("pack-a", VALID_MANIFEST.replace("test-pack", "pack-a"));
        writePack("pack-b", VALID_MANIFEST.replace("test-pack", "pack-b"));
        // 无 manifest 的目录应被忽略
        Files.createDirectories(uploadDir.resolve("scene-assets").resolve("empty-dir"));

        List<ScenePackVO> packs = service.listPacks();

        assertEquals(2, packs.size());
        assertEquals("pack-a", packs.get(0).getPackId());
        assertEquals("测试包", packs.get(0).getName());
        assertEquals(2, packs.get(0).getAssetCount());
    }

    @Test
    void listAssets_filtersByPackId() throws IOException {
        writePack("test-pack", VALID_MANIFEST);
        service.importPack("test-pack");

        assertEquals(2, service.listAssets(37L, null).size());
        assertEquals(2, service.listAssets(37L, "test-pack").size());
        assertEquals(0, service.listAssets(37L, "other-pack").size());
    }
}
