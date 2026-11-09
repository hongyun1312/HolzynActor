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

package com.holzyn.actor.domain.world.adjudicate.model;

/**
 * 裁决结论（§4.5）。{@code UNSURE} 只存在于规则层，用来触发罕见 AI 裁决；
 * 落库前必须收敛为 SUCCESS/PARTIAL/FAIL。
 */
public enum Verdict {

    /** 成功 */
    SUCCESS,

    /** 部分成功（执行原行动，效果草稿留待） */
    PARTIAL,

    /** 失败（中止原行动，只留可见失败） */
    FAIL,

    /** 规则无法判定（触发 AI 或降级） */
    UNSURE;

    /**
     * 解析 AI 输出（只接受三态；UNSURE 不允许由 AI 直接返回）。
     *
     * @param raw 原始文本
     *
     * @return SUCCESS/PARTIAL/FAIL；非法返回 null
     */
    public static Verdict parseAi(String raw) {
        if (raw == null) {
            return null;
        }
        String v = raw.trim().toUpperCase();
        return switch (v) {
            case "SUCCESS" -> SUCCESS;
            case "PARTIAL" -> PARTIAL;
            case "FAIL" -> FAIL;
            default -> null;
        };
    }
}
