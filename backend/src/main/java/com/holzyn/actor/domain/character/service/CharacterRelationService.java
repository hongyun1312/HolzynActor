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

package com.holzyn.actor.domain.character.service;

import com.holzyn.actor.ai.AiCallException;
import com.holzyn.actor.ai.AiChatRequest;
import com.holzyn.actor.ai.AiChatResult;
import com.holzyn.actor.ai.spring.AiSpringChatGateway;
import com.holzyn.actor.common.BizException;
import com.holzyn.actor.common.JsonUtil;
import com.holzyn.actor.common.NpcRefs;
import com.holzyn.actor.domain.account.CurrentUserProvider;
import com.holzyn.actor.domain.agent.learning.RelationDynamicsService;
import com.holzyn.actor.domain.character.dto.RelationBatchDTO;
import com.holzyn.actor.domain.character.dto.RelationGenerateDTO;
import com.holzyn.actor.domain.character.dto.RelationWriteDTO;
import com.holzyn.actor.domain.npc.profile.entity.ActorCharacter;
import com.holzyn.actor.domain.npc.relation.entity.ActorCharacterRelation;
import com.holzyn.actor.domain.npc.relation.repository.ActorCharacterRelationRepository;
import com.holzyn.actor.domain.npc.profile.repository.ActorCharacterRepository;
import com.holzyn.actor.domain.character.vo.CharacterRelationVO;
import com.holzyn.actor.domain.character.vo.RelationDraftVO;
import com.holzyn.actor.domain.debug.service.AiCallContext;
import com.holzyn.actor.domain.settings.service.PromptTemplateService;
import com.holzyn.actor.domain.usage.service.UsageLogService;
import com.holzyn.actor.domain.world.entity.ActorWorldSetting;
import com.holzyn.actor.domain.world.repository.ActorWorldSettingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 角色关系 AI 生成与入库服务（角色页「关系拓扑」Tab + 全局拓扑页共用）。
 * <p>职责：① 生成预览（不落库）——按 scope 读取【项目世界观 + 项目现有角色/普通人群成员名单
 * （+单角色时的详细信息）】，AI 识别关系 JSON 数组后解析/清洗/去重返回预览；
 * ② 批量入库——mode=rebuild 先清空相关范围（单角色=该角色相关关系；全项目=整个项目关系表）
 * 再写入；写入时端点若命中已存在 NPC 则存 id+名，否则名称兜底（id=0 + from_name/to_name，
 * 后续在「补充新增角色」时按名称全表扫描回填 id）。</p>
 * <p>AI 用量记录 scene=relation_gen（用户/项目/角色维度）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CharacterRelationService {

    private final CharacterService characterService;
    private final ActorCharacterRelationRepository relationRepository;
    private final ActorCharacterRepository characterRepository;
    private final ActorCharacterRepository ordinaryNpcRepository;
    private final ActorWorldSettingRepository worldSettingRepository;
    private final CurrentUserProvider currentUserProvider;
    private final AiSpringChatGateway aiChatGateway;
    private final UsageLogService usageLogService;
    private final PromptTemplateService promptTemplateService;
    /** 动态关系服务（：状态机/状态中文标签复用；依赖方向 character→agent/learning 单向无环） */
    private final RelationDynamicsService relationDynamicsService;

    /** AI 输出 token 上限（关系 JSON 数组） */
    private static final int RELATION_MAX_TOKENS = 4000;
    /** AI 调用重试次数 */
    private static final int MAX_RETRY = 2;
    /** 世界观输入截断 */
    private static final int WORLD_MAX = 6000;
    /** 名单输入截断 */
    private static final int ROSTER_MAX = 4000;
    /** 单角色详细信息截断 */
    private static final int TARGET_DETAIL_MAX = 2000;
    /** 合法关系状态集合（状态机枚举） */
    private static final Set<String> VALID_STATES = Set.of(
            "stranger", "acquaintance", "friend", "intimate", "rival", "hostile");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * AI 生成关系预览（不落库； 支持普通型 NPC 范围）。
     *
     * @param projectId 项目 ID
     *
     * @param dto 生成入参（scope/mode/characterId/crowdId）
     *
     * @return 识别并清洗后的关系草稿列表
     */
    public List<RelationDraftVO> generate(Long projectId, RelationGenerateDTO dto) {
        Long userId = currentUserProvider.currentUserId();
        characterService.requireProjectOwned(projectId);
        String scope = dto == null || dto.scope() == null || dto.scope().isBlank() ? "character" : dto.scope();
        ActorCharacter target = null;
        ActorCharacter crowdTarget = null;
        if ("character".equals(scope)) {
            if (dto.characterId() == null) {
                throw new BizException(400, "请先选择一个角色，再生成该角色的关系");
            }
            target = characterService.requireOwned(dto.characterId());
            if (!projectId.equals(target.getProjectId())) {
                throw new BizException(400, "所选角色不属于当前项目");
            }
        } else if ("crowd".equals(scope)) {
            if (dto.crowdId() == null) {
                throw new BizException(400, "请先选择一个普通型 NPC，再生成其关系");
            }
            crowdTarget = ordinaryNpcRepository.findByIdAndProjectId(dto.crowdId(), projectId)
                    .orElseThrow(() -> new BizException(400, "所选普通型 NPC 不属于当前项目"));
        }
        String prompt = buildGeneratePrompt(projectId, scope, target, crowdTarget);
        // 控制台日志（对齐 ChatService [对话] / WorldLocationService [地点提取] 风格）：任务开始
        String targetName = target != null ? target.getName()
                : (crowdTarget != null ? crowdTarget.getName() : "全部角色");
        log.info("[关系生成] 任务开始：项目={} 范围={} 角色={}", projectId, scope, targetName);
        long start = System.currentTimeMillis();
        Exception lastError = null;
        for (int attempt = 1; attempt <= MAX_RETRY; attempt++) {
            try {
                AiChatRequest req = new AiChatRequest(null, List.of(
                        new AiChatRequest.ChatMessage("system", promptTemplateService.systemMessage(userId, projectId,
                                PromptTemplateService.CODE_RELATION_GEN, null)),
                        new AiChatRequest.ChatMessage("user", prompt)), 0.3, RELATION_MAX_TOKENS, true);
                AiChatResult result = AiCallContext.run("relation_gen", target == null ? null : target.getId(),
                        () -> aiChatGateway.chatCompletion(userId, projectId, null, req));
                List<RelationDraftVO> drafts = parseDrafts(result.content());
                // token 兜底为 0（供应商可能不返回 usage），对齐 [对话] tokens=x/y 的 int 展示
                int tokenIn = result.promptTokens() == null ? 0 : result.promptTokens();
                int tokenOut = result.completionTokens() == null ? 0 : result.completionTokens();
                // 控制台日志：AI 输出内容 + 任务结束（耗时 + token 消耗），对齐 [对话] 格式
                log.info("[关系生成] 范围={} 角色={}：{}", scope, targetName, result.content());
                log.info("[关系生成] 任务结束：项目={} 范围={} 角色={} 耗时={}ms tokens={}/{}",
                        projectId, scope, targetName, System.currentTimeMillis() - start, tokenIn, tokenOut);
                // 用量角色维度：特殊 NPC 记 id；普通型 NPC 不记角色 id（用量明细按特殊角色聚合，避免 id 冲突）
                usageLogService.record(userId, projectId, target == null ? null : target.getId(),
                        result.providerId(), result.model(), "relation_gen",
                        result.promptTokens(), result.completionTokens(),
                        result.cacheHitTokens(), result.cacheMissTokens(),
                        (int) (System.currentTimeMillis() - start));
                log.info("[关系生成] 项目 {} scope={} 第 {} 次成功：识别 {} 条", projectId, scope, attempt, drafts.size());
                return drafts;
            } catch (Exception e) {
                lastError = e;
                log.warn("[关系生成] 项目 {} 第 {} 次失败: {}", projectId, attempt, e.getMessage());
            }
        }
        log.warn("[关系生成] 任务失败：项目={} 范围={} 角色={} 耗时={}ms：{}",
                projectId, scope, targetName, System.currentTimeMillis() - start,
                lastError == null ? "未知错误" : lastError.getMessage());
        throw new BizException(400, "关系生成失败：" + friendlyError(lastError) + "，请稍后重试");
    }

    /**
     * 批量入库（预览确认后的写入）。
     * <p><b>幂等（init 关系幂等根治，2026-10-02）</b>：supplement 模式按「无序端点对 + 类型」
     * 归一化键与库中既有行去重（含同批内去重）——同一关系重复提交/初始化重跑不产生重复行；
     * 端点=ref 优先、其次 id>0、最后名称忽略大小写；方向翻转容忍（A→B 与 B→A 同键）；
     * 不同类型允许共存（如 A-B 既是师徒又是邻居）。rebuild 模式先清空相关范围天然幂等。</p>
     *
     * @param projectId 项目 ID
     *
     * @param dto 入库入参（mode/characterId/items）
     *
     * @return {added 实际写入条数, skipped 幂等去重跳过条数, total 提交条数}
     */
    @Transactional
    public Map<String, Object> batchSave(Long projectId, RelationBatchDTO dto) {
        characterService.requireProjectOwned(projectId);
        String mode = dto == null || dto.mode() == null || dto.mode().isBlank() ? "supplement" : dto.mode();
        List<RelationDraftVO> items = dto == null || dto.items() == null ? List.of() : dto.items();

        // ===== 重建：清空相关范围（特殊 NPC 按 id/名称；普通型 NPC 按名称；全项目整表） =====
        if ("rebuild".equals(mode)) {
            List<ActorCharacterRelation> all = relationRepository.findByProjectId(projectId);
            if (dto != null && dto.crowdId() != null) {
                // 单普通型 NPC 重建：清空与该普通 NPC（按名称匹配，其关系以名称兜底存储）相关的关系
                ActorCharacter crowd = ordinaryNpcRepository.findByIdAndProjectId(dto.crowdId(), projectId)
                        .orElseThrow(() -> new BizException(400, "所选普通型 NPC 不属于当前项目"));
                List<ActorCharacterRelation> toDelete = all.stream()
                        .filter(r -> crowd.getName().equals(r.getFromName()) || crowd.getName().equals(r.getToName()))
                        .toList();
                if (!toDelete.isEmpty()) relationRepository.deleteAll(toDelete);
            } else if (dto != null && dto.characterId() != null) {
                // 单特殊角色重建：清空与该角色相关（id 或名称匹配）的关系
                ActorCharacter target = characterService.requireOwned(dto.characterId());
                List<ActorCharacterRelation> toDelete = all.stream()
                        .filter(r -> target.getId().equals(r.getFromCharacterId())
                                || target.getId().equals(r.getToCharacterId())
                                || target.getName().equals(r.getFromName())
                                || target.getName().equals(r.getToName()))
                        .toList();
                if (!toDelete.isEmpty()) relationRepository.deleteAll(toDelete);
            } else {
                // 全项目重建：清空整个项目关系表
                if (!all.isEmpty()) relationRepository.deleteAll(all);
            }
        }

        // ===== 幂等去重（init 关系幂等根治）：归一化键=无序端点对+类型，与库中既有行比对跳过 =====
        // rebuild 已先清空相关范围（剩余行为基准）；同批内去重复用同一键集（先写入即入键）
        Set<String> existingKeys = new HashSet<>();
        for (ActorCharacterRelation r : relationRepository.findByProjectId(projectId)) {
            existingKeys.add(relationKey(new RelationRefs.Endpoint(r.getFromRef(), r.getFromCharacterId(), r.getFromName()),
                    new RelationRefs.Endpoint(r.getToRef(), r.getToCharacterId(), r.getToName()),
                    r.getRelationType()));
        }
        // ===== 写入：端点按名称解析——命中特殊/普通 NPC → 存 id/名 + 正式 ref（修）；否则幽灵名称兜底 id=0 =====
        int added = 0;
        int skipped = 0;
        for (RelationDraftVO item : items) {
            String from = item == null || item.from() == null ? "" : item.from().trim();
            String to = item == null || item.to() == null ? "" : item.to().trim();
            String type = item == null || item.relationType() == null || item.relationType().isBlank()
                    ? "未知关系" : item.relationType().trim();
            if (from.isEmpty() || to.isEmpty() || from.equals(to)) continue;
            // ：按名称解析端点（命中 actor_profile → npc:{id}；都不命中 → 幽灵名称）；：npc:{id} 不再编码类型，此处按意图 A 处理
            RelationRefs.Endpoint fromEp = RelationRefs.resolve(projectId, from, characterRepository);
            RelationRefs.Endpoint toEp = RelationRefs.resolve(projectId, to, characterRepository);
            if (fromEp == null || toEp == null) continue;
            // 治理批次 0 · 卡5 亡偶守卫（G-REMARRY-02）：婚恋类关系（ROMANTIC/MARRIAGE）不得指向已故角色——
            // 亡偶不进入 AI 精修/draft 入库候选（跳过 + 告警；生者候选正常）
            if (isRomanceType(type) && hasDeceasedEndpoint(fromEp, toEp)) {
                log.warn("[关系入库] 亡偶守卫：跳过 {} → {}（{}）——婚恋关系不得指向已故角色", from, to, type);
                continue;
            }
            // 幂等去重：同端点对（不分方向）+ 同类型已存在 → 跳过（含同批内先写入的）
            if (!existingKeys.add(relationKey(fromEp, toEp, type))) {
                skipped++;
                continue;
            }
            ActorCharacterRelation r = new ActorCharacterRelation();
            r.setProjectId(projectId);
            applyEndpoints(r, fromEp, toEp);
            r.setRelationType(type.length() > 50 ? type.substring(0, 50) : type);
            String desc = item.description() == null ? "" : item.description().trim();
            r.setDescription(desc.length() > 255 ? desc.substring(0, 255) : (desc.isEmpty() ? null : desc));
            relationRepository.save(r);
            added++;
        }
        log.info("[关系入库] 项目 {} mode={} 写入 {} 条，去重跳过 {} 条（提交 {} 条）",
                projectId, mode, added, skipped, items.size());
        Map<String, Object> result = new HashMap<>();
        result.put("added", added);
        result.put("skipped", skipped);
        result.put("total", items.size());
        return result;
    }

    // ==================== 角色关系手动 CRUD（用户：手动增删改，端点支持 characterRef） ====================

    /**
     * 手动新增关系（，用户：角色关系手动 CRUD）。
     * <p>端点支持 characterRef 与名称兜底：
     * 纯名称若命中项目内角色自动转正式 ref，否则以幽灵（id=0+名称）存储；
     * affinity/trust/relation_state 缺省 0/0/按状态机计算。</p>
     *
     * @param projectId 项目 ID
     *
     * @param dto 写入入参（fromRef/toRef 或 fromName/toName 至少其一）
     *
     * @return 新增后的关系行 VO
     */
    @Transactional
    public CharacterRelationVO create(Long projectId, RelationWriteDTO dto) {
        characterService.requireProjectOwned(projectId);
        RelationWriteDTO d = dto == null ? new RelationWriteDTO(null, null, null, null, null, null, null, null, null) : dto;
        RelationRefs.Endpoint from = resolveEndpoint(d.fromRef(), d.fromName(), projectId);
        RelationRefs.Endpoint to = resolveEndpoint(d.toRef(), d.toName(), projectId);
        if (from == null || to == null || sameEndpoint(from, to)) {
            throw new BizException(400, "关系两端必须填写且不能相同（支持 npc:{id} 或角色名）");
        }
        ActorCharacterRelation rel = new ActorCharacterRelation();
        rel.setProjectId(projectId);
        applyEndpoints(rel, from, to);
        rel.setRelationType(cleanType(d.relationType()));
        rel.setDescription(cleanDesc(d.description()));
        int affinity = clamp(d.affinity() == null ? 0 : d.affinity(), -100, 100);
        int trust = clamp(d.trust() == null ? 0 : d.trust(), 0, 100);
        rel.setAffinity(affinity);
        rel.setTrust(trust);
        rel.setRelationState(validState(d.relationState(), RelationDynamicsService.stateOf(affinity, trust)));
        ActorCharacterRelation saved = relationRepository.save(rel);
        log.info("[关系CRUD] 新增关系：project={} id={} {}→{}（{}）好感={} 信任={} 状态={}",
                projectId, saved.getId(), saved.getFromName(), saved.getToName(), saved.getRelationType(),
                saved.getAffinity(), saved.getTrust(), saved.getRelationState());
        // 实时广播：另一个标签页/协作者的拓扑页要能自己跟上（纯新增事件，不改任何既有语义）
        relationDynamicsService.publishManualRelationEvent(projectId, saved, "create");
        return toVO(saved);
    }

    /**
     * 手动更新关系（，用户）：端点可改（显式提供则重新解析，缺省保留原端点），
     * 类型/描述/好感/信任/状态可改；状态缺省按好感/信任状态机重算。
     *
     * @param relationId 关系主键
     *
     * @param dto 写入入参（可空字段=保留原值）
     *
     * @return 更新后的关系行 VO
     */
    @Transactional
    public CharacterRelationVO update(Long relationId, RelationWriteDTO dto) {
        ActorCharacterRelation rel = relationRepository.findById(relationId)
                .orElseThrow(() -> new BizException(404, "关系不存在：" + relationId));
        RelationWriteDTO d = dto == null ? new RelationWriteDTO(null, null, null, null, null, null, null, null, null) : dto;
        boolean fromChanged = isPresent(d.fromRef()) || isPresent(d.fromName());
        boolean toChanged = isPresent(d.toRef()) || isPresent(d.toName());
        if (fromChanged || toChanged) {
            RelationRefs.Endpoint from = fromChanged
                    ? resolveEndpoint(d.fromRef(), d.fromName(), rel.getProjectId())
                    : existingEndpoint(rel.getFromRef(), rel.getFromCharacterId(), rel.getFromName());
            RelationRefs.Endpoint to = toChanged
                    ? resolveEndpoint(d.toRef(), d.toName(), rel.getProjectId())
                    : existingEndpoint(rel.getToRef(), rel.getToCharacterId(), rel.getToName());
            if (from == null || to == null || sameEndpoint(from, to)) {
                throw new BizException(400, "关系两端不能为空或相同");
            }
            applyEndpoints(rel, from, to);
        }
        if (d.relationType() != null) {
            rel.setRelationType(cleanType(d.relationType()));
        }
        if (d.description() != null) {
            rel.setDescription(cleanDesc(d.description()));
        }
        if (d.affinity() != null) {
            rel.setAffinity(clamp(d.affinity(), -100, 100));
        }
        if (d.trust() != null) {
            rel.setTrust(clamp(d.trust(), 0, 100));
        }
        int affinity = rel.getAffinity() == null ? 0 : rel.getAffinity();
        int trust = rel.getTrust() == null ? 0 : rel.getTrust();
        rel.setRelationState(validState(d.relationState(), RelationDynamicsService.stateOf(affinity, trust)));
        ActorCharacterRelation saved = relationRepository.save(rel);
        log.info("[关系CRUD] 更新关系：id={} {}→{}（{}）好感={} 信任={} 状态={}",
                saved.getId(), saved.getFromName(), saved.getToName(), saved.getRelationType(),
                saved.getAffinity(), saved.getTrust(), saved.getRelationState());
        relationDynamicsService.publishManualRelationEvent(saved.getProjectId(), saved, "update");
        return toVO(saved);
    }

    /**
     * 手动删除关系（，用户）。
     *
     * @param relationId 关系主键
     */
    @Transactional
    public void delete(Long relationId) {
        ActorCharacterRelation rel = relationRepository.findById(relationId)
                .orElseThrow(() -> new BizException(404, "关系不存在：" + relationId));
        Long projectId = rel.getProjectId();
        relationRepository.delete(rel);
        log.info("[关系CRUD] 删除关系：id={} {}→{}（{}）", rel.getId(), rel.getFromName(), rel.getToName(), rel.getRelationType());
        relationDynamicsService.publishManualRelationEvent(projectId, rel, "delete");
    }

    /**
     * 关系 AI 精修：
     * 把「当前状态+数值+双方档案+世界观」作为提示词交 AI 综合判断好感/信任/状态，
     * 解析结果钳制校验后落库。scene=relation_refine，模板 code=relation_refine（可编辑）。
     *
     * @param projectId 项目 ID
     *
     * @param relationId 关系主键
     *
     * @return 精修后的关系行 VO
     */
    @Transactional
    public CharacterRelationVO aiRefine(Long projectId, Long relationId) {
        characterService.requireProjectOwned(projectId);
        ActorCharacterRelation rel = relationRepository.findById(relationId)
                .orElseThrow(() -> new BizException(404, "关系不存在：" + relationId));
        if (!projectId.equals(rel.getProjectId())) {
            throw new BizException(400, "关系不属于当前项目");
        }
        Long userId = currentUserProvider.currentUserId();
        Map<String, Object> placeholders = new HashMap<>();
        placeholders.put("from_info", endpointInfo(rel.getFromRef(), rel.getFromCharacterId(), rel.getFromName()));
        placeholders.put("to_info", endpointInfo(rel.getToRef(), rel.getToCharacterId(), rel.getToName()));
        placeholders.put("current_relation", currentRelationText(rel));
        String world = worldSettingRepository.findTopByProjectIdOrderByVersionDesc(projectId)
                .map(this::renderWorld)
                .orElse("（项目暂无世界观设定）");
        placeholders.put("world_setting", world);
        String prompt = promptTemplateService.render(userId, projectId,
                PromptTemplateService.CODE_RELATION_REFINE, placeholders);
        String sys = promptTemplateService.systemMessage(userId, projectId,
                PromptTemplateService.CODE_RELATION_REFINE, null);
        List<AiChatRequest.ChatMessage> messages = new ArrayList<>();
        if (sys != null && !sys.isBlank()) {
            messages.add(new AiChatRequest.ChatMessage("system", sys));
        }
        messages.add(new AiChatRequest.ChatMessage("user", prompt));
        log.info("[关系精修] 任务开始：项目={} relation={}（{}→{}）", projectId, relationId,
                rel.getFromName(), rel.getToName());
        long start = System.currentTimeMillis();
        Exception lastError = null;
        for (int attempt = 1; attempt <= MAX_RETRY; attempt++) {
            try {
                AiChatResult result = AiCallContext.run("relation_refine", null,
                        () -> aiChatGateway.chatCompletion(userId, projectId, null,
                                new AiChatRequest(null, messages, 0.3, 1024)));
                JsonNode node = parseRefineResult(result.content());
                int affinity = clamp(node.path("affinity").asInt(rel.getAffinity() == null ? 0 : rel.getAffinity()),
                        -100, 100);
                int trust = clamp(node.path("trust").asInt(rel.getTrust() == null ? 0 : rel.getTrust()), 0, 100);
                String state = validState(node.path("relationState").asText(null),
                        RelationDynamicsService.stateOf(affinity, trust));
                rel.setAffinity(affinity);
                rel.setTrust(trust);
                rel.setRelationState(state);
                if (node.hasNonNull("description")) {
                    rel.setDescription(cleanDesc(node.path("description").asText()));
                }
                ActorCharacterRelation saved = relationRepository.save(rel);
                usageLogService.record(userId, projectId, null, result.providerId(), result.model(), "relation_refine",
                        result.promptTokens(), result.completionTokens(),
                        result.cacheHitTokens(), result.cacheMissTokens(),
                        (int) (System.currentTimeMillis() - start));
                log.info("[关系精修] 任务结束：relation={} {}→{} 好感={} 信任={} 状态={} 耗时={}ms tokens={}/{}",
                        saved.getId(), saved.getFromName(), saved.getToName(),
                        saved.getAffinity(), saved.getTrust(), saved.getRelationState(),
                        System.currentTimeMillis() - start,
                        result.promptTokens() == null ? 0 : result.promptTokens(),
                        result.completionTokens() == null ? 0 : result.completionTokens());
                return toVO(saved);
            } catch (Exception e) {
                lastError = e;
                log.warn("[关系精修] 第 {} 次失败：relation={} : {}", attempt, relationId, e.getMessage());
            }
        }
        throw new BizException(400, "关系 AI 精修失败：" + friendlyError(lastError) + "，请稍后重试");
    }

    /**
     * 解析 AI 精修输出 JSON（纯静态可测）：剥代码块/说明文字，取顶层对象的
     * affinity/trust/relationState/description 字段。
     *
     * @param content AI 输出文本
     *
     * @return 顶层 JSON 对象节点（解析失败返回空对象节点）
     */
    static JsonNode parseRefineResult(String content) {
        JsonNode node = parseQuiet(JsonUtil.extractJson(content));
        if (node == null || !node.isObject()) {
            return MAPPER.createObjectNode();
        }
        return node;
    }

    /** 关系行 → VO（含状态中文标签） */
    private CharacterRelationVO toVO(ActorCharacterRelation r) {
        return new CharacterRelationVO(r.getId(), r.getProjectId(),
                r.getFromRef(), r.getToRef(), r.getFromName(), r.getToName(),
                r.getRelationType(), r.getDescription(),
                r.getAffinity(), r.getTrust(), r.getRelationState(),
                RelationDynamicsService.stateLabel(r.getRelationState()),
                r.getUpdatedAt(), r.getLastInteractionAt());
    }

    /** 解析端点：ref 优先，其次名称（纯名称命中项目内角色转正式 ref，否则幽灵） */
    private RelationRefs.Endpoint resolveEndpoint(String ref, String name, Long projectId) {
        String raw = ref != null && !ref.isBlank() ? ref : (name != null && !name.isBlank() ? name : null);
        if (raw == null) {
            return null;
        }
        // ：npc:{id} 不再编码类型，此处按意图 A 处理（单表 actor_profile 解析）
        return RelationRefs.resolve(projectId, raw, characterRepository);
    }

    /** 由存量关系行构建端点（未显式改端点的更新路径） */
    private static RelationRefs.Endpoint existingEndpoint(String ref, Long id, String name) {
        return new RelationRefs.Endpoint(ref, id != null && id > 0 ? id : null, name);
    }

    /** 按解析结果写入关系行端点（special 存 id+名+ref；crowd 存名+ref；幽灵存名） */
    private static void applyEndpoints(ActorCharacterRelation rel, RelationRefs.Endpoint from, RelationRefs.Endpoint to) {
        RelationRefs.Store fs = RelationRefs.storeOf(from);
        RelationRefs.Store ts = RelationRefs.storeOf(to);
        rel.setFromCharacterId(fs.characterId());
        rel.setFromName(fs.name());
        rel.setFromRef(fs.ref());
        rel.setToCharacterId(ts.characterId());
        rel.setToName(ts.name());
        rel.setToRef(ts.ref());
    }

    /** 是否婚恋类关系类型（亡偶守卫只拦这类——AI 可为亡者保留亲属/师徒等非婚恋关系） */
    private static boolean isRomanceType(String type) {
        return "ROMANTIC".equals(type) || "MARRIAGE".equals(type);
    }

    /** 端点是否已故（正式端点查 actor_profile.stage=DECEASED；幽灵/无 id 端点无法判定=不拦） */
    private boolean hasDeceasedEndpoint(RelationRefs.Endpoint from, RelationRefs.Endpoint to) {
        return isDeceasedEndpoint(from) || isDeceasedEndpoint(to);
    }

    private boolean isDeceasedEndpoint(RelationRefs.Endpoint ep) {
        if (ep == null || ep.id() == null || ep.id() <= 0) {
            return false;
        }
        try {
            ActorCharacter c = characterRepository.findById(ep.id()).orElse(null);
            return c != null && "DECEASED".equalsIgnoreCase(c.getStage());
        } catch (Exception e) {
            return false;
        }
    }

    /** 两端是否同一端点（ref 相等 / id 相等 / 名称忽略大小写相等） */
    private static boolean sameEndpoint(RelationRefs.Endpoint a, RelationRefs.Endpoint b) {
        if (a == null || b == null) {
            return false;
        }
        if (a.ref() != null && a.ref().equals(b.ref())) {
            return true;
        }
        if (a.id() != null && a.id() > 0 && a.id().equals(b.id())) {
            return true;
        }
        return a.name() != null && b.name() != null
                && a.name().trim().equalsIgnoreCase(b.name().trim());
    }

    /**
     * 幂等去重键（无序端点对 + 类型，2026-10-02 init 关系幂等根治）。
     * <p>端点归一化 = <b>名称优先</b>（幽灵 id=0 与正式端点同名同键=跨形态去重：
     * 同一逻辑关系先以幽灵入库、角色建档后重跑补充不重复）→ ref → id&gt;0；
     * 两端排序后拼接（A→B 与 B→A 同键=方向翻转容忍）；类型空白按空串（不同类型允许共存）。</p>
     *
     * @param from 关系发起端（已解析端点）
     *
     * @param to 关系目标端（已解析端点）
     *
     * @param type 关系类型（可空）
     *
     * @return 去重键（同端点对同类型恒同键）
     */
    private static String relationKey(RelationRefs.Endpoint from, RelationRefs.Endpoint to, String type) {
        String a = endpointKey(from);
        String b = endpointKey(to);
        String pair = a.compareTo(b) <= 0 ? a + "|" + b : b + "|" + a;
        return pair + "|" + (type == null ? "" : type.trim());
    }

    /** 端点归一化键（名称优先[幽灵与正式端点同键=跨形态去重]，其次 ref，最后 id>0）。 */
    private static String endpointKey(RelationRefs.Endpoint ep) {
        if (ep == null) {
            return "";
        }
        if (ep.name() != null && !ep.name().isBlank()) {
            return "n:" + ep.name().trim().toLowerCase();
        }
        if (ep.ref() != null && !ep.ref().isBlank()) {
            return "r:" + ep.ref();
        }
        if (ep.id() != null && ep.id() > 0) {
            return "i:" + ep.id();
        }
        return "";
    }

    /** 端点信息段（AI 精修 Prompt 用：特殊 NPC 档案 / 普通 NPC 档案 / 幽灵提示） */
    private String endpointInfo(String ref, Long id, String name) {
        String display = name == null || name.isBlank() ? "未知" : name;
        // ：npc:{id} 不再编码类型，此处按意图 B 处理（先解析 actor_profile.id，再按行内 type 选字段口径）
        Long npcId = NpcRefs.idOf(ref);
        if (npcId == null && id != null && id > 0) {
            npcId = id;
        }
        ActorCharacter c = npcId == null ? null : characterRepository.findById(npcId).orElse(null);
        if (c != null && NpcRefs.isCommonType(c.getType())) {
            return "姓名：" + c.getName()
                    + "，种族：" + (c.getRace() == null || c.getRace().isBlank() ? "未知" : c.getRace())
                    + (c.getSubRace() == null || c.getSubRace().isBlank() ? "" : " / " + c.getSubRace())
                    + "，职业：" + (c.getOccupation() == null || c.getOccupation().isBlank() ? "未知" : c.getOccupation())
                    + "，归属：" + (c.getAffiliation() == null || c.getAffiliation().isBlank() ? "未知" : c.getAffiliation())
                    + "，所在地：" + (c.getLocation() == null || c.getLocation().isBlank() ? "未知" : c.getLocation())
                    + "\n详细信息：" + (c.getDetail() == null || c.getDetail().isBlank()
                    ? "（未提供）" : c.getDetail());
        }
        if (c != null) {
            return "姓名：" + c.getName()
                    + "，头衔：" + (c.getTitle() == null || c.getTitle().isBlank() ? "无" : c.getTitle())
                    + "，类型：特殊NPC，重要度：" + (c.getImportance() == null ? "未标注" : c.getImportance())
                    + "\n详细信息：" + (c.getDetail() == null || c.getDetail().isBlank()
                    ? "（未提供）" : c.getDetail());
        }
        return "姓名：" + display + "（待补充角色：actor_profile 无档案，仅名称）";
    }

    /** 当前关系文本 */
    private static String currentRelationText(ActorCharacterRelation rel) {
        return (rel.getFromName() == null ? "未知" : rel.getFromName()) + " 对 "
                + (rel.getToName() == null ? "未知" : rel.getToName())
                + "：关系类型=" + (rel.getRelationType() == null ? "未知关系" : rel.getRelationType())
                + "；描述=" + (rel.getDescription() == null || rel.getDescription().isBlank() ? "无" : rel.getDescription())
                + "；好感=" + (rel.getAffinity() == null ? 0 : rel.getAffinity()) + "（-100~100）"
                + "；信任=" + (rel.getTrust() == null ? 0 : rel.getTrust()) + "（0~100）"
                + "；关系状态=" + RelationDynamicsService.stateLabel(rel.getRelationState());
    }

    /** 关系类型清洗（截断 50；空回退「未知关系」） */
    private static String cleanType(String type) {
        if (type == null || type.isBlank()) {
            return "未知关系";
        }
        return type.trim().length() > 50 ? type.trim().substring(0, 50) : type.trim();
    }

    /** 关系描述清洗（截断 255；空返回 null） */
    private static String cleanDesc(String desc) {
        if (desc == null || desc.isBlank()) {
            return null;
        }
        String t = desc.trim();
        return t.length() > 255 ? t.substring(0, 255) : t;
    }

    /** 状态校验：合法集合内原样，否则回退状态机计算值 */
    private static String validState(String state, String fallback) {
        if (state == null || state.isBlank()) {
            return fallback;
        }
        String s = state.trim().toLowerCase();
        return VALID_STATES.contains(s) ? s : fallback;
    }

    /** 数值钳制 */
    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    /** 字符串是否非空白 */
    private static boolean isPresent(String s) {
        return s != null && !s.isBlank();
    }

    /**
     * 解析 AI 输出的关系 JSON（纯静态，可独立单测）。
     * <p>兼容：顶层数组 / 顶层对象含 relations 数组 / Markdown 代码块包裹 / 首尾说明文字；
     * 清洗规则：from/to 非空且不等、关系类型缺省为「未知关系」、按 from|to|type 去重、字段长度截断。</p>
     *
     * @param content AI 输出文本
     *
     * @return 清洗后的关系草稿列表
     */
    static List<RelationDraftVO> parseDrafts(String content) {
        if (content == null || content.isBlank()) return List.of();
        JsonNode node = parseQuiet(JsonUtil.extractJson(content));
        // JsonUtil 对「说明文字 + JSON 数组」只截出第一个对象：若顶层对象不含 relations 数组，
        // 再尝试宽松的数组/对象提取（剥代码块 + 取首个 [..]）
        if (node == null || (node.isObject() && !node.path("relations").isArray())) {
            JsonNode wider = parseQuiet(extractArrayOrObject(content));
            if (wider != null) node = wider;
        }
        if (node == null) return List.of();
        JsonNode arr = node.isArray() ? node : node.path("relations");
        if (!arr.isArray() || arr.isEmpty()) return List.of();
        List<RelationDraftVO> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (JsonNode item : arr) {
            if (item == null || !item.isObject()) continue;
            String from = text(item, "from").trim();
            String to = text(item, "to").trim();
            if (from.isEmpty() || to.isEmpty() || from.equals(to)) continue;
            String type = text(item, "relationType");
            if (type.isEmpty()) type = text(item, "relation_type");
            if (type.isEmpty()) type = "未知关系";
            String desc = text(item, "description");
            String key = from + "|" + to + "|" + type;
            if (!seen.add(key)) continue;
            out.add(new RelationDraftVO(from, to,
                    type.length() > 50 ? type.substring(0, 50) : type,
                    desc.length() > 255 ? desc.substring(0, 255) : (desc.isEmpty() ? null : desc)));
        }
        return out;
    }

    /** 宽松提取 JSON 数组/对象：剥代码块 + 取首个 [..] 或 {..}（兼容「说明文字 + 代码块 + JSON」输出） */
    private static String extractArrayOrObject(String content) {
        String t = content.replaceAll("(?s)```[a-zA-Z0-9]*\\s*", "").replace("```", "");
        int start = t.indexOf('[');
        int end = t.lastIndexOf(']');
        if (start >= 0 && end > start) return t.substring(start, end + 1);
        int s = t.indexOf('{');
        int e = t.lastIndexOf('}');
        if (s >= 0 && e > s) return t.substring(s, e + 1);
        return null;
    }

    /** 静默解析 JSON（失败返回 null） */
    private static JsonNode parseQuiet(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return MAPPER.readTree(json);
        } catch (Exception e) {
            return null;
        }
    }

    /** 节点字段安全取文本 */
    private static String text(JsonNode item, String field) {
        JsonNode v = item.path(field);
        return v.isMissingNode() || v.isNull() ? "" : v.asText();
    }

    /**
     * 组装 AI 生成 Prompt（改用 Prompt 模板表，code=relation_gen 三级回退渲染）。
     * <p>占位符：world_setting 世界观 / roster 现有名单 / target_section 单角色信息段（可空）/
     * task_requirement 范围任务要求。业务侧仍负责读取世界观与名单，模板文本可在「设置→Prompt」编辑。
     * 单角色范围支持特殊 NPC（target）与普通型 NPC（crowdTarget）两种目标。</p>
     *
     * @param projectId 项目 ID
     *
     * @param target 单角色范围的特殊 NPC 目标（可空）
     *
     * @param crowdTarget 单角色范围的普通型 NPC 目标（可空）
     *
     * @return 完整 Prompt 文本
     */
    private String buildGeneratePrompt(Long projectId, String scope, ActorCharacter target, ActorCharacter crowdTarget) {
        Long userId = currentUserProvider.currentUserId();
        Map<String, Object> placeholders = new HashMap<>();
        // 世界观
        String world = worldSettingRepository.findTopByProjectIdOrderByVersionDesc(projectId)
                .map(this::renderWorld)
                .orElse("（项目暂无世界观设定）");
        placeholders.put("world_setting", world);
        // 现有名单（NPC + 普通人群成员）
        placeholders.put("roster", buildRoster(projectId));
        // 单角色范围：附加目标角色信息段 + 任务要求；全项目范围只给任务要求
        if (target != null) {
            placeholders.put("target_section", buildTargetSection(target));
            placeholders.put("task_requirement", "【任务要求】请识别「" + target.getName()
                    + "」与名单内外其他角色之间的所有关系（双向：该角色既可以是关系发起方 from，也可以是目标方 to），不遗漏任何明确的关系。");
        } else if (crowdTarget != null) {
            placeholders.put("target_section", buildCrowdTargetSection(crowdTarget));
            placeholders.put("task_requirement", "【任务要求】请识别「" + crowdTarget.getName()
                    + "」与名单内外其他角色之间的所有关系（双向：该角色既可以是关系发起方 from，也可以是目标方 to；可关联特殊 NPC、普通型 NPC 或世界观中提到但未创建的角色），不遗漏任何明确的关系。");
        } else {
            placeholders.put("target_section", "");
            placeholders.put("task_requirement", "【任务要求】请识别该世界观下所有角色之间的完整关系网络（双向、尽量全面），包括名单外但世界观中明确提到的角色。");
        }
        return promptTemplateService.render(userId, projectId, PromptTemplateService.CODE_RELATION_GEN, placeholders);
    }

    /**
     * 现有角色名单（NPC + 普通型 NPC，普通人群重构后普通 NPC 以具体人名入名单）。
     *
     * @param projectId 项目 ID
     *
     * @return 名单文本（空则返回「（暂无）」）
     */
    private String buildRoster(Long projectId) {
        StringBuilder roster = new StringBuilder();
        List<ActorCharacter> npcs = characterRepository.findByProjectIdAndDeletedOrderByIdAsc(projectId, 0);
        for (ActorCharacter c : npcs) {
            roster.append("- ").append(c.getName())
                    .append(c.getTitle() == null || c.getTitle().isBlank() ? "" : "（" + c.getTitle() + "）")
                    .append("common".equals(c.getType()) ? " [普通NPC]" : " [特殊NPC]").append("\n");
        }
        for (ActorCharacter n : ordinaryNpcRepository.findByProjectIdOrderByIdAsc(projectId)) {
            roster.append("- ").append(n.getName())
                    .append("（普通型：")
                    .append(n.getOccupation() == null || n.getOccupation().isBlank() ? "职业未知" : n.getOccupation())
                    .append("）\n");
        }
        return roster.length() == 0 ? "（暂无）\n" : roster.toString();
    }

    /**
     * 单特殊 NPC 信息段（姓名/头衔/类型/重要度/详细信息）。
     *
     * @param target 目标角色
     *
     * @return 信息段文本
     */
    private String buildTargetSection(ActorCharacter target) {
        StringBuilder sb = new StringBuilder();
        sb.append("【当前角色】\n姓名：").append(target.getName())
                .append("，头衔：").append(target.getTitle() == null || target.getTitle().isBlank() ? "无" : target.getTitle())
                .append("，类型：").append("common".equals(target.getType()) ? "普通NPC" : "特殊NPC")
                .append("，重要度：").append(target.getImportance() == null ? "未标注" : target.getImportance())
                .append("\n详细信息：").append(target.getDetail() == null || target.getDetail().isBlank()
                        ? "（未提供）" : target.getDetail())
                .append("\n\n");
        return sb.toString();
    }

    /**
     * 单普通型 NPC 信息段（姓名/种族/次级种族/归属/职业/所在地/详情；）。
     *
     * @param target 目标普通型 NPC
     *
     * @return 信息段文本
     */
    private String buildCrowdTargetSection(ActorCharacter target) {
        StringBuilder sb = new StringBuilder();
        sb.append("【当前角色】\n姓名：").append(target.getName())
                .append("，种族：").append(target.getRace() == null || target.getRace().isBlank() ? "未知" : target.getRace())
                .append(target.getSubRace() == null || target.getSubRace().isBlank() ? "" : " / " + target.getSubRace())
                .append("，归属：").append(target.getAffiliation() == null || target.getAffiliation().isBlank() ? "未知" : target.getAffiliation())
                .append("，职业：").append(target.getOccupation() == null || target.getOccupation().isBlank() ? "未知" : target.getOccupation())
                .append("，所在地：").append(target.getLocation() == null || target.getLocation().isBlank() ? "未知" : target.getLocation())
                .append("\n详细信息：").append(target.getDetail() == null || target.getDetail().isBlank()
                        ? "（未提供）" : target.getDetail())
                .append("\n\n");
        return sb.toString();
    }

    /** 世界观实体 → 紧凑文本 */
    private String renderWorld(ActorWorldSetting w) {
        StringBuilder t = new StringBuilder();
        appendIf(t, "名称", w.getName());
        appendIf(t, "题材", w.getGenre());
        appendIf(t, "时代", w.getEra());
        appendIf(t, "地理", w.getGeography());
        appendIf(t, "势力", w.getFactions());
        appendIf(t, "力量体系", w.getMagicSystem());
        appendIf(t, "文化", w.getCulture());
        appendIf(t, "历史", w.getHistory());
        appendIf(t, "补充设定", w.getFreeText());
        return t.length() == 0 ? "（世界观字段为空）" : t.toString();
    }

    /** 世界观字段追加（空跳过） */
    private void appendIf(StringBuilder sb, String label, String value) {
        if (value == null || value.isBlank()) return;
        sb.append(label).append("：").append(value).append("\n");
    }

    /** 字符串截断（null 安全） */
    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max);
    }

    /** 供应商错误文案收敛（AI 调用失败 → 友好提示） */
    private String friendlyError(Exception e) {
        if (e instanceof AiCallException ae && ae.getMessage() != null && !ae.getMessage().isBlank()) {
            return ae.getMessage();
        }
        return e == null || e.getMessage() == null || e.getMessage().isBlank() ? "未知错误" : e.getMessage();
    }
}
