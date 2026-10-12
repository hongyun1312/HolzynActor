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

package com.holzyn.actor.domain.sandbox.controller;

import com.holzyn.actor.common.BizException;
import com.holzyn.actor.common.NpcRefs;
import com.holzyn.actor.common.R;
import com.holzyn.actor.domain.account.CurrentUserProvider;
import com.holzyn.actor.domain.npc.goal.entity.ActorActionPlan;
import com.holzyn.actor.domain.agent.roleplay.RoleplayService;
import com.holzyn.actor.domain.agent.runtime.AgentRuntime;
import com.holzyn.actor.domain.npc.profile.entity.ActorCharacter;
import com.holzyn.actor.domain.npc.profile.repository.ActorCharacterRepository;
import com.holzyn.actor.domain.npc.profile.service.NpcTypeResolver;
import com.holzyn.actor.domain.conversation.dto.ConversationDTO;
import com.holzyn.actor.domain.npc.dialogue.entity.ActorConversation;
import com.holzyn.actor.domain.npc.dialogue.entity.ActorConversationMember;
import com.holzyn.actor.domain.npc.dialogue.repository.ActorConversationMemberRepository;
import com.holzyn.actor.domain.npc.dialogue.repository.ActorConversationRepository;
import com.holzyn.actor.domain.conversation.service.ConversationService;
import com.holzyn.actor.domain.conversation.vo.ConversationVO;
import com.holzyn.actor.domain.project.repository.ActorProjectRepository;
import com.holzyn.actor.domain.sandbox.entity.ActorSandboxScene;
import com.holzyn.actor.domain.sandbox.repository.ActorSandboxSceneRepository;
import com.holzyn.actor.domain.sandbox.service.SceneAssetService;
import com.holzyn.actor.domain.sandbox.service.ScenePlacementService;
import com.holzyn.actor.domain.sandbox.service.ScenePresetService;
import com.holzyn.actor.domain.sandbox.service.WorldSandboxSseHub;
import com.holzyn.actor.domain.sandbox.service.WorldSandboxStateService;
import com.holzyn.actor.domain.sandbox.vo.SandboxOverviewVO;
import com.holzyn.actor.domain.sandbox.vo.SandboxSceneVO;
import com.holzyn.actor.domain.sandbox.vo.SandboxStateVO;
import com.holzyn.actor.domain.sandbox.vo.SceneAssetImportVO;
import com.holzyn.actor.domain.sandbox.vo.SceneAssetVO;
import com.holzyn.actor.domain.sandbox.vo.ScenePackVO;
import com.holzyn.actor.domain.sandbox.vo.ScenePlacementVO;
import com.holzyn.actor.domain.sandbox.vo.ScenePresetApplyVO;
import com.holzyn.actor.domain.sandbox.vo.WeatherVO;
import com.holzyn.actor.domain.world.dto.WorldClockDTO;
import com.holzyn.actor.domain.world.service.TimelineService;
import com.holzyn.actor.domain.world.service.WeatherSwitchService;
import com.holzyn.actor.domain.world.service.WorldClockService;
import com.holzyn.actor.domain.agent.runtime.WorldEngine;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 世界沙盘控制器（世界沙盘 S1→S2，domain/sandbox/controller）。
 * <p>职责：提供沙盘页读接口（总览/场景/状态）＋ <b>S2 实时通道与干预端点</b>：</p>
 * <ul>
 * （订阅即推 connected + sandbox:init 全量快照，后续 10 事件增量推送：npc:moved/arrived/state/
 * thought/talk/join/leave + scene:event + clock）；</li>
 * <li><b>干预</b>（统一 /sandbox/intervene/*，全部委托既有能力，不含新业务逻辑）：
 * 插话（find-or-create 单聊 + {@link RoleplayService#sendMessage}）、
 * 触发行动（仅特殊角色，{@link AgentRuntime#triggerAction}）、
 * 手动事件（{@link TimelineService#createEvent}）、时间控制（{@link WorldClockService#updateClock} /
 * </ul>
 * S3 资产摆放：GET|POST /api/projects/{id}/sandbox/scenes/{sceneId}/placements、
 */
@Slf4j
@RestController
@RequestMapping("/api/projects/{projectId}/sandbox")
@RequiredArgsConstructor
public class SandboxController {

    private final WorldSandboxStateService sandboxStateService;
    private final ActorSandboxSceneRepository sceneRepository;
    private final ActorProjectRepository projectRepository;
    private final ActorCharacterRepository characterRepository;
    private final ActorCharacterRepository ordinaryNpcRepository;

    /** NPC 类型解析器 */
    private final NpcTypeResolver npcTypeResolver;

    /** 世界沙盘 SSE 广播中心（S2 实时通道） */
    private final WorldSandboxSseHub worldSandboxSseHub;

    /** 会话服务（插话：find-or-create 单聊） */
    private final ConversationService conversationService;
    private final ActorConversationRepository conversationRepository;
    private final ActorConversationMemberRepository conversationMemberRepository;

    /** 角色扮演组件（插话：消息发送 + 回复生成） */
    private final RoleplayService roleplayService;

    /** 复合行动触发（触发行动：仅特殊角色） */
    private final AgentRuntime agentRuntime;

    /** 时间线服务（手动事件） */
    private final TimelineService timelineService;

    /** 世界时钟服务 + 世界推进引擎（时间控制） */
    private final WorldClockService worldClockService;
    private final WorldEngine worldEngine;

    /** 当前用户解析器（归属校验） */
    private final CurrentUserProvider currentUserProvider;

    /** 资产摆放服务（S3：actor_scene_placement CRUD + 资产信息解析） */
    private final ScenePlacementService scenePlacementService;

    /** 资产数据包服务（S3 补全：manifest → actor_sandbox_asset 导入登记 + 数据包/资产清单） */
    private final SceneAssetService sceneAssetService;

    /** 场景预设服务（S3 补全：内置预设 JSON → 校验 → 写 layout_json + 重建摆放表） */
    private final ScenePresetService scenePresetService;

    /** 天气切换服务（S4：晴/雨/雪/阴 → 就地更新世界帧天气 + 环境刺激 + SSE weather 推送） */
    private final WeatherSwitchService weatherSwitchService;

    /** Jackson（S3 补全：场景列表携带解析后的 layout_json） */
    private final tools.jackson.databind.ObjectMapper objectMapper;

    /**
     * 沙盘总览：场景（含主场景）+ 全部 NPC 沙盘状态（S2 起委托
     * {@link WorldSandboxStateService#overview}——读取前惰性同步 + 名称索引统一在服务层完成）。
     *
     * @param projectId 项目 ID
     */
    @GetMapping("/overview")
    public R<SandboxOverviewVO> overview(@PathVariable Long projectId) {
        requireProject(projectId);
        return R.ok(sandboxStateService.overview(projectId));
    }

    /**
     * 沙盘场景列表（只读；S3 补全：携带解析后的 layout_json 供前端渲染河路/地形）。
     *
     * @param projectId 项目 ID
     *
     * @return 场景 VO 列表
     */
    @GetMapping("/scenes")
    public R<List<SandboxSceneVO>> scenes(@PathVariable Long projectId) {
        requireProject(projectId);
        return R.ok(sceneRepository.findByProjectIdOrderByCreatedAtAsc(projectId)
                .stream().map(s -> SandboxSceneVO.of(s, parseLayout(s.getLayoutJson()))).toList());
    }

    /**
     * NPC 沙盘状态列表（只读，读取前惰性同步状态行）。
     *
     * @param projectId 项目 ID
     *
     * @return 状态 VO 列表
     */
    @GetMapping("/states")
    public R<List<SandboxStateVO>> states(@PathVariable Long projectId) {
        requireProject(projectId);
        return R.ok(sandboxStateService.overview(projectId).getStates());
    }

    // ==================== S3：资产摆放（actor_scene_placement CRUD） ====================

    /**
     * 资产摆放-按场景列表（sort_order 升序 + id 升序；校验场景存在且属于项目）。
     *
     * @param projectId 项目 ID
     *
     * @param sceneId 场景 ID
     *
     * @return 摆放 VO 列表（附资产登记表解析：资产名/分类/GLB 路径）
     */
    @GetMapping("/scenes/{sceneId}/placements")
    public R<List<ScenePlacementVO>> placements(@PathVariable Long projectId, @PathVariable Long sceneId) {
        requireProject(projectId);
        return R.ok(scenePlacementService.list(projectId, sceneId));
    }

    /**
     * 资产摆放-创建（asset_id 必填；位置/缩放/朝向可缺省，默认地面高度/单位缩放/零朝向）。
     *
     * @param projectId 项目 ID
     *
     * @param sceneId 场景 ID
     *
     * @return 新建摆放 VO
     */
    @PostMapping("/scenes/{sceneId}/placements")
    public R<ScenePlacementVO> createPlacement(@PathVariable Long projectId, @PathVariable Long sceneId,
                                               @RequestBody(required = false) Map<String, Object> body) {
        requireProject(projectId);
        return R.ok(scenePlacementService.create(projectId, sceneId, body));
    }

    /**
     * 资产摆放-更新（仅覆盖非空字段）。
     *
     * @param projectId 项目 ID
     *
     * @param placementId 摆放记录 ID
     *
     * @param body 入参同创建
     *
     * @return 更新后摆放 VO
     */
    @PutMapping("/placements/{placementId}")
    public R<ScenePlacementVO> updatePlacement(@PathVariable Long projectId, @PathVariable Long placementId,
                                               @RequestBody(required = false) Map<String, Object> body) {
        requireProject(projectId);
        return R.ok(scenePlacementService.update(projectId, placementId, body));
    }

    /**
     * 资产摆放-删除。
     *
     * @param projectId 项目 ID
     *
     * @param placementId 摆放记录 ID
     */
    @DeleteMapping("/placements/{placementId}")
    public R<Map<String, Object>> deletePlacement(@PathVariable Long projectId, @PathVariable Long placementId) {
        requireProject(projectId);
        scenePlacementService.delete(projectId, placementId);
        return R.ok(Map.of("message", "摆放记录已删除"));
    }

    // ==================== S3 补全：资产数据包 + 场景预设 ====================

    /**
     * 资产数据包列表（扫描 uploads/scene-assets/ 下全部 manifest）。
     *
     * @param projectId 项目 ID（仅归属校验）
     *
     * @return 数据包摘要列表
     */
    @GetMapping("/packs")
    public R<List<ScenePackVO>> packs(@PathVariable Long projectId) {
        requireProject(projectId);
        return R.ok(sceneAssetService.listPacks());
    }

    /**
     * 资产清单（含全局内置包 project_id=0 与项目级；可按 packId 过滤）。
     *
     * @param projectId 项目 ID
     *
     * @param packId 可选：数据包 ID 过滤
     *
     * @return 资产 VO 列表
     */
    @GetMapping("/assets")
    public R<List<SceneAssetVO>> assets(@PathVariable Long projectId,
                                        @org.springframework.web.bind.annotation.RequestParam(required = false) String packId) {
        requireProject(projectId);
        return R.ok(sceneAssetService.listAssets(projectId, packId));
    }

    /**
     * 导入资产数据包（manifest → actor_sandbox_asset 登记，project_id=0 全局；幂等可重跑）。
     *
     * @param projectId 项目 ID（仅归属校验）
     *
     * @param body 入参：{packId 必填}
     *
     * @return 导入统计 VO
     */
    @PostMapping("/assets/import")
    public R<SceneAssetImportVO> importAssets(@PathVariable Long projectId,
                                              @RequestBody(required = false) Map<String, Object> body) {
        requireProject(projectId);
        String packId = str(body, "packId");
        if (packId == null) {
            throw new BizException(400, "packId 不能为空（数据包目录名，如 sandbox-xianxia-v1）");
        }
        return R.ok(sceneAssetService.importPack(packId));
    }

    /**
     * 内置场景预设列表（classpath:scene-presets/*.json）。
     *
     * @param projectId 项目 ID（仅归属校验）
     */
    @GetMapping("/presets")
    public R<List<Map<String, Object>>> presets(@PathVariable Long projectId) {
        requireProject(projectId);
        return R.ok(scenePresetService.listPresets());
    }

    /**
     * 应用内置场景预设到指定场景（校验 → 写 layout_json + 重建摆放表；幂等可重跑）。
     *
     * @param projectId 项目 ID
     *
     * @param sceneId 场景 ID
     *
     * @param body 入参：{presetName 必填}
     *
     * @return 应用结果 VO
     */
    @PostMapping("/scenes/{sceneId}/preset")
    public R<ScenePresetApplyVO> applyPreset(@PathVariable Long projectId, @PathVariable Long sceneId,
                                             @RequestBody(required = false) Map<String, Object> body) {
        requireProject(projectId);
        String presetName = str(body, "presetName");
        if (presetName == null) {
            throw new BizException(400, "presetName 不能为空（如 qinghe-village-v1）");
        }
        return R.ok(scenePresetService.applyPreset(projectId, sceneId, presetName));
    }

    // ==================== S2：实时通道 ====================

    /**
     * 世界沙盘 SSE 实时订阅（订阅型长连接，不包 R）。
     * <p>订阅即推送 {@code connected} 确认 + {@code sandbox:init} 全量快照（场景 + 全部 NPC 状态 +
     * 时钟），后续 10 事件增量推送（npc:moved/arrived/state/thought/talk/join/leave +
     * scene:event + clock）。心跳 15s，断线由前端 createSubscription 自动重连。</p>
     *
     * @param projectId 项目 ID
     *
     * @return SseEmitter 流
     */
    @GetMapping(value = "/stream", produces = "text/event-stream")
    public SseEmitter stream(@PathVariable Long projectId) {
        requireProject(projectId);
        return worldSandboxSseHub.subscribe(projectId);
    }

    // ==================== S2：干预（统一委托既有能力） ====================

    /**
     * 干预-插话：向指定 NPC 说一句话（自动查找该 NPC 已有的单聊会话，无则创建），
     * 消息落库后由 SSE 通道流式生成回复（角色扮演组件既有链路）。
     *
     * @param projectId 项目 ID
     *
     * @param body 入参：{npcRef: npc:{id}, content 必填}
     */
    @PostMapping("/intervene/talk")
    public R<Map<String, Object>> interveneTalk(@PathVariable Long projectId,
                                                @RequestBody(required = false) Map<String, Object> body) {
        requireProject(projectId);
        String npcRef = str(body, "npcRef");
        String content = str(body, "content");
        if (npcRef == null || npcRef.isBlank()) {
            throw new BizException(400, "npcRef 不能为空（npc:{id}）");
        }
        if (content == null || content.isBlank()) {
            throw new BizException(400, "插话内容不能为空");
        }
        requireNpc(projectId, npcRef);
        ConversationVO conv = ensureSingleConversation(projectId, npcRef);
        Map<String, Object> sent = roleplayService.sendMessage(conv.id(), content.trim(), NpcRefs.idOf(npcRef));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("conversationId", conv.id());
        out.put("mode", conv.mode());
        out.put("message", "插话已写入，回复经沙盘流式推送（npc:talk）");
        out.putAll(sent);
        return R.ok(out);
    }

    /**
     * 干预-触发行动：手动触发特殊角色一轮思考决策 + 行动（复合行动链路，AI 不可用时程序化兜底）。
     * 普通型（common）不支持手动触发（返回 400）。
     *
     * @param projectId 项目 ID
     */
    @PostMapping("/intervene/action")
    public R<Map<String, Object>> interveneAction(@PathVariable Long projectId,
                                                  @RequestBody(required = false) Map<String, Object> body) {
        requireProject(projectId);
        String npcRef = str(body, "npcRef");
        // ：npc:{id} 不再编码类型，此处按意图 B 处理（仅特殊型支持手动触发行动）
        if (npcRef == null || !npcTypeResolver.isSpecial(projectId, npcRef)) {
            throw new BizException(400, "仅特殊角色支持手动触发行动（npcRef 形如 npc:{id}）");
        }
        Long characterId = NpcRefs.idOf(npcRef);
        ActorCharacter ch = characterRepository.findById(characterId)
                .filter(c -> projectId.equals(c.getProjectId()))
                .filter(c -> Integer.valueOf(0).equals(c.getDeleted()))
                .orElseThrow(() -> new BizException(404, "角色不存在或不属于该项目"));
        String reason = str(body, "reason");
        ActorActionPlan plan = agentRuntime.triggerAction(characterId,
                currentUserProvider.currentUserId(), "manual", null, reason, null, null);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("planId", plan.getId());
        out.put("characterId", plan.getCharacterId());
        out.put("characterName", ch.getName());
        out.put("status", plan.getStatus());
        out.put("triggerType", plan.getTriggerType());
        out.put("reason", reason);
        out.put("message", "行动已触发（思考决策 + 计划持久化，执行结果进入世界事件流）");
        return R.ok(out);
    }

    /**
     * 干预-手动事件：向世界注入一条事件（持久化 + 事件总线广播，沙盘经 scene:event 实时可视化）。
     *
     * @param projectId 项目 ID
     *
     * @param body 入参：{title?, content 必填, kind?}
     *
     * @return 新增的事件视图（同时间线接口）
     */
    @PostMapping("/intervene/event")
    public R<Map<String, Object>> interveneEvent(@PathVariable Long projectId,
                                                 @RequestBody(required = false) Map<String, Object> body) {
        requireProject(projectId);
        return R.ok(timelineService.createEvent(projectId, body));
    }

    /**
     * 干预-时间控制：更新世界时钟（速率/暂停/常驻运行）或手动补推一轮（advance=true）。
     *
     * @param projectId 项目 ID
     *
     * @return advance=false → {clock: 时钟 VO}；advance=true → 推进结果 Map
     */
    @PostMapping("/intervene/clock")
    public R<Map<String, Object>> interveneClock(@PathVariable Long projectId,
                                                 @RequestBody(required = false) Map<String, Object> body) {
        requireProject(projectId);
        Long userId = currentUserProvider.currentUserId();
        boolean advance = body != null && Boolean.TRUE.equals(asBool(body.get("advance")));
        if (advance) {
            // 归属校验（同时懒创建时钟行），随后执行推进（对齐 WorldClockController.advance）
            worldClockService.getClock(projectId, userId);
            return R.ok(worldEngine.advanceProject(projectId));
        }
        WorldClockDTO dto = new WorldClockDTO(
                body == null ? null : asInt(body.get("rate")),
                body == null ? null : asBool(body.get("paused")),
                body == null ? null : asInt(body.get("worldRunning")),
                null, null);
        return R.ok(Map.of("clock", worldClockService.updateClock(projectId, userId, dto)));
    }

    /**
     * 干预-天气切换（S4）：把当前世界帧天气确定性切换为指定条件（晴/雨/雪/阴）。
     * <p>就地更新 active 帧 weather_json（保留温度/风，不新建帧、不依赖 AI）+ 环境刺激挂钩
     * （NPC 感知天气变化做出反应）+ 世界事件（重要度 2 仅可视化）+ 沙盘 SSE {@code weather} 推送。</p>
     *
     * @param projectId 项目 ID
     *
     * @param body 入参：{condition: 晴|雨|雪|阴 必填}
     */
    @PostMapping("/weather")
    public R<WeatherVO> switchWeather(@PathVariable Long projectId,
                                      @RequestBody(required = false) Map<String, Object> body) {
        requireProject(projectId);
        String condition = body == null ? null : str(body, "condition");
        return R.ok(weatherSwitchService.switchWeather(projectId, condition));
    }

    // ==================== 内部工具 ====================

    /** 解析 layout_json → JsonNode（S3 补全：失败/为空返回 null，前端回退无布局渲染） */
    private Object parseLayout(String layoutJson) {
        if (layoutJson == null || layoutJson.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(layoutJson);
        } catch (Exception e) {
            log.warn("[沙盘场景] layout_json 解析失败：{}", e.getMessage());
            return null;
        }
    }

    /** 项目校验（不存在/已删除 → 404） */
    private void requireProject(Long projectId) {
        projectRepository.findById(projectId)
                .filter(p -> Integer.valueOf(0).equals(p.getDeleted()))
                .orElseThrow(() -> new BizException(404, "项目不存在或已删除"));
    }

    /** NPC 引用校验：npc:{id} → 档案行存在且属于项目 */
    private void requireNpc(Long projectId, String npcRef) {
        // ：npc:{id} 不再编码类型，此处按意图 A 处理（能否解析成 actor_profile 行）
        Long id = NpcRefs.idOf(npcRef);
        if (id == null || characterRepository.findById(id)
                .filter(c -> projectId.equals(c.getProjectId()))
                .filter(c -> Integer.valueOf(0).equals(c.getDeleted()))
                .isEmpty()) {
            throw new BizException(404, "NPC 不存在或不属于该项目");
        }
    }

    /**
     * 查找或创建该 NPC 的单聊会话（插话复用）：
     * 优先复用项目内该 NPC 已有的 active 单聊（按更新时间最新），无则创建（memberRefs=[npcRef]）。
     *
     * @param projectId 项目 ID
     *
     * @param npcRef NPC 引用
     *
     * @return 会话 VO
     */
    private ConversationVO ensureSingleConversation(Long projectId, String npcRef) {
        // ：npc:{id} 不再编码类型，此处按意图 B 处理（成员类型改查 actor_profile.type）
        String memberType = npcTypeResolver.typeOf(projectId, npcRef);
        Long npcId = NpcRefs.idOf(npcRef);
        List<ActorConversation> existing = conversationMemberRepository.findByCharacterId(npcId).stream()
                .filter(m -> memberType.equals(NpcRefs.normalizeType(m.getMemberType())))
                .map(ActorConversationMember::getConversationId)
                .distinct()
                .map(conversationRepository::findById)
                .flatMap(Optional::stream)
                .filter(c -> projectId.equals(c.getProjectId()))
                .filter(c -> "single".equals(c.getMode()))
                .filter(c -> "active".equals(c.getStatus()))
                .sorted(Comparator.comparing(ActorConversation::getUpdatedAt,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();
        if (!existing.isEmpty()) {
            return conversationService.detail(existing.get(0).getId());
        }
        return conversationService.create(projectId,
                new ConversationDTO("single", null, null, List.of(npcRef), null, null));
    }

    /** Map 取字符串（null 安全）。 */
    private String str(Map<String, Object> body, String key) {
        if (body == null || body.get(key) == null) {
            return null;
        }
        String s = String.valueOf(body.get(key)).trim();
        return s.isEmpty() ? null : s;
    }

    /** Map 取布尔（null 安全）。 */
    private Boolean asBool(Object v) {
        if (v == null) {
            return null;
        }
        return "true".equalsIgnoreCase(String.valueOf(v)) || Boolean.TRUE.equals(v);
    }

    /** Map 取整数（null 安全）。 */
    private Integer asInt(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof Number n) {
            return n.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(v).trim());
        } catch (Exception e) {
            return null;
        }
    }
}
