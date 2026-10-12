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

package com.holzyn.actor.domain.npc.state.controller;

import com.holzyn.actor.common.BizException;
import com.holzyn.actor.common.R;
import com.holzyn.actor.domain.npc.state.service.NpcStateTuningService;
import com.holzyn.actor.domain.npc.state.service.NpcStateTuningService.TuningRow;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * NPC 状态模块调参接口。
 * <p>职责：向调试前端调参面板提供<b>可写</b>的运行时参数覆盖接口——读取全量可调参数
 * （生效值/默认值/是否覆盖）、保存单个覆盖（写 actor_setting + 实时应用）、单键重置、
 * 一键全部恢复默认。</p>
 * <p>端点：</p>
 * <ul>
 * <li>GET /api/npc-state-config —— 全量可调参数（调参面板数据源）；</li>
 * <li>PUT /api/npc-state-config/{key} —— 保存单个覆盖（body={value:"..."}）；</li>
 * <li>DELETE /api/npc-state-config/{key} —— 单键重置（恢复默认）；</li>
 * <li>DELETE /api/npc-state-config —— 一键全部恢复默认。</li>
 * </ul>
 * <p>安全约定：本地单用户、无鉴权（本地调试工具）；仅接受注册表内键（未注册 400）。</p>
 * <p>设计依据：《NPC状态模块_主开发方案》§12 （调参）。
 * </p>
 */
@Slf4j
@RestController
@RequestMapping("/api/npc-state-config")
@RequiredArgsConstructor
public class NpcStateTuningController {

    private final NpcStateTuningService tuningService;

    /**
     * 全量可调参数（生效值 + 默认值 + 是否覆盖；调参面板数据源）。
     */
    @GetMapping
    public R<Map<String, Object>> effective() {
        List<TuningRow> rows = tuningService.effective();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("count", rows.size());
        out.put("rows", rows);
        return R.ok(out);
    }

    /**
     * 保存单个参数覆盖（写库 + 实时应用）。
     *
     * @param key 配置路径（如 decay.hunger-per-hour）
     *
     * @param body 载荷 {value: "新值"}
     *
     * @return 应用后的生效值
     */
    @PutMapping("/{key}")
    public R<Map<String, Object>> save(@PathVariable String key, @RequestBody Map<String, String> body) {
        if (body == null || body.get("value") == null) {
            throw new BizException(400, "请求体需包含 value 字段");
        }
        String applied = tuningService.save(key, body.get("value"));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("key", key);
        out.put("value", applied);
        return R.ok(out);
    }

    /**
     * 单键重置（恢复默认 + 删除覆盖）。
     *
     * @param key 配置路径
     *
     * @return 恢复后的默认值
     */
    @DeleteMapping("/{key}")
    public R<Map<String, Object>> reset(@PathVariable String key) {
        String def = tuningService.reset(key);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("key", key);
        out.put("value", def);
        return R.ok(out);
    }

    /**
     * 一键全部恢复默认（清空全部覆盖）。
     *
     * @return 重置的覆盖条数
     */
    @DeleteMapping
    public R<Map<String, Object>> resetAll() {
        int count = tuningService.resetAll();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("reset", count);
        return R.ok(out);
    }
}
