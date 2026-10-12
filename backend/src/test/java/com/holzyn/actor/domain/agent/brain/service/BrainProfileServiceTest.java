package com.holzyn.actor.domain.agent.brain.service;

import com.holzyn.actor.domain.npc.profile.entity.ActorAgentProfile;
import com.holzyn.actor.domain.npc.profile.repository.ActorAgentProfileRepository;
import com.holzyn.actor.domain.agent.brain.service.BrainProfileService.DefaultBrain;
import com.holzyn.actor.domain.agent.brain.service.BrainProfileService.ParsedRef;
import com.holzyn.actor.domain.npc.profile.entity.ActorCharacter;
import com.holzyn.actor.domain.npc.profile.repository.ActorCharacterRepository;
import com.holzyn.actor.domain.npc.profile.service.NpcTypeResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.data.domain.Sort;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * BrainProfileService（阶段 C C1 脑力配置）单元测试（与实现同包，domain/agent/brain/service）。
 * <p>职责：验证——默认值解析（special/ordinary/非法回退）、ensureProfile 幂等（已有不重建/
 * 缺失按默认补行/非法入参不触碰仓库）、getProfile 缺失返回 null（语义分离不自动建）、
 * resolveByRef（{@code npc:{id}} 经 {@code actor_profile.type} 定 special/ordinary + 非法引用返回 null）、
 * backfill 遍历与失败隔离。</p>
 */
class BrainProfileServiceTest {

    private ActorAgentProfileRepository profileRepository;
    private ActorCharacterRepository characterRepository;
    private ActorCharacterRepository npcRepository;
    private BrainProfileService service;

    @BeforeEach
    void setUp() {
        profileRepository = Mockito.mock(ActorAgentProfileRepository.class);
        characterRepository = Mockito.mock(ActorCharacterRepository.class);
        npcRepository = Mockito.mock(ActorCharacterRepository.class);
        service = new BrainProfileService(profileRepository, characterRepository, npcRepository);
        // G1a：resolveByRef/parseRef 的类型判定依赖 NpcTypeResolver（字段注入，单测手动回填）
        ReflectionTestUtils.setField(service, "npcTypeResolver", new NpcTypeResolver(characterRepository));
    }

    // ==================== 默认值解析（静态纯逻辑） ====================

    /**
     * 特殊智能体默认值：full/auto(premium)/realtime + planning/reflection 开（权威=系统框架 V2.0 §2.4）。
     */
    @Test
    void defaultsForSpecial() {
        DefaultBrain d = BrainProfileService.defaultsFor("special");
        assertEquals("full", d.brainLevel());
        assertEquals("auto", d.modelTier());
        assertEquals("realtime", d.decisionFrequency());
        assertEquals(1, d.thinkingEnabled());
        assertEquals(1, d.planningEnabled());
        assertEquals(1, d.reflectionEnabled());
    }

    /**
     * 普通智能体默认值：light/auto(standard)/per_interval + planning/reflection 关。
     */
    @Test
    void defaultsForOrdinary() {
        DefaultBrain d = BrainProfileService.defaultsFor("ordinary");
        assertEquals("light", d.brainLevel());
        assertEquals("auto", d.modelTier());
        assertEquals("per_interval", d.decisionFrequency());
        assertEquals(1, d.thinkingEnabled());
        assertEquals(0, d.planningEnabled());
        assertEquals(0, d.reflectionEnabled());
    }

    /**
     * 非法/未知类型回退：programmatic（纯程序化，AI 0 成本）+ 全部组件开关关。
     */
    @Test
    void defaultsForUnknownFallsBackProgrammatic() {
        for (String unknown : new String[]{"bogus", "", null, "npc"}) {
            DefaultBrain d = BrainProfileService.defaultsFor(unknown);
            assertEquals("programmatic", d.brainLevel(), "未知类型=" + unknown);
            assertEquals("auto", d.modelTier());
            assertEquals("per_interval", d.decisionFrequency());
            assertEquals(0, d.thinkingEnabled());
            assertEquals(0, d.planningEnabled());
            assertEquals(0, d.reflectionEnabled());
        }
    }

    // ==================== ensureProfile（幂等建行） ====================

    /**
     * 缺失时按默认值补行（special→full 等），save 入参字段与 defaultsFor 一致。
     */
    @Test
    void ensureProfileCreatesWithDefaultsWhenMissing() {
        when(profileRepository.findByAgentTypeAndAgentRefId("special", 3L)).thenReturn(Optional.empty());
        when(profileRepository.save(any(ActorAgentProfile.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        ActorAgentProfile p = service.ensureProfile("special", 3L);

        assertNotNull(p);
        assertEquals("special", p.getAgentType());
        assertEquals(3L, p.getAgentRefId());
        assertEquals("full", p.getBrainLevel());
        assertEquals("auto", p.getModelTier());
        assertEquals("realtime", p.getDecisionFrequency());
        assertEquals(1, p.getThinkingEnabled());
        assertEquals(1, p.getPlanningEnabled());
        assertEquals(1, p.getReflectionEnabled());
        assertNull(p.getMemoryBudget(), "memory_budget 默认 null（继承全局）");
        assertNull(p.getAiBudgetPerTick(), "ai_budget_per_tick 默认 null（继承项目）");
        verify(profileRepository).save(any(ActorAgentProfile.class));
    }

    /**
     * 幂等：已有行直接返回，不重建（save 不被调用）。
     */
    @Test
    void ensureProfileIdempotentReturnsExisting() {
        ActorAgentProfile existing = new ActorAgentProfile();
        existing.setId(10L);
        existing.setAgentType("ordinary");
        existing.setAgentRefId(5L);
        when(profileRepository.findByAgentTypeAndAgentRefId("ordinary", 5L))
                .thenReturn(Optional.of(existing));

        ActorAgentProfile p = service.ensureProfile("ordinary", 5L);

        assertSame(existing, p);
        verify(profileRepository, never()).save(any(ActorAgentProfile.class));
    }

    /**
     * 非法入参（空类型/空 ID）：返回 null，不触碰仓库。
     */
    @Test
    void ensureProfileInvalidArgsReturnsNull() {
        assertNull(service.ensureProfile(null, 1L));
        assertNull(service.ensureProfile("", 1L));
        assertNull(service.ensureProfile("special", null));
        verify(profileRepository, never()).findByAgentTypeAndAgentRefId(anyString(), any());
        verify(profileRepository, never()).save(any(ActorAgentProfile.class));
    }

    // ==================== getProfile（只读，缺失不自动建） ====================

    /**
     * getProfile 缺失返回 null（语义分离：查询不建行）。
     */
    @Test
    void getProfileMissingReturnsNull() {
        when(profileRepository.findByAgentTypeAndAgentRefId("special", 9L)).thenReturn(Optional.empty());
        assertNull(service.getProfile("special", 9L));
        verify(profileRepository, never()).save(any(ActorAgentProfile.class));
    }

    /**
     * getProfile 命中返回实体。
     */
    @Test
    void getProfilePresentReturnsEntity() {
        ActorAgentProfile existing = new ActorAgentProfile();
        existing.setId(1L);
        when(profileRepository.findByAgentTypeAndAgentRefId("special", 1L)).thenReturn(Optional.of(existing));
        assertSame(existing, service.getProfile("special", 1L));
    }

    // ==================== resolveByRef（统一角色引用解析） ====================

    /**
     * {@code npc:{id}} → 类型查 {@code actor_profile.type}：type=special → ("special", id)；
     * type=common → ("ordinary", id)；解析后 ensureProfile。
     * <p>G1a：类型不再由引用前缀编码（{@code special:}/{@code crowd:} 已废弃）。</p>
     */
    @Test
    void resolveByRefResolvesTypeFromProfile() {
        when(profileRepository.findByAgentTypeAndAgentRefId(anyString(), any())).thenReturn(Optional.empty());
        when(profileRepository.save(any(ActorAgentProfile.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        // 3=特殊型（实体默认 type=special）、12=普通型（显式 common）
        when(characterRepository.findById(3L)).thenReturn(Optional.of(character(3L)));
        when(characterRepository.findById(12L)).thenReturn(Optional.of(npc(12L)));

        ActorAgentProfile sp = service.resolveByRef("npc:3");
        assertNotNull(sp);
        assertEquals("special", sp.getAgentType());
        assertEquals(3L, sp.getAgentRefId());
        assertEquals("full", sp.getBrainLevel(), "special 解析后按默认 full");

        ActorAgentProfile cr = service.resolveByRef("npc:12");
        assertNotNull(cr);
        assertEquals("ordinary", cr.getAgentType());
        assertEquals(12L, cr.getAgentRefId());
        assertEquals("light", cr.getBrainLevel(), "ordinary 解析后按默认 light");
    }

    /**
     * 非法引用（非 {@code npc:} 前缀 / 历史双前缀 / 空 / 数字非法）：返回 null，不触碰仓库。
     */
    @Test
    void resolveByRefInvalidReturnsNull() {
        assertNull(service.resolveByRef("bogus:1"));
        assertNull(service.resolveByRef("special:3"), "历史 special: 前缀不再是合法身份引用");
        assertNull(service.resolveByRef("crowd:3"), "历史 crowd: 前缀不再是合法身份引用");
        assertNull(service.resolveByRef("npc:abc"));
        assertNull(service.resolveByRef(""));
        assertNull(service.resolveByRef(null));
        verify(profileRepository, never()).findByAgentTypeAndAgentRefId(anyString(), any());
        verify(profileRepository, never()).save(any(ActorAgentProfile.class));
    }

    /**
     * parseRef 解析（G1a 起为实例方法：需 NpcTypeResolver 查 {@code actor_profile.type}）：
     * {@code npc:{id}} → ("special"/"ordinary", id)；非法回退 null。
     */
    @Test
    void parseRefResolvesTypeFromProfile() {
        when(characterRepository.findById(7L)).thenReturn(Optional.of(character(7L)));
        when(characterRepository.findById(99L)).thenReturn(Optional.of(npc(99L)));

        ParsedRef sp = service.parseRef("npc:7");
        assertNotNull(sp);
        assertEquals("special", sp.type());
        assertEquals(7L, sp.id());
        ParsedRef cr = service.parseRef("npc:99");
        assertNotNull(cr);
        assertEquals("ordinary", cr.type());
        assertEquals(99L, cr.id());
        assertNull(service.parseRef("other:1"));
        assertNull(service.parseRef("special:7"), "历史 special: 前缀不再是合法身份引用");
        assertNull(service.parseRef("npc:"));
        assertNull(service.parseRef(null));
    }

    // ==================== backfillExisting（启动补行 + 失败隔离） ====================

    /**
     * backfill：遍历未删除特殊角色（deleted=0）→ ensureProfile("special")；遍历全部普通 NPC →
     * ensureProfile("ordinary")；返回已确保数。
     */
    @Test
    void backfillExistingIteratesCharactersAndNpcs() {
        ActorCharacter c1 = character(1L);
        ActorCharacter c2 = character(2L);
        ActorCharacter n1 = npc(5L);
        when(characterRepository.findByDeletedOrderByIdAsc(0)).thenReturn(List.of(c1, c2));
        when(npcRepository.findAll(Sort.by(Sort.Direction.ASC, "id"))).thenReturn(List.of(n1));
        when(profileRepository.findByAgentTypeAndAgentRefId(anyString(), any())).thenReturn(Optional.empty());
        when(profileRepository.save(any(ActorAgentProfile.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        int ensured = service.backfillExisting();

        assertEquals(3, ensured);
        ArgumentCaptor<ActorAgentProfile> captor = ArgumentCaptor.forClass(ActorAgentProfile.class);
        verify(profileRepository, times(3)).save(captor.capture());
        List<ActorAgentProfile> saved = captor.getAllValues();
        assertTrue(saved.stream().anyMatch(p -> "special".equals(p.getAgentType()) && p.getAgentRefId() == 1L));
        assertTrue(saved.stream().anyMatch(p -> "special".equals(p.getAgentType()) && p.getAgentRefId() == 2L));
        assertTrue(saved.stream().anyMatch(p -> "ordinary".equals(p.getAgentType()) && p.getAgentRefId() == 5L));
    }

    /**
     * backfill 失败隔离：单条补行抛异常不阻断后续（其余照常补行），启动不被中断。
     */
    @Test
    void backfillExistingIsolatesFailures() {
        ActorCharacter c1 = character(1L);
        ActorCharacter c2 = character(2L);
        ActorCharacter n1 = npc(5L);
        when(characterRepository.findByDeletedOrderByIdAsc(0)).thenReturn(List.of(c1, c2));
        when(npcRepository.findAll(Sort.by(Sort.Direction.ASC, "id"))).thenReturn(List.of(n1));
        when(profileRepository.findByAgentTypeAndAgentRefId(anyString(), any())).thenReturn(Optional.empty());
        // 特殊角色 1 补行失败（模拟 SQL 异常），其余成功
        when(profileRepository.save(any(ActorAgentProfile.class))).thenAnswer(inv -> {
            ActorAgentProfile p = inv.getArgument(0);
            if ("special".equals(p.getAgentType()) && p.getAgentRefId() == 1L) {
                throw new RuntimeException("模拟建行失败");
            }
            return p;
        });

        int ensured = service.backfillExisting();

        assertEquals(2, ensured, "失败的 1 条不计入，其余 2 条成功");
        ArgumentCaptor<ActorAgentProfile> captor = ArgumentCaptor.forClass(ActorAgentProfile.class);
        // 3 次 save 尝试（含抛异常的 1 次，Mockito 记录抛异常的调用）；成功落库的是其余 2 条
        verify(profileRepository, times(3)).save(captor.capture());
        List<ActorAgentProfile> saved = captor.getAllValues();
        assertTrue(saved.stream().anyMatch(p -> "special".equals(p.getAgentType()) && p.getAgentRefId() == 2L));
        assertTrue(saved.stream().anyMatch(p -> "ordinary".equals(p.getAgentType()) && p.getAgentRefId() == 5L));
    }

    // ==================== 工具 ====================

    private ActorCharacter character(Long id) {
        ActorCharacter c = new ActorCharacter();
        c.setId(id);
        return c;
    }

    private ActorCharacter npc(Long id) {
        ActorCharacter n = new ActorCharacter();
        n.setId(id);
        // G1a：单主表下普通型必须显式 setType("common")，实体默认值是 special
        n.setType("common");
        return n;
    }
}
