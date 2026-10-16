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

import com.holzyn.actor.domain.account.entity.LocalAccount;
import com.holzyn.actor.domain.account.repository.LocalAccountRepository;
import com.holzyn.actor.domain.dialogue.entity.ActorPlayerProfile;
import com.holzyn.actor.domain.dialogue.entity.ActorPlayerRelation;
import com.holzyn.actor.domain.dialogue.repository.ActorPlayerProfileRepository;
import com.holzyn.actor.domain.dialogue.repository.ActorPlayerRelationRepository;
import com.holzyn.actor.domain.dialogue.vo.PlayerProfileVO;
import com.holzyn.actor.domain.dialogue.vo.PlayerRelationVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 玩家档案服务（M3：玩家档案 + 玩家-角色关系设定的<b>唯一写入口</b>）。
 * <p>职责：① 玩家档案（`actor_talk_player_profile`）读取/惰性创建/更新——persona
 * 六维（身份/背景/性格/说话风格/目标/能力，与特殊 NPC 档案 11 维同构裁剪，无记忆/状态/行动）；
 * ② 每角色「与我的关系」设定（`relation_settings_json`：过往/身份关系/态度）读改写；
 * ③ 玩家-角色关系行（`actor_talk_player_relation`）读取/惰性创建/更新（好感/信任/阶段/备注，
 * 用户可编辑基线；state_summary/version 归 M6 沉淀写回，本服务只读保留）。</p>
 * <p><b>写入口纪律</b>（概念注册表/模块边界契约）：本服务为玩家档案与玩家-角色关系的 M3 唯一写入口；
 * M6 `DialogueConsolidationService` 沉淀只写 relation delta（state_summary/version/好感 delta）。</p>
 * <p><b>更新语义</b>：字段选填——null 保持原值，空串清空（对齐本地账户保存约定）。</p>
 * <p><b>零 AI</b>：本服务无任何 AI 调用，纯本地短事务（@Transactional 合法）；渲染段读取在
 * {@link DialogueRelationService}（只读，复用同一 JSON 结构约定）。</p>
 */
@Slf4j
@Service
public class PlayerProfileService {

    /** Jackson 3 序列化器（persona / 关系设定 JSON 读写） */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 玩家 persona 六维键（与特殊 NPC 档案同构裁剪；键名对齐 dims 各维语义） */
    public static final List<String> PERSONA_KEYS =
            List.of("identity", "background", "personality", "voice", "goals", "ability");

    /** 关系设定三字段：过往 / 身份关系 / 态度 */
    private static final List<String> RELATION_SETTING_KEYS = List.of("past", "identity", "attitude");

    /** 玩家档案仓库 */
    @Autowired(required = false)
    private ActorPlayerProfileRepository profileRepository;

    /** 玩家-角色关系仓库 */
    @Autowired(required = false)
    private ActorPlayerRelationRepository relationRepository;

    /** 本地账户仓库（惰性创建时默认玩家名=账户昵称） */
    @Autowired(required = false)
    private LocalAccountRepository accountRepository;

    // ==================== 玩家档案 ====================

    /**
     * 玩家档案（不存在时惰性创建：默认玩家名=账户昵称，关系设定初始为空对象）。
     * <p>保证后续读写非空——首次引导/设置页/对谈装配均可安全调用。</p>
     *
     * @param userId 用户 ID（null 兜底 1）
     *
     * @return 玩家档案实体
     */
    @Transactional
    public ActorPlayerProfile profileOf(Long userId) {
        Long uid = userId == null ? 1L : userId;
        return profileRepository.findByUserId(uid).orElseGet(() -> {
            ActorPlayerProfile p = new ActorPlayerProfile();
            p.setUserId(uid);
            p.setRelationSettingsJson("{}");
            if (accountRepository != null) {
                try {
                    LocalAccount a = accountRepository.findByUserId(uid).orElse(null);
                    if (a != null && isNotBlank(a.getNickname())) {
                        p.setName(a.getNickname().trim());
                    }
                } catch (Exception e) {
                    log.debug("[玩家档案] 账户昵称读取失败（玩家名留空，解析兜底「你」）: {}", e.getMessage());
                }
            }
            return profileRepository.save(p);
        });
    }

    /**
     * 玩家档案 VO（含 persona 六维 + 每角色关系设定）。
     *
     * @param userId 用户 ID
     *
     * @return 玩家档案 VO（恒非空）
     */
    @Transactional
    public PlayerProfileVO profileVO(Long userId) {
        return toVO(profileOf(userId));
    }

    /**
     * 更新玩家档案（name/persona 选填；null 保持原值，空串清空）。
     * <p>persona 只按六维键合并更新——未传的键保持原值，传入空串的键移除。</p>
     *
     * @param userId 用户 ID
     * @param name   玩家名（可空=不修改）
     * @param persona persona 六维（可空=不修改；值 null 保持、空串清空）
     *
     * @return 更新后的 VO
     */
    @Transactional
    public PlayerProfileVO updateProfile(Long userId, String name, Map<String, String> persona) {
        ActorPlayerProfile p = profileOf(userId);
        if (name != null) {
            p.setName(name.isBlank() ? null : name.trim());
        }
        if (persona != null && !persona.isEmpty()) {
            p.setPersonaJson(mergePersona(p.getPersonaJson(), persona));
        }
        return toVO(profileRepository.save(p));
    }

    // ==================== 玩家-角色关系设定（relation_settings_json） ====================

    /**
     * 某角色的关系设定（过往/身份关系/态度）。
     *
     * @param userId       用户 ID
     * @param characterRef 对谈角色引用（npc:{id}）
     *
     * @return 设定 Map（仅含非空字段）；无设定返回空 Map
     */
    @Transactional(readOnly = true)
    public Map<String, String> relationSettingOf(Long userId, String characterRef) {
        Map<String, String> out = new LinkedHashMap<>();
        if (characterRef == null || characterRef.isBlank()) {
            return out;
        }
        try {
            ActorPlayerProfile p = profileRepository.findByUserId(userId == null ? 1L : userId).orElse(null);
            if (p == null || isBlank(p.getRelationSettingsJson())) {
                return out;
            }
            JsonNode root = MAPPER.readTree(p.getRelationSettingsJson());
            JsonNode entry = root.path(characterRef);
            if (!entry.isObject()) {
                return out;
            }
            for (String k : RELATION_SETTING_KEYS) {
                JsonNode v = entry.path(k);
                if (!v.isMissingNode() && !v.isNull() && isNotBlank(v.asText())) {
                    out.put(k, v.asText().trim());
                }
            }
        } catch (Exception e) {
            log.debug("[玩家档案] 关系设定读取失败（返回空）: {}", e.getMessage());
        }
        return out;
    }

    /**
     * 更新某角色的关系设定（past/identity/attitude 选填；null 保持、空串清空）。
     * <p>其余角色的设定保持不变；该角色三字段全空时移除该条目。</p>
     *
     * @param userId       用户 ID
     * @param characterRef 对谈角色引用（npc:{id}）
     * @param past         过往（可空）
     * @param identity     身份关系（可空）
     * @param attitude     他对我的态度（可空）
     *
     * @return 更新后的玩家档案 VO
     */
    @Transactional
    public PlayerProfileVO updateRelationSetting(Long userId, String characterRef, String past,
                                                 String identity, String attitude) {
        ActorPlayerProfile p = profileOf(userId);
        p.setRelationSettingsJson(mergeRelationSetting(p.getRelationSettingsJson(), characterRef,
                past, identity, attitude));
        return toVO(profileRepository.save(p));
    }

    // ==================== 玩家-角色关系行（actor_talk_player_relation） ====================

    /**
     * 某项目+角色的关系行（不存在时惰性创建：好感 0/信任 0/阶段「陌生」，关联玩家档案）。
     *
     * @param projectId    项目 ID
     * @param userId       用户 ID（惰性创建时关联玩家档案）
     * @param characterRef 对谈角色引用
     *
     * @return 关系行实体（恒非空）
     */
    @Transactional
    public ActorPlayerRelation relationOf(Long projectId, Long userId, String characterRef) {
        return relationRepository.findByProjectIdAndCharacterRef(projectId, characterRef).orElseGet(() -> {
            ActorPlayerRelation r = new ActorPlayerRelation();
            r.setProjectId(projectId);
            r.setPlayerProfileId(profileOf(userId).getId());
            r.setCharacterRef(characterRef);
            return relationRepository.save(r);
        });
    }

    /**
     * 更新某项目+角色的关系行（affinity/trust/stage/note 选填；null 保持、空串清空）。
     * <p>好感/信任钳制 0~100；state_summary/version 归 M6 沉淀写回，本方法只读保留不触碰。</p>
     *
     * @param projectId    项目 ID
     * @param userId       用户 ID
     * @param characterRef 对谈角色引用
     * @param affinity     好感（可空；钳制 0~100）
     * @param trust        信任（可空；钳制 0~100）
     * @param stage        关系阶段（可空；空串清空）
     * @param note         备注（可空；空串清空）
     *
     * @return 更新后的关系 VO
     */
    @Transactional
    public PlayerRelationVO upsertRelation(Long projectId, Long userId, String characterRef,
                                           Integer affinity, Integer trust, String stage, String note) {
        ActorPlayerRelation r = relationOf(projectId, userId, characterRef);
        if (affinity != null) {
            r.setAffinity(clamp(affinity));
        }
        if (trust != null) {
            r.setTrust(clamp(trust));
        }
        if (stage != null) {
            r.setStage(stage.isBlank() ? null : stage.trim());
        }
        if (note != null) {
            r.setNote(note.isBlank() ? null : note.trim());
        }
        return toVO(relationRepository.save(r));
    }

    /**
     * 某项目全部关系行（列表，供设置页/对谈面板读取）。
     *
     * @param projectId 项目 ID
     *
     * @return 关系 VO 列表（无数据返回空列表）
     */
    @Transactional(readOnly = true)
    public List<PlayerRelationVO> relationsOf(Long projectId) {
        return relationRepository.findByProjectId(projectId).stream().map(this::toVO).toList();
    }

    // ==================== M6 沉淀写回（DialogueConsolidationService 委托的短事务） ====================

    /**
     * 应用一次沉淀的关系写回（M6，短事务；由 {@code DialogueConsolidationService.apply} 编排调用）。
     * <p>语义：好感/信任按 AI 判定 delta 叠加后钳制 0~100；阶段/备注/状态摘要非空才覆盖；
     * <b>version +1</b>（幂等标记：每次沉淀版本递增，会话 {@code player_relation_version} 同步）；
     * 更新时间刷新。AI 已在事务外完成（调用方无 @Transactional），本方法为毫秒级写阶段短事务。</p>
     *
     * @param projectId     项目 ID
     * @param userId        用户 ID（惰性创建关系行时关联玩家档案）
     * @param characterRef  对谈角色引用
     * @param affinityDelta 好感 delta（可空/0=不改；叠加后钳制 0~100）
     * @param trustDelta    信任 delta（可空/0=不改；叠加后钳制 0~100）
     * @param stage         关系阶段（可空/空串=保持现状）
     * @param note          备注（可空；空串清空）
     * @param stateSummary  关系状态摘要（可空；空串=保持现状）
     *
     * @return 更新后的关系 VO
     */
    @Transactional
    public PlayerRelationVO applyConsolidatedRelation(Long projectId, Long userId, String characterRef,
                                                      Integer affinityDelta, Integer trustDelta,
                                                      String stage, String note, String stateSummary) {
        ActorPlayerRelation r = relationOf(projectId, userId, characterRef);
        if (affinityDelta != null && affinityDelta != 0) {
            int base = r.getAffinity() == null ? 0 : r.getAffinity();
            r.setAffinity(clamp(base + affinityDelta));
        }
        if (trustDelta != null && trustDelta != 0) {
            int base = r.getTrust() == null ? 0 : r.getTrust();
            r.setTrust(clamp(base + trustDelta));
        }
        if (stage != null && !stage.isBlank()) {
            r.setStage(stage.trim());
        }
        if (note != null) {
            r.setNote(note.isBlank() ? null : note.trim());
        }
        if (stateSummary != null && !stateSummary.isBlank()) {
            r.setStateSummary(stateSummary.trim());
        }
        r.setVersion((r.getVersion() == null ? 0 : r.getVersion()) + 1);
        r.setUpdatedAt(LocalDateTime.now());
        return toVO(relationRepository.save(r));
    }

    // ==================== 内部 ====================

    /** persona 合并（六维键；null 保持原键、空串移除键、其余覆盖）。 */
    private static String mergePersona(String personaJson, Map<String, String> incoming) {
        ObjectNode root = parseObject(personaJson);
        for (String k : PERSONA_KEYS) {
            String v = incoming.get(k);
            if (v == null) {
                continue;
            }
            if (v.isBlank()) {
                root.remove(k);
            } else {
                root.put(k, v.trim());
            }
        }
        return write(root);
    }

    /** 关系设定合并（三字段；null 保持、空串移除、其余覆盖；全空移除条目）。 */
    private static String mergeRelationSetting(String relationSettingsJson, String characterRef,
                                               String past, String identity, String attitude) {
        if (characterRef == null || characterRef.isBlank()) {
            return relationSettingsJson;
        }
        ObjectNode root = parseObject(relationSettingsJson);
        JsonNode existing = root.path(characterRef);
        ObjectNode entry = existing.isObject() ? (ObjectNode) existing : root.putObject(characterRef);
        putOrRemove(entry, "past", past);
        putOrRemove(entry, "identity", identity);
        putOrRemove(entry, "attitude", attitude);
        if (entry.isEmpty()) {
            root.remove(characterRef);
        }
        return write(root);
    }

    /** 空串移除/非空写入（字段级更新语义）。 */
    private static void putOrRemove(ObjectNode node, String key, String value) {
        if (value == null) {
            return;
        }
        if (value.isBlank()) {
            node.remove(key);
        } else {
            node.put(key, value.trim());
        }
    }

    /** JSON 文本 → 对象节点（null/空/非法回退空对象）。 */
    private static ObjectNode parseObject(String json) {
        if (isBlank(json)) {
            return MAPPER.createObjectNode();
        }
        try {
            JsonNode n = MAPPER.readTree(json);
            if (n.isObject()) {
                return (ObjectNode) n;
            }
        } catch (Exception e) {
            log.debug("[玩家档案] JSON 解析失败（按空对象重建）: {}", e.getMessage());
        }
        return MAPPER.createObjectNode();
    }

    /** 对象节点 → JSON 文本（失败回退原样空对象）。 */
    private static String write(ObjectNode node) {
        try {
            return MAPPER.writeValueAsString(node);
        } catch (Exception e) {
            log.warn("[玩家档案] JSON 序列化失败（回退空对象）: {}", e.getMessage());
            return "{}";
        }
    }

    /** 好感/信任钳制 0~100。 */
    private static int clamp(Integer v) {
        return Math.max(0, Math.min(100, v == null ? 0 : v));
    }

    /** 实体 → 玩家档案 VO（persona/关系设定解析为 Map）。 */
    private PlayerProfileVO toVO(ActorPlayerProfile p) {
        PlayerProfileVO vo = new PlayerProfileVO();
        vo.setId(p.getId());
        vo.setUserId(p.getUserId());
        vo.setName(p.getName());
        vo.setPersona(personaMapOf(p.getPersonaJson()));
        vo.setRelationSettings(relationSettingsMapOf(p.getRelationSettingsJson()));
        vo.setCreatedAt(p.getCreatedAt());
        vo.setUpdatedAt(p.getUpdatedAt());
        return vo;
    }

    /** persona JSON → 六维 Map（仅含非空字段）。 */
    private static Map<String, String> personaMapOf(String personaJson) {
        Map<String, String> out = new LinkedHashMap<>();
        if (isBlank(personaJson)) {
            return out;
        }
        try {
            JsonNode root = MAPPER.readTree(personaJson);
            if (!root.isObject()) {
                return out;
            }
            for (String k : PERSONA_KEYS) {
                JsonNode v = root.path(k);
                if (!v.isMissingNode() && !v.isNull() && isNotBlank(v.asText())) {
                    out.put(k, v.asText().trim());
                }
            }
        } catch (Exception e) {
            log.debug("[玩家档案] persona 解析失败（返回空）: {}", e.getMessage());
        }
        return out;
    }

    /** 关系设定 JSON → characterRef → {past,identity,attitude}（仅含非空字段）。 */
    private static Map<String, Map<String, String>> relationSettingsMapOf(String relationSettingsJson) {
        Map<String, Map<String, String>> out = new LinkedHashMap<>();
        if (isBlank(relationSettingsJson)) {
            return out;
        }
        try {
            JsonNode root = MAPPER.readTree(relationSettingsJson);
            if (!root.isObject()) {
                return out;
            }
            root.properties().forEach(e -> {
                String ref = e.getKey();
                JsonNode entry = e.getValue();
                if (!entry.isObject()) {
                    return;
                }
                Map<String, String> m = new LinkedHashMap<>();
                for (String k : RELATION_SETTING_KEYS) {
                    JsonNode v = entry.path(k);
                    if (!v.isMissingNode() && !v.isNull() && isNotBlank(v.asText())) {
                        m.put(k, v.asText().trim());
                    }
                }
                if (!m.isEmpty()) {
                    out.put(ref, m);
                }
            });
        } catch (Exception e) {
            log.debug("[玩家档案] 关系设定解析失败（返回空）: {}", e.getMessage());
        }
        return out;
    }

    /** 实体 → 关系 VO。 */
    private PlayerRelationVO toVO(ActorPlayerRelation r) {
        PlayerRelationVO vo = new PlayerRelationVO();
        vo.setProjectId(r.getProjectId());
        vo.setCharacterRef(r.getCharacterRef());
        vo.setAffinity(r.getAffinity());
        vo.setTrust(r.getTrust());
        vo.setStage(r.getStage());
        vo.setNote(r.getNote());
        vo.setStateSummary(r.getStateSummary());
        vo.setVersion(r.getVersion());
        vo.setUpdatedAt(r.getUpdatedAt());
        return vo;
    }

    /** 空白判断（null 或全空白视为空）。 */
    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    /** 非空白判断。 */
    private static boolean isNotBlank(String s) {
        return s != null && !s.isBlank();
    }
}
