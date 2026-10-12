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

package com.holzyn.actor.domain.knowledge.service;

import com.holzyn.actor.domain.knowledge.entity.ActorKnowledgeDoc;
import com.holzyn.actor.domain.knowledge.repository.ActorKnowledgeDocRepository;
import com.holzyn.actor.domain.world.entity.ActorWorldLocation;
import com.holzyn.actor.domain.world.entity.ActorWorldSetting;
import com.holzyn.actor.domain.world.repository.ActorWorldSettingRepository;
import com.holzyn.actor.domain.world.repository.WorldLocationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 世界设定知识同步器（知识库 RAG 适配， _03）。
 * <p>职责：把「项目级结构化世界设定」（{@link ActorWorldSetting} 最新版本各分段 + {@link ActorWorldLocation}
 * 地点志）幂等同步为<b>项目级知识文档</b>（characterId=null，所有角色可检索），并向量化入库——
 * 解决 RAG 适配核心缺口：知识库此前只有角色级「知识领域/盲区」清单，AI 检索不到世界设定全文
 * （地理/势力/规则/文化/历史/总览/地点志），「世界设定注入」目标无从谈起。</p>
 * <p><b>文档划分</b>（按主题分文档，标题即知识类别，利于检索命中与注入来源标注）：
 * 世界设定·总览（名称+题材+时代+freeText）/ 世界设定·地理 / 世界设定·势力 / 世界设定·规则 /
 * 世界设定·文化 / 世界设定·历史 / 世界地点志（地点名+类型+简介逐条）。空分段跳过；全部为空 → 零操作。</p>
 * <p><b>标题锚词引导句（_07 知识库内容层观察项）</b>：每份文档正文首行前置
 * 「【{标题}】…」引导句（含标题锚词，如「势力」「地理」「地点志」）——冒烟实证（09-29_04）：
 * 势力文档正文不含「势力」字样导致向量语义路与话题词路<b>均难命中</b>；正文含标题锚词后两路召回
 * 同时改善（对齐 Anthropic contextual retrieval 引导句思路：检索前为正文补一句主题上下文）。
 * 内容变化经幂等「内容未变跳过 / 变化更新+重向量化」自动覆盖存量文档，无需数据迁移。</p>
 * <p><b>幂等语义</b>：按 (projectId, characterId=null, title) 匹配既有文档——不存在则新建；
 * 存在且内容一致 → 跳过（不重复 embedding 调用，避免无谓用量）；内容变化 → 更新正文 + 重新向量化。</p>
 * <p><b>事务约束</b>：本类所有方法<b>不标注 @Transactional</b>——向量化含 embedding AI 调用，
 * 遵循「AI 在事务外、写阶段短事务」架构约束（H2 行锁）；调用方在事务内时必须经
 * <p>挂载点：① WorldInitService 世界初始化第 6 步前（新项目自动）；② ProjectService.saveWorldSetting
 * 事务提交后（编辑实时生效）；③ HolzynImportService 导入事务提交后（导入项目自动）；
 * ④ KnowledgeController 手动补建端点（既有项目/修复）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WorldKnowledgeSyncService {

    /** 世界设定文档标题（前缀「世界设定·」便于识别为项目级设定知识，与角色级「知识领域/盲区」区分） */
    static final String TITLE_OVERVIEW = "世界设定·总览";
    static final String TITLE_GEOGRAPHY = "世界设定·地理";
    static final String TITLE_FACTIONS = "世界设定·势力";
    static final String TITLE_RULES = "世界设定·规则";
    static final String TITLE_CULTURE = "世界设定·文化";
    static final String TITLE_HISTORY = "世界设定·历史";
    static final String TITLE_LOCATIONS = "世界地点志";

    /** 每份文档正文首行的「标题锚词引导句」（含标题锚词，正文含锚词后向量/话题词两路召回改善）。 */
    static final String ANCHOR_OVERVIEW = "【世界设定·总览】以下为这个世界整体设定的总览（名称、题材、时代与世界观概述）。";
    static final String ANCHOR_GEOGRAPHY = "【世界设定·地理】以下为这个世界的地理环境与地域分布设定。";
    static final String ANCHOR_FACTIONS = "【世界设定·势力】以下为这个世界各方势力的设定。";
    static final String ANCHOR_RULES = "【世界设定·规则】以下为这个世界运行规则的设定（力量体系、法则与禁忌）。";
    static final String ANCHOR_CULTURE = "【世界设定·文化】以下为这个世界文化与风俗的设定。";
    static final String ANCHOR_HISTORY = "【世界设定·历史】以下为这个世界历史沿革的设定。";
    static final String ANCHOR_LOCATIONS = "【世界地点志】以下为这个世界各处地点与场所的志书。";

    private final ActorWorldSettingRepository worldSettingRepository;
    private final WorldLocationRepository locationRepository;
    private final ActorKnowledgeDocRepository docRepository;
    private final KnowledgeVectorizerService vectorizerService;

    /**
     * 同步某项目的世界设定 → 项目级知识文档（幂等 upsert + 向量化）。
     *
     * @param userId 归属用户 ID（embedding 凭据归属）
     *
     * @param projectId 项目 ID
     *
     * @return 本次新建/更新的文档数（内容未变跳过不计数）
     */
    public int syncWorldSetting(Long userId, Long projectId) {
        if (projectId == null) {
            return 0;
        }
        ActorWorldSetting ws = worldSettingRepository.findTopByProjectIdOrderByVersionDesc(projectId).orElse(null);
        List<ActorWorldLocation> locations = locationRepository.findByProjectIdOrderBySortOrderAscIdAsc(projectId);
        Map<String, String> drafts = buildDrafts(ws, locations);
        if (drafts.isEmpty()) {
            log.info("[世界设定同步] 项目 {} 无世界设定/地点内容，跳过", projectId);
            return 0;
        }
        // 既有项目级文档按标题索引（幂等匹配键：projectId + characterId=null + title）
        Map<String, ActorKnowledgeDoc> existing = new LinkedHashMap<>();
        for (ActorKnowledgeDoc d : docRepository.findByProjectIdOrderByIdAsc(projectId)) {
            if (d != null && d.getCharacterId() == null && d.getTitle() != null) {
                existing.put(d.getTitle(), d);
            }
        }
        int synced = 0;
        for (Map.Entry<String, String> e : drafts.entrySet()) {
            String title = e.getKey();
            String content = e.getValue();
            ActorKnowledgeDoc doc = existing.get(title);
            if (doc == null) {
                doc = new ActorKnowledgeDoc();
                doc.setProjectId(projectId);
                doc.setTitle(title);
                doc.setContent(content);
                doc.setStatus(1);
                doc = docRepository.saveAndFlush(doc);
                log.info("[世界设定同步] 新建项目级知识文档：项目={} 文档={} 标题={} 字数={}",
                        projectId, doc.getId(), title, content.length());
            } else if (content.equals(doc.getContent())) {
                // 幂等：内容未变 → 不重复向量化（embedding 用量与检索缓存零扰动）
                continue;
            } else {
                doc.setContent(content);
                doc.setStatus(1);
                doc = docRepository.saveAndFlush(doc);
                log.info("[世界设定同步] 更新项目级知识文档：项目={} 文档={} 标题={} 字数={}",
                        projectId, doc.getId(), title, content.length());
            }
            // 向量化（本方法无事务；embedding 未配置/失败由 vectorizerService 内部降级，不阻断）
            try {
                vectorizerService.vectorize(userId, projectId, doc.getId(), title, null, content);
            } catch (Exception ex) {
                log.warn("[世界设定同步] 向量化失败（文档已落库，走文本降级）: 项目={} 标题={} : {}",
                        projectId, title, ex.getMessage());
            }
            synced++;
        }
        log.info("[世界设定同步] 结束：项目={} 本次同步 {} 份（设定源={} 地点={}）",
                projectId, synced, ws == null ? 0 : 1, locations == null ? 0 : locations.size());
        return synced;
    }

    /**
     * 组装世界设定知识文档草稿（纯逻辑，可单测）。
     *
     * @param ws 最新版本世界设定（可空）
     *
     * @param locations 地点列表（可空）
     *
     * @return 标题 → 内容（有序；空分段已剔除）
     */
    static Map<String, String> buildDrafts(ActorWorldSetting ws, List<ActorWorldLocation> locations) {
        Map<String, String> drafts = new LinkedHashMap<>();
        if (ws != null) {
            putIfNotBlank(drafts, TITLE_OVERVIEW, overviewText(ws));
            putIfNotBlank(drafts, TITLE_GEOGRAPHY, ws.getGeography());
            putIfNotBlank(drafts, TITLE_FACTIONS, ws.getFactions());
            putIfNotBlank(drafts, TITLE_RULES, ws.getMagicSystem());
            putIfNotBlank(drafts, TITLE_CULTURE, ws.getCulture());
            putIfNotBlank(drafts, TITLE_HISTORY, ws.getHistory());
        }
        String locText = locationsText(locations);
        if (!locText.isBlank()) {
            drafts.put(TITLE_LOCATIONS, withAnchor(TITLE_LOCATIONS, locText));
        }
        return drafts;
    }

    /**
     * 正文前置「标题锚词引导句」：首行含标题锚词（如「势力」「地理」「地点志」），
     * 解决「正文不含标题锚词 → 向量语义路 + 话题词向量路均难命中」的检索缺口
     * （_07 知识库内容层观察项；对齐 contextual-retrieval 引导句思路）。
     *
     * @param title 文档标题（锚词来源）
     *
     * @param content 原始正文（非空）
     *
     * @return 锚词引导句 + 换行 + 原文（未知标题防御性原样返回）
     */
    static String withAnchor(String title, String content) {
        String lead = anchorLeadIn(title);
        if (lead == null || lead.isBlank()) {
            return content;
        }
        return lead + "\n" + content;
    }

    /** 标题 → 锚词引导句（未知标题返回 null，防御：不走锚词）。 */
    static String anchorLeadIn(String title) {
        if (title == null) {
            return null;
        }
        return switch (title) {
            case TITLE_OVERVIEW -> ANCHOR_OVERVIEW;
            case TITLE_GEOGRAPHY -> ANCHOR_GEOGRAPHY;
            case TITLE_FACTIONS -> ANCHOR_FACTIONS;
            case TITLE_RULES -> ANCHOR_RULES;
            case TITLE_CULTURE -> ANCHOR_CULTURE;
            case TITLE_HISTORY -> ANCHOR_HISTORY;
            case TITLE_LOCATIONS -> ANCHOR_LOCATIONS;
            default -> null;
        };
    }

    /** 仅当内容非空时放入草稿（空分段跳过，不建空文档）；非空内容经 withAnchor 前置标题锚词引导句。 */
    private static void putIfNotBlank(Map<String, String> drafts, String title, String content) {
        if (content != null && !content.isBlank()) {
            drafts.put(title, withAnchor(title, content));
        }
    }

    /**
     * 总览文档组装：名称 + 题材/时代 + 完整世界观自由文本（freeText 为知识库注入源字段）。
     */
    static String overviewText(ActorWorldSetting ws) {
        StringBuilder sb = new StringBuilder();
        String name = ws.getName();
        if (name != null && !name.isBlank()) {
            sb.append("【").append(name.trim()).append("】");
        }
        String genre = ws.getGenre();
        String era = ws.getEra();
        if ((genre != null && !genre.isBlank()) || (era != null && !era.isBlank())) {
            sb.append("题材：").append(genre == null || genre.isBlank() ? "未知" : genre.trim());
            sb.append("；时代：").append(era == null || era.isBlank() ? "未知" : era.trim());
        }
        String free = ws.getFreeText();
        if (free != null && !free.isBlank()) {
            if (sb.length() > 0) {
                sb.append("\n");
            }
            sb.append(free.trim());
        }
        return sb.toString();
    }

    /**
     * 地点志文档组装：「地点名（类型）：简介」逐条换行（排序=sortOrder 升序 + id 升序）。
     */
    static String locationsText(List<ActorWorldLocation> locations) {
        if (locations == null || locations.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (ActorWorldLocation l : locations) {
            if (l == null || l.getName() == null || l.getName().isBlank()) {
                continue;
            }
            sb.append(l.getName().trim());
            if (l.getType() != null && !l.getType().isBlank()) {
                sb.append("（").append(l.getType().trim()).append("）");
            }
            sb.append("：");
            sb.append(l.getIntro() == null || l.getIntro().isBlank() ? "" : l.getIntro().trim());
            sb.append("\n");
        }
        return sb.toString().trim();
    }
}
