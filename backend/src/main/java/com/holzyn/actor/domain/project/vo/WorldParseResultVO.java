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

package com.holzyn.actor.domain.project.vo;

import java.util.Map;

/**
 * 新建项目「文件解析工作流」结果 VO（新建项目解析重构）。
 * <p>职责：承载解析工作流全部落库完成后的汇总结果（项目已创建，可直接跳转项目空间/初始化页）。</p>
 *
 * @param projectId 新建项目 ID
 *
 * @param projectName 项目名称
 *
 * @param worldName 世界观名称
 *
 * @param segmentChars 各分段字数（地理/势力/规则/文化/历史/补充）
 *
 * @param characterCount 已创建角色数
 *
 * @param knowledgeDocCount 知识库文档数（原始文件全文，暂未向量化）
 */
public record WorldParseResultVO(
        Long projectId,
        String projectName,
        String worldName,
        Map<String, Integer> segmentChars,
        int characterCount,
        int knowledgeDocCount
) {
}
