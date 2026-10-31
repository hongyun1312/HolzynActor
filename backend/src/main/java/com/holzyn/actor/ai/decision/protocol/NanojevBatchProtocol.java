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

package com.holzyn.actor.ai.decision.protocol;

import com.holzyn.actor.ai.decision.DecisionRequest;
import com.holzyn.actor.ai.decision.DecisionResponse;
import com.holzyn.actor.ai.decision.DecisionState;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * <b>批量状态协议</b>（{@code nanojev-batch}）：{@code {"states":[{id,state,questions}]}} → 一次前向出全部答案。
 *
 * <p>本协议覆盖的服务端形态：</p>
 * <ul>
 *   <li>自托管网关的 {@code POST /v1/<model>/evaluate}（本仓原生形态，响应带
 *       {@code execution.forward_passes} 证据字段）；</li>
 *   <li>官方仓库自带脚本 {@code serve_decisions.py} 的 {@code POST /api/evaluate}
 *       （响应可能不带 {@code execution}——此时按「协议天然零解码」合成元数据，
 *       见 {@link DecisionResponse#tolerantParse(String)}）。</li>
 * </ul>
 *
 * <p><b>静默回退防护仍然保留</b>：响应若为 chat 形状（含 {@code choices}），
 * {@link DecisionResponse#isChatFallback()} 为真，客户端按失败处置。</p>
 */
@Component
public class NanojevBatchProtocol implements DecisionProtocol {

    /** 协议 ID */
    public static final String ID = "nanojev-batch";

    /** 网关形态默认路径（可被配置 evaluate-path 覆盖） */
    public static final String DEFAULT_PATH = "/nanojev/evaluate";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String defaultEvaluatePath() {
        return DEFAULT_PATH;
    }

    @Override
    public boolean batched() {
        return true;
    }

    @Override
    public String buildBody(List<DecisionState> states) {
        return new DecisionRequest(states).toJson();
    }

    @Override
    public DecisionResponse parse(String body, List<DecisionState> sent) {
        DecisionResponse strict = DecisionResponse.parse(body);
        if (strict != null) {
            return strict;
        }
        // 官方脚本形态：无 execution 证据字段，但 states/answers 齐全 → 视为协议内合法响应
        return DecisionResponse.tolerantParse(body);
    }

    @Override
    public String healthPath() {
        return "/models";
    }
}
