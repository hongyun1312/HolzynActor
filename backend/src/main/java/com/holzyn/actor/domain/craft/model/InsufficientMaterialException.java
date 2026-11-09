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

import java.util.List;

/**
 * 材料不齐（R-CRAFT-01 的 {@code NO_MATERIAL}）。
 *
 * <p>带上缺口清单而不是一句字符串：效果层整批回滚后，日志与冒烟断言要能说出
 * 「缺的是面粉 2 份」，而不是「做不了」。</p>
 */
public class InsufficientMaterialException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** 缺口清单（永不为 null） */
    private final transient List<MaterialGap> gaps;

    /**
     * 构造。
     *
     * @param recipeKey 配方键
     * @param gaps      缺口清单
     */
    public InsufficientMaterialException(String recipeKey, List<MaterialGap> gaps) {
        super("材料不齐：" + recipeKey + " 缺 " + describe(gaps));
        this.gaps = gaps == null ? List.of() : List.copyOf(gaps);
    }

    /**
     * 缺口清单。
     *
     * @return 缺口（可能为空列表）
     */
    public List<MaterialGap> gaps() {
        return gaps;
    }

    private static String describe(List<MaterialGap> gaps) {
        if (gaps == null || gaps.isEmpty()) {
            return "（未知）";
        }
        StringBuilder sb = new StringBuilder();
        for (MaterialGap gap : gaps) {
            if (gap == null) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append("、");
            }
            sb.append(gap.describe());
        }
        return sb.length() == 0 ? "（未知）" : sb.toString();
    }
}
