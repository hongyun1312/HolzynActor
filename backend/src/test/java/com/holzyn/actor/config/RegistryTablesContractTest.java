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

import com.holzyn.actor.domain.worldstore.MirrorTables;
import com.holzyn.actor.domain.worldstore.RegistryTables;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 目录库表清单与迁移文本的一致性（V1.1.2-beta.1 M2）。
 *
 * <p>为什么需要这个测试：{@code RegistryTables} 被 {@code DatabaseStructureValidator} 用来
 * "只校验目录库的 16 张表"。这份清单一旦与 {@code V100} 漂移，后果不是报错而是<b>静默建表</b>——
 * 校验器会把缺的表 {@code CREATE TABLE} 出来，于是目录库里凭空多出本该只存在于世界库的表，
 * 而分档的核心承诺（两库永不混杂）就此破掉。</p>
 *
 * <p>所属模块：test/config（系统底层 schema 治理）</p>
 */
class RegistryTablesContractTest {

    private static final Pattern CREATE_TABLE = Pattern.compile(
            "^\\s*create\\s+table\\s+(?:if\\s+not\\s+exists\\s+)?([A-Za-z0-9_]+)",
            Pattern.CASE_INSENSITIVE | Pattern.MULTILINE);

    private static Path sql(String relative) {
        Path p = Path.of("src/main/resources/" + relative);
        assertTrue(Files.isRegularFile(p), "找不到迁移文件：" + p.toAbsolutePath());
        return p;
    }

    @Test
    @DisplayName("RegistryTables 与 V100 的 create table 集合逐表相等（16 张）")
    void registryTablesMatchV100() throws IOException {
        String text = Files.readString(sql("db/migration/global/V100__global_baseline.sql"), StandardCharsets.UTF_8);
        Set<String> inSql = new TreeSet<>();
        Matcher m = CREATE_TABLE.matcher(text);
        while (m.find()) {
            inSql.add(m.group(1).toLowerCase());
        }
        assertEquals(16, inSql.size(), "V100 的建表数应为 16，实际=" + inSql);
        assertEquals(inSql, new TreeSet<>(RegistryTables.ALL),
                "RegistryTables 与 V100 不一致（多=" + minus(RegistryTables.SET, inSql)
                        + " 少=" + minus(inSql, RegistryTables.SET) + "）");
    }

    @Test
    @DisplayName("12 张镜像表全部属于目录库的 16 张（镜像不可能由世界库独有）")
    void mirrorTablesAreSubsetOfRegistry() {
        Set<String> notInRegistry = new TreeSet<>(MirrorTables.SET);
        notInRegistry.removeAll(RegistryTables.SET);
        assertTrue(notInRegistry.isEmpty(), "镜像表不在目录库清单里：" + notInRegistry);
    }

    private static Set<String> minus(Set<String> a, Set<String> b) {
        Set<String> out = new TreeSet<>(a);
        out.removeAll(b);
        return out;
    }
}
