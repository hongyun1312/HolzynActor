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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 世界库「基线之后」的新增表（V201+ 增量迁移建的）。
 *
 * <p><b>为什么需要这个测试工具</b>：V200 是<b>已发布</b>的基线——改它就会改 checksum，
 * Flyway 校验会让已有世界的应用直接起不来（{@code WorldTableClassificationTest} 的
 * {@code applyRenames} 注释里已经写死了这条结论）。所以「基线之后加表」只能加增量迁移，
 * 而几条护栏（实体表必须在世界库、每个 IDENTITY 表必须有 identity）原本只看基线，
 * 会把合法的新表误报成"漏了"。这里把增量迁移里 create table 的表名收集出来，
 * 让护栏看「基线 + 增量」的并集——护栏的意图（不能有表只存在于实体里）一点没变。</p>
 *
 * <p>只认 {@code V201} 及以后：V100/V200 是基线，V20x 里 V200 本身不算增量。</p>
 */
final class WorldIncrementTables {

    /** 世界库迁移目录（相对 backend 模块根）。 */
    private static final Path WORLD_DIR = Path.of("src/main/resources/db/migration/world");

    /** 建表语句（与其它护栏同一套正则口径）。 */
    private static final Pattern CREATE_TABLE = Pattern.compile(
            "^\\s*create\\s+table\\s+(?:if\\s+not\\s+exists\\s+)?([A-Za-z0-9_]+)",
            Pattern.CASE_INSENSITIVE | Pattern.MULTILINE);

    private WorldIncrementTables() {
    }

    /**
     * 收集 V201+ 增量迁移里建的表。
     *
     * @return 表名（小写）；目录不存在返回空集（在 jar 里跑测试时不至于炸）
     */
    static Set<String> createdAfterBaseline() {
        Set<String> out = new LinkedHashSet<>();
        if (!Files.isDirectory(WORLD_DIR)) {
            return out;
        }
        List<Path> files;
        try (Stream<Path> s = Files.list(WORLD_DIR)) {
            files = s.filter(p -> p.getFileName().toString().endsWith(".sql")).sorted().toList();
        } catch (IOException e) {
            return out;
        }
        for (Path p : files) {
            if (!isIncrement(p.getFileName().toString())) {
                continue;
            }
            try {
                Matcher m = CREATE_TABLE.matcher(Files.readString(p, StandardCharsets.UTF_8));
                while (m.find()) {
                    out.add(m.group(1).toLowerCase());
                }
            } catch (IOException e) {
                // 读不到就当没有：护栏会以「缺表」的形式报出来，比静默放行安全
            }
        }
        return out;
    }

    /**
     * 增量迁移的 SQL 文本（给「identity 必须在某处声明」这类全文扫描用）。
     *
     * @return 拼接文本；目录不存在返回空串
     */
    static String incrementSql() {
        StringBuilder sb = new StringBuilder();
        if (!Files.isDirectory(WORLD_DIR)) {
            return "";
        }
        try (Stream<Path> s = Files.list(WORLD_DIR)) {
            for (Path p : s.filter(x -> x.getFileName().toString().endsWith(".sql")).sorted().toList()) {
                if (!isIncrement(p.getFileName().toString())) {
                    continue;
                }
                sb.append(Files.readString(p, StandardCharsets.UTF_8)).append('\n');
            }
        } catch (IOException e) {
            return "";
        }
        return sb.toString();
    }

    /** 文件名是否 V201+ 的增量。 */
    private static boolean isIncrement(String name) {
        if (!name.startsWith("V") || !name.endsWith(".sql")) {
            return false;
        }
        int end = name.indexOf("__");
        if (end < 0) {
            return false;
        }
        try {
            return Integer.parseInt(name.substring(1, end)) > 200;
        } catch (NumberFormatException e) {
            return false;
        }
    }
}
