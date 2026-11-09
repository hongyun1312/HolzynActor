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

package com.holzyn.actor.domain.equip.model;

/**
 * 装备操作被拒绝。
 *
 * <p>带原因码，让裁决层能直接把它翻成 {@code reason_code}，不必解析文案。</p>
 */
public class EquipmentRejectedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** 原因码：物品未登记 */
    public static final String NOT_FOUND = "ITEM_NOT_FOUND";

    /** 原因码：这件东西没有装备槽 */
    public static final String NOT_EQUIPPABLE = "NOT_EQUIPPABLE";

    /** 原因码：槽位对不上（拿衣服往手上套） */
    public static final String WRONG_SLOT = "WRONG_SLOT";

    /** 原因码：手上只能拿武器/工具 */
    public static final String NOT_A_WEAPON = "NOT_A_WEAPON";

    /** 原因码：身上没有这件东西 */
    public static final String NOT_CARRIED = "NOT_CARRIED";

    private final String reasonCode;

    /**
     * 构造。
     *
     * @param reasonCode 原因码
     * @param message    说明
     */
    public EquipmentRejectedException(String reasonCode, String message) {
        super(message);
        this.reasonCode = reasonCode;
    }

    /**
     * 原因码。
     *
     * @return 原因码
     */
    public String reasonCode() {
        return reasonCode;
    }
}
