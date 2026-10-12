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

import com.holzyn.actor.domain.world.config.MesoProperties;
import com.holzyn.actor.domain.world.entity.ActorMesoDirective;
import com.holzyn.actor.domain.world.entity.ActorMesoGroup;
import com.holzyn.actor.domain.world.entity.ActorMesoMembership;
import com.holzyn.actor.domain.world.repository.ActorMesoDirectiveRepository;
import com.holzyn.actor.domain.world.repository.ActorMesoGroupRepository;
import com.holzyn.actor.domain.world.repository.ActorMesoMembershipRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

/**
 * 宏观社会演化 · 中观指令路由器。
 * <p>职责：把中观指令转成个体感知上下文段（三通道：倾向/约束/资源变化）——按成员关系
 * 路由到人（同一宏观事件，粮商/农户/医者读到不同的组织处境）：</p>
 * <ul>
 * <li><b>resolveTargetActors</b>：指令的目标成员（组指令=组内 active 成员；全局指令=
 * 全部有组织的成员）；</li>
 * <li><b>composePerceptionSegment</b>：【组织处境】段（感知注入用，≤perceptionBudget）——
 * 成员角色 + 组影响力 + 有效指令三通道摘要；</li>
 * <li><b>composeDecisionContext</b>：决策情境追加用（≤decisionBudget）——更短；</li>
 * <li><b>activeDirectivesFor</b>：某成员当前有效指令（成员所属组指令 + 全局指令；
 * 按 ttl 到期过滤，惰性不写库）。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MesoDirectiveRouter {

    private final MesoProperties props;
    private final ActorMesoMembershipRepository membershipRepository;
    private final ActorMesoGroupRepository groupRepository;
    private final ActorMesoDirectiveRepository directiveRepository;
    private final ObjectMapper objectMapper;

    // ==================== 目标解析 ====================

    /**
     * 指令目标成员：组指令=组内 active 成员；全局指令=项目全部有组织的成员（含 leader）。
     *
     * @param projectId 项目 ID
     *
     * @param directive 指令（groupId 可空=全局）
     *
     * @return 成员引用列表（special:{id}/crowd:{id}）
     */
    public List<String> resolveTargetActors(Long projectId, ActorMesoDirective directive) {
        List<String> out = new ArrayList<>();
        if (projectId == null || directive == null) {
            return out;
        }
        try {
            if (directive.getGroupId() != null) {
                for (ActorMesoMembership m : membershipRepository
                        .findByGroupIdAndStatusOrderByIdAsc(directive.getGroupId(), "active")) {
                    out.add(m.getActorRef());
                }
            } else {
                List<ActorMesoGroup> groups = groupRepository.findByProjectIdAndStatusOrderByIdAsc(projectId, "active");
                for (ActorMesoGroup g : groups) {
                    for (ActorMesoMembership m : membershipRepository
                            .findByGroupIdAndStatusOrderByIdAsc(g.getId(), "active")) {
                        if (!out.contains(m.getActorRef())) {
                            out.add(m.getActorRef());
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.warn("[中观] 指令目标解析失败（返回已得部分）: project={} : {}", projectId, e.getMessage());
        }
        return out;
    }

    // ==================== 感知/决策上下文组装 ====================

    /**
     * 【组织处境】段（感知注入用；≤props.perceptionBudget；无组织/无指令返回空串）。
     * 同一宏观事件，不同组织的成员读到不同的组织处境——个体化解读（回灌个体化）。
     *
     * @param projectId 项目 ID
     *
     * @param actorRef 统一角色引用（special:{id}）
     *
     * @param gameSecond 游戏秒（指令到期判定）
     *
     * @return 段文本（空=跳过）
     */
    public String composePerceptionSegment(Long projectId, String actorRef, Long gameSecond) {
        return compose(projectId, actorRef, gameSecond, props.getPerceptionBudget(), true);
    }

    /**
     * 【组织处境】段（决策情境追加用；≤props.decisionBudget；与感知段同源更短）。
     */
    public String composeDecisionContext(Long projectId, String actorRef, Long gameSecond) {
        return compose(projectId, actorRef, gameSecond, props.getDecisionBudget(), false);
    }

    /** 组装核心（组织角色 + 有效指令三通道摘要；长度预算截断）。 */
    private String compose(Long projectId, String actorRef, Long gameSecond, int budget, boolean withInfluence) {
        if (!props.isEnabled() || projectId == null || actorRef == null) {
            return "";
        }
        try {
            List<ActorMesoMembership> memberships = membershipRepository
                    .findByProjectIdAndActorRefAndStatus(projectId, actorRef, "active");
            if (memberships.isEmpty()) {
                return "";
            }
            StringBuilder sb = new StringBuilder("【组织处境】");
            int used = 6;
            int shown = 0;
            for (ActorMesoMembership m : memberships) {
                if (shown >= 2) {
                    break; // 最多呈现 2 个组织（长度预算保护）
                }
                ActorMesoGroup g = groupRepository.findById(m.getGroupId()).orElse(null);
                if (g == null || !"active".equals(g.getStatus())) {
                    continue;
                }
                String role = roleLabel(m.getRole());
                StringBuilder line = new StringBuilder("\n· 你在「").append(g.getName()).append("」中任")
                        .append(role);
                if (withInfluence && g.getInfluence() != null && g.getInfluence() > 0) {
                    line.append("（组织影响力 ").append(Math.round(g.getInfluence())).append("）");
                }
                List<ActorMesoDirective> ds = activeDirectivesFor(projectId, m.getGroupId(), gameSecond);
                if (!ds.isEmpty()) {
                    String hint = directiveHint(ds.get(0));
                    if (!hint.isBlank()) {
                        line.append("；组织叮嘱：").append(hint);
                    }
                }
                if (used + line.length() <= budget) {
                    sb.append(line);
                    used += line.length();
                    shown++;
                }
            }
            return sb.toString();
        } catch (Exception e) {
            log.warn("[中观] 【组织处境】段组装失败（跳过）: project={} ref={} : {}", projectId, actorRef, e.getMessage());
            return "";
        }
    }

    /** 组内角色显示名。 */
    static String roleLabel(String role) {
        if (role == null) {
            return "成员";
        }
        return switch (role) {
            case "leader" -> "首领";
            case "elder" -> "长老";
            case "core" -> "核心成员";
            case "affiliate" -> "外围成员";
            default -> "成员";
        };
    }

    /** 指令三通道摘要（倾向 bias → 约束 forbidden → 资源变化 hint；≤40 字）。 */
    String directiveHint(ActorMesoDirective d) {
        if (d == null || d.getPayloadJson() == null || d.getPayloadJson().isBlank()) {
            return "";
        }
        try {
            JsonNode root = objectMapper.readTree(d.getPayloadJson());
            StringBuilder sb = new StringBuilder();
            JsonNode tendency = root.path("tendency");
            if (tendency.isObject() && tendency.path("bias").isTextual()) {
                sb.append(tendency.path("bias").asText());
            }
            JsonNode constraint = root.path("constraint");
            if (constraint.isObject() && constraint.path("forbidden").isArray()) {
                List<String> fb = new ArrayList<>();
                constraint.path("forbidden").forEach(n -> fb.add(n.asText()));
                if (!fb.isEmpty()) {
                    sb.append("，忌").append(String.join("/", fb));
                }
            }
            JsonNode resource = root.path("resourceDelta");
            if (resource.isObject() && resource.path("hint").isTextual()
                    && !resource.path("hint").asText().isBlank()) {
                sb.append("，").append(resource.path("hint").asText());
            }
            if (sb.length() == 0) {
                JsonNode note = root.path("note");
                if (note.isTextual()) {
                    return abbrev(note.asText(), 40);
                }
            }
            return abbrev(sb.toString(), 40);
        } catch (Exception e) {
            return "";
        }
    }

    // ==================== 有效指令查询 ====================

    /**
     * 某成员当前有效指令（成员所属组指令 + 全局指令；按 ttl 到期过滤；惰性不写库——
     * 状态列保留 active，读取侧按到期时间过滤，行为一致且零写放大）。
     *
     * @param projectId 项目 ID
     *
     * @param groupId 成员所属组 ID（null=仅全局）
     *
     * @param gameSecond 当前游戏秒（到期判定；null=永不过期）
     *
     * @return 有效指令列表（按发布时间倒序）
     */
    public List<ActorMesoDirective> activeDirectivesFor(Long projectId, Long groupId, Long gameSecond) {
        List<ActorMesoDirective> out = new ArrayList<>();
        if (!props.isEnabled() || projectId == null) {
            return out;
        }
        try {
            if (groupId != null) {
                for (ActorMesoDirective d : directiveRepository
                        .findByProjectIdAndGroupIdAndStatusOrderByIdDesc(projectId, groupId, "active")) {
                    if (notExpired(d, gameSecond)) {
                        out.add(d);
                    }
                }
            }
            for (ActorMesoDirective d : directiveRepository
                    .findByProjectIdAndGroupIdIsNullAndStatusOrderByIdDesc(projectId, "active")) {
                if (notExpired(d, gameSecond)) {
                    out.add(d);
                }
            }
        } catch (Exception e) {
            log.warn("[中观] 有效指令查询失败（返回已得部分）: project={} : {}", projectId, e.getMessage());
        }
        return out;
    }

    /** ttl 到期判定（issued + ttl < now → 过期；ttl null=永不过期）。 */
    static boolean notExpired(ActorMesoDirective d, Long gameSecond) {
        if (d == null || d.getTtl() == null || gameSecond == null) {
            return true;
        }
        long issued = d.getIssuedGameSecond() == null ? 0L : d.getIssuedGameSecond();
        return issued + d.getTtl() >= gameSecond;
    }

    /** 文本截断。 */
    static String abbrev(String s, int max) {
        if (s == null || s.length() <= max) {
            return s;
        }
        return s.substring(0, max);
    }
}
