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

package com.holzyn.actor.domain.market.model;

/**
 * 买方资金不足（R-TRADE-01 {@code NO_FUNDS}）。
 *
 * <p>与 {@code InsufficientItemException} 同一条纪律：<b>严格拒绝、整批回滚、
 * 永不写负余额</b>。钱是物品（{@code coin}），所以这条异常最终也是由物品域
 * 「没有就是没有」兜住的——市场层只是提前给出可读的原因。</p>
 */
public class InsufficientFundsException extends RuntimeException {

    private final String buyerRef;

    private final String currencyKey;

    private final long required;

    private final long available;

    /**
     * 构造。
     *
     * @param buyerRef    买家
     * @param currencyKey 货币物品键
     * @param required    需要金额
     * @param available   实际持有
     */
    public InsufficientFundsException(String buyerRef, String currencyKey, long required, long available) {
        super("资金不足：" + buyerRef + " 需要 " + required + " " + currencyKey
                + "，实际只有 " + available);
        this.buyerRef = buyerRef;
        this.currencyKey = currencyKey;
        this.required = required;
        this.available = available;
    }

    /** @return 买家 ref */
    public String buyerRef() {
        return buyerRef;
    }

    /** @return 货币物品键 */
    public String currencyKey() {
        return currencyKey;
    }

    /** @return 需要金额 */
    public long required() {
        return required;
    }

    /** @return 实际持有 */
    public long available() {
        return available;
    }
}
