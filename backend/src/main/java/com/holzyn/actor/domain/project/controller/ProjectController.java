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

package com.holzyn.actor.domain.project.controller;

import com.holzyn.actor.common.R;
import com.holzyn.actor.domain.world.dto.WorldSettingDTO;
import com.holzyn.actor.domain.world.vo.WorldSettingVO;
import com.holzyn.actor.domain.character.service.CharacterCardService;
import com.holzyn.actor.domain.project.service.ProjectService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 世界观设定与角色卡生成控制器（A-；V1.1.2-beta.1 M5 起挂在 {@code /api/worlds} 下）。
 *
 * <p>职责收窄：只保留"世界内"的两件事——世界观设定读写、一键生成角色卡。
 * 存档列表/详情/新建/改名/删除已全部迁到 {@link com.holzyn.actor.domain.worldstore.WorldLifecycleController}
 * （那是目录库语义，与当前挂载哪个世界无关，混在本类里会被路由到世界库）。</p>
 */
@RestController
@RequestMapping("/api/worlds")
@RequiredArgsConstructor
public class ProjectController {

    private final ProjectService projectService;
    private final CharacterCardService characterCardService;

    /**
     * 读取项目最新版本世界观设定。
     *
     * @param id 项目主键
     *
     * @return 世界观（无则 data=null）
     */
    @GetMapping("/{id}/world-setting")
    public R<WorldSettingVO> worldSetting(@PathVariable("id") Long id) {
        return R.ok(projectService.getWorldSetting(id));
    }

    /**
     * 保存世界观设定（覆盖更新保存，直接修改最新记录不产生新版本）。
     *
     * @param id 项目主键
     *
     * @param dto 世界观入参
     *
     * @return 保存后的世界观（当前版本）
     */
    @PostMapping("/{id}/world-setting")
    public R<WorldSettingVO> saveWorldSetting(@PathVariable("id") Long id, @Valid @RequestBody WorldSettingDTO dto) {
        return R.ok(projectService.saveWorldSetting(id, dto));
    }

    /**
     * 一键为项目全部角色生成角色卡（AI，逐角色串行）。
     *
     * @param id 项目主键
     *
     * @return 每个角色的生成结果列表
     */
    @PostMapping("/{id}/generate-cards")
    public R<List<Map<String, Object>>> generateCards(@PathVariable("id") Long id) {
        return R.ok(characterCardService.generateAllCards(id));
    }
}