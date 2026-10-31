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

package com.holzyn.actor.domain.world.controller;

import com.holzyn.actor.common.BizException;
import com.holzyn.actor.domain.world.dto.ClimateConfigDTO;
import com.holzyn.actor.domain.world.service.EnvironmentContextService;
import com.holzyn.actor.domain.world.service.SeasonService;
import com.holzyn.actor.domain.world.vo.ClimateConfigVO;
import com.holzyn.actor.domain.world.vo.EnvironmentContextVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 统一环境上下文 + 季节骨架配置接口。
 * <p>接口集：</p>
 * <ul>
 * <li>GET /environment/context?gameSecond= —— 统一环境上下文（季节/时刻阶段/日出日落/
 * 天气/气温/体感/环境状态文本/感官标签；零同步 AI，纯确定性计算 + 润色缓存读取）；</li>
 * <li>GET /climate-config —— 项目季节骨架配置（季节固定绑定表 + 四季骨架 + 来源）；</li>
 * <li>PUT /climate-config —— 覆盖项目四季骨架（body={configJson}；校验 JSON 对象含四季键；
 * 不改变季节固定绑定表）。</li>
 * </ul>
 */
@Slf4j
@RestController
@RequestMapping("/api/worlds/{id}")
@RequiredArgsConstructor
public class EnvironmentController {

    /** 季节键白名单（骨架配置必须含四季） */
    private static final String[] SEASON_KEYS = {SeasonService.SPRING, SeasonService.SUMMER,
            SeasonService.AUTUMN, SeasonService.WINTER};

    private final EnvironmentContextService environmentContextService;
    private final SeasonService seasonService;
    private final ObjectMapper objectMapper;

    /**
     * 统一环境上下文（读接口；gameSecond 可空=引擎当前推进位置）。
     *
     * @param projectId 项目 ID
     *
     * @param gameSecond 游戏秒（可空）
     *
     * @return 环境上下文 VO
     */
    @GetMapping("/environment/context")
    public EnvironmentContextVO context(@PathVariable("id") Long projectId,
                                        @RequestParam(required = false) Long gameSecond) {
        return environmentContextService.computeContext(projectId, gameSecond);
    }

    /**
     * 项目季节骨架配置（GET）：季节固定绑定表（1-12→季节）+ 四季骨架 + 来源。
     *
     * @param projectId 项目 ID
     */
    @GetMapping("/climate-config")
    public ClimateConfigVO config(@PathVariable("id") Long projectId) {
        ClimateConfigVO vo = new ClimateConfigVO();
        vo.setProjectId(projectId);
        for (int m = 1; m <= 12; m++) {
            vo.getSeasonMap().put(m, SeasonService.seasonOfMonth(m));
        }
        Map<String, Object> skeleton = new LinkedHashMap<>();
        Map<String, SeasonService.SeasonSkeleton> defaults = SeasonService.defaultSkeleton();
        for (String key : SEASON_KEYS) {
            SeasonService.SeasonSkeleton sk = seasonService.skeletonOf(projectId, key);
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("baseTempC", new double[]{sk.baseTempLo(), sk.baseTempHi()});
            entry.put("weatherTendency", sk.weatherTendency());
            entry.put("daylightOffsetMin", sk.daylightOffsetMin());
            entry.put("colorTone", sk.colorTone());
            skeleton.put(key, entry);
        }
        vo.setSkeleton(skeleton);
        vo.setSource(seasonService.configExists(projectId) ? "custom" : "default");
        vo.setVersion(1);
        return vo;
    }

    /**
     * 覆盖项目四季骨架（PUT）：校验 configJson 为 JSON 对象且含四季键 → 保存（版本 +1）。
     *
     * @param projectId 项目 ID
     *
     * @param body 请求体（configJson 骨架 JSON 文本，必填）
     *
     * @return 更新后的 ClimateConfigVO
     */
    @PutMapping("/climate-config")
    public ClimateConfigVO updateConfig(@PathVariable("id") Long projectId,
                                        @RequestBody ClimateConfigDTO body) {
        if (body == null || body.configJson() == null || body.configJson().isBlank()) {
            throw new BizException(400, "configJson 不能为空（四季骨架 JSON）");
        }
        JsonNode root;
        try {
            root = objectMapper.readTree(body.configJson());
        } catch (Exception e) {
            throw new BizException(400, "configJson 不是合法 JSON：" + e.getMessage());
        }
        if (root == null || !root.isObject()) {
            throw new BizException(400, "configJson 必须是 JSON 对象（{spring/summer/autumn/winter: {...}}）");
        }
        for (String key : SEASON_KEYS) {
            JsonNode node = root.path(key);
            if (node == null || !node.isObject()) {
                throw new BizException(400, "configJson 缺少季节键：" + key);
            }
            JsonNode temp = node.path("baseTempC");
            if (temp == null || !temp.isArray() || temp.size() < 2
                    || !temp.get(0).isNumber() || !temp.get(1).isNumber()) {
                throw new BizException(400, "季节 " + key + " 的 baseTempC 必须为 [低温, 高温] 数值数组");
            }
        }
        seasonService.saveConfig(projectId, body.configJson());
        return config(projectId);
    }
}
