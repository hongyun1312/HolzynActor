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

package com.holzyn.actor.domain.character.vo;

import com.holzyn.actor.domain.growth.persona.PersonaJson;
import com.holzyn.actor.domain.growth.persona.PersonaProfile;
import com.holzyn.actor.domain.npc.profile.entity.ActorCharacter;

import java.time.LocalDateTime;

/**
 * 角色卡视图对象（CharacterCardVO）。
 * <p><b>语义迁移（为什么改成这样构造）</b>：角色档案原存于独立表 {@code actor_profile_card}
 * ，现并入角色统一主表 {@code actor_profile} 的
 * {@code persona_json} / {@code persona_version} 两列。因此本 VO <b>不再由"角色卡实体"构造</b>，
 * 而由<b>主表实体 + 档案服务产物</b>构造（见 {@link #of}）——"角色卡"这个词在代码里保留只是为了避免
 * 牵连过多调用点，语义上它就是"角色档案"。</p>
 * <p><b>字段口径</b>：</p>
 * <ul>
 * <li>{@code id}/{@code characterId}：同为 {@code actor_profile.id}——单表之后"角色 id"与"档案 id"
 * 就是同一个统一身份（{@code npc:{id}}，见 {@code common/NpcRefs}）；保留两个字段只为不打断既有前端读取习惯；</li>
 * <li>{@code characterName}：角色姓名（主表结构化列，前端列表/标题直接可用，免二次请求）；</li>
 * <li>{@code version}：{@code actor_profile.persona_version}（档案版本号，每次落库 +1）；</li>
 * <li>{@code personaJson}：11 维档案 JSON 原文（只读展示 / 手动编辑回填用）；</li>
 * <li>{@code personaRenderText}：<b>新增</b>——{@code PersonaService.renderFull(id)} 的全量 11 维渲染文本
 * （含 MODULE/DER 维经投影补齐的权威数据，如 ③ 生理基线来自 {@code actor_state}）；
 * 渲染规则只应有一份实现，故由后端渲染好交给前端只读展示，避免前端重复实现渲染逻辑而与契约漂移；</li>
 * <li>{@code source}：档案生成来源（取档案内 {@code generated_by}：ai_bootstrap / manual …，取不到为 null）；</li>
 * <li>{@code systemPrompt}：<b>已删除</b>——system_prompt 概念随 废弃（不再落库、不再在成卡时定稿），
 * 改为<b>运行时按场景渲染</b>（{@code PersonaService.renderShardsForScene}：决策 {@code action_gen}、
 * 单聊 {@code dialog_system}、群聊 {@code group_reply}、计划 {@code plan_day}、人群 {@code npc_crowd_decision}），
 * 各场景各取所需分片，信息密度最优，也不再存在"改了档案忘了重渲染 prompt"的失配问题。</li>
 * </ul>
 */
public record CharacterCardVO(
        Long id,
        Long characterId,
        String characterName,
        Integer version,
        String personaJson,
        String personaRenderText,
        String source,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {

    /**
     * 由角色统一主表实体 + 档案内容构造 VO。
     * <p><b>零阻断</b>：{@code personaJson} 用 {@link PersonaJson#readQuietly} 宽松解析——历史脏 JSON
     * 只影响 {@code source}/{@code version} 的兜底取值，<b>绝不让只读接口抛异常</b>（脏数据不得阻断页面）。</p>
     *
     * @param ch 角色统一主表实体（actor_profile；提供 id/姓名/版本号/时间戳）
     *
     * @param personaJson 11 维档案 JSON 原文（可空）
     *
     * @param personaRenderText 全量 11 维渲染文本（可空/空串）
     *
     * @return VO；{@code ch} 为空时返回 null（调用方按"无档案"处理）
     */
    public static CharacterCardVO of(ActorCharacter ch, String personaJson, String personaRenderText) {
        if (ch == null) {
            return null;
        }
        PersonaProfile profile = PersonaJson.readQuietly(personaJson);
        // 版本号以主表列为准（落库唯一真源）；列值为空时退回档案 JSON 内的 persona_version
        Integer version = ch.getPersonaVersion() != null ? ch.getPersonaVersion()
                : (profile == null ? null : profile.getPersonaVersion());
        String source = profile == null ? null : profile.getGeneratedBy();
        return new CharacterCardVO(ch.getId(), ch.getId(), ch.getName(), version, personaJson,
                personaRenderText, source, ch.getCreatedAt(), ch.getUpdatedAt());
    }
}