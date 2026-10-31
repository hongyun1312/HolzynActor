package com.holzyn.actor.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 世界数据分存档：切分规则本身的不变量（V1.1.2-beta.1 M0）。
 *
 * <p>前三类测试管"生成了什么"，本测试管"凭什么这么切"。规则一旦被悄悄放宽，
 * 症状不是编译错误，而是某个世界的角色出现在另一个世界里，或某张全局配置表被某个
 * 世界单独修改后与全局失联。这里把规则钉成可执行断言：</p>
 *
 * <ol>
 * <li><b>实体全覆盖</b>：ORM 映射的每张表都必须在世界库里——世界库缺一张 ORM 要用的表，
 * 该世界的相关功能会在线 500，而新库只在首次访问时暴露；</li>
 * <li><b>目录库不许有世界业务表</b>：目录库里带 project_id 的表只能是 3 张观测日志
 * 与 2 张双性质配置（它们的 project_id 可空，NULL 表示全局行）；</li>
 * <li><b>镜像同构</b>：12 张镜像表在两份基线里的建表语句必须逐字相同，
 * 因为同一个 Hibernate 实体映射两边，列漂移会让挂载后的读走空值；</li>
 * <li><b>子表确有父键</b>：14 张无 project_id 的子表必须至少有一个 {@code *_id} 列，
 * 否则"按父键归属世界"这条迁移规则无从执行。</li>
 * </ol>
 *
 * <p>所属模块：test/config（系统底层 schema 治理）</p>
 */
class WorldSplitRulesTest {

    /** 双性质表：目录库是"全局配置"，世界库是"只读镜像" */
    private static final Set<String> DUAL_NATURE = Set.of("actor_model_provider", "actor_prompt_template");

    /** 观测日志：project_id 可空，NULL 属应用级观测，留在目录库 */
    private static final Set<String> OBSERVATION = Set.of("actor_usage_log", "actor_test_usage_log", "actor_ai_call_log");

    /** 唯一的非实体表（元数据），两库都有 */
    private static final String META_TABLE = "actor_schema_meta";

    private static final Pattern CREATE_TABLE =
            Pattern.compile("^\\s*create\\s+table\\s+(?:if\\s+not\\s+exists\\s+)?([A-Za-z0-9_]+)", Pattern.CASE_INSENSITIVE);

    private static final Pattern TABLE_ANNOTATION =
            Pattern.compile("@Table\\s*\\([^)]*?name\\s*=\\s*\"([^\"]+)\"", Pattern.DOTALL);

    private static final Pattern FK_LIKE_COLUMN =
            Pattern.compile("\\b([a-z0-9_]+_id)\\b");

    private static String text(String classpathRelative) throws IOException {
        ClassPathResource res = new ClassPathResource(classpathRelative);
        assertTrue(res.exists(), "资源缺失：" + classpathRelative);
        try (var in = res.getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static JsonNode manifest() throws IOException {
        return new ObjectMapper().readTree(text("db/worldsplit-tables.json"));
    }

    private static Set<String> names(JsonNode arr) {
        Set<String> out = new TreeSet<>();
        for (JsonNode n : arr) {
            out.add(n.asText().toLowerCase(Locale.ROOT));
        }
        return out;
    }

    /** 去注释、压平空白、转小写（逐语句比对的归一化） */
    private static String normalize(String sql) {
        StringBuilder sb = new StringBuilder();
        for (String line : sql.split("\\R")) {
            String t = line.trim();
            if (t.isEmpty() || t.startsWith("--")) {
                continue;
            }
            sb.append(t).append(' ');
        }
        return sb.toString().replaceAll("\\s+", " ").trim().toLowerCase(Locale.ROOT);
    }

    /** 取某张表的建表语句（归一化后，含结尾分号前的内容） */
    private static String createTableStatement(String sql, String table) {
        for (String stmt : normalize(sql).split(";")) {
            String s = stmt.trim();
            Matcher m = CREATE_TABLE.matcher(s);
            if (m.find() && m.group(1).equalsIgnoreCase(table)) {
                return s;
            }
        }
        return null;
    }

    @Test
    @DisplayName("规则1：ORM 映射的每张表都在世界库里，且世界库仅多出 actor_schema_meta")
    void everyMappedEntityTableIsInsideWorldDb() throws IOException {
        Path sourceRoot = Path.of("src/main/java");
        assumeTrue(Files.isDirectory(sourceRoot), "非 maven 后端根目录，跳过源码扫描");

        Set<String> mapped = new LinkedHashSet<>();
        try (Stream<Path> files = Files.walk(sourceRoot)) {
            for (Path p : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                String src = Files.readString(p, StandardCharsets.UTF_8);
                if (!src.contains("@Table")) {
                    continue;
                }
                Matcher m = TABLE_ANNOTATION.matcher(src);
                while (m.find()) {
                    mapped.add(m.group(1).toLowerCase(Locale.ROOT));
                }
            }
        }
        assertFalse(mapped.isEmpty(), "未扫描到任何 @Table（扫描逻辑失效）");

        Set<String> world = names(manifest().get("world"));
        // V200 之后的增量迁移也会建表（基线不能改：改 V200 = 改 checksum = 已有世界起不来）
        world.addAll(WorldIncrementTables.createdAfterBaseline());
        Set<String> missing = new LinkedHashSet<>(mapped);
        missing.removeAll(world);
        assertTrue(missing.isEmpty(), "以下 ORM 映射表不在世界库里（该世界相关功能会在线 500）：" + missing);

        Set<String> extra = new LinkedHashSet<>(world);
        extra.removeAll(mapped);
        assertEquals(Set.of(META_TABLE), extra,
                "世界库除 ORM 映射表外只应有 " + META_TABLE + "，实际多出：" + extra);
    }

    @Test
    @DisplayName("规则2：目录库里带 project_id 的表只能是观测日志与双性质配置")
    void registryHasNoWorldBusinessTable() throws IOException {
        JsonNode root = manifest();
        Set<String> registry = names(root.get("registry"));
        Set<String> withPid = names(root.get("withProjectId"));

        Set<String> leaked = new LinkedHashSet<>(registry);
        leaked.retainAll(withPid);
        leaked.removeAll(OBSERVATION);
        leaked.removeAll(DUAL_NATURE);
        assertTrue(leaked.isEmpty(),
                "目录库混入了世界业务表（会让所有世界的数据挤在同一个库里）：" + leaked);

        // 反向：世界库必须包含全部带 project_id 的表
        Set<String> world = names(root.get("world"));
        Set<String> lost = new LinkedHashSet<>(withPid);
        lost.removeAll(world);
        assertTrue(lost.isEmpty(), "带 project_id 的表必须都在世界库里，缺=" + lost);
    }

    @Test
    @DisplayName("规则3：12 张镜像表在两份基线里的建表语句逐字相同")
    void mirrorTablesAreIdenticalInBothBaselines() throws IOException {
        Set<String> mirror = names(manifest().get("mirror"));
        assertEquals(12, mirror.size(), "镜像表应为 12 张");

        String globalSql = text("db/migration/global/V100__global_baseline.sql");
        String worldSql = text("db/migration/world/V200__world_baseline.sql");

        List<String> problems = new ArrayList<>();
        for (String table : mirror) {
            String inGlobal = createTableStatement(globalSql, table);
            String inWorld = createTableStatement(worldSql, table);
            if (inGlobal == null) {
                problems.add(table + " 在 V100 里没有建表语句");
            } else if (inWorld == null) {
                problems.add(table + " 在 V200 里没有建表语句");
            } else if (!inGlobal.equals(inWorld)) {
                problems.add(table + " 两库建表语句不同：\n    目录库=" + inGlobal + "\n    世界库=" + inWorld);
            }
        }
        assertTrue(problems.isEmpty(), "镜像表同构校验失败：\n  " + String.join("\n  ", problems));
    }

    @Test
    @DisplayName("规则4：14 张子表都带父键列（*_id），且都不含 project_id")
    void childTablesCarryParentKey() throws IOException {
        JsonNode root = manifest();
        Set<String> child = names(root.get("child"));
        assertEquals(14, child.size(), "子表应为 14 张");

        String worldSql = text("db/migration/world/V200__world_baseline.sql");
        List<String> problems = new ArrayList<>();
        for (String table : child) {
            // 清单用改名后的新名，V200 基线里仍是旧名（改名由 V203 收口）
            String ddl = createTableStatement(worldSql, table);
            if (ddl == null) {
                ddl = createTableStatement(worldSql, WorldSchemaRenames.baselineNameOf(table));
            }
            if (ddl == null) {
                problems.add(table + " 在世界库基线里没有建表语句");
                continue;
            }
            if (ddl.matches("(?s).*\\bproject_id\\b.*")) {
                problems.add(table + " 竟然含 project_id（应归入世界业务表而非子表）");
                continue;
            }
            if (!FK_LIKE_COLUMN.matcher(ddl).find()) {
                problems.add(table + " 没有任何 *_id 父键列，无法按父键归属世界");
            }
        }
        assertTrue(problems.isEmpty(), "子表规则校验失败：\n  " + String.join("\n  ", problems));
    }
}
