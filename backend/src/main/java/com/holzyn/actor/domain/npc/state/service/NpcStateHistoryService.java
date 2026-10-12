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

package com.holzyn.actor.domain.npc.state.service;

import com.holzyn.actor.domain.npc.state.config.NpcStateProperties;
import com.holzyn.actor.domain.npc.state.entity.ActorNpcState;
import com.holzyn.actor.domain.npc.state.entity.ActorStateHistory;
import com.holzyn.actor.domain.npc.state.repository.ActorStateHistoryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * NPC 状态历史快照服务 ——曲线数据源采样/保留/读接口。
 * <p>职责：</p>
 * <ul>
 * <li><b>采样</b>：{@link #recordSnapshot} 在引擎逐游戏小时推进（{@code NpcStateService.advance}）
 * 与人群作息挂钩（{@code onCrowdSchedule}）时对状态行做<b>全字段快照</b>落库；
 * 同一 (project_id, npc_ref, game_second) 幂等（唯一约束 + exists 前置跳过）——同秒重复采样不产生重复行；</li>
 * <li><b>保留</b>：每项目每 NPC 最近 {@code history.max-per-npc}（默认 168 ≈ 7 游戏日）条，
 * 超出滚动删除最旧（采样后即时清理）；</li>
 * <li><b>读接口</b>：{@link #listHistory} 按项目 + NPC 引用返回快照（时间升序，可限量）——
 * 调试前端状态页 / 主前端角色页历史曲线数据源。</li>
 * </ul>
 * <p>本服务为<b>只读历史</b>层：不回写决策/衰减（actor_state 仍是唯一状态权威）。</p>
 * <p>设计依据：《NPC状态模块_主开发方案》§10（展示-调试前端曲线）/ §12。
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NpcStateHistoryService {

    private final ActorStateHistoryRepository historyRepository;
    private final NpcStateProperties props;

    // ==================== 采样 ====================

    /**
     * 对单个状态行做快照落库（幂等 + 保留上限滚动清理）。
     * <p>采样时机：引擎逐游戏小时推进（advance 循环内）+ 人群作息挂钩（onCrowdSchedule 循环内）；
     * 世界暂停（game_second 不推进）自然不采样。同一 (项目, NPC, 游戏时刻) 已存在则跳过
     * （WorldEngine 每时先 advance 采样全行、再人群调度采样 crowd——同秒幂等去重）。</p>
     *
     * @param projectId 项目 ID
     *
     * @param st 状态行（快照来源；null/无 game_second 跳过）
     */
    @Transactional
    public void recordSnapshot(Long projectId, ActorNpcState st) {
        if (projectId == null || st == null || !props.getHistory().isEnabled()) {
            return;
        }
        String npcRef = st.getNpcRef();
        Long gameSecond = st.getGameSecond();
        if (npcRef == null || npcRef.isBlank() || gameSecond == null) {
            return;
        }
        // 幂等：同秒已采样则跳过（WorldEngine 每时 advance + 人群调度双入口同秒去重）
        if (historyRepository.existsByProjectIdAndNpcRefAndGameSecond(projectId, npcRef, gameSecond)) {
            return;
        }
        ActorStateHistory h = new ActorStateHistory();
        h.setProjectId(projectId);
        h.setNpcType(st.getNpcType());
        h.setNpcRef(npcRef);
        h.setHunger(st.getHunger());
        h.setThirst(st.getThirst());
        h.setEnergy(st.getEnergy());
        h.setBladder(st.getBladder());
        h.setHealth(st.getHealth());
        h.setMood(st.getMood());
        h.setEmotionPleasure(st.getEmotionPleasure());
        h.setEmotionArousal(st.getEmotionArousal());
        h.setEmotionDominance(st.getEmotionDominance());
        h.setDominantEmotion(st.getDominantEmotion());
        h.setEmotionIntensity(st.getEmotionIntensity());
        h.setHygiene(st.getHygiene());
        h.setSocial(st.getSocial());
        h.setFun(st.getFun());
        h.setComfort(st.getComfort());
        h.setEnvModifier(st.getEnvModifier());
        h.setUrgencyLevel(st.getUrgencyLevel());
        h.setUrgencyText(st.getUrgencyText());
        h.setGameSecond(gameSecond);
        historyRepository.save(h);
        // 保留上限滚动清理：超出 maxPerNpc 删除最旧
        trim(projectId, npcRef);
    }

    /**
     * 保留上限清理：每项目每 NPC 仅保留最近有效上限条（删除最旧；纯静态可单测）。
     * <p>（世界演化与游戏时间系统，）：有效上限取 History.effectiveMaxPerNpc()
     * ——采样粒度切 5 游戏分钟时自动按 7 游戏日放大（168→2016），max-per-npc 仍可手动调大。</p>
     */
    void trim(Long projectId, String npcRef) {
        if (projectId == null || npcRef == null) {
            return;
        }
        List<ActorStateHistory> all = historyRepository.findByProjectIdAndNpcRefOrderByGameSecondAsc(projectId, npcRef);
        int max = Math.max(1, props.getHistory().effectiveMaxPerNpc());
        if (all.size() <= max) {
            return;
        }
        List<ActorStateHistory> toDelete = all.subList(0, all.size() - max);
        historyRepository.deleteAll(toDelete);
    }

    // ==================== 读接口 ====================

    /**
     * 按项目 + NPC 引用查询历史快照（时间升序——曲线 x 轴；可限量取最近 N 条）。
     *
     * @param projectId 项目 ID
     *
     * @param limit 返回条数上限（可空/&lt;=0=全部）
     *
     * @return 快照列表（按 game_second 升序）
     */
    @Transactional(readOnly = true)
    public List<ActorStateHistory> listHistory(Long projectId, String npcRef, Integer limit) {
        if (projectId == null || npcRef == null || npcRef.isBlank()) {
            return List.of();
        }
        List<ActorStateHistory> all = historyRepository.findByProjectIdAndNpcRefOrderByGameSecondAsc(projectId, npcRef);
        if (limit == null || limit <= 0 || all.size() <= limit) {
            return all;
        }
        // 取最近 N 条（保持升序：subList 尾部）
        return all.subList(all.size() - limit, all.size());
    }
}
