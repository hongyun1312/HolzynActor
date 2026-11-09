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

package com.holzyn.actor.domain.item.service;

import com.holzyn.actor.domain.item.config.ItemProperties;
import com.holzyn.actor.domain.item.entity.ActorItemDef;
import com.holzyn.actor.domain.item.entity.ActorItemLedger;
import com.holzyn.actor.domain.item.entity.ActorItemStack;
import com.holzyn.actor.domain.item.model.DurabilityChange;
import com.holzyn.actor.domain.item.model.InsufficientItemException;
import com.holzyn.actor.domain.item.model.ItemView;
import com.holzyn.actor.domain.item.repository.ActorItemDefRepository;
import com.holzyn.actor.domain.item.repository.ActorItemLedgerRepository;
import com.holzyn.actor.domain.item.repository.ActorItemStackRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 物品域唯一写入口：转移/幂等/堆叠合并/严格不足/懒建定义/命名空间校验（验收）。
 *
 * <p>不加载 Spring 上下文（仓库惯例）：仓储全部 Mockito 模拟，只验服务自身的
 * 决策与调用顺序。</p>
 */
class InventoryServiceTest {

    private ActorItemDefRepository defRepository;
    private ActorItemStackRepository stackRepository;
    private ActorItemLedgerRepository ledgerRepository;
    private ItemProperties properties;
    private InventoryService service;

    @BeforeEach
    void setUp() {
        defRepository = mock(ActorItemDefRepository.class);
        stackRepository = mock(ActorItemStackRepository.class);
        ledgerRepository = mock(ActorItemLedgerRepository.class);
        properties = new ItemProperties();
        service = new InventoryService(defRepository, stackRepository, ledgerRepository, properties);
        when(defRepository.findByProjectIdAndItemKey(anyLong(), anyString())).thenReturn(Optional.empty());
        when(defRepository.findByProjectIdAndItemKeyIn(anyLong(), any())).thenReturn(List.of());
    }

    // ==================== 转移 / 堆叠 ====================

    @Test
    @DisplayName("转移：来源扣减、目标按同品质合并、账本记正向增量")
    void transferMovesAndMerges() {
        ActorItemStack source = stack("npc:1", "grain", 5, 100);
        when(stackRepository.lockByHolderAndItem(1L, "npc:1", "grain"))
                .thenReturn(new ArrayList<>(List.of(source)));

        service.transfer(1L, "grain", 3, "npc:1", "npc:2", "trade", 9L, 1000L, "plan:7:op:0");

        assertEquals(2, source.getQuantity(), "来源应剩 2");
        ArgumentCaptor<ActorItemStack> stacks = ArgumentCaptor.forClass(ActorItemStack.class);
        verify(stackRepository, times(2)).save(stacks.capture());
        ActorItemStack credited = stacks.getAllValues().stream()
                .filter(s -> "npc:2".equals(s.getHolderRef()))
                .findFirst().orElseThrow(() -> new AssertionError("没有给目标入账"));
        assertEquals(3, credited.getQuantity(), "目标应得 3");
        assertEquals(100, credited.getQuality(), "品质应随货走");

        ArgumentCaptor<ActorItemLedger> ledgers = ArgumentCaptor.forClass(ActorItemLedger.class);
        verify(ledgerRepository).save(ledgers.capture());
        assertEquals(3, ledgers.getValue().getQtyDelta());
        assertEquals("plan:7:op:0", ledgers.getValue().getIdempotencyKey());
        assertEquals("trade", ledgers.getValue().getReason());
        assertEquals("brain", ledgers.getValue().getWriter());
    }

    @Test
    @DisplayName("幂等：同一 idempotencyKey 重放不再扣货、不再记账")
    void transferIsIdempotent() {
        when(ledgerRepository.existsByProjectIdAndIdempotencyKey(1L, "plan:7:op:0")).thenReturn(true);

        service.transfer(1L, "grain", 3, "npc:1", "npc:2", "trade", 9L, 1000L, "plan:7:op:0");

        verifyNoInteractions(stackRepository);
        verify(ledgerRepository, never()).save(any());
    }

    @Test
    @DisplayName("严格不足：数量不够抛 InsufficientItemException，不落任何写入")
    void transferRejectsInsufficient() {
        ActorItemStack source = stack("npc:1", "grain", 2, 100);
        when(stackRepository.lockByHolderAndItem(1L, "npc:1", "grain"))
                .thenReturn(new ArrayList<>(List.of(source)));

        assertThrows(InsufficientItemException.class, () -> service.transfer(
                1L, "grain", 5, "npc:1", "npc:2", "trade", 9L, 1000L, "k1"));

        verify(stackRepository, never()).save(any());
        verify(stackRepository, never()).delete(any());
        verify(ledgerRepository, never()).save(any());
    }

    @Test
    @DisplayName("跨品质消耗：低品质先扣，目标按各自品质分层入账")
    void transferConsumesAcrossQualities() {
        ActorItemStack low = stack("npc:1", "grain", 1, 60);
        ActorItemStack high = stack("npc:1", "grain", 4, 100);
        when(stackRepository.lockByHolderAndItem(1L, "npc:1", "grain"))
                .thenReturn(new ArrayList<>(List.of(low, high)));

        service.transfer(1L, "grain", 2, "npc:1", "npc:2", "trade", null, 1000L, "k2");

        verify(stackRepository).delete(low);
        assertEquals(3, high.getQuantity(), "高品质层应只扣 1");
        ArgumentCaptor<ActorItemStack> stacks = ArgumentCaptor.forClass(ActorItemStack.class);
        // 一次来源保存 + 两次目标入账（两种品质各一层）
        verify(stackRepository, times(3)).save(stacks.capture());
        List<ActorItemStack> credited = stacks.getAllValues().stream()
                .filter(s -> "npc:2".equals(s.getHolderRef())).toList();
        assertEquals(2, credited.size(), "两种品质应各建一层");
        assertTrue(credited.stream().anyMatch(s -> s.getQuality() == 60 && s.getQuantity() == 1));
        assertTrue(credited.stream().anyMatch(s -> s.getQuality() == 100 && s.getQuantity() == 1));
    }

    @Test
    @DisplayName("同持有者自转：直接忽略，不产生账本")
    void transferToSelfIsNoop() {
        service.transfer(1L, "grain", 1, "npc:1", "npc:1", "trade", null, 1L, "k3");
        verifyNoInteractions(stackRepository);
        verify(ledgerRepository, never()).save(any());
    }

    // ==================== 产出 / 消耗 ====================

    @Test
    @DisplayName("产出：无来源必须声明合法 reason，否则拒绝")
    void addRequiresCreationReason() {
        assertThrows(IllegalArgumentException.class, () -> service.add(
                1L, "grain", 1, "npc:1", "whatever", null, 1L, "k4"));
    }

    @Test
    @DisplayName("产出：craft 合法，落 +qty 账本并新建栈")
    void addCreatesStack() {
        service.add(1L, "grain", 5, "npc:1", "craft", null, 1L, "k5");

        ArgumentCaptor<ActorItemStack> stacks = ArgumentCaptor.forClass(ActorItemStack.class);
        verify(stackRepository).save(stacks.capture());
        assertEquals(5, stacks.getValue().getQuantity());
        assertEquals("npc:1", stacks.getValue().getHolderRef());

        ArgumentCaptor<ActorItemLedger> ledgers = ArgumentCaptor.forClass(ActorItemLedger.class);
        verify(ledgerRepository).save(ledgers.capture());
        assertEquals(5, ledgers.getValue().getQtyDelta());
        assertEquals("craft", ledgers.getValue().getReason());
    }

    @Test
    @DisplayName("消耗：数量恰好清空时删行，账本记 -qty")
    void removeDeletesEmptyStack() {
        ActorItemStack source = stack("npc:1", "grain", 1, 100);
        when(stackRepository.lockByHolderAndItem(1L, "npc:1", "grain"))
                .thenReturn(new ArrayList<>(List.of(source)));

        service.remove(1L, "grain", 1, "npc:1", "consume", null, 1L, "k6");

        verify(stackRepository).delete(source);
        ArgumentCaptor<ActorItemLedger> ledgers = ArgumentCaptor.forClass(ActorItemLedger.class);
        verify(ledgerRepository).save(ledgers.capture());
        assertEquals(-1, ledgers.getValue().getQtyDelta());
    }

    // ==================== 定义 / 校验 ====================

    @Test
    @DisplayName("懒建定义：未知 itemKey 落 material/99/0，并留 INFO")
    void lazyDefCreatesMinimalDefinition() {
        service.add(1L, "短刀", 1, "npc:1", "initial", null, 1L, "k7");

        ArgumentCaptor<ActorItemDef> defs = ArgumentCaptor.forClass(ActorItemDef.class);
        verify(defRepository).save(defs.capture());
        assertEquals("短刀", defs.getValue().getName());
        assertEquals("material", defs.getValue().getCategory());
        assertEquals(99, defs.getValue().getStackMax());
        assertEquals(0, defs.getValue().getBasePrice());
    }

    @Test
    @DisplayName("lazy-def=false：未登记物品直接拒绝")
    void lazyDefDisabledRejectsUnknown() {
        properties.setLazyDef(false);
        assertThrows(IllegalArgumentException.class, () -> service.add(
                1L, "短刀", 1, "npc:1", "initial", null, 1L, "k8"));
    }

    @Test
    @DisplayName("持有者命名空间：只接受 npc:/loc:/container: 前缀")
    void holderNamespaceIsValidated() {
        assertThrows(IllegalArgumentException.class, () -> service.transfer(
                1L, "grain", 1, "shop:1", "npc:2", "trade", null, 1L, "k9"));
        assertThrows(IllegalArgumentException.class, () -> service.transfer(
                1L, "grain", 1, "npc:1", "npc2", "trade", null, 1L, "k10"));
    }

    @Test
    @DisplayName("数量：0/负数/超上限都拒绝")
    void quantityIsValidated() {
        assertThrows(IllegalArgumentException.class, () -> service.transfer(
                1L, "grain", 0, "npc:1", "npc:2", "trade", null, 1L, "k11"));
        assertThrows(IllegalArgumentException.class, () -> service.transfer(
                1L, "grain", 1000, "npc:1", "npc:2", "trade", null, 1L, "k12"));
    }

    @Test
    @DisplayName("物品键归一化：去空白、内部空白折叠、ASCII 小写")
    void itemKeyIsNormalized() {
        assertEquals("grain", InventoryService.normalizeItemKey(" Grain "));
        assertEquals("干_粮", InventoryService.normalizeItemKey("干  粮"));
        assertEquals("a_b_c", InventoryService.normalizeItemKey("A  B\tC"));
        assertThrows(IllegalArgumentException.class, () -> InventoryService.normalizeItemKey("   "));
    }

    // ==================== 读模型 ====================

    @Test
    @DisplayName("list：按定义映射名称/分类；缺定义时回退 itemKey/material")
    void listMapsDefinitions() {
        ActorItemStack known = stack("npc:1", "grain", 4, 100);
        ActorItemStack unknown = stack("npc:1", "mystery", 2, 100);
        when(stackRepository.findByProjectIdAndHolderRefOrderByItemKeyAscQualityAsc(1L, "npc:1"))
                .thenReturn(List.of(known, unknown));
        ActorItemDef def = new ActorItemDef();
        def.setItemKey("grain");
        def.setName("麦子");
        def.setCategory("food");
        when(defRepository.findByProjectIdAndItemKeyIn(org.mockito.ArgumentMatchers.eq(1L), any()))
                .thenReturn(List.of(def));

        List<ItemView> views = service.list(1L, "npc:1");

        assertEquals(2, views.size());
        assertEquals("麦子", views.get(0).name());
        assertEquals("food", views.get(0).category());
        assertEquals("mystery", views.get(1).name(), "缺定义回退 itemKey");
        assertEquals("material", views.get(1).category());
    }

    @Test
    @DisplayName("quantityOf：跨品质求和")
    void quantityOfSums() {
        when(stackRepository.sumQuantity(1L, "npc:1", "grain")).thenReturn(7L);
        assertEquals(7, service.quantityOf(1L, "npc:1", "grain"));
    }

    private static ActorItemStack stack(String holder, String key, int qty, int quality) {
        ActorItemStack stack = new ActorItemStack();
        stack.setProjectId(1L);
        stack.setHolderRef(holder);
        stack.setItemKey(key);
        stack.setQuantity(qty);
        stack.setQuality(quality);
        stack.setVersion(1);
        return stack;
    }

    // ==================== 耐久腐坏 ====================

    @Test
    @DisplayName("I4 磨损：从最完好的一层挪 1 件到低耐久层，账本记 0 差额 wear")
    void wearMovesOnePieceToLowerDurability() {
        ActorItemStack source = stack("npc:1", "柴刀", 2, 100);
        when(stackRepository.lockByHolderAndItem(1L, "npc:1", "柴刀"))
                .thenReturn(new ArrayList<>(List.of(source)));

        DurabilityChange change = service.wear(1L, "npc:1", "柴刀", 2, null, 1000L, "k-wear");

        assertEquals(100, change.before());
        assertEquals(98, change.after());
        assertTrue(change.present());
        assertFalse(change.broke());
        assertEquals(1, source.getQuantity(), "源层应只剩 1 件（只磨一件）");

        ArgumentCaptor<ActorItemStack> stacks = ArgumentCaptor.forClass(ActorItemStack.class);
        // 一次「源层减 1 件」+ 一次「新耐久层入账」
        verify(stackRepository, times(2)).save(stacks.capture());
        ActorItemStack worn = stacks.getAllValues().stream()
                .filter(s -> s.getDurability() != null && s.getDurability() == 98)
                .findFirst().orElseThrow(() -> new AssertionError("没有落到低耐久层"));
        assertEquals(98, worn.getDurability(), "磨损件落到新耐久层");
        assertEquals(1, worn.getQuantity());

        ArgumentCaptor<ActorItemLedger> ledgers = ArgumentCaptor.forClass(ActorItemLedger.class);
        verify(ledgerRepository).save(ledgers.capture());
        assertEquals(0, ledgers.getValue().getQtyDelta(), "耐久变化不是数量变化");
        assertEquals("wear", ledgers.getValue().getReason());
    }

    @Test
    @DisplayName("I4 磨损到 0：整件消失，账本记 -1 break")
    void wearBreaksAtZero() {
        ActorItemStack source = stack("npc:1", "柴刀", 1, 100);
        source.setDurability(2);
        when(stackRepository.lockByHolderAndItem(1L, "npc:1", "柴刀"))
                .thenReturn(new ArrayList<>(List.of(source)));

        DurabilityChange change = service.wear(1L, "npc:1", "柴刀", 5, null, 1000L, "k-break");

        assertTrue(change.broke());
        assertEquals(0, change.after());
        verify(stackRepository).delete(source);
        ArgumentCaptor<ActorItemLedger> ledgers = ArgumentCaptor.forClass(ActorItemLedger.class);
        verify(ledgerRepository).save(ledgers.capture());
        assertEquals(-1, ledgers.getValue().getQtyDelta());
        assertEquals("break", ledgers.getValue().getReason());
    }

    @Test
    @DisplayName("I4 磨损：目标不存在 → before=-1，不记账")
    void wearMissingItem() {
        when(stackRepository.lockByHolderAndItem(1L, "npc:1", "柴刀"))
                .thenReturn(new ArrayList<>());

        DurabilityChange change = service.wear(1L, "npc:1", "柴刀", 2, null, 1L, "k-none");

        assertFalse(change.present());
        verify(ledgerRepository, never()).save(any());
    }

    @Test
    @DisplayName("I4 磨损幂等：同一键重放不再扣耐久")
    void wearIsIdempotent() {
        when(ledgerRepository.existsByProjectIdAndIdempotencyKey(1L, "k-replay")).thenReturn(true);

        DurabilityChange change = service.wear(1L, "npc:1", "柴刀", 2, null, 1L, "k-replay");

        assertTrue(change.replayed());
        verifyNoInteractions(stackRepository);
    }

    @Test
    @DisplayName("I4 修复：补回耐久并封顶 100，账本记 repair")
    void restoreCapsAtFull() {
        ActorItemStack source = stack("npc:1", "柴刀", 1, 100);
        source.setDurability(90);
        when(stackRepository.lockByHolderAndItem(1L, "npc:1", "柴刀"))
                .thenReturn(new ArrayList<>(List.of(source)));

        DurabilityChange change = service.restore(1L, "npc:1", "柴刀", 25, null, 1L, "k-fix");

        assertEquals(90, change.before());
        assertEquals(100, change.after(), "封顶 100，不出现「比新的还新」");
        ArgumentCaptor<ActorItemLedger> ledgers = ArgumentCaptor.forClass(ActorItemLedger.class);
        verify(ledgerRepository).save(ledgers.capture());
        assertEquals("repair", ledgers.getValue().getReason());
    }

    @Test
    @DisplayName("I4 腐坏质量：按新鲜锚点现算，同一游戏秒必然同一结果")
    void qualityAtIsDeterministic() {
        assertEquals(100, InventoryService.qualityAt(0L, 0L, 72));
        assertEquals(99, InventoryService.qualityAt(0L, 3600L, 72), "1 小时 / 72 小时 ≈ 1.4%");
        assertEquals(50, InventoryService.qualityAt(0L, 36L * 3600L, 72), "一半时间 = 一半质量");
        assertEquals(0, InventoryService.qualityAt(0L, 72L * 3600L, 72), "到点烂透");
        assertEquals(0, InventoryService.qualityAt(0L, 999L * 3600L, 72), "过了也还是 0");
        assertEquals(100, InventoryService.qualityAt(0L, -100L, 72), "时间倒流不扣质量");
        assertEquals(100, InventoryService.qualityAt(0L, 100L, 0), "没有 decay_hours 就不腐坏");
    }

    @Test
    @DisplayName("I4 腐坏 tick：首次碰到先立锚点，不追溯（避免一开世界全烂了）")
    void decayTickAnchorsOnFirstSight() {
        ActorItemStack bread = stack("npc:1", "面包", 3, 100);
        when(stackRepository.findByProjectIdOrderByIdAsc(1L)).thenReturn(List.of(bread));
        when(defRepository.findByProjectIdAndItemKeyIn(eq(0L), any())).thenReturn(List.of(decaying("面包", 1)));

        InventoryService.DecayReport report = service.decayTick(1L, 5000L);

        assertEquals(0, report.movedLayers());
        assertEquals(0, report.spoiledLayers());
        assertEquals(5000L, bread.getFreshAtGameSecond(), "锚点补成当前游戏秒");
        verify(stackRepository).save(bread);
        verify(stackRepository, never()).delete(any());
    }

    @Test
    @DisplayName("I4 腐坏 tick：烂透的整层删除并记 decay 账")
    void decayTickSpoilsAtZero() {
        ActorItemStack bread = stack("npc:1", "面包", 3, 100);
        bread.setFreshAtGameSecond(0L);
        when(stackRepository.findByProjectIdOrderByIdAsc(1L)).thenReturn(List.of(bread));
        when(defRepository.findByProjectIdAndItemKeyIn(eq(0L), any())).thenReturn(List.of(decaying("面包", 1)));

        InventoryService.DecayReport report = service.decayTick(1L, 3600L);

        assertEquals(1, report.spoiledLayers());
        verify(stackRepository).delete(bread);
        ArgumentCaptor<ActorItemLedger> ledgers = ArgumentCaptor.forClass(ActorItemLedger.class);
        verify(ledgerRepository).save(ledgers.capture());
        assertEquals(-3, ledgers.getValue().getQtyDelta());
        assertEquals("decay", ledgers.getValue().getReason());
    }

    @Test
    @DisplayName("I4 腐坏 tick：没有 decay_hours 的物品一律不动")
    void decayTickSkipsNonDecaying() {
        ActorItemStack grain = stack("npc:1", "麦子", 5, 100);
        grain.setFreshAtGameSecond(0L);
        when(stackRepository.findByProjectIdOrderByIdAsc(1L)).thenReturn(List.of(grain));
        when(defRepository.findByProjectIdAndItemKeyIn(eq(0L), any())).thenReturn(List.of());

        InventoryService.DecayReport report = service.decayTick(1L, 999999L);

        assertEquals(0, report.movedLayers());
        assertEquals(0, report.spoiledLayers());
        verify(stackRepository, never()).delete(any());
    }

    @Test
    @DisplayName("I4 bestLayerOf：同物品多状态层取最完好的一件")
    void bestLayerPicksHealthiest() {
        ActorItemStack worn = stack("npc:1", "柴刀", 1, 100);
        worn.setDurability(20);
        ActorItemStack fresh = stack("npc:1", "柴刀", 1, 100);
        fresh.setDurability(100);
        when(stackRepository.findByProjectIdAndHolderRefOrderByItemKeyAscQualityAsc(1L, "npc:1"))
                .thenReturn(List.of(worn, fresh));

        ItemView best = service.bestLayerOf(1L, "npc:1", "柴刀").orElseThrow();

        assertEquals(100, best.durability(), "手上那件 = 最完好的那把");
    }

    /** 造一个「会腐坏」的物品定义。 */
    private static ActorItemDef decaying(String key, int decayHours) {
        ActorItemDef def = new ActorItemDef();
        def.setItemKey(key);
        def.setName(key);
        def.setCategory("food");
        def.setDecayHours(decayHours);
        return def;
    }
}
