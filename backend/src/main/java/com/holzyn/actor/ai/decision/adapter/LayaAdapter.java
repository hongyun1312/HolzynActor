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
import java.util.Map;

/**
 * 适配器：<b>Laya</b>（ModernBERT 421M 决策模型族，Jev 单 state 协议）。
 *
 * <p>权重仓库 {@code convaiinnovations/laya}（另有 {@code laya-multilingual} 覆盖 100+ 语言，
 * Apache-2.0）；官方 CLI {@code laya-serve}，端点 {@code POST /v1/systemone} 与 {@code GET /health}，
 * 布尔题型名为 {@code noul}，state 支持 JSON 对象/数组。</p>
 */
@Component
public class LayaAdapter implements DecisionAdapter {

    /** 适配器 ID */
    public static final String ID = "laya";

    private final DecisionProtocol protocol;

    /**
     * 构造。
     *
     * @param protocol Jev 单 state 协议
     */
    @Autowired
    public LayaAdapter(JevSystemOneProtocol protocol) {
        this.protocol = protocol;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String displayName() {
        return "Laya 421M";
    }

    @Override
    public String vendor() {
        return "ConvAI Innovations";
    }

    @Override
    public String description() {
        return "421M 轻量判定模型（多语言版 322M），显存占用最低，适合低配机器常驻。";
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
        return "convaiinnovations/laya";
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
        return List.of("laya[serve]");
    }

    @Override
    public List<String> weightFilePatterns() {
        return List.of("*.safetensors", "*.json", "tokenizer/*", "*.model");
    }

    @Override
    public long approxWeightsBytes() {
        // ModernBERT-base 421M ≈ 850MB（含 tokenizer/配置余量）
        return 900_000_000L;
    }

    @Override
    public int defaultLocalPort() {
        return 8001;
    }

    @Override
    public String licenseName() {
        return "Apache-2.0";
    }

    @Override
    public String docsUrl() {
        return "https://github.com/convaiinnovations/laya";
    }

    @Override
    public LocalEndpoint localEndpoint(int port) {
        return new LocalEndpoint("http://127.0.0.1:" + port + "/v1", "/systemone");
    }

    @Override
    public String consoleScript() {
        return "laya-serve";
    }

    @Override
    public List<String> consoleArgs(String weightsDir, int port) {
        return List.of("--model", weightsDir, "--host", "127.0.0.1", "--port", String.valueOf(port));
    }

    @Override
    public List<String> serveCommand(String pythonExe, String repoDir, String weightsDir, int port) {
        return List.of(pythonExe, "-m", "laya.serve", "--model", weightsDir,
                "--host", "127.0.0.1", "--port", String.valueOf(port));
    }

    @Override
    public Map<String, String> serveEnvironment(int port) {
        return Map.of(
                "LAYA_HOST", "127.0.0.1",
                "LAYA_PORT", String.valueOf(port),
                "LAYA_PRELOAD", "1",
                "LAYA_JEV_STRICT", "0"
        );
    }
}
