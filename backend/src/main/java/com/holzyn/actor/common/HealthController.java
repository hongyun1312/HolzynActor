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

import com.holzyn.actor.common.R;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 健康检查控制器。
 * <p>职责：提供 /api/health 端点，用于存活探针与联调验证。</p>
 */
@RestController
@RequestMapping("/api/health")
public class HealthController {

    /** 应用名称，从配置注入 */
    @Value("${spring.application.name:holzyn-actor}")
    private String appName;

    /**
     * 健康检查端点。
     *
     * @return 包含状态、应用名、当前时间的 Map
     */
    @GetMapping
    public R<Map<String, Object>> health() {
        return R.ok(Map.of(
                "status", "UP",
                "name", appName,
                "time", LocalDateTime.now().toString()
        ));
    }
}