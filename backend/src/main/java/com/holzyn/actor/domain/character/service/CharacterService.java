package com.holzyn.actor.domain.character.service;

import com.holzyn.actor.common.BizException;
import com.holzyn.actor.domain.account.CurrentUserProvider;
import com.holzyn.actor.domain.character.dto.CharacterDTO;
import com.holzyn.actor.domain.character.entity.ActorCharacter;
import com.holzyn.actor.domain.character.entity.ActorCharacterCard;
import com.holzyn.actor.domain.character.entity.ActorCharacterRelation;
import com.holzyn.actor.domain.crowd.entity.ActorOrdinaryNpc;
import com.holzyn.actor.domain.crowd.repository.ActorOrdinaryNpcRepository;
import com.holzyn.actor.domain.project.entity.ActorProject;
import com.holzyn.actor.domain.character.vo.CharacterCardVO;
import com.holzyn.actor.domain.character.vo.CharacterRelationGraphVO;
import com.holzyn.actor.domain.character.vo.CharacterVO;
import com.holzyn.actor.domain.character.repository.ActorCharacterCardRepository;
import com.holzyn.actor.domain.character.repository.ActorCharacterRelationRepository;
import com.holzyn.actor.domain.character.repository.ActorCharacterRepository;
import com.holzyn.actor.domain.project.repository.ActorProjectRepository;
import lombok.RequiredArgsConstructor;
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
 * 角色业务服务（A-C2）。
 * <p>职责：提供角色基础档案的增删改查，并基于「是否已有角色卡」为前端提供生成态标记。
 * 角色归属通过 角色→项目→用户 两级校验，越权访问返回 404。</p>
 * <p>所属模块：service/character（角色/角色卡子域）</p>
 */
@Service
@RequiredArgsConstructor
public class CharacterService {

    private final ActorCharacterRepository characterRepository;
    private final ActorCharacterCardRepository cardRepository;
    private final ActorCharacterRelationRepository relationRepository;
    private final ActorOrdinaryNpcRepository ordinaryNpcRepository;
    private final ActorProjectRepository projectRepository;
    private final CurrentUserProvider currentUserProvider;

    /** 节点详细信息返回截断长度（避免全量 detail 撑爆拓扑接口 payload） */
    private static final int NODE_DETAIL_MAX = 300;

    /**
     * 查询项目下角色列表（含是否已生成角色卡标记）。
     *
     * @param projectId 项目 ID
     * @return 角色 VO 列表
     */
    @Transactional(readOnly = true)
    public List<CharacterVO> list(Long projectId) {
        requireProject(projectId);
        List<ActorCharacter> chars = characterRepository.findByProjectIdAndDeletedOrderByIdAsc(projectId, 0);
        // 一次性批量查询哪些角色已生成角色卡，避免 N+1
        Set<Long> hasCard = chars.stream()
                .map(ActorCharacter::getId)
                .filter(id -> cardRepository.findTopByCharacterIdOrderByVersionDesc(id).isPresent())
                .collect(Collectors.toSet());
        return chars.stream().map(c -> CharacterVO.of(c, hasCard.contains(c.getId()))).toList();
    }

    /**
     * 创建角色。
     *
     * @param projectId 项目 ID
     * @param dto       角色入参
     * @return 创建后的角色 VO
     */
    @Transactional
    public CharacterVO create(Long projectId, CharacterDTO dto) {
        requireProject(projectId);
        ActorCharacter c = new ActorCharacter();
        c.setProjectId(projectId);
        apply(dto, c);
        if (c.getType() == null || c.getType().isBlank()) c.setType("special");
        ActorCharacter saved = characterRepository.save(c);
        // 「补充」关联：新角色创建后，扫描该项目关系表，把 from_name/to_name 匹配该角色名且 id=0
        // 的「幽灵关系」行自动回填为新角色 id（全局拓扑页「暂无具体信息→补充新增」后的全表关联）
        backfillRelationIdsByName(projectId, saved.getId(), saved.getName());
        return CharacterVO.of(saved, false);
    }

    /**
     * 角色详情（归属校验）。
     *
     * @param id 角色主键
     * @return 角色 VO
     */
    @Transactional(readOnly = true)
    public CharacterVO detail(Long id) {
        ActorCharacter c = requireOwned(id);
        boolean hasCard = cardRepository.findTopByCharacterIdOrderByVersionDesc(id).isPresent();
        return CharacterVO.of(c, hasCard);
    }

    /**
     * 编辑角色（归属校验）。
     *
     * @param id  角色主键
     * @param dto 角色入参
     * @return 更新后的角色 VO
     */
    @Transactional
    public CharacterVO update(Long id, CharacterDTO dto) {
        ActorCharacter c = requireOwned(id);
        apply(dto, c);
        ActorCharacter saved = characterRepository.save(c);
        // 角色改名后同样按新名称回填关联（幂等，仅匹配 id=0 的幽灵行）
        backfillRelationIdsByName(saved.getProjectId(), saved.getId(), saved.getName());
        boolean hasCard = cardRepository.findTopByCharacterIdOrderByVersionDesc(id).isPresent();
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
    }

    /**
     * 查询项目「角色关系拓扑图」数据（角色页「关系拓扑」Tab + 全局拓扑页共用）。
     * <p>节点 = ①项目下全部未删除 NPC 角色 + ②项目下全部普通型 NPC（每个普通 NPC 以具体人名
     * 独立成节点）+ ③关系表引用但两表都不存在的「幽灵角色」（仅名称）；关系 = 项目下全部
     * actor_character_relation（端点按 NPC→普通型 NPC→幽灵 顺序解析为唯一 key，自环过滤）。</p>
     *
     * @param projectId 项目 ID
     * @return 拓扑图 VO（nodes + relations）
     */
    @Transactional(readOnly = true)
    public CharacterRelationGraphVO relationsGraph(Long projectId) {
        requireProject(projectId);
        List<ActorCharacter> npcs = characterRepository.findByProjectIdAndDeletedOrderByIdAsc(projectId, 0);
        List<ActorOrdinaryNpc> ordinaryNpcs = ordinaryNpcRepository.findByProjectIdOrderByIdAsc(projectId);
        List<ActorCharacterRelation> rels = relationRepository.findByProjectId(projectId);
        return buildGraph(npcs, ordinaryNpcs, rels);
    }

    /**
     * 纯映射函数（可独立单测，不依赖数据库）：NPC/普通型 NPC/关系 → 拓扑图 VO。
     * <p>解析规则：① 关系端点 id&gt;0 且 NPC 存在 → npc-&lt;id&gt;；② 否则按名称先查 NPC 再查普通型 NPC
     * → npc-/crowd-；③ 两表都不存在 → ghost-&lt;名称&gt;（幽灵节点，仅名称兜底）；
     * ④ 端点无 id 且无名称（旧脏数据）或自环（from==to）→ 丢弃。</p>
     *
     * @param npcs         项目下全部未删除 NPC 角色
     * @param ordinaryNpcs 项目下全部普通型 NPC（单表扁平）
     * @param rels         项目下全部关系
     * @return 拓扑图 VO
     */
    static CharacterRelationGraphVO buildGraph(List<ActorCharacter> npcs, List<ActorOrdinaryNpc> ordinaryNpcs,
                                               List<ActorCharacterRelation> rels) {
        Map<Long, ActorCharacter> npcById = npcs.stream()
                .collect(Collectors.toMap(ActorCharacter::getId, c -> c));
        Map<String, ActorCharacter> npcByName = indexByName(npcs, ActorCharacter::getName);
        Map<String, ActorOrdinaryNpc> ordinaryByName = indexByName(ordinaryNpcs, ActorOrdinaryNpc::getName);

        List<CharacterRelationGraphVO.NodeVO> nodes = new ArrayList<>();
        // NPC 节点
        for (ActorCharacter c : npcs) {
            nodes.add(new CharacterRelationGraphVO.NodeVO(
                    "npc-" + c.getId(), c.getName(), "npc", c.getType(), c.getImportance(), c.getIsProtagonist(),
                    c.getTitle(), truncate(c.getDetail(), NODE_DETAIL_MAX), null, null, null, null));
        }
        // 普通型 NPC 节点（每个普通 NPC 以具体人名独立成节点）
        for (ActorOrdinaryNpc m : ordinaryNpcs) {
            nodes.add(new CharacterRelationGraphVO.NodeVO(
                    "crowd-" + m.getId(), m.getName(), "crowd", null, null, null, null,
                    truncate(m.getDetail(), NODE_DETAIL_MAX), m.getAffiliation(), m.getOccupation(), m.getState(), m.getLastAction()));
        }

        // 幽灵节点去重收集（key -> 名称）
        Map<String, String> ghostIndex = new LinkedHashMap<>();
        List<CharacterRelationGraphVO.EdgeVO> edges = new ArrayList<>();
        for (ActorCharacterRelation r : rels) {
            String fromKey = resolveKey(r.getFromCharacterId(), r.getFromName(), npcById, npcByName, ordinaryByName, ghostIndex);
            String toKey = resolveKey(r.getToCharacterId(), r.getToName(), npcById, npcByName, ordinaryByName, ghostIndex);
            if (fromKey == null || toKey == null || fromKey.equals(toKey)) continue;
            edges.add(new CharacterRelationGraphVO.EdgeVO(
                    r.getId(), fromKey, toKey,
                    nameOf(r.getFromCharacterId(), r.getFromName(), npcById),
                    nameOf(r.getToCharacterId(), r.getToName(), npcById),
                    r.getRelationType(), r.getDescription()));
        }
        // 幽灵节点收尾追加
        ghostIndex.forEach((key, name) -> nodes.add(new CharacterRelationGraphVO.NodeVO(
                key, name, "ghost", null, null, null, null, null, null, null, null, null)));

        return new CharacterRelationGraphVO(nodes, edges);
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
     * 解析关系端点 → 节点 key：id&gt;0 且 NPC 存在 → npc-&lt;id&gt;；否则按名称查 NPC/普通型 NPC → 对应 key；
     * 都不存在 → ghost-&lt;名称&gt;（并登记幽灵节点）；无法解析返回 null。
     */
    private static String resolveKey(Long id, String name, Map<Long, ActorCharacter> npcById,
                                     Map<String, ActorCharacter> npcByName, Map<String, ActorOrdinaryNpc> ordinaryByName,
                                     Map<String, String> ghostIndex) {
        if (id != null && id > 0 && npcById.containsKey(id)) {
            return "npc-" + id;
        }
        String n = name == null ? "" : name.trim();
        if (n.isEmpty()) return null;
        ActorCharacter npc = npcByName.get(n);
        if (npc != null) return "npc-" + npc.getId();
        ActorOrdinaryNpc ordinary = ordinaryByName.get(n);
        if (ordinary != null) return "crowd-" + ordinary.getId();
        String key = "ghost-" + n;
        ghostIndex.putIfAbsent(key, n);
        return key;
    }

    /** 端点显示名：NPC 存在取角色名，否则取存储名称（无则空） */
    private static String nameOf(Long id, String name, Map<Long, ActorCharacter> npcById) {
        if (id != null && id > 0) {
            ActorCharacter c = npcById.get(id);
            if (c != null) return c.getName();
        }
        return name == null ? "" : name;
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
     * @param projectId   项目 ID
     * @param characterId 新角色 ID
     * @param name        新角色名（按名匹配）
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
     * @param r           关系实体（会被就地修改）
     * @param characterId 新角色 ID
     * @param name        新角色名
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
     * @return 角色实体
     */
    public ActorCharacter requireOwned(Long id) {
        return requireOwned(id, currentUserProvider.currentUserId());
    }

    /**
     * 按 id + 显式归属用户查询角色（两级归属校验，异步/定时线程安全）。
     *
     * @param id     角色主键
     * @param userId 归属用户 ID（调用方保证为请求线程解析的真实用户）
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
     * @param userId    归属用户 ID
     */
    private void requireProject(Long projectId, Long userId) {
        ActorProject p = projectRepository.findByIdAndUserIdAndDeleted(projectId, userId, 0)
                .orElseThrow(() -> new BizException(404, "项目不存在或无权访问"));
    }

    /**
     * 将 DTO 字段应用到角色实体（创建/更新共用）。
     *
     * @param dto 角色入参
     * @param c   角色实体
     */
    private void apply(CharacterDTO dto, ActorCharacter c) {
        if (dto.type() != null && !dto.type().isBlank()) c.setType(dto.type());
        c.setName(dto.name());
        c.setTitle(dto.title());
        c.setDetail(dto.detail());
        c.setAvatarUrl(dto.avatarUrl());
        if (dto.isProtagonist() != null) c.setIsProtagonist(dto.isProtagonist());
        if (dto.importance() != null) c.setImportance(dto.importance());
    }
}