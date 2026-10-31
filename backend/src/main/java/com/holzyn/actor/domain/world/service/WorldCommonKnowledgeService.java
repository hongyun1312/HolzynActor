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

import com.holzyn.actor.ai.AiChatRequest;
import com.holzyn.actor.ai.AiChatResult;
import com.holzyn.actor.ai.spring.AiSpringChatGateway;
import com.holzyn.actor.domain.debug.service.AiCallContext;
import com.holzyn.actor.domain.settings.service.PromptTemplateService;
import com.holzyn.actor.domain.usage.service.UsageLogService;
import com.holzyn.actor.domain.world.entity.ActorWorldSetting;
import com.holzyn.actor.domain.world.repository.ActorWorldSettingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 世界常识摘要服务（V2.0 段 1A 稳定世界观 · 300-500 字精炼常识）。
 * <p><b>背景</b>：{@code AiContextInjector.worldBrief} 此前把 {@code actor_world_setting}
 * 结构化字段直接拼接后硬截断 400 字符（实测停在「农业工具改」句中），世界观显示残缺
 * （2026-10-08 外网冒烟实测）。本服务改为<b>依最新世界观设定 AI 生成一份 300-500 字
 * 精炼「世界常识」</b>——定位是该世界角色都知道的基础常识（世界格局/主要聚居地/
 * 势力与规则/日常文化风俗的精炼总览），不罗列全部设定、不做句子级截断。</p>
 * <p><b>生成/更新时机</b>（用户口径「初始化时或定期依最新世界观生成/更新」）：</p>
 * <ul>
 * <li><b>worldgen 确认落库后</b>：{@code WorldGenPersistService.confirm} → 世界观保存
 * 事务提交后（复用 {@code ProjectService.saveWorldSetting} 的 afterCommit 钩子）触发；</li>
 * <li><b>手动保存世界观后</b>：同上 afterCommit 钩子（世界观编辑实时更新常识）；</li>
 * <li><b>定期兜底扫描</b>：{@link #scanAndBackfill} 按配置间隔扫描——有世界观但
 * 常识缺失 / 世界观 {@code updatedAt} 晚于常识生成时间 {@code commonKnowledgeAt}
 * （世界观变了常识没跟上）的项目，补生成（幂等：内容未变不重写）。</li>
 * </ul>
 * <p><b>输出契约</b>：纯文本正文（PLAIN_TEXT，非 JSON），300-500 字；AI 失败/超长
 * 逐级降级（保留旧值/截断 500 字/空=注入器回退截断拼装），绝不阻断世界运行。</p>
 * <p><b>架构不变量</b>：本服务含 AI 调用（chat 网关），公开方法一律<b>不带
 * {@code @Transactional}</b>（AiCallOutsideTransactionTest 扩展守护）；写库为
 * 独立短事务（saveAndFlush 单条）；调用经 {@link AiCallContext#run} 包裹
 * （trace/用量记账完整）；依赖全部 {@code @Autowired(required=false)}
 * 字段注入（缺失自动跳过，零阻断）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WorldCommonKnowledgeService {

    /** 常识目标字数区间（下限：过短视为失败降级；上限：截断红线） */
    static final int MIN_CHARS = 300;
    static final int MAX_CHARS = 500;

    /** AI 最大输出 token（300-500 汉字 ≈ 450-750 token，给足余量 900） */
    static final int MAX_TOKENS = 900;

    /** AI 温度（提炼常识=客观总结，低温收敛） */
    static final double TEMPERATURE = 0.4;

    private final ActorWorldSettingRepository worldSettingRepository;
    private final PromptTemplateService templateService;
    private final AiSpringChatGateway aiChatGateway;

    @Autowired(required = false)
    private UsageLogService usageLogService;

    /**
     * 世界闸门（V1.1.2-beta.1 M4）：世界未挂载时定期扫描必须<b>零 DB 写入、零 AI 调用</b>（AC12）。
     *
     * <p>本服务是这条纪律里最贵的一个：{@link #scanAndBackfillNow()} 会对"世界观变过"的项目
     * 直接调 chat 网关生成常识——未挂载时跑一趟就是在没有世界的情况下白烧 token。</p>
     */
    @Autowired(required = false)
    private com.holzyn.actor.domain.worldstore.WorldGate worldGate;

    /** 经世界闸门执行；未装配闸门（单测）时直接执行 */
    private void gated(String name, Runnable body) {
        if (worldGate == null) {
            body.run();
        } else {
            worldGate.tryMaintenance(name, body);
        }
    }

    /**
     * 依最新世界观生成/更新某项目的世界常识摘要（同步调用，含 AI）。
     * <p>幂等语义：世界观未变化（updatedAt 不晚于常识生成时间）→ 跳过；AI 输出
     * 过短/空 → 保留旧值并 WARN；AI 失败 → 保留旧值并 WARN（fail-open 零阻断）。</p>
     *
     * @param userId 归属用户 ID（AI 用量/trace 归属）
     *
     * @param projectId 项目 ID
     *
     * @return 是否实际生成/更新（false=跳过或失败降级）
     */
    public boolean generate(Long userId, Long projectId) {
        if (projectId == null || worldSettingRepository == null) {
            return false;
        }
        ActorWorldSetting ws = worldSettingRepository
                .findTopByProjectIdOrderByVersionDesc(projectId).orElse(null);
        if (ws == null || blank(ws.getName()) && blank(ws.getFreeText())
                && blank(ws.getGeography()) && blank(ws.getFactions())
                && blank(ws.getMagicSystem()) && blank(ws.getCulture())
                && blank(ws.getHistory())) {
            return false;
        }
        // 幂等：世界观没变（updatedAt ≤ 常识生成时间）→ 跳过
        if (ws.getCommonKnowledgeAt() != null
                && (ws.getUpdatedAt() == null || !ws.getUpdatedAt().isAfter(ws.getCommonKnowledgeAt()))) {
            return false;
        }
        String content = buildAndCall(userId, projectId, ws);
        if (content == null || content.isBlank()) {
            log.warn("[世界常识] 生成结果为空/过短，保留旧值: project={}", projectId);
            return false;
        }
        ws.setCommonKnowledge(content);
        ws.setCommonKnowledgeAt(LocalDateTime.now());
        worldSettingRepository.saveAndFlush(ws);
        log.info("[世界常识] 生成/更新完成: project={} 字数={}", projectId, content.length());
        return true;
    }

    /**
     * 定期兜底扫描：有世界观但常识缺失 / 世界观已更新的项目，补生成常识。
     * <p>对齐知识生命周期维护先例（@Scheduled 全项目扫描）；间隔经
     * {@code holzyn.actor.world.common-knowledge.scan-interval-ms} 配置
     * （默认 6 小时；0=禁用扫描）。单项目失败隔离不阻断后续。</p>
     */
    @Scheduled(fixedDelayString = "${holzyn.actor.world.common-knowledge.scan-interval-ms:21600000}")
    public void scanAndBackfill() {
        gated("WorldCommonKnowledgeService.scanAndBackfill", this::scanAndBackfillNow);
    }

    /** 扫描体（经世界闸门之后才允许扫库/调用 AI） */
    private void scanAndBackfillNow() {
        List<ActorWorldSetting> all;
        try {
            all = worldSettingRepository.findAll();
        } catch (Exception e) {
            log.warn("[世界常识] 定期扫描失败（跳过本轮）: {}", e.getMessage());
            return;
        }
        // 每项目取最新版本（按 projectId 分组取 version 最大）
        Map<Long, ActorWorldSetting> latest = new LinkedHashMap<>();
        for (ActorWorldSetting ws : all) {
            if (ws == null || ws.getProjectId() == null) {
                continue;
            }
            ActorWorldSetting cur = latest.get(ws.getProjectId());
            if (cur == null || (ws.getVersion() != null && cur.getVersion() != null
                    && ws.getVersion() > cur.getVersion())) {
                latest.put(ws.getProjectId(), ws);
            }
        }
        int generated = 0;
        int skipped = 0;
        for (Map.Entry<Long, ActorWorldSetting> e : latest.entrySet()) {
            Long projectId = e.getKey();
            ActorWorldSetting ws = e.getValue();
            // 世界观没变（updatedAt ≤ 常识生成时间）→ 跳过
            if (ws.getCommonKnowledgeAt() != null
                    && (ws.getUpdatedAt() == null || !ws.getUpdatedAt().isAfter(ws.getCommonKnowledgeAt()))) {
                skipped++;
                continue;
            }
            try {
                if (generate(0L, projectId)) {
                    generated++;
                } else {
                    skipped++;
                }
            } catch (Exception ex) {
                log.warn("[世界常识] 定期补生成失败（隔离）: project={} : {}", projectId, ex.getMessage());
                skipped++;
            }
        }
        log.info("[世界常识] 定期扫描完成：检查 {} 个项目，生成 {}，跳过 {}",
                latest.size(), generated, skipped);
    }

    // ==================== 内部 ====================

    /** 组装输入 + 调用 chat 网关生成常识（失败/过短返回 null）。 */
    private String buildAndCall(Long userId, Long projectId, ActorWorldSetting ws) {
        Map<String, Object> ph = new LinkedHashMap<>();
        ph.put("world_setting", worldSettingText(ws));
        String prompt = templateService.render(userId, projectId,
                PromptTemplateService.CODE_WORLD_COMMON_KNOWLEDGE, ph);
        AiChatRequest req = new AiChatRequest(null, List.of(
                new AiChatRequest.ChatMessage("system", templateService.systemMessage(userId, projectId,
                        PromptTemplateService.CODE_WORLD_COMMON_KNOWLEDGE, ph)),
                new AiChatRequest.ChatMessage("user", prompt)), TEMPERATURE, MAX_TOKENS, false);
        long t0 = System.currentTimeMillis();
        AiChatResult result = AiCallContext.run("world_common_knowledge", null, () ->
                aiChatGateway.chatCompletion(userId, projectId, null, req));
        if (usageLogService != null) {
            try {
                usageLogService.record(userId, projectId, null, result.providerId(), result.model(),
                        "world_common_knowledge", result.promptTokens(), result.completionTokens(),
                        result.cacheHitTokens(), result.cacheMissTokens(),
                        (int) (System.currentTimeMillis() - t0));
            } catch (Exception e) {
                log.warn("[世界常识] 用量入账失败（忽略）: {}", e.getMessage());
            }
        }
        return sanitize(result.content());
    }

    /** 清洗 AI 输出：剥 markdown 围栏/空白 → 限长 500 字；<300 字视为过短返回 null。 */
    static String sanitize(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String s = raw.trim();
        // 剥 ``` 围栏（若 AI 包了 markdown 代码块）
        if (s.startsWith("```")) {
            int nl = s.indexOf('\n');
            if (nl >= 0) {
                s = s.substring(nl + 1).trim();
            }
            if (s.endsWith("```")) {
                s = s.substring(0, s.length() - 3).trim();
            }
        }
        if (s.length() < MIN_CHARS) {
            return null;
        }
        return s.length() > MAX_CHARS ? s.substring(0, MAX_CHARS) + "…" : s;
    }

    /** 世界观全文（结构化分节 + 自由文本；空分段跳过）。 */
    private static String worldSettingText(ActorWorldSetting ws) {
        StringBuilder sb = new StringBuilder();
        append(sb, "名称", ws.getName());
        append(sb, "题材", ws.getGenre());
        append(sb, "时代", ws.getEra());
        append(sb, "地理", ws.getGeography());
        append(sb, "势力", ws.getFactions());
        append(sb, "规则", ws.getMagicSystem());
        append(sb, "文化", ws.getCulture());
        append(sb, "历史", ws.getHistory());
        append(sb, "自由文本", ws.getFreeText());
        return sb.toString();
    }

    private static void append(StringBuilder sb, String label, String content) {
        if (blank(content)) {
            return;
        }
        if (sb.length() > 0) {
            sb.append("\n\n");
        }
        sb.append("【").append(label).append("】\n").append(content.trim());
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
