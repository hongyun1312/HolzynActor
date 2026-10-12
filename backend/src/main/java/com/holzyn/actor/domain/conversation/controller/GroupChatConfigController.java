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

package com.holzyn.actor.domain.conversation.controller;

import com.holzyn.actor.domain.account.CurrentUserProvider;
import com.holzyn.actor.common.R;
import com.holzyn.actor.domain.conversation.service.GroupChatConfigService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 群聊配置控制器（用户级）。
 * <p>职责：提供群聊每轮回复上限的读取（GET）与修改（PUT，持久化），
 * 供前端「每轮回复上限」控件使用。归属一律以当前会话用户为准。</p>
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class GroupChatConfigController {

    /** 群聊配置服务 */
    private final GroupChatConfigService configService;

    /** 当前用户解析器 */
    private final CurrentUserProvider currentUserProvider;

    /**
     * 读取当前用户群聊每轮回复上限。
     */
    @GetMapping("/group-chat/config")
    public R<Map<String, Object>> get() {
        return R.ok(Map.of("maxReplies",
                configService.getMaxReplies(currentUserProvider.currentUserId())));
    }

    /**
     * 当前用户群聊每轮回复上限（1~20，持久化）。
     *
     * @param body 入参：{maxReplies}
     *
     * @return 保存后的 {maxReplies}
     */
    @PutMapping("/group-chat/config")
    public R<Map<String, Object>> put(@RequestBody Map<String, Object> body) {
        Integer maxReplies = body.get("maxReplies") instanceof Number n ? n.intValue() : null;
        return R.ok(Map.of("maxReplies",
                configService.saveMaxReplies(currentUserProvider.currentUserId(), maxReplies)));
    }
}