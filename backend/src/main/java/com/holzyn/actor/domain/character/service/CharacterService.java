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

import com.holzyn.actor.common.BizException;
import com.holzyn.actor.common.NpcRefs;
import com.holzyn.actor.domain.account.CurrentUserProvider;
import com.holzyn.actor.domain.agent.brain.service.BrainProfileService;
import com.holzyn.actor.domain.agent.learning.RelationDynamicsService;
import com.holzyn.actor.domain.character.dto.CharacterDTO;
import com.holzyn.actor.domain.npc.profile.entity.ActorCharacter;
import com.holzyn.actor.domain.npc.relation.entity.ActorCharacterRelation;
import com.holzyn.actor.domain.npc.profile.repository.ActorCharacterRepository;
import com.holzyn.actor.domain.npc.state.service.NpcActivityStateService;
import com.holzyn.actor.domain.project.entity.ActorProject;
import com.holzyn.actor.domain.character.vo.CharacterCardVO;
import com.holzyn.actor.domain.character.vo.CharacterRelationGraphVO;
import com.holzyn.actor.domain.character.vo.CharacterVO;
import com.holzyn.actor.domain.npc.relation.repository.ActorCharacterRelationRepository;
import com.holzyn.actor.domain.project.repository.ActorProjectRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 角色业务服务（A-）。
 * <p>职责：提供角色基础档案的增删改查，并基于「是否已有角色卡」为前端提供生成态标记。
 * 角色归属通过 角色→项目→用户 两级校验，越权访问返回 404。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CharacterService {

    private final ActorCharacterRepository characterRepository;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.holzyn.actor.domain.growth.persona.PersonaService personaService;
    private final ActorCharacterRelationRepository relationRepository;
    private final ActorCharacterRepository ordinaryNpcRepository;
    private final ActorProjectRepository projectRepository;
    private final CurrentUserProvider currentUserProvider;
    /** BrainProfile：新建角色自动建脑力配置行（ensureProfile 失败仅告警，不阻断创建） */
    private final BrainProfileService brainProfileService;

    /** 位置归一（字段注入保持构造器不变；null=归一关闭，不落 location_id） */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.holzyn.actor.domain.world.service.LocationNormalizationService locationNormalizationService;

    /** 沙盘状态服务（S2 npc:join/leave：角色创建/删除挂钩；字段注入保持构造器不变，存量单测零改动） */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.holzyn.actor.domain.sandbox.service.WorldSandboxStateService sandboxStateService;

    /**
     * NPC 作息运行态访问器。
     * <p><b>为什么必须有它</b>：关系拓扑图的"普通型 NPC 节点"要展示 {@code state} / {@code lastAction}
     * （最近作息与行动），而这两个运行态字段已随 {@code actor_profile_ordinary} 物理废弃迁入
     * {@code actor_state}（{@code activity_state} / {@code last_action}，运行态唯一家）——主表实体
     * {@link ActorCharacter} 上再无这两个字段，只能经本访问器读。</p>
     * <p>字段注入（非 final）保持既有构造器签名不变（存量单测零改动）；{@code null}=未装配 →
     * 节点回退默认态 {@code idle}/空串（零阻断）。</p>
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private NpcActivityStateService npcActivityStateService;

    /**
     * 关系拓扑节点「附加语义」解析器（UI V3.0 关系工作台新增）。
     * <p><b>为什么必须有它</b>：重做前拓扑节点只有姓名/重要度可编码，关系表为空时整块画布是
     * 一盘孤立圆点；而「谁和谁是一拨人」世界内核里本就有真实答案（中观组织 + 血缘谱系）。
     * 本解析器把归属/家族/所在地/档案速览一次带给前端，使默认视图能按世界事实分圈。</p>
     * <p>字段注入（非 final）保持既有构造器签名不变（存量单测零改动）；{@code null}=未装配 →
     * 节点 meta 全空（{@link com.holzyn.actor.domain.character.vo.CharacterRelationGraphVO.NodeMeta#EMPTY}），
     * 前端走职业/归属文本兜底分区，零阻断。</p>
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private CharacterRelationMetaResolver relationMetaResolver;

    /** 节点详细信息返回截断长度（避免全量 detail 撑爆拓扑接口 payload） */
    private static final int NODE_DETAIL_MAX = 300;

    /**
     * 查询项目下角色列表（含是否已生成角色卡标记）。
     *
     * @param projectId 项目 ID
     *
     * @return 角色 VO 列表
     */
    @Transactional(readOnly = true)
    public List<CharacterVO> list(Long projectId) {
        requireProject(projectId);
        List<ActorCharacter> chars = characterRepository.findByProjectIdAndDeletedOrderByIdAsc(projectId, 0);
        // 一次性批量查询哪些角色已生成角色卡，避免 N+1
        Set<Long> hasCard = chars.stream()
                .map(ActorCharacter::getId)
                .filter(id -> (personaService != null && personaService.latestPersonaJson(id).isPresent()))
                .collect(Collectors.toSet());
        return chars.stream().map(c -> CharacterVO.of(c, hasCard.contains(c.getId()))).toList();
    }

    /**
     * 创建角色。
     *
     * @param projectId 项目 ID
     *
     * @param dto 角色入参
     *
     * @return 创建后的角色 VO
     */
    @Transactional
    public CharacterVO create(Long projectId, CharacterDTO dto) {
        requireProject(projectId);
        ActorCharacter c = new ActorCharacter();
        c.setProjectId(projectId);
        apply(dto, c);
        if (c.getType() == null || c.getType().isBlank()) c.setType("special");
        // （阶段 A3）：手动新增角色未显式填重要度时按是否主角给默认（主角→4/其他→3），
        // 避免 prePersist 默认 1 < 3 → 永远走程序化兜底（AI 行动门槛 importance≥3，见 ActionEngine）。
        // 注意：必须用 DTO 原始值判断——实体字段带内联初始值（importance=1 / isProtagonist=0），
        // 读实体永远非 null，会误判为「显式提供」导致默认逻辑失效。
        c.setImportance(resolveDefaultImportance(dto.importance(), dto.isProtagonist()));
        ActorCharacter saved = characterRepository.save(c);
        // BrainProfile：新建角色自动建脑力配置行（特殊→full/auto(premium)/realtime；
        // 幂等 ensureProfile，失败仅告警不阻断创建——脑力配置非角色主数据）
        ensureProfileQuietly(saved.getId());
        // 「补充」关联：新角色创建后，扫描该项目关系表，把 from_name/to_name 匹配该角色名且 id=0
        // 的「幽灵关系」行自动回填为新角色 id（全局拓扑页「暂无具体信息→补充新增」后的全表关联）
        backfillRelationIdsByName(projectId, saved.getId(), saved.getName());
        // S2 沙盘 npc:join：新角色进入场景（字段注入，失败静默不阻断创建）
        try {
            if (sandboxStateService != null) {
                sandboxStateService.publishJoin(projectId, com.holzyn.actor.common.NpcRefs.of(saved.getId()), "special", saved.getName());
            }
        } catch (Exception e) {
            log.debug("[角色] 沙盘 join 广播失败（静默）: char={} : {}", saved.getId(), e.getMessage());
        }
        return CharacterVO.of(saved, false);
    }

    /**
     * 为新建特殊角色幂等建脑力配置行。
     * <p>不建行不阻断业务：ensureProfile 失败仅告警（对齐方案「新建接入失败仅告警」）。</p>
     *
     * @param characterId 角色 ID
     */
    private void ensureProfileQuietly(Long characterId) {
        try {
            brainProfileService.ensureProfile(BrainProfileService.TYPE_SPECIAL, characterId);
        } catch (Exception e) {
            // 脑力配置行建行失败不影响角色创建（仅告警，启动补行可兜底）
            log.warn("[脑力配置] 新建角色自动建行失败（不影响创建，启动补行兜底）: char={} : {}",
                    characterId, e.getMessage());
        }
    }

    /**
     * 解析角色默认重要度（阶段 A3，修）。
     * <p>仅当入参未提供重要度（null）时生效：主角（isProtagonist=1）→4（≥4 档，配得上主角的 AI 投入），
     * 非主角/未指定 →3（≥3 走 AI 行动决策）；显式提供的值原样保留（尊重用户选择，不覆盖）。
     * 纯静态函数，供单测独立验证。</p>
     *
     * @param importance 入参重要度（可空）
     *
     * @param isProtagonist 是否主角（0/1，可空）
     *
     * @return 最终重要度
     */
    static Integer resolveDefaultImportance(Integer importance, Integer isProtagonist) {
        if (importance != null) {
            return importance;
        }
        return Integer.valueOf(1).equals(isProtagonist) ? 4 : 3;
    }

    /**
     * 角色详情（归属校验）。
     *
     * @param id 角色主键
     *
     * @return 角色 VO
     */
    @Transactional(readOnly = true)
    public CharacterVO detail(Long id) {
        ActorCharacter c = requireOwned(id);
        boolean hasCard = (personaService != null && personaService.latestPersonaJson(id).isPresent());
        return CharacterVO.of(c, hasCard);
    }

    /**
     * 编辑角色（归属校验）。
     *
     * @param id 角色主键
     *
     * @param dto 角色入参
     *
     * @return 更新后的角色 VO
     */
    @Transactional
    public CharacterVO update(Long id, CharacterDTO dto) {
        ActorCharacter c = requireOwned(id);
        apply(dto, c);
        ActorCharacter saved = characterRepository.save(c);
        // 角色改名后同样按新名称回填关联（幂等，仅匹配 id=0 的幽灵行）
        backfillRelationIdsByName(saved.getProjectId(), saved.getId(), saved.getName());
        boolean hasCard = (personaService != null && personaService.latestPersonaJson(id).isPresent());
        return CharacterVO.of(saved, hasCard);
    }

    /**
     * 删除角色（软删除，归属校验）。
     *
     * @param id 角色主键
     */
    @Transactional
    public void delete(Long id) {
        ActorCharacter c = requireOwned(id);
        c.setDeleted(1);
        characterRepository.save(c);
        // S2 沙盘 npc:leave：角色离开场景（并移除状态行；字段注入，失败静默不阻断删除）
        try {
            if (sandboxStateService != null) {
                sandboxStateService.publishLeave(c.getProjectId(), com.holzyn.actor.common.NpcRefs.of(id), "special", c.getName());
            }
        } catch (Exception e) {
            log.debug("[角色] 沙盘 leave 广播失败（静默）: char={} : {}", id, e.getMessage());
        }
    }

    /**
     * 查询项目「角色关系拓扑图」数据（角色页「关系拓扑」Tab + 全局拓扑页共用）。
     * <p>节点 = ①项目下全部未删除特殊型角色（{@code type='special'}）+ ②项目下全部普通型 NPC
     * （{@code type='common'}，每个普通 NPC 以具体人名独立成节点）+ ③关系表引用但两表都不存在的
     * 「幽灵角色」（仅名称）；关系 = 项目下全部 actor_character_relation（端点按
     * 特殊型→普通型→幽灵 顺序解析为唯一 key，自环过滤）。</p>
     * <p>单表合并后①/②由 {@code actor_profile.type} 区分（原先是两张表），普通型节点的
     * state/lastAction 从 {@code actor_state} 批量读出（运行态唯一家）。</p>
     *
     * @param projectId 项目 ID
     *
     * @return 拓扑图 VO（nodes + relations）
     */
    @Transactional(readOnly = true)
    public CharacterRelationGraphVO relationsGraph(Long projectId) {
        requireProject(projectId);
        // ：单表合并后「NPC 角色」= type='special'、「普通型 NPC」= type='common'——这两条查询在
        // 之前各自打在不同的表上（actor_profile / actor_profile_ordinary），合并为同一个仓库后
        // 必须显式写回 type 过滤，否则同一行会既进 npcs（npc-{id} 节点）又进 ordinaryNpcs（crowd-{id}
        // 节点）：拓扑图每个 NPC 重复成两个节点，且 resolveKey 按 id 归属定 key 时永远命中前者，
        // crowd 节点全成孤儿。软删除一并过滤（与查角色侧口径一致）。
        List<ActorCharacter> npcs = characterRepository.findByProjectIdAndTypeAndDeletedOrderByIdAsc(
                projectId, NpcRefs.TYPE_SPECIAL, 0);
        List<ActorCharacter> ordinaryNpcs = ordinaryNpcRepository.findByProjectIdAndTypeAndDeletedOrderByIdAsc(
                projectId, NpcRefs.TYPE_COMMON, 0);
        List<ActorCharacterRelation> rels = relationRepository.findByProjectId(projectId);
        // ：普通型节点要展示的 state/lastAction 已迁入 actor_state（运行态唯一家，主表实体上
        // 再无这两列）——这里一次批量取全（单次查询）后交给纯映射函数，避免逐 NPC 查库（N+1）；
        // 访问器未装配/读失败 → 空 Map（节点回退 idle/空串，零阻断）
        Map<String, NpcActivityStateService.ActivityUpdate> runStateByRef = npcActivityStateService == null
                ? Map.of() : npcActivityStateService.activityByRef(projectId);
        // UI V3.0：一次批量解析「圈子（中观组织）+ 家族（血缘分量）+ 所在地/档案速览」附加语义
        // （3 条查询封顶、无 N+1；解析失败内部降级为空 Map，绝不阻断拓扑接口）
        Map<Long, CharacterRelationGraphVO.NodeMeta> metaByActorId = relationMetaResolver == null
                ? Map.of() : relationMetaResolver.resolve(projectId, allCharacters(npcs, ordinaryNpcs));
        return buildGraph(npcs, ordinaryNpcs, rels, runStateByRef, metaByActorId);
    }

    /**
     * 合并特殊型 + 普通型角色为一个列表（附加语义解析用；顺序稳定=特殊型在前）。
     *
     * @param npcs 特殊型角色
     *
     * @param ordinaryNpcs 普通型角色
     *
     * @return 合并后的角色列表
     */
    private static List<ActorCharacter> allCharacters(List<ActorCharacter> npcs, List<ActorCharacter> ordinaryNpcs) {
        List<ActorCharacter> all = new ArrayList<>(npcs == null ? List.of() : npcs);
        all.addAll(ordinaryNpcs == null ? List.of() : ordinaryNpcs);
        return all;
    }

    /**
     * 纯映射函数（可独立单测，不依赖数据库）：NPC/普通型 NPC/关系 → 拓扑图 VO。
     * <p>解析规则：① 关系端点 id&gt;0 且 NPC 存在 → npc-&lt;id&gt;；② 否则按名称先查 NPC 再查普通型 NPC
     * → npc-/crowd-；③ 两表都不存在 → ghost-&lt;名称&gt;（幽灵节点，仅名称兜底）；
     * ④ 端点无 id 且无名称（旧脏数据）或自环（from==to）→ 丢弃。</p>
     * <p>本重载保留给"无运行态上下文"的调用（存量单测/纯算法场景），普通型节点的
     * state/lastAction 取默认态（{@code idle}/空串）——生产路径请用带运行态的四参重载。</p>
     *
     * @param npcs 项目下全部特殊型角色
     *
     * @param ordinaryNpcs 项目下全部普通型 NPC
     *
     * @param rels 项目下全部关系
     *
     * @return 拓扑图 VO
     */
    static CharacterRelationGraphVO buildGraph(List<ActorCharacter> npcs, List<ActorCharacter> ordinaryNpcs,
                                               List<ActorCharacterRelation> rels) {
        return buildGraph(npcs, ordinaryNpcs, rels, Map.of());
    }

    /**
     * 纯映射函数：额外接收普通型的<b>运行态快照</b>（{@code actor_state} 读出的
     * state/lastAction，按 {@code npc:{id}} 索引）。
     * <p>之前 {@code state}/{@code last_action} 是主表实体字段，映射函数直接取即可；单表重构后
     * 运行态收敛到 {@code actor_state}（运行态唯一家），而本函数是纯函数（无仓库依赖，便于单测），
     * 故改由调用方<b>一次批量取全</b>后透传（见 {@link #relationsGraph}）——既保住纯函数可测性，
     * 又不引入 N+1。</p>
     *
     * @param npcs 项目下全部特殊型角色
     *
     * @param ordinaryNpcs 项目下全部普通型 NPC
     *
     * @param rels 项目下全部关系
     *
     * @param runStateByRef 普通型运行态（{@code npc:{id}} → state/lastAction；可空=全按默认态）
     *
     * @return 拓扑图 VO
     */
    static CharacterRelationGraphVO buildGraph(List<ActorCharacter> npcs, List<ActorCharacter> ordinaryNpcs,
                                               List<ActorCharacterRelation> rels,
                                               Map<String, NpcActivityStateService.ActivityUpdate> runStateByRef) {
        return buildGraph(npcs, ordinaryNpcs, rels, runStateByRef, Map.of());
    }

    /**
     * 纯映射函数：在运行态快照之外，额外接收<b>节点附加语义</b>（UI V3.0 关系工作台新增）。
     *
     * <p>附加语义 = 圈子（中观组织）/ 家族（血缘分量）/ 所在地 / 档案速览，由
     * {@link CharacterRelationMetaResolver} 一次批量解析后透传（本函数保持纯函数、零仓库依赖，
     * 便于单测）。未提供时节点 meta 全空 —— 旧调用方行为与旧字段语义完全不变。</p>
     *
     * @param npcs 项目下全部特殊型角色
     *
     * @param ordinaryNpcs 项目下全部普通型 NPC
     *
     * @param rels 项目下全部关系
     *
     * @param runStateByRef 普通型运行态（{@code npc:{id}} → state/lastAction；可空=全按默认态）
     *
     * @param metaByActorId 角色 ID → 附加语义（可空=全部空 meta）
     *
     * @return 拓扑图 VO
     */
    static CharacterRelationGraphVO buildGraph(List<ActorCharacter> npcs, List<ActorCharacter> ordinaryNpcs,
                                               List<ActorCharacterRelation> rels,
                                               Map<String, NpcActivityStateService.ActivityUpdate> runStateByRef,
                                               Map<Long, CharacterRelationGraphVO.NodeMeta> metaByActorId) {
        Map<String, NpcActivityStateService.ActivityUpdate> runStates =
                runStateByRef == null ? Map.of() : runStateByRef;
        Map<Long, CharacterRelationGraphVO.NodeMeta> metas =
                metaByActorId == null ? Map.of() : metaByActorId;
        Map<Long, ActorCharacter> npcById = npcs.stream()
                .collect(Collectors.toMap(ActorCharacter::getId, c -> c));
        Map<Long, ActorCharacter> ordinaryById = ordinaryNpcs.stream()
                .collect(Collectors.toMap(ActorCharacter::getId, m -> m));
        Map<String, ActorCharacter> npcByName = indexByName(npcs, ActorCharacter::getName);
        Map<String, ActorCharacter> ordinaryByName = indexByName(ordinaryNpcs, ActorCharacter::getName);

        List<CharacterRelationGraphVO.NodeVO> nodes = new ArrayList<>();
        // NPC 节点
        for (ActorCharacter c : npcs) {
            nodes.add(new CharacterRelationGraphVO.NodeVO(
                    "npc-" + c.getId(), c.getName(), "npc", c.getType(), c.getImportance(), c.getIsProtagonist(),
                    c.getTitle(), truncate(c.getDetail(), NODE_DETAIL_MAX), null, null, null, null,
                    metaOf(metas, c.getId())));
        }
        // 普通型 NPC 节点（每个普通 NPC 以具体人名独立成节点）
        for (ActorCharacter m : ordinaryNpcs) {
            // ：state/lastAction 从 actor_state 快照取（主表实体已无这两列）——无行/取不到时
            // 回退默认态（idle/空串，零阻断），绝不让某个 NPC 缺状态行拖垮整个拓扑接口
            String ref = NpcRefs.of(m.getId());
            NpcActivityStateService.ActivityUpdate rs = ref == null ? null : runStates.get(ref);
            String runState = rs == null || rs.state() == null || rs.state().isBlank()
                    ? NpcActivityStateService.DEFAULT_ACTIVITY : rs.state();
            String runLastAction = rs == null || rs.lastAction() == null ? "" : rs.lastAction();
            nodes.add(new CharacterRelationGraphVO.NodeVO(
                    "crowd-" + m.getId(), m.getName(), "crowd", null, null, null, null,
                    truncate(m.getDetail(), NODE_DETAIL_MAX), m.getAffiliation(), m.getOccupation(),
                    runState, runLastAction, metaOf(metas, m.getId())));
        }

        // 幽灵节点去重收集（key -> 名称）
        Map<String, String> ghostIndex = new LinkedHashMap<>();
        List<CharacterRelationGraphVO.EdgeVO> edges = new ArrayList<>();
        for (ActorCharacterRelation r : rels) {
            // ：端点解析优先正式 ref（special:{id}/crowd:{id}，修 幽灵歧义），回退旧 id/名称路径
            String fromKey = resolveKey(r.getFromRef(), r.getFromCharacterId(), r.getFromName(),
                    npcById, ordinaryById, npcByName, ordinaryByName, ghostIndex);
            String toKey = resolveKey(r.getToRef(), r.getToCharacterId(), r.getToName(),
                    npcById, ordinaryById, npcByName, ordinaryByName, ghostIndex);
            if (fromKey == null || toKey == null || fromKey.equals(toKey)) continue;
            edges.add(new CharacterRelationGraphVO.EdgeVO(
                    r.getId(), fromKey, toKey,
                    nameOf(r.getFromRef(), r.getFromCharacterId(), r.getFromName(), npcById),
                    nameOf(r.getToRef(), r.getToCharacterId(), r.getToName(), npcById),
                    r.getRelationType(), r.getDescription(),
                    r.getFromRef(), r.getToRef(),
                    r.getAffinity(), r.getTrust(), r.getRelationState(),
                    RelationDynamicsService.stateLabel(r.getRelationState()),
                    r.getLastInteractionAt() == null ? null : r.getLastInteractionAt().toString()));
        }
        // 幽灵节点收尾追加
        ghostIndex.forEach((key, name) -> nodes.add(new CharacterRelationGraphVO.NodeVO(
                key, name, "ghost", null, null, null, null, null, null, null, null, null,
                CharacterRelationGraphVO.NodeMeta.EMPTY)));

        return new CharacterRelationGraphVO(nodes, edges);
    }

    /**
     * 节点附加语义取值（缺失一律回退空对象，前端无需判空）。
     *
     * @param metas 角色 ID → 附加语义
     *
     * @param actorId 角色 ID
     *
     * @return 附加语义（永不为 null）
     */
    private static CharacterRelationGraphVO.NodeMeta metaOf(
            Map<Long, CharacterRelationGraphVO.NodeMeta> metas, Long actorId) {
        if (actorId == null) {
            return CharacterRelationGraphVO.NodeMeta.EMPTY;
        }
        CharacterRelationGraphVO.NodeMeta meta = metas.get(actorId);
        return meta == null ? CharacterRelationGraphVO.NodeMeta.EMPTY : meta;
    }

    /** 按名称建索引（名称去空白后首条胜出；空名跳过） */
    private static <T> Map<String, T> indexByName(List<T> list, Function<T, String> nameFn) {
        Map<String, T> index = new LinkedHashMap<>();
        for (T item : list) {
            String n = nameFn.apply(item);
            if (n == null || n.isBlank()) continue;
            index.putIfAbsent(n.trim(), item);
        }
        return index;
    }

    /**
     * 解析关系端点 → 节点 key（支持正式 ref）：
     * ① from_ref=npc:{id} 且 NPC 存在 → npc-&lt;id&gt;（普通型命中 → crowd-&lt;id&gt;）；
     * ③ 旧路径 id&gt;0 且 NPC 存在 → npc-&lt;id&gt;；④ 否则按名称查 NPC/普通型 NPC → 对应 key；
     * ⑤ 都不存在 → ghost-&lt;名称&gt;（并登记幽灵节点）；无法解析返回 null。
     * <p>npc:{id} 不再编码类型，此处按意图 A 处理（按 id 解析档案行，类型由行内 type 体现）。</p>
     */
    private static String resolveKey(String ref, Long id, String name, Map<Long, ActorCharacter> npcById,
                                     Map<Long, ActorCharacter> ordinaryById,
                                     Map<String, ActorCharacter> npcByName, Map<String, ActorCharacter> ordinaryByName,
                                     Map<String, String> ghostIndex) {
        // ① 正式 ref 优先（修：重名端点以 npc:{id} 精确区分）
        // ：npc:{id} 不再编码类型，此处按意图 A 处理（先解析 actor_profile.id，再按 id 归属定节点 key）
        Long rid = NpcRefs.idOf(ref);
        if (rid != null) {
            if (npcById.containsKey(rid)) {
                return "npc-" + rid;
            }
            if (ordinaryById.containsKey(rid)) {
                return "crowd-" + rid;
            }
            // 角色已删除/不存在 → 回退名称（下方统一处理）
        }
        // ③ 旧路径：id>0 且 NPC 存在
        if (id != null && id > 0 && npcById.containsKey(id)) {
            return "npc-" + id;
        }
        // ④ 名称查 NPC/普通型 NPC
        String n = name == null ? "" : name.trim();
        if (n.isEmpty()) return null;
        ActorCharacter npc = npcByName.get(n);
        if (npc != null) return "npc-" + npc.getId();
        ActorCharacter ordinary = ordinaryByName.get(n);
        if (ordinary != null) return "crowd-" + ordinary.getId();
        // ⑤ 幽灵
        String key = "ghost-" + n;
        ghostIndex.putIfAbsent(key, n);
        return key;
    }

    /** 端点显示名（正式 ref 命中取角色名；否则 id 命中取角色名；再否则取存储名称） */
    private static String nameOf(String ref, Long id, String name, Map<Long, ActorCharacter> npcById) {
        // ：npc:{id} 不再编码类型，此处按意图 A 处理（统一前缀按 id 取档案名）
        Long rid = NpcRefs.idOf(ref);
        if (rid != null) {
            ActorCharacter c = npcById.get(rid);
            if (c != null) return c.getName();
        }
        if (id != null && id > 0) {
            ActorCharacter c = npcById.get(id);
            if (c != null) return c.getName();
        }
        return name == null ? "" : name;
    }

    /** 安全解析 id（非法返回 null） */
    private static Long parseId(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        try {
            long v = Long.parseLong(s.trim());
            return v > 0 ? v : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 字符串截断（null 安全） */
    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }

    /**
     * 「补充」关联：按角色名全表扫描项目关系表，把 from_name/to_name 匹配该角色名且 id 为 0 的
     * 「幽灵关系」行回填为新角色 id（全局拓扑页「暂无具体信息→补充新增」创建角色后的自动关联）。
     * 幂等：仅回填仍为幽灵（id=0）的行，已有关联不覆盖。
     *
     * @param projectId 项目 ID
     *
     * @param characterId 新角色 ID
     *
     * @param name 新角色名（按名匹配）
     */
    @Transactional
    public void backfillRelationIdsByName(Long projectId, Long characterId, String name) {
        if (name == null || name.isBlank() || characterId == null) return;
        List<ActorCharacterRelation> rels = relationRepository.findByProjectId(projectId);
        boolean changed = false;
        for (ActorCharacterRelation r : rels) {
            changed |= backfillRelation(r, characterId, name);
        }
        if (changed) relationRepository.saveAll(rels);
    }

    /**
     * 纯函数（可独立单测）：把单条「幽灵关系」按名称回填为新角色 id。
     * <p>规则：from_name/to_name 匹配新角色名且对应 id 为 0（幽灵）时回填；自环（新角色两端都命中）
     * 仅回填一端；已有关联（id&gt;0）不覆盖。</p>
     *
     * @param r 关系实体（会被就地修改）
     *
     * @param characterId 新角色 ID
     *
     * @param name 新角色名
     *
     * @return 是否发生修改
     */
    static boolean backfillRelation(ActorCharacterRelation r, Long characterId, String name) {
        boolean changed = false;
        if ((r.getFromCharacterId() == null || r.getFromCharacterId() == 0)
                && name.equals(r.getFromName())
                && !characterId.equals(r.getToCharacterId())) {
            r.setFromCharacterId(characterId);
            changed = true;
        }
        if ((r.getToCharacterId() == null || r.getToCharacterId() == 0)
                && name.equals(r.getToName())
                && !characterId.equals(r.getFromCharacterId())) {
            r.setToCharacterId(characterId);
            changed = true;
        }
        return changed;
    }

    /**
     * 校验项目归属当前用户（供关系生成服务等复用；越权抛 404）。
     *
     * @param projectId 项目 ID
     */
    public void requireProjectOwned(Long projectId) {
        requireProject(projectId);
    }

    /**
     * 按 id + 当前用户归属查询角色（两级归属校验）。
     * <p>默认实现委托显式 userId 版本（取当前线程 SecurityContext 用户），仅可在请求线程调用；
     * 异步/定时线程（如世界模拟、after_dialog 行动评估）必须用
     * {@link #requireOwned(Long, Long)} 并显式传入从请求线程捕获的 userId，
     * 否则 currentUserId() 会回退演示用户（id=1），真实用户角色被误判无权访问。</p>
     *
     * @param id 角色主键
     *
     * @return 角色实体
     */
    public ActorCharacter requireOwned(Long id) {
        return requireOwned(id, currentUserProvider.currentUserId());
    }

    /**
     * 按 id + 显式归属用户查询角色（两级归属校验，异步/定时线程安全）。
     *
     * @param id 角色主键
     *
     * @param userId 归属用户 ID（调用方保证为请求线程解析的真实用户）
     *
     * @return 角色实体
     */
    public ActorCharacter requireOwned(Long id, Long userId) {
        ActorCharacter c = characterRepository.findById(id)
                .filter(x -> Integer.valueOf(0).equals(x.getDeleted()))
                .orElseThrow(() -> new BizException(404, "角色不存在或无权访问"));
        requireProject(c.getProjectId(), userId);
        return c;
    }

    /**
     * 校验项目归属当前用户（越权抛 404）。
     *
     * @param projectId 项目 ID
     */
    private void requireProject(Long projectId) {
        requireProject(projectId, currentUserProvider.currentUserId());
    }

    /**
     * 校验项目归属指定用户（越权抛 404；异步/定时线程用显式 userId 版本）。
     *
     * @param projectId 项目 ID
     *
     * @param userId 归属用户 ID
     */
    private void requireProject(Long projectId, Long userId) {
        ActorProject p = projectRepository.findByIdAndUserIdAndDeleted(projectId, userId, 0)
                .orElseThrow(() -> new BizException(404, "项目不存在或无权访问"));
    }

    /**
     * 将 DTO 字段应用到角色实体（创建/更新共用）。
     *
     * @param dto 角色入参
     *
     * @param c 角色实体
     */
    private void apply(CharacterDTO dto, ActorCharacter c) {
        if (dto.type() != null && !dto.type().isBlank()) c.setType(dto.type());
        c.setName(dto.name());
        c.setTitle(dto.title());
        c.setDetail(dto.detail());
        c.setAvatarUrl(dto.avatarUrl());
        if (dto.isProtagonist() != null) c.setIsProtagonist(dto.isProtagonist());
        if (dto.importance() != null) c.setImportance(dto.importance());
        // 位置归一：按地点表匹配落 location_id + 规范名快照；未命中保留自由文本兜底
        if (dto.location() != null) {
            String text = dto.location().trim();
            c.setLocation(text.isEmpty() ? null : text);
            c.setLocationId(normalizeLocationId(c.getProjectId(), c.getLocation()));
        }
    }

    /**
     * 位置归一落地点 ID（；归一服务缺失/未命中返回 null，保留自由文本兜底）。
     *
     * @param projectId 项目 ID
     *
     * @param location 自由文本位置（可空）
     *
     * @return 地点表 ID 或 null
     */
    private Long normalizeLocationId(Long projectId, String location) {
        if (locationNormalizationService == null || projectId == null || location == null || location.isBlank()) {
            return null;
        }
        try {
            return locationNormalizationService.normalizedId(projectId, location);
        } catch (Exception e) {
            log.warn("[位置归一] 角色位置归一失败（保留自由文本）: project={} : {}", projectId, e.getMessage());
            return null;
        }
    }
}
