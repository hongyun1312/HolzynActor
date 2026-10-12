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

package com.holzyn.actor.domain.worldgen.vo;

import lombok.Data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 世界观生成任务「6 维产物 → 现有分节」预览 VO。
 * <p>职责：承载任务跑完后的结构化预览——6 个维度产物（key/中文标签/essence/完整 JSON）、
 * 映射到 actor_world_setting 分节的内容（name/genre/era/magicSystem/geography/factions/
 * culture/history/freeText）与 key_locations 映射的地点候选列表，供前端展示后由用户确认落库
 * （避免未确认覆盖现有世界观，方案 §3.5 预览确认语义）。</p>
 */
@Data
public class WorldGenPreviewVO {

    /** 任务 ID */
    private Long taskId;

    /** 归属项目 ID */
    private Long projectId;

    /** 工作流编码 */
    private String workflowCode;

    /** 6 个维度产物（顺序=链式生成顺序） */
    private List<PreviewDimension> dimensions = new ArrayList<>();

    /** 映射后的 actor_world_setting 分节内容（键=name/genre/era/magicSystem/geography/factions/culture/history/freeText） */
    private Map<String, String> sections = new LinkedHashMap<>();

    /** key_locations 映射的地点候选列表（确认落库时写入 actor_world_location） */
    private List<PreviewLocation> locations = new ArrayList<>();

    /** 维度产物 */
    @Data
    public static class PreviewDimension {
        /** 维度键（core_laws/space_time/...） */
        private String key;
        /** 维度中文标签（如 核心法则） */
        private String label;
        /** essence 定调句 */
        private String essence;
        /** 完整维度 JSON（包裹形态 {"dim": {...}}） */
        private Object data;
    }

    /** 地点候选 */
    @Data
    public static class PreviewLocation {
        /** 地点名称 */
        private String name;
        /** 地点类型（=terrain 地形） */
        private String type;
        /** 简介（=feature + risk + controlling_force） */
        private String intro;
    }
}
