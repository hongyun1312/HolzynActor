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

package com.holzyn.actor.domain.craft.model;

/**
 * 开工被拒（不是「材料不齐」，而是「这儿/现在做不了」）。
 *
 * <p>reasonCode 与 R-CRAFT-01 的失败码同一套词汇：{@code NO_FACILITY} / {@code OCCUPIED} /
 * {@code BUSY} / {@code RECIPE_UNKNOWN}。这样规则层、效果层、冒烟断言说的是同一种话，
 * 不需要一张「异常类型 → 原因码」的翻译表。</p>
 */
public class CraftRejectedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** 失败原因码（R-CRAFT-01 词汇） */
    private final String reasonCode;

    /**
     * 构造。
     *
     * @param reasonCode 失败原因码
     * @param message    人话说明（写进日志）
     */
    public CraftRejectedException(String reasonCode, String message) {
        super(message);
        this.reasonCode = reasonCode == null || reasonCode.isBlank() ? "REJECTED" : reasonCode.trim();
    }

    /**
     * 失败原因码。
     *
     * @return 原因码
     */
    public String reasonCode() {
        return reasonCode;
    }
}
