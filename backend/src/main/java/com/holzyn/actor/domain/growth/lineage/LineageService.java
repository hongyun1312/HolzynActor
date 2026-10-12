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

package com.holzyn.actor.domain.growth.lineage;

import com.holzyn.actor.common.NpcRefs;
import com.holzyn.actor.domain.growth.config.GrowthProperties;
import com.holzyn.actor.domain.npc.profile.entity.ActorCharacter;
import com.holzyn.actor.domain.npc.profile.repository.ActorCharacterRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 谱系服务：谱系边的写入与家族查询。
 * <p><b>职责</b>：</p>
 * <ul>
 * <li><b>婚姻边</b>：{@link #marry}（缔结，幂等）+ {@link #terminateMarriage}（丧偶/离异 → active=0）；</li>
 * <li><b>亲子边</b>：{@link #addParentChild}（父/母 → 子女）；</li>
 * <li><b>血亲禁忌</b>：{@link #isBloodRelated}（程序化硬门控：直系 + 三代内旁系禁婚——
 * 家族树不出现近亲婚配，真实宗族伦理）；</li>
 * <li><b>家族树</b>：{@link #familyTree}（三代，主前端/调试前端数据源）；</li>
 * <li><b>遗产继承</b>：{@link #heirOf}。</li>
 * </ul>
 * <p><b>只增不改</b>：本服务只新增/标记，不物理删除谱系边（家族事实不可篡改）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LineageService {

    private final ActorGrowthLineageRepository lineageRepository;
    private final ActorCharacterRepository characterRepository;
    private final GrowthProperties growthProperties;

    //
    // 婚姻边
    //

    /**
     * 缔结婚姻（幂等：同对已存在存续婚姻边则跳过）。
     *
     * @param projectId 项目 ID
     *
     * @param husbandRef 丈夫 npcRef（统一 npc:{id}）
     *
     * @param wifeRef 妻子 npcRef
     *
     * @param gameSecond 婚礼时刻（游戏秒）
     *
     * @return 新建边；已存在返回 null
     */
    public ActorGrowthLineage marry(Long projectId, String husbandRef, String wifeRef, Long gameSecond) {
        if (projectId == null || husbandRef == null || wifeRef == null) {
            return null;
        }
        boolean exists = lineageRepository
                .findByProjectIdAndEdgeTypeAndFromRefAndToRef(projectId, ActorGrowthLineage.EDGE_MARRIAGE,
                        husbandRef, wifeRef)
                .stream().anyMatch(e -> Integer.valueOf(1).equals(e.getActive()));
        if (exists) {
            return null;
        }
        ActorGrowthLineage edge = new ActorGrowthLineage();
        edge.setProjectId(projectId);
        edge.setEdgeType(ActorGrowthLineage.EDGE_MARRIAGE);
        edge.setFromRef(husbandRef);
        edge.setToRef(wifeRef);
        edge.setGameSecond(gameSecond);
        edge.setActive(1);
        ActorGrowthLineage saved = lineageRepository.save(edge);
        log.info("[谱系] 婚姻缔结：{} ↔ {}（gameSecond={}）", husbandRef, wifeRef, gameSecond);
        return saved;
    }

    /**
     * 终止婚姻（丧偶/离异）：把该角色作为夫/妻的存续婚姻边置 active=0（历史保留）。
     *
     * @param projectId 项目 ID
     *
     * @param ref 当事人 npcRef
     *
     * @param reason 原因（widow 丧偶 / divorce 离异；日志审计）
     */
    public void terminateMarriage(Long projectId, String ref, String reason) {
        if (projectId == null || ref == null) {
            return;
        }
        lineageRepository.findFirstByProjectIdAndEdgeTypeAndFromRefAndActiveOrderByIdDesc(
                        projectId, ActorGrowthLineage.EDGE_MARRIAGE, ref, 1)
                .ifPresent(e -> {
                    e.setActive(0);
                    lineageRepository.save(e);
                });
        lineageRepository.findFirstByProjectIdAndEdgeTypeAndToRefAndActiveOrderByIdDesc(
                        projectId, ActorGrowthLineage.EDGE_MARRIAGE, ref, 1)
                .ifPresent(e -> {
                    e.setActive(0);
                    lineageRepository.save(e);
                });
        log.info("[谱系] 婚姻终止：{}（{}）", ref, reason);
    }

    /**
     * 当前配偶（存续婚姻边的另一方；无则空）。
     * <p>治理批次 0 · 卡5：配偶须<b>在世</b>——婚姻边 active=1 但配偶已故（如存量
     * 丧偶未终止的边）不视为已婚（读侧过滤，与源头 terminateMarriage 双保险）。</p>
     *
     * @param projectId 项目 ID
     *
     * @param ref 当事人 npcRef
     *
     * @return 在世配偶 npcRef（可空）
     */
    public Optional<String> spouseOf(Long projectId, String ref) {
        if (projectId == null || ref == null) {
            return Optional.empty();
        }
        Optional<ActorGrowthLineage> asHusband = lineageRepository
                .findFirstByProjectIdAndEdgeTypeAndFromRefAndActiveOrderByIdDesc(
                        projectId, ActorGrowthLineage.EDGE_MARRIAGE, ref, 1);
        if (asHusband.isPresent()) {
            String spouse = asHusband.get().getToRef();
            return alive(projectId, spouse) ? Optional.ofNullable(spouse) : Optional.empty();
        }
        Optional<ActorGrowthLineage> asWife = lineageRepository
                .findFirstByProjectIdAndEdgeTypeAndToRefAndActiveOrderByIdDesc(
                        projectId, ActorGrowthLineage.EDGE_MARRIAGE, ref, 1);
        return asWife.filter(e -> alive(projectId, e.getFromRef())).map(ActorGrowthLineage::getFromRef);
    }

    /** 是否已婚（存在存续婚姻边）。 */
    public boolean isMarried(Long projectId, String ref) {
        return spouseOf(projectId, ref).isPresent();
    }

    /**
     * 是否存在存续婚姻边（<b>不过滤配偶在世</b>；治理批次 0 · 卡5：存量丧偶终止补行判定用——
     * spouseOf/isMarried 已带 alive 读侧过滤，亡偶边在它们眼中「不存在」，补行必须查原始边）。
     *
     * @param projectId 项目 ID
     *
     * @param ref 当事人 npcRef
     *
     * @return true=存在 active=1 的婚姻边（无论配偶是否在世）
     */
    public boolean hasActiveMarriageEdge(Long projectId, String ref) {
        if (projectId == null || ref == null) {
            return false;
        }
        boolean asHusband = lineageRepository
                .findFirstByProjectIdAndEdgeTypeAndFromRefAndActiveOrderByIdDesc(
                        projectId, ActorGrowthLineage.EDGE_MARRIAGE, ref, 1).isPresent();
        if (asHusband) {
            return true;
        }
        return lineageRepository
                .findFirstByProjectIdAndEdgeTypeAndToRefAndActiveOrderByIdDesc(
                        projectId, ActorGrowthLineage.EDGE_MARRIAGE, ref, 1).isPresent();
    }

    //
    // 亲子边
    //

    /**
     * 登记亲子边（父/母 → 子女；幂等：同对同关系已存在则跳过）。
     *
     * @param projectId 项目 ID
     *
     * @param parentRef 父母 npcRef
     *
     * @param childRef 子女 npcRef
     *
     * @param gameSecond 出生时刻
     */
    public void addParentChild(Long projectId, String parentRef, String childRef, String relation, Long gameSecond) {
        if (projectId == null || parentRef == null || childRef == null) {
            return;
        }
        boolean exists = lineageRepository.findByProjectIdOrderByIdAsc(projectId).stream().anyMatch(e ->
                ActorGrowthLineage.EDGE_PARENT_CHILD.equals(e.getEdgeType())
                        && parentRef.equals(e.getFromRef()) && childRef.equals(e.getToRef())
                        && (relation == null || relation.equals(e.getRelation())));
        if (exists) {
            return;
        }
        ActorGrowthLineage edge = new ActorGrowthLineage();
        edge.setProjectId(projectId);
        edge.setEdgeType(ActorGrowthLineage.EDGE_PARENT_CHILD);
        edge.setFromRef(parentRef);
        edge.setToRef(childRef);
        edge.setRelation(relation);
        edge.setGameSecond(gameSecond);
        edge.setActive(1);
        lineageRepository.save(edge);
    }

    /** 某角色的子女（本人为父母的全部亲子边的 to 端）。 */
    public List<String> childrenOf(Long projectId, String ref) {
        if (projectId == null || ref == null) {
            return List.of();
        }
        return lineageRepository
                .findByProjectIdAndEdgeTypeAndFromRefOrderByIdAsc(projectId, ActorGrowthLineage.EDGE_PARENT_CHILD, ref)
                .stream().map(ActorGrowthLineage::getToRef).distinct().toList();
    }

    /** 某角色的父母（本人为子女的全部亲子边的 from 端；含 relation）。 */
    public List<ActorGrowthLineage> parentEdgesOf(Long projectId, String ref) {
        if (projectId == null || ref == null) {
            return List.of();
        }
        return lineageRepository
                .findByProjectIdAndEdgeTypeAndToRefOrderByIdAsc(projectId, ActorGrowthLineage.EDGE_PARENT_CHILD, ref);
    }

    /** 某角色的父亲 npcRef（无则空）。 */
    public Optional<String> fatherOf(Long projectId, String ref) {
        return parentEdgesOf(projectId, ref).stream()
                .filter(e -> ActorGrowthLineage.REL_FATHER.equals(e.getRelation()))
                .map(ActorGrowthLineage::getFromRef).findFirst();
    }

    /** 某角色的母亲 npcRef（无则空）。 */
    public Optional<String> motherOf(Long projectId, String ref) {
        return parentEdgesOf(projectId, ref).stream()
                .filter(e -> ActorGrowthLineage.REL_MOTHER.equals(e.getRelation()))
                .map(ActorGrowthLineage::getFromRef).findFirst();
    }

    /** 某角色的兄弟姐妹（共享至少一个父母的角色，不含本人）。 */
    public List<String> siblingsOf(Long projectId, String ref) {
        if (projectId == null || ref == null) {
            return List.of();
        }
        Set<String> myParents = new LinkedHashSet<>();
        parentEdgesOf(projectId, ref).forEach(e -> myParents.add(e.getFromRef()));
        Set<String> siblings = new LinkedHashSet<>();
        for (String parent : myParents) {
            for (String child : childrenOf(projectId, parent)) {
                if (!ref.equals(child)) {
                    siblings.add(child);
                }
            }
        }
        return new ArrayList<>(siblings);
    }

    //
    // 血亲禁忌
    //

    /**
     * 是否血亲（禁婚判定）：同一祖先三代内（含直系父母/子女）。
     * <p>判定 = 共享任一祖先（父母、祖父母、外祖父母、曾祖辈取到 {@code pedigreeGenerations} 代）。</p>
     *
     * @param projectId 项目 ID
     *
     * @return true=三代内血亲（禁止婚配）
     */
    public boolean isBloodRelated(Long projectId, String a, String b) {
        if (projectId == null || a == null || b == null || a.equals(b)) {
            return a != null && a.equals(b);
        }
        int gens = Math.max(1, growthProperties.getReproduction().getPedigreeGenerations());
        Set<String> ancestorsA = ancestorSet(projectId, a, gens);
        Set<String> ancestorsB = ancestorSet(projectId, b, gens);
        // 直系：B 在 A 的祖先集（或反向）——父母/祖辈关系
        if (ancestorsA.contains(b) || ancestorsB.contains(a)) {
            return true;
        }
        // 旁系：共享任一祖先
        for (String x : ancestorsA) {
            if (ancestorsB.contains(x)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 某角色向上 N 代的祖先集（含父母/祖父母/外祖父母…）。
     * <p>注意：已发现的父辈不能同时进「已展开」集合（否则下一轮展开会被跳过，
     * 只取到一代祖先）——用 out 自身去重发现、expanded 去重展开，两套集合分离。</p>
     */
    private Set<String> ancestorSet(Long projectId, String ref, int generations) {
        Set<String> out = new LinkedHashSet<>();
        if (generations <= 0 || ref == null) {
            return out;
        }
        List<String> frontier = new ArrayList<>(List.of(ref));
        Set<String> expanded = new LinkedHashSet<>();
        for (int g = 0; g < generations && !frontier.isEmpty(); g++) {
            List<String> next = new ArrayList<>();
            for (String cur : frontier) {
                if (!expanded.add(cur)) {
                    continue;
                }
                for (ActorGrowthLineage pe : parentEdgesOf(projectId, cur)) {
                    String parent = pe.getFromRef();
                    if (parent != null && out.add(parent)) {
                        next.add(parent);
                    }
                }
            }
            frontier = next;
        }
        return out;
    }

    //
    // 家族树（三代；主前端/调试前端数据源）
    //

    /**
     * 三代家族树（以本人为中心：祖辈 → 父母辈 → 本人与兄弟姐妹 → 子女辈）。
     *
     * @param projectId 项目 ID
     *
     * @param npcRef 本人 npcRef
     *
     * @return 树状 Map（节点含 id/name/ref/gender/stage/relation 与 children）
     */
    public Map<String, Object> familyTree(Long projectId, String npcRef) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (projectId == null || npcRef == null) {
            return out;
        }
        Map<String, ActorCharacter> byRef = new LinkedHashMap<>();
        for (ActorCharacter ch : characterRepository.findByProjectIdAndDeletedOrderByIdAsc(projectId, 0)) {
            byRef.put(ch.npcRef(), ch);
        }
        // 本人 + 直系亲属（父母/配偶/子女/兄弟姐妹）
        Set<String> members = new LinkedHashSet<>();
        members.add(npcRef);
        parentEdgesOf(projectId, npcRef).forEach(e -> {
            if (e.getFromRef() != null) members.add(e.getFromRef());
        });
        spouseOf(projectId, npcRef).ifPresent(members::add);
        members.addAll(childrenOf(projectId, npcRef));
        members.addAll(siblingsOf(projectId, npcRef));
        // 祖辈（本人父母的父母）+ 祖辈配偶（家族完整性）
        for (String parent : parentEdgesOf(projectId, npcRef).stream().map(ActorGrowthLineage::getFromRef).toList()) {
            parentEdgesOf(projectId, parent).forEach(e -> {
                if (e.getFromRef() != null) members.add(e.getFromRef());
                // 祖辈（祖父/祖母）的配偶
                if (e.getFromRef() != null) {
                    spouseOf(projectId, e.getFromRef()).ifPresent(members::add);
                }
            });
            // 父母的配偶（家族完整性）
            spouseOf(projectId, parent).ifPresent(members::add);
        }
        List<Map<String, Object>> nodes = new ArrayList<>();
        Map<String, String> relLabels = new LinkedHashMap<>();
        for (String ref : members) {
            if (!byRef.containsKey(ref)) {
                continue;
            }
            ActorCharacter ch = byRef.get(ref);
            Map<String, Object> n = new LinkedHashMap<>();
            n.put("ref", ref);
            n.put("id", ch.getId());
            n.put("name", ch.getName());
            n.put("gender", ch.getGender());
            n.put("stage", ch.getStage());
            n.put("age", ch.getAge());
            n.put("relation", relationLabel(projectId, npcRef, ref, ch));
            n.put("deceased", "DECEASED".equalsIgnoreCase(ch.getStage()));
            nodes.add(n);
        }
        out.put("focus", npcRef);
        out.put("nodes", nodes);
        out.put("relations", familyEdges(projectId, members));
        return out;
    }

    /** 家族树内的边（婚姻 + 亲子；供前端画图）。 */
    private List<Map<String, Object>> familyEdges(Long projectId, Set<String> members) {
        List<Map<String, Object>> edges = new ArrayList<>();
        for (ActorGrowthLineage e : lineageRepository.findByProjectIdOrderByIdAsc(projectId)) {
            if (!members.contains(e.getFromRef()) || !members.contains(e.getToRef())) {
                continue;
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("type", e.getEdgeType());
            m.put("relation", e.getRelation());
            m.put("from", e.getFromRef());
            m.put("to", e.getToRef());
            m.put("active", e.getActive());
            m.put("gameSecond", e.getGameSecond());
            edges.add(m);
        }
        return edges;
    }

    /** 本人与亲属的关系标签（家族树节点展示）。 */
    private String relationLabel(Long projectId, String focus, String ref, ActorCharacter ch) {
        if (focus.equals(ref)) {
            return "本人";
        }
        if (spouseOf(projectId, focus).map(ref::equals).orElse(false)) {
            return "配偶";
        }
        if (fatherOf(projectId, focus).map(ref::equals).orElse(false)) {
            return "父亲";
        }
        if (motherOf(projectId, focus).map(ref::equals).orElse(false)) {
            return "母亲";
        }
        if (childrenOf(projectId, focus).contains(ref)) {
            return "子女";
        }
        if (siblingsOf(projectId, focus).contains(ref)) {
            return "兄弟姐妹";
        }
        // 祖辈
        for (String parent : parentEdgesOf(projectId, focus).stream().map(ActorGrowthLineage::getFromRef).toList()) {
            if (fatherOf(projectId, parent).map(ref::equals).orElse(false)) {
                return "祖父";
            }
            if (motherOf(projectId, parent).map(ref::equals).orElse(false)) {
                return "祖母";
            }
            if (spouseOf(projectId, parent).map(ref::equals).orElse(false)) {
                return parentGender(projectId, parent).map(g -> "男".equals(g) ? "祖母" : "祖父").orElse("祖辈配偶");
            }
        }
        return "亲属";
    }

    private Optional<String> parentGender(Long projectId, String ref) {
        return characterRepository.findById(NpcRefs.idOf(ref)).map(ActorCharacter::getGender);
    }

    //
    // 遗产继承
    //

    /**
     * 继承顺序查询：配偶 → 长子/长女 → 父母 → 族中。
     *
     * @param projectId 项目 ID
     *
     * @param ref 亡者 npcRef
     *
     * @return 继承人 npcRef；无任何在世血亲/配偶 → null（=族中公产，不落个人）
     */
    public Optional<String> heirOf(Long projectId, String ref) {
        if (projectId == null || ref == null) {
            return Optional.empty();
        }
        // ① 配偶（存续婚姻边）
        Optional<String> spouse = spouseOf(projectId, ref);
        if (spouse.isPresent() && alive(projectId, spouse.get())) {
            return spouse;
        }
        // ② 长子/长女（子女按出生时刻正序取最先者——谱系边 gameSecond=出生时刻）
        List<String> children = childrenOf(projectId, ref);
        for (String child : children) {
            if (alive(projectId, child)) {
                return Optional.of(child);
            }
        }
        // ③ 父母
        for (ActorGrowthLineage pe : parentEdgesOf(projectId, ref)) {
            if (pe.getFromRef() != null && alive(projectId, pe.getFromRef())) {
                return Optional.of(pe.getFromRef());
            }
        }
        return Optional.empty();
    }

    /** 角色是否在世（有档案行且非 DECEASED 且未删）。 */
    public boolean alive(Long projectId, String ref) {
        ActorCharacter ch = characterRepository.findById(NpcRefs.idOf(ref)).orElse(null);
        return ch != null && ch.getProjectId().equals(projectId)
                && !"DECEASED".equalsIgnoreCase(ch.getStage())
                && (ch.getDeleted() == null || ch.getDeleted() == 0);
    }
}
