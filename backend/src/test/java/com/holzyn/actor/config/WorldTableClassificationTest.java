package com.holzyn.actor.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 世界数据分存档：表分类与基线覆盖校验（V1.1.2-beta.1 M0）。
 *
 * <p>背景：分存档后有两套基线——目录库 16 张表、世界库 113 张表（含 12 张全局只读镜像）。
 * 这两份 SQL 由 {@code scripts/dev/gen_worldsplit_schema.py} 从 {@code db/legacy/V1~V7} 生成，
 * 一旦有人手工改基线、或加表时忘了重生成，就会出现「世界库里缺一张表」这种只在新世界
 * 首次写入时才炸的缺陷（本仓库没有 {@code @SpringBootTest}，见 AGENTS.md §8.4-3）。</p>
 *
 * <p>本测试只做文件级断言，不连数据库：</p>
 * <ol>
 * <li>分类清单 {@code db/worldsplit-tables.json} 的规模与显式清单一致（113/16/12/88/14）；</li>
 * <li>两份基线里 {@code create table} 的表集合，必须与清单**逐表**相等（双向，不许漏也不许多）；</li>
 * <li>每张表在基线里只能建一次（防重复粘贴）。</li>
 * </ol>
 *
 * <p>所属模块：test/config（系统底层 schema 治理）</p>
 */
class WorldTableClassificationTest {

    /** 预期：世界库表数 */
    private static final int EXPECT_WORLD = 113;

    /** 预期：目录库表数 */
    private static final int EXPECT_REGISTRY = 16;

    /** 预期：镜像表数（9 全局 + 2 双性质 + actor_project） */
    private static final int EXPECT_MIRROR = 12;

    /** 预期：带 project_id 的表数 */
    private static final int EXPECT_WITH_PROJECT_ID = 88;

    /** 预期：子表数（自身无 project_id，按父键归属世界） */
    private static final int EXPECT_CHILD = 14;

    private static final Pattern CREATE_TABLE =
            Pattern.compile("^\\s*create\\s+table\\s+(?:if\\s+not\\s+exists\\s+)?([A-Za-z0-9_]+)", Pattern.CASE_INSENSITIVE | Pattern.MULTILINE);

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
            out.add(n.asText().toLowerCase());
        }
        return out;
    }

    /** 从基线 SQL 里抽出所有 create table 的表名（含重复计数） */
    private static Set<String> createdTables(String sql) {
        Set<String> out = new TreeSet<>();
        Matcher m = CREATE_TABLE.matcher(sql);
        while (m.find()) {
            out.add(m.group(1).toLowerCase());
        }
        return out;
    }

    @Test
    @DisplayName("分类清单规模：113 世界 / 16 目录 / 12 镜像 / 88 带 project_id / 14 子表")
    void classificationCountsMatchDesign() throws IOException {
        JsonNode root = manifest();
        JsonNode counts = root.get("counts");
        assertEquals(EXPECT_WORLD, counts.get("world").asInt(), "世界库表数");
        assertEquals(EXPECT_REGISTRY, counts.get("registry").asInt(), "目录库表数");
        assertEquals(EXPECT_MIRROR, counts.get("mirror").asInt(), "镜像表数");
        assertEquals(EXPECT_WITH_PROJECT_ID, counts.get("withProjectId").asInt(), "带 project_id 的表数");
        assertEquals(EXPECT_CHILD, counts.get("child").asInt(), "子表数");

        // 清单里的明细数组长度必须与 counts 对得上（防"改了 counts 忘了明细"）
        assertEquals(EXPECT_WORLD, root.get("world").size(), "world 明细条数");
        assertEquals(EXPECT_REGISTRY, root.get("registry").size(), "registry 明细条数");
        assertEquals(EXPECT_MIRROR, root.get("mirror").size(), "mirror 明细条数");
        assertEquals(EXPECT_CHILD, root.get("child").size(), "child 明细条数");
    }

    @Test
    @DisplayName("集合关系：子表/镜像都属世界库，子表不属目录库，镜像表不含子表")
    void classificationSetsAreConsistent() throws IOException {
        JsonNode root = manifest();
        Set<String> world = names(root.get("world"));
        Set<String> registry = names(root.get("registry"));
        Set<String> mirror = names(root.get("mirror"));
        Set<String> child = names(root.get("child"));

        assertTrue(world.containsAll(registry), "目录库表必须都是世界库的子集（世界库要能独立还原世界），缺=" + diff(registry, world));
        assertTrue(world.containsAll(mirror), "镜像表必须都在世界库里，缺=" + diff(mirror, world));
        assertTrue(world.containsAll(child), "子表必须都在世界库里，缺=" + diff(child, world));

        Set<String> childInRegistry = new LinkedHashSet<>(child);
        childInRegistry.retainAll(registry);
        assertTrue(childInRegistry.isEmpty(), "子表不应进目录库（它们没有 project_id，靠父键归属世界）：" + childInRegistry);

        Set<String> mirrorChild = new LinkedHashSet<>(mirror);
        mirrorChild.retainAll(child);
        assertTrue(mirrorChild.isEmpty(), "镜像表不应同时是子表：" + mirrorChild);

        // 目录库 16 张 = 9 全局 + 2 双性质 + actor_project + actor_schema_meta + 3 观测
        assertTrue(registry.containsAll(mirror), "目录库必须包含全部镜像表（镜像的源就是目录库），缺=" + diff(mirror, registry));
        assertEquals(EXPECT_REGISTRY - EXPECT_MIRROR, registry.size() - mirror.size(),
                "目录库除镜像外应恰有 4 张（3 观测 + actor_schema_meta）");
    }

    @Test
    @DisplayName("基线覆盖：V100/V200 的 create table 集合与清单逐表相等（双向）")
    void baselinesCoverExactlyTheDeclaredTables() throws IOException {
        JsonNode root = manifest();
        Set<String> world = names(root.get("world"));
        Set<String> registry = names(root.get("registry"));

        Set<String> inGlobal = createdTables(text("db/migration/global/V100__global_baseline.sql"));
        Set<String> inWorld = applyRenames(createdTables(text("db/migration/world/V200__world_baseline.sql")),
                WorldSchemaRenames.tableRenames());
        // 基线之后的新表走增量迁移（V201+），清单只描述基线；比对时两侧都补上增量表集合
        Set<String> increments = WorldIncrementTables.createdAfterBaseline();
        Set<String> declared = new LinkedHashSet<>(world);
        declared.addAll(increments);
        Set<String> created = new LinkedHashSet<>(inWorld);
        created.addAll(increments);

        assertEquals(registry, inGlobal, "V100 目录库基线表集合与清单不一致"
                + "（多=" + diff(inGlobal, registry) + " 少=" + diff(registry, inGlobal) + "）");
        assertEquals(declared, created, "V200 世界库基线表集合与清单不一致"
                + "（多=" + diff(created, declared) + " 少=" + diff(declared, created) + "）");
    }

    /**
     * 按改名映射归一表名集合（映射来源见 {@link WorldSchemaRenames}）。
     *
     * <p><b>为什么测试要跟着改名</b>：{@code V200} 已出现在用户库里，改动文件会改 checksum，
     * Flyway 校验会直接让应用启动失败；所以旧名基线只能由 {@code V203} 收口。
     * 断言因此要比较「基线 + 改名后」的集合，否则这条护栏会误报。</p>
     */
    private static Set<String> applyRenames(Set<String> tables, java.util.Map<String, String> renames) {
        Set<String> out = new LinkedHashSet<>();
        for (String t : tables) {
            out.add(renames.getOrDefault(t, t));
        }
        return out;
    }

    @Test
    @DisplayName("基线唯一性：每张表在各自基线里只建一次")
    void eachTableCreatedExactlyOnce() throws IOException {
        for (String path : new String[]{
                "db/migration/global/V100__global_baseline.sql",
                "db/migration/world/V200__world_baseline.sql"}) {
            String sql = text(path);
            Matcher m = CREATE_TABLE.matcher(sql);
            java.util.Map<String, Integer> seen = new java.util.HashMap<>();
            while (m.find()) {
                seen.merge(m.group(1).toLowerCase(), 1, Integer::sum);
            }
            Set<String> dup = new TreeSet<>();
            seen.forEach((k, v) -> {
                if (v > 1) {
                    dup.add(k);
                }
            });
            assertTrue(dup.isEmpty(), path + " 中以下表被重复建表：" + dup);
            assertFalse(seen.isEmpty(), path + " 里没有解析到任何 create table（解析器失效或文件为空）");
        }
    }

    private static <T> Set<T> diff(Set<T> a, Set<T> b) {
        Set<T> out = new LinkedHashSet<>(a);
        out.removeAll(b);
        return out;
    }
}
