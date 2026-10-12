package com.holzyn.actor.domain.knowledge.service;

import com.holzyn.actor.domain.knowledge.entity.ActorKnowledgeDoc;
import com.holzyn.actor.domain.knowledge.repository.ActorKnowledgeDocRepository;
import com.holzyn.actor.domain.project.entity.ActorProject;
import com.holzyn.actor.domain.project.repository.ActorProjectRepository;
import com.holzyn.actor.domain.world.service.WorldClockService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 知识层生命周期（P1 ②-2）单元测试。
 * <p>覆盖：四态状态机判定（hot/warm/stale/archived 阈值边界与保护）；定期扫描迁移
 * （按游戏日差：无访问→逐级衰减；archived 不自动迁移；无时钟跳过不误衰减）；
 * 引用率驱动（recordAccess 计数+时间刷新+stale 复活）；置信度衰减/提升；
 * 手动归档/还原；开关门控。</p>
 * <p>装配：仓库 mock 直接 new 构造（构造器=仓库）；worldClock 字段注入
 * {@code ReflectionTestUtils.setField}——gameHourAt 按「真实天数差 × 24 小时」换算
 * （当前游戏日=101，基准游戏日=100 - N 天 × 1 日），直观驱动阈值。</p>
 */
class KnowledgeLifecycleServiceTest {

    private static final long PROJECT_ID = 100L;

    private final ActorKnowledgeDocRepository docRepo = mock(ActorKnowledgeDocRepository.class);
    private final ActorProjectRepository projectRepo = mock(ActorProjectRepository.class);
    private final WorldClockService clock = mock(WorldClockService.class);
    private final KnowledgeLifecycleService service = new KnowledgeLifecycleService(docRepo, projectRepo);

    /** 当前游戏日基准：Day101 0 点（last_game_second=101×86400）。 */
    private static final long NOW_GAME_DAY = 101L;

    private void stubClock() {
        // 当前引擎秒 → Day101 0 点；速率 4（游戏秒/真实秒）——换算：真实 N 天 = N×4 游戏日
        when(clock.currentEngineGameSecond(PROJECT_ID)).thenReturn(NOW_GAME_DAY * 86400L);
        when(clock.rateOf(PROJECT_ID)).thenReturn(4);
        ReflectionTestUtils.setField(service, "worldClockService", clock);
    }

    private ActorKnowledgeDoc doc(Long id, String state, LocalDateTime lastAccessAt, Double confidence, Integer accessCount) {
        ActorKnowledgeDoc d = new ActorKnowledgeDoc();
        d.setId(id);
        d.setProjectId(PROJECT_ID);
        d.setTitle("知识-" + id);
        d.setContent("内容-" + id);
        d.setLifecycleState(state);
        d.setConfidence(confidence);
        d.setAccessCount(accessCount);
        d.setLastAccessAt(lastAccessAt);
        d.setCreatedAt(LocalDateTime.now().minusDays(30));
        return d;
    }

    // ==================== 状态判定纯逻辑 ====================

    @Test
    void evaluateStateBoundaries() {
        // 默认阈值 3/7/14
        assertEquals(KnowledgeLifecycleService.STATE_HOT, KnowledgeLifecycleService.evaluateState(0, 3, 7, 14));
        assertEquals(KnowledgeLifecycleService.STATE_HOT, KnowledgeLifecycleService.evaluateState(2, 3, 7, 14));
        assertEquals(KnowledgeLifecycleService.STATE_WARM, KnowledgeLifecycleService.evaluateState(3, 3, 7, 14));
        assertEquals(KnowledgeLifecycleService.STATE_WARM, KnowledgeLifecycleService.evaluateState(6, 3, 7, 14));
        assertEquals(KnowledgeLifecycleService.STATE_STALE, KnowledgeLifecycleService.evaluateState(7, 3, 7, 14));
        assertEquals(KnowledgeLifecycleService.STATE_STALE, KnowledgeLifecycleService.evaluateState(13, 3, 7, 14));
        assertEquals(KnowledgeLifecycleService.STATE_ARCHIVED, KnowledgeLifecycleService.evaluateState(14, 3, 7, 14));
        assertEquals(KnowledgeLifecycleService.STATE_ARCHIVED, KnowledgeLifecycleService.evaluateState(99, 3, 7, 14));
    }

    /** 阈值保护：非递增/非法阈值强制递增（h+1/s+1 单调，防负窗口误迁移）。 */
    @Test
    void evaluateStateProtectsThresholds() {
        // (3,3,3) → 保护为 h=3, s=4, a=5
        assertEquals(KnowledgeLifecycleService.STATE_HOT, KnowledgeLifecycleService.evaluateState(2, 3, 3, 3));
        assertEquals(KnowledgeLifecycleService.STATE_WARM, KnowledgeLifecycleService.evaluateState(3, 3, 3, 3));
        assertEquals(KnowledgeLifecycleService.STATE_STALE, KnowledgeLifecycleService.evaluateState(4, 3, 3, 3));
        assertEquals(KnowledgeLifecycleService.STATE_ARCHIVED, KnowledgeLifecycleService.evaluateState(5, 3, 3, 3));
        // (0,0,0) → 保护为 h=1, s=2, a=3
        assertEquals(KnowledgeLifecycleService.STATE_HOT, KnowledgeLifecycleService.evaluateState(0, 0, 0, 0));
        assertEquals(KnowledgeLifecycleService.STATE_WARM, KnowledgeLifecycleService.evaluateState(1, 0, 0, 0));
        assertEquals(KnowledgeLifecycleService.STATE_STALE, KnowledgeLifecycleService.evaluateState(2, 0, 0, 0));
        assertEquals(KnowledgeLifecycleService.STATE_ARCHIVED, KnowledgeLifecycleService.evaluateState(3, 0, 0, 0));
    }

    /** 置信度提升（桥接确认/访问复活/还原）上限 1.0；衰减下限 0.1。 */
    @Test
    void confidenceBounds() {
        assertEquals(1.0, KnowledgeLifecycleService.boostConfidence(0.95), 1e-9);
        assertEquals(0.6, KnowledgeLifecycleService.boostConfidence(0.5), 1e-9);
    }

    // ==================== 引用率驱动（recordAccess） ====================

    /** 命中即引用：access_count+1、last_access_at 刷新。 */
    @Test
    void recordAccessCountsAndRefreshes() {
        ActorKnowledgeDoc d = doc(1L, KnowledgeLifecycleService.STATE_HOT, LocalDateTime.now().minusDays(10), 0.5, 3);
        when(docRepo.findAllById(List.of(1L))).thenReturn(List.of(d));
        stubClock();

        int n = service.recordAccess(PROJECT_ID, List.of(1L));

        assertEquals(1, n);
        assertEquals(4, d.getAccessCount(), "计数 +1");
        assertNotNull(d.getLastAccessAt(), "时间刷新");
        assertEquals(KnowledgeLifecycleService.STATE_HOT, d.getLifecycleState(), "hot 保持");
    }

    /** 访问复活：stale 被再次引用 → warm + 置信度提升。 */
    @Test
    void recordAccessRevivesStale() {
        ActorKnowledgeDoc d = doc(2L, KnowledgeLifecycleService.STATE_STALE, LocalDateTime.now().minusDays(20), 0.2, 0);
        when(docRepo.findAllById(List.of(2L))).thenReturn(List.of(d));
        stubClock();

        service.recordAccess(PROJECT_ID, List.of(2L));

        assertEquals(KnowledgeLifecycleService.STATE_WARM, d.getLifecycleState(), "stale→warm 复活");
        assertEquals(0.3, d.getConfidence(), 1e-9, "置信度提升");
    }

    /** 跨项目 docId 不计数（归属校验）。 */
    @Test
    void recordAccessIgnoresOtherProject() {
        ActorKnowledgeDoc d = doc(3L, KnowledgeLifecycleService.STATE_HOT, LocalDateTime.now(), 0.5, 0);
        d.setProjectId(999L);
        when(docRepo.findAllById(List.of(3L))).thenReturn(List.of(d));
        stubClock();

        assertEquals(0, service.recordAccess(PROJECT_ID, List.of(3L)), "非本项目不计");
        assertEquals(0, d.getAccessCount());
    }

    // ==================== 衰减扫描（scanProject） ====================

    /** 全部区间（rate=4：真实 1 天=4 游戏日）：0 天=hot 保持；1 天=hot→warm；2 天=warm→stale；4 天=stale→archived。 */
    @Test
    void scanProjectMigratesAllLevels() {
        ActorKnowledgeDoc hot = doc(1L, KnowledgeLifecycleService.STATE_HOT, LocalDateTime.now(), 0.6, 5);
        ActorKnowledgeDoc toWarm = doc(2L, KnowledgeLifecycleService.STATE_HOT, LocalDateTime.now().minusDays(1), 0.5, 2);
        ActorKnowledgeDoc toStale = doc(3L, KnowledgeLifecycleService.STATE_WARM, LocalDateTime.now().minusDays(2), 0.4, 1);
        ActorKnowledgeDoc toArchived = doc(4L, KnowledgeLifecycleService.STATE_STALE, LocalDateTime.now().minusDays(4), 0.2, 0);
        when(docRepo.findByProjectIdOrderByIdAsc(PROJECT_ID)).thenReturn(List.of(hot, toWarm, toStale, toArchived));
        stubClock();

        KnowledgeLifecycleService.ScanResult r = service.scanProject(PROJECT_ID);

        assertEquals(4, r.evaluated());
        assertEquals(3, r.migrated());
        assertEquals(KnowledgeLifecycleService.STATE_HOT, hot.getLifecycleState(), "近期访问保持 hot");
        assertEquals(KnowledgeLifecycleService.STATE_WARM, toWarm.getLifecycleState(), "hot→warm");
        assertEquals(KnowledgeLifecycleService.STATE_STALE, toStale.getLifecycleState(), "warm→stale");
        assertEquals(KnowledgeLifecycleService.STATE_ARCHIVED, toArchived.getLifecycleState(), "stale→archived");
        assertEquals(1, r.byState().getOrDefault(KnowledgeLifecycleService.STATE_WARM, 0));
        assertEquals(1, r.byState().getOrDefault(KnowledgeLifecycleService.STATE_STALE, 0));
        assertEquals(1, r.byState().getOrDefault(KnowledgeLifecycleService.STATE_ARCHIVED, 0));
    }

    /** 衰减伴随置信度递减（每级 -0.1，下限 0.1）。 */
    @Test
    void scanProjectDecaysConfidence() {
        ActorKnowledgeDoc toArchived = doc(4L, KnowledgeLifecycleService.STATE_STALE, LocalDateTime.now().minusDays(4), 0.15, 0);
        when(docRepo.findByProjectIdOrderByIdAsc(PROJECT_ID)).thenReturn(List.of(toArchived));
        stubClock();

        service.scanProject(PROJECT_ID);

        assertEquals(KnowledgeLifecycleService.STATE_ARCHIVED, toArchived.getLifecycleState());
        assertEquals(0.1, toArchived.getConfidence(), 1e-9, "衰减至下限 0.1");
    }

    /** archived 不自动迁移（还原走手动端点）。 */
    @Test
    void scanProjectSkipsArchived() {
        ActorKnowledgeDoc archived = doc(5L, KnowledgeLifecycleService.STATE_ARCHIVED, LocalDateTime.now().minusDays(99), 0.1, 0);
        when(docRepo.findByProjectIdOrderByIdAsc(PROJECT_ID)).thenReturn(List.of(archived));
        stubClock();

        KnowledgeLifecycleService.ScanResult r = service.scanProject(PROJECT_ID);

        assertEquals(0, r.evaluated(), "archived 不参与评估");
        assertEquals(0, r.migrated());
        assertEquals(KnowledgeLifecycleService.STATE_ARCHIVED, archived.getLifecycleState());
    }

    /** 从未被引用（last_access_at null + access_count 0）→ 不衰减（保持 hot，防存量文档批量误归档）。 */
    @Test
    void scanProjectNeverAccessedStaysHot() {
        ActorKnowledgeDoc neverUsed = doc(9L, KnowledgeLifecycleService.STATE_HOT, null, 0.5, 0);
        neverUsed.setCreatedAt(LocalDateTime.now().minusDays(60)); // 即使创建很久
        when(docRepo.findByProjectIdOrderByIdAsc(PROJECT_ID)).thenReturn(List.of(neverUsed));
        stubClock();

        KnowledgeLifecycleService.ScanResult r = service.scanProject(PROJECT_ID);

        assertEquals(1, r.evaluated());
        assertEquals(0, r.migrated(), "从未被引用不衰减");
        assertEquals(KnowledgeLifecycleService.STATE_HOT, neverUsed.getLifecycleState());
    }

    /** 世界时钟缺失（未装配）→ 全部跳过（不误衰减）。 */
    @Test
    void scanProjectWithoutClockSkipsAll() {
        ActorKnowledgeDoc d = doc(6L, KnowledgeLifecycleService.STATE_HOT, LocalDateTime.now().minusDays(30), 0.5, 0);
        when(docRepo.findByProjectIdOrderByIdAsc(PROJECT_ID)).thenReturn(List.of(d));
        // 不注入 clock → currentGameDay null → 跳过

        KnowledgeLifecycleService.ScanResult r = service.scanProject(PROJECT_ID);

        assertEquals(0, r.evaluated());
        assertEquals(1, r.skipped());
        assertEquals(KnowledgeLifecycleService.STATE_HOT, d.getLifecycleState(), "时钟缺失不迁移");
    }

    /** 开关关 → 扫描零操作。 */
    @Test
    void scanProjectDisabledReturnsEmpty() {
        ReflectionTestUtils.setField(service, "enabled", false);
        stubClock();
        when(docRepo.findByProjectIdOrderByIdAsc(PROJECT_ID)).thenReturn(List.of(
                doc(7L, KnowledgeLifecycleService.STATE_HOT, LocalDateTime.now().minusDays(99), 0.5, 0)));

        KnowledgeLifecycleService.ScanResult r = service.scanProject(PROJECT_ID);

        assertEquals(0, r.evaluated());
        assertEquals(0, r.migrated());
    }

    // ==================== 全项目扫描 / 手动归档还原 ====================

    /** 全项目扫描：遍历所有项目。 */
    @Test
    void scanAllProjectsIteratesProjects() {
        ActorProject p = new ActorProject();
        p.setId(PROJECT_ID);
        when(projectRepo.findAll()).thenReturn(List.of(p));
        when(docRepo.findByProjectIdOrderByIdAsc(PROJECT_ID)).thenReturn(List.of(
                doc(1L, KnowledgeLifecycleService.STATE_STALE, LocalDateTime.now().minusDays(4), 0.2, 0)));
        stubClock();

        Map<Long, KnowledgeLifecycleService.ScanResult> out = service.scanAllProjects();

        assertEquals(1, out.size());
        assertEquals(1, out.get(PROJECT_ID).migrated());
    }

    /** 手动归档 + 还原（归档衰减 -0.1 → 还原提升 +0.1，回到原置信度）。 */
    @Test
    void manualArchiveAndRestore() {
        ActorKnowledgeDoc d = doc(8L, KnowledgeLifecycleService.STATE_WARM, LocalDateTime.now(), 0.5, 1);
        when(docRepo.findById(8L)).thenReturn(java.util.Optional.of(d));
        stubClock();

        assertEquals(1, service.archiveDoc(8L), "归档成功");
        assertEquals(KnowledgeLifecycleService.STATE_ARCHIVED, d.getLifecycleState());
        assertEquals(0.4, d.getConfidence(), 1e-9, "归档衰减 -0.1");

        assertEquals(1, service.restoreDoc(8L), "还原成功");
        assertEquals(KnowledgeLifecycleService.STATE_WARM, d.getLifecycleState(), "还原为 warm");
        assertEquals(0.5, d.getConfidence(), 1e-9, "还原提升 +0.1 回到原值");

        assertEquals(0, service.restoreDoc(8L), "非 archived 不可再还原");
        assertEquals(1, service.archiveDoc(8L), "warm 可再次归档");
        assertEquals(KnowledgeLifecycleService.STATE_ARCHIVED, d.getLifecycleState());
        assertEquals(0, service.archiveDoc(8L), "已 archived 不可重复归档");
    }

    /** 手动操作开关门控。 */
    @Test
    void manualOpsDisabled() {
        ReflectionTestUtils.setField(service, "enabled", false);
        assertEquals(0, service.archiveDoc(8L));
        assertEquals(0, service.restoreDoc(8L));
        assertEquals(0, service.recordAccess(PROJECT_ID, List.of(1L)));
    }
}
