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

package com.holzyn.actor.domain.dev.service;

import com.holzyn.actor.common.DataDirResolver;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;

/**
 * 开发者参数覆盖文件（{@code <data-dir>/dev-param-overrides.properties}）的读写与路径解析。
 *
 * <p><b>为什么需要这个文件</b>：开发者模式里能改的不只是「有 {@code @ConfigurationProperties} bean 的键」，
 * 还有大量只被 {@code @Value} 或 Spring 基础设施读取的启动期键（如 {@code holzyn.actor.ai.timeout-read-seconds}、
 * {@code holzyn.actor.log.file}）。这类键改内存没有意义——它们只在启动那一刻被解析一次。
 * 于是把它们写进这个 properties 文件，启动最早期由 {@link DevOverrideEnvironmentPostProcessor}
 * 以**最高优先级**注入 Environment：{@code @ConfigurationProperties} 绑定与 {@code @Value} 解析
 * 都会先看到覆盖值，效果等同于「改了 application.yml 再重启」，但不需要用户手改文件。</p>
 *
 * <p><b>为什么不用 yml</b>：Properties 解析不依赖 SnakeYAML 的文档结构语义，单行一个键，坏了也只坏一行；
 * 而且 {@code Properties.load/store} 是 JDK 自带，启动早期不需要任何第三方依赖。</p>
 *
 * <p><b>失败兜底</b>：文件不存在 → 直接返回空（正常路径）；解析/写入异常 → WARN 后继续，
 * 绝不因为「开发者参数文件坏了」让世界起不来。</p>
 *
 * <p>所属模块：domain/dev（开发者模式控制台）</p>
 */
@Slf4j
public final class DevOverrideFile {

    /** 覆盖文件名（放在数据目录根部，与其他运行产物同级，便于备份/排查） */
    public static final String FILE_NAME = "dev-param-overrides.properties";

    /** PropertySource 名称（启动日志与调试时用它定位「值到底来自哪一层」） */
    public static final String SOURCE_NAME = "devParamOverrides";

    private DevOverrideFile() {
    }

    /**
     * 解析覆盖文件的绝对路径。
     *
     * <p>数据目录口径与全仓一致（AGENTS §8.5）：优先 {@code -DHOLOZYN_ACTOR_DATA_DIR} 系统属性
     * （{@code DataDirResolver.normalizeDefaults()} 在 Spring 启动前写入），其次同名环境变量，
     * 最后回退 {@code ./data}（与 application.yml 的占位符默认值一致）。</p>
     *
     * @return 覆盖文件路径（不保证存在）
     */
    public static Path path() {
        String dir = System.getProperty(DataDirResolver.ENV_DATA_DIR);
        if (dir == null || dir.isBlank()) {
            dir = System.getenv(DataDirResolver.ENV_DATA_DIR);
        }
        if (dir == null || dir.isBlank()) {
            dir = "./data";
        }
        return Path.of(dir).resolve(FILE_NAME).toAbsolutePath();
    }

    /**
     * 读取全部覆盖（键升序）。
     *
     * @return 覆盖键值（文件不存在或读失败返回空 Map，不抛异常）
     */
    public static Map<String, String> readAll() {
        Path file = path();
        if (!Files.exists(file)) {
            return new LinkedHashMap<>();
        }
        Properties props = new Properties();
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            props.load(reader);
        } catch (Exception e) {
            log.warn("[开发者模式] 覆盖文件读取失败，本轮按「无覆盖」处理：{}（{}）", file, e.getMessage());
            return new LinkedHashMap<>();
        }
        Map<String, String> out = new TreeMap<>();
        for (String name : props.stringPropertyNames()) {
            out.put(name, props.getProperty(name));
        }
        return out;
    }

    /**
     * 全量重写覆盖文件（先写临时文件再原子替换，避免中途崩溃留下半个文件）。
     *
     * @param overrides 覆盖键值（全量；空 Map 等价于删除文件内容）
     *
     * @throws IOException 写入失败（由调用方决定是否降级）
     */
    public static void writeAll(Map<String, String> overrides) throws IOException {
        Path file = path();
        Files.createDirectories(file.getParent());
        if (overrides == null || overrides.isEmpty()) {
            Files.deleteIfExists(file);
            log.info("[开发者模式] 覆盖已清空（{}）", file);
            return;
        }
        Properties props = new Properties();
        new TreeMap<>(overrides).forEach(props::setProperty);
        Path tmp = file.resolveSibling(FILE_NAME + ".tmp");
        try (Writer writer = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
            props.store(writer, "HolzynActor 开发者模式参数覆盖（由控制台维护，手改也生效；删掉即全部恢复默认）");
        }
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        log.info("[开发者模式] 覆盖文件已重写：{} 条（{}）", overrides.size(), file);
    }
}
