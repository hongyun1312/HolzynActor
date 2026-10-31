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

package com.holzyn.actor.ai.decision.adapter;

import com.holzyn.actor.ai.decision.protocol.DecisionProtocol;
import com.holzyn.actor.ai.decision.protocol.JevSystemOneProtocol;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 适配器：<b>Strands Decider 2B</b>（Qwen3.5-2B 之上的 LoRA + 判定头，Jev 单 state 协议）。
 *
 * <p>权重仓库 {@code StrandsAgents/strands-decider-2B-hobson-v21}（Apache-2.0）；
 * 官方 CLI {@code strands-decider serve <repo> --port 8000}，
 * 端点 {@code POST /v1/systemone}，布尔题型名为 {@code noul}。</p>
 */
@Component
public class StrandsDeciderAdapter implements DecisionAdapter {

    /** 适配器 ID */
    public static final String ID = "strands-decider-2b";

    private final DecisionProtocol protocol;

    /**
     * 构造。
     *
     * @param protocol Jev 单 state 协议
     */
    @Autowired
    public StrandsDeciderAdapter(JevSystemOneProtocol protocol) {
        this.protocol = protocol;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String displayName() {
        return "Strands Decider 2B";
    }

    @Override
    public String vendor() {
        return "Strands";
    }

    @Override
    public String description() {
        return "2B 判定模型（Qwen3.5-2B + 判定头），语义区分度更高，逐状态调用、可选视觉输入。";
    }

    @Override
    public DecisionProtocol protocol() {
        return protocol;
    }

    @Override
    public String defaultModelId() {
        return ID;
    }

    @Override
    public String defaultBaseUrl() {
        return "http://127.0.0.1:8000/v1";
    }

    @Override
    public String defaultEvaluatePath() {
        return JevSystemOneProtocol.DEFAULT_PATH;
    }

    @Override
    public boolean localDeployable() {
        return true;
    }

    @Override
    public String weightsRepo() {
        return "StrandsAgents/strands-decider-2B-hobson-v21";
    }

    @Override
    public String weightsRevision() {
        return "main";
    }

    @Override
    public String runtimeKind() {
        return "pip";
    }

    @Override
    public List<String> pipPackages() {
        return List.of("strands-decider");
    }

    @Override
    public List<String> weightFilePatterns() {
        return List.of("*.safetensors", "*.json", "tokenizer/*", "*.model");
    }

    @Override
    public long approxWeightsBytes() {
        // 2B bf16 ≈ 4GB + LoRA/head
        return 4_500_000_000L;
    }

    @Override
    public int defaultLocalPort() {
        return 8000;
    }

    @Override
    public String licenseName() {
        return "Apache-2.0";
    }

    @Override
    public String docsUrl() {
        return "https://github.com/strands-agents/strands-decider";
    }

    @Override
    public LocalEndpoint localEndpoint(int port) {
        return new LocalEndpoint("http://127.0.0.1:" + port + "/v1", "/systemone");
    }

    @Override
    public String consoleScript() {
        return "strands-decider";
    }

    @Override
    public List<String> consoleArgs(String weightsDir, int port) {
        return List.of("serve", weightsDir, "--port", String.valueOf(port));
    }

    @Override
    public List<String> serveCommand(String pythonExe, String repoDir, String weightsDir, int port) {
        return List.of(pythonExe, "-m", "strands_decider", "serve", weightsDir,
                "--port", String.valueOf(port));
    }
}
