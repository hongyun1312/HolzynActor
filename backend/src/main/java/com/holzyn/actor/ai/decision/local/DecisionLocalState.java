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

package com.holzyn.actor.ai.decision.local;

import lombok.Getter;
import lombok.Setter;

/**
 * 本地决策模型的安装/运行状态（持久化到 {@code <模型目录>/adapters/<id>/state.json}）。
 *
 * <p><b>为什么落盘</b>：下载是 GB 级长任务，进程重启后必须能回答「装到哪一步了」，
 * 而不是让用户重新下载一遍；状态机同时被设置页轮询展示。</p>
 *
 * <p>状态机：{@code not-installed → downloading → verifying → provisioning → installed
 * → starting → running ⇄ stopped}，任一步失败 → {@code error}（保留 {@code lastError}）。</p>
 */
@Getter
@Setter
public class DecisionLocalState {

    /** 未安装 */
    public static final String STATUS_NOT_INSTALLED = "not-installed";
    /** 下载中 */
    public static final String STATUS_DOWNLOADING = "downloading";
    /** 校验中（sha256/文件完整性） */
    public static final String STATUS_VERIFYING = "verifying";
    /** 安装依赖中（Python 运行时 + pip 依赖） */
    public static final String STATUS_PROVISIONING = "provisioning";
    /** 已安装（未启动） */
    public static final String STATUS_INSTALLED = "installed";
    /** 启动中（等待健康检查通过） */
    public static final String STATUS_STARTING = "starting";
    /** 运行中（健康检查通过） */
    public static final String STATUS_RUNNING = "running";
    /** 已停止（用户主动停止） */
    public static final String STATUS_STOPPED = "stopped";
    /** 失败（见 lastError） */
    public static final String STATUS_ERROR = "error";

    /** 适配器 ID */
    private String adapterId = "";

    /** 状态（见常量） */
    private String status = STATUS_NOT_INSTALLED;

    /** 面向用户的一句话进度说明 */
    private String message = "";

    /** 已下载字节数 */
    private long downloadedBytes;

    /** 预计总字节数（0=未知） */
    private long totalBytes;

    /** 下载源（hf-mirror / huggingface） */
    private String source = "";

    /** 权重目录（绝对路径） */
    private String weightsDir = "";

    /** 源码目录（repo-script 形态；可空） */
    private String repoDir = "";

    /** 虚拟环境目录 */
    private String venvDir = "";

    /** 虚拟环境 Python 可执行文件（绝对路径） */
    private String pythonExe = "";

    /** 监听端口（0=未启动） */
    private int port;

    /** 托管进程 PID（0=未运行） */
    private long pid;

    /** 最近一次错误（面向用户） */
    private String lastError = "";

    /** 安装完成时间（ISO-8601；空=未安装） */
    private String installedAt = "";

    /** 状态更新时间（ISO-8601） */
    private String updatedAt = "";

    /** 用户自定义启动命令（空=用适配器默认命令；排障逃生门） */
    private String customCommand = "";

    /** 是否已完成安装（可启动）。 */
    public boolean installed() {
        return !installedAt.isBlank() && !STATUS_NOT_INSTALLED.equals(status)
                && !STATUS_DOWNLOADING.equals(status) && !STATUS_PROVISIONING.equals(status)
                && !STATUS_ERROR.equals(status);
    }

    /** 是否处于可取消的进行中状态。 */
    public boolean busy() {
        return STATUS_DOWNLOADING.equals(status) || STATUS_VERIFYING.equals(status)
                || STATUS_PROVISIONING.equals(status) || STATUS_STARTING.equals(status);
    }

    /** 进度百分比（0~100；总量未知返回 -1）。 */
    public int progressPercent() {
        if (totalBytes <= 0) {
            return -1;
        }
        long done = Math.min(downloadedBytes, totalBytes);
        return (int) Math.min(100, Math.max(0, done * 100 / totalBytes));
    }

    /** 深拷贝（对外返回快照，避免调用方改到运行中的状态对象）。 */
    public DecisionLocalState copy() {
        DecisionLocalState s = new DecisionLocalState();
        s.adapterId = adapterId;
        s.status = status;
        s.message = message;
        s.downloadedBytes = downloadedBytes;
        s.totalBytes = totalBytes;
        s.source = source;
        s.weightsDir = weightsDir;
        s.repoDir = repoDir;
        s.venvDir = venvDir;
        s.pythonExe = pythonExe;
        s.port = port;
        s.pid = pid;
        s.lastError = lastError;
        s.installedAt = installedAt;
        s.updatedAt = updatedAt;
        s.customCommand = customCommand;
        return s;
    }
}
