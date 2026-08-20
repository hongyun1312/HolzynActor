package com.holzyn.actor.domain.project.service;

import com.holzyn.actor.common.BizException;
import com.holzyn.actor.domain.project.entity.ActorProject;
import com.holzyn.actor.domain.character.entity.ActorCharacter;
import com.holzyn.actor.domain.conversation.entity.ActorConversation;
import com.holzyn.actor.domain.conversation.entity.ActorMessage;
import com.holzyn.actor.domain.conversation.entity.ActorGroupChatConfig;
import com.holzyn.actor.domain.world.entity.ActorWorldSetting;
import com.holzyn.actor.domain.world.entity.ActorWorldLocation;
import com.holzyn.actor.domain.world.entity.ActorEvent;
import com.holzyn.actor.domain.memory.entity.ActorMemory;
import com.holzyn.actor.domain.knowledge.entity.ActorKnowledgeDoc;
import com.holzyn.actor.domain.world.entity.ActorWorldClock;
import com.holzyn.actor.domain.crowd.entity.ActorCrowdRuntime;
import com.holzyn.actor.domain.crowd.entity.ActorNpcFieldDict;
import com.holzyn.actor.domain.crowd.entity.ActorOrdinaryNpc;
import com.holzyn.actor.domain.crowd.repository.ActorCrowdRuntimeRepository;
import com.holzyn.actor.domain.crowd.repository.ActorNpcFieldDictRepository;
import com.holzyn.actor.domain.crowd.repository.ActorOrdinaryNpcRepository;
import com.holzyn.actor.domain.world.entity.ActorScene;
import com.holzyn.actor.domain.usage.entity.ActorUsageLog;
import com.holzyn.actor.domain.settings.entity.ModelProvider;
import com.holzyn.actor.domain.project.repository.ActorProjectRepository;
import com.holzyn.actor.domain.world.repository.ActorWorldSettingRepository;
import com.holzyn.actor.domain.character.repository.ActorCharacterRepository;
import com.holzyn.actor.domain.character.repository.ActorCharacterCardRepository;
import com.holzyn.actor.domain.conversation.repository.ActorConversationRepository;
import com.holzyn.actor.domain.conversation.repository.ActorConversationMemberRepository;
import com.holzyn.actor.domain.conversation.repository.ActorMessageRepository;
import com.holzyn.actor.domain.memory.repository.ActorMemoryRepository;
import com.holzyn.actor.domain.knowledge.repository.ActorKnowledgeDocRepository;
import com.holzyn.actor.domain.crowd.repository.ActorCrowdRuntimeRepository;
import com.holzyn.actor.domain.crowd.repository.ActorNpcFieldDictRepository;
import com.holzyn.actor.domain.crowd.repository.ActorOrdinaryNpcRepository;
import com.holzyn.actor.domain.world.repository.ActorWorldClockRepository;
import com.holzyn.actor.domain.world.repository.WorldLocationRepository;
import com.holzyn.actor.domain.action.repository.ActorActionPlanRepository;
import com.holzyn.actor.domain.action.repository.ActorActionLogRepository;
import com.holzyn.actor.domain.world.repository.ActorEventRepository;
import com.holzyn.actor.domain.settings.repository.ActorPromptTemplateRepository;
import com.holzyn.actor.domain.settings.repository.ModelProviderRepository;
import com.holzyn.actor.domain.conversation.repository.ActorGroupChatConfigRepository;
import com.holzyn.actor.domain.usage.repository.ActorUsageLogRepository;
import com.holzyn.actor.domain.world.repository.ActorSceneRepository;
import com.holzyn.actor.domain.account.CurrentUserProvider;
import com.holzyn.actor.common.HolzynCrypto;
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
 * .holzyn 项目包导出服务（V2.0 设计文档，导入/导出能力）。
 * <p>职责：将 DB 中的 actor 项目导出为 .holzyn 分发态单文件（ZIP 容器）——
 * 结构：顶层 holzyn.json 索引 + actor/ 模块目录（settings/ world/ characters/
 * conversations/ messages/ events/ actions/ memory/ knowledge/ ordinary_npcs/ world_clock/ usage/）。
 * 敏感数据处理：settings/apis.json 默认空骨架（不打包密钥）；includeSensitive=true 时
 * 含 API 配置，password 非空则用「密码加密」（PBKDF2 + AES-GCM）加密该文件整块。
 * 实体沿用 DB 数字 id（导入时做 id 映射）。</p>
 * <p>所属模块：service/project（项目导入导出子域）</p>
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
    private final ActorCharacterCardRepository cardRepository;
    private final ActorConversationRepository conversationRepository;
    private final ActorConversationMemberRepository conversationMemberRepository;
    private final ActorMessageRepository messageRepository;
    private final ActorMemoryRepository memoryRepository;
    private final ActorKnowledgeDocRepository knowledgeDocRepository;
    private final ActorOrdinaryNpcRepository ordinaryNpcRepository;
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
    private final CurrentUserProvider currentUserProvider;
    private final HolzynCrypto holzynCrypto;
    private final ObjectMapper objectMapper;

    /**
     * 导出项目为 .holzyn 包（ZIP 字节）。
     *
     * @param projectId       项目 ID
     * @param includeSensitive 是否打包 API 等敏感数据（默认 false）
     * @param password         密码（可选；提供时对敏感文件做密码加密）
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
            // 启用模块：actor（schemaVersion 非空）
            Map<String, Object> actorModule = new LinkedHashMap<>();
            actorModule.put("enabled", true);
            actorModule.put("schemaVersion", SCHEMA_VERSION);
            modules.put("actor", actorModule);
            // 预留模块：未启用，schemaVersion=null（Map.of 不允许 null，用 LinkedHashMap）
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
     * 2026-08-19 起含系统提示词（systemMessage）。
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
     * 角色卡（当前态，每个角色最新版本）。
     */
    private List<Map<String, Object>> buildCharacterCards(List<ActorCharacter> chars) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (ActorCharacter c : chars) {
            cardRepository.findTopByCharacterIdOrderByVersionDesc(c.getId()).ifPresent(card -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", card.getId());
                m.put("characterId", card.getCharacterId());
                m.put("version", card.getVersion());
                m.put("personaJson", card.getPersonaJson());
                m.put("systemPrompt", card.getSystemPrompt());
                m.put("source", card.getSource());
                out.add(m);
            });
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
        m.put("kind", mem.getKind());
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
        m.put("paused", c.getPaused());
        m.put("lastSummary", c.getLastSummary());
        return m;
    }

    /**
     * 普通型 NPC（单表扁平，每人独立一行；2026-08-19 分类体系重构：含次级种族，不含废弃分类字段）。
     */
    private List<Map<String, Object>> buildOrdinaryNpcs(Long projectId) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (ActorOrdinaryNpc n : ordinaryNpcRepository.findByProjectIdOrderByIdAsc(projectId)) {
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
            m.put("state", n.getState());
            m.put("lastAction", n.getLastAction());
            out.add(m);
        }
        return out;
    }

    /**
     * 普通型 NPC 标准字段数据字典（2026-08-19 新增：race 含次级种族/affiliation/occupation，含出处）。
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
                "conversations", convs.size(),
                "messages", convs.stream()
                        .mapToInt(c -> messageRepository.findByConversationIdOrderByIdAsc(c.getId()).size()).sum(),
                "events", eventRepository.findByProjectIdOrderByIdDesc(projectId).size(),
                "memory", memoryRepository.findByProjectIdAndDeletedOrderByIdDesc(projectId, 0).size(),
                "usageCalls", (int) usageLogRepository.findByUserIdOrderByIdDesc(userId).stream()
                        .filter(l -> projectId.equals(l.getProjectId())).count()));
        m.put("files", List.of(
                "settings/project.json", "settings/apis.json", "settings/prompts.json", "settings/chat.json",
                "world.json", "locations.json", "characters.json", "character_cards.json",
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
