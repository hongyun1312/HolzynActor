package com.holzyn.actor.domain.project.service;

import com.holzyn.actor.common.BizException;
import com.holzyn.actor.domain.project.entity.ActorProject;
import com.holzyn.actor.domain.world.entity.ActorWorldSetting;
import com.holzyn.actor.domain.world.entity.ActorWorldLocation;
import com.holzyn.actor.domain.world.entity.ActorScene;
import com.holzyn.actor.domain.character.entity.ActorCharacter;
import com.holzyn.actor.domain.character.entity.ActorCharacterCard;
import com.holzyn.actor.domain.conversation.entity.ActorConversation;
import com.holzyn.actor.domain.conversation.entity.ActorConversationMember;
import com.holzyn.actor.domain.conversation.entity.ActorMessage;
import com.holzyn.actor.domain.world.entity.ActorEvent;
import com.holzyn.actor.domain.memory.entity.ActorMemory;
import com.holzyn.actor.domain.knowledge.entity.ActorKnowledgeDoc;
import com.holzyn.actor.domain.crowd.entity.ActorCrowdRuntime;
import com.holzyn.actor.domain.crowd.entity.ActorNpcFieldDict;
import com.holzyn.actor.domain.crowd.entity.ActorOrdinaryNpc;
import com.holzyn.actor.domain.crowd.repository.ActorNpcFieldDictRepository;
import com.holzyn.actor.domain.world.entity.ActorWorldClock;
import com.holzyn.actor.domain.settings.entity.ActorPromptTemplate;
import com.holzyn.actor.domain.settings.entity.ModelProvider;
import com.holzyn.actor.domain.conversation.entity.ActorGroupChatConfig;
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
import com.holzyn.actor.domain.crowd.repository.ActorOrdinaryNpcRepository;
import com.holzyn.actor.domain.world.repository.ActorWorldClockRepository;
import com.holzyn.actor.domain.world.repository.ActorEventRepository;
import com.holzyn.actor.domain.world.repository.ActorSceneRepository;
import com.holzyn.actor.domain.world.repository.WorldLocationRepository;
import com.holzyn.actor.domain.settings.repository.ActorPromptTemplateRepository;
import com.holzyn.actor.domain.settings.repository.ModelProviderRepository;
import com.holzyn.actor.domain.conversation.repository.ActorGroupChatConfigRepository;
import com.holzyn.actor.domain.account.CurrentUserProvider;
import com.holzyn.actor.common.HolzynCrypto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
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
 * .holzyn 项目包导入服务（V2.0 设计文档，Web 模式：文件 → DB + id 映射）。
 * <p>职责：将 .holzyn 分发态单文件（ZIP 容器）解包并还原为 DB 中的项目——
 * ① 读 holzyn.json 校验版本与加密声明（password-encrypted 需密码，错误则 apis 置空并提示）；
 * ② 幂等检测：projectUid 已存在则拒绝（防重复导入脏数据）；
 * ③ id 映射：文件实体数字 id → DB 自增 id，确保角色卡/会话/消息/事件/记忆等外键正确重链；
 * ④ 导入项目/世界观/角色/角色卡/场景/会话/消息/事件/记忆/知识/人群/时钟/项目级设置（API/Prompt/群聊）。</p>
 * <p>所属模块：service/project（项目导入导出子域）</p>
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
    private final ActorEventRepository eventRepository;
    private final ActorSceneRepository sceneRepository;
    private final WorldLocationRepository worldLocationRepository;
    private final ActorPromptTemplateRepository promptTemplateRepository;
    private final ModelProviderRepository modelProviderRepository;
    private final ActorGroupChatConfigRepository groupChatConfigRepository;
    private final CurrentUserProvider currentUserProvider;
    private final HolzynCrypto holzynCrypto;
    private final ObjectMapper objectMapper;

    /**
     * 导入 .holzyn 包。
     *
     * @param bytes    ZIP 包字节
     * @param password 密码（包为 password-encrypted 时必填）
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
                worldLocationRepository.save(al);
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
        JsonNode characters = readJson(entries, "actor/characters.json");
        if (characters != null && characters.isArray()) {
            for (JsonNode c : characters) {
                ActorCharacter ch = new ActorCharacter();
                ch.setProjectId(project.getId());
                ch.setType(readText(c, "type", "special"));
                ch.setName(readText(c, "name"));
                ch.setTitle(readText(c, "title"));
                ch.setIsProtagonist(readInt(c, "isProtagonist", 0));
                ch.setImportance(readInt(c, "importance", 3));
                ch.setStatus(readInt(c, "status", 0));
                ch.setDetail(readText(c, "detail"));
                ch = characterRepository.save(ch);
                characterIdMap.put(c.path("id").asLong(), ch.getId());
            }
        }
        JsonNode cards = readJson(entries, "actor/character_cards.json");
        if (cards != null && cards.isArray()) {
            for (JsonNode card : cards) {
                Long oldCharId = card.path("characterId").asLong();
                Long newCharId = characterIdMap.get(oldCharId);
                if (newCharId == null) continue;
                ActorCharacterCard cc = new ActorCharacterCard();
                cc.setCharacterId(newCharId);
                Integer cv = readInt(card, "version");
                cc.setVersion(cv == null ? 1 : cv);
                cc.setPersonaJson(card.path("personaJson").isMissingNode() ? null : card.path("personaJson").asText());
                cc.setSystemPrompt(readText(card, "systemPrompt"));
                cc.setSource(readText(card, "source", "generated"));
                cardRepository.save(cc);
            }
        }

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
        for (JsonNode e : readJsonLines(entries, "actor/events.jsonl")) {
            ActorEvent event = new ActorEvent();
            event.setProjectId(project.getId());
            event.setKind(readText(e, "kind", "event"));
            event.setTitle(readText(e, "title"));
            event.setContent(readText(e, "content"));
            Long oldChar = e.path("characterId").isNull() ? null : e.path("characterId").asLong();
            event.setCharacterId(oldChar == null ? null : characterIdMap.get(oldChar));
            Long oldScene = e.path("sceneId").isNull() ? null : e.path("sceneId").asLong();
            event.setSceneId(oldScene == null ? null : sceneIdMap.get(oldScene));
            event.setSource(readText(e, "source", "manual"));
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
                m.setKind(readText(mem, "kind", "fact"));
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
                doc.setEmbedding("[]");
                knowledgeDocRepository.save(doc);
            }
        }

        // 普通型 NPC（单表）+ 标准字段字典 + 项目级调度运行时
        JsonNode ordinaryNpcs = readJson(entries, "actor/ordinary_npcs.json");
        if (ordinaryNpcs != null && ordinaryNpcs.isArray()) {
            for (JsonNode n : ordinaryNpcs) {
                ActorOrdinaryNpc npc = new ActorOrdinaryNpc();
                npc.setProjectId(project.getId());
                npc.setName(readText(n, "name"));
                npc.setGender(readText(n, "gender"));
                npc.setRace(readText(n, "race"));
                npc.setSubRace(readText(n, "subRace"));
                Integer age = readInt(n, "age");
                npc.setAge(age == null || age < 0 ? null : age);
                npc.setAffiliation(readText(n, "affiliation"));
                npc.setLocation(readText(n, "location"));
                npc.setOccupation(readText(n, "occupation"));
                npc.setDetail(readText(n, "detail"));
                npc.setState(readText(n, "state", "idle"));
                npc.setLastAction(readText(n, "lastAction"));
                ordinaryNpcRepository.save(npc);
            }
        }
        // 标准字段字典（actor/npc_field_dict.json，2026-08-19 取代旧 actor/crowd_categories.json）
        JsonNode fieldDict = readJson(entries, "actor/npc_field_dict.json");
        if (fieldDict != null && fieldDict.isArray()) {
            int sort = 0;
            for (JsonNode d : fieldDict) {
                String field = readText(d, "field");
                String level1 = readText(d, "level1");
                if (field == null || level1 == null) continue;
                ActorNpcFieldDict fd = new ActorNpcFieldDict();
                fd.setProjectId(project.getId());
                fd.setField(field);
                fd.setLevel1(level1);
                fd.setLevel2(readText(d, "level2"));
                fd.setSource(readText(d, "source"));
                Integer so = readInt(d, "sortOrder");
                fd.setSortOrder(so == null ? sort++ : so);
                fieldDictRepository.save(fd);
            }
            log.info("[导入] 项目={} 还原字段字典 {} 条", project.getName(), fieldDict.size());
        }
        JsonNode runtime = readJson(entries, "actor/crowd_runtime.json");
        if (runtime != null && !runtime.isNull()) {
            ActorCrowdRuntime rt = new ActorCrowdRuntime();
            rt.setProjectId(project.getId());
            rt.setEnabled(readInt(runtime, "enabled", 0));
            rt.setPrimaryField(readText(runtime, "primaryField"));
            rt.setSecondaryField(readText(runtime, "secondaryField"));
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
            worldClockRepository.save(wc);
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
        return out;
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
