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

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 决策模型接入配置（{@code holzyn.actor.ai.decision.*}）。
 *
 * <p><b>通用化说明</b>：本配置类描述的是「一家可插拔的决策模型」——
 * 具体接哪家由 {@code adapter} 决定（{@code nanojev} / {@code strands-decider-2b} / {@code laya}，
 * 见 {@code com.holzyn.actor.ai.decision.adapter}），本类不假设任何单一模型的端点与模型名。</p>
 *
 * <p><b>两种运行形态</b>（{@code mode}）：</p>
 * <ul>
 *   <li>{@code api}（默认）：走 {@code base-url} 指向的远端/自托管网关；</li>
 *   <li>{@code local}：走本机托管的模型进程（{@code local.*}，由设置页一键下载安装）。</li>
 * </ul>
 *
 * <p><b>鉴权</b>：{@code api-key} 非空即专用；为空时经 {@code ModelApiService} 复用当前
 * 文本模型供应商的 Key（本地形态通常不需要 Key）。</p>
 *
 * <p>⚠️ 冒烟教训：嵌套配置类禁用 record（构造器绑定依赖 {@code -parameters} 编译标志，
 * 本项目未启用 → 绑定静默失败全部回退默认）；一律普通 POJO + {@code @Getter/@Setter}。</p>
 */
@Component
@ConfigurationProperties(prefix = "holzyn.actor.ai.decision")
@Getter
@Setter
public class DecisionProperties {

    /** 判定类调用总开关（默认 false=未配置前一律禁用，调用方回退文本模型/程序化，fail-closed） */
    private boolean enabled = false;

    /** 运行形态：api（远端网关）/ local（本机托管模型进程） */
    private String mode = "api";

    /** 适配器 ID（nanojev / strands-decider-2b / laya；空=缺省适配器） */
    private String adapter = "";

    /**
     * 决策模型网关 Base URL（api 形态使用；已含 /v1，如 https://gateway.example.com/v1；
     * 空=未配置，客户端 isEnabled()=false，调用方回退）
     */
    private String baseUrl = "";

    /**
     * 专用 API Key（可空。默认空=沿用当前文本模型供应商的 Key；
     * 非空=直接使用本 Key，不再解析主供应商）
     */
    private String apiKey = "";

    /** 记账/健康检查用的模型名（空=适配器默认） */
    private String modelId = "";

    /** evaluate 路径覆盖（空=适配器/协议默认；用于同一协议换服务端路径的场合） */
    private String evaluatePath = "";

    /** 建连超时（秒，默认 10） */
    private int timeoutConnectSeconds = 10;

    /** 读超时（秒，默认 30：决策模型单次前向数十 ms 级，30s 足够宽松） */
    private int timeoutReadSeconds = 30;

    /** 单次请求总时长上限（秒，默认 60：> 读超时，作为硬性兜底） */
    private int timeoutRequestSeconds = 60;

    /** 失败重试次数（默认 1：总计最多尝试 2 次；仍失败 fail-closed 返回 null） */
    private int maxRetries = 1;

    /** 单次请求 states 上限（批量协议契约，默认 32） */
    private int maxStatesPerRequest = 32;

    /** 单次请求 questions 上限（默认 96） */
    private int maxQuestionsPerRequest = 96;

    /** 单次请求候选路径上限（默认 256；choice 项数 + boolean/score 各计 1） */
    private int maxPathsPerRequest = 256;

    /** 单次请求体大小上限（默认 2MB） */
    private int maxBodyBytes = 2 * 1024 * 1024;

    /** 采样段 */
    private Sampling sampling = new Sampling();

    /** 本地部署段 */
    private Local local = new Local();

    /** 采样配置：sample=按分布加权随机（默认，真实优先）/ argmax=取概率最大项（测试与回放） */
    @Getter
    @Setter
    public static class Sampling {
        /** 采样模式：sample（默认）/ argmax */
        private String mode = "sample";
        /** 固定随机种子（可空=每次随机；注入后采样可复现，供测试/回放） */
        private Long seed;
    }

    /**
     * 本地部署配置（模型本体 + 便携运行时都在本机数据目录，桌面安装包不含任何权重文件）。
     */
    @Getter
    @Setter
    public static class Local {
        /** 本地部署总开关（false=设置页只展示不支持，下载/启动接口一律 403） */
        private boolean enabled = true;

        /** 模型根目录（空=数据目录下 decision-models） */
        private String dir = "";

        /** 默认下载源：hf-mirror（国内镜像）/ huggingface（官方） */
        private String source = "hf-mirror";

        /** 便携 Python 版本（uv 引导的 CPython 版本） */
        private String pythonVersion = "3.12";

        /** 是否在应用启动时自动拉起上次选中的本地模型 */
        private boolean autoStart = true;

        /** 下载并发分片数（单文件 Range 续传的分段数） */
        private int downloadParallelism = 2;

        /** 托管进程空闲多久后自动停止（分钟；0=不自动停） */
        private int idleStopMinutes = 0;
    }
}
