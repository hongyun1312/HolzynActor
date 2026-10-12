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

import com.holzyn.actor.domain.project.dto.ProjectImportDTO;

import java.util.List;

/**
 * 文件导入解析预览 VO。
 * <p>职责：向前端返回 AI 解析后的预览结构（项目/世界观/角色/地点），
 * 前端展示预览卡片供用户确认/编辑后再调用确认创建。</p>
 *
 * @param project 项目部分
 *
 * @param worldSetting 世界观部分
 *
 * @param characters 角色列表（解析或 AI 生成）
 *
 * @param locations 地点列表（地理设定之后由 AI 提取，可为空）
 *
 * @param hasCharacters 是否解析到角色（false 时前端展示三种补角色方式）
 *
 * @param sourceFiles 来源文件名列表
 */
public record ProjectImportPreviewVO(
        ProjectImportDTO.ProjectPart project,
        ProjectImportDTO.WorldPart worldSetting,
        List<ProjectImportDTO.CharacterPart> characters,
        List<ProjectImportDTO.LocationPart> locations,
        boolean hasCharacters,
        List<String> sourceFiles
) {
}
