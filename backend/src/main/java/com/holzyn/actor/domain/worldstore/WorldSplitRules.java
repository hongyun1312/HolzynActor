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

import com.holzyn.actor.common.NpcRefs;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

/**
 * 旧单库 → 多库的切分规则（V1.1.2-beta.1 M3，设计文档 §3.2 + 附录 A/B 的可执行形态）。
 *
 * <p><b>为什么规则必须集中在一个类里</b>：迁移器出错的表现是"某个世界的角色出现在另一个世界里"
 * 或"某张表少搬了一个世界的行"，这两种错都不会编译失败，也往往在几百行数据里看不出来。
 * 把"哪张表用哪条 SQL 归属到哪个世界"写成一张唯一的规则表，才可能用合成数据把
 * 「每行只进一个世界、无孤儿、无重复」断言成测试。</p>
 *
 * <p><b>两类表名来源，各有各的理由</b>：</p>
 * <ol>
 *   <li><b>78 张 {@code project_id} 可直接筛选的表</b>：名单来自 M0 生成器产出的
 *       {@code db/worldsplit-tables.json}（同一份文件也被 {@code WorldTableClassificationTest}
 *       与迁移器共用）。这里刻意<b>不</b>硬编码 78 个表名——那必然与生成器漂移，而且漂移了看不出来；
 *       但它的<b>规模</b>（78）与<b>分桶结果</b>在 {@link #selfCheck()} 里硬断言，
 *       所以"生成器少产出一张表"会当场失败；</li>
 *   <li><b>8 张可空 {@code project_id} 的表 + 14 张子表</b>：每张一条写死的 SQL（含父键、
 *       含回填表达式），因为它们各自的归属语义不同（记忆按角色、修订按档案、计划按角色……），
 *       通用化反而会写出"看起来对、实际串世界"的 SQL。</li>
 * </ol>
 *
 * <p><b>统一契约</b>：每条 {@link RowRule#selectSql()} 都是"把 {@code ?} 绑成 projectId 后，
 * 恰好选出该世界的那部分行"的完整 select。于是迁移器只需要循环规则表，不需要为任何一张表
 * 写特例——这也是"每张表一条规则 + 单测"能落地的前提。</p>
 *
 * <p>所属模块：domain/worldstore（世界存档存储）</p>
 */
public final class WorldSplitRules {

    /** 生成器产出的表归属清单（M0 起唯一权威） */
    public static final String TABLES_JSON = "db/worldsplit-tables.json";

    /** 两库各一份的元数据表（不参与行迁移） */
    public static final String META_TABLE = "actor_schema_meta";

    /** 世界归属列（Q6：库内列名保持 {@code project_id} 不改名） */
    public static final String PROJECT_COLUMN = "project_id";

    /** 分桶期望规模（与设计文档 §3.2 逐项对应；任何漂移都当场失败） */
    public static final int EXPECTED_WORLD_TABLES = 113;
    public static final int EXPECTED_REGISTRY_TABLES = 16;
    public static final int EXPECTED_MIRROR = 12;
    public static final int EXPECTED_CHILD = 14;
    public static final int EXPECTED_BACKFILL = 8;
    public static final int EXPECTED_PROJECT_SCOPED = 78;
    public static final int EXPECTED_OBSERVATION = 3;

    /**
     * 一条行归属规则。
     *
     * @param table       表名
     * @param bucket      所属桶（日志/报告里显示）
     * @param selectSql   选出"属于某个世界"的完整 select（{@code ?} = projectId，
     *                    列集合由调用方按公共列填充，见 {@link #withColumns}）
     * @param needsStamp  复制进世界库后是否需要把 {@code project_id is null} 的行补成本世界 id
     *                    （只有"回填"桶需要：源库里该列是空的）
     * @param description 规则说明（报告里逐条列出，便于人工核对）
     */
    public record RowRule(String table, String bucket, String selectSql,
                          boolean needsStamp, String description) {
    }

    /** 分桶名（报告与日志用，避免各处自拼字符串） */
    public static final String BUCKET_PROJECT_SCOPED = "project-scoped";
    public static final String BUCKET_BACKFILL = "backfill";
    public static final String BUCKET_CHILD = "child";

    private static volatile Snapshot snapshot;

    private WorldSplitRules() {
    }

    /**
     * 某条规则的可执行 select（把 {@code *} 换成实际列名）。
     *
     * @param rule    规则
     * @param columns 目标库公共列（顺序即插入顺序）
     *
     * @return 可直接执行的 select
     */
    public static String withColumns(RowRule rule, List<String> columns) {
        return rule.selectSql().replace("{}", String.join(", ", columns));
    }

    /**
     * 某条规则的计数 SQL（把列清单换成 {@code count(*)}，参数与 where 完全一致）。
     *
     * <p>计数与搬行必须走<b>同一条</b> where 子句：两边各写一份 where 是本项目历史上
     * "界面显示 24 个角色、实际搬了 23 个"这类问题的来源。</p>
     *
     * @param rule 规则
     *
     * @return 可直接执行的 count SQL（同样只需一个 projectId 参数）
     */
    public static String countSql(RowRule rule) {
        String sql = rule.selectSql().replace("select {} from ", "select count(*) from ");
        if (sql.contains("{}")) {
            throw new IllegalStateException("规则 SQL 结构与约定不符（无法生成计数 SQL）：" + rule.table());
        }
        return sql;
    }

    // ------------------------------------------------------------------ 分桶

    /** 113 张世界库表（含 12 镜像 + 元数据） */
    public static Set<String> worldTables() {
        return snapshot().world;
    }

    /** 16 张目录库表 */
    public static Set<String> registryTables() {
        return snapshot().registry;
    }

    /** 12 张全局只读镜像表 */
    public static Set<String> mirrorTables() {
        return snapshot().mirror;
    }

    /** 3 张两库同构的观测日志表 */
    public static Set<String> observationTables() {
        return snapshot().observation;
    }

    /**
     * 78 张按 {@code project_id} 直接筛选的世界表（75 张纯世界表 + 3 张观测表）。
     *
     * @return 表名（升序，顺序稳定便于报告 diff）
     */
    public static List<String> projectScopedTables() {
        return snapshot().projectScoped;
    }

    /**
     * 8 张 {@code project_id} 可空、需要按父键回填归属的世界数据表。
     *
     * @return 规则（按表名升序）
     */
    public static List<RowRule> backfillRules() {
        return snapshot().backfill;
    }

    /**
     * 14 张无 {@code project_id}、随父表归属的子表。
     *
     * @return 规则（按表名升序）
     */
    public static List<RowRule> childRules() {
        return snapshot().child;
    }

    /** 全部行规则（78 + 8 + 14 = 100 条，按桶顺序：可筛 → 回填 → 子表） */
    public static List<RowRule> allRowRules() {
        return snapshot().allRules;
    }

    /**
     * 8 张可空表的表名（判定"孤儿行"时用）。
     *
     * @return 表名集合
     */
    public static Set<String> backfillTables() {
        return snapshot().backfillNames;
    }

    // ------------------------------------------------------------------ 规则实现

    /**
     * 78 张可筛表的规则：{@code where <table>.project_id = ?}。
     *
     * <p>观测表（{@code actor_ai_call_log}/{@code actor_usage_log}/{@code actor_test_usage_log}）
     * 也在这条规则里：它们的 {@code project_id} 为 NULL 的行属于"应用级观测"，按设计 §3.2
     * 留在目录库，因此这里天然只选出非空行（SQL 的三值逻辑让 {@code null = ?} 不成立）。</p>
     */
    private static RowRule projectScopedRule(String table) {
        return new RowRule(table, BUCKET_PROJECT_SCOPED,
                "select {} from " + table + " where " + table + "." + PROJECT_COLUMN + " = ?",
                false, "project_id 直接筛选");
    }

    /**
     * 8 张可空表的规则。
     *
     * <p>归属表达式一律写成 {@code coalesce(自身 project_id, 父键反查)}：先用自身列（真实数据里
     * 8 张表全部非空，这条即命中），为空时才按父键反查——这样"脏数据也不会被静默丢弃"，
     * 反查不出来才算孤儿并进报告。</p>
     *
     * <p>为什么反查用 {@code 'npc:' || cast(p.id as varchar) = t.character_ref} 而不是反向解析
     * {@code character_ref}：{@code cast(substring(...) as bigint)} 会在脏值上抛"数值转换失败"，
     * 让整个迁移中断；而字符串比较遇到任何脏值都只是"匹配不上"，退化为孤儿行并留证。</p>
     */
    private static List<RowRule> buildBackfillRules() {
        // actor_profile 只有 24~200 行级，逐行相关子查询的代价可以忽略（迁移是一次性动作）
        String byRef = "(select p.project_id from actor_profile p where '"
                + NpcRefs.PREFIX + "' || cast(p.id as varchar) = ";
        List<RowRule> rules = new ArrayList<>(EXPECTED_BACKFILL);

        // 1) actor_memory：既有 character_id（bigint）也有 character_ref（文本），优先用前者
        rules.add(new RowRule("actor_memory", BUCKET_BACKFILL,
                "select {} from actor_memory where coalesce(actor_memory.project_id, "
                        + "(select p.project_id from actor_profile p where p.id = actor_memory.character_id), "
                        + byRef + "actor_memory.character_ref)) = ?",
                true, "自身 project_id 优先，其次 character_id，最后 character_ref（npc:<id>）"));

        // 2) 其余记忆表只有 character_ref
        for (String table : List.of("actor_memory_archive", "actor_memory_core",
                "actor_memory_fuzz_state", "actor_memory_working")) {
            rules.add(new RowRule(table, BUCKET_BACKFILL,
                    "select {} from " + table + " where coalesce(" + table + ".project_id, "
                            + byRef + table + ".character_ref)) = ?",
                    true, "自身 project_id 优先，其次 character_ref（npc:<id>）→ actor_profile.project_id"));
        }

        // 3) actor_memory_lineage：没有任何角色列，只能顺着 source/target 记忆行反查归属
        rules.add(new RowRule("actor_memory_lineage", BUCKET_BACKFILL,
                "select {} from actor_memory_lineage where coalesce(actor_memory_lineage.project_id, "
                        + "(select m.project_id from actor_memory m where m.id = actor_memory_lineage.source_memory_id), "
                        + "(select m.project_id from actor_memory m where m.id = actor_memory_lineage.target_memory_id)) = ?",
                true, "自身 project_id 优先，其次 source_memory_id / target_memory_id → actor_memory.project_id"));

        // 4) actor_profile_revision：profile_id 直接指向角色档案
        rules.add(new RowRule("actor_profile_revision", BUCKET_BACKFILL,
                "select {} from actor_profile_revision where coalesce(actor_profile_revision.project_id, "
                        + "(select p.project_id from actor_profile p where p.id = actor_profile_revision.profile_id)) = ?",
                true, "自身 project_id 优先，其次 profile_id → actor_profile.project_id"));

        // 5) actor_worldgen_task：归属就是自身 project_id，没有父键可反查（为空只能是孤儿）
        rules.add(new RowRule("actor_worldgen_task", BUCKET_BACKFILL,
                "select {} from actor_worldgen_task where actor_worldgen_task.project_id = ?",
                false, "自身 project_id（无父键可反查；为空即孤儿）"));

        rules.sort((a, b) -> a.table().compareTo(b.table()));
        return List.copyOf(rules);
    }

    /**
     * 14 张子表的规则（设计文档附录 B 逐条落地）。
     *
     * <p>为什么必须逐条写死而<b>不能</b>整表搬：子表没有 {@code project_id}，整表搬会让每个世界
     * 都带着所有世界的对话成员/行动日志；而"按父键 in 子查询"是唯一能保证
     * 「每行只进一个世界」的写法。孤儿行（父行不在任何世界）自然选不出来，由报告 WARN。</p>
     */
    private static List<RowRule> buildChildRules() {
        List<RowRule> rules = new ArrayList<>(EXPECTED_CHILD);
        rules.add(child("actor_action_log", "character_id", "actor_profile",
                "按父 actor_profile.character_id"));
        rules.add(child("actor_plan", "character_id", "actor_profile",
                "按父 actor_profile.character_id"));
        rules.add(new RowRule("actor_profile_brain", BUCKET_CHILD,
                "select {} from actor_profile_brain where agent_type = 'profile' and agent_ref_id in "
                        + "(select id from actor_profile where project_id = ?)",
                false, "按父 actor_profile.agent_ref_id（且 agent_type='profile'）"));
        rules.add(child("actor_dialogue_member", "conversation_id", "actor_dialogue",
                "按父 actor_dialogue.conversation_id"));
        rules.add(child("actor_dialogue_message", "conversation_id", "actor_dialogue",
                "按父 actor_dialogue.conversation_id"));
        rules.add(child("actor_evolution_participant", "evolution_id", "actor_evolution",
                "按父 actor_evolution.evolution_id"));
        rules.add(child("actor_evolution_turn", "evolution_id", "actor_evolution",
                "按父 actor_evolution.evolution_id"));
        rules.add(child("actor_eval_sample", "eval_run_id", "actor_eval_run",
                "按父 actor_eval_run.eval_run_id"));
        rules.add(child("actor_eval_score", "eval_run_id", "actor_eval_run",
                "按父 actor_eval_run.eval_run_id（sample_id 随样例行同世界）"));
        rules.add(child("actor_decision_ab_sample", "ab_run_id", "actor_decision_ab_run",
                "按父 actor_decision_ab_run.ab_run_id"));
        rules.add(child("actor_talk_group_member", "session_id", "actor_talk_session",
                "按父 actor_talk_session.session_id"));
        rules.add(child("actor_talk_message", "session_id", "actor_talk_session",
                "按父 actor_talk_session.session_id"));
        rules.add(child("actor_test_run_event", "run_id", "actor_test_run",
                "按父 actor_test_run.run_id"));
        rules.add(child("actor_worldgen_node_attempt", "task_id", "actor_worldgen_task",
                "按父 actor_worldgen_task.task_id"));
        rules.sort((a, b) -> a.table().compareTo(b.table()));
        return List.copyOf(rules);
    }

    /** 子表通用规则：{@code where <fk> in (select id from <parent> where project_id = ?)} */
    private static RowRule child(String table, String foreignKey, String parent, String description) {
        return new RowRule(table, BUCKET_CHILD,
                "select {} from " + table + " where " + foreignKey + " in "
                        + "(select id from " + parent + " where project_id = ?)",
                false, description);
    }

    // ------------------------------------------------------------------ 装配与自检

    private record Snapshot(Set<String> world, Set<String> registry, Set<String> mirror,
                            Set<String> observation, List<String> projectScoped,
                            List<RowRule> backfill, List<RowRule> child,
                            List<RowRule> allRules, Set<String> backfillNames) {
    }

    /** 懒加载 + 自检（单测会直接触发，任何规模漂移都会在这里失败） */
    private static Snapshot snapshot() {
        Snapshot s = snapshot;
        if (s == null) {
            synchronized (WorldSplitRules.class) {
                s = snapshot;
                if (s == null) {
                    s = load();
                    snapshot = s;
                }
            }
        }
        return s;
    }

    private static Snapshot load() {
        JsonNode root = readJson();
        Set<String> world = names(root, "world");
        Set<String> registry = names(root, "registry");
        Set<String> mirror = names(root, "mirror");
        Set<String> observation = names(root, "observation");
        Set<String> withProjectId = names(root, "withProjectId");
        Set<String> childNames = names(root, "child");

        List<RowRule> backfill = buildBackfillRules();
        List<RowRule> child = buildChildRules();
        Set<String> backfillNames = new TreeSet<>();
        backfill.forEach(r -> backfillNames.add(r.table()));

        // 78 = 88 张带 project_id 的表 − 2 张双性质配置（走镜像）− 8 张需回填
        Set<String> projectScoped = new TreeSet<>(withProjectId);
        projectScoped.removeAll(mirror);
        projectScoped.removeAll(backfillNames);
        List<RowRule> scopedRules = projectScoped.stream().map(WorldSplitRules::projectScopedRule).toList();

        List<RowRule> all = new ArrayList<>(EXPECTED_PROJECT_SCOPED + EXPECTED_BACKFILL + EXPECTED_CHILD);
        all.addAll(scopedRules);
        all.addAll(backfill);
        all.addAll(child);

        Snapshot s = new Snapshot(Set.copyOf(world), Set.copyOf(registry), Set.copyOf(mirror),
                Set.copyOf(observation), List.copyOf(projectScoped),
                backfill, child, List.copyOf(all), Set.copyOf(backfillNames));
        selfCheck(s, withProjectId, childNames);
        return s;
    }

    /**
     * 结构自检（生产路径也跑一次）：分桶规模与覆盖关系一错，迁移就会"少搬一个世界却不报错"。
     *
     * @param s             已装配的快照
     * @param withProjectId 生成器给出的 88 张带 project_id 的表
     * @param childNames    生成器给出的 14 张子表
     */
    private static void selfCheck(Snapshot s, Set<String> withProjectId, Set<String> childNames) {
        require(s.world.size() == EXPECTED_WORLD_TABLES,
                "世界库表数应为 " + EXPECTED_WORLD_TABLES + "，实际 " + s.world.size()
                        + "（db/worldsplit-tables.json 与设计 §3.2 漂移）");
        require(s.registry.size() == EXPECTED_REGISTRY_TABLES,
                "目录库表数应为 " + EXPECTED_REGISTRY_TABLES + "，实际 " + s.registry.size());
        require(s.mirror.size() == EXPECTED_MIRROR, "镜像表应为 " + EXPECTED_MIRROR + " 张");
        require(s.projectScoped.size() == EXPECTED_PROJECT_SCOPED,
                "可筛表应为 " + EXPECTED_PROJECT_SCOPED + " 张，实际 " + s.projectScoped.size());
        require(s.backfill.size() == EXPECTED_BACKFILL, "回填表应为 " + EXPECTED_BACKFILL + " 张");
        require(s.child.size() == EXPECTED_CHILD, "子表应为 " + EXPECTED_CHILD + " 张");
        require(s.observation.size() == EXPECTED_OBSERVATION, "观测表应为 " + EXPECTED_OBSERVATION + " 张");

        // 生成器的 child 名单必须与写死规则一一对应（少一条规则 = 某张子表整表不搬）
        require(childNames.equals(tableNames(s.child)),
                "子表规则与生成器清单不一致：规则=" + tableNames(s.child) + "，清单=" + childNames);

        // 全部规则表必须都在世界库里，且 5 个桶的并集恰好等于世界库表集合
        Set<String> covered = new TreeSet<>(s.projectScoped);
        covered.addAll(tableNames(s.backfill));
        covered.addAll(tableNames(s.child));
        covered.addAll(s.mirror);
        covered.add(META_TABLE);
        require(covered.equals(s.world),
                "分桶并集与世界库表集合不一致：多=" + minus(covered, s.world) + "，少=" + minus(s.world, covered));

        // 可筛 + 回填 必须恰好是被 allow 的 88 张带 project_id 的表（不含 2 张镜像配置）
        Set<String> pidCovered = new TreeSet<>(s.projectScoped);
        pidCovered.addAll(tableNames(s.backfill));
        Set<String> pidExpected = new TreeSet<>(withProjectId);
        pidExpected.removeAll(s.mirror);
        require(pidCovered.equals(pidExpected),
                "按 project_id 归属的表集合不一致：多=" + minus(pidCovered, pidExpected)
                        + "，少=" + minus(pidExpected, pidCovered));

        // 规则 SQL 必须都带且只带一个占位参数（否则说明规则漏了世界过滤，会整表搬；
        // 多于一个的话，复制器只绑第 1 个，其余会以"Parameter not set"在真实迁移时才炸）
        for (RowRule r : s.allRules) {
            require(r.selectSql().contains("?"), "规则缺少 worldId 占位参数：" + r.table());
            require(r.selectSql().indexOf('?') == r.selectSql().lastIndexOf('?'),
                    "规则有多个占位参数（复制器只绑一个）：" + r.table());
            require(r.selectSql().contains("{}"), "规则缺少列占位：" + r.table());
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException("世界切分规则自检失败：" + message);
        }
    }

    private static Set<String> tableNames(List<RowRule> rules) {
        Set<String> out = new TreeSet<>();
        rules.forEach(r -> out.add(r.table()));
        return out;
    }

    private static Set<String> minus(Set<String> a, Set<String> b) {
        Set<String> out = new LinkedHashSet<>(a);
        out.removeAll(b);
        return out;
    }

    private static JsonNode readJson() {
        ClassPathResource res = new ClassPathResource(TABLES_JSON);
        if (!res.exists()) {
            throw new IllegalStateException("缺少表归属清单 " + TABLES_JSON
                    + "（由 scripts/dev/gen_worldsplit_schema.py 产出，M3 迁移器与分类测试共用）");
        }
        try (InputStream in = res.getInputStream()) {
            String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            return new ObjectMapper().readTree(text);
        } catch (IOException e) {
            throw new IllegalStateException("读取表归属清单失败：" + TABLES_JSON, e);
        }
    }

    private static Set<String> names(JsonNode root, String field) {
        JsonNode arr = root.get(field);
        if (arr == null || !arr.isArray()) {
            throw new IllegalStateException("表归属清单缺少数组字段 " + field + "（" + TABLES_JSON + "）");
        }
        Set<String> out = new TreeSet<>();
        for (JsonNode n : arr) {
            out.add(n.asText().toLowerCase(Locale.ROOT));
        }
        return out;
    }
}
