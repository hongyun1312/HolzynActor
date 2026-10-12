package com.holzyn.actor.domain.growth.lineage;

import com.holzyn.actor.domain.growth.config.GrowthProperties;
import com.holzyn.actor.domain.npc.profile.entity.ActorCharacter;
import com.holzyn.actor.domain.npc.profile.repository.ActorCharacterRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * 谱系服务单元测试（O6 G4：婚姻边/亲子边/血亲禁忌/家族树/继承顺序）。
 *
 * <p>用内存边表模拟仓库（无 Spring 上下文）；血亲禁忌按「共享三代内祖先」判定。</p>
 */
@ExtendWith(MockitoExtension.class)
class LineageServiceTest {

    @Mock
    private ActorGrowthLineageRepository lineageRepository;
    @Mock
    private ActorCharacterRepository characterRepository;

    private LineageService service;
    private final List<ActorGrowthLineage> edges = new ArrayList<>();

    @BeforeEach
    void setUp() {
        GrowthProperties props = new GrowthProperties();
        service = new LineageService(lineageRepository, characterRepository, props);
        edges.clear();
        lenient().when(lineageRepository.save(any(ActorGrowthLineage.class))).thenAnswer(inv -> {
            ActorGrowthLineage e = inv.getArgument(0);
            if (e.getId() == null) {
                e.setId((long) (edges.size() + 1));
            }
            edges.add(e);
            return e;
        });
        lenient().when(lineageRepository.findByProjectIdOrderByIdAsc(1L)).thenReturn(edges);
        lenient().when(lineageRepository.findByProjectIdAndFromRefOrderByIdAsc(any(), any())).thenAnswer(inv ->
                edges.stream().filter(e -> e.getFromRef().equals(inv.getArgument(1))).toList());
        lenient().when(lineageRepository.findByProjectIdAndToRefOrderByIdAsc(any(), any())).thenAnswer(inv ->
                edges.stream().filter(e -> e.getToRef().equals(inv.getArgument(1))).toList());
        lenient().when(lineageRepository.findByProjectIdAndEdgeTypeAndFromRefOrderByIdAsc(any(), any(), any()))
                .thenAnswer(inv -> edges.stream().filter(e -> e.getEdgeType().equals(inv.getArgument(1))
                        && e.getFromRef().equals(inv.getArgument(2))).toList());
        lenient().when(lineageRepository.findByProjectIdAndEdgeTypeAndToRefOrderByIdAsc(any(), any(), any()))
                .thenAnswer(inv -> edges.stream().filter(e -> e.getEdgeType().equals(inv.getArgument(1))
                        && e.getToRef().equals(inv.getArgument(2))).toList());
        lenient().when(lineageRepository.findFirstByProjectIdAndEdgeTypeAndFromRefAndActiveOrderByIdDesc(any(), any(), any(), any()))
                .thenAnswer(inv -> edges.stream().filter(e -> e.getEdgeType().equals(inv.getArgument(1))
                                && e.getFromRef().equals(inv.getArgument(2)) && e.getActive() == 1)
                        .findFirst());
        lenient().when(lineageRepository.findFirstByProjectIdAndEdgeTypeAndToRefAndActiveOrderByIdDesc(any(), any(), any(), any()))
                .thenAnswer(inv -> edges.stream().filter(e -> e.getEdgeType().equals(inv.getArgument(1))
                                && e.getToRef().equals(inv.getArgument(2)) && e.getActive() == 1)
                        .findFirst());
        lenient().when(lineageRepository.findByProjectIdAndEdgeTypeAndFromRefAndToRef(any(), any(), any(), any()))
                .thenAnswer(inv -> edges.stream().filter(e -> e.getEdgeType().equals(inv.getArgument(1))
                                && e.getFromRef().equals(inv.getArgument(2)) && e.getToRef().equals(inv.getArgument(3)))
                        .toList());
    }

    private ActorCharacter ch(Long id, String name, String gender, String stage) {
        ActorCharacter c = new ActorCharacter();
        c.setId(id);
        c.setName(name);
        c.setGender(gender);
        c.setStage(stage);
        c.setProjectId(1L);
        return c;
    }

    private void mockAlive(Long id) {
        when(characterRepository.findById(id)).thenReturn(Optional.of(ch(id, "角色" + id, "男", "PRIME")));
    }

    @Test
    @DisplayName("婚姻边：缔结幂等 + 配偶查询 + 终止（丧偶 → active=0，历史保留）")
    void marryAndSpouse() {
        mockAlive(1L);
        mockAlive(2L);
        service.marry(1L, "npc:1", "npc:2", 1000L);
        assertEquals(1, edges.size());
        assertEquals("npc:2", service.spouseOf(1L, "npc:1").orElseThrow());
        assertEquals("npc:1", service.spouseOf(1L, "npc:2").orElseThrow());
        assertTrue(service.isMarried(1L, "npc:1"));
        // 重复缔结 → 跳过（幂等）
        service.marry(1L, "npc:1", "npc:2", 2000L);
        assertEquals(1, edges.size());
        // 丧偶 → active=0，spouseOf 空
        service.terminateMarriage(1L, "npc:2", "widow");
        assertTrue(service.spouseOf(1L, "npc:1").isEmpty());
        assertFalse(service.isMarried(1L, "npc:1"));
    }

    @Test
    @DisplayName("治理批次 0 卡5：配偶已故（存量边未终止）→ spouseOf 空（读侧过滤）")
    void spouseOfFiltersDeceasedSpouse() {
        service.marry(1L, "npc:1", "npc:2", 1000L);
        // 存量场景：婚姻边 active 仍 1，但配偶已故（丧偶未终止，如雷大壮→孙二娘存量边）
        when(characterRepository.findById(2L)).thenReturn(Optional.of(ch(2L, "亡妻", "女", "DECEASED")));
        assertTrue(service.spouseOf(1L, "npc:1").isEmpty(), "亡偶不算在世配偶（读侧过滤）");
        assertFalse(service.isMarried(1L, "npc:1"), "亡偶婚姻边不视为已婚");
    }

    @Test
    @DisplayName("亲子边：父母/子女/兄弟姐妹查询 + 幂等")
    void parentChildAndSiblings() {
        service.addParentChild(1L, "npc:1", "npc:10", ActorGrowthLineage.REL_FATHER, 100L);
        service.addParentChild(1L, "npc:2", "npc:10", ActorGrowthLineage.REL_MOTHER, 100L);
        service.addParentChild(1L, "npc:1", "npc:11", ActorGrowthLineage.REL_FATHER, 200L);
        service.addParentChild(1L, "npc:2", "npc:11", ActorGrowthLineage.REL_MOTHER, 200L);
        // 重复登记幂等
        service.addParentChild(1L, "npc:1", "npc:10", ActorGrowthLineage.REL_FATHER, 100L);
        assertEquals(4, edges.size());
        assertEquals("npc:1", service.fatherOf(1L, "npc:10").orElseThrow());
        assertEquals("npc:2", service.motherOf(1L, "npc:10").orElseThrow());
        assertEquals(List.of("npc:10", "npc:11"), service.childrenOf(1L, "npc:1"));
        assertTrue(service.siblingsOf(1L, "npc:10").contains("npc:11"));
    }

    @Test
    @DisplayName("血亲禁忌：直系/共享祖先是血亲；无亲缘不是")
    void bloodRelation() {
        // 祖辈：npc:1 是 npc:10 的祖父（npc:1 → npc:5 父 → npc:10）
        service.addParentChild(1L, "npc:1", "npc:5", ActorGrowthLineage.REL_FATHER, 1L);
        service.addParentChild(1L, "npc:5", "npc:10", ActorGrowthLineage.REL_FATHER, 100L);
        service.addParentChild(1L, "npc:6", "npc:10", ActorGrowthLineage.REL_MOTHER, 100L);
        // npc:7 是 npc:5 的另一个孩子（npc:10 的兄弟姐妹）
        service.addParentChild(1L, "npc:5", "npc:7", ActorGrowthLineage.REL_FATHER, 150L);
        service.addParentChild(1L, "npc:6", "npc:7", ActorGrowthLineage.REL_MOTHER, 150L);
        // 旁系（堂/表亲）：npc:8 是 npc:1 的另一个孙辈（通过 npc:9 父）
        service.addParentChild(1L, "npc:1", "npc:9", ActorGrowthLineage.REL_FATHER, 2L);
        service.addParentChild(1L, "npc:9", "npc:8", ActorGrowthLineage.REL_FATHER, 160L);
        assertTrue(service.isBloodRelated(1L, "npc:10", "npc:7"), "亲兄弟姐妹是血亲");
        assertTrue(service.isBloodRelated(1L, "npc:10", "npc:8"), "三代内堂亲是血亲");
        assertTrue(service.isBloodRelated(1L, "npc:10", "npc:5"), "直系父女是血亲");
        assertTrue(service.isBloodRelated(1L, "npc:10", "npc:1"), "三代内祖孙是血亲");
        assertFalse(service.isBloodRelated(1L, "npc:10", "npc:30"), "无亲缘不是血亲");
    }

    @Test
    @DisplayName("家族树：三代节点 + 边（婚姻/亲子）")
    void familyTree() {
        List<ActorCharacter> chars = List.of(
                ch(1L, "祖父", "男", "ELDER"), ch(2L, "祖母", "女", "ELDER"),
                ch(5L, "父亲", "男", "PRIME"), ch(6L, "母亲", "女", "PRIME"),
                ch(10L, "本人", "男", "YOUTH"), ch(11L, "妹妹", "女", "CHILD"));
        when(characterRepository.findByProjectIdAndDeletedOrderByIdAsc(1L, 0)).thenReturn(chars);
        // 治理批次 0 卡5：spouseOf 读侧 alive 过滤 → 家族树配偶判定需 findById 在世行（部分行可能不查，lenient）
        for (ActorCharacter c : chars) {
            lenient().when(characterRepository.findById(c.getId())).thenReturn(Optional.of(c));
        }
        service.marry(1L, "npc:1", "npc:2", 1L);
        service.marry(1L, "npc:5", "npc:6", 100L);
        service.addParentChild(1L, "npc:1", "npc:5", ActorGrowthLineage.REL_FATHER, 10L);
        service.addParentChild(1L, "npc:5", "npc:10", ActorGrowthLineage.REL_FATHER, 1000L);
        service.addParentChild(1L, "npc:6", "npc:10", ActorGrowthLineage.REL_MOTHER, 1000L);
        service.addParentChild(1L, "npc:5", "npc:11", ActorGrowthLineage.REL_FATHER, 2000L);
        service.addParentChild(1L, "npc:6", "npc:11", ActorGrowthLineage.REL_MOTHER, 2000L);
        var tree = service.familyTree(1L, "npc:10");
        assertEquals("npc:10", tree.get("focus"));
        @SuppressWarnings("unchecked")
        List<Object> nodes = (List<Object>) tree.get("nodes");
        assertTrue(nodes.size() >= 6, "三代家族树应含 ≥6 节点（祖辈/父母/本人/妹）");
        @SuppressWarnings("unchecked")
        List<Object> rels = (List<Object>) tree.get("relations");
        assertTrue(rels.size() >= 6, "家族树应含婚姻+亲子边");
    }

    @Test
    @DisplayName("继承顺序：配偶 → 长子 → 父母 → 无（族中）")
    void heirOrder() {
        mockAlive(2L);
        mockAlive(10L);
        mockAlive(5L);
        service.marry(1L, "npc:1", "npc:2", 1L);
        service.addParentChild(1L, "npc:1", "npc:10", ActorGrowthLineage.REL_FATHER, 10L);
        service.addParentChild(1L, "npc:5", "npc:1", ActorGrowthLineage.REL_FATHER, 1L);
        // 配偶在世 → 配偶
        assertEquals("npc:2", service.heirOf(1L, "npc:1").orElseThrow());
        // 配偶已故 → 长子
        when(characterRepository.findById(2L)).thenReturn(Optional.of(ch(2L, "亡妻", "女", "DECEASED")));
        assertEquals("npc:10", service.heirOf(1L, "npc:1").orElseThrow());
        // 子女已故 → 父母
        when(characterRepository.findById(10L)).thenReturn(Optional.of(ch(10L, "亡子", "男", "DECEASED")));
        assertEquals("npc:5", service.heirOf(1L, "npc:1").orElseThrow());
        // 全部已故 → 族中（空）
        when(characterRepository.findById(5L)).thenReturn(Optional.of(ch(5L, "亡父", "男", "DECEASED")));
        assertTrue(service.heirOf(1L, "npc:1").isEmpty());
    }
}
