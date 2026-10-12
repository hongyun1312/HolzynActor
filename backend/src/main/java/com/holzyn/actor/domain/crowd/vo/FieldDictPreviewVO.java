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

package com.holzyn.actor.domain.crowd.vo;

import java.util.List;
import java.util.Map;

/**
 * 标准字段数据 + 主/次分类字段的 AI 拟定预览（分类体系重构）。
 * <p>职责：承载「AI 依据世界观一次性拟定全部字段字典 + 选出主/次分类字段」的结果（不落库），
 * 供前端预览确认后整体保存。</p>
 *
 * @param primaryField 主分类字段（AI 选出：race/affiliation/occupation）
 *
 * @param secondaryField 次分类字段（与主字段不同）
 *
 * @param fields 字段字典：{ race: [...], affiliation: [...], occupation: [...] }
 */
public record FieldDictPreviewVO(
        String primaryField,
        String secondaryField,
        Map<String, List<FieldDictVO>> fields
) {
}
