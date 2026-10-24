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

package com.holzyn.actor.domain.mapgen.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 世界地图生成管线配置（前缀 {@code holzyn.actor.mapgen}；env 松弛绑定：点→下划线、连字符删除）。
 * <p>三条纪律写在这里而不是散落成常量：</p>
 * <ul>
 *   <li><b>尺度只有一个来源</b>：{@link #size} × {@link #metersPerCell} 决定整张图的物理大小，
 *       算法层不许再出现「1024」这类魔法数字（改尺度只改配置 + 不用迁移数据，因为库里存的是米）；</li>
 *   <li><b>AI 可整体关闭</b>：{@link #aiEnabled}=false 时 S0/S2/S3 全部走确定性兜底
 *       （内置海岛档案 + 内置资产库 + 程序化路网拓扑），管线仍能出图——离线、单测、AI 挂掉都不至于白屏；</li>
 *   <li><b>AI 调用次数有上限</b>：{@link #aiRetriesJustification} 只是文档锚点，真正的重试在
 *       各 Stage 内部（失败回落兜底而不是无限重试——地图生成不能因为供应商抖动卡住不返回）。</li>
 * </ul>
 */
@Data
@Component
@ConfigurationProperties(prefix = "holzyn.actor.mapgen")
public class MapGenProperties {

    /** 管线总开关（false=拒绝生成，查询照常） */
    private boolean enabled = true;

    /** AI 开关（false=S0/S2/S3 全走确定性兜底，产出「内置海岛 + 内置资产 + 程序化路网」） */
    private boolean aiEnabled = true;

    /** 管线版本（落库到 {@code actor_world_map.version}，便于识别旧数据） */
    private int pipelineVersion = 4;

    /** 地图边长（格） */
    private int size = 1024;

    /** 每格米数（1 格 = 1 米；坐标一律按米存库） */
    private double metersPerCell = 1.0;

    /** 输出根目录（空=数据目录下 {@code worldmap}；地形图/合成图落这里） */
    private String outputDir = "";

    /**
     * 写实底图倍率（像素 / 米）。
     *
     * <p>4.0 = 4096²（1 米 1 格的地形栅格放大 4 倍），近景能看到屋脊、田埂与院墙厚度；
     * 2.0 = 2048² 是旧基线，整岛视野够用但放大就糊。显存/磁盘紧张时调回 2.0 即可，
     * 前端 LOD 会自动少一档（path 仍按 1024/2048/4096 请求，缺档时后端 404、前端降级）。</p>
     */
    private double compositeScale = 4.0;

    /**
     * 是否输出调试诊断计数（{@code stats.diagnostics}）。
     *
     * <p>这些计数（尝试/不可建/占用/镇外/田占用…）是调参期埋点，稳定后应关掉——
     * 它们会让每张图的 {@code stats_json} 多出几十个键，前端面板与验收报告都要过滤。
     * 默认仍开：本轮还在调参，关掉会失去「为什么这块地放不下」的唯一线索。</p>
     */
    private boolean diagnostics = true;

    /** 单次生成的 AI 重试次数（不含首次；失败仍会回落确定性兜底） */
    private int aiRetries = 1;

    /** AI 调用温度（S0/S2 用；S3 拓扑用 {@link #roadTemperature}） */
    private double temperature = 0.5;

    /** S3 路网拓扑温度（结构化输出，低一点更稳） */
    private double roadTemperature = 0.4;

    /** AI 输出上限（S0 档案 / S2 资产库） */
    private int profileMaxTokens = 2048;

    /** S2 资产库输出上限 */
    private int assetMaxTokens = 8192;

    /** S3 拓扑输出上限 */
    private int roadMaxTokens = 4096;

    /** 单次生成的超时上限（毫秒；超时按失败收尾并把 stage 停在当时阶段，便于单阶段重跑） */
    private long runTimeoutMs = 600_000L;
}
