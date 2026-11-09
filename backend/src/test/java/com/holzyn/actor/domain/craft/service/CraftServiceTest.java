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

package com.holzyn.actor.domain.craft.service;

import com.holzyn.actor.domain.craft.config.CraftProperties;
import com.holzyn.actor.domain.craft.entity.ActorCraftJob;
import com.holzyn.actor.domain.craft.model.CraftJobResult;
import com.holzyn.actor.domain.craft.model.CraftRecipeView;
import com.holzyn.actor.domain.craft.model.CraftRejectedException;
import com.holzyn.actor.domain.craft.model.CraftStartRequest;
import com.holzyn.actor.domain.craft.model.InsufficientMaterialException;
import com.holzyn.actor.domain.craft.model.MaterialGap;
import com.holzyn.actor.domain.craft.repository.ActorCraftJobRepository;
import com.holzyn.actor.domain.item.config.ItemProperties;
import com.holzyn.actor.domain.item.entity.ActorItemRecipe;
import com.holzyn.actor.domain.item.model.ItemView;
import com.holzyn.actor.domain.item.port.InventoryPort;
import com.holzyn.actor.domain.item.repository.ActorItemRecipeRepository;
import com.holzyn.actor.domain.npc.profile.entity.ActorCharacter;
import com.holzyn.actor.domain.npc.profile.repository.ActorCharacterRepository;
import com.holzyn.actor.domain.world.fact.entity.ActorWorldFact;
import com.holzyn.actor.domain.world.fact.model.FactCommand;
import com.holzyn.actor.domain.world.fact.service.WorldFactQuery;
import com.holzyn.actor.domain.world.fact.service.WorldFactService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 制作唯一写入口单测（配方开工扣料到点结算设施门控）。
 *
 * <p>锁五条硬规则：</p>
 * <ol>
 *   <li>配方两级查找：本项目覆盖 → 全局模板；停做（enabled=false）视为不存在；</li>
 *   <li>开工<b>先验料再扣料</b>，材料不齐整批拒绝（不扣一半）；</li>
 *   <li>材料在<b>开工时</b>扣、产物在<b>到点后</b>出，中间没有任何东西；</li>
 *   <li>设施类型不对/被占 → 拒绝（不占着别人的炉子）；</li>
 *   <li>人离开原地 → 活判失败、材料不退（真实：料已经投进去了）。</li>
 * </ol>
 */
class CraftServiceTest {

    private static final Long PROJECT = 1L;

    private static final String ACTOR = "npc:7";

    private static final long GS = 10_000L;

    private CraftProperties props;

    private ItemProperties itemProps;

    private ActorItemRecipeRepository recipeRepo;

    private ActorCraftJobRepository jobRepo;

    private InventoryPort port;

    private WorldFactService factService;

    private WorldFactQuery factQuery;

    private ActorCharacterRepository characterRepo;

    private CraftService service;

    @BeforeEach
    void setUp() {
        props = new CraftProperties();
        props.setEnabled(true);
        itemProps = new ItemProperties();
        itemProps.setEnabled(true);
        recipeRepo = mock(ActorItemRecipeRepository.class);
        jobRepo = mock(ActorCraftJobRepository.class);
        port = mock(InventoryPort.class);
        factService = mock(WorldFactService.class);
        factQuery = mock(WorldFactQuery.class);
        characterRepo = mock(ActorCharacterRepository.class);
        service = new CraftService(props, itemProps, recipeRepo, jobRepo);
        ReflectionTestUtils.setField(service, "inventoryPort", port);
        ReflectionTestUtils.setField(service, "worldFactService", factService);
        ReflectionTestUtils.setField(service, "worldFactQuery", factQuery);
        ReflectionTestUtils.setField(service, "characterRepository", characterRepo);
        when(jobRepo.save(any(ActorCraftJob.class))).thenAnswer(inv -> {
            ActorCraftJob job = inv.getArgument(0);
            if (job.getId() == null) {
                job.setId(42L);
            }
            return job;
        });
    }

    // ==================== 配方查找 ====================

    @Test
    @DisplayName("配方：本项目没有时回退全局模板（project_id=0）")
    void recipeFallsBackToGlobalTemplate() {
        when(recipeRepo.findByProjectIdAndRecipeKey(PROJECT, "烤面包")).thenReturn(Optional.empty());
        when(recipeRepo.findByProjectIdAndRecipeKey(0L, "烤面包"))
                .thenReturn(Optional.of(row("烤面包", "面包", "灶台", 30, "[{\"itemKey\":\"面粉\",\"qty\":2}]")));

        CraftRecipeView view = service.recipeOf(PROJECT, "烤面包");

        assertEquals("烤面包", view.recipeKey());
        assertEquals("面包", view.outputItemKey());
        assertEquals("灶台", view.facilityType());
        assertEquals(1, view.inputs().size());
        assertEquals(2, view.inputs().get(0).qty());
    }

    @Test
    @DisplayName("配方：本项目覆盖优先于全局模板（同名时用本项目那份）")
    void projectRecipeOverridesGlobal() {
        when(recipeRepo.findByProjectIdAndRecipeKey(PROJECT, "烤面包"))
                .thenReturn(Optional.of(row("烤面包", "黑面包", "灶台", 45, "[{\"itemKey\":\"面粉\",\"qty\":3}]")));

        CraftRecipeView view = service.recipeOf(PROJECT, "烤面包");

        assertEquals("黑面包", view.outputItemKey());
        assertEquals(45, view.durationMinutes());
    }

    @Test
    @DisplayName("配方：enabled=false 视为不存在（停做不等于删行）")
    void disabledRecipeIsInvisible() {
        ActorItemRecipe row = row("烤面包", "面包", "灶台", 30, "[]");
        row.setEnabled(Boolean.FALSE);
        when(recipeRepo.findByProjectIdAndRecipeKey(PROJECT, "烤面包")).thenReturn(Optional.of(row));

        assertNull(service.recipeOf(PROJECT, "烤面包"));
    }

    @Test
    @DisplayName("配方解析：只知道产出（柴刀）也能落到配方上")
    void findRecipeByOutputItem() {
        when(recipeRepo.findByProjectIdAndRecipeKey(PROJECT, null)).thenReturn(Optional.empty());
        when(recipeRepo.findByProjectIdAndOutputItemKeyOrderByIdAsc(PROJECT, "柴刀"))
                .thenReturn(List.of(row("打柴刀", "柴刀", "铁匠炉", 60, "[]")));

        CraftRecipeView view = service.findRecipe(PROJECT, null, "柴刀", "");

        assertEquals("打柴刀", view.recipeKey());
    }

    @Test
    @DisplayName("配方解析：只剩自由文本时取最长命中（「打柴刀」优先于「柴刀」）")
    void findRecipeByActionTextPrefersLongestMatch() {
        when(recipeRepo.findByProjectIdAndEnabledOrderByIdAsc(0L, Boolean.TRUE)).thenReturn(List.of(
                row("打柴刀", "柴刀", "铁匠炉", 60, "[]"),
                row("磨柴刀", "柴刀", "石磨", 10, "[]")));
        when(recipeRepo.findByProjectIdAndEnabledOrderByIdAsc(PROJECT, Boolean.TRUE)).thenReturn(List.of());

        CraftRecipeView view = service.findRecipe(PROJECT, null, null, "他打算磨柴刀");

        assertEquals("磨柴刀", view.recipeKey());
    }

    // ==================== 材料缺口 ====================

    @Test
    @DisplayName("材料缺口：按份数乘出来，缺的才算（齐了返回空）")
    void gapsMultiplyByBatches() {
        CraftRecipeView recipe = view("打柴刀", "柴刀", "铁匠炉", 60,
                "铁锭", 2, "木头", 1);

        List<MaterialGap> gaps = CraftService.gapsOf(recipe,
                List.of(item("铁锭", 3), item("木头", 5)), 2);

        assertEquals(1, gaps.size(), "铁锭 2×2=4 > 3，只该报铁锭");
        assertEquals("铁锭", gaps.get(0).itemKey());
        assertEquals(4, gaps.get(0).need());
        assertEquals(3, gaps.get(0).have());
        assertEquals(1, gaps.get(0).missing());
        assertTrue(CraftService.gapsOf(recipe, List.of(item("铁锭", 4), item("木头", 2)), 2).isEmpty());
    }

    // ==================== 开工 ====================

    @Test
    @DisplayName("开工：扣料 → 建作业（到点才出活）→ 占设施")
    void startDeductsMaterialsAndCreatesJob() {
        stubRecipe("打柴刀", "柴刀", "铁匠炉", 60, "铁锭", 1, "木头", 1);
        when(port.list(PROJECT, ACTOR)).thenReturn(List.of(item("铁锭", 2), item("木头", 2)));
        when(factQuery.byKey(PROJECT, "facility:loc:9")).thenReturn(Optional.of(facility("铁匠炉", true)));

        CraftJobResult result = service.start(request("打柴刀", 1, "loc:9", 9L, "青峰镇", "i3:job:1"));

        assertFalse(result.replay());
        assertEquals(42L, result.jobId());
        assertEquals(1, result.outputQty());
        assertEquals(GS + 60 * 60L, result.finishGameSecond(), "完工 = 开工 + 配方耗时");

        verify(port).consume(eq(PROJECT), eq("铁锭"), eq(1), eq(ACTOR), eq("craft"),
                any(), eq(GS), eq("i3:job:1:mat:1"));
        verify(port).consume(eq(PROJECT), eq("木头"), eq(1), eq(ACTOR), eq("craft"),
                any(), eq(GS), eq("i3:job:1:mat:2"));
        verify(port, never()).produce(anyLong(), anyString(), anyInt(), anyString(), anyString(),
                any(), anyLong(), any());

        ArgumentCaptor<ActorCraftJob> captor = ArgumentCaptor.forClass(ActorCraftJob.class);
        verify(jobRepo).save(captor.capture());
        ActorCraftJob saved = captor.getValue();
        assertEquals(ActorCraftJob.STATUS_IN_PROGRESS, saved.getStatus());
        assertEquals("铁匠炉", saved.getFacilityType());
        assertEquals("facility:loc:9", saved.getFacilityRef());
        assertEquals(9L, saved.getLocationId());

        // 设施被标记为占用（旁人能看见「他正在打铁」）
        ArgumentCaptor<FactCommand> factCaptor = ArgumentCaptor.forClass(FactCommand.class);
        verify(factService).upsert(factCaptor.capture());
        assertTrue(factCaptor.getValue().attributesJson().contains("\"occupied\":true"));
        assertTrue(factCaptor.getValue().attributesJson().contains(ACTOR));
    }

    @Test
    @DisplayName("开工：同一幂等键重放 → replay=true，不再扣一次料")
    void startReplaysIdempotently() {
        ActorCraftJob existing = new ActorCraftJob();
        existing.setId(77L);
        existing.setRecipeKey("打柴刀");
        existing.setOutputItemKey("柴刀");
        existing.setOutputQty(1);
        existing.setBatches(1);
        existing.setFinishGameSecond(GS + 3600L);
        when(jobRepo.findByProjectIdAndIdempotencyKey(PROJECT, "i3:job:1")).thenReturn(Optional.of(existing));

        CraftJobResult result = service.start(request("打柴刀", 1, null, null, null, "i3:job:1"));

        assertTrue(result.replay());
        assertEquals(77L, result.jobId());
        verify(port, never()).consume(anyLong(), anyString(), anyInt(), anyString(), anyString(),
                any(), anyLong(), any());
        verify(jobRepo, never()).save(any(ActorCraftJob.class));
    }

    @Test
    @DisplayName("开工：材料不齐 → InsufficientMaterialException，一份料都不扣")
    void startRejectsWhenMaterialShort() {
        stubRecipe("打柴刀", "柴刀", "铁匠炉", 60, "铁锭", 2);
        when(port.list(PROJECT, ACTOR)).thenReturn(List.of(item("铁锭", 1)));
        when(factQuery.byKey(PROJECT, "facility:loc:9")).thenReturn(Optional.of(facility("铁匠炉", true)));

        InsufficientMaterialException ex = assertThrows(InsufficientMaterialException.class,
                () -> service.start(request("打柴刀", 1, "loc:9", 9L, "青峰镇", "i3:job:1")));

        assertEquals(1, ex.gaps().size());
        assertEquals("铁锭", ex.gaps().get(0).itemKey());
        verify(port, never()).consume(anyLong(), anyString(), anyInt(), anyString(), anyString(),
                any(), anyLong(), any());
        verify(jobRepo, never()).save(any(ActorCraftJob.class));
    }

    @Test
    @DisplayName("开工：配方要炉子但现场没有 → NO_FACILITY")
    void startRejectsWhenFacilityMissing() {
        stubRecipe("打柴刀", "柴刀", "铁匠炉", 60, "铁锭", 1);
        when(port.list(PROJECT, ACTOR)).thenReturn(List.of(item("铁锭", 2)));
        when(factQuery.byKey(PROJECT, "facility:loc:9")).thenReturn(Optional.empty());
        when(factQuery.byKey(PROJECT, "facility:青峰镇")).thenReturn(Optional.empty());

        CraftRejectedException ex = assertThrows(CraftRejectedException.class,
                () -> service.start(request("打柴刀", 1, "loc:9", 9L, "青峰镇", "i3:job:1")));

        assertEquals("NO_FACILITY", ex.reasonCode());
        verify(port, never()).consume(anyLong(), anyString(), anyInt(), anyString(), anyString(),
                any(), anyLong(), any());
    }

    @Test
    @DisplayName("开工：炉子被别人占着 → OCCUPIED")
    void startRejectsWhenFacilityOccupied() {
        stubRecipe("打柴刀", "柴刀", "铁匠炉", 60, "铁锭", 1);
        when(port.list(PROJECT, ACTOR)).thenReturn(List.of(item("铁锭", 2)));
        when(factQuery.byKey(PROJECT, "facility:loc:9")).thenReturn(Optional.of(facility("铁匠炉", true)));
        ActorCraftJob other = new ActorCraftJob();
        other.setActorRef("npc:99");
        when(jobRepo.findByProjectIdAndFacilityRefAndStatusOrderByIdAsc(PROJECT, "facility:loc:9",
                ActorCraftJob.STATUS_IN_PROGRESS)).thenReturn(List.of(other));

        CraftRejectedException ex = assertThrows(CraftRejectedException.class,
                () -> service.start(request("打柴刀", 1, "loc:9", 9L, "青峰镇", "i3:job:1")));

        assertEquals("OCCUPIED", ex.reasonCode());
    }

    @Test
    @DisplayName("开工：手上还压着没做完的活 → BUSY（一个人不能同时打两把刀）")
    void startRejectsWhenBusy() {
        stubRecipe("打柴刀", "柴刀", "铁匠炉", 60, "铁锭", 1);
        when(port.list(PROJECT, ACTOR)).thenReturn(List.of(item("铁锭", 2)));
        when(factQuery.byKey(PROJECT, "facility:loc:9")).thenReturn(Optional.of(facility("铁匠炉", true)));
        ActorCraftJob active = new ActorCraftJob();
        active.setId(55L);
        active.setActorRef(ACTOR);
        active.setRecipeKey("锯木板");
        active.setStatus(ActorCraftJob.STATUS_IN_PROGRESS);
        active.setFinishGameSecond(GS + 999L);
        when(jobRepo.findByProjectIdAndActorRefAndStatusOrderByStartedGameSecondAsc(
                PROJECT, ACTOR, ActorCraftJob.STATUS_IN_PROGRESS)).thenReturn(List.of(active));

        CraftRejectedException ex = assertThrows(CraftRejectedException.class,
                () -> service.start(request("打柴刀", 1, "loc:9", 9L, "青峰镇", "i3:job:1")));

        assertEquals("BUSY", ex.reasonCode());
    }

    @Test
    @DisplayName("开工：制作未启用 → IllegalStateException（不静默什么都不做）")
    void startRequiresEnabled() {
        props.setEnabled(false);
        assertThrows(IllegalStateException.class,
                () -> service.start(request("打柴刀", 1, null, null, null, null)));
    }

    // ==================== 结算 ====================

    @Test
    @DisplayName("结算：到点 → 产出物品 + 作业 done + 释放设施")
    void finishDueProducesOutput() {
        ActorCraftJob job = inProgressJob("柴刀", "item", 1, 1);
        when(jobRepo.findDue(eq(PROJECT), eq(ActorCraftJob.STATUS_IN_PROGRESS), eq(GS + 3600L), any()))
                .thenReturn(List.of(job));
        when(factQuery.byKey(PROJECT, "facility:loc:9")).thenReturn(Optional.of(facility("铁匠炉", true)));

        int settled = service.finishDue(PROJECT, GS + 3600L);

        assertEquals(1, settled);
        verify(port).produce(eq(PROJECT), eq("柴刀"), eq(1), eq(ACTOR), eq("craft"), any(),
                eq(GS + 3600L), eq("craft:42:out"));
        assertEquals(ActorCraftJob.STATUS_DONE, job.getStatus());
        // 完工后释放设施（旁人再去看，炉子空着）
        ArgumentCaptor<FactCommand> captor = ArgumentCaptor.forClass(FactCommand.class);
        verify(factService).upsert(captor.capture());
        assertTrue(captor.getValue().attributesJson().contains("\"occupied\":false"));
    }

    @Test
    @DisplayName("结算：没到点 → 一件都不结算（活还在炉子上）")
    void finishDueSkipsEarly() {
        when(jobRepo.findDue(eq(PROJECT), eq(ActorCraftJob.STATUS_IN_PROGRESS), eq(GS), any()))
                .thenReturn(List.of());

        assertEquals(0, service.finishDue(PROJECT, GS));
        verify(port, never()).produce(anyLong(), anyString(), anyInt(), anyString(), anyString(),
                any(), anyLong(), any());
    }

    @Test
    @DisplayName("结算：人已经离开原地 → 判失败 LEFT_SITE，不产出、材料不退")
    void finishDueFailsWhenActorLeft() {
        ActorCraftJob job = inProgressJob("柴刀", "item", 1, 1);
        when(jobRepo.findDue(eq(PROJECT), eq(ActorCraftJob.STATUS_IN_PROGRESS), eq(GS + 3600L), any()))
                .thenReturn(List.of(job));
        ActorCharacter actor = new ActorCharacter();
        actor.setId(7L);
        actor.setLocation("别处");
        when(characterRepo.findById(7L)).thenReturn(Optional.of(actor));

        int settled = service.finishDue(PROJECT, GS + 3600L);

        assertEquals(1, settled);
        assertEquals(ActorCraftJob.STATUS_FAILED, job.getStatus());
        assertEquals("LEFT_SITE", job.getReason());
        verify(port, never()).produce(anyLong(), anyString(), anyInt(), anyString(), anyString(),
                any(), anyLong(), any());
    }

    @Test
    @DisplayName("结算：建造类产物落成设施事实（不进背包）")
    void finishDueBuildsFacilityFact() {
        ActorCraftJob job = inProgressJob("铁匠炉", "facility", 1, 1);
        when(jobRepo.findDue(eq(PROJECT), eq(ActorCraftJob.STATUS_IN_PROGRESS), eq(GS + 3600L), any()))
                .thenReturn(List.of(job));

        service.finishDue(PROJECT, GS + 3600L);

        verify(port, never()).produce(anyLong(), anyString(), anyInt(), anyString(), anyString(),
                any(), anyLong(), any());
        ArgumentCaptor<FactCommand> captor = ArgumentCaptor.forClass(FactCommand.class);
        verify(factService).upsert(captor.capture());
        FactCommand cmd = captor.getValue();
        assertEquals("facility", cmd.factType());
        assertEquals("facility:loc:9", cmd.factKey());
        assertTrue(cmd.attributesJson().contains("\"type\":\"铁匠炉\""));
        assertEquals(ActorCraftJob.STATUS_DONE, job.getStatus());
    }

    // ==================== 取消 ====================

    @Test
    @DisplayName("取消：进行中的活置 cancelled 并释放设施（材料不退）")
    void cancelReleasesFacility() {
        ActorCraftJob job = inProgressJob("柴刀", "item", 1, 1);
        when(jobRepo.findById(42L)).thenReturn(Optional.of(job));
        when(factQuery.byKey(PROJECT, "facility:loc:9")).thenReturn(Optional.of(facility("铁匠炉", true)));

        assertTrue(service.cancel(PROJECT, 42L, "改主意了"));

        assertEquals(ActorCraftJob.STATUS_CANCELLED, job.getStatus());
        assertEquals("改主意了", job.getReason());
        verify(port, never()).produce(anyLong(), anyString(), anyInt(), anyString(), anyString(),
                any(), anyLong(), any());
    }

    @Test
    @DisplayName("取消：已完成的活不能再取消")
    void cancelRejectsFinishedJob() {
        ActorCraftJob job = inProgressJob("柴刀", "item", 1, 1);
        job.setStatus(ActorCraftJob.STATUS_DONE);
        when(jobRepo.findById(42L)).thenReturn(Optional.of(job));

        assertFalse(service.cancel(PROJECT, 42L, "晚了"));
    }

    // ==================== 份数钳制 ====================

    @Test
    @DisplayName("份数：超出上限被钳到 max-batches-per-job（不炸掉整批效果）")
    void clampBatches() {
        props.setMaxBatchesPerJob(3);
        assertEquals(3, service.clampBatches(99));
        assertEquals(1, service.clampBatches(0));
        assertEquals(2, service.clampBatches(2));
    }

    // ==================== 工具 ====================

    private void stubRecipe(String recipeKey, String output, String facilityType, int minutes,
                            Object... inputs) {
        when(recipeRepo.findByProjectIdAndRecipeKey(PROJECT, recipeKey))
                .thenReturn(Optional.of(row(recipeKey, output, facilityType, minutes, inputsJson(inputs))));
    }

    private static String inputsJson(Object... pairs) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            if (sb.length() > 1) {
                sb.append(',');
            }
            sb.append("{\"itemKey\":\"").append(pairs[i]).append("\",\"qty\":").append(pairs[i + 1]).append('}');
        }
        return sb.append(']').toString();
    }

    private static ActorItemRecipe row(String recipeKey, String output, String facilityType,
                                       int minutes, String inputsJson) {
        ActorItemRecipe row = new ActorItemRecipe();
        row.setProjectId(PROJECT);
        row.setRecipeKey(recipeKey);
        row.setOutputItemKey(output);
        row.setOutputQty(1);
        row.setOutputKind("item");
        row.setInputsJson(inputsJson);
        row.setFacilityType(facilityType);
        row.setDurationMinutes(minutes);
        row.setEnabled(Boolean.TRUE);
        return row;
    }

    private static CraftRecipeView view(String recipeKey, String output, String facilityType,
                                        int minutes, Object... pairs) {
        java.util.List<com.holzyn.actor.domain.craft.model.RecipeInput> inputs = new java.util.ArrayList<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            inputs.add(new com.holzyn.actor.domain.craft.model.RecipeInput(
                    String.valueOf(pairs[i]), (Integer) pairs[i + 1]));
        }
        return new CraftRecipeView(recipeKey, output, 1, "item", facilityType, minutes, inputs);
    }

    private static ItemView item(String itemKey, int qty) {
        return new ItemView(itemKey, itemKey, "material", qty, 100, null, null);
    }

    private static ActorWorldFact facility(String type, boolean open) {
        ActorWorldFact fact = new ActorWorldFact();
        fact.setProjectId(PROJECT);
        fact.setFactType("facility");
        fact.setFactKey("facility:loc:9");
        fact.setName("铁匠铺");
        fact.setStatus("active");
        fact.setVisibility("public");
        fact.setAttributesJson("{\"type\":\"" + type + "\",\"open\":" + open + "}");
        return fact;
    }

    private static ActorCraftJob inProgressJob(String output, String kind, int outputQty, int batches) {
        ActorCraftJob job = new ActorCraftJob();
        job.setId(42L);
        job.setProjectId(PROJECT);
        job.setActorRef(ACTOR);
        job.setRecipeKey("打" + output);
        job.setOutputItemKey(output);
        job.setOutputKind(kind);
        job.setOutputQty(outputQty);
        job.setBatches(batches);
        job.setFacilityType("铁匠炉");
        job.setFacilityRef("facility:loc:9");
        job.setLocationId(9L);
        job.setLocationName("青峰镇");
        job.setStartedGameSecond(GS);
        job.setFinishGameSecond(GS + 3600L);
        job.setStatus(ActorCraftJob.STATUS_IN_PROGRESS);
        return job;
    }

    private static CraftStartRequest request(String recipeKey, int batches, String facilityRef,
                                             Long locationId, String locationName, String idem) {
        return new CraftStartRequest(PROJECT, ACTOR, recipeKey, batches, facilityRef, locationId,
                locationName, 99L, GS, idem);
    }

    /** 一次开工两条账（铁锭/木头各一条）的幂等后缀必须不同。 */
    @Test
    @DisplayName("开工：多份材料各用不同幂等后缀（否则第二条会被当成重放）")
    void startUsesDistinctIdemPerMaterial() {
        stubRecipe("打柴刀", "柴刀", "铁匠炉", 60, "铁锭", 1, "木头", 1);
        when(port.list(PROJECT, ACTOR)).thenReturn(List.of(item("铁锭", 2), item("木头", 2)));
        when(factQuery.byKey(PROJECT, "facility:loc:9")).thenReturn(Optional.of(facility("铁匠炉", true)));

        service.start(request("打柴刀", 1, "loc:9", 9L, "青峰镇", "i3:job:9"));

        verify(port, times(2)).consume(eq(PROJECT), anyString(), anyInt(), eq(ACTOR), eq("craft"),
                any(), eq(GS), anyString());
        verify(port).consume(eq(PROJECT), eq("铁锭"), eq(1), eq(ACTOR), eq("craft"), any(), eq(GS),
                eq("i3:job:9:mat:1"));
        verify(port).consume(eq(PROJECT), eq("木头"), eq(1), eq(ACTOR), eq("craft"), any(), eq(GS),
                eq("i3:job:9:mat:2"));
    }
}
