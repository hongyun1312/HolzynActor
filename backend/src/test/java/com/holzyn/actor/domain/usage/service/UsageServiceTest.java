package com.holzyn.actor.domain.usage.service;

import com.holzyn.actor.domain.usage.entity.ActorUsageLog;
import com.holzyn.actor.domain.usage.service.UsageService;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * UsageService 聚合逻辑单元测试（P2 阶段三）。
 * <p>职责：验证 summary 汇总与 groupBy 分组聚合的正确性（不依赖数据库）。</p>
 */
class UsageServiceTest {

    /**
     * 汇总：调用次数 / 输入输出 token / 耗时累加正确。
     */
    @Test
    void summaryAggregatesCorrectly() {
        ActorUsageLog a = new ActorUsageLog();
        a.setTokenIn(10); a.setTokenOut(20); a.setDurationMs(100);
        a.setCacheHitTokens(40); a.setCacheMissTokens(60);
        ActorUsageLog b = new ActorUsageLog();
        b.setTokenIn(30); b.setTokenOut(40); b.setDurationMs(200);
        b.setCacheHitTokens(100); b.setCacheMissTokens(200);
        Map<String, Object> s = UsageService.summary(List.of(a, b));
        assertEquals(2, s.get("count"));
        assertEquals(40, s.get("tokenIn"));
        assertEquals(60, s.get("tokenOut"));
        assertEquals(140, s.get("cacheHit"));
        assertEquals(260, s.get("cacheMiss"));
        assertEquals(35.0, (double) s.get("cacheHitRate"), 0.001);
        assertEquals(300, s.get("durationMs"));
    }

    /**
     * 缓存命中率：无缓存数据时返回 0，避免除零。
     */
    @Test
    void cacheHitRateBoundary() {
        assertEquals(0.0, UsageService.cacheHitRate(0, 0), 0.001);
        assertEquals(36.8, UsageService.cacheHitRate(140_000, 240_000), 0.001);
        assertEquals(100.0, UsageService.cacheHitRate(10, 0), 0.001);
        assertEquals(0.0, UsageService.cacheHitRate(0, 10), 0.001);
    }

    /**
     * 分组：按场景聚合（count / token 累加），并按次数降序。
     */
    @Test
    void groupBySceneWorks() {
        ActorUsageLog a = new ActorUsageLog();
        a.setScene("dialog"); a.setTokenIn(10); a.setTokenOut(5);
        ActorUsageLog b = new ActorUsageLog();
        b.setScene("dialog"); b.setTokenIn(20); b.setTokenOut(10);
        ActorUsageLog c = new ActorUsageLog();
        c.setScene("action"); c.setTokenIn(1); c.setTokenOut(1);
        List<Map<String, Object>> groups = UsageService.groupBy(List.of(a, b, c),
                l -> l.getScene(), s -> s);
        assertEquals(2, groups.size());
        Map<String, Object> dialog = groups.get(0);
        assertEquals("dialog", dialog.get("key"));
        assertEquals(2, dialog.get("count"));
        assertEquals(30, dialog.get("tokenIn"));
        assertEquals(15, dialog.get("tokenOut"));
    }

    /**
     * 场景中文标签：既有/新增记录场景全部有可读标签（用量页「按场景」展示）。
     * <p>2026-08-18 补记：角色卡生成此前未记录用量；embedding/title_gen/location_extract/memory/import
     * 等场景此前缺标签会显示原始编码。</p>
     */
    @Test
    void sceneNameLabelsCoverAllRecordedScenes() {
        assertEquals("角色卡生成", UsageService.sceneName("card_gen"));
        assertEquals("对话", UsageService.sceneName("dialog"));
        assertEquals("行动", UsageService.sceneName("action"));
        assertEquals("人群", UsageService.sceneName("crowd"));
        assertEquals("标题生成", UsageService.sceneName("title_gen"));
        assertEquals("地点提取", UsageService.sceneName("location_extract"));
        assertEquals("记忆抽取", UsageService.sceneName("memory"));
        assertEquals("导入解析", UsageService.sceneName("import"));
        assertEquals("向量化（RAG）", UsageService.sceneName("embedding"));
        // 未知场景回退原始编码
        assertEquals("unknown_scene", UsageService.sceneName("unknown_scene"));
        assertEquals("unknown", UsageService.sceneName(null));
    }

    /**
     * 明细行：保留原字段并补充 characterName（角色名）与 sceneName（场景中文名）。
     * <p>2026-08-18 增强：用量明细不再直接展示 characterId 数字与 scene 编码。</p>
     */
    @Test
    void detailRowAddsCharacterNameAndSceneName() {
        ActorUsageLog l = new ActorUsageLog();
        l.setId(5L);
        l.setCharacterId(3L);
        l.setScene("card_gen");
        l.setTokenIn(10);
        l.setTokenOut(20);
        l.setDurationMs(100);
        l.setCreatedAt(LocalDateTime.of(2026, 8, 18, 10, 30, 0));
        Map<String, Object> row = UsageService.detailRow(l, "示例角色", "角色卡生成");
        // 原字段保留
        assertEquals(5L, row.get("id"));
        assertEquals(3L, row.get("characterId"));
        assertEquals("card_gen", row.get("scene"));
        assertEquals(10, row.get("tokenIn"));
        assertEquals(20, row.get("tokenOut"));
        assertEquals(100, row.get("durationMs"));
        // 新增可读字段
        assertEquals("示例角色", row.get("characterName"));
        assertEquals("角色卡生成", row.get("sceneName"));
    }
}