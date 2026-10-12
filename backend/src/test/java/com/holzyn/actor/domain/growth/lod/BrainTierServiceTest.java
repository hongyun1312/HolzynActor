package com.holzyn.actor.domain.growth.lod;

import com.holzyn.actor.domain.growth.config.GrowthProperties;
import com.holzyn.actor.domain.growth.stage.GrowthStage;
import com.holzyn.actor.domain.npc.profile.entity.ActorCharacter;
import com.holzyn.actor.domain.npc.profile.repository.ActorCharacterRepository;
import com.holzyn.actor.domain.npc.relation.repository.ActorCharacterRelationRepository;
import com.holzyn.actor.domain.world.repository.ActorEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/**
 * 认知 LOD 档位单元测试（O6 轨道 G2）。
 * <p>职责：默认档语义（special=focus / common=ambient / DECEASED=dormant）、
 * 手动覆盖优先、升档条件（重要度 ≥4 → focus）、档位持久化与升档清单返回。</p>
 */
@ExtendWith(MockitoExtension.class)
class BrainTierServiceTest {

    @Mock
    private ActorCharacterRepository characterRepository;
    @Mock
    private ActorCharacterRelationRepository relationRepository;
    @Mock
    private ActorEventRepository eventRepository;
    @Mock
    private CameraDistanceProvider cameraDistanceProvider;

    private GrowthProperties properties;
    private BrainTierService service;

    @BeforeEach
    void setUp() {
        properties = new GrowthProperties();
        service = new BrainTierService(characterRepository, relationRepository, eventRepository,
                properties, cameraDistanceProvider);
    }

    private ActorCharacter ch(Long id, String type, String stage, Integer importance, Integer locationId) {
        ActorCharacter c = new ActorCharacter();
        c.setId(id);
        c.setProjectId(1L);
        c.setType(type);
        c.setStage(stage);
        c.setImportance(importance);
        c.setLocationId(locationId == null ? null : locationId.longValue());
        c.setName("角色" + id);
        return c;
    }

    @Test
    @DisplayName("默认档位：special=focus / common=ambient / DECEASED=dormant")
    void defaultTiers() {
        when(characterRepository.findById(1L)).thenReturn(Optional.of(ch(1L, "special", "PRIME", 3, null)));
        when(characterRepository.findById(2L)).thenReturn(Optional.of(ch(2L, "common", "YOUTH", 2, null)));
        when(characterRepository.findById(3L)).thenReturn(Optional.of(ch(3L, "common", GrowthStage.DECEASED.name(), 2, null)));
        assertEquals(BrainTierService.TIER_FOCUS, service.tierOf(1L, "npc:1"));
        assertEquals(BrainTierService.TIER_AMBIENT, service.tierOf(1L, "npc:2"));
        assertEquals(BrainTierService.TIER_DORMANT, service.tierOf(1L, "npc:3"), "已故不消费认知投入");
    }

    @Test
    @DisplayName("手动覆盖优先于计算档位；null 清除覆盖")
    void overridePriority() {
        when(characterRepository.findById(4L)).thenReturn(Optional.of(ch(4L, "common", "YOUTH", 2, null)));
        assertEquals(BrainTierService.TIER_AMBIENT, service.tierOf(1L, "npc:4"));
        service.setOverride("npc:4", BrainTierService.TIER_FOCUS);
        assertEquals(BrainTierService.TIER_FOCUS, service.tierOf(1L, "npc:4"));
        service.setOverride("npc:4", null);
        assertEquals(BrainTierService.TIER_AMBIENT, service.tierOf(1L, "npc:4"));
    }

    @Test
    @DisplayName("重要度 ≥4 升档 focus（特殊型常驻焦点；升档计算在 refreshProject，tierOf 读缓存）")
    void importancePromotesToFocus() {
        ActorCharacter common4 = ch(5L, "common", "YOUTH", 4, null);
        when(characterRepository.findByProjectIdAndDeletedOrderByIdAsc(1L, 0))
                .thenReturn(List.of(common4));
        // 重要度 ≥4 在升档判定第一步短路，不触碰事件/关系仓库；refreshProject 已建档位缓存，tierOf 不再查 findById
        List<String> promotions = service.refreshProject(1L, 1_000_000L);
        assertTrue(promotions.contains("npc:5"), "重要度 4 的普通居民也升档进清单");
        assertEquals(BrainTierService.TIER_FOCUS, service.tierOf(1L, "npc:5"), "刷新后读缓存即 focus");
    }

    @Test
    @DisplayName("refreshProject：档位持久化 + 升档清单返回")
    void refreshProjectPersistsAndReturnsPromotions() {
        ActorCharacter common = ch(6L, "common", "YOUTH", 2, null);
        common.setBrainTier(BrainTierService.TIER_AMBIENT);
        ActorCharacter important = ch(7L, "common", "YOUTH", 5, null);
        important.setBrainTier(BrainTierService.TIER_AMBIENT);
        when(characterRepository.findByProjectIdAndDeletedOrderByIdAsc(1L, 0))
                .thenReturn(List.of(common, important));
        // 重要度 5 短路升档；重要度 2 的 common 判定时事件/关系仓库返回空（走完 ②③④ 判定）
        when(eventRepository.findByProjectIdOrderByIdDesc(1L)).thenReturn(List.of());
        when(relationRepository.findByProjectId(1L)).thenReturn(List.of());
        List<String> promotions = service.refreshProject(1L, 1_000_000L);
        assertTrue(promotions.contains("npc:7"), "重要度 5 → 升档进清单");
        assertEquals(BrainTierService.TIER_FOCUS, important.getBrainTier(), "重要度 5 的持久化 brain_tier");
        assertEquals(BrainTierService.TIER_AMBIENT, common.getBrainTier(), "重要度 2 保持 ambient");
    }

    // ==================== N5 注意力语义通道（2026-09-26） ====================

    /** 普通 NPC 无任何规则升档条件的判定环境（事件/关系仓库空）。 */
    private void stubNoRulePromotion(ActorCharacter c) {
        when(characterRepository.findByProjectIdAndDeletedOrderByIdAsc(1L, 0)).thenReturn(List.of(c));
        when(eventRepository.findByProjectIdOrderByIdDesc(1L)).thenReturn(List.of());
        when(relationRepository.findByProjectId(1L)).thenReturn(List.of());
    }

    @Test
    @DisplayName("N5 applyAttention：注意力高（level=3）→ 语义升档 focus（保持窗口内）")
    void applyAttentionHighPromotes() {
        ActorCharacter common = ch(8L, "common", "YOUTH", 2, 99);
        common.setBrainTier(BrainTierService.TIER_AMBIENT);
        when(characterRepository.findById(8L)).thenReturn(java.util.Optional.of(common));
        stubNoRulePromotion(common);
        service.applyAttention(1L, "npc:8", 3, 1_000_000L);
        assertEquals(BrainTierService.TIER_FOCUS, service.tierOf(1L, "npc:8"), "注意力高 → 语义升档 focus");
        assertEquals(BrainTierService.TIER_FOCUS, common.getBrainTier(), "档位变化即刻持久化");
    }

    @Test
    @DisplayName("N5 applyAttention：注意力低（level≤1，普通 NPC）→ 语义降档 ambient（升档痕迹在场）")
    void applyAttentionLowDemotes() {
        ActorCharacter common = ch(9L, "common", "YOUTH", 2, 99);
        common.setBrainTier(BrainTierService.TIER_AMBIENT);
        when(characterRepository.findById(9L)).thenReturn(java.util.Optional.of(common));
        stubNoRulePromotion(common);
        // 先高注意力升档（语义 focus），再低注意力 → 语义降档 ambient
        service.applyAttention(1L, "npc:9", 3, 2_000_000L);
        service.applyAttention(1L, "npc:9", 1, 2_000_300L);
        assertEquals(BrainTierService.TIER_AMBIENT, service.tierOf(1L, "npc:9"),
                "注意力低且普通 NPC → 语义降档回 ambient");
        assertEquals(BrainTierService.TIER_AMBIENT, common.getBrainTier());
    }

    @Test
    @DisplayName("N5 applyAttention：中性（level=2）→ 清语义痕迹回默认档")
    void applyAttentionNeutralClears() {
        ActorCharacter common = ch(10L, "common", "YOUTH", 2, 99);
        common.setBrainTier(BrainTierService.TIER_AMBIENT);
        when(characterRepository.findById(10L)).thenReturn(java.util.Optional.of(common));
        stubNoRulePromotion(common);
        service.applyAttention(1L, "npc:10", 3, 3_000_000L);
        assertEquals(BrainTierService.TIER_FOCUS, service.tierOf(1L, "npc:10"));
        service.applyAttention(1L, "npc:10", 2, 3_000_300L);
        assertEquals(BrainTierService.TIER_AMBIENT, service.tierOf(1L, "npc:10"),
                "中性注意力清语义痕迹 → 回默认档 ambient");
    }

    @Test
    @DisplayName("N5 applyAttention：语义通道配置关闭 → 零副作用（规则硬基线不受影响）")
    void applyAttentionSemanticDisabled() {
        properties.getLod().setSemanticEnabled(false);
        ActorCharacter common = ch(11L, "common", "YOUTH", 2, null);
        common.setBrainTier(BrainTierService.TIER_AMBIENT);
        when(characterRepository.findById(11L)).thenReturn(java.util.Optional.of(common));
        service.applyAttention(1L, "npc:11", 3, 4_000_000L);
        assertEquals(BrainTierService.TIER_AMBIENT, service.tierOf(1L, "npc:11"),
                "semantic-enabled=false → 注意力语义通道零副作用");
    }
}
