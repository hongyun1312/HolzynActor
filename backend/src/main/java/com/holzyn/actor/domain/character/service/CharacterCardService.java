package com.holzyn.actor.domain.character.service;

import com.holzyn.actor.common.BizException;
import com.holzyn.actor.domain.account.CurrentUserProvider;
import com.holzyn.actor.domain.character.entity.ActorCharacter;
import com.holzyn.actor.domain.character.entity.ActorCharacterCard;
import com.holzyn.actor.domain.project.entity.ActorProject;
import com.holzyn.actor.domain.world.entity.ActorWorldSetting;
import com.holzyn.actor.domain.character.vo.CharacterCardVO;
import com.holzyn.actor.domain.character.repository.ActorCharacterCardRepository;
import com.holzyn.actor.domain.character.repository.ActorCharacterRepository;
import com.holzyn.actor.domain.project.repository.ActorProjectRepository;
import com.holzyn.actor.domain.world.repository.ActorWorldSettingRepository;
import com.holzyn.actor.domain.usage.service.UsageLogService;
import com.holzyn.actor.ai.AiChatRequest;
import com.holzyn.actor.ai.AiChatResult;
import com.holzyn.actor.ai.AiProviderRouter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 角色卡生成引擎（A-C2 核心产物）。
 * <p>职责：基于世界观设定与角色档案，调用 AI（OpenAI 兼容 json 输出）生成结构化角色卡，
 * 经 JSON Schema 校验后按版本写入 actor_character_card，并渲染对话系统 Prompt。
 * 生成失败自动重试（≤2 次），全部失败抛出中文提示。</p>
 * <p>归属与凭据：角色归属经 CharacterService 校验；AI 调用走 AiProviderRouter，
 * 使用当前用户配置的默认模型 API（无配置时给出友好提示）。</p>
 * <p>所属模块：service/character（角色/角色卡子域）</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CharacterCardService {

    /**
     * 结构化输出最大 token。
     * <p>2026-08-17 提升 4096→16384：用户可输入最多 20000 字的角色详细信息（实测 3800 字 ≈ 3800+ token），
     * 4096 输出上限会让模型被迫压缩/截断角色卡（表现为结构化卡空、Prompt 仅千字、信息失真）。
     * 给足空间后模型才能「只可多不可少」地完整保留详细信息。</p>
     */
    private static final int CARD_MAX_TOKENS = 16384;

    /** 生成失败最大重试次数 */
    private static final int MAX_RETRY = 2;

    private final ActorCharacterRepository characterRepository;
    private final ActorCharacterCardRepository cardRepository;
    private final ActorProjectRepository projectRepository;
    private final ActorWorldSettingRepository worldSettingRepository;
    private final CharacterService characterService;
    private final PromptService promptService;
    private final AiProviderRouter aiProviderRouter;
    private final CurrentUserProvider currentUserProvider;
    private final UsageLogService usageLogService;
    private final ObjectMapper objectMapper;

    /**
     * 为单个角色生成（或重新生成）角色卡（归属校验）。
     *
     * @param characterId 角色主键
     * @return 生成后的角色卡 VO（新版本）
     */
    @Transactional
    public CharacterCardVO generateCard(Long characterId) {
        ActorCharacter character = characterService.requireOwned(characterId);
        Long userId = currentUserProvider.currentUserId();
        long taskStart = System.currentTimeMillis();
        log.info("[角色卡生成] 任务开始：角色ID={} 名称={}", characterId, character.getName());
        String prompt = buildGenPrompt(character);

        // 重试循环：结构化输出不稳定时重试（≤2 次）
        AiChatResult result = null;
        Exception lastError = null;
        for (int attempt = 1; attempt <= MAX_RETRY; attempt++) {
            try {
                AiChatRequest req = new AiChatRequest(null, List.of(
                        new AiChatRequest.ChatMessage("system", "你只输出严格的 JSON，不输出任何其他文字。"),
                        new AiChatRequest.ChatMessage("user", prompt)
                ), 0.7, CARD_MAX_TOKENS);
                result = aiProviderRouter.chatCompletion(userId, character.getProjectId(), null, req);
                String persona = extractJson(result.content());
                JsonNode root = validateSchema(persona);
                if (root == null) {
                    throw new BizException("角色卡输出不符合 Schema 要求");
                }
                CharacterCardVO card = persistCard(character, persona);
                // 用量记录（scene=card_gen：角色卡生成，含 provider/model/token/缓存/耗时）
                // 2026-08-18 补记：此前角色卡生成调用 AI 却未写入 actor_usage_log，用量页无消耗
                usageLogService.record(userId, character.getProjectId(), character.getId(),
                        result.providerId(), result.model(), "card_gen",
                        result.promptTokens(), result.completionTokens(),
                        result.cacheHitTokens(), result.cacheMissTokens(),
                        (int) (System.currentTimeMillis() - taskStart));
                log.info("[角色卡生成] 任务结束：角色ID={} 名称={} 耗时={}ms 成功", characterId, character.getName(), System.currentTimeMillis() - taskStart);
                return card;
            } catch (Exception e) {
                lastError = e;
                log.warn("角色卡生成第 {} 次失败: {}", attempt, e.getMessage());
            }
        }
        log.warn("[角色卡生成] 任务失败：角色ID={} 名称={} 耗时={}ms", characterId, character.getName(), System.currentTimeMillis() - taskStart);
        throw new BizException(400, "角色卡生成失败：" + friendlyError(lastError));
    }

    /**
     * 一键为项目全部角色生成角色卡（逐角色串行）。
     *
     * @param projectId 项目 ID
     * @return 每个角色的生成结果列表（成功含 card，失败含 message）
     */
    @Transactional
    public List<Map<String, Object>> generateAllCards(Long projectId) {
        List<ActorCharacter> chars = characterRepository.findByProjectIdAndDeletedOrderByIdAsc(projectId, 0);
        if (chars.isEmpty()) {
            throw new BizException(400, "该项目还没有角色，请先添加角色再生成角色卡");
        }
        long batchStart = System.currentTimeMillis();
        log.info("[角色卡-批量生成] 任务开始：项目={} 角色数={}", projectId, chars.size());
        List<Map<String, Object>> results = new ArrayList<>();
        for (ActorCharacter c : chars) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("characterId", c.getId());
            item.put("characterName", c.getName());
            try {
                CharacterCardVO card = generateCard(c.getId());
                item.put("success", true);
                item.put("card", card);
            } catch (Exception e) {
                item.put("success", false);
                item.put("message", e.getMessage());
            }
            results.add(item);
        }
        log.info("[角色卡-批量生成] 任务结束：项目={} 角色数={} 耗时={}ms", projectId, chars.size(), System.currentTimeMillis() - batchStart);
        return results;
    }

    /**
     * 为项目「尚未生成角色卡」的角色批量生成（2026-08-19 世界初始化第 2 步；跳过已生成）。
     * <p>与 generateAllCards 的区别：只处理尚无角色卡的角色（幂等），已生成的角色跳过；
     * 单个角色失败不影响其余；返回每个角色的生成结果列表。</p>
     *
     * @param projectId 项目 ID
     * @return 处理结果列表（含 characterId/characterName/success/message；已生成跳过的行 success=false 且 skipped=true）
     */
    @Transactional
    public List<Map<String, Object>> generateMissingCards(Long projectId) {
        List<ActorCharacter> chars = characterRepository.findByProjectIdAndDeletedOrderByIdAsc(projectId, 0);
        if (chars.isEmpty()) {
            log.info("[角色卡-缺失补生成] 项目={} 暂无角色，跳过", projectId);
            return List.of();
        }
        long batchStart = System.currentTimeMillis();
        log.info("[角色卡-缺失补生成] 任务开始：项目={} 角色数={}", projectId, chars.size());
        List<Map<String, Object>> results = new ArrayList<>();
        int generated = 0;
        for (ActorCharacter c : chars) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("characterId", c.getId());
            item.put("characterName", c.getName());
            if (cardRepository.findTopByCharacterIdOrderByVersionDesc(c.getId()).isPresent()) {
                item.put("success", false);
                item.put("skipped", true);
                item.put("message", "已生成，跳过");
                results.add(item);
                continue;
            }
            try {
                CharacterCardVO card = generateCard(c.getId());
                item.put("success", true);
                item.put("card", card);
                generated++;
                log.info("[角色卡-缺失补生成] 角色 {} 角色卡生成成功", c.getName());
            } catch (Exception e) {
                item.put("success", false);
                item.put("skipped", false);
                item.put("message", e.getMessage());
                log.warn("[角色卡-缺失补生成] 角色 {} 生成失败：{}", c.getName(), e.getMessage());
            }
            results.add(item);
        }
        log.info("[角色卡-缺失补生成] 任务结束：项目={} 角色数={} 新生成={} 耗时={}ms",
                projectId, chars.size(), generated, System.currentTimeMillis() - batchStart);
        return results;
    }

    /**
     * 组装角色卡生成 Prompt：世界观 + 角色档案 + 社会关系。
     *
     * @param character 角色实体
     * @return 生成 Prompt
     */
    private String buildGenPrompt(ActorCharacter character) {
        // 世界观：取项目最新版本自由文本
        String worldText = worldSettingRepository.findTopByProjectIdOrderByVersionDesc(character.getProjectId())
                .map(ActorWorldSetting::getFreeText).orElse("");
        // 角色档案摘要
        StringBuilder input = new StringBuilder();
        input.append("姓名：").append(character.getName()).append("\n");
        input.append("类型：").append(character.getType()).append("\n");
        if (character.getTitle() != null && !character.getTitle().isBlank()) {
            input.append("头衔：").append(character.getTitle()).append("\n");
        }
        if (Integer.valueOf(1).equals(character.getIsProtagonist())) {
            input.append("地位：主角\n");
        }
        // 用户自行输入的详细信息：作为核心输入源，要求模型严格遵循
        if (character.getDetail() != null && !character.getDetail().isBlank()) {
            input.append("—— 角色详细信息（用户提供，请严格遵循） ——\n").append(character.getDetail()).append("\n");
        }
        return promptService.buildCharacterCardGenPrompt(currentUserProvider.currentUserId(), character.getProjectId(), worldText, input.toString());
    }

    /**
     * 从 AI 输出中提取 JSON 文本（剥离 Markdown 代码块包裹）。
     *
     * @param content AI 输出内容
     * @return 提取出的 JSON 文本
     */
    private String extractJson(String content) {
        if (content == null) throw new BizException("AI 未返回内容");
        String text = content.trim();
        // 剥离 ```json ... ``` 代码块
        if (text.startsWith("```")) {
            int firstNewline = text.indexOf('\n');
            if (firstNewline > 0) text = text.substring(firstNewline + 1);
            if (text.endsWith("```")) text = text.substring(0, text.length() - 3);
            text = text.trim();
        }
        // 兼容首尾花括号之间有说明文字的情况：截取首个 { 到最后一个 }
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start >= 0 && end > start) {
            text = text.substring(start, end + 1);
        }
        return text;
    }

    /**
     * 校验角色卡 JSON 是否满足核心 Schema（identity/personality/speechStyle/knowledge）。
     * <p>2026-08-17 增强：除字段存在外，额外要求 identity.name 非空且至少一个内容分节非空，
     * 拒绝模型输出的「空壳卡」（全部字段为空对象/空数组），避免生成后结构化卡显示为空。</p>
     *
     * @param persona JSON 文本
     * @return 解析后的根节点（校验失败返回 null）
     */
    private JsonNode validateSchema(String persona) {
        try {
            JsonNode root = objectMapper.readTree(persona);
            boolean hasKeys = root.has("identity") && root.has("personality")
                    && root.has("speechStyle") && root.has("knowledge");
            if (!hasKeys) {
                return null;
            }
            // 内容完整性：姓名非空 + 至少一个内容分节有实际内容（杜绝空壳卡）
            String name = root.path("identity").path("name").asText("").trim();
            boolean hasContent = !name.isEmpty()
                    && (hasText(root.path("personality").path("traits"))
                    || hasText(root.path("background").path("history"))
                    || hasText(root.path("speechStyle").path("tone"))
                    || hasText(root.path("knowledge").path("knows")));
            return hasContent ? root : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** 判断节点是否有实际文本内容（对象/数组/文本均支持） */
    private static boolean hasText(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return false;
        }
        if (node.isTextual()) {
            return !node.asText("").isBlank();
        }
        if (node.isArray()) {
            for (JsonNode n : node) {
                if (hasText(n)) {
                    return true;
                }
            }
            return false;
        }
        return !node.asText("").isBlank();
    }

    /**
     * 将角色卡持久化：新增版本 + 渲染 system_prompt + 更新角色/项目状态。
     *
     * @param character 角色实体
     * @param persona   Schema 校验通过的 JSON 文本
     * @return 新版本角色卡 VO
     */
    private CharacterCardVO persistCard(ActorCharacter character, String persona) {
        ActorCharacterCard latest = cardRepository.findTopByCharacterIdOrderByVersionDesc(character.getId()).orElse(null);
        ActorCharacterCard card = new ActorCharacterCard();
        card.setCharacterId(character.getId());
        card.setVersion(latest == null ? 1 : latest.getVersion() + 1);
        // 归一化 JSON（重新序列化去除多余空白，保证落库为合法 JSON）
        try {
            card.setPersonaJson(objectMapper.writeValueAsString(objectMapper.readTree(persona)));
        } catch (Exception e) {
            card.setPersonaJson(persona);
        }
        String worldName = worldSettingRepository.findTopByProjectIdOrderByVersionDesc(character.getProjectId())
                .map(ActorWorldSetting::getName).orElse(null);
        card.setSystemPrompt(buildSystemPrompt(character, card.getPersonaJson(), worldName));
        card.setSource("generated");
        cardRepository.save(card);

        // 更新角色与项目状态（已生成角色卡）
        character.setStatus(1);
        characterRepository.save(character);
        projectRepository.findById(character.getProjectId()).ifPresent(p -> {
            if (p.getStatus() == null || p.getStatus() < 1) {
                p.setStatus(1);
                projectRepository.save(p);
            }
        });
        return CharacterCardVO.of(card);
    }



    /**
     * 查询角色最新版本角色卡（归属校验）。
     *
     * @param characterId 角色主键
     * @return 最新角色卡（可能为空）
     */
    @Transactional(readOnly = true)
    public java.util.Optional<ActorCharacterCard> latestCard(Long characterId) {
        characterService.requireOwned(characterId);
        return cardRepository.findTopByCharacterIdOrderByVersionDesc(characterId);
    }

    /**
     * 查询角色卡版本历史（升序）。
     *
     * @param characterId 角色主键
     * @return 版本列表
     */
    @Transactional(readOnly = true)
    public List<ActorCharacterCard> versionHistory(Long characterId) {
        characterService.requireOwned(characterId);
        return cardRepository.findByCharacterIdOrderByVersionAsc(characterId);
    }

    /**
     * 手动编辑角色卡（保存为新版本，source=edited）。
     * <p>入参支持 personaJson 与 systemPrompt；personaJson 若为合法 JSON 则校验后保存，
     * systemPrompt 缺省时基于新 persona 重新渲染。</p>
     *
     * @param characterId 角色主键
     * @param body        编辑入参：{personaJson, systemPrompt}
     * @return 编辑后的角色卡 VO
     */
    @Transactional
    public CharacterCardVO editCard(Long characterId, Map<String, String> body) {
        ActorCharacter character = characterService.requireOwned(characterId);
        String persona = body == null ? null : body.get("personaJson");
        String systemPrompt = body == null ? null : body.get("systemPrompt");
        if (persona == null || persona.isBlank()) {
            throw new BizException(400, "personaJson 不能为空");
        }
        // 校验 JSON 合法性
        try {
            objectMapper.readTree(persona);
        } catch (Exception e) {
            throw new BizException(400, "角色卡 JSON 格式不正确，请检查后重试");
        }
        ActorCharacterCard latest = cardRepository.findTopByCharacterIdOrderByVersionDesc(characterId).orElse(null);
        ActorCharacterCard card = new ActorCharacterCard();
        card.setCharacterId(characterId);
        card.setVersion(latest == null ? 1 : latest.getVersion() + 1);
        card.setPersonaJson(persona);
        if (systemPrompt == null || systemPrompt.isBlank()) {
            String worldName = worldSettingRepository.findTopByProjectIdOrderByVersionDesc(character.getProjectId())
                    .map(ActorWorldSetting::getName).orElse(null);
            card.setSystemPrompt(buildSystemPrompt(character, persona, worldName));
        } else {
            card.setSystemPrompt(systemPrompt);
        }
        card.setSource("edited");
        cardRepository.save(card);
        return CharacterCardVO.of(card);
    }

    /**
     * 渲染对话系统 Prompt：persona 渲染 + 追加角色详细信息原文。
     * <p>2026-08-17 强化「只可多不可少」：即使 AI 对详细信息的结构化摘要有压缩，
     * 用户输入的原始详细信息也会以「角色详细信息原文（严格遵循，禁止删减/改动，优先级最高）」
     * 整段附加到 Prompt 末尾——保证对话模型拿到的角色信息 ≥ 用户输入，杜绝信息丢失与 OOC。</p>
     *
     * @param character   角色实体（取 detail 原文）
     * @param personaJson 结构化角色卡 JSON
     * @param worldName   世界观名称（可空）
     * @return 渲染后的系统 Prompt（含原文附段）
     */
    private String buildSystemPrompt(ActorCharacter character, String personaJson, String worldName) {
        String base = promptService.renderDialogSystemPrompt(
                currentUserProvider.currentUserId(), character.getProjectId(), worldName, personaJson);
        String detail = character.getDetail();
        if (detail == null || detail.isBlank()) {
            return base;
        }
        return base + "\n\n—— 角色详细信息原文（严格遵循，禁止删减/改动，优先级最高）——\n" + detail;
    }
    /**
     * 生成失败的用户友好提示（区分 AI 调用错误与业务错误）。
     *
     * @param e 捕获的异常
     * @return 中文提示
     */
    private String friendlyError(Exception e) {
        if (e instanceof BizException be) return be.getMessage();
        if (e instanceof com.holzyn.actor.ai.AiCallException ae) return ae.getMessage();
        return "模型输出无法解析，请稍后重试或手动编辑角色卡";
    }
}