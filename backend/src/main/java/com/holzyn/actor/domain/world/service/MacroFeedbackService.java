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

package com.holzyn.actor.domain.world.service;

import com.holzyn.actor.domain.world.config.MacroTemplate;
import com.holzyn.actor.domain.world.entity.ActorInfoPacket;
import com.holzyn.actor.domain.world.entity.ActorWorldFrame;
import com.holzyn.actor.domain.world.entity.ActorWorldMacroEvent;
import com.holzyn.actor.domain.world.repository.ActorInfoPacketRepository;
import com.holzyn.actor.domain.world.repository.ActorWorldFrameRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 宏观社会演化 · 宏观回灌服务。
 * <p>职责：宏观变化 → 世界既有体系的四路回灌：
 * ① 世界帧「社会宏观」板块（actor_world_frame.macro_summary_json——MacroScheduler 每旬/月
 * 确定性写简报 + 重大宏观事件即时追加）；</p>
 * <p>② 编年史记录——由 MacroEventService 发布 kind=macro 事件经 WorldMemoryService 订阅
 * （即时提炼，severity≥阈值自动成条）完成，本服务不再重复写；</p>
 * <p>③ 信息扩散源头——宏观事件实体化为 actor_info_packet（frame_id=null，独立于帧包），
 * 经既有 InfoDiffusionService 每游戏小时规则扩散（空间/社会/认知三层过滤后注入 NPC 感知）；</p>
 * <p>④ NPC 感知——宏观摘要经 MacroContextService.macroDynamicsSegment 注入感知【宏观环境】段
 * （行为参数联动 v1：粮价/局势/舆论只读摘要，决策与对话可引用）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MacroFeedbackService {

    private final ActorWorldFrameRepository frameRepository;
    private final ActorInfoPacketRepository packetRepository;
    private final MacroStateService macroStateService;

    /** 模板服务（注入；@Autowired(required=false) 保持构造器不变，null=回退默认仙侠模板） */
    @Autowired(required = false)
    private MacroTemplateService macroTemplateService;

    /** 项目社区名。 */
    private String communityName(Long projectId) {
        if (macroTemplateService != null) {
            try {
                return macroTemplateService.forProject(projectId).communityName();
            } catch (Exception ignored) {
                // 回退默认
            }
        }
        return MacroTemplate.builtins().get(MacroTemplate.DEFAULT_ID).communityName();
    }

    // ==================== 回灌路③：信息包（扩散源头） ====================

    /**
     * 宏观事件 → 信息包（frame_id=null 独立于帧包；InfoDiffusionService 按小时扩散；
     * info_id 带 macro: 前缀防与帧包撞号；valid_until=3 游戏日）。
     */
    public void onMacroEvent(Long projectId, Long gameDay, ActorWorldMacroEvent ev, String namedNpc) {
        if (projectId == null || ev == null) {
            return;
        }
        try {
            ActorInfoPacket p = new ActorInfoPacket();
            p.setProjectId(projectId);
            p.setFrameId(null);
            p.setInfoId(abbrev("macro:" + gameDay + ":" + ev.getId(), 50));
            // 扩散源头：具名 NPC 在场（减员/增员事件带出）→ 以该 NPC 为源；否则社区名（模板语义）
            p.setOrigin(namedNpc != null && !namedNpc.isBlank() ? namedNpc : communityName(projectId));
            p.setContent(ev.getNarrative());
            p.setReliability(ev.getSource() == null || ev.getSource().equals("rule") ? 0.7 : 0.9);
            // 扩散渠道
            p.setSpreadChannelsJson("[\"街坊传闻\",\"邻里闲谈\"]");
            p.setSpreadSpeedDaysPerKm(0.3);
            p.setValidUntilDay(gameDay + 3);
            p.setDistortionRulesJson("[\"越传越远越夸大\"]");
            p.setVisibilityConditionJson(null); // 全村皆知（空=社会层放行）
            p.setStatus("active");
            packetRepository.save(p);
            // 回灌路①：重大宏观事件即时追加到当前生效帧「社会宏观」板块
            appendFrameSummary(projectId, ev);
        } catch (Exception e) {
            log.warn("[宏观] 宏观事件信息包实体化失败（隔离继续）: project={} : {}", projectId, e.getMessage());
        }
    }

    // ==================== 回灌路①：帧「社会宏观」板块 ====================

    /**
     * 每旬/月简报写入当前生效帧（MacroScheduler 调用；无 active 帧则跳过——帧生成在 00:00
     * 早于宏观 tick，正常应存在）。
     */
    public void writeFrameSummary(Long projectId, MacroState st, String tickType) {
        try {
            List<ActorWorldFrame> frames = frameRepository.findByProjectIdAndStatus(projectId, "active");
            if (frames.isEmpty()) {
                log.debug("[宏观] 无 active 帧，「社会宏观」板块暂不写入: project={}", projectId);
                return;
            }
            ActorWorldFrame f = frames.get(0);
            f.setMacroSummaryJson(macroStateService.buildSummary(st, tickType));
            frameRepository.save(f);
        } catch (Exception e) {
            log.warn("[宏观] 帧「社会宏观」板块写入失败（隔离继续）: project={} : {}", projectId, e.getMessage());
        }
    }

    /** 重大事件即时追加到当前 active 帧板块（保留既有简报，追加事件行）。 */
    private void appendFrameSummary(Long projectId, ActorWorldMacroEvent ev) {
        try {
            List<ActorWorldFrame> frames = frameRepository.findByProjectIdAndStatus(projectId, "active");
            if (frames.isEmpty()) {
                return;
            }
            ActorWorldFrame f = frames.get(0);
            String existing = f.getMacroSummaryJson();
            String line = "【宏观事件·第" + (ev.getGameDay() == null ? 0 : ev.getGameDay() + 1) + "日】"
                    + ev.getNarrative();
            if (existing == null || existing.isBlank()) {
                f.setMacroSummaryJson(line);
            } else if (existing.length() < 2000) {
                f.setMacroSummaryJson(existing + "\n" + line);
            }
            frameRepository.save(f);
        } catch (Exception e) {
            log.warn("[宏观] 帧板块追加宏观事件失败（隔离继续）: project={} : {}", projectId, e.getMessage());
        }
    }

    /** 截断（中文按字符）。 */
    private static String abbrev(String s, int max) {
        if (s == null || s.length() <= max) {
            return s;
        }
        return s.substring(0, max);
    }
}
