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

package com.holzyn.actor.domain.dialogue.service;

import com.holzyn.actor.common.BizException;
import com.holzyn.actor.common.NpcRefs;
import com.holzyn.actor.domain.dialogue.entity.ActorDialogueSession;
import com.holzyn.actor.domain.dialogue.entity.ActorTalkGroupMember;
import com.holzyn.actor.domain.dialogue.repository.ActorTalkGroupMemberRepository;
import com.holzyn.actor.domain.dialogue.vo.DialogueGroupMemberVO;
import com.holzyn.actor.domain.dialogue.vo.DialogueSessionVO;
import com.holzyn.actor.domain.npc.profile.entity.ActorCharacter;
import com.holzyn.actor.domain.npc.profile.repository.ActorCharacterRepository;
import com.holzyn.actor.domain.npc.state.entity.ActorNpcState;
import com.holzyn.actor.domain.npc.state.service.NpcStateService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 对谈群聊场景化成员服务（零 AI，短事务）。
 * <p>职责（设计 §6.4/§4）：<b>场景化成员</b>——群聊会话创建（type=group + 场景地点）、
 * 默认成员 = 冻结时刻同一地点在场的角色（present 来源，读角色冻结位置，世界已暂停=冻结）、
 * <b>换场</b>（切地点重算在场，manual 拉人成员保留）、<b>拉人</b>（临时加入，manual 标记）、
 * 移除成员与成员列表。</p>
 * <p><b>写入口纪律</b>：群聊成员（actor_talk_group_member）唯一写入口 = 本服务；
 * 会话行（location_id/location_name）写入口 = {@link DialogueSessionService#updateLocation}
 * （对谈会话写入口不变），本服务只编排两处落点。</p>
 * <p><b>fail-open</b>：角色主表/仓库缺失 → 在场成员为空表（会话仍可创建/换场，可后续拉人）；
 * 地点解析失败 → location 置空。</p>
 */
@Slf4j
@Service
public class DialogueGroupService {

    /** 成员来源：在场（换场重算） */
    public static final String SOURCE_PRESENT = "present";

    /** 成员来源：拉人（换场保留） */
    public static final String SOURCE_MANUAL = "manual";

    /** 入睡关键词（清醒过滤：成员 lastAction 命中任一即视为睡着，复用既有群聊护栏经验） */
    private static final List<String> SLEEP_KEYWORDS = List.of(
            "入睡", "沉睡", "就寝", "歇息", "卧床", "酣睡", "安睡", "睡着", "睡觉", "睡下", "入眠", "打盹", "小憩");

    /** 会话服务（群聊会话创建 + 场景地点写入口） */
    @Lazy
    @Autowired(required = false)
    private DialogueSessionService sessionService;

    /** 群聊成员仓库 */
    @Autowired(required = false)
    private ActorTalkGroupMemberRepository memberRepository;

    /** 角色主表仓库（在场计算/角色名/归属校验） */
    @Autowired(required = false)
    private ActorCharacterRepository characterRepository;

    /** 世界地点仓库（第 61 轮冒烟抓修：地点名主源——换场到无角色在场地点的名称解析） */
    @Autowired(required = false)
    private com.holzyn.actor.domain.world.repository.WorldLocationRepository worldLocationRepository;

    /** NPC 状态服务（清醒过滤只读；null=不过滤 fail-open） */
    @Lazy
    @Autowired(required = false)
    private NpcStateService npcStateService;

    /**
     * 创建群聊会话（type=group，场景化成员）。
     * <p>流程：{@link DialogueSessionService#create} 建会话（进入即冻结，含冻结时刻/世界动态行）
     * → 解析场景地点（显式或自动选在场最多的地点）→ 写会话 location → 计算并落在场成员。</p>
     *
     * @param projectId  项目 ID
     * @param userId     用户 ID
     * @param locationId 场景地点 ID（可空=自动选在场最多的地点；无地点则空成员）
     * @param title      会话标题（可空）
     *
     * @return 群聊会话视图（含 locationName）
     */
    @Transactional
    public DialogueSessionVO createGroup(Long projectId, Long userId, Long locationId, String title) {
        DialogueSessionVO created = sessionService.create(projectId, userId, "group", null, title);
        resolveAndApplyLocation(projectId, created.id(), locationId);
        initPresentMembers(projectId, created.id());
        return sessionService.get(projectId, created.id());
    }

    /**
     * 换场（切地点重算在场成员）。
     * <p>语义：更新会话 location → 删除既有 present 成员并按新地点重算落库；<b>manual 拉人成员保留</b>。</p>
     *
     * @param projectId  项目 ID
     * @param sessionId  会话 ID（须为群聊）
     * @param locationId 新地点 ID（可空=自动选在场最多的地点）
     *
     * @return 更新后的会话视图
     */
    @Transactional
    public DialogueSessionVO changeLocation(Long projectId, Long sessionId, Long locationId) {
        requireGroup(projectId, sessionId);
        resolveAndApplyLocation(projectId, sessionId, locationId);
        replacePresentMembers(projectId, sessionId);
        return sessionService.get(projectId, sessionId);
    }

    /**
     * 拉人（临时加入，manual 标记；换场保留）。
     *
     * @param projectId   项目 ID
     * @param sessionId   会话 ID（须为群聊）
     * @param characterRef 角色引用（npc:{id}）
     *
     * @return 拉人后的完整成员列表
     */
    @Transactional
    public List<DialogueGroupMemberVO> invite(Long projectId, Long sessionId, String characterRef) {
        requireGroup(projectId, sessionId);
        if (characterRef == null || characterRef.isBlank()) {
            throw new BizException(400, "请指定要拉入的角色");
        }
        Long id = NpcRefs.idOf(characterRef);
        if (id == null || characterRepository == null) {
            throw new BizException(404, "角色不存在");
        }
        ActorCharacter ch = characterRepository.findByIdAndProjectId(id, projectId)
                .orElseThrow(() -> new BizException(404, "角色不存在或不属于该项目"));
        if (memberRepository.findBySessionIdAndCharacterRef(sessionId, characterRef).isPresent()) {
            throw new BizException(400, "该角色已在群聊中");
        }
        ActorTalkGroupMember m = new ActorTalkGroupMember();
        m.setSessionId(sessionId);
        m.setCharacterRef(characterRef);
        m.setCharacterName(ch.getName());
        m.setSource(SOURCE_MANUAL);
        m.setCreatedAt(LocalDateTime.now());
        memberRepository.save(m);
        return members(projectId, sessionId);
    }

    /**
     * 移除成员（在场或拉人均可；在场成员换场时会按地点重算回来）。
     *
     * @param projectId   项目 ID
     * @param sessionId   会话 ID（须为群聊）
     * @param characterRef 角色引用（npc:{id}）
     */
    @Transactional
    public void removeMember(Long projectId, Long sessionId, String characterRef) {
        requireGroup(projectId, sessionId);
        ActorTalkGroupMember m = memberRepository.findBySessionIdAndCharacterRef(sessionId, characterRef)
                .orElseThrow(() -> new BizException(404, "该角色不在群聊中"));
        memberRepository.delete(m);
    }

    /**
     * 群聊成员列表（present 在前、manual 在后；各来源内按加入序）。
     *
     * @param projectId 项目 ID
     * @param sessionId 会话 ID
     *
     * @return 成员视图列表
     */
    public List<DialogueGroupMemberVO> members(Long projectId, Long sessionId) {
        requireGroup(projectId, sessionId);
        return memberRepository.findBySessionIdOrderByIdAsc(sessionId).stream()
                .sorted(Comparator.comparingInt((ActorTalkGroupMember m) ->
                        SOURCE_MANUAL.equals(m.getSource()) ? 1 : 0)
                        .thenComparingLong(ActorTalkGroupMember::getId))
                .map(this::toVO)
                .toList();
    }

    /**
     * 清醒成员列表（M5 编排/开场共用；复用既有群聊护栏「清醒过滤」经验）。
     * <p>过滤：NPC 状态缺失 → 视为清醒（fail-open）；activityState 命中
     * rest/sleep/睡/休息 或 lastAction 命中入睡关键词 → 视为睡着跳过。</p>
     *
     * @param projectId 项目 ID
     * @param sessionId 会话 ID
     *
     * @return 清醒成员视图列表（状态服务未装配=全部成员）
     */
    public List<DialogueGroupMemberVO> awakeMembers(Long projectId, Long sessionId) {
        List<DialogueGroupMemberVO> all = members(projectId, sessionId);
        if (npcStateService == null) {
            return all;
        }
        List<DialogueGroupMemberVO> out = new ArrayList<>();
        for (DialogueGroupMemberVO m : all) {
            if (isAwake(projectId, m.characterRef())) {
                out.add(m);
            }
        }
        return out;
    }

    /** 成员是否清醒（读取失败视为清醒，fail-open）。 */
    private boolean isAwake(Long projectId, String characterRef) {
        try {
            ActorNpcState st = npcStateService.stateFor(projectId, characterRef);
            if (st == null) {
                return true;
            }
            String act = st.getActivityState();
            if (act != null) {
                String a = act.trim().toLowerCase();
                if (a.contains("sleep") || a.contains("rest") || a.contains("睡") || a.contains("休息")) {
                    return false;
                }
            }
            String last = st.getLastAction();
            if (last != null) {
                for (String k : SLEEP_KEYWORDS) {
                    if (last.contains(k)) {
                        return false;
                    }
                }
            }
            return true;
        } catch (Exception e) {
            log.debug("[群聊] 清醒判定失败（视为清醒 fail-open）: ref={} : {}", characterRef, e.getMessage());
            return true;
        }
    }

    // ==================== 内部 ====================

    /** 会话须为群聊（否则 400）。 */
    private void requireGroup(Long projectId, Long sessionId) {
        ActorDialogueSession s = sessionService.require(projectId, sessionId);
        if (!"group".equals(s.getType())) {
            throw new BizException(400, "仅群聊会话支持该操作");
        }
    }

    /** 解析地点并写会话 location（零 AI 短事务写入口 = DialogueSessionService.updateLocation）。 */
    private void resolveAndApplyLocation(Long projectId, Long sessionId, Long locationId) {
        LocationKey key = resolveLocation(projectId, locationId);
        sessionService.updateLocation(projectId, sessionId, key.locationId(), key.locationName());
    }

    /** 解析场景地点：显式 locationId → 对应地点；null → 自动选在场最多的地点（无 → 空地点）。 */
    private LocationKey resolveLocation(Long projectId, Long locationId) {
        if (locationId != null) {
            return LocationKey.of(locationId, locationNameOf(projectId, locationId));
        }
        LocationKey best = autoPickLocation(projectId);
        return best == null ? new LocationKey(null, null) : best;
    }

    /**
     * 某地点的地点名（第 61 轮冒烟抓修★：优先按世界地点表解析——旧实现只取「该地点首个角色的 location
     * 字符串」，换场到<b>无角色在场</b>的地点（如青禾村古井）时返回 null → 会话 location_name 丢失、
     * 会话搜索按地点名失效；地点表解析失败再回退角色 location；仍无则 null）。
     */
    private String locationNameOf(Long projectId, Long locationId) {
        if (worldLocationRepository != null && locationId != null) {
            try {
                java.util.Optional<com.holzyn.actor.domain.world.entity.ActorWorldLocation> loc =
                        worldLocationRepository.findById(locationId);
                if (loc.isPresent() && loc.get().getName() != null && !loc.get().getName().isBlank()) {
                    return loc.get().getName();
                }
            } catch (Exception e) {
                log.debug("[群聊] 地点表名称解析失败（回退角色 location）: {}", e.getMessage());
            }
        }
        if (characterRepository == null) {
            return null;
        }
        try {
            for (ActorCharacter c : allCharacters(projectId)) {
                if (c.getLocationId() != null && c.getLocationId().equals(locationId) && c.getLocation() != null) {
                    return c.getLocation();
                }
            }
        } catch (Exception e) {
            log.debug("[群聊] 地点名解析失败（降级 null）: {}", e.getMessage());
        }
        return null;
    }

    /** 自动选地点：在场角色最多的地点（平局取角色 id 较小者所在；角色全无地点 → null）。 */
    private LocationKey autoPickLocation(Long projectId) {
        List<ActorCharacter> all = allCharactersSafe(projectId);
        if (all.isEmpty()) {
            return null;
        }
        Map<LocationKey, int[]> tally = new LinkedHashMap<>();
        for (ActorCharacter c : all) {
            LocationKey key = keyOf(c);
            if (key.locationId() == null && key.locationName() == null) {
                continue;
            }
            tally.computeIfAbsent(key, k -> new int[]{0})[0]++;
        }
        if (tally.isEmpty()) {
            return null;
        }
        return tally.entrySet().stream()
                .min(Comparator.comparingInt((Map.Entry<LocationKey, int[]> e) -> -e.getValue()[0])
                        .thenComparing(e -> e.getKey().locationId() == null ? Long.MAX_VALUE : e.getKey().locationId())
                        .thenComparing(e -> e.getKey().locationName() == null ? "" : e.getKey().locationName()))
                .map(Map.Entry::getKey)
                .orElse(null);
    }

    /** 角色 → 地点键（优先 locationId；缺失回退 location 名）。 */
    private static LocationKey keyOf(ActorCharacter c) {
        if (c.getLocationId() != null) {
            return new LocationKey(c.getLocationId(), c.getLocation());
        }
        return new LocationKey(null, c.getLocation());
    }

    /** 初始化在场成员（群聊创建：清空后按地点重算插入 present 行）。 */
    private void initPresentMembers(Long projectId, Long sessionId) {
        if (memberRepository == null) {
            return;
        }
        memberRepository.deleteBySessionId(sessionId);
        insertPresentMembers(projectId, sessionId);
    }

    /** 换场重算在场：删除既有 present 行（manual 保留），按新地点重插。 */
    private void replacePresentMembers(Long projectId, Long sessionId) {
        if (memberRepository == null) {
            return;
        }
        memberRepository.deleteBySessionIdAndSource(sessionId, SOURCE_PRESENT);
        insertPresentMembers(projectId, sessionId);
    }

    /** 按会话当前地点计算在场角色并落 present 行（零 AI；失败 fail-open 空表）。 */
    private void insertPresentMembers(Long projectId, Long sessionId) {
        if (memberRepository == null || characterRepository == null) {
            return;
        }
        ActorDialogueSession s = sessionService.require(projectId, sessionId);
        Long locationId = s.getLocationId();
        String locationName = s.getLocationName();
        List<ActorTalkGroupMember> rows = new ArrayList<>();
        try {
            for (ActorCharacter c : allCharacters(projectId)) {
                boolean samePlace = (locationId != null && c.getLocationId() != null && c.getLocationId().equals(locationId))
                        || (locationName != null && locationName.isBlank() == false
                        && locationName.equals(c.getLocation()));
                if (!samePlace) {
                    continue;
                }
                String ref = NpcRefs.of(c.getId());
                if (ref == null) {
                    continue;
                }
                if (memberRepository.findBySessionIdAndCharacterRef(sessionId, ref).isPresent()) {
                    continue; // manual 已存在 → 不重复
                }
                ActorTalkGroupMember m = new ActorTalkGroupMember();
                m.setSessionId(sessionId);
                m.setCharacterRef(ref);
                m.setCharacterName(c.getName());
                m.setSource(SOURCE_PRESENT);
                m.setCreatedAt(LocalDateTime.now());
                rows.add(m);
            }
        } catch (Exception e) {
            log.warn("[群聊] 在场成员重算失败（fail-open 空表）: project={} session={} : {}",
                    projectId, sessionId, e.getMessage());
            return;
        }
        memberRepository.saveAll(rows);
    }

    /** 项目全部角色（未删除；读取失败空表）。 */
    private List<ActorCharacter> allCharacters(Long projectId) {
        return characterRepository.findByProjectIdAndDeletedOrderByIdAsc(projectId, 0);
    }

    /** 项目全部角色（读取失败空表，fail-open）。 */
    private List<ActorCharacter> allCharactersSafe(Long projectId) {
        try {
            return allCharacters(projectId);
        } catch (Exception e) {
            log.debug("[群聊] 角色列表读取失败（降级空表）: {}", e.getMessage());
            return List.of();
        }
    }

    /** 成员实体 → 视图。 */
    private DialogueGroupMemberVO toVO(ActorTalkGroupMember m) {
        return new DialogueGroupMemberVO(m.getId(), m.getSessionId(), m.getCharacterRef(), m.getCharacterName(),
                m.getSource(), m.getCreatedAt());
    }

    /** 场景地点键（locationId + locationName 双轨）。 */
    record LocationKey(Long locationId, String locationName) {
        static LocationKey of(Long locationId, String locationName) {
            return new LocationKey(locationId, locationName);
        }
    }
}
