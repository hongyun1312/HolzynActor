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

package com.holzyn.actor.ai.decision;

/**
 * 决策模型调用目标端点（一次解析结果，供客户端拼 URL / 记账 / 健康检查共用）。
 *
 * <p><b>为什么单独建模</b>：端点可能来自三处——本地托管进程（{@code mode=local}）、
 * 配置的网关（{@code mode=api} 且 {@code base-url} 非空）、调试接口的临时覆盖。
 * 三者拼出的 base-url / 路径 / 模型名各不相同，散落在调用链里必然出现「本地跑到远端路径」
 * 这类 404；统一解析成一个值对象后，客户端只剩一条直路。</p>
 *
 * @param adapterId 适配器 ID（trace / 前端展示）
 *
 * @param baseUrl 端点根（不含尾斜杠，如 {@code http://127.0.0.1:8765}）
 *
 * @param evaluatePath 相对路径（如 {@code /api/evaluate}；空=直接 POST baseUrl）
 *
 * @param modelId 记账/健康检查用的模型名
 *
 * @param local 是否为本机托管进程（true=本地部署，false=远端/自托管网关）
 */
public record DecisionEndpoint(String adapterId, String baseUrl, String evaluatePath,
                               String modelId, boolean local) {

    /** 完整 evaluate URL。 */
    public String evaluateUrl() {
        return baseUrl + (evaluatePath == null ? "" : evaluatePath);
    }

    /** 去尾斜杠的工具方法（URL 拼接防 //）。 */
    public static String trimSlash(String url) {
        if (url == null || url.isBlank()) {
            return "";
        }
        String s = url.trim();
        while (s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }
        return s;
    }
}
