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

package com.holzyn.actor.domain.world.controller;

import com.holzyn.actor.domain.world.entity.ActorMesoDirective;
import com.holzyn.actor.domain.world.entity.ActorMesoGroup;
import com.holzyn.actor.domain.world.entity.ActorMesoMembership;
import com.holzyn.actor.domain.world.entity.ActorMesoResponse;
import com.holzyn.actor.domain.world.repository.ActorMesoDirectiveRepository;
import com.holzyn.actor.domain.world.repository.ActorMesoGroupRepository;
import com.holzyn.actor.domain.world.repository.ActorMesoMembershipRepository;
import com.holzyn.actor.domain.world.repository.ActorMesoResponseRepository;
import com.holzyn.actor.domain.world.service.MacroEmergenceService;
import com.holzyn.actor.domain.world.service.MesoCoordinationService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 宏观社会演化 · 中观协调 + 二阶涌现接口。
 * <p>接口集（调试面板/定向冒烟用）：</p>
 * <ul>
 * <li>GET /macro/groups —— 中观组织列表（名称/类型/影响力/领袖/成员数）；</li>
 * <li>POST /macro/groups —— 手动建组（body {name,type,leaderRef?,tags?}；调试/定向冒烟，可回滚）；</li>
 * <li>DELETE /macro/groups/{gid} —— 解散组织（成员关系与指令一并软删）；</li>
 * <li>GET /macro/groups/{gid}/directives —— 组织指令回放（活跃/过期）；</li>
 * <li>GET /macro/groups/{gid}/responses —— 组织指令响应回收回放（comply/partial/defy/ignore）；</li>
 * <li>GET /macro/emergence —— 二阶涌现事件列表（囤积潮/迁徙潮/抗议潮…）；</li>
 * <li>GET /macro/emergence/{eid} —— 涌现事件详情（含参与成员明细）。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/projects/{id}/macro")
@RequiredArgsConstructor
public class MesoController {

    private final ActorMesoGroupRepository groupRepository;
    private final ActorMesoMembershipRepository membershipRepository;
    private final ActorMesoDirectiveRepository directiveRepository;
    private final ActorMesoResponseRepository responseRepository;
    private final MesoCoordinationService mesoCoordinationService;
    private final MacroEmergenceService macroEmergenceService;
    private final com.holzyn.actor.domain.world.service.BehaviorDiversityService behaviorDiversityService;

    /** 中观组织列表（调试面板）。 */
    @GetMapping("/groups")
    public List<Map<String, Object>> groups(@PathVariable("id") Long projectId) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (ActorMesoGroup g : groupRepository.findByProjectIdOrderByIdAsc(projectId)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", g.getId());
            m.put("name", g.getName());
            m.put("type", g.getType());
            m.put("origin", g.getOrigin());
            m.put("status", g.getStatus());
            m.put("influence", g.getInfluence());
            m.put("leaderRef", g.getLeaderRef());
            m.put("tagsJson", g.getTagsJson());
            m.put("metaJson", g.getMetaJson());
            long members = membershipRepository.findByGroupIdOrderByIdAsc(g.getId()).stream()
                    .filter(x -> "active".equals(x.getStatus())).count();
            m.put("memberCount", members);
            out.add(m);
        }
        return out;
    }

    /** 手动建组（调试/定向冒烟用；可回滚——DELETE 解散）。 */
    @PostMapping("/groups")
    public Map<String, Object> createGroup(@PathVariable("id") Long projectId,
                                           @RequestBody(required = false) Map<String, Object> body) {
        String name = body == null ? null : str(body.get("name"));
        String type = body == null ? null : str(body.get("type"));
        String leaderRef = body == null ? null : str(body.get("leaderRef"));
        List<String> tags = new ArrayList<>();
        Object rawTags = body == null ? null : body.get("tags");
        if (rawTags instanceof List<?> list) {
            for (Object o : list) {
                if (o != null) {
                    tags.add(String.valueOf(o));
                }
            }
        }
        ActorMesoGroup g = mesoCoordinationService.createGroup(projectId, name, type, leaderRef, tags, null);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", g.getId());
        m.put("name", g.getName());
        m.put("type", g.getType());
        m.put("status", g.getStatus());
        m.put("influence", g.getInfluence());
        m.put("leaderRef", g.getLeaderRef());
        return m;
    }

    /** 解散组织（成员关系与指令一并软删）。 */
    @DeleteMapping("/groups/{groupId}")
    public Map<String, Object> dissolveGroup(@PathVariable("id") Long projectId,
                                             @PathVariable Long groupId) {
        mesoCoordinationService.dissolveGroup(projectId, groupId);
        return Map.of("ok", true, "groupId", groupId);
    }

    /** 组织指令回放（活跃/过期；调试面板）。 */
    @GetMapping("/groups/{groupId}/directives")
    public List<Map<String, Object>> directives(@PathVariable("id") Long projectId,
                                                @PathVariable Long groupId) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (ActorMesoDirective d : directiveRepository.findByProjectIdAndGroupIdOrderByIdDesc(projectId, groupId)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", d.getId());
            m.put("directiveType", d.getDirectiveType());
            m.put("sourceMacroEventId", d.getSourceMacroEventId());
            m.put("payloadJson", d.getPayloadJson());
            m.put("status", d.getStatus());
            m.put("issuedGameSecond", d.getIssuedGameSecond());
            m.put("ttlSeconds", d.getTtl());
            m.put("source", d.getSource());
            out.add(m);
        }
        return out;
    }

    /** 组织指令响应回收回放（comply/partial/defy/ignore；调试面板）。 */
    @GetMapping("/groups/{groupId}/responses")
    public List<Map<String, Object>> responses(@PathVariable("id") Long projectId,
                                               @PathVariable Long groupId) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (ActorMesoResponse r : responseRepository.findByProjectIdOrderByIdDesc(projectId)) {
            ActorMesoDirective d = directiveRepository.findById(r.getDirectiveId()).orElse(null);
            if (d == null || !groupId.equals(d.getGroupId())) {
                continue;
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", r.getId());
            m.put("directiveId", r.getDirectiveId());
            m.put("actorRef", r.getActorRef());
            m.put("responseType", r.getResponseType());
            m.put("decisionTraceId", r.getDecisionTraceId());
            m.put("gameSecond", r.getGameSecond());
            out.add(m);
        }
        return out;
    }

    /** 二阶涌现事件列表（囤积潮/迁徙潮/抗议潮…；调试面板）。 */
    @GetMapping("/emergence")
    public List<Map<String, Object>> emergence(@PathVariable("id") Long projectId,
                                               @RequestParam(required = false) Integer limit) {
        return macroEmergenceService.listVOs(projectId, limit);
    }

    /** 涌现事件详情（含参与成员明细）。 */
    @GetMapping("/emergence/{emergenceId}")
    public Map<String, Object> emergenceDetail(@PathVariable("id") Long projectId,
                                               @PathVariable Long emergenceId) {
        Map<String, Object> vo = macroEmergenceService.detailVO(projectId, emergenceId);
        if (vo == null) {
            throw new com.holzyn.actor.common.BizException(404, "涌现事件不存在");
        }
        return vo;
    }

    /** 行为多样性注入记录。 */
    @GetMapping("/diversity")
    public List<Map<String, Object>> diversity(@PathVariable("id") Long projectId,
                                               @RequestParam(required = false) String actorRef,
                                               @RequestParam(required = false) Integer limit) {
        return behaviorDiversityService.listVOs(projectId, actorRef, limit);
    }

    /** 值安全转字符串。 */
    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }
}
