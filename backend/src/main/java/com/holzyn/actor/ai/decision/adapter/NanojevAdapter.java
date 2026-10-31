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
import com.holzyn.actor.ai.decision.protocol.NanojevBatchProtocol;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 适配器：<b>NanoJev</b>（0.6B 并行决策模型，批量状态协议）。
 *
 * <p>权重仓库 {@code C-Tianyu/NanoJev}（revision {@code unified-games-v1}，Apache-2.0，
 * backbone {@code Qwen3-0.6B}）；本地服务由官方脚本
 * {@code scripts/serve_decisions.py} 提供，端点 {@code POST /api/evaluate}；
 * 远端网关形态为 {@code POST /v1/nanojev/evaluate}（本项目既有接入）。</p>
 */
@Component
public class NanojevAdapter implements DecisionAdapter {

    /** 适配器 ID（同时是本地部署目录名与配置值） */
    public static final String ID = "nanojev";

    private final DecisionProtocol protocol;

    /**
     * 构造（协议由 Spring 注入，便于按协议复用同一套实现）。
     *
     * @param protocol 批量状态协议
     */
    @Autowired
    public NanojevAdapter(NanojevBatchProtocol protocol) {
        this.protocol = protocol;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String displayName() {
        return "NanoJev 0.6B";
    }

    @Override
    public String vendor() {
        return "C-Tianyu";
    }

    @Override
    public String description() {
        return "0.6B 并行决策模型，批量状态一次前向出全部概率分布，零解码、延迟最低。";
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
        return "http://127.0.0.1:9100/v1";
    }

    @Override
    public String defaultEvaluatePath() {
        return NanojevBatchProtocol.DEFAULT_PATH;
    }

    @Override
    public boolean localDeployable() {
        return true;
    }

    @Override
    public String weightsRepo() {
        return "C-Tianyu/NanoJev";
    }

    @Override
    public String weightsRevision() {
        return "unified-games-v1";
    }

    @Override
    public String runtimeKind() {
        return "repo-script";
    }

    @Override
    public List<String> pipPackages() {
        return List.of("torch", "transformers", "safetensors", "fastapi", "uvicorn", "huggingface_hub");
    }

    @Override
    public List<String> weightFilePatterns() {
        return List.of("*.safetensors", "config.json", "tokenizer/*", "backbone_config/*");
    }

    @Override
    public long approxWeightsBytes() {
        // 0.6B bf16 ≈ 1.2GB 权重 + 分词器/配置
        return 1_300_000_000L;
    }

    @Override
    public int defaultLocalPort() {
        return 8765;
    }

    @Override
    public String licenseName() {
        return "Apache-2.0";
    }

    @Override
    public String docsUrl() {
        return "https://github.com/TianyuCodings/NanoJev";
    }

    @Override
    public LocalEndpoint localEndpoint(int port) {
        return new LocalEndpoint("http://127.0.0.1:" + port, "/api/evaluate");
    }

    @Override
    public String sourceRepo() {
        return "TianyuCodings/NanoJev";
    }

    @Override
    public List<String> serveCommand(String pythonExe, String repoDir, String weightsDir, int port) {
        return List.of(pythonExe, repoDir + "/scripts/serve_decisions.py",
                "--checkpoint-dir", weightsDir,
                "--port", String.valueOf(port),
                "--disable-native-triton");
    }
}
