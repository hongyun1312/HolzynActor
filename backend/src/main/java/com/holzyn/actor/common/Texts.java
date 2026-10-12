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

package com.holzyn.actor.common;

/**
 * 文本口径工具（列宽截断 / 空白归一）。
 * <p>收口：库表列宽（{@code @Column(length = N)}）是<b>契约</b>，但写入路径来自
 * 三方——AI 输出、外部导入包、HTTP 请求体——都可能是"不可信输入"。此前各服务各自写
 * 私有 {@code truncate}，口径不一致且覆盖不全（导入路径索性不截断），
 * 溢出时数据库层抛 {@code Value too long}；若该写入又发生在一个长事务里，
 * 异常会拖垮整段业务（阶段④实测：{@code actor_usage_log.scene} 差 4 个字符，
 * 让 20 个 NPC 的 AI 建档产出被整段作废）。</p>
 * <p>因此把"按列宽截断"抽成公共实现，供各域写入路径共用。调用方传自己的列宽常量
 */
public final class Texts {

    private Texts() {
    }

    /**
     * 字符串截断（null/空白返回 null；超长按字符数截断）。
     *
     * @param s 原始文本（可空）
     *
     * @param max 最大字符数（≤0 视为不限制）
     *
     * @return trim 并截断后的文本；原始值为 null/空白时返回 null
     */
    public static String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        if (t.isEmpty()) {
            return null;
        }
        if (max <= 0 || t.length() <= max) {
            return t;
        }
        return t.substring(0, max);
    }

    /**
     * 空白归一：null/空白返回 null，否则返回 trim 后的文本。
     *
     * @param s 原始文本（可空）
     *
     * @return trim 后的文本或 null
     */
    public static String nullIfBlank(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}