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

import com.holzyn.actor.common.NpcRefs;
import com.holzyn.actor.domain.character.vo.CharacterRelationGraphVO;
import com.holzyn.actor.domain.growth.lineage.ActorGrowthLineage;
import com.holzyn.actor.domain.growth.lineage.ActorGrowthLineageRepository;
import com.holzyn.actor.domain.npc.profile.entity.ActorCharacter;
import com.holzyn.actor.domain.world.entity.ActorMesoGroup;
import com.holzyn.actor.domain.world.entity.ActorMesoMembership;
import com.holzyn.actor.domain.world.repository.ActorMesoGroupRepository;
import com.holzyn.actor.domain.world.repository.ActorMesoMembershipRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 关系拓扑节点「附加语义」解析器（UI V3.0 · 关系工作台只增不改新增）。
 *
 * <p><b>为什么需要它</b>：重做前的关系拓扑只有「姓名 + 重要度 + 圆点」三种可编码信息，
 * 关系表为空时整块画布就是一片孤立圆点（实测：青禾村 17 节点 / 0 关系），界面看上去像坏了。
 * 而「谁和谁是一拨人」这件事世界内核里<b>本来就有真实答案</b>：</p>
 * <ul>
 * <li><b>中观组织</b>：{@code actor_meso_membership}（active 成员关系）× {@code actor_meso_group}
 * （宗族 family / 祭祀 cult / 行会 guild / 群落 community / 自发成组 adhoc）——含组内角色与影响力；</li>
 * <li><b>血缘家族</b>：{@code actor_growth_lineage}（亲子 + 婚姻边）的连通分量。</li>
 * </ul>
 *
 * <p><b>口径纪律（唯一来源）</b>：组织归属的兜底规则与 {@code WorldGraphQueryService}
 * 「角色所属组织」完全同源——先查成员关系；无成员关系时按 {@code actor_profile.affiliation}
 * 文本匹配组织名（相等或包含，排除自发成组），保证关系拓扑里的圈子与世界图谱里的势力对得上。
 * 本类只读、不写、不缓存，失败一律降级为「无归属」而不是抛错（拓扑接口绝不因缺数据而 500）。</p>
 *
 * <p><b>不编造原则</b>：查不到组织就 {@code groupId=null}，查不到谱系边就 {@code familyId=null}——
 * 前端据此走「按职业/归属文本兜底分区」，而不是让后端猜一个看起来齐整的分组。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CharacterRelationMetaResolver {

    /** 成员关系状态：在组（与 MesoCoordinationService 口径一致） */
    private static final String MEMBER_STATUS_ACTIVE = "active";

    /** 组织类型：自发成组（势力/家族口径排除，对齐 WorldGraphQueryService） */
    private static final String GROUP_TYPE_ADHOC = "adhoc";

    /** 组织状态：生效（解散/合并的组织不再作为归属展示） */
    private static final String GROUP_STATUS_ACTIVE = "active";

    /** 组内角色优先级（数字越小越「主要」；成员多组时取最靠前的一个作为主归属） */
    private static final Map<String, Integer> ROLE_PRIORITY = Map.of(
            "leader", 0, "elder", 1, "core", 2, "member", 3, "affiliate", 4);

    /** 中文复姓（家族称谓取姓时用；不全也不影响正确性——只会少认一个复姓，不会认错成两姓） */
    private static final Set<String> COMPOUND_SURNAMES = Set.of(
            "欧阳", "司马", "上官", "诸葛", "司徒", "夏侯", "慕容", "皇甫", "东方", "独孤",
            "南宫", "西门", "公孙", "长孙", "宇文", "轩辕", "令狐", "尉迟", "赫连", "澹台");

    private final ActorMesoGroupRepository groupRepository;
    private final ActorMesoMembershipRepository membershipRepository;
    private final ActorGrowthLineageRepository lineageRepository;

    /**
     * 解析项目下全部角色的附加语义（一次批量，无 N+1：3 条查询封顶）。
     *
     * @param projectId 项目 ID
     *
     * @param characters 项目下全部角色（特殊型 + 普通型；用于按 id / affiliation 建立兜底索引）
     *
     * @return 角色 ID → 附加语义（查询失败时为空 Map，调用方回退 {@code NodeMeta.EMPTY}）
     */
    public Map<Long, CharacterRelationGraphVO.NodeMeta> resolve(Long projectId, List<ActorCharacter> characters) {
        List<ActorCharacter> chars = characters == null ? List.of() : characters;
        if (chars.isEmpty()) {
            return Map.of();
        }
        try {
            List<ActorMesoGroup> groups = groupRepository.findByProjectIdOrderByIdAsc(projectId).stream()
                    .filter(g -> g.getStatus() == null || GROUP_STATUS_ACTIVE.equals(g.getStatus()))
                    .toList();
            Map<Long, ActorMesoGroup> groupById = new LinkedHashMap<>();
            for (ActorMesoGroup g : groups) {
                groupById.put(g.getId(), g);
            }
            Map<Long, ActorMesoMembership> primaryMembership = primaryMemberships(projectId);
            Map<String, ActorMesoGroup> groupByName = new LinkedHashMap<>();
            for (ActorMesoGroup g : groups) {
                if (g.getName() != null && !g.getName().isBlank()) {
                    groupByName.putIfAbsent(g.getName().trim(), g);
                }
            }
            Map<Long, Family> families = families(projectId, chars);

            Map<Long, CharacterRelationGraphVO.NodeMeta> out = new HashMap<>();
            for (ActorCharacter c : chars) {
                if (c == null || c.getId() == null) {
                    continue;
                }
                ActorMesoMembership membership = primaryMembership.get(c.getId());
                ActorMesoGroup group = membership == null ? null : groupById.get(membership.getGroupId());
                if (group == null) {
                    group = matchByAffiliationText(c.getAffiliation(), groups, groupByName);
                }
                out.put(c.getId(), new CharacterRelationGraphVO.NodeMeta(
                        c.getAvatarUrl(),
                        c.getGender(),
                        c.getAge(),
                        c.getStage(),
                        c.getAffiliation(),
                        c.getOccupation(),
                        c.getLocation(),
                        c.getLocationId(),
                        group == null ? null : group.getId(),
                        group == null ? null : group.getName(),
                        group == null ? null : group.getType(),
                        group == null || membership == null ? null : membership.getRole(),
                        families.containsKey(c.getId()) ? families.get(c.getId()).id() : null,
                        families.containsKey(c.getId()) ? families.get(c.getId()).name() : null));
            }
            return out;
        } catch (Exception e) {
            // 归属是「锦上添花」：解析失败绝不能让关系拓扑接口失败（前端已有职业/文本兜底分区）
            log.warn("[关系拓扑] 节点附加语义解析失败（降级无归属）: project={} : {}", projectId, e.getMessage());
            return Map.of();
        }
    }

    /**
     * 每角色取一条「主要」成员关系（组内角色优先级 → 组织 ID 升序，保证同一数据每次结果一致）。
     *
     * @param projectId 项目 ID
     *
     * @return 角色 ID → 主要成员关系
     */
    private Map<Long, ActorMesoMembership> primaryMemberships(Long projectId) {
        List<ActorMesoMembership> rows =
                membershipRepository.findByProjectIdAndStatusOrderByIdAsc(projectId, MEMBER_STATUS_ACTIVE);
        Map<Long, ActorMesoMembership> out = new HashMap<>();
        for (ActorMesoMembership m : rows) {
            if (m == null || m.getActorId() == null || m.getGroupId() == null) {
                continue;
            }
            ActorMesoMembership old = out.get(m.getActorId());
            if (old == null || isMorePrimary(m, old)) {
                out.put(m.getActorId(), m);
            }
        }
        return out;
    }

    /** 成员关系优先级比较（组内角色优先，其次组织 ID 小者胜）。 */
    private static boolean isMorePrimary(ActorMesoMembership candidate, ActorMesoMembership current) {
        int a = ROLE_PRIORITY.getOrDefault(candidate.getRole(), ROLE_PRIORITY.get("member"));
        int b = ROLE_PRIORITY.getOrDefault(current.getRole(), ROLE_PRIORITY.get("member"));
        if (a != b) {
            return a < b;
        }
        return candidate.getGroupId() < current.getGroupId();
    }

    /**
     * affiliation 文本兜底匹配（与 {@code WorldGraphQueryService.factionsOfCharacter} 同源规则）：
     * 文本等于组织名，或包含组织名；排除自发成组（adhoc 是行为聚簇，不是身份归属）。
     *
     * @param affiliation 角色归属文本（可空）
     *
     * @param groups 项目下生效组织
     *
     * @param groupByName 组织名索引（精确命中优先）
     *
     * @return 命中的组织；无命中返回 null
     */
    private static ActorMesoGroup matchByAffiliationText(String affiliation, List<ActorMesoGroup> groups,
                                                         Map<String, ActorMesoGroup> groupByName) {
        if (affiliation == null || affiliation.isBlank()) {
            return null;
        }
        String aff = affiliation.trim();
        ActorMesoGroup exact = groupByName.get(aff);
        if (exact != null) {
            return exact;
        }
        for (ActorMesoGroup g : groups) {
            if (GROUP_TYPE_ADHOC.equals(g.getType()) || g.getName() == null || g.getName().isBlank()) {
                continue;
            }
            if (aff.contains(g.getName().trim())) {
                return g;
            }
        }
        return null;
    }

    /**
     * 血缘家族解析：谱系边（亲子 + 婚姻）连通分量 → 家族（稳定 ID + 称谓）。
     *
     * <p>只收录<b>至少一条谱系边</b>的角色：独身且无父母子女者返回空（不编造「一人一族」）。
     * 称谓取分量内出现最多的姓氏（复姓优先识别）+「一族」——这是从谱系事实派生的最小描述，
     * 不引入任何世界专属命名常量。ID 取分量最小角色 ID（同姓不同宗也各自独立，不合并）。</p>
     *
     * @param projectId 项目 ID
     *
     * @param characters 项目下全部角色（提供姓名 → 姓氏）
     *
     * @return 角色 ID → 家族（无谱系边者不在 Map 内）
     */
    private Map<Long, Family> families(Long projectId, List<ActorCharacter> characters) {
        List<ActorGrowthLineage> edges;
        try {
            edges = lineageRepository.findByProjectIdOrderByIdAsc(projectId);
        } catch (Exception e) {
            log.debug("[关系拓扑] 谱系查询失败（家族维降级为空）: project={} : {}", projectId, e.getMessage());
            return Map.of();
        }
        if (edges == null || edges.isEmpty()) {
            return Map.of();
        }
        Map<Long, String> nameById = new HashMap<>();
        for (ActorCharacter c : characters) {
            if (c != null && c.getId() != null) {
                nameById.put(c.getId(), c.getName());
            }
        }
        // 并查集：亲子与婚姻边都视为「一家人」（婚姻把两个家族连起来，展示上比拆开更有信息量）
        Map<Long, Long> parent = new HashMap<>();
        Set<Long> touched = new java.util.HashSet<>();
        for (ActorGrowthLineage e : edges) {
            if (e == null) {
                continue;
            }
            if (e.getActive() != null && e.getActive() == 0) {
                continue; // 已终止的婚姻边不再连家
            }
            Long from = NpcRefs.parseLenient(e.getFromRef());
            Long to = NpcRefs.parseLenient(e.getToRef());
            if (from == null || to == null || from.equals(to)) {
                continue;
            }
            if (!nameById.containsKey(from) && !nameById.containsKey(to)) {
                continue;
            }
            union(parent, from, to);
            touched.add(from);
            touched.add(to);
        }
        if (touched.isEmpty()) {
            return Map.of();
        }
        // 分量 → 成员
        Map<Long, List<Long>> members = new LinkedHashMap<>();
        for (Long id : touched) {
            members.computeIfAbsent(find(parent, id), k -> new ArrayList<>()).add(id);
        }
        Map<Long, Family> out = new HashMap<>();
        for (Map.Entry<Long, List<Long>> en : members.entrySet()) {
            List<Long> ids = en.getValue();
            ids.sort(Comparator.naturalOrder());
            Family family = new Family("fam-" + en.getKey(), majoritySurname(ids, nameById));
            for (Long id : ids) {
                out.put(id, family);
            }
        }
        return out;
    }

    /**
     * 血缘家族（稳定 ID + 人类可读称谓）。
     *
     * @param id 家族 ID（{@code fam-<分量最小角色 ID>}；前端用作分区键）
     *
     * @param name 家族称谓（如「沈氏一族」）
     */
    private record Family(String id, String name) {
    }

    /** 分量多数姓氏 → 「X氏一族」（无姓名可取时用「同族」）。 */
    private static String majoritySurname(List<Long> ids, Map<Long, String> nameById) {
        Map<String, Integer> count = new LinkedHashMap<>();
        for (Long id : ids) {
            String surname = surnameOf(nameById.get(id));
            if (surname != null) {
                count.merge(surname, 1, Integer::sum);
            }
        }
        String best = null;
        int bestCount = 0;
        for (Map.Entry<String, Integer> en : count.entrySet()) {
            if (en.getValue() > bestCount) {
                best = en.getKey();
                bestCount = en.getValue();
            }
        }
        return best == null ? "同族" : best + "氏一族";
    }

    /** 中文姓名取姓（先试复姓，再取首字；空名返回 null）。 */
    static String surnameOf(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        String n = name.trim();
        if (n.length() >= 2 && COMPOUND_SURNAMES.contains(n.substring(0, 2))) {
            return n.substring(0, 2);
        }
        return n.substring(0, 1);
    }

    // ==================== 并查集 ====================

    /** 并查集合并（懒初始化）。 */
    private static void union(Map<Long, Long> parent, Long a, Long b) {
        Long ra = find(parent, a);
        Long rb = find(parent, b);
        if (!ra.equals(rb)) {
            // 固定以较小 id 为根，保证同一数据每次分量代表一致（确定性）
            if (ra < rb) {
                parent.put(rb, ra);
            } else {
                parent.put(ra, rb);
            }
        }
    }

    /** 并查集查找（路径压缩）。 */
    private static Long find(Map<Long, Long> parent, Long x) {
        Long p = parent.get(x);
        if (p == null) {
            parent.put(x, x);
            return x;
        }
        if (p.equals(x)) {
            return x;
        }
        Long root = find(parent, p);
        parent.put(x, root);
        return root;
    }
}
