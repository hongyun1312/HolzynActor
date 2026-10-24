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

package com.holzyn.actor.domain.project.service;

import com.holzyn.actor.common.BizException;
import com.holzyn.actor.common.NpcRefs;
import com.holzyn.actor.domain.npc.state.service.NpcActivityStateService;
import com.holzyn.actor.domain.project.entity.ActorProject;
import com.holzyn.actor.domain.npc.profile.entity.ActorCharacter;
import com.holzyn.actor.domain.npc.dialogue.entity.ActorConversation;
import com.holzyn.actor.domain.npc.dialogue.entity.ActorMessage;
import com.holzyn.actor.domain.npc.dialogue.entity.ActorGroupChatConfig;
import com.holzyn.actor.domain.world.entity.ActorWorldSetting;
import com.holzyn.actor.domain.world.entity.ActorWorldLocation;
import com.holzyn.actor.domain.world.entity.ActorEvent;
import com.holzyn.actor.domain.npc.memory.entity.ActorMemory;
import com.holzyn.actor.domain.knowledge.entity.ActorKnowledgeDoc;
import com.holzyn.actor.domain.world.entity.ActorWorldClock;
import com.holzyn.actor.domain.crowd.entity.ActorCrowdRuntime;
import com.holzyn.actor.domain.crowd.entity.ActorNpcFieldDict;
import com.holzyn.actor.domain.crowd.repository.ActorCrowdRuntimeRepository;
import com.holzyn.actor.domain.crowd.repository.ActorNpcFieldDictRepository;
import com.holzyn.actor.domain.npc.profile.repository.ActorCharacterRepository;
import com.holzyn.actor.domain.world.entity.ActorScene;
import com.holzyn.actor.domain.usage.entity.ActorUsageLog;
import com.holzyn.actor.domain.settings.entity.ModelProvider;
import com.holzyn.actor.domain.project.repository.ActorProjectRepository;
import com.holzyn.actor.domain.world.repository.ActorWorldSettingRepository;
import com.holzyn.actor.domain.npc.dialogue.repository.ActorConversationRepository;
import com.holzyn.actor.domain.npc.dialogue.repository.ActorConversationMemberRepository;
import com.holzyn.actor.domain.npc.dialogue.repository.ActorMessageRepository;
import com.holzyn.actor.domain.npc.memory.repository.ActorMemoryRepository;
import com.holzyn.actor.domain.knowledge.repository.ActorKnowledgeDocRepository;
import com.holzyn.actor.domain.world.repository.ActorWorldClockRepository;
import com.holzyn.actor.domain.world.repository.WorldLocationRepository;
import com.holzyn.actor.domain.npc.goal.repository.ActorActionPlanRepository;
import com.holzyn.actor.domain.npc.action.repository.ActorActionLogRepository;
import com.holzyn.actor.domain.world.repository.ActorEventRepository;
import com.holzyn.actor.domain.settings.repository.ActorPromptTemplateRepository;
import com.holzyn.actor.domain.settings.repository.ModelProviderRepository;
import com.holzyn.actor.domain.npc.dialogue.repository.ActorGroupChatConfigRepository;
import com.holzyn.actor.domain.usage.repository.ActorUsageLogRepository;
import com.holzyn.actor.domain.world.repository.ActorSceneRepository;
import com.holzyn.actor.domain.account.CurrentUserProvider;
import com.holzyn.actor.common.HolzynCrypto;
// M1 世界地图（沙盘场景/摆放/住所/沙盘状态）导出：历史 .holzyn 包不含此段，缺装配=跳过，零阻断
import com.holzyn.actor.domain.sandbox.entity.ActorSandboxScene;
import com.holzyn.actor.domain.sandbox.entity.ActorScenePlacement;
import com.holzyn.actor.domain.sandbox.entity.ActorSandboxState;
import com.holzyn.actor.domain.sandbox.repository.ActorSandboxSceneRepository;
import com.holzyn.actor.domain.sandbox.repository.ActorScenePlacementRepository;
import com.holzyn.actor.domain.sandbox.repository.ActorSandboxStateRepository;
import com.holzyn.actor.domain.worldmap.entity.ActorNpcHome;
import com.holzyn.actor.domain.worldmap.entity.ActorHomeMember;
import com.holzyn.actor.domain.worldmap.repository.ActorNpcHomeRepository;
import com.holzyn.actor.domain.worldmap.repository.ActorHomeMemberRepository;
// 角色社会关系（actor/relations.json；本次新增段——历史 .holzyn 包不含人际关系，缺装配=跳过，零阻断）
import com.holzyn.actor.domain.npc.relation.entity.ActorCharacterRelation;
import com.holzyn.actor.domain.npc.relation.repository.ActorCharacterRelationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * .holzyn 项目包导出服务。
 * <p>职责：将 DB 中的 actor 项目导出为 .holzyn 分发态单文件（ZIP 容器）——
 * 结构：顶层 holzyn.json 索引 + actor/ 模块目录（settings/ world/ characters/
 * conversations/ messages/ events/ actions/ memory/ knowledge/ ordinary_npcs/ world_clock/ usage/）。
 * 敏感数据处理：settings/apis.json 默认空骨架（不打包密钥）；includeSensitive=true 时
 * 含 API 配置，password 非空则用「密码加密」（PBKDF2 + AES-GCM）加密该文件整块。
 * 实体沿用 DB 数字 id（导入时做 id 映射）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProjectExportService {

    /** 结构版本 */
    private static final String FORMAT_VERSION = "2.0";

    /** 模块 schema 版本 */
    private static final String SCHEMA_VERSION = "2.0";

    /** 敏感文件相对路径（加密范围） */
    private static final String APIS_PATH = "actor/settings/apis.json";

    private final ActorProjectRepository projectRepository;
    private final ActorWorldSettingRepository worldSettingRepository;
    private final ActorCharacterRepository characterRepository;

    /**
     * NPC 作息运行态访问器。
     * <p><b>为什么必须有它</b>：{@code state} / {@code last_action} / {@code daily_plan_json} 三个运行态字段
     * 原先挂在 {@code actor_profile_ordinary}（普通型 NPC 表）上， 已把该表<b>物理废弃</b>并把运行态
     * 收敛到 {@code actor_state}（{@code activity_state} / {@code last_action} / {@code daily_plan_json} 三列，
     * 运行态唯一家）——主表实体 {@link ActorCharacter} 上<b>不再有</b>这三个字段，导出运行态只能经本访问器
     * 批量读（{@code activityByRef} 一次查询取全，避免逐 NPC 查库）。</p>
     * <p>字段注入（非 final）保持既有构造器签名不变；{@code null}=未装配（导出回退默认态 {@code idle}/空串，
     * 零阻断——运行态读不到绝不让整包导出失败）。</p>
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private NpcActivityStateService npcActivityStateService;

    private final ActorConversationRepository conversationRepository;
    private final ActorConversationMemberRepository conversationMemberRepository;
    private final ActorMessageRepository messageRepository;
    private final ActorMemoryRepository memoryRepository;
    private final ActorKnowledgeDocRepository knowledgeDocRepository;
    private final ActorCharacterRepository ordinaryNpcRepository;
    private final ActorNpcFieldDictRepository fieldDictRepository;
    private final ActorCrowdRuntimeRepository crowdRuntimeRepository;
    private final ActorWorldClockRepository worldClockRepository;
    private final ActorActionPlanRepository planRepository;
    private final ActorActionLogRepository logRepository;
    private final ActorEventRepository eventRepository;
    private final ActorPromptTemplateRepository promptTemplateRepository;
    private final ModelProviderRepository modelProviderRepository;
    private final ActorGroupChatConfigRepository groupChatConfigRepository;
    private final ActorUsageLogRepository usageLogRepository;
    private final ActorSceneRepository sceneRepository;
    private final WorldLocationRepository worldLocationRepository;
    /** 角色社会关系（actor_relation；随包导出，导入侧按角色 id 重链还原） */
    private final ActorCharacterRelationRepository relationRepository;
    private final CurrentUserProvider currentUserProvider;
    private final HolzynCrypto holzynCrypto;
    private final ObjectMapper objectMapper;

    // ==================== M1 世界地图导出（字段注入保持构造器签名不变；null=未装配跳过对应段，零阻断） ====================
    /** 沙盘场景（M1 世界地图权威场景，layout_json 为权威布局） */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private ActorSandboxSceneRepository sandboxSceneRepository;
    /** 沙盘摆放（actor_sandbox_placement） */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private ActorScenePlacementRepository sandboxPlacementRepository;
    /** 住所（M2 移动状态机，actor_npc_home） */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private ActorNpcHomeRepository npcHomeRepository;
    /** 住户（actor_home_member） */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private ActorHomeMemberRepository homeMemberRepository;
    /** 沙盘状态（NPC 坐标权威源，actor_sandbox_state） */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private ActorSandboxStateRepository sandboxStateRepository;

    /**
     * 导出项目为 .holzyn 包（ZIP 字节）。
     *
     * @param projectId 项目 ID
     *
     * @param includeSensitive 是否打包 API 等敏感数据（默认 false）
     *
     * @param password 密码（可选；提供时对敏感文件做密码加密）
     *
     * @return .holzyn ZIP 字节数组
     */
    @Transactional(readOnly = true)
    public byte[] export(Long projectId, boolean includeSensitive, String password) {
        ActorProject project = requireProject(projectId);
        // 惰性生成 projectUid（存量项目可能无 uid）
        if (project.getProjectUid() == null || project.getProjectUid().isBlank()) {
            project.setProjectUid(UUID.randomUUID().toString());
            projectRepository.save(project);
        }
        Long userId = currentUserProvider.currentUserId();

        try (ByteArrayOutputStream bos = new ByteArrayOutputStream();
             ZipOutputStream zos = new ZipOutputStream(bos, StandardCharsets.UTF_8)) {
            // ---------- 入口索引 holzyn.json ----------
            Map<String, Object> holzynJson = new LinkedHashMap<>();
            holzynJson.put("formatVersion", FORMAT_VERSION);
            holzynJson.put("projectUid", project.getProjectUid());
            holzynJson.put("origin", "web");
            holzynJson.put("lastModifiedAt", LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
            holzynJson.put("lastModifiedByDevice", System.getProperty("user.name", "unknown"));
            Map<String, Object> encryption = new LinkedHashMap<>();
            encryption.put("mode", (includeSensitive && password != null && !password.isBlank())
                    ? "password-encrypted" : "none");
            encryption.put("scope", (includeSensitive && password != null && !password.isBlank())
                    ? List.of(APIS_PATH) : List.of());
            holzynJson.put("encryption", encryption);
            Map<String, Object> modules = new LinkedHashMap<>();
            // 启用
            Map<String, Object> actorModule = new LinkedHashMap<>();
            actorModule.put("enabled", true);
            actorModule.put("schemaVersion", SCHEMA_VERSION);
            modules.put("actor", actorModule);
            // 预留
            for (String m : List.of("natural", "civil", "fauna", "orchestrator", "web")) {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("enabled", false);
                entry.put("schemaVersion", null);
                modules.put(m, entry);
            }
            holzynJson.put("modules", modules);
            writeJson(zos, "holzyn.json", holzynJson);

            // ---------- actor/settings ----------
            writeJson(zos, "actor/settings/project.json", buildProjectSettings(project));
            writeJson(zos, "actor/settings/apis.json", buildApis(userId, projectId, includeSensitive, password));
            writeJson(zos, "actor/settings/prompts.json", buildPrompts(userId, projectId));
            writeJson(zos, "actor/settings/chat.json", buildChatConfig(userId));

            // ---------- actor 数据 ----------
            writeJson(zos, "actor/world.json", worldSettingRepository
                    .findTopByProjectIdOrderByVersionDesc(projectId).map(this::worldVO).orElse(null));
            writeJson(zos, "actor/locations.json", worldLocationRepository
                    .findByProjectIdOrderBySortOrderAscIdAsc(projectId).stream().map(this::locationVO).toList());
            List<ActorCharacter> chars = characterRepository.findByProjectIdAndDeletedOrderByIdAsc(projectId, 0);
            writeJson(zos, "actor/characters.json", chars.stream().map(this::characterVO).toList());
            writeJson(zos, "actor/character_cards.json", buildCharacterCards(chars));
            // 角色社会关系（actor/relations.json；本次新增段——随包导出全部人际关系边）
            writeJson(zos, "actor/relations.json", relationRepository.findByProjectId(projectId)
                    .stream().map(this::relationVO).toList());

            List<ActorConversation> convs = conversationRepository
                    .findByProjectIdAndUserIdOrderByUpdatedAtDesc(projectId, userId);
            writeJson(zos, "actor/conversations.json", convs.stream().map(this::conversationVO).toList());
            writeJson(zos, "actor/conversation_members.json", buildConversationMembers(convs));
            // 消息按会话分文件（JSONL）
            for (ActorConversation conv : convs) {
                List<ActorMessage> msgs = messageRepository.findByConversationIdOrderByIdAsc(conv.getId());
                writeJsonl(zos, "actor/messages/" + conv.getId() + ".jsonl", msgs.stream().map(this::messageVO).toList());
            }

            writeJsonl(zos, "actor/events.jsonl", eventRepository.findByProjectIdOrderByIdDesc(projectId)
                    .stream().map(this::eventVO).toList());
            writeJsonl(zos, "actor/actions.jsonl", buildActions(chars));
            writeJson(zos, "actor/scenes.json", sceneRepository.findByProjectIdOrderByEnabledDescIdAsc(projectId)
                    .stream().map(this::sceneVO).toList());
            // ---------- M1 世界地图（沙盘场景/摆放/住所/沙盘状态；v2.0 新增段，历史包无此数据） ----------
            writeJson(zos, "actor/sandbox/scene.json", sandboxSceneRepository == null ? null
                    : sandboxSceneRepository.findFirstByProjectIdOrderByIdAsc(projectId)
                    .map(this::sandboxSceneVO).orElse(null));
            writeJson(zos, "actor/sandbox/placements.json", sandboxPlacementRepository == null ? List.of()
                    : sandboxPlacementRepository.findByProjectIdOrderBySceneIdAscSortOrderAscIdAsc(projectId)
                    .stream().map(this::sandboxPlacementVO).toList());
            writeJson(zos, "actor/sandbox/homes.json", npcHomeRepository == null ? List.of()
                    : npcHomeRepository.findByProjectIdOrderByIdAsc(projectId).stream()
                    .map(h -> sandboxHomeVO(h, homeMemberRepository == null ? List.<ActorHomeMember>of()
                            : homeMemberRepository.findByHomeIdOrderByIdAsc(h.getId()))).toList());
            writeJson(zos, "actor/sandbox/states.json", sandboxStateRepository == null ? List.of()
                    : sandboxStateRepository.findByProjectIdOrderByIdAsc(projectId)
                    .stream().map(this::sandboxStateVO).toList());
            writeJson(zos, "actor/memory.json", memoryRepository.findByProjectIdAndDeletedOrderByIdDesc(projectId, 0)
                    .stream().map(this::memoryVO).toList());
            writeJson(zos, "actor/knowledge.json", knowledgeDocRepository.findByProjectIdOrderByIdAsc(projectId)
                    .stream().map(this::knowledgeVO).toList());
            writeJson(zos, "actor/world_clock.json", worldClockRepository.findByProjectId(projectId)
                    .map(this::clockVO).orElse(null));
            writeJson(zos, "actor/ordinary_npcs.json", buildOrdinaryNpcs(projectId));
            writeJson(zos, "actor/npc_field_dict.json", buildFieldDict(projectId));
            writeJson(zos, "actor/crowd_runtime.json", buildCrowdRuntime(projectId));
            writeJson(zos, "actor/usage/usage_stats.json", buildUsageStats(userId, projectId));
            writeJson(zos, "actor/actor.json", buildActorManifest(projectId, userId, convs, chars));

            zos.finish();
            log.info("[导出] 项目={} 生成 .holzyn 包：敏感={} 加密={}", projectId, includeSensitive,
                    password != null && !password.isBlank());
            return bos.toByteArray();
        } catch (BizException be) {
            throw be;
        } catch (Exception e) {
            log.error("[导出] 项目={} 打包失败", projectId, e);
            throw new BizException(500, "导出失败：" + e.getMessage());
        }
    }

    // ==================== 各文件组装 ====================

    /**
     * 项目基本信息 + 世界时钟（settings/project.json）。
     */
    private Map<String, Object> buildProjectSettings(ActorProject project) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", project.getName());
        m.put("code", project.getCode());
        m.put("summary", project.getSummary());
        m.put("coverRef", project.getCoverUrl());
        m.put("status", project.getStatus());
        m.put("worldClock", worldClockRepository.findByProjectId(project.getId())
                .map(this::clockVO).orElse(Map.of()));
        return m;
    }

    /**
     * API 配置（settings/apis.json）：默认空骨架；includeSensitive 时含配置；password 时加密整块。
     */
    private String buildApis(Long userId, Long projectId, boolean includeSensitive, String password) {
        List<Map<String, Object>> apis = new ArrayList<>();
        if (includeSensitive) {
            apis = modelProviderRepository.findByUserIdAndProjectIdOrderByPriorityDescIdAsc(userId, projectId)
                    .stream().map(this::providerVO).collect(java.util.stream.Collectors.toList());
        }
        String json = writeJsonString(apis);
        if (includeSensitive && password != null && !password.isBlank()) {
            String encrypted = holzynCrypto.encrypt(json, password);
            return encrypted == null ? "[]" : encrypted;
        }
        return json;
    }

    /**
     * Prompt 模板（settings/prompts.json）：项目级覆盖（user_id=当前用户 + project_id=当前项目），
     * 起含系统提示词（systemMessage）。
     */
    private List<Map<String, Object>> buildPrompts(Long userId, Long projectId) {
        return promptTemplateRepository.findByUserIdAndProjectIdOrderByCodeAsc(userId, projectId).stream()
                .map(t -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("code", t.getCode());
                    m.put("name", t.getName());
                    m.put("template", t.getTemplate());
                    m.put("systemMessage", t.getSystemMessage());
                    m.put("version", t.getVersion());
                    m.put("enabled", t.getEnabled());
                    m.put("category", com.holzyn.actor.domain.settings.service.PromptTemplateService.categoryOf(t.getCode()));
                    m.put("override", true);
                    return m;
                }).toList();
    }

    /**
     * 群聊配置（settings/chat.json，用户级）。
     */
    private Map<String, Object> buildChatConfig(Long userId) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("maxReplies", groupChatConfigRepository.findByUserId(userId)
                .map(ActorGroupChatConfig::getMaxReplies).orElse(5));
        return m;
    }

    /**
     * 世界观视图。
     */
    private Map<String, Object> worldVO(ActorWorldSetting w) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", w.getId());
        m.put("name", w.getName());
        m.put("genre", w.getGenre());
        m.put("era", w.getEra());
        m.put("geography", w.getGeography());
        m.put("factions", w.getFactions());
        m.put("magicSystem", w.getMagicSystem());
        m.put("culture", w.getCulture());
        m.put("history", w.getHistory());
        m.put("freeText", w.getFreeText());
        m.put("version", w.getVersion());
        return m;
    }

    /**
     * 角色视图。
     */
    private Map<String, Object> characterVO(ActorCharacter c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", c.getId());
        m.put("type", c.getType());
        m.put("name", c.getName());
        m.put("title", c.getTitle());
        m.put("isProtagonist", c.getIsProtagonist());
        m.put("importance", c.getImportance());
        m.put("status", c.getStatus());
        m.put("detail", c.getDetail());
        return m;
    }

    /**
     * 角色社会关系视图（actor/relations.json）。
     * <p>端点双带：from/to 的 characterId（旧 id）与 fromRef/toRef（npc:{旧id}），
     * 导入侧优先按 ref 重链（remapRef），characterId 兜底；幽灵端点（id=0 仅名称）原样保留。</p>
     */
    private Map<String, Object> relationVO(ActorCharacterRelation r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.getId());
        m.put("fromCharacterId", r.getFromCharacterId());
        m.put("toCharacterId", r.getToCharacterId());
        m.put("fromRef", r.getFromRef());
        m.put("toRef", r.getToRef());
        m.put("fromName", r.getFromName());
        m.put("toName", r.getToName());
        m.put("relationType", r.getRelationType());
        m.put("description", r.getDescription());
        m.put("affinity", r.getAffinity());
        m.put("trust", r.getTrust());
        m.put("relationState", r.getRelationState());
        m.put("lastInteractionAt", r.getLastInteractionAt());
        return m;
    }

    /**
     * 地点视图（世界观地点表）。
     */
    private Map<String, Object> locationVO(ActorWorldLocation l) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", l.getId());
        m.put("name", l.getName());
        m.put("type", l.getType());
        m.put("intro", l.getIntro());
        m.put("importance", l.getImportance());
        m.put("sortOrder", l.getSortOrder());
        m.put("createdAt", l.getCreatedAt());
        m.put("updatedAt", l.getUpdatedAt());
        return m;
    }

    /**
     * 角色档案。
     * <p><b>背景</b>：{@code actor_profile_card} 表已<b>物理废弃</b>（"角色卡"作为独立实体的
     * 多版本卡面语义连同 {@code system_prompt} 概念一并作废），档案并入角色统一主表
     * <b>与角色行一一对应</b>（不再有"每角色多版本卡片"，故本条目的 {@code id} 即角色 id）。</p>
     * <p><b>键口径</b>（对齐 {@code HolzynImportService} 已迁移的导入契约）：</p>
     * <ul>
     * <li>{@code characterId}：角色 id——导入侧 id 映射（旧 id → 新 id）的锚点，<b>必留</b>；</li>
     * <li>{@code personaJson}：档案 JSON 文本（{@code actor_profile.persona_json} 原样）；
     * 导入侧兼容"文本"与"内嵌对象"两种形态，这里按文本导出（保真、且坏档案不会拖垮整包）；</li>
     * <li>{@code version}：档案版本号（{@code persona_version}）——导入侧优先取本键，
     * 缺失时回退读档案内的 {@code persona_version}；</li>
     * <li><b>{@code systemPrompt} 键已删除</b>：{@code system_prompt} 概念已废弃——提示词不再随档案
     * 固化，改为<b>运行时按场景渲染分片</b>（注入/对话/决策各自渲染），包内旧值无落点，
     * 导入侧同样不再读该键；</li>
     * <li><b>{@code source} 键已删除</b>：档案来源改由档案 JSON 内的 {@code _sources} /
     * {@code generated_by} 表达，包外层的旧字段无落点。</li>
     * </ul>
     * <p><b>零阻断 + 无 N+1</b>：档案直接从传入的 {@link ActorCharacter} 行内取（{@code getPersonaJson()}），
     * 不再经 {@code PersonaService.latestPersonaJson} 逐角色回查（省一轮 N+1）；无档案的行跳过
     * （对齐历史 {@code ifPresent} 语义），不抛异常。</p>
     *
     * @param chars 待导出的角色/档案行（已按项目查询得到）
     *
     * @return character_cards.json 条目列表（顺序与 chars 一致）
     */
    private List<Map<String, Object>> buildCharacterCards(List<ActorCharacter> chars) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (ActorCharacter c : chars) {
            if (c == null || c.getId() == null) {
                continue;
            }
            String personaJson = c.getPersonaJson();
            // 零阻断：无档案（未建档/空串）不产生条目——导入侧亦按"无 personaJson 则跳过"处理
            if (personaJson == null || personaJson.isBlank()) {
                continue;
            }
            Map<String, Object> m = new LinkedHashMap<>();
            // 单表合一：档案行 id 即角色 id（原 actor_profile_card.id 已不存在）
            m.put("id", c.getId());
            m.put("characterId", c.getId());
            m.put("version", c.getPersonaVersion());
            m.put("personaJson", personaJson);
            out.add(m);
        }
        return out;
    }

    /**
     * 会话视图。
     */
    private Map<String, Object> conversationVO(ActorConversation c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", c.getId());
        m.put("mode", c.getMode());
        m.put("title", c.getTitle());
        m.put("worldEventEnabled", c.getWorldEventEnabled());
        m.put("lastMessageAt", c.getLastMessageAt());
        m.put("createdAt", c.getCreatedAt());
        return m;
    }

    /**
     * 会话成员。
     */
    private List<Map<String, Object>> buildConversationMembers(List<ActorConversation> convs) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (ActorConversation c : convs) {
            conversationMemberRepository.findByConversationId(c.getId()).forEach(member -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("conversationId", c.getId());
                m.put("characterId", member.getCharacterId());
                out.add(m);
            });
        }
        return out;
    }

    /**
     * 消息视图。
     */
    private Map<String, Object> messageVO(ActorMessage msg) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", msg.getId());
        m.put("conversationId", msg.getConversationId());
        m.put("characterId", msg.getCharacterId());
        m.put("role", msg.getRole());
        m.put("type", msg.getType());
        m.put("content", msg.getContent());
        m.put("status", msg.getStatus());
        m.put("tokenIn", msg.getTokenIn());
        m.put("tokenOut", msg.getTokenOut());
        m.put("createdAt", msg.getCreatedAt());
        return m;
    }

    /**
     * 事件视图。
     */
    private Map<String, Object> eventVO(ActorEvent e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", e.getId());
        m.put("kind", e.getKind());
        m.put("title", e.getTitle());
        m.put("content", e.getContent());
        m.put("characterId", e.getCharacterId());
        m.put("sceneId", e.getSceneId());
        m.put("evolutionId", e.getEvolutionId());
        m.put("source", e.getSource());
        m.put("gameHour", e.getGameHour());
        m.put("createdAt", e.getCreatedAt());
        return m;
    }

    /**
     * 行动（决策 + 执行日志，JSONL）。
     */
    private List<Map<String, Object>> buildActions(List<ActorCharacter> chars) {
        List<Long> ids = chars.stream().map(ActorCharacter::getId).toList();
        List<Map<String, Object>> out = new ArrayList<>();
        if (!ids.isEmpty()) {
            planRepository.findByCharacterIdInOrderByIdDesc(ids).forEach(p -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("kind", "plan");
                m.put("id", p.getId());
                m.put("characterId", p.getCharacterId());
                m.put("conversationId", p.getConversationId());
                m.put("triggerType", p.getTriggerType());
                m.put("status", p.getStatus());
                m.put("actionJson", p.getActionJson());
                m.put("plannedTime", p.getPlannedTime());
                m.put("executedAt", p.getExecutedAt());
                m.put("createdAt", p.getCreatedAt());
                out.add(m);
            });
            logRepository.findByCharacterIdInOrderByLogTimeDesc(ids).forEach(l -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("kind", "log");
                m.put("id", l.getId());
                m.put("characterId", l.getCharacterId());
                m.put("planId", l.getPlanId());
                m.put("summary", l.getSummary());
                m.put("detail", l.getDetail());
                m.put("logTime", l.getLogTime());
                m.put("createdAt", l.getCreatedAt());
                out.add(m);
            });
        }
        return out;
    }

    /**
     * 记忆视图。
     */
    private Map<String, Object> memoryVO(ActorMemory mem) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", mem.getId());
        m.put("characterId", mem.getCharacterId());
        m.put("type", mem.getType());
        m.put("content", mem.getContent());
        m.put("importance", mem.getImportance());
        m.put("deleted", mem.getDeleted());
        m.put("createdAt", mem.getCreatedAt());
        return m;
    }

    /**
     * 知识文档视图（不含 embedding 向量，避免包过大；导入后需重新向量化）。
     */
    private Map<String, Object> knowledgeVO(ActorKnowledgeDoc d) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", d.getId());
        m.put("characterId", d.getCharacterId());
        m.put("title", d.getTitle());
        m.put("content", d.getContent());
        m.put("createdAt", d.getCreatedAt());
        return m;
    }

    /**
     * 世界时钟视图。
     */
    private Map<String, Object> clockVO(ActorWorldClock c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("rate", c.getRate());
        m.put("worldStartAt", c.getWorldStartAt());
        m.put("worldStartGameHour", c.getWorldStartGameHour());
        m.put("lastGameHour", c.getLastGameHour());
        // 时间口径统一：世界时间权威=引擎推进位置（last_game_second），必须随档导出——
        // 否则导入后引擎位置从 0 起跑（0001年01月01日 00时），导出时的世界时间丢失（时钟字段
        // 与 AI 提示词时间感同源于该列）。
        m.put("lastGameSecond", c.getLastGameSecond());
        m.put("paused", c.getPaused());
        // 世界持续运行常驻开关（world_running 0/1）：随包导出——否则导入后 @PrePersist 默认 1
        // （世界自动开跑），导出时冻结的档回导后就不再静止（违背"世界保持静止"的语义）。
        m.put("worldRunning", c.getWorldRunning());
        m.put("lastSummary", c.getLastSummary());
        return m;
    }

    /**
     * 普通型 NPC（单表扁平，每人独立一行； 分类体系重构：含次级种族，不含废弃分类字段）。
     * <p><b>两处口径修正</b>：</p>
     * <ol>
     * <li><b>行范围</b>：单表合并后"普通型 NPC"= {@code actor_profile} 中 {@code type='common'} 的行，
     * 原 {@code ActorOrdinaryNpcRepository.findByProjectIdOrderByIdAsc} 的"只含普通型"语义必须在
     * 合并后的统一仓库上显式写回 type 过滤——否则特殊型角色也会混进本文件，而导入侧对本文件
     * <b>强制</b>写 {@code type=common}（见 {@code HolzynImportService}），特殊型一旦被导进来，
     * 回导时就会被克隆成一个普通型副本（脏数据）。同时过滤软删除行：导出只带有效数据。</li>
     * <li><b>运行态来源</b>：{@code state} / {@code lastAction} 两个运行态字段已随
     * {@code actor_profile_ordinary} 物理废弃迁入 {@code actor_state}（{@code activity_state} /
     * {@code last_action}，运行态唯一家），主表实体上再无该字段——改为<b>一次批量读运行态</b>
     * （{@code activityByRef} 单次查询，循环内零查库），<b>导出键名保持不变</b>；
     * 无状态行 → {@code idle} / 空串（零阻断：读不到运行态不得中断整包导出）。</li>
     * </ol>
     *
     * @param projectId 项目 ID
     *
     * @return ordinary_npcs.json 条目列表
     */
    private List<Map<String, Object>> buildOrdinaryNpcs(Long projectId) {
        List<Map<String, Object>> out = new ArrayList<>();
        // ：批量取运行态（npcRef → {state, lastAction}）——1 次查询回填整页，避免逐 NPC 查库（N+1）
        Map<String, NpcActivityStateService.ActivityUpdate> runStates =
                npcActivityStateService == null ? Map.of() : npcActivityStateService.activityByRef(projectId);
        // ：普通型 = type='common'（历史写 crowd 的行在写入侧已由 NpcRefs.normalizeType 归一）
        for (ActorCharacter n : ordinaryNpcRepository.findByProjectIdAndTypeAndDeletedOrderByIdAsc(
                projectId, NpcRefs.TYPE_COMMON, 0)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", n.getId());
            m.put("name", n.getName());
            m.put("gender", n.getGender());
            m.put("race", n.getRace());
            m.put("subRace", n.getSubRace());
            m.put("age", n.getAge());
            m.put("affiliation", n.getAffiliation());
            m.put("location", n.getLocation());
            m.put("occupation", n.getOccupation());
            m.put("detail", n.getDetail());
            // ：运行态从 actor_state 读（主表已无 state/last_action 列）；键名沿用既有导出键
            String ref = NpcRefs.of(n.getId());
            NpcActivityStateService.ActivityUpdate rs = ref == null ? null : runStates.get(ref);
            String runState = rs == null || rs.state() == null || rs.state().isBlank()
                    ? NpcActivityStateService.DEFAULT_ACTIVITY : rs.state();
            m.put("state", runState);
            m.put("lastAction", rs == null || rs.lastAction() == null ? "" : rs.lastAction());
            out.add(m);
        }
        return out;
    }

    /**
     * 普通型 NPC 标准字段数据字典（race 含次级种族/affiliation/occupation，含出处）。
     */
    private List<Map<String, Object>> buildFieldDict(Long projectId) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (ActorNpcFieldDict d : fieldDictRepository.findByProjectIdOrderByFieldAscSortOrderAscIdAsc(projectId)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("field", d.getField());
            m.put("level1", d.getLevel1());
            m.put("level2", d.getLevel2());
            m.put("source", d.getSource());
            m.put("sortOrder", d.getSortOrder());
            out.add(m);
        }
        return out;
    }

    /**
     * 普通型 NPC 项目级调度运行时（开关/主次分类字段/上次调度/环境快照）。
     */
    private Map<String, Object> buildCrowdRuntime(Long projectId) {
        ActorCrowdRuntime rt = crowdRuntimeRepository.findByProjectId(projectId).orElse(null);
        if (rt == null) return null;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("enabled", rt.getEnabled());
        m.put("primaryField", rt.getPrimaryField());
        m.put("secondaryField", rt.getSecondaryField());
        m.put("lastScheduleAt", rt.getLastScheduleAt());
        m.put("latestSummary", rt.getLatestSummary());
        return m;
    }

    /**
     * 场景视图（世界演化）。
     */
    private Map<String, Object> sceneVO(ActorScene s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", s.getId());
        m.put("name", s.getName());
        m.put("description", s.getDescription());
        m.put("location", s.getLocation());
        m.put("background", s.getBackground());
        m.put("enabled", s.getEnabled());
        return m;
    }

    /**
     * 用量统计（该项目 usage_log 明细 + 汇总，不含任何密钥）。
     */
    // ==================== M1 世界地图（沙盘场景/摆放/住所/沙盘状态）视图 ====================

    /**
     * 沙盘场景视图（M1 世界地图）。layout_json/anchor_json 等 JSON 文本<b>原文</b>导出，
     * 导入侧原样还原 + 新 id；region/placement 内嵌的 locationId 为旧 id，由导入侧按地点名重解析。
     */
    private Map<String, Object> sandboxSceneVO(ActorSandboxScene s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", s.getId());
        m.put("name", s.getName());
        m.put("templateCode", s.getTemplateCode());
        m.put("styleCode", s.getStyleCode());
        m.put("sceneSize", s.getSceneSize());
        m.put("seed", s.getSeed());
        m.put("layoutJson", s.getLayoutJson());
        m.put("envJson", s.getEnvJson());
        m.put("anchorJson", s.getAnchorJson());
        m.put("terrainOverrideJson", s.getTerrainOverrideJson());
        m.put("paletteJson", s.getPaletteJson());
        m.put("styleDesc", s.getStyleDesc());
        m.put("source", s.getSource());
        m.put("version", s.getVersion());
        return m;
    }

    /** 沙盘摆放视图（scene_id/project_id 由导入侧重建，不导出）。 */
    private Map<String, Object> sandboxPlacementVO(ActorScenePlacement p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", p.getId());
        m.put("assetId", p.getAssetId());
        m.put("name", p.getName());
        m.put("posX", p.getPosX());
        m.put("posY", p.getPosY());
        m.put("posZ", p.getPosZ());
        m.put("scaleX", p.getScaleX());
        m.put("scaleY", p.getScaleY());
        m.put("scaleZ", p.getScaleZ());
        m.put("rotX", p.getRotX());
        m.put("rotY", p.getRotY());
        m.put("rotZ", p.getRotZ());
        m.put("sortOrder", p.getSortOrder());
        return m;
    }

    /**
     * 住所视图（含住户）。locationId 原 id + locationName 双带，导入侧优先按地点名重解析
     * （地点 id 导入后变化）；placementId 由导入侧按摆放 id 映射重建。
     */
    private Map<String, Object> sandboxHomeVO(ActorNpcHome h, List<ActorHomeMember> members) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", h.getId());
        m.put("name", h.getName());
        m.put("locationId", h.getLocationId());
        m.put("locationName", locationNameOf(h.getLocationId()));
        m.put("placementId", h.getPlacementId());
        m.put("posX", h.getPosX());
        m.put("posZ", h.getPosZ());
        m.put("roomLabel", h.getRoomLabel());
        m.put("region", h.getRegion());
        m.put("note", h.getNote());
        m.put("status", h.getStatus());
        m.put("decorationLevel", h.getDecorationLevel());
        List<Map<String, Object>> memberNodes = new ArrayList<>();
        for (ActorHomeMember mm : members) {
            Map<String, Object> node = new LinkedHashMap<>();
            node.put("characterRef", mm.getCharacterRef());
            node.put("isOwner", mm.getIsOwner());
            node.put("role", mm.getRole());
            node.put("roomLabel", mm.getRoomLabel());
            memberNodes.add(node);
        }
        m.put("members", memberNodes);
        return m;
    }

    /** 地点名反查（home.locationId → 地点名；失败返回 null，导入侧回退按名重解析）。 */
    private String locationNameOf(Long locationId) {
        if (locationId == null) {
            return null;
        }
        try {
            return worldLocationRepository.findById(locationId).map(ActorWorldLocation::getName).orElse(null);
        } catch (Exception e) {
            return null;
        }
    }

    /** 沙盘状态视图（npc_ref 原样导出，导入侧按角色 id 映射重写为 special/crowd/npc:{newId}）。 */
    private Map<String, Object> sandboxStateVO(ActorSandboxState st) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", st.getId());
        m.put("npcType", st.getNpcType());
        m.put("npcRef", st.getNpcRef());
        m.put("posX", st.getPosX());
        m.put("posY", st.getPosY());
        m.put("posZ", st.getPosZ());
        m.put("heading", st.getHeading());
        m.put("moveState", st.getMoveState());
        m.put("targetPosJson", st.getTargetPosJson());
        m.put("actionText", st.getActionText());
        m.put("statusText", st.getStatusText());
        m.put("thoughtText", st.getThoughtText());
        m.put("thoughtSource", st.getThoughtSource());
        m.put("gameSecond", st.getGameSecond());
        return m;
    }

    private Map<String, Object> buildUsageStats(Long userId, Long projectId) {
        List<ActorUsageLog> logs = usageLogRepository.findByUserIdOrderByIdDesc(userId).stream()
                .filter(l -> projectId.equals(l.getProjectId())).toList();
        int tokenIn = 0, tokenOut = 0, calls = logs.size();
        Map<String, Integer> byScene = new LinkedHashMap<>();
        Map<String, Integer> byModel = new LinkedHashMap<>();
        for (ActorUsageLog l : logs) {
            tokenIn += l.getTokenIn() == null ? 0 : l.getTokenIn();
            tokenOut += l.getTokenOut() == null ? 0 : l.getTokenOut();
            byScene.merge(l.getScene() == null ? "unknown" : l.getScene(), 1, Integer::sum);
            byModel.merge(l.getModel() == null ? "unknown" : l.getModel(), 1, Integer::sum);
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("totals", Map.of("tokenIn", tokenIn, "tokenOut", tokenOut, "calls", calls));
        m.put("byScene", byScene);
        m.put("byModel", byModel);
        return m;
    }

    /**
     * actor.json 模块清单。
     */
    private Map<String, Object> buildActorManifest(Long projectId, Long userId, List<ActorConversation> convs,
                                                   List<ActorCharacter> chars) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("schemaVersion", SCHEMA_VERSION);
        m.put("stats", Map.of(
                "characters", chars.size(),
                "relations", relationRepository.findByProjectId(projectId).size(),
                "conversations", convs.size(),
                "messages", convs.stream()
                        .mapToInt(c -> messageRepository.findByConversationIdOrderByIdAsc(c.getId()).size()).sum(),
                "events", eventRepository.findByProjectIdOrderByIdDesc(projectId).size(),
                "memory", memoryRepository.findByProjectIdAndDeletedOrderByIdDesc(projectId, 0).size(),
                "usageCalls", (int) usageLogRepository.findByUserIdOrderByIdDesc(userId).stream()
                        .filter(l -> projectId.equals(l.getProjectId())).count()));
        m.put("files", List.of(
                "settings/project.json", "settings/apis.json", "settings/prompts.json", "settings/chat.json",
                "world.json", "locations.json", "characters.json", "character_cards.json", "relations.json",
                "ordinary_npcs.json", "npc_field_dict.json", "crowd_runtime.json",
                "conversations.json", "conversation_members.json",
                "events.jsonl", "actions.jsonl", "memory.json", "knowledge.json", "world_clock.json",
                "usage/usage_stats.json"));
        return m;
    }

    /**
     * 供应商视图（含 apiKeyCipher，敏感）。
     */
    private Map<String, Object> providerVO(ModelProvider p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", p.getId());
        m.put("name", p.getName());
        m.put("baseUrl", p.getBaseUrl());
        m.put("apiKeyCipher", p.getApiKeyCipher());
        m.put("model", p.getModel());
        m.put("purpose", p.getPurpose());
        m.put("embeddingModel", p.getEmbeddingModel());
        m.put("supportsStream", p.getSupportsStream());
        m.put("isDefault", p.getIsDefault());
        m.put("enabled", p.getEnabled());
        return m;
    }

    // ==================== 工具 ====================

    /**
     * 写一个 JSON 条目到 ZIP。
     */
    private void writeJson(ZipOutputStream zos, String path, Object value) throws Exception {
        String content = value == null ? "null" : writeJsonString(value);
        zos.putNextEntry(new ZipEntry(path));
        zos.write(content.getBytes(StandardCharsets.UTF_8));
        zos.closeEntry();
    }

    /**
     * 写一个 JSONL 条目到 ZIP（每行一个对象）。
     */
    private void writeJsonl(ZipOutputStream zos, String path, List<?> items) throws Exception {
        StringBuilder sb = new StringBuilder();
        for (Object item : items) {
            sb.append(writeJsonString(item)).append("\n");
        }
        zos.putNextEntry(new ZipEntry(path));
        zos.write(sb.toString().getBytes(StandardCharsets.UTF_8));
        zos.closeEntry();
    }

    /**
     * 对象序列化为 JSON 字符串。
     */
    private String writeJsonString(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new BizException(500, "序列化失败");
        }
    }

    /**
     * 校验项目归属当前用户。
     */
    private ActorProject requireProject(Long projectId) {
        Long userId = currentUserProvider.currentUserId();
        return projectRepository.findByIdAndUserIdAndDeleted(projectId, userId, 0)
                .orElseThrow(() -> new BizException(404, "项目不存在或无权访问"));
    }
}
