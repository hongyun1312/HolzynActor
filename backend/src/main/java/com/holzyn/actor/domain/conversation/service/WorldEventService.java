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

package com.holzyn.actor.domain.conversation.service;

import com.holzyn.actor.common.BizException;
import com.holzyn.actor.common.JsonUtil;
import com.holzyn.actor.common.NpcRefs;
import com.holzyn.actor.domain.agent.event.EventRoutingService;
import com.holzyn.actor.domain.agent.event.EventSpec;
import com.holzyn.actor.domain.agent.event.WorldEventBus;
import com.holzyn.actor.domain.npc.profile.repository.ActorCharacterRepository;
import com.holzyn.actor.domain.npc.dialogue.entity.ActorConversation;
import com.holzyn.actor.domain.npc.dialogue.entity.ActorMessage;
import com.holzyn.actor.domain.npc.dialogue.repository.ActorConversationMemberRepository;
import com.holzyn.actor.domain.npc.dialogue.repository.ActorConversationRepository;
import com.holzyn.actor.domain.npc.dialogue.repository.ActorMessageRepository;
import com.holzyn.actor.domain.npc.state.config.NpcStateProperties;
import com.holzyn.actor.domain.npc.state.service.NpcStateService;
import com.holzyn.actor.domain.debug.service.AiCallContext;
import com.holzyn.actor.domain.world.repository.ActorWorldSettingRepository;
import com.holzyn.actor.domain.world.service.WorldClockService;
import com.holzyn.actor.domain.agent.runtime.AgentRuntime;
import com.holzyn.actor.ai.AiChatRequest;
import com.holzyn.actor.ai.AiChatResult;
import com.holzyn.actor.ai.spring.AiSpringChatGateway;
import com.holzyn.actor.domain.character.service.PromptService;
import com.holzyn.actor.domain.settings.service.PromptTemplateService;
import com.holzyn.actor.domain.usage.service.UsageLogService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 世界事件服务。
 * <p>职责：承载世界事件注入——手填文本或按 world_event 模板 AI 生成事件，
 * 落库为 type=event 消息（角色 system 角色）后返回，前端随之打开 SSE 触发在场角色回应。
 * 事件消息对所有在场角色可见，作为对话/行动的情境上下文。
 * <b>B1（世界事件总线）</b>：注入同时经 {@link WorldEventBus#publish} 发布 world 事件
 * 修断链2（L-06/）的落库侧：世界事件进 actor_event 时间线 + 事件流广播；
 * 调试事件流改由总线订阅者（DebugEventHub）同源消费，不再此处直推。</p>
 * <p>；经事件发布接口回调 agent 域）</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WorldEventService {

    /** 事件生成输出最大 token */
    private static final int EVENT_MAX_TOKENS = 1024;

    private final ConversationService conversationService;
    private final ActorConversationRepository conversationRepository;
    private final ActorMessageRepository messageRepository;
    private final ActorWorldSettingRepository worldSettingRepository;
    private final AiSpringChatGateway aiChatGateway;
    private final PromptService promptService;
    private final PromptTemplateService promptTemplateService;
    private final UsageLogService usageLogService;
    private final AgentRuntime agentRuntime;
    private final WorldEventBus worldEventBus;
    private final ObjectMapper objectMapper;

    /** 事件路由（字段注入保持构造器不变）：手动注入事件按会话所在地路由到其他在场会话 */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private EventRoutingService eventRoutingService;

    /** 重排接线：事件打断后为会话内高重要度特殊 NPC 重排当日计划（字段注入避免改构造器） */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.holzyn.actor.domain.agent.thinking.PlanningService planningService;

    /** 会话成员仓库（重排接线遍历会话成员用） */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private ActorConversationMemberRepository memberRepository;

    /**
     * 世界氛围片段读取服务（story-plan 工作流 _06 字段注入——保持构造器
     * 签名不变；直接 new 时为空 → 事件润色不注入世界氛围，零破坏）
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.holzyn.actor.domain.world.service.StoryAtmosphereService storyAtmosphereService;

    /** 特殊角色仓库（重排接线按重要度门控用） */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private ActorCharacterRepository characterRepository;

    /** 世界时钟（手动注入重排取当前游戏时刻） */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private WorldClockService worldClockService;

    /** 无玩家群聊服务（字段注入避免改构造器）：事件注入后触发事件回应轮（异步） */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.holzyn.actor.domain.agent.roleplay.AutonomousChatService autonomousChatService;

    /** 角色扮演组件（字段注入避免改构造器）：advance 占位接线为真正的无玩家群聊轮 */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.holzyn.actor.domain.agent.roleplay.RoleplayService roleplayService;

    /** 状态；冷却/人群门控/失败回退在服务内） */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private NpcStateService npcStateService;

    /** 状态 */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private NpcStateProperties npcStateProperties;

    /** 世界事件默认重要度（手动/外部注入无显式重要度，按重大事件口径 4；引擎跨日事件亦为 4） */
    private static final int EVENT_DEFAULT_IMPORTANCE = 4;

    /**
     * 统一 AI 上下文注入器（世界事件润色属「世界视角」生成——非角色视角，无角色核心卡；
     * 仅补 段2D 当前世界动态 = worldDynamicsLine（世界历时刻/季节/天气），润色贴合当下世界；
     * 字段注入 + @Lazy 破环；null=未装配跳过注入，不影响原调用）。
     */
    @org.springframework.context.annotation.Lazy
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.holzyn.actor.domain.agent.context.AiContextInjector aiContextInjector;

    /**
     * 注入世界事件：手填文本或 AI 生成 → 落 type=event 消息 → 返回供前端触发角色回应。
     *
     * @param conversationId 会话主键
     *
     * @param userId 归属用户 ID
     *
     * @param body 入参：{text? 手填事件文本, generate? 是否 AI 生成}
     *
     * @return Map：eventMessageId / mode / reaction（true 表示前端应打开 SSE 触发回应）
     */
    @Transactional
    public Map<String, Object> inject(Long conversationId, Long userId, Map<String, Object> body) {
        // 归属校验用显式 userId：本方法会被世界模拟定时线程调用（无 SecurityContext），
        // 默认 requireOwned(id) 会回退演示用户导致真实用户会话误判无权访问
        ActorConversation conv = conversationService.requireOwned(conversationId, userId);
        String text = body == null ? null : str(body.get("text"));
        boolean generate = body != null && Boolean.TRUE.equals(body.get("generate"));

        String eventContent;
        if (text != null && !text.isBlank()) {
            eventContent = text.trim();
        } else if (generate) {
            eventContent = generateByAi(userId, conv);
        } else {
            throw new BizException(400, "请提供事件文本或开启 AI 生成（generate=true）");
        }

        // 事件消息：role=system、type=event、状态 done（非对话消息，不参与流式占位）
        ActorMessage eventMsg = new ActorMessage();
        eventMsg.setConversationId(conversationId);
        eventMsg.setRole("system");
        eventMsg.setType("event");
        eventMsg.setContent(eventContent);
        eventMsg.setStatus("done");
        messageRepository.save(eventMsg);

        conv.setLastMessageAt(LocalDateTime.now());
        conversationRepository.save(conv);

        // B1（世界事件总线）：注入事件同时发布 world 事件（kind=world、visibility=global，
        // 幂等键 inject:{conversationId}:{messageId} 防重复发布）——事件进 actor_event 时间线 +
        // 事件流广播（SSE/调试订阅者同源）；发布开关关闭时 publish 返回 null（回到 B1 前：
        // 仅 type=event 消息注入 + 对话上下文，DebugEventHub 不再直推）。
        publishWorldEvent(userId, conv, eventMsg.getId(), eventContent, generate);

        // 世界事件后自动触发在场重要角色的行动（event 触发源，异步； 统一经 AgentRuntime）
        agentRuntime.triggerForEventAsync(conversationId, userId);

        // 重排接线：世界事件打断 → 会话内高重要度特殊 NPC 当日计划自动重排（预算门控，失败静默）
        replanForEvent(conv.getProjectId(), userId, conversationId, null, eventContent);

        // 事件按地点路由：同一事件也注入「同地点其他在场会话」（修 ——城西事件只进城西在场角色的会话）
        routeToInSceneConversations(userId, conv, eventContent);

        // 状态
        maybeEmotionEvalFromEvent(conv, eventContent, EVENT_DEFAULT_IMPORTANCE);

        // ：世界事件后的「事件回应轮」——社会对话层修复 B 层 起由
        // AutonomousChatEventSubscriber 订阅事件总线统一接线（importance≥阈值 + 带地点 → 所在地点
        // NPC 内群一轮无玩家群聊；日上限护栏在订阅者内）——此处不再直挂，防同事件双触发。

        return Map.of("eventMessageId", eventMsg.getId(), "mode", conv.getMode(),
                "reaction", true, "content", eventContent);
    }

    /**
     * 手动注入的世界事件按「会话所在地」路由到同地点的其他在场会话。
     * <p>仅当源会话带地点（location_id 或地点名非空）时参与路由；匹配到「其他」会话时
     * 复用 {@link #injectExternal} 落事件消息 + 触发该会话在场角色回应（不重复发布总线事件，
     * 幂等键已归源会话）。无匹配/异常仅记日志不阻断主流程。</p>
     *
     * @param userId 归属用户 ID
     *
     * @param conv 源会话（事件注入目标）
     *
     * @param eventContent 事件文本
     */
    private void routeToInSceneConversations(Long userId, ActorConversation conv, String eventContent) {
        if (eventRoutingService == null || conv == null || conv.getProjectId() == null) {
            return;
        }
        boolean hasLocation = conv.getLocationId() != null
                || (conv.getLocation() != null && !conv.getLocation().isBlank());
        if (!hasLocation) {
            return; // 远程通讯/无地点事件不做地点路由
        }
        try {
            ActorConversation target = eventRoutingService.findTarget(conv.getProjectId(), userId,
                    conv.getLocationId(), conv.getLocation(), null, conv.getId());
            if (target != null && !target.getId().equals(conv.getId())) {
                injectExternal(target.getId(), userId, eventContent);
                // 同地点在场角色的计划同样被打断 → 重排
                replanForEvent(conv.getProjectId(), userId, target.getId(), null, eventContent);
                log.info("[世界事件] 按地点路由注入其他在场会话：conv={} → conv={} loc={}",
                        conv.getId(), target.getId(), conv.getLocation());
            }
        } catch (Exception e) {
            log.warn("[世界事件] 按地点路由到其他在场会话失败（主流程不受影响）: conv={} : {}",
                    conv.getId(), e.getMessage());
        }
    }

    /**
     * 重排接线：事件打断后为会话内「高重要度（≥3）特殊 NPC」自动重排当日计划。
     * <p>对齐 AI 成本门槛（importance≥3 走 AI 行动决策）：低重要度角色不浪费重排 AI 预算；
     * 委托 {@code PlanningService.replanOnDisruption}（内部预算门控 + minimal 机制化 + 失败静默回退）；
     * 单角色失败隔离。gameHour 可空=读世界时钟当前游戏时刻。</p>
     *
     * @param projectId 项目 ID
     *
     * @param userId 归属用户 ID（未使用，保留签名一致）
     *
     * @param conversationId 会话 ID
     *
     * @param gameHour 当前游戏小时（可空=世界时钟当前值）
     *
     * @param eventText 打断事件摘要（可空）
     */
    public void replanForEvent(Long projectId, Long userId, Long conversationId, Long gameHour, String eventText) {
        if (planningService == null || memberRepository == null || characterRepository == null
                || projectId == null || conversationId == null) {
            return;
        }
        Long gh = gameHour;
        if (gh == null && worldClockService != null) {
            gh = worldClockService.currentGameHour(projectId);
        }
        List<com.holzyn.actor.domain.npc.dialogue.entity.ActorConversationMember> members =
                memberRepository.findByConversationId(conversationId);
        for (com.holzyn.actor.domain.npc.dialogue.entity.ActorConversationMember m : members) {
            try {
                if (m == null || m.getCharacterId() == null
                        || NpcRefs.isCommonType(m.getMemberType())) {
                    continue; // 普通型 NPC 无目标表，不参与重排
                }
                var ch = characterRepository.findById(m.getCharacterId()).orElse(null);
                if (ch == null || Integer.valueOf(1).equals(ch.getDeleted())
                        || ch.getImportance() == null || ch.getImportance() < 3) {
                    continue; // 高重要度（≥3）才投入重排 AI 预算
                }
                planningService.replanOnDisruption(projectId, NpcRefs.of(ch.getId()), gh, eventText);
            } catch (Exception e) {
                log.warn("[世界事件] 角色计划重排失败（隔离继续）: member={} : {}",
                        m == null ? null : m.getId(), e.getMessage());
            }
        }
    }

    /**
     * 注入<b>外部</b>世界事件文本到会话（B3 世界推进引擎用）：落 type=event 消息 + 刷新会话
     * lastMessageAt + 异步触发在场角色回应。
     * <p>与 {@link #inject} 的区别：<b>不</b>向事件总线重复发布——跨日世界事件由 WorldEngine 先行
     * 这里仅把事件文本放进会话上下文（AI 可见【世界事件】段）并触发「在场角色回应」链环
     * ，避免同一条事件双写 actor_event（source=manual 与 source=engine 各一条）。</p>
     *
     * @param conversationId 会话主键
     *
     * @param userId 归属用户 ID
     *
     * @param text 事件文本（非空）
     */
    @Transactional
    public Map<String, Object> injectExternal(Long conversationId, Long userId, String text) {
        ActorConversation conv = conversationService.requireOwned(conversationId, userId);
        if (text == null || text.isBlank()) {
            throw new BizException(400, "事件文本不能为空");
        }
        ActorMessage eventMsg = new ActorMessage();
        eventMsg.setConversationId(conversationId);
        eventMsg.setRole("system");
        eventMsg.setType("event");
        eventMsg.setContent(text.trim());
        eventMsg.setStatus("done");
        messageRepository.save(eventMsg);

        conv.setLastMessageAt(LocalDateTime.now());
        conversationRepository.save(conv);

        // 世界事件后自动触发在场重要角色的行动（event 触发源，异步； 统一经 AgentRuntime；
        // -3 注意：lastMessageAt 刷新在活跃窗口废除后无「自续活跃」副作用，仅作会话最近活动标记）
        agentRuntime.triggerForEventAsync(conversationId, userId);
        // 状态 世界事件后 → 会话成员 AI 情绪评估写回（引擎事件重要度 4）
        maybeEmotionEvalFromEvent(conv, text.trim(), EVENT_DEFAULT_IMPORTANCE);
        return Map.of("eventMessageId", eventMsg.getId(), "reaction", true);
    }

    /**
     * 状态模块 事件情绪评估挂钩：世界事件到达会话后，对会话成员（统一 npc:{id}）逐个触发
     * {@link NpcStateService#evaluateEmotion}（AI 评估写回）。
     * <p>重要度门控：事件重要度 ≥ {@code emotion.eval-event-importance}（默认 3）才评估——引擎跨日
     * 事件重要度恒 4、手动注入按重大事件口径 4，均自然满足；冷却（每 NPC 每游戏小时 1 次）与
     * 人群场景人数门控在服务内；单成员失败隔离。</p>
     */
    private void maybeEmotionEvalFromEvent(ActorConversation conv, String eventContent, int importance) {
        if (npcStateService == null || conv == null || conv.getProjectId() == null
                || eventContent == null || eventContent.isBlank()) {
            return;
        }
        if (npcStateProperties != null && importance < npcStateProperties.getEmotion().getEvalEventImportance()) {
            return; // 低重要度事件不投入 AI 评估
        }
        try {
            if (memberRepository != null) {
                List<com.holzyn.actor.domain.npc.dialogue.entity.ActorConversationMember> members =
                        memberRepository.findByConversationId(conv.getId());
                for (com.holzyn.actor.domain.npc.dialogue.entity.ActorConversationMember m : members) {
                    try {
                        if (m == null || m.getCharacterId() == null) {
                            continue;
                        }
                        // ：npc:{id} 不再编码类型，此处按意图 B 处理（成员类型值经 NpcRefs 归一后传给状态模块）
                        String memberType = NpcRefs.normalizeType(m.getMemberType());
                        npcStateService.evaluateEmotion(conv.getProjectId(),
                                NpcRefs.of(m.getCharacterId()),
                                memberType, "世界事件", eventContent, null);
                    } catch (Exception e) {
                        log.warn("[世界事件] 成员情绪评估失败（隔离继续）: member={} : {}",
                                m == null ? null : m.getId(), e.getMessage());
                    }
                }
            }
        } catch (Exception e) {
            log.warn("[世界事件] 情绪评估挂钩失败（静默）: conv={} : {}", conv == null ? null : conv.getId(), e.getMessage());
        }
    }

    /**
     * 发布世界事件到事件总线（B1）。
     * <p>kind=world、source=ai（AI 生成）/manual（手填）、visibility=global（全项目知晓）、
     * title=事件首行、content=完整事件文本、source_key=inject:{conversationId}:{messageId}。</p>
     * <p>发布失败仅告警不阻断注入（对话上下文消息已落库，主链路不受影响）。</p>
     *
     * @param userId 归属用户 ID
     *
     * @param conv 会话实体
     *
     * @param messageId 事件消息 ID（幂等键组成部分）
     *
     * @param eventContent 事件文本
     *
     * @param generate 是否 AI 生成（决定 source）
     */
    private void publishWorldEvent(Long userId, ActorConversation conv, Long messageId,
                                   String eventContent, boolean generate) {
        try {
            if (conv == null || conv.getProjectId() == null || eventContent == null || eventContent.isBlank()) {
                return;
            }
            String[] lines = eventContent.trim().split("\\n");
            String title = lines.length > 0 && !lines[0].isBlank() ? lines[0].trim() : "世界事件";
            worldEventBus.publish(userId, conv.getProjectId(), EventSpec.builder()
                    .kind("world")
                    .source(generate ? "ai" : "manual")
                    .visibility("global")
                    .title(title.length() > 200 ? title.substring(0, 200) : title)
                    .content(eventContent.trim())
                    // ：事件携带会话所在地（location_id/locationName， 归一），供按地点路由/感知过滤
                    .locationId(conv.getLocationId())
                    .locationName(conv.getLocation() == null || conv.getLocation().isBlank()
                            ? null : conv.getLocation().trim())
                    .sourceKey("inject:" + conv.getId() + ":" + messageId)
                    .build());
        } catch (Exception e) {
            log.warn("[世界事件] 事件总线发布失败: conv={} : {}", conv == null ? null : conv.getId(), e.getMessage());
        }
    }

    /**
     * 「无玩家轮次」自主推进（接线：原 占位——「自主推进已就绪，请打开 SSE」——
     * 现为真正的无玩家群聊轮）：无需玩家消息，对该群聊会话直接推进一轮 NPC 自发对话
     * （headless：消息逐条广播到会话实时订阅 Hub，玩家打开会话页即可实时围观/插话）。
     *
     * @param conversationId 会话主键
     *
     * @param userId 归属用户 ID
     */
    @Transactional
    public Map<String, Object> advance(Long conversationId, Long userId) {
        ActorConversation conv = conversationService.requireOwned(conversationId, userId);
        if (!"group".equals(conv.getMode())) {
            throw new BizException(400, "「无玩家轮次」仅支持群聊会话");
        }
        if (roleplayService == null) {
            return Map.of("mode", "group", "autonomous", true, "replies", 0,
                    "message", "无玩家群聊组件未就绪");
        }
        int replies = roleplayService.runAutonomousGroupRound(conv, userId, new java.util.concurrent.atomic.AtomicBoolean(true));
        return Map.of("mode", "group", "autonomous", true, "replies", replies,
                "message", "无玩家群聊轮已推进");
    }

    /**
     * AI 生成世界事件（world_event 模板 + json_object 输出）。
     *
     * @param userId 归属用户 ID
     *
     * @param conv 会话实体
     *
     * @return 事件文本（标题 + 内容）
     */
    private String generateByAi(Long userId, ActorConversation conv) {
        String worldSetting = worldSettingRepository.findTopByProjectIdOrderByVersionDesc(conv.getProjectId())
                .map(s -> s.getFreeText()).orElse("");
        String context = "会话模式：" + ("group".equals(conv.getMode()) ? "群聊" : "单聊")
                + "；会话ID：" + conv.getId();
        String prompt = promptService.buildWorldEventPrompt(userId, conv.getProjectId(), worldSetting, context);
        AiChatRequest req = new AiChatRequest(null, List.of(
                new AiChatRequest.ChatMessage("system", "你只输出严格的 JSON，不输出任何其他文字。"),
                new AiChatRequest.ChatMessage("user", prompt)), 0.8, EVENT_MAX_TOKENS, true);
        long startMs = System.currentTimeMillis();
        // AI 调用埋点（调试前端）：scene=world_event
        AiChatResult result = AiCallContext.run("world_event", null, () ->
                aiChatGateway.chatCompletion(userId, conv.getProjectId(), null, req));
        usageLogService.record(userId, conv.getProjectId(), null, result.providerId(), result.model(), "world_event",
                result.promptTokens(), result.completionTokens(),
                result.cacheHitTokens(), result.cacheMissTokens(),
                (int) (System.currentTimeMillis() - startMs));

        String json = JsonUtil.extractJson(result.content());
        try {
            JsonNode node = objectMapper.readTree(json);
            String title = node.path("title").asText("世界事件");
            String content = node.path("content").asText("");
            if (content.isBlank()) {
                throw new BizException(400, "AI 生成的世界事件内容为空");
            }
            return title + "\n" + content;
        } catch (BizException be) {
            throw be;
        } catch (Exception e) {
            throw new BizException(400, "世界事件生成失败：AI 输出无法解析，请重试或手填事件");
        }
    }

    /**
     * AI 润色跨日世界事件草稿（B3 T-4 双通道第二通道，world_event_polish 模板 + json_object 输出）。
     * <p>以「机制生成的程序化草稿」为基础润色成文（保留机制设定的事件主旨与时序，补全经过与影响）；
     * 润色失败抛 {@link BizException}，由 WorldEngine 回退机制草稿（AI 润色是可选增强，不阻断世界推进）。</p>
     * <p>埋点：scene=world_event_polish（AI Trace + 用量图可观测，W-3）；系统提示词按「生成 Prompt
     * 全部落库」约定从模板库读取（world_event_polish 内置行， B3 新增）。</p>
     *
     * @param userId 归属用户 ID
     *
     * @param projectId 项目 ID
     *
     * @param draft 机制生成的事件草稿（必填）
     *
     * @param context 当前情境（引擎推进游戏时刻/第几天等）
     *
     * @return 润色后的事件文本（标题 + 内容，两行）
     */
    public String polishEventByAi(Long userId, Long projectId, String draft, String context) {
        String worldSetting = worldSettingRepository.findTopByProjectIdOrderByVersionDesc(projectId)
                .map(s -> s.getFreeText()).orElse("");
        // story-plan（_06）：世界氛围片段注入（未装配 → 空串，模板残留占位符自动抹除）
        String atmosphere = storyAtmosphereService == null ? "" : storyAtmosphereService.atmosphereOf(projectId);
        String prompt = promptService.buildWorldEventPolishPrompt(userId, projectId, worldSetting, draft, context, atmosphere);
        // 批6 收口（world_event_polish 属「世界视角」生成）：前置 段1A【世界观】+ 段2D【当前世界动态】
        // （世界历时刻/季节/天气），润色贴合世界设定与当下世界（未装配/失败零阻断）
        String contextPrefix = aiContextInjector == null ? "" : aiContextInjector.worldContextPrefix(projectId, null);
        String finalPrompt = contextPrefix.isBlank() ? prompt : contextPrefix + "\n\n" + prompt;
        AiChatRequest req = new AiChatRequest(null, List.of(
                new AiChatRequest.ChatMessage("system", promptTemplateService.systemMessage(userId, projectId,
                        PromptTemplateService.CODE_WORLD_EVENT_POLISH, null)),
                new AiChatRequest.ChatMessage("user", finalPrompt)), 0.8, EVENT_MAX_TOKENS, true);
        long startMs = System.currentTimeMillis();
        // AI 调用埋点（调试前端）：scene=world_event_polish
        AiChatResult result = AiCallContext.run("world_event_polish", null, () ->
                aiChatGateway.chatCompletion(userId, projectId, null, req));
        usageLogService.record(userId, projectId, null, result.providerId(), result.model(), "world_event_polish",
                result.promptTokens(), result.completionTokens(),
                result.cacheHitTokens(), result.cacheMissTokens(),
                (int) (System.currentTimeMillis() - startMs));

        String json = JsonUtil.extractJson(result.content());
        try {
            JsonNode node = objectMapper.readTree(json);
            String title = node.path("title").asText("世界事件");
            String content = node.path("content").asText("");
            if (content.isBlank()) {
                throw new BizException(400, "AI 润色的世界事件内容为空");
            }
            return title + "\n" + content;
        } catch (BizException be) {
            throw be;
        } catch (Exception e) {
            throw new BizException(400, "世界事件润色失败：AI 输出无法解析，请回退机制草稿");
        }
    }

    /**
     * 取值辅助：Object 转字符串。
     *
     * @param v 原始对象
     *
     * @return 字符串（null 时返回 null）
     */
    private String str(Object v) {
        return v == null ? null : String.valueOf(v);
    }
}