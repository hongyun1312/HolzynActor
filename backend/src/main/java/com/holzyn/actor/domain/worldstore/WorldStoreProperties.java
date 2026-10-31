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

package com.holzyn.actor.domain.worldstore;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 世界存档存储配置（{@code holzyn.actor.world-store.*}，V1.1.2-beta.1）。
 *
 * <p>为什么单独开一段而不是复用 {@code holzyn.actor.world.*}：后者是"世界模拟/时间比/调度"
 * 的既有业务配置段（{@code WorldConfigYamlTest} 在守护），本次是**存储布局**，语义不同；
 * 混在一段会让"改时间比"与"改存档目录"共用一个命名空间，排查时无法区分归属。</p>
 *
 * <p>为什么 {@code allow-multi-mount} 固定 false：分档方案的安全性建立在"同时只有一个世界库
 * 被打开"之上——路由键丢失时回落到"当前挂载世界"是安全的，因为不存在第二个可写世界。
 * 一旦允许并行挂载，这个前提失效，必须改成线程级显式路由（63 处自建线程池全部要挂
 * TaskDecorator），本版本刻意为 false 并由启动期断言兜底。</p>
 *
 * <p>所属模块：domain/worldstore（世界存档存储）</p>
 */
@Component
@ConfigurationProperties(prefix = "holzyn.actor.world-store")
@Getter
@Setter
public class WorldStoreProperties {

    /**
     * 世界根目录（空 = 派生为 {@code <data-dir>/worlds}）。
     * <p>默认派生而非写死，是为了让存档目录跟着数据目录走：写绝对路径会让整个
     * {@code worlds/} 与某台机器绑定，"拷文件夹即带走存档"就失效。</p>
     */
    private String rootDir = "";

    /** 目录库文件名（相对 data-dir，不带 {@code .mv.db} 后缀） */
    private String registryFile = "holzyn-registry";

    /** 是否允许同时挂载多个世界（单活动世界模型下必须为 false） */
    private boolean allowMultiMount = false;

    /** 每次 open 时从目录库重建 12 张全局只读镜像表 */
    private boolean mirrorRefreshOnOpen = true;

    /** 世界库连接池上限 */
    private int poolSize = 4;

    /** 关世界时执行 {@code SHUTDOWN COMPACT}（H2 默认只压缩约 200ms，不显式压缩不回收空间） */
    private boolean compactOnClose = true;

    /**
     * 目录库为空且存在旧单库时自动执行一次性迁移（{@code LegacyWorldSplitMigrator}）。
     *
     * <p>默认 true：升级安装的用户第一次启动就会自动把旧的单体库拆成目录库 + 世界库，
     * 不需要任何手工步骤。设 false 只用于"想先看迁移报告再动手"的排障场景——
     * 关掉后应用会以空目录库启动，旧世界在存档列表里看不到（但旧库文件仍在原处，可再开）。</p>
     */
    private boolean migrateLegacy = true;

    /**
     * 归档根目录（空 = 派生为 {@code <data-dir>/archive}）。
     *
     * <p>归档里放两类东西：迁移前的旧单体库整份拷贝（{@code pre-worldsplit-<ts>/}）与被归档删除的
     * 世界目录（{@code deleted-worlds/<uid>-<ts>/}）。它必须与数据目录同一个根，
     * 否则会出现"世界在 A 盘、备份在 B 盘"，换机时只搬走了一半。</p>
     */
    private String archiveDir = "";
}
