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
import com.holzyn.actor.common.ActorEventFields;
import com.holzyn.actor.common.NpcClassifyFields;
import com.holzyn.actor.common.Texts;
import com.holzyn.actor.domain.project.entity.ActorProject;
import com.holzyn.actor.domain.world.entity.ActorWorldSetting;
import com.holzyn.actor.domain.world.entity.ActorWorldLocation;
import com.holzyn.actor.domain.world.entity.ActorScene;
import com.holzyn.actor.domain.npc.profile.entity.ActorCharacter;
import com.holzyn.actor.domain.growth.persona.PersonaJson;
import com.holzyn.actor.domain.growth.persona.PersonaProfile;
import com.holzyn.actor.domain.npc.dialogue.entity.ActorConversation;
import com.holzyn.actor.domain.npc.dialogue.entity.ActorConversationMember;
import com.holzyn.actor.domain.npc.dialogue.entity.ActorMessage;
import com.holzyn.actor.domain.world.entity.ActorEvent;
import com.holzyn.actor.domain.npc.memory.entity.ActorMemory;
import com.holzyn.actor.domain.knowledge.entity.ActorKnowledgeDoc;
import com.holzyn.actor.domain.crowd.entity.ActorCrowdRuntime;
import com.holzyn.actor.domain.crowd.entity.ActorNpcFieldDict;
import com.holzyn.actor.domain.crowd.repository.ActorNpcFieldDictRepository;
import com.holzyn.actor.domain.world.entity.ActorWorldClock;
import com.holzyn.actor.domain.settings.entity.ActorPromptTemplate;
import com.holzyn.actor.domain.settings.entity.ModelProvider;
import com.holzyn.actor.domain.npc.dialogue.entity.ActorGroupChatConfig;
import com.holzyn.actor.domain.project.repository.ActorProjectRepository;
import com.holzyn.actor.domain.world.repository.ActorWorldSettingRepository;
import com.holzyn.actor.domain.npc.profile.repository.ActorCharacterRepository;
import com.holzyn.actor.domain.npc.dialogue.repository.ActorConversationRepository;
import com.holzyn.actor.domain.npc.dialogue.repository.ActorConversationMemberRepository;
import com.holzyn.actor.domain.npc.dialogue.repository.ActorMessageRepository;
import com.holzyn.actor.domain.memory.common.ActorMemoryTypes;
import com.holzyn.actor.domain.npc.memory.repository.ActorMemoryRepository;
import com.holzyn.actor.domain.knowledge.repository.ActorKnowledgeDocRepository;
import com.holzyn.actor.domain.knowledge.service.WorldKnowledgeSyncService;
import com.holzyn.actor.domain.crowd.repository.ActorCrowdRuntimeRepository;
import com.holzyn.actor.domain.world.repository.ActorWorldClockRepository;
import com.holzyn.actor.domain.world.repository.ActorEventRepository;
import com.holzyn.actor.domain.world.repository.ActorSceneRepository;
import com.holzyn.actor.domain.world.repository.WorldLocationRepository;
import com.holzyn.actor.domain.settings.repository.ActorPromptTemplateRepository;
import com.holzyn.actor.domain.settings.repository.ModelProviderRepository;
import com.holzyn.actor.domain.npc.dialogue.repository.ActorGroupChatConfigRepository;
import com.holzyn.actor.domain.account.CurrentUserProvider;
import com.holzyn.actor.common.HolzynCrypto;
// M1 世界地图（沙盘场景/摆放/住所/沙盘状态）导入：v2.0 新增段，历史包无此数据，缺装配=跳过，零阻断
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
// 角色社会关系（actor/relations.json；本次新增段——历史包无此数据 → 跳过，零阻断）
import com.holzyn.actor.domain.npc.relation.entity.ActorCharacterRelation;
import com.holzyn.actor.domain.npc.relation.repository.ActorCharacterRelationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * .holzyn 项目包导入服务。
 * <p>职责：将 .holzyn 分发态单文件（ZIP 容器）解包并还原为 DB 中的项目——
 * ① 读 holzyn.json 校验版本与加密声明（password-encrypted 需密码，错误则 apis 置空并提示）；
 * ② 幂等检测：projectUid 已存在则拒绝（防重复导入脏数据）；
 * ③ id 映射：文件实体数字 id → DB 自增 id，确保角色/角色档案/会话/消息/事件/记忆等外键正确重链；
 * ④ 导入项目/世界观/角色/角色档案/场景/会话/消息/事件/记忆/知识/人群/时钟/项目级设置（API/Prompt/群聊）。</p>
 * <p><b>起的两处口径变化</b>：① 角色档案并入角色统一主表 {@code actor_profile.persona_json}
 * （包内 {@code actor/character_cards.json} 的文件名与结构保持不变，只是落库目标从已废弃的
 * {@code actor_profile_card} 换成主表档案列，且不再导入 systemPrompt/source）；
 * ② 普通型 NPC 也写进 {@code actor_profile} 并显式标 {@code type=common}（旧 {@code actor_profile_ordinary}
 * 表已废弃），但其运行态（state/lastAction）不随包导入——运行态的唯一家在 {@code actor_state}。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HolzynImportService {

    /** 支持的顶层结构版本 major */
    private static final int FORMAT_MAJOR = 2;

    /** 敏感文件相对路径 */
    private static final String APIS_PATH = "actor/settings/apis.json";

    private final ActorProjectRepository projectRepository;
    private final ActorWorldSettingRepository worldSettingRepository;
    private final ActorCharacterRepository characterRepository;
    private final ActorConversationRepository conversationRepository;
    private final ActorConversationMemberRepository conversationMemberRepository;
    private final ActorMessageRepository messageRepository;
    private final ActorMemoryRepository memoryRepository;
    private final ActorKnowledgeDocRepository knowledgeDocRepository;
    /** 世界设定知识同步器（RAG 适配：导入提交后把导入的世界设定/地点同步为项目级知识文档 + 向量化） */
    private final WorldKnowledgeSyncService worldKnowledgeSyncService;
    /**
     * 普通型 NPC 仓库（历史遗留字段，<b>未删除，仅加注说明</b>）。
     * <p>单表化后它与 {@link #characterRepository} <b>是同一种类型、同一个 Spring Data bean</b>
     * （{@code actor_profile} 一张表，用 {@code type} 列区分 special / common），字段名已名不副实；
     * 保留只是为了避免牵连调用点，<b>它并不是第二张表</b>。后续清理时应连同下方
     * "普通型 NPC（单表）"导入段一起并回 {@code characterRepository}（本次不动）。</p>
     */
    private final ActorCharacterRepository ordinaryNpcRepository;
    private final ActorNpcFieldDictRepository fieldDictRepository;
    private final ActorCrowdRuntimeRepository crowdRuntimeRepository;
    private final ActorWorldClockRepository worldClockRepository;
    private final ActorEventRepository eventRepository;
    private final ActorSceneRepository sceneRepository;
    private final WorldLocationRepository worldLocationRepository;
    /** 角色社会关系（actor_relation；随包导入，端点按角色 id 重链） */
    private final ActorCharacterRelationRepository relationRepository;
    private final ActorPromptTemplateRepository promptTemplateRepository;
    private final ModelProviderRepository modelProviderRepository;
    private final ActorGroupChatConfigRepository groupChatConfigRepository;
    private final CurrentUserProvider currentUserProvider;
    private final HolzynCrypto holzynCrypto;
    private final ObjectMapper objectMapper;

    /** 世界时钟服务（时间口径统一：导入时按 lastGameSecond 校准世界时间；字段注入避免改构造器） */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.holzyn.actor.domain.world.service.WorldClockService worldClockService;

    // ==================== M1 世界地图导入（字段注入保持构造器签名不变；null=未装配跳过地图段，零阻断） ====================
    /** 沙盘场景 */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private ActorSandboxSceneRepository sandboxSceneRepository;
    /** 沙盘摆放 */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private ActorScenePlacementRepository sandboxPlacementRepository;
    /** 住所 */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private ActorNpcHomeRepository npcHomeRepository;
    /** 住户 */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private ActorHomeMemberRepository homeMemberRepository;
    /** 沙盘状态（NPC 坐标） */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private ActorSandboxStateRepository sandboxStateRepository;

    /**
     * 导入 .holzyn 包。
     *
     * @param bytes ZIP 包字节
     *
     * @param password 密码（包为 password-encrypted 时必填）
     *
     * @return 导入结果（projectId/项目名/统计/敏感数据状态）
     */
    @Transactional
    public Map<String, Object> importPackage(byte[] bytes, String password) {
        if (bytes == null || bytes.length == 0) {
            throw new BizException(400, "导入文件为空");
        }
        Long userId = currentUserProvider.currentUserId();
        Map<String, byte[]> entries = unzip(bytes);

        // ---------- ① 校验入口索引 ----------
        byte[] indexBytes = entries.get("holzyn.json");
        if (indexBytes == null) {
            throw new BizException(400, "非法的 .holzyn 包：缺少 holzyn.json 入口索引");
        }
        JsonNode index;
        try {
            index = objectMapper.readTree(new String(indexBytes, StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new BizException(400, "非法的 .holzyn 包：holzyn.json 无法解析");
        }
        String formatVersion = index.path("formatVersion").asText("");
        if (!formatVersion.matches("^\\d+\\.\\d+$")) {
            throw new BizException(400, "非法的 .holzyn 包：formatVersion 缺失");
        }
        int major = Integer.parseInt(formatVersion.split("\\.")[0]);
        if (major > FORMAT_MAJOR) {
            throw new BizException(400, "包版本过高（formatVersion=" + formatVersion
                    + "），请升级应用后再导入");
        }
        String projectUid = index.path("projectUid").asText("");
        if (projectUid.isBlank()) {
            throw new BizException(400, "非法的 .holzyn 包：缺少 projectUid");
        }

        // ---------- ② 幂等检测 ----------
        if (projectRepository.findByProjectUid(projectUid).isPresent()) {
            throw new BizException(409, "该项目已导入过（projectUid=" + projectUid
                    + "），为避免脏数据请勿重复导入");
        }

        // ---------- ③ 加密处理 ----------
        boolean encrypted = "password-encrypted".equals(index.path("encryption").path("mode").asText("none"));
        String apisContent = readText(entries, APIS_PATH);
        boolean apisAvailable = false;
        if (encrypted) {
            if (password == null || password.isBlank()) {
                apisContent = "[]"; // 未提供密码：敏感配置不可用，保持空骨架
            } else {
                String decrypted = holzynCrypto.decrypt(apisContent, password);
                if (decrypted != null) {
                    apisContent = decrypted;
                    apisAvailable = true;
                } else {
                    apisContent = "[]"; // 密码错误：置空 + 提示
                }
            }
        } else if (apisContent != null && !apisContent.isBlank() && !"[]".equals(apisContent.trim())) {
            apisAvailable = true;
        }

        // ---------- ④ id 映射 ----------
        Map<Long, Long> characterIdMap = new HashMap<>();
        Map<Long, Long> conversationIdMap = new HashMap<>();
        Map<Long, Long> sceneIdMap = new HashMap<>();
        // 地点 id 映射（M1 地图住所 location_id 重链用；旧包地点无 id 时为 null，回退按地点名重解析）
        Map<Long, Long> locationIdMap = new HashMap<>();

        // 项目（保留 projectUid）
        ActorProject project = new ActorProject();
        project.setProjectUid(projectUid);
        JsonNode settings = readJson(entries, "actor/settings/project.json");
        project.setUserId(userId);
        project.setName(readText(settings, "name"));
        project.setCode(readText(settings, "code"));
        project.setSummary(readText(settings, "summary"));
        project.setCoverUrl(readText(settings, "coverRef"));
        Integer status = readInt(settings, "status");
        project.setStatus(status == null ? 0 : status);
        project = projectRepository.save(project);
        log.info("[导入] 创建项目：{}（uid={}）", project.getName(), projectUid);

        // 世界观
        JsonNode world = readJson(entries, "actor/world.json");
        if (world != null && !world.isNull()) {
            ActorWorldSetting ws = new ActorWorldSetting();
            ws.setProjectId(project.getId());
            ws.setName(readText(world, "name"));
            ws.setGenre(readText(world, "genre"));
            ws.setEra(readText(world, "era"));
            ws.setGeography(readText(world, "geography"));
            ws.setFactions(readText(world, "factions"));
            ws.setMagicSystem(readText(world, "magicSystem"));
            ws.setCulture(readText(world, "culture"));
            ws.setHistory(readText(world, "history"));
            ws.setFreeText(readText(world, "freeText"));
            Integer wv = readInt(world, "version");
            ws.setVersion(wv == null ? 1 : wv);
            worldSettingRepository.save(ws);
        }

        // 地点表
        JsonNode locations = readJson(entries, "actor/locations.json");
        if (locations != null && locations.isArray()) {
            int i = 0;
            for (JsonNode loc : locations) {
                String name = readText(loc, "name");
                if (name == null || name.isBlank()) {
                    continue;
                }
                ActorWorldLocation al = new ActorWorldLocation();
                al.setProjectId(project.getId());
                al.setName(name);
                al.setType(readText(loc, "type"));
                al.setIntro(readText(loc, "intro"));
                Integer imp = readInt(loc, "importance");
                al.setImportance(imp == null ? 3 : imp);
                Integer so = readInt(loc, "sortOrder");
                al.setSortOrder(so == null ? i++ : so);
                al = worldLocationRepository.save(al);
                // 旧 id → 新 id（M1 地图住所 location_id 重链；历史包无 id 字段则跳过）
                if (loc.path("id").isIntegralNumber()) {
                    locationIdMap.put(loc.path("id").asLong(), al.getId());
                }
            }
            log.info("[导入] 项目={} 还原地点 {} 条", project.getName(), i);
        }

        // 场景
        JsonNode scenes = readJson(entries, "actor/scenes.json");
        if (scenes != null && scenes.isArray()) {
            for (JsonNode s : scenes) {
                ActorScene scene = new ActorScene();
                scene.setProjectId(project.getId());
                scene.setName(readText(s, "name"));
                scene.setDescription(readText(s, "description"));
                scene.setLocation(readText(s, "location"));
                scene.setBackground(readText(s, "background"));
                Integer en = readInt(s, "enabled");
                scene.setEnabled(en == null ? 1 : en);
                scene = sceneRepository.save(scene);
                sceneIdMap.put(s.path("id").asLong(), scene.getId());
            }
        }

        // 角色 + 角色卡
        // 收口：外部包是不可信输入，结构性文本列一律按实体列宽截断
        // （name/title 50、gender 20、race/sub_race/occupation 50、affiliation 100、location 255），
        // 否则超长值会在数据库层抛 Value too long，让整包导入失败。
        JsonNode characters = readJson(entries, "actor/characters.json");
        if (characters != null && characters.isArray()) {
            for (JsonNode c : characters) {
                // 普通型 NPC 由 ordinary_npcs.json 段统一导入（type=common），这里跳过——
                // 否则与 characters.json 里的同型行重复建行，产生克隆副本（兼容旧包同样跳过）。
                if (com.holzyn.actor.common.NpcRefs.isCommonType(readText(c, "type", "special"))) {
                    continue;
                }
                ActorCharacter ch = new ActorCharacter();
                ch.setProjectId(project.getId());
                ch.setType(readText(c, "type", "special"));
                ch.setName(Texts.truncate(readText(c, "name"), 50));
                ch.setTitle(Texts.truncate(readText(c, "title"), 50));
                ch.setIsProtagonist(readInt(c, "isProtagonist", 0));
                ch.setImportance(readInt(c, "importance", 3));
                ch.setStatus(readInt(c, "status", 0));
                ch.setDetail(readText(c, "detail"));
                ch = characterRepository.save(ch);
                characterIdMap.put(c.path("id").asLong(), ch.getId());
            }
        }

        // 普通型 NPC（单表 actor_profile，type=common）——紧随特殊型导入，
        // 必须在角色档案/世界地图/关系/事件/记忆等任何引用普通型 NPC 的段之前导入，
        // 并把 id 记入 characterIdMap，确保后续所有 npc:{oldId} 引用都能重链到新 id（无缝回导闭环）。
        JsonNode ordinaryNpcs = readJson(entries, "actor/ordinary_npcs.json");
        if (ordinaryNpcs != null && ordinaryNpcs.isArray()) {
            int ordinaryImported = 0;
            for (JsonNode n : ordinaryNpcs) {
                ActorCharacter npc = new ActorCharacter();
                npc.setProjectId(project.getId());
                // 旧表 actor_profile_ordinary 已废弃：普通型不再靠"在哪张表"表达，必须显式标 type=common，
                // 否则会被默认成 special（人群调度/沙盘等按 type 过滤的查询就再也找不到这些人）。
                npc.setType("common");
                npc.setName(Texts.truncate(readText(n, "name"), 50));
                npc.setGender(Texts.truncate(readText(n, "gender"), 20));
                npc.setRace(Texts.truncate(readText(n, "race"), 50));
                npc.setSubRace(Texts.truncate(readText(n, "subRace"), 50));
                Integer age = readInt(n, "age");
                npc.setAge(age == null || age < 0 ? null : age);
                npc.setAffiliation(Texts.truncate(readText(n, "affiliation"), 100));
                npc.setLocation(Texts.truncate(readText(n, "location"), 255));
                npc.setOccupation(Texts.truncate(readText(n, "occupation"), 50));
                npc.setDetail(readText(n, "detail"));
                // 运行态（state/lastAction）不再随包导入：这两列已从主表移除，运行态的唯一家在
                // actor_state（作息状态机/生理与情绪），导入后由状态机按默认态重建，不在这里写。
                npc = ordinaryNpcRepository.save(npc);
                // id 映射：普通型 NPC 也记入 characterIdMap（供关系/沙盘/事件等 npc: 引用重链）
                if (n.path("id").isIntegralNumber()) {
                    characterIdMap.put(n.path("id").asLong(), npc.getId());
                }
                ordinaryImported++;
            }
            log.info("[导入] 项目={} 还原普通型 NPC {} 条", project.getName(), ordinaryImported);
        }

        // 角色档案（包内文件名仍是 actor/character_cards.json：格式不变，仅落库目标变了）
        // 起 actor_profile_card 表物理废弃，档案并入角色统一主表 actor_profile 的
        // persona_json / persona_version 两列，因此这里改为：按 id 映射后的 newCharId 找到
        // ActorCharacter，直接把档案 JSON 写进它的档案列（不再 new 一个"角色卡"实体）。
        // · systemPrompt 不再导入：system_prompt 概念已废弃（改为运行时按场景渲染分片），包里的旧值无落点；
        // · source 不再导入：来源改由档案 JSON 内的 _sources/generated_by 表达，包外层的旧字段无落点；
        // · 零阻断：单条缺 personaJson、或角色没映射上时跳过并计数，绝不让一个坏条目打断整包导入。
        // · 特殊型与普通型卡都在此统一还原（普通型须已在 ordinary_npcs 段建行并映射进
        //   characterIdMap，否则其角色卡会被当成"角色缺失"跳过——顺序：characters→ordinary→cards）。
        JsonNode cards = readJson(entries, "actor/character_cards.json");
        if (cards != null && cards.isArray()) {
            int profileImported = 0;
            int profileSkipped = 0;
            for (JsonNode card : cards) {
                Long oldCharId = card.path("characterId").asLong();
                Long newCharId = characterIdMap.get(oldCharId);
                if (newCharId == null) continue;
                JsonNode personaNode = card.path("personaJson");
                if (personaNode.isMissingNode() || personaNode.isNull()) {
                    profileSkipped++;
                    continue;
                }
                // 兼容两种导出形态：档案 JSON 以字符串存放（历史形态）或以内嵌对象存放
                String personaJson = personaNode.isTextual() ? personaNode.asText() : personaNode.toString();
                if (personaJson.isBlank()) {
                    profileSkipped++;
                    continue;
                }
                ActorCharacter archived = characterRepository.findById(newCharId).orElse(null);
                if (archived == null) {
                    profileSkipped++;
                    continue;
                }
                archived.setPersonaJson(personaJson);
                // 版本号：优先取包内外层 version；缺失时退回档案 JSON 内的 persona_version
                // （导出包可能只带档案本体，此时版本以内嵌字段为准，避免把已有多版档案降回 1）
                Integer version = readInt(card, "version");
                if (version == null || version < 1) {
                    PersonaProfile profile = PersonaJson.readQuietly(personaJson);
                    version = profile == null || profile.getPersonaVersion() == null ? 1 : profile.getPersonaVersion();
                }
                archived.setPersonaVersion(version);
                characterRepository.save(archived);
                profileImported++;
            }
            log.info("[导入] 项目={} 还原角色档案 {} 条（无 personaJson 或角色缺失跳过 {} 条）",
                    project.getName(), profileImported, profileSkipped);
        }

        // 角色社会关系（actor/relations.json；本次新增段，历史包无此数据 → 跳过，零阻断）。
        // 端点重链：fromRef/toRef（npc:/special:/crowd:{旧id}）优先 → characterIdMap 新 id；
        // fromCharacterId/toCharacterId 兜底；幽灵端点（id=0/未映射）保留名称、id 记 0，不阻断。
        JsonNode relations = readJson(entries, "actor/relations.json");
        if (relations != null && relations.isArray()) {
            int imported = 0, skipped = 0;
            for (JsonNode r : relations) {
                String relationType = readText(r, "relationType");
                if (relationType == null || relationType.isBlank()) {
                    skipped++;
                    continue;
                }
                String fromRef = readText(r, "fromRef");
                String toRef = readText(r, "toRef");
                Long fromId = resolveRelationEndpoint(fromRef, characterIdMap);
                Long toId = resolveRelationEndpoint(toRef, characterIdMap);
                Long oldFrom = r.path("fromCharacterId").isNull() ? null : r.path("fromCharacterId").asLong();
                Long oldTo = r.path("toCharacterId").isNull() ? null : r.path("toCharacterId").asLong();
                if (fromId == null && oldFrom != null) {
                    fromId = characterIdMap.get(oldFrom);
                }
                if (toId == null && oldTo != null) {
                    toId = characterIdMap.get(oldTo);
                }
                ActorCharacterRelation rel = new ActorCharacterRelation();
                rel.setProjectId(project.getId());
                rel.setFromCharacterId(fromId == null ? 0L : fromId);
                rel.setToCharacterId(toId == null ? 0L : toId);
                rel.setFromRef(remapRef(fromRef, characterIdMap));
                rel.setToRef(remapRef(toRef, characterIdMap));
                rel.setFromName(Texts.truncate(readText(r, "fromName"), 100));
                rel.setToName(Texts.truncate(readText(r, "toName"), 100));
                rel.setRelationType(Texts.truncate(relationType, 50));
                rel.setDescription(readText(r, "description"));
                rel.setAffinity(readInt(r, "affinity"));
                rel.setTrust(readInt(r, "trust"));
                rel.setRelationState(Texts.truncate(readText(r, "relationState"), 30));
                rel.setLastInteractionAt(parseTime(readText(r, "lastInteractionAt")));
                relationRepository.save(rel);
                imported++;
            }
            log.info("[导入] 项目={} 还原社会关系 {} 条（跳过 {} 条）", project.getName(), imported, skipped);
        }

        // M1 世界地图（沙盘场景/摆放/住所/沙盘状态；v2.0 新增段，历史包无此数据 → 跳过，零阻断）
        importSandboxMap(entries, project.getId(), characterIdMap, locationIdMap);

        // 会话 + 成员 + 消息
        JsonNode conversations = readJson(entries, "actor/conversations.json");
        Map<Long, List<Long>> membersByConv = new HashMap<>();
        JsonNode members = readJson(entries, "actor/conversation_members.json");
        if (members != null && members.isArray()) {
            for (JsonNode mb : members) {
                membersByConv.computeIfAbsent(mb.path("conversationId").asLong(), k -> new ArrayList<>())
                        .add(mb.path("characterId").asLong());
            }
        }
        if (conversations != null && conversations.isArray()) {
            for (JsonNode c : conversations) {
                Long oldId = c.path("id").asLong();
                ActorConversation conv = new ActorConversation();
                conv.setProjectId(project.getId());
                conv.setUserId(userId);
                conv.setMode(readText(c, "mode", "single"));
                conv.setTitle(readText(c, "title"));
                Integer we = readInt(c, "worldEventEnabled");
                conv.setWorldEventEnabled(we == null ? 0 : we);
                conv = conversationRepository.save(conv);
                conversationIdMap.put(oldId, conv.getId());
                // 成员
                for (Long oldCharId : membersByConv.getOrDefault(oldId, List.of())) {
                    Long newCharId = characterIdMap.get(oldCharId);
                    if (newCharId == null) continue;
                    ActorConversationMember cm = new ActorConversationMember();
                    cm.setConversationId(conv.getId());
                    cm.setCharacterId(newCharId);
                    conversationMemberRepository.save(cm);
                }
                // 消息（每会话 JSONL）
                List<JsonNode> msgs = readJsonLines(entries, "actor/messages/" + oldId + ".jsonl");
                for (JsonNode msg : msgs) {
                    ActorMessage m = new ActorMessage();
                    m.setConversationId(conv.getId());
                    Long oldMsgChar = msg.path("characterId").isNull() ? null : msg.path("characterId").asLong();
                    m.setCharacterId(oldMsgChar == null ? null : characterIdMap.get(oldMsgChar));
                    m.setRole(readText(msg, "role", "user"));
                    m.setType(readText(msg, "type", "text"));
                    m.setContent(msg.path("content").isNull() ? null : msg.path("content").asText());
                    m.setStatus(readText(msg, "status", "done"));
                    m.setTokenIn(readInt(msg, "tokenIn", 0));
                    m.setTokenOut(readInt(msg, "tokenOut", 0));
                    messageRepository.save(m);
                }
            }
        }

        // 事件
        // 收口：kind/source 走事件总线同一份白名单归一（非法值回退 event/manual），
        // title/locationName 按实体列宽截断——外部包是"不可信输入"，原样落库会在
        // 数据库层抛 Value too long，或写入总线读不回来的脏枚举值。
        for (JsonNode e : readJsonLines(entries, "actor/events.jsonl")) {
            ActorEvent event = new ActorEvent();
            event.setProjectId(project.getId());
            event.setKind(ActorEventFields.normalizeKind(readText(e, "kind", "event")));
            event.setTitle(ActorEventFields.truncate(readText(e, "title"), ActorEventFields.TITLE_MAX));
            event.setContent(readText(e, "content"));
            Long oldChar = e.path("characterId").isNull() ? null : e.path("characterId").asLong();
            event.setCharacterId(oldChar == null ? null : characterIdMap.get(oldChar));
            Long oldScene = e.path("sceneId").isNull() ? null : e.path("sceneId").asLong();
            event.setSceneId(oldScene == null ? null : sceneIdMap.get(oldScene));
            event.setSource(ActorEventFields.normalizeSource(readText(e, "source", "manual")));
            Long gh = e.path("gameHour").isNull() ? null : e.path("gameHour").asLong();
            event.setGameHour(gh);
            eventRepository.save(event);
        }

        // 记忆
        JsonNode memories = readJson(entries, "actor/memory.json");
        if (memories != null && memories.isArray()) {
            for (JsonNode mem : memories) {
                ActorMemory m = new ActorMemory();
                m.setProjectId(project.getId());
                Long oldChar = mem.path("characterId").isNull() ? null : mem.path("characterId").asLong();
                m.setCharacterId(oldChar == null ? null : characterIdMap.get(oldChar));
                // 起 type 取代 kind：读新导出 type，兼容旧导出 kind（映射）
                String memType = readText(mem, "type", null);
                if (memType == null) {
                    memType = ActorMemoryTypes.mapOldKindToType(readText(mem, "kind", "fact"));
                }
                m.setType(memType);
                m.setContent(readText(mem, "content"));
                m.setImportance(readInt(mem, "importance", 1));
                m.setDeleted(readInt(mem, "deleted", 0));
                memoryRepository.save(m);
            }
        }

        // 知识文档（不含向量，导入后需重新向量化）
        JsonNode knowledge = readJson(entries, "actor/knowledge.json");
        if (knowledge != null && knowledge.isArray()) {
            for (JsonNode d : knowledge) {
                ActorKnowledgeDoc doc = new ActorKnowledgeDoc();
                doc.setProjectId(project.getId());
                Long oldChar = d.path("characterId").isNull() ? null : d.path("characterId").asLong();
                doc.setCharacterId(oldChar == null ? null : characterIdMap.get(oldChar));
                doc.setTitle(readText(d, "title"));
                doc.setContent(readText(d, "content"));
                knowledgeDocRepository.save(doc);
            }
        }

        // 普通型 NPC 已在上文（角色档案之后、世界地图/关系之前）导入并记入 characterIdMap。
        // 标准字段字典 + 项目级调度运行时（紧随其后的两段）
        // 标准字段字典（actor/npc_field_dict.json， 取代旧 actor/crowd_categories.json）
        // 收口：字段名走分类白名单（race/affiliation/occupation），一级/二级值按列宽截断。
        // 白名单外的字段名直接跳过而不是兜底改名——落库后按 field 聚合的人群分类会静默失效，
        // 不如不导入并在日志里报出条数。
        JsonNode fieldDict = readJson(entries, "actor/npc_field_dict.json");
        if (fieldDict != null && fieldDict.isArray()) {
            int sort = 0;
            int skipped = 0;
            for (JsonNode d : fieldDict) {
                String field = NpcClassifyFields.normalize(readText(d, "field"));
                String level1 = Texts.truncate(readText(d, "level1"), NpcClassifyFields.LEVEL1_MAX);
                if (field == null || level1 == null) {
                    skipped++;
                    continue;
                }
                ActorNpcFieldDict fd = new ActorNpcFieldDict();
                fd.setProjectId(project.getId());
                fd.setField(field);
                fd.setLevel1(level1);
                fd.setLevel2(NpcClassifyFields.RACE.equals(field)
                        ? Texts.truncate(readText(d, "level2"), NpcClassifyFields.LEVEL2_MAX)
                        : null);
                fd.setSource(Texts.truncate(readText(d, "source"), NpcClassifyFields.SOURCE_MAX));
                Integer so = readInt(d, "sortOrder");
                fd.setSortOrder(so == null ? sort++ : so);
                fieldDictRepository.save(fd);
            }
            log.info("[导入] 项目={} 还原字段字典 {} 条（跳过非法/空 {} 条）",
                    project.getName(), fieldDict.size() - skipped, skipped);
        }
        JsonNode runtime = readJson(entries, "actor/crowd_runtime.json");
        if (runtime != null && !runtime.isNull()) {
            ActorCrowdRuntime rt = new ActorCrowdRuntime();
            rt.setProjectId(project.getId());
            rt.setEnabled(readInt(runtime, "enabled", 0));
            // 主/次分类字段同样走白名单（AI 只从 种族/归属/职业 中选两个），列宽 30
            rt.setPrimaryField(NpcClassifyFields.normalize(readText(runtime, "primaryField")));
            rt.setSecondaryField(NpcClassifyFields.normalize(readText(runtime, "secondaryField")));
            rt.setLastScheduleAt(parseTime(readText(runtime, "lastScheduleAt")));
            rt.setLatestSummary(readText(runtime, "latestSummary"));
            crowdRuntimeRepository.save(rt);
        }

        // 世界时钟
        JsonNode clock = readJson(entries, "actor/world_clock.json");
        if (clock != null && !clock.isNull()) {
            ActorWorldClock wc = new ActorWorldClock();
            wc.setProjectId(project.getId());
            Integer rate = readInt(clock, "rate");
            wc.setRate(rate == null ? 24 : rate);
            wc.setWorldStartAt(parseTime(readText(clock, "worldStartAt")));
            Long wsg = clock.path("worldStartGameHour").isNull() ? null : clock.path("worldStartGameHour").asLong();
            wc.setWorldStartGameHour(wsg == null ? 0 : wsg);
            wc.setLastSummary(readText(clock, "lastSummary"));
            Integer paused = readInt(clock, "paused");
            wc.setPaused(paused == null ? 0 : paused);
            // 世界持续运行常驻开关：随包还原（world_running=0 冻结/1 持续运行）——必须显式设置，
            // 否则 @PrePersist 会默认成 1（导入后的世界会自动开跑，违背"世界保持静止"的语义）。
            Integer worldRunning = readInt(clock, "worldRunning");
            if (worldRunning != null) {
                wc.setWorldRunning(worldRunning);
            }
            worldClockRepository.save(wc);
            // 时间口径统一：世界时间权威=引擎推进位置（last_game_second）——导入必须恢复该列，
            // 否则导入后的世界时间从纪元 0 起跑（0001年01月01日 00时），与导出档的世界时间不一致。
            // 旧档无 lastGameSecond 列时回退 lastGameHour×3600。
            Long importedSecond = null;
            if (clock.hasNonNull("lastGameSecond")) {
                importedSecond = clock.path("lastGameSecond").asLong();
            } else if (clock.hasNonNull("lastGameHour")) {
                importedSecond = clock.path("lastGameHour").asLong() * 3600L;
            }
            if (importedSecond != null && importedSecond > 0 && worldClockService != null) {
                // 校准世界时间并把真实锚点重基到该时刻（锚点换算已降级为诊断口径，重基后二者一致）
                worldClockService.alignWorldTimeSeconds(project.getId(), importedSecond);
            }
        }

        // 项目级设置：Prompt 模板（项目级覆盖）
        JsonNode prompts = readJson(entries, "actor/settings/prompts.json");
        if (prompts != null && prompts.isArray()) {
            // 捕获有效 final 变量供 lambda 使用（project 在本方法内被重新赋值）
            final Long newProjectId = project.getId();
            for (JsonNode t : prompts) {
                String code = readText(t, "code");
                if (code == null) continue;
                ActorPromptTemplate pt = promptTemplateRepository
                        .findByUserIdAndProjectIdAndCode(userId, newProjectId, code).orElseGet(() -> {
                            ActorPromptTemplate nt = new ActorPromptTemplate();
                            nt.setUserId(userId);
                            nt.setProjectId(newProjectId);
                            nt.setCode(code);
                            return nt;
                        });
                pt.setName(readText(t, "name"));
                pt.setTemplate(readText(t, "template"));
                pt.setSystemMessage(readText(t, "systemMessage"));
                Integer tv = readInt(t, "version");
                pt.setVersion(tv == null ? 1 : tv);
                pt.setEnabled(1);
                // 分类：包内优先，缺失按编码兜底（保证设置页分组稳定）
                String cat = readText(t, "category");
                pt.setCategory(cat == null || cat.isBlank()
                        ? com.holzyn.actor.domain.settings.service.PromptTemplateService.categoryOf(code)
                        : cat);
                promptTemplateRepository.save(pt);
            }
        }

        // 项目级设置：API 配置（仅 apisAvailable 时导入）
        if (apisAvailable) {
            try {
                JsonNode apis = objectMapper.readTree(apisContent);
                if (apis.isArray()) {
                    for (JsonNode a : apis) {
                        ModelProvider p = new ModelProvider();
                        p.setUserId(userId);
                        p.setProjectId(project.getId());
                        p.setName(readText(a, "name", "导入 API"));
                        p.setBaseUrl(readText(a, "baseUrl"));
                        p.setApiKeyCipher(a.path("apiKeyCipher").isNull() ? null : a.path("apiKeyCipher").asText());
                        p.setModel(readText(a, "model"));
                        p.setPurpose(readText(a, "purpose", "chat"));
                        Integer se = readInt(a, "supportsStream");
                        p.setSupportsStream(se == null ? 1 : se);
                        Integer def = readInt(a, "isDefault");
                        p.setIsDefault(def == null ? 0 : def);
                        Integer en = readInt(a, "enabled");
                        p.setEnabled(en == null ? 1 : en);
                        p.setEmbeddingModel(readText(a, "embeddingModel"));
                        modelProviderRepository.save(p);
                    }
                }
            } catch (Exception e) {
                log.warn("[导入] 项目 API 配置解析失败，跳过：{}", e.getMessage());
            }
        }

        // 群聊配置
        JsonNode chat = readJson(entries, "actor/settings/chat.json");
        if (chat != null && !chat.isNull() && chat.path("maxReplies").isIntegralNumber()) {
            ActorGroupChatConfig cfg = groupChatConfigRepository.findByUserId(userId).orElseGet(() -> {
                ActorGroupChatConfig nc = new ActorGroupChatConfig();
                nc.setUserId(userId);
                return nc;
            });
            cfg.setMaxReplies(chat.path("maxReplies").asInt(5));
            groupChatConfigRepository.save(cfg);
        }

        int charCount = characterIdMap.size();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("projectId", project.getId());
        out.put("name", project.getName());
        out.put("projectUid", projectUid);
        out.put("characterCount", charCount);
        out.put("conversationCount", conversationIdMap.size());
        out.put("sensitiveImported", apisAvailable);
        out.put("sensitiveUnavailable", encrypted && !apisAvailable);
        log.info("[导入] 项目「{}」导入完成：角色={} 会话={} 敏感可用={}", project.getName(),
                charCount, conversationIdMap.size(), apisAvailable);
        // RAG 适配：导入事务提交后 → 世界设定/地点同步为项目级知识文档（向量化含 embedding AI 调用，
        // 必须在事务外执行；afterCommit 时请求上下文可能已清理，userId 在事务内先取出；
        // project 在方法内被重新赋值非 effectively final，先捕获 id/name 供匿名类使用）
        final Long newProjectId = project.getId();
        final String newProjectName = project.getName();
        try {
            if (TransactionSynchronizationManager.isSynchronizationActive()) {
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        try {
                            int n = worldKnowledgeSyncService.syncWorldSetting(userId, newProjectId);
                            log.info("[导入-知识同步] 项目={} 世界设定知识文档同步 {} 份", newProjectName, n);
                        } catch (Exception e) {
                            log.warn("[导入-知识同步] 提交后同步失败（可手动补建）: 项目={} : {}", newProjectName, e.getMessage());
                        }
                    }
                });
            } else {
                worldKnowledgeSyncService.syncWorldSetting(userId, newProjectId);
            }
        } catch (Exception e) {
            log.warn("[导入-知识同步] 注册失败（跳过，可手动补建）: 项目={} : {}", newProjectName, e.getMessage());
        }
        return out;
    }

    // ==================== M1 世界地图还原（沙盘场景/摆放/住所/沙盘状态） ====================

    /**
     * M1 世界地图还原（actor/sandbox/{scene,placements,homes,states}.json）。
     * <p>v2.0 新增 .holzyn 段：历史包无此数据 → 直接跳过；任一沙盘仓库未装配 → 跳过地图段（零阻断）。
     * id 重映射：scene/placement 旧 id → 新 id（住所 placement_id 重链）；location 旧 id → 新 id
     * （locationIdMap，旧 id 缺失/未映射时按地点名回退）；npc_ref 旧 id → 新 id
     * （characterIdMap，special/npc/crowd 前缀，未命中保持原样）。地图段任何异常只告警，
     * 绝不让整包导入失败（用户可后续手动重生成地图）。</p>
     */
    private void importSandboxMap(Map<String, byte[]> entries, Long newProjectId,
                                  Map<Long, Long> characterIdMap, Map<Long, Long> locationIdMap) {
        if (sandboxSceneRepository == null) {
            return;
        }
        JsonNode sbScene = readJson(entries, "actor/sandbox/scene.json");
        if (sbScene == null || sbScene.isNull()) {
            return;
        }
        try {
            ActorSandboxScene scene = new ActorSandboxScene();
            scene.setProjectId(newProjectId);
            scene.setName(readText(sbScene, "name", "主场景"));
            scene.setTemplateCode(readText(sbScene, "templateCode", "village"));
            scene.setStyleCode(readText(sbScene, "styleCode", "plain"));
            Integer size = readInt(sbScene, "sceneSize", 500);
            scene.setSceneSize(size == null || size <= 0 ? 500 : size);
            if (sbScene.path("seed").isIntegralNumber()) {
                scene.setSeed(sbScene.path("seed").asLong());
            }
            scene.setLayoutJson(readText(sbScene, "layoutJson"));
            scene.setEnvJson(readText(sbScene, "envJson"));
            scene.setAnchorJson(readText(sbScene, "anchorJson"));
            scene.setTerrainOverrideJson(readText(sbScene, "terrainOverrideJson"));
            scene.setPaletteJson(readText(sbScene, "paletteJson"));
            scene.setStyleDesc(readText(sbScene, "styleDesc"));
            scene.setSource(readText(sbScene, "source", "ai_generated"));
            Integer ver = readInt(sbScene, "version", 1);
            scene.setVersion(ver == null ? 1 : ver);
            scene = sandboxSceneRepository.save(scene);
            Long newSceneId = scene.getId();

            // 摆放（旧 id → 新 id，供住所 placement_id 重链）
            Map<Long, Long> placementIdMap = new HashMap<>();
            JsonNode placements = readJson(entries, "actor/sandbox/placements.json");
            if (placements != null && placements.isArray()) {
                for (JsonNode p : placements) {
                    ActorScenePlacement row = new ActorScenePlacement();
                    row.setProjectId(newProjectId);
                    row.setSceneId(newSceneId);
                    row.setAssetId(readText(p, "assetId"));
                    row.setName(readText(p, "name"));
                    row.setPosX(num(p, "posX"));
                    row.setPosY(num(p, "posY"));
                    row.setPosZ(num(p, "posZ"));
                    row.setScaleX(num(p, "scaleX"));
                    row.setScaleY(num(p, "scaleY"));
                    row.setScaleZ(num(p, "scaleZ"));
                    row.setRotX(num(p, "rotX"));
                    row.setRotY(num(p, "rotY"));
                    row.setRotZ(num(p, "rotZ"));
                    Integer so = readInt(p, "sortOrder", 0);
                    row.setSortOrder(so == null ? 0 : so);
                    row = sandboxPlacementRepository.save(row);
                    if (p.path("id").isIntegralNumber()) {
                        placementIdMap.put(p.path("id").asLong(), row.getId());
                    }
                }
            }

            // 住所 + 住户（地点/摆放/角色引用全部重映射）
            JsonNode homes = readJson(entries, "actor/sandbox/homes.json");
            if (homes != null && homes.isArray()) {
                for (JsonNode h : homes) {
                    ActorNpcHome home = new ActorNpcHome();
                    home.setProjectId(newProjectId);
                    home.setName(readText(h, "name"));
                    home.setLocationId(resolveLocationId(h, locationIdMap, newProjectId));
                    Long oldPlacement = h.path("placementId").isIntegralNumber()
                            ? h.path("placementId").asLong() : null;
                    home.setPlacementId(oldPlacement == null ? null : placementIdMap.get(oldPlacement));
                    home.setPosX(num(h, "posX"));
                    home.setPosZ(num(h, "posZ"));
                    home.setRoomLabel(readText(h, "roomLabel"));
                    home.setRegion(readText(h, "region"));
                    home.setNote(readText(h, "note"));
                    home.setStatus(readText(h, "status", "active"));
                    Integer deco = readInt(h, "decorationLevel", 0);
                    home.setDecorationLevel(deco == null ? 0 : deco);
                    home = npcHomeRepository.save(home);
                    Long newHomeId = home.getId();
                    JsonNode memberNodes = h.path("members");
                    if (memberNodes.isArray()) {
                        for (JsonNode mm : memberNodes) {
                            ActorHomeMember member = new ActorHomeMember();
                            member.setProjectId(newProjectId);
                            member.setHomeId(newHomeId);
                            member.setCharacterRef(remapRef(readText(mm, "characterRef"), characterIdMap));
                            Integer owner = readInt(mm, "isOwner", 0);
                            member.setIsOwner(owner == null ? 0 : owner);
                            member.setRole(readText(mm, "role"));
                            member.setRoomLabel(readText(mm, "roomLabel"));
                            homeMemberRepository.save(member);
                        }
                    }
                }
            }

            // 沙盘状态（NPC 坐标；npc_ref 重映射）
            JsonNode states = readJson(entries, "actor/sandbox/states.json");
            if (states != null && states.isArray()) {
                for (JsonNode st : states) {
                    ActorSandboxState state = new ActorSandboxState();
                    state.setProjectId(newProjectId);
                    state.setSceneId(newSceneId);
                    state.setNpcType(readText(st, "npcType"));
                    state.setNpcRef(remapRef(readText(st, "npcRef"), characterIdMap));
                    state.setPosX(num(st, "posX"));
                    state.setPosY(num(st, "posY"));
                    state.setPosZ(num(st, "posZ"));
                    state.setHeading(num(st, "heading"));
                    state.setMoveState(readText(st, "moveState", "idle"));
                    state.setTargetPosJson(readText(st, "targetPosJson"));
                    state.setActionText(readText(st, "actionText"));
                    state.setStatusText(readText(st, "statusText"));
                    state.setThoughtText(readText(st, "thoughtText"));
                    state.setThoughtSource(readText(st, "thoughtSource"));
                    if (st.path("gameSecond").isIntegralNumber()) {
                        state.setGameSecond(st.path("gameSecond").asLong());
                    }
                    sandboxStateRepository.save(state);
                }
            }
            log.info("[导入] 项目={} 还原 M1 世界地图（scene id={}）", scene.getName(), newSceneId);
        } catch (Exception e) {
            // 地图段失败绝不让整包导入失败（可后续手动重生成地图）
            log.warn("[导入] M1 世界地图还原失败（跳过地图，可手动重生成）: {}", e.getMessage());
        }
    }

    /** 数字字段（isNumber 才取；非数字/缺失返回 null）。 */
    private Double num(JsonNode node, String field) {
        JsonNode v = node.path(field);
        return v.isNumber() ? v.asDouble() : null;
    }

    /**
     * 住所地点重解析：优先 locationIdMap（旧 id → 新 id）；旧 id 缺失/未映射时按地点名回退
     * （精确匹配，忽略大小写）。仍失败返回 null（住所保留坐标，地点关联留空，不阻断）。
     */
    private Long resolveLocationId(JsonNode home, Map<Long, Long> locationIdMap, Long newProjectId) {
        if (home.path("locationId").isIntegralNumber()) {
            Long old = home.path("locationId").asLong();
            if (locationIdMap != null && locationIdMap.containsKey(old)) {
                return locationIdMap.get(old);
            }
        }
        String name = readText(home, "locationName");
        if (name != null && !name.isBlank()) {
            for (ActorWorldLocation loc : worldLocationRepository.findByProjectId(newProjectId)) {
                if (name.equalsIgnoreCase(loc.getName() == null ? "" : loc.getName().trim())) {
                    return loc.getId();
                }
            }
        }
        return null;
    }

    /**
     * NPC 引用重映射：special/npc/crowd:{旧id} → {新id}（characterIdMap 按角色 id 换算）；
     * 未命中保持原样 fail-open（不影响导入）。
     */
    private String remapRef(String ref, Map<Long, Long> characterIdMap) {
        if (ref == null || characterIdMap == null || characterIdMap.isEmpty()) {
            return ref;
        }
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("^(special|npc|crowd):(\\d+)$").matcher(ref.trim());
        if (m.matches()) {
            Long newId = characterIdMap.get(Long.parseLong(m.group(2)));
            if (newId != null) {
                return m.group(1) + ":" + newId;
            }
        }
        return ref;
    }

    /**
     * 关系端点 id 重链：解析 npc:/special:/crowd:{旧id}（或裸数字）→ characterIdMap 新 id。
     * 解析失败或未映射返回 null（幽灵端点，由调用方兜底 id=0 + 名称）。
     */
    private Long resolveRelationEndpoint(String ref, Map<Long, Long> characterIdMap) {
        if (ref == null || characterIdMap == null || characterIdMap.isEmpty()) {
            return null;
        }
        Long oldId = com.holzyn.actor.common.NpcRefs.parseLenient(ref);
        return oldId == null ? null : characterIdMap.get(oldId);
    }

    // ==================== 工具 ====================

    /**
     * 解包 ZIP → Map&lt;path, bytes&gt;。
     */
    private Map<String, byte[]> unzip(byte[] bytes) {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.isDirectory()) continue;
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                int len;
                while ((len = zis.read(buffer)) > 0) {
                    bos.write(buffer, 0, len);
                }
                entries.put(entry.getName(), bos.toByteArray());
            }
        } catch (Exception e) {
            throw new BizException(400, "无法解包 .holzyn 文件：" + e.getMessage());
        }
        return entries;
    }

    /**
     * 读取 JSON 文件并解析（缺失返回 null）。
     */
    private JsonNode readJson(Map<String, byte[]> entries, String path) {
        byte[] bytes = entries.get(path);
        if (bytes == null) return null;
        try {
            return objectMapper.readTree(new String(bytes, StandardCharsets.UTF_8));
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 读取 JSONL 文件（每行一个对象，缺失返回空列表）。
     */
    private List<JsonNode> readJsonLines(Map<String, byte[]> entries, String path) {
        List<JsonNode> out = new ArrayList<>();
        byte[] bytes = entries.get(path);
        if (bytes == null) return out;
        try {
            String text = new String(bytes, StandardCharsets.UTF_8);
            for (String line : text.split("\n")) {
                if (line.isBlank()) continue;
                try {
                    out.add(objectMapper.readTree(line));
                } catch (Exception ignored) {
                    // 单行解析失败跳过
                }
            }
        } catch (Exception e) {
            // 忽略
        }
        return out;
    }

    /**
     * 读取文本文件（缺失返回 null）。
     */
    private String readText(Map<String, byte[]> entries, String path) {
        byte[] bytes = entries.get(path);
        return bytes == null ? null : new String(bytes, StandardCharsets.UTF_8);
    }

    /**
     * 字段取值（字符串）。
     */
    private String readText(JsonNode node, String field) {
        return readText(node, field, null);
    }

    /**
     * 字段取值（字符串，带默认）。
     */
    private String readText(JsonNode node, String field, String def) {
        if (node == null || node.isNull() || node.path(field).isMissingNode() || node.path(field).isNull()) {
            return def;
        }
        String v = node.path(field).asText();
        return v.isBlank() ? def : v;
    }

    /**
     * 字段取值（整数）。
     */
    private Integer readInt(JsonNode node, String field) {
        return readInt(node, field, null);
    }

    /**
     * 字段取值（整数，带默认）。
     */
    private Integer readInt(JsonNode node, String field, Integer def) {
        if (node == null || node.isNull() || !node.path(field).isIntegralNumber()) {
            return def;
        }
        return node.path(field).asInt();
    }

    /**
     * 解析时间（ISO）。
     */
    private LocalDateTime parseTime(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return LocalDateTime.parse(raw);
        } catch (Exception e) {
            return null;
        }
    }
}
