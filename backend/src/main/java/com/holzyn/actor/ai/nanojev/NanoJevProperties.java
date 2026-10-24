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

package com.holzyn.actor.ai.nanojev;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * NanoJev 并行决策模型接入配置。
 * <p>对应《HolzynActor_NanoJev并行决策模型接入_开发路径 .md》§7 「配置
 * {@code holzyn.actor.ai.nanojev.*}（base-url/开关/超时/批上限）」。</p>
 * <p><b>供应商/鉴权来源</b>：base-url 走本配置
 * （默认空=未配置即禁用，fail-closed；外网机示例 {@code https://llm.mbfsr.com:14443/v1}，
 * 内网机 HONGYUN 示例 {@code http://127.0.0.1:9100/v1}），<b>API Key 默认不存本配置</b>——
 * 经 {@code ModelApiService.resolveProviderConfig} 复用当前 chat provider 的密钥
 * （NanoJev 与 Qwen3.6-35B 同网关同 Key，双环境天然跟随 DB 配置，不重复存 Key）。</p>
 * <p><b>⚠️ NanoJev 专用 Key</b>：当<b>主模型与 NanoJev 不在同一网关</b>
 * 时（本轮主模型 = DeepSeek 官方 {@code api.deepseek.com}，NanoJev 仍在内网网关
 * {@code 127.0.0.1:9100/v1}），复用主模型 Key 会被网关判 401（实测
 * {@code invalid_api_key}）→ 全部判定 fail-closed 静默回退、16 个判定场景集体失效且不报错。
 * 故新增本项：<b>非空即专用</b>（不再解析主供应商），<b>默认空 = 沿用主供应商 Key</b>，
 * 与第 1/2 轮行为逐字节一致（开关式回退，无行为变更）。</p>
 * <p><b>⚠️ 静默回退防护</b>：{@code /v1/chat/completions} 传 {@code model=nanojev} 会静默
 * 回退 qwen3.6-35b-q4-un ——本客户端只走 {@code POST /v1/nanojev/evaluate}，
 * 响应必须断言 {@code execution.forward_passes=1} 且无 chat 形状（choices 字段）。</p>
 * <p><b>采样</b>：{@code sampling.mode}=sample（默认，按完整概率分布加权随机，
 * 真实优先/行为多样）| argmax（确定性，测试与回放用）；{@code sampling.seed} 可注入固定种子。</p>
 * <p>⚠️ 冒烟教训：嵌套档位类禁用 record（构造器绑定依赖 -parameters 编译标志，
 * 项目未启用 → 绑定静默失败全部回退默认）；一律用普通 POJO（@Getter/@Setter），
 * 注册方式与项目既有配置类一致 = @Component + @ConfigurationProperties。</p>
 */
@Component
@ConfigurationProperties(prefix = "holzyn.actor.ai.nanojev")
@Getter
@Setter
public class NanoJevProperties {

    /** NanoJev 模型 ID（/v1/models 中的 id；调度/记账/trace 的 model 字段统一取此值） */
    public static final String MODEL_ID = "nanojev";

    /** evaluate 端点路径（网关 OpenAI 兼容前缀下追加；base-url 已含 /v1） */
    public static final String EVALUATE_PATH = "/nanojev/evaluate";

    /** 模型列表端点（健康检查用：确认 nanojev 在列，防静默回退/未部署） */
    public static final String MODELS_PATH = "/models";

    /** 判定类调用总开关（默认 false=未配置 base-url 前一律禁用，fail-closed） */
    private boolean enabled = false;

    /**
     * NanoJev evaluate 网关 Base URL（已含 /v1，如 https://llm.mbfsr.com:14443/v1；
     * 默认空=未配置，客户端 isEnabled()=false，调用方回退 Qwen/程序化）
     */
    private String baseUrl = "";

    /**
     * NanoJev 专用 API Key（可空。默认空=沿用当前 chat provider 的 Key（第 1/2 轮行为）；
     * 非空=直接使用本 Key，不再解析主供应商（主模型换到外部网关时必须显式配置））
     */
    private String apiKey = "";

    /** 建连超时（秒，默认 10） */
    private int timeoutConnectSeconds = 10;

    /** 读超时（秒，默认 30：NanoJev 单次前向数十 ms 级，网关往返实测 ≈0.77s，30s 足够宽松） */
    private int timeoutReadSeconds = 30;

    /** 单次请求总时长上限（秒，默认 60：> 读超时，作为硬性兜底覆盖整次调用） */
    private int timeoutRequestSeconds = 60;

    /** 失败重试次数（默认 1：总计最多尝试 2 次；仍失败 fail-closed 返回 null） */
    private int maxRetries = 1;

    /** 单次请求 states 上限（官方契约 ≤32） */
    private int maxStatesPerRequest = 32;

    /** 单次请求 questions 上限（官方契约 ≤96） */
    private int maxQuestionsPerRequest = 96;

    /** 单次请求候选路径上限（官方契约 ≤256；choice 项数 + boolean/score 各计 1） */
    private int maxPathsPerRequest = 256;

    /** 单次请求体大小上限（官方契约 1B~2MB，默认 2MB） */
    private int maxBodyBytes = 2 * 1024 * 1024;

    /** 采样段 */
    private Sampling sampling = new Sampling();

    /** 采样配置：sample=按分布加权随机（默认，真实优先）/ argmax=取概率最大项（测试与回放） */
    @Getter
    @Setter
    public static class Sampling {
        /** 采样模式：sample（默认）/ argmax */
        private String mode = "sample";
        /** 固定随机种子（可空=每次随机；注入后采样可复现，供测试/回放） */
        private Long seed;
    }
}
