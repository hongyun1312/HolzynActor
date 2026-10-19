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

package com.holzyn.actor.domain.dialogue.controller;

import com.holzyn.actor.common.R;
import com.holzyn.actor.domain.account.CurrentUserProvider;
import com.holzyn.actor.domain.dialogue.service.PlayerProfileGenService;
import com.holzyn.actor.domain.dialogue.service.PlayerProfileService;
import com.holzyn.actor.domain.dialogue.vo.PlayerProfileVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 玩家档案接口（M3：用户级，独立于具体项目）。
 * <p>职责：玩家档案（persona 六维）读取/保存 + 每角色「与我的关系」设定（过往/身份关系/态度）
 * 读取/保存——对谈上下文的关系段/玩家 persona 段由此驱动。</p>
 * <p>架构纪律：本控制器的<b>读取/保存</b>为<b>零 AI 短事务</b>（服务层 {@link PlayerProfileService} 无 AI 调用）；
 * 唯一的 AI 调用是「AI 补充生成」{@code POST /generate}（委托 {@link PlayerProfileGenService}，不落库、
 * 只回填预览——落库仍走零 AI 的 {@code PUT}）；玩家-角色关系行（项目级）见 {@link PlayerProfileRelationController}。</p>
 */
@RestController
@RequestMapping("/api/player-profile")
public class PlayerProfileController {

    /** 玩家档案服务 */
    @Autowired(required = false)
    private PlayerProfileService playerProfileService;

    /** 玩家档案 AI 补充生成服务 */
    @Autowired(required = false)
    private PlayerProfileGenService playerProfileGenService;

    /** 当前用户解析 */
    @Autowired(required = false)
    private CurrentUserProvider currentUserProvider;

    /**
     * 获取玩家档案（不存在时惰性创建；含 persona 六维 + 每角色关系设定）。
     *
     * @return 玩家档案 VO
     */
    @GetMapping
    public R<PlayerProfileVO> get() {
        return R.ok(playerProfileService.profileVO(currentUserProvider.currentUserId()));
    }

    /**
     * 保存/更新玩家档案（name/persona 选填；null 保持原值，空串清空；persona 按六维键合并）。
     *
     * @param body 请求体 { name?, persona?: { identity?, background?, personality?, voice?, goals?, ability? } }
     *
     * @return 更新后的 VO
     */
    @PutMapping
    public R<PlayerProfileVO> save(@RequestBody(required = false) Map<String, Object> body) {
        String name = body == null ? null : str(body.get("name"));
        Map<String, String> persona = personaMap(body == null ? null : body.get("persona"));
        return R.ok(playerProfileService.updateProfile(currentUserProvider.currentUserId(), name, persona));
    }

    /**
     * AI 补充生成玩家 persona 六维（新用户引导「我的形象」页）。
     * <p>输入一段自我介绍 → AI 生成六维（identity/background/personality/voice/goals/ability），
     * 返回给前端回填表单预览；<b>不落库</b>（确认保存仍走 {@code PUT}）。
     * 未配置 AI API 时网关抛 {@code AiCallException} → 全局处理器返回 400 中文提示。</p>
     *
     * @param body 请求体 { text: 自我介绍 }
     *
     * @return { persona: { identity?, background?, personality?, voice?, goals?, ability? } }
     */
    @PostMapping("/generate")
    public R<Map<String, Object>> generate(@RequestBody(required = false) Map<String, Object> body) {
        String text = str(body == null ? null : body.get("text"));
        Map<String, String> persona = playerProfileGenService.generate(currentUserProvider.currentUserId(), text);
        return R.ok(Map.of("persona", persona));
    }

    /**
     * 保存某角色的关系设定（past/identity/attitude 选填；null 保持原值，空串清空）。
     *
     * @param characterRef 对谈角色引用（npc:{id}）
     * @param body         请求体 { past?, identity?, attitude? }
     *
     * @return 更新后的玩家档案 VO
     */
    @PutMapping("/relation-settings/{characterRef}")
    public R<PlayerProfileVO> saveRelationSetting(@PathVariable String characterRef,
                                                  @RequestBody(required = false) Map<String, Object> body) {
        String past = body == null ? null : str(body.get("past"));
        String identity = body == null ? null : str(body.get("identity"));
        String attitude = body == null ? null : str(body.get("attitude"));
        return R.ok(playerProfileService.updateRelationSetting(currentUserProvider.currentUserId(),
                characterRef, past, identity, attitude));
    }

    // ==================== 内部 ====================

    /** persona 请求体 → 六维 Map（非对象/非法忽略）。 */
    @SuppressWarnings("unchecked")
    private static Map<String, String> personaMap(Object o) {
        if (!(o instanceof Map<?, ?> m)) {
            return null;
        }
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : m.entrySet()) {
            if (e.getKey() != null && e.getValue() != null) {
                out.put(String.valueOf(e.getKey()), String.valueOf(e.getValue()));
            }
        }
        return out;
    }

    /** body 字符串取值（null 安全）。 */
    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }
}
