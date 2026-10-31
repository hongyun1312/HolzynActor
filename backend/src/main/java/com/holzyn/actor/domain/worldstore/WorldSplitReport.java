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

import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一次性迁移报告（{@code worldsplit-report.json} + {@code .md}，V1.1.2-beta.1 M3）。
 *
 * <p>为什么迁移必须留报告而不是"日志里翻一翻"：迁移是<b>不可回退的用户数据操作</b>。
 * 出问题时唯一能回答"到底哪张表少搬了几行"的东西就是这份逐世界逐表的行数/校验和对照；
 * 日志会被滚动、控制台会被关掉，报告是磁盘上的证据（对齐 AGENTS「治理轮强制留证」）。</p>
 *
 * <p>两种形态各有用处：{@code .json} 给脚本与后续 AC3 对账复用（字段稳定，含逐表 PASS/FAIL），
 * {@code .md} 给人看（一屏内能读完每个世界是否干净）。</p>
 *
 * <p>所属模块：domain/worldstore（世界存档存储）</p>
 */
@Slf4j
public final class WorldSplitReport {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 总体状态：全部逐表 PASS 才算 PASS */
    public static final String PASS = "PASS";

    /** 总体状态：任一世界/任一表失败 */
    public static final String FAIL = "FAIL";

    private WorldSplitReport() {
    }

    /**
     * 一张表的对照行。
     *
     * @param table      表名
     * @param bucket     分桶（project-scoped / backfill / child）
     * @param sourceRows 旧库里属于该世界的行数（按规则 SQL 数）
     * @param targetRows 世界库里的实际行数
     * @param pass       是否相等
     * @param note       备注（不一致时写差异说明）
     */
    public record TablePart(String table, String bucket, long sourceRows, long targetRows,
                            boolean pass, String note) {

        /** 行数一致即 PASS（note 只作说明，不参与判定） */
        public boolean ok() {
            return pass && sourceRows == targetRows;
        }
    }

    /**
     * 时钟三字段对照（AC3 要求逐位相同）。
     *
     * @param sourceGameSecond 旧库 last_game_second
     * @param targetGameSecond 世界库 last_game_second
     * @param sourcePaused     旧库 paused
     * @param targetPaused     世界库 paused
     * @param sourceRate       旧库 rate
     * @param targetRate       世界库 rate
     */
    public record ClockPart(Long sourceGameSecond, Long targetGameSecond,
                            Integer sourcePaused, Integer targetPaused,
                            Integer sourceRate, Integer targetRate) {

        /** 三字段逐位相等 */
        public boolean pass() {
            return eq(sourceGameSecond, targetGameSecond)
                    && eq(sourcePaused, targetPaused)
                    && eq(sourceRate, targetRate);
        }

        private static boolean eq(Object a, Object b) {
            return a == null ? b == null : a.equals(b);
        }
    }

    /**
     * 一个世界的迁移结果。
     *
     * @param id           目录库 {@code actor_project.id}
     * @param worldUid     世界身份
     * @param worldName    世界名
     * @param tables       逐表对照
     * @param clock        时钟对照
     * @param checksums    关键表内容校验和（表名 → {@code sha256:…}）
     * @param checksumPass 全部关键表校验和是否一致
     * @param resources    已复制的资源子目录名
     * @param manifestPath manifest 相对路径（相对归档/数据根，日志用）
     * @param elapsedMs    该世界耗时
     * @param warnings     该世界的告警（孤儿行、资源缺失等）
     * @param errors       该世界的错误（非空即失败）
     */
    public record WorldPart(long id, String worldUid, String worldName,
                            List<TablePart> tables, ClockPart clock,
                            Map<String, String> checksums, boolean checksumPass,
                            List<String> resources, String manifestPath, long elapsedMs,
                            List<String> warnings, List<String> errors) {

        /** 该世界整体是否 PASS */
        public boolean pass() {
            return errors.isEmpty() && checksumPass && clock != null && clock.pass()
                    && tables.stream().allMatch(TablePart::ok);
        }

        /** 已迁移行数合计 */
        public long totalRows() {
            return tables.stream().mapToLong(TablePart::targetRows).sum();
        }
    }

    /**
     * 写入报告（json + md）。
     *
     * @param dir         报告目录
     * @param meta        汇总字段
     * @param worlds      逐世界结果
     * @param warnings    全局告警
     * @param errors      全局错误（非空则总体 FAIL）
     *
     * @return 实际写出的 json 文件
     */
    public static Path write(Path dir, Meta meta, List<WorldPart> worlds,
                             List<String> warnings, List<String> errors) {
        boolean pass = errors.isEmpty() && worlds.stream().allMatch(WorldPart::pass);
        String status = pass ? PASS : FAIL;
        try {
            Files.createDirectories(dir);
            Path json = dir.resolve("worldsplit-report.json");
            Files.writeString(json, toJson(status, meta, worlds, warnings, errors), StandardCharsets.UTF_8);
            Path md = dir.resolve("worldsplit-report.md");
            Files.writeString(md, toMarkdown(status, meta, worlds, warnings, errors), StandardCharsets.UTF_8);
            log.info("[world-store] 迁移报告已写出：{}（status={}）", json, status);
            return json;
        } catch (IOException e) {
            throw new UncheckedIOException("写出迁移报告失败：" + dir, e);
        }
    }

    /**
     * 报告的头字段。
     *
     * @param generatedAt 生成时间（ISO-8601 本地）
     * @param sourceDb    旧库文件路径
     * @param sourceBytes 旧库字节数
     * @param sourceSha256 旧库 sha256
     * @param archiveDir  归档目录
     * @param registryDb  目录库文件
     * @param worldsRoot  世界根目录
     * @param dryRun      是否 dry-run（未写盘）
     * @param elapsedMs   总耗时
     */
    public record Meta(String generatedAt, String sourceDb, long sourceBytes, String sourceSha256,
                       String archiveDir, String registryDb, String worldsRoot,
                       boolean dryRun, long elapsedMs) {
    }

    // ------------------------------------------------------------------ JSON

    private static String toJson(String status, Meta meta, List<WorldPart> worlds,
                                 List<String> warnings, List<String> errors) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("status", status);
        root.put("generatedAt", meta.generatedAt());
        root.put("dryRun", meta.dryRun());
        root.put("elapsedMs", meta.elapsedMs());
        ObjectNode src = root.putObject("source");
        src.put("dbFile", meta.sourceDb());
        src.put("bytes", meta.sourceBytes());
        src.put("sha256", meta.sourceSha256());
        root.put("archiveDir", meta.archiveDir());
        root.put("registryDb", meta.registryDb());
        root.put("worldsRoot", meta.worldsRoot());

        ArrayNode ws = root.putArray("worlds");
        for (WorldPart w : worlds) {
            ObjectNode n = ws.addObject();
            n.put("id", w.id());
            n.put("worldUid", w.worldUid());
            n.put("worldName", w.worldName());
            n.put("status", w.pass() ? PASS : FAIL);
            n.put("elapsedMs", w.elapsedMs());
            n.put("totalRows", w.totalRows());
            n.put("checksumPass", w.checksumPass());
            n.put("manifest", w.manifestPath());
            ArrayNode rs = n.putArray("resources");
            w.resources().forEach(rs::add);

            ObjectNode clock = n.putObject("clock");
            ClockPart c = w.clock();
            if (c != null) {
                putNullable(clock, "sourceGameSecond", c.sourceGameSecond());
                putNullable(clock, "targetGameSecond", c.targetGameSecond());
                putNullable(clock, "sourcePaused", c.sourcePaused());
                putNullable(clock, "targetPaused", c.targetPaused());
                putNullable(clock, "sourceRate", c.sourceRate());
                putNullable(clock, "targetRate", c.targetRate());
                clock.put("pass", c.pass());
            } else {
                clock.put("pass", false);
            }

            ObjectNode sums = n.putObject("checksums");
            w.checksums().forEach(sums::put);

            ArrayNode ts = n.putArray("tables");
            for (TablePart t : w.tables()) {
                ObjectNode tn = ts.addObject();
                tn.put("table", t.table());
                tn.put("bucket", t.bucket());
                tn.put("sourceRows", t.sourceRows());
                tn.put("targetRows", t.targetRows());
                tn.put("pass", t.ok());
                if (t.note() != null && !t.note().isBlank()) {
                    tn.put("note", t.note());
                }
            }
            array(n, "warnings", w.warnings());
            array(n, "errors", w.errors());
        }
        array(root, "warnings", warnings);
        array(root, "errors", errors);
        return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(root);
    }

    private static void putNullable(ObjectNode node, String key, Number value) {
        if (value == null) {
            node.putNull(key);
        } else if (value instanceof Integer i) {
            node.put(key, i.intValue());
        } else {
            node.put(key, value.longValue());
        }
    }

    private static void array(ObjectNode node, String key, List<String> values) {
        ArrayNode arr = node.putArray(key);
        if (values != null) {
            values.forEach(arr::add);
        }
    }

    // ------------------------------------------------------------------ Markdown

    private static String toMarkdown(String status, Meta meta, List<WorldPart> worlds,
                                     List<String> warnings, List<String> errors) {
        StringBuilder sb = new StringBuilder(4096);
        sb.append("# 世界数据分存档 · 迁移报告\n\n");
        sb.append("| 项 | 值 |\n|---|---|\n");
        row(sb, "总体状态", status);
        row(sb, "生成时间", meta.generatedAt());
        row(sb, "dry-run", String.valueOf(meta.dryRun()));
        row(sb, "总耗时", meta.elapsedMs() + " ms");
        row(sb, "旧库文件", meta.sourceDb());
        row(sb, "旧库大小", meta.sourceBytes() + " B");
        row(sb, "旧库 sha256", meta.sourceSha256());
        row(sb, "归档目录", meta.archiveDir());
        row(sb, "目录库", meta.registryDb());
        row(sb, "世界根目录", meta.worldsRoot());
        sb.append('\n');

        for (WorldPart w : worlds) {
            sb.append("## ").append(w.worldName()).append("（id=").append(w.id())
                    .append("，uid=").append(w.worldUid()).append("）\n\n");
            sb.append("| 项 | 值 |\n|---|---|\n");
            row(sb, "结果", w.pass() ? PASS : FAIL);
            row(sb, "耗时", w.elapsedMs() + " ms");
            row(sb, "迁移行数", String.valueOf(w.totalRows()));
            row(sb, "清单", w.manifestPath());
            row(sb, "资源", w.resources().isEmpty() ? "（无）" : String.join(", ", w.resources()));
            ClockPart c = w.clock();
            row(sb, "时钟", c == null ? "（缺失）"
                    : c.sourceGameSecond() + " / paused=" + c.sourcePaused() + " / rate=" + c.sourceRate()
                    + " → " + c.targetGameSecond() + " / paused=" + c.targetPaused() + " / rate=" + c.targetRate()
                    + (c.pass() ? " ✔" : " ✘"));
            row(sb, "关键表校验和", w.checksumPass() ? "全部一致 ✔" : "不一致 ✘");
            sb.append('\n');

            sb.append("| 表 | 分桶 | 旧库行数 | 世界库行数 | 结果 | 备注 |\n");
            sb.append("|---|---|---:|---:|---|---|\n");
            for (TablePart t : w.tables()) {
                sb.append("| `").append(t.table()).append("` | ").append(t.bucket())
                        .append(" | ").append(t.sourceRows())
                        .append(" | ").append(t.targetRows())
                        .append(" | ").append(t.ok() ? "PASS" : "**FAIL**")
                        .append(" | ").append(escape(t.note()))
                        .append(" |\n");
            }
            if (!w.checksums().isEmpty()) {
                sb.append("\n校验和：\n\n");
                Map<String, String> sorted = new LinkedHashMap<>(w.checksums());
                for (Map.Entry<String, String> e : sorted.entrySet()) {
                    sb.append("- `").append(e.getKey()).append("` = `").append(e.getValue()).append("`\n");
                }
            }
            appendList(sb, "告警", w.warnings());
            appendList(sb, "错误", w.errors());
            sb.append('\n');
        }

        appendList(sb, "全局告警", warnings);
        appendList(sb, "全局错误", errors);
        return sb.toString();
    }

    private static void appendList(StringBuilder sb, String title, List<String> items) {
        if (items == null || items.isEmpty()) {
            return;
        }
        sb.append('\n').append(title).append("：\n\n");
        for (String item : items) {
            sb.append("- ").append(item).append('\n');
        }
    }

    private static void row(StringBuilder sb, String key, String value) {
        sb.append("| ").append(key).append(" | ").append(escape(value)).append(" |\n");
    }

    /** Markdown 表格里不能出现裸竖线与换行 */
    private static String escape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("|", "\\|").replace("\r", " ").replace("\n", " ").trim();
    }
}
