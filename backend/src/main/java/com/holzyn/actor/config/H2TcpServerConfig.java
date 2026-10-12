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

package com.holzyn.actor.config;

import com.holzyn.actor.common.DataDirResolver;
import org.h2.tools.Server;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * H2 TCP Server 配置（，应用运行中外部工具直连能力）。
 * <p>背景：本地 H2 文件库（{@code jdbc:h2:file:...}）被应用进程独占文件锁——应用运行中
 * DataGrip 等外部工具无法直连文件。本配置在<b>应用同 JVM 内</b>启动一个 H2 TCP Server，
 * 客户端经 {@code jdbc:h2:tcp://localhost:9092/holzyn-actor} 连接时，TCP Server 解析到
 * <b>与数据源完全相同的绝对路径</b>（{@link #resolvedDataDir()} 与 {@link DataDirResolver}
 * 同一解析口径），复用该 JVM 已打开的库实例——<b>不产生第二个文件锁、不双开文件</b>，
 * 共享同一份数据且无写坏风险；应用停止时 TCP Server 随 bean destroyMethod 自动关闭。</p>
 * CASE_INSENSITIVE_IDENTIFIERS=TRUE}，用户 {@code sa}，密码空；仅本机 loopback（{@code -tcpAllowOthers}
 * 默认关闭，局域网/远程 DataGrip 需显式开启）。</p>
 * <p>开关：{@code holzyn.actor.h2.tcp-enabled}（默认 true）/ {@code tcp-port}（默认 9092）/
 * {@code tcp-allow-others}（默认 false）。</p>
 */
@Configuration
public class H2TcpServerConfig {

    /** H2 TCP 默认端口（与 8080 主端口错开；DataGrip 连接用） */
    public static final int DEFAULT_TCP_PORT = 9092;

    /**
     * H2 TCP Server bean（生命周期 start/stop 由 Spring 管理）。
     *
     * @param tcpPort 监听端口（application.yml holzyn.actor.h2.tcp-port，默认 9092）
     *
     * @param allowOthers 是否允许非本机连接（默认 false=仅本机 loopback）
     *
     * @return H2 TCP Server 实例
     *
     * @throws SQLException 创建失败（端口被占用等）
     */
    @Bean(initMethod = "start", destroyMethod = "stop")
    @ConditionalOnProperty(name = "holzyn.actor.h2.tcp-enabled", havingValue = "true", matchIfMissing = true)
    public Server h2TcpServer(
            @Value("${holzyn.actor.h2.tcp-port:9092}") int tcpPort,
            @Value("${holzyn.actor.h2.tcp-allow-others:false}") boolean allowOthers) throws SQLException {
        List<String> args = new ArrayList<>();
        args.add("-tcpPort");
        args.add(String.valueOf(tcpPort));
        // 关键：-baseDir 与数据源解析到同一绝对数据目录，客户端 jdbc:h2:tcp://localhost:9092/holzyn-actor
        // 被解析为 <数据目录>/holzyn-actor，与应用的 file: URL 命中同一库名 → 复用同 JVM 已打开的实例
        args.add("-baseDir");
        args.add(resolvedDataDir());
        if (allowOthers) {
            // 默认仅本机（loopback）；局域网/远程 DataGrip 才需要 true（注意：本库无鉴权，勿公网暴露）
            args.add("-tcpAllowOthers");
        }
        return Server.createTcpServer(args.toArray(String[]::new));
    }

    /**
     * 解析与数据源完全一致的绝对数据目录（可独立单测）。
     * <p>口径与 {@link DataDirResolver} 一致：系统属性（DataDirResolver 已在 main 归一化写入）
     * 优先 > 环境变量 > 缺省 {@code ./data}；统一 toAbsolutePath+normalize，保证 TCP baseDir
     * 与数据源 file URL 指向同一绝对路径。</p>
     *
     * @return 绝对数据目录（如 {@code C:\Users\...\HolzynActor\data}）
     */
    static String resolvedDataDir() {
        String dir = System.getProperty(DataDirResolver.ENV_DATA_DIR);
        if (dir == null || dir.isBlank()) {
            dir = System.getenv(DataDirResolver.ENV_DATA_DIR);
        }
        if (dir == null || dir.isBlank()) {
            dir = "./data";
        }
        return Path.of(dir).toAbsolutePath().normalize().toString();
    }
}
