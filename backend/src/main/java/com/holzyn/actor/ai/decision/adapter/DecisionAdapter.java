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

import java.util.List;

/**
 * <b>决策模型适配器</b>：一家模型 = 一个适配器（协议 + 默认端点 + 本地部署元数据）。
 *
 * <p><b>分层职责</b>：</p>
 * <ul>
 *   <li>{@link DecisionProtocol}：只管报文形状（怎么发、怎么解析）；</li>
 *   <li>本接口：管「这家模型叫什么、默认连哪、权重从哪下、怎么装、怎么起」；</li>
 *   <li>{@code DecisionCallClient}：管调用编排（批处理、重试、记账、fail-closed）。</li>
 * </ul>
 * <p>新增一家模型时不需要改调用链：实现本接口 + 一个 {@code @Component} 即自动进入注册表
 * （见 {@link DecisionAdapters}），前端设置页也会自动读到目录项。</p>
 */
public interface DecisionAdapter {

    /** 适配器 ID（配置值 {@code holzyn.actor.ai.decision.adapter}；同时作为本地部署目录名）。 */
    String id();

    /** 展示名（设置页下拉/卡片用）。 */
    String displayName();

    /** 提供方/作者（展示用）。 */
    String vendor();

    /** 一句话说明（展示用）。 */
    String description();

    /** 线协议（报文形状）。 */
    DecisionProtocol protocol();

    /** 记账 / 健康检查用的默认模型名。 */
    String defaultModelId();

    /** 远程部署时的默认 base-url（空串=必须由用户在设置页配置）。 */
    String defaultBaseUrl();

    /** 远程部署时的默认 evaluate 路径（空串=用协议默认）。 */
    String defaultEvaluatePath();

    // ==================== 本地部署元数据 ====================

    /** 是否支持本地部署（false=仅 API 调用，不下发到本地部署目录）。 */
    boolean localDeployable();

    /** 权重仓库（HuggingFace repo id，本地部署按此下载）。 */
    String weightsRepo();

    /** 权重 revision（分支/标签/commit；空=main）。 */
    String weightsRevision();

    /** 本地运行时形态：{@code repo-script}=拉官方仓库脚本起服务；{@code pip}=pip 装包起服务。 */
    String runtimeKind();

    /** pip 安装清单（{@code runtimeKind=pip} 或 repo 脚本依赖；含版本约束）。 */
    List<String> pipPackages();

    /** 权重文件通配（只下必要文件，避免整仓全量拉取）。 */
    List<String> weightFilePatterns();

    /** 权重体量估算（字节；前端展示「约 xx MB」与进度分母）。 */
    long approxWeightsBytes();

    /** 默认本地端口。 */
    int defaultLocalPort();

    /** 许可证名（展示用）。 */
    String licenseName();

    /** 官方文档/仓库地址（设置页「查看文档」）。 */
    String docsUrl();

    /**
     * 本地运行时的端点（base-url + evaluate 路径）。
     * <p><b>为什么按适配器给</b>：本地服务与自托管网关的路径约定不同
     * （官方脚本 {@code /api/evaluate} vs 网关 {@code /v1/<model>/evaluate}），
     * 硬编码一处必然在另一处 404。</p>
     *
     * @param port 本地监听端口
     *
     * @return 端点（base-url 不含尾斜杠）
     */
    LocalEndpoint localEndpoint(int port);

    /**
     * 源码仓库（{@code owner/name}；空=不需要源码，纯 pip 形态）。
     * <p>用于 {@code runtimeKind=repo-script}：本地服务需要官方仓库里的启动脚本。</p>
     */
    default String sourceRepo() {
        return "";
    }

    /** 源码仓库 revision（分支/标签；默认 main）。 */
    default String sourceRevision() {
        return "main";
    }

    /**
     * 本地启动命令（相对路径已由调用方拼成绝对路径）。
     *
     * @param pythonExe 虚拟环境里的 Python 可执行文件
     *
     * @param repoDir 源码目录（无源码时为空串）
     *
     * @param weightsDir 权重目录
     *
     * @param port 监听端口
     *
     * @return 命令数组；空=该适配器不支持本地启动（由前端提示手动配置）
     */
    default List<String> serveCommand(String pythonExe, String repoDir, String weightsDir, int port) {
        return List.of();
    }

    /**
     * 控制台脚本名（pip 安装后落在虚拟环境里的可执行入口，如 {@code xxx-serve}）。
     *
     * <p>为什么要单独声明：pip 形态的服务通常只提供 console script，{@code python -m 包名}
     * 在多数发行版上并不能启动服务（包内没有 {@code __main__}）。虚拟环境里能找到该脚本时优先用它。</p>
     *
     * @return 脚本名；空=没有控制台入口（走 {@link #serveCommand}）
     */
    default String consoleScript() {
        return "";
    }

    /**
     * 控制台脚本的启动参数（权重目录 / 监听端口）。
     *
     * @param weightsDir 权重目录
     *
     * @param port 监听端口
     *
     * @return 参数数组（默认空）
     */
    default List<String> consoleArgs(String weightsDir, int port) {
        return List.of();
    }

    /**
     * 本地进程需要的额外环境变量（各实现的环境变量名不一致，由适配器自己声明）。
     * <p>为什么放在适配器：把「某家的环境变量名」留在适配器实现里，运行时不出现任何单一模型的名字。</p>
     *
     * @param port 监听端口
     *
     * @return 环境变量（默认空）
     */
    default java.util.Map<String, String> serveEnvironment(int port) {
        return java.util.Map.of();
    }

    /**
     * 本地端点对（base-url + evaluate 路径）。
     *
     * @param baseUrl 端点根（如 {@code http://127.0.0.1:8765}）
     *
     * @param evaluatePath 相对路径（如 {@code /api/evaluate}）
     */
    record LocalEndpoint(String baseUrl, String evaluatePath) {
    }
}
