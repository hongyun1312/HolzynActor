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
import com.holzyn.actor.domain.npc.profile.repository.ActorCharacterRepository;
import com.holzyn.actor.domain.project.entity.ActorProject;
import com.holzyn.actor.domain.project.repository.ActorProjectRepository;
import com.holzyn.actor.domain.world.entity.ActorWorldClock;
import com.holzyn.actor.domain.world.service.WorldClockService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.flywaydb.core.Flyway;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.File;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 开发者模式「总览 / 自检 / 系统环境」数据服务。
 *
 * <p><b>总览要回答的问题</b>：这个进程现在健康吗？世界在推进吗？今天 AI 花了多少？参数被改过什么？
 * 所以它是三组数字的合并——引擎运行态（世界时钟/暂停/倍率）、内容规模（世界/角色/记忆）、
 * 成本与异常（今日调用数/token/失败数/待重启覆盖）。</p>
 *
 * <p><b>自检要回答的问题</b>：「页面为什么是空的」——多数时候不是页面坏了，而是世界没挂载、
 * 数据目录不可写、AI 供应商没配。自检把这类前置条件一条条列成 ok/fail，避免开发者从日志里猜。</p>
 *
 * <p><b>只读</b>：全程不写库、不触发 AI（观测不改变被观测对象）。</p>
 *
 * <p>所属模块：domain/dev（开发者模式控制台）</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DevSystemService {

    private final JdbcTemplate jdbcTemplate;
    private final Environment environment;
    private final Flyway flyway;
    private final ActorProjectRepository projectRepository;
    private final ActorCharacterRepository characterRepository;
    private final WorldClockService worldClockService;
    private final DevParamService paramService;

    /**
     * 开发者总览（引擎/内容/成本/参数四组指标）。
     *
     * <p><b>刻意不加 {@code @Transactional}</b>：本方法要先用 {@code requireClock} 探测「世界是否挂载」，
     * 而未挂载时它会抛异常——在同一个事务里「故意失败一次」会把事务标记为 rollback-only，
     * 之后的正常读取全部报「Transaction silently rolled back」。总览是纯读仪表盘，
     * 不需要跨表一致性快照，因此让各子查询各自自动提交，探针失败不影响后续读取。</p>
     *
     * @param projectId 当前世界 ID（可空=未挂载）
     *
     * @return 总览 Map
     */
    public Map<String, Object> overview(Long projectId) {
        // 挂载状态是一切世界库查询的前置条件：路由数据源在未挂载时指向目录库，
        // 而 actor_profile / actor_ai_call_log / actor_memory 只在世界库里有。
        // 不先判定就查，会得到「Table not found」500——总览页因此整页报错（实测）。
        boolean mounted = safeClock(projectId) != null;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("mounted", mounted);
        out.put("engine", engineBlock(projectId));
        out.put("content", contentBlock(projectId, mounted));
        out.put("cost", costBlock(mounted));
        out.put("params", paramBlock());
        out.put("recentErrors", recentErrors(mounted));
        return out;
    }

    /**
     * 自检清单（前置条件逐条判定）。
     *
     * @param projectId 当前世界 ID（可空）
     *
     * @return {checks: [{key,name,ok,detail}], failed}
     */
    public Map<String, Object> health(Long projectId) {
        List<Map<String, Object>> checks = new ArrayList<>();
        checks.add(check("db", "数据库连接", () -> {
            Integer one = jdbcTemplate.queryForObject("select 1", Integer.class);
            return one != null && one == 1;
        }, "目录库可读写（select 1）"));
        checks.add(check("flyway", "Schema 迁移", () -> {
            var current = flyway.info().current();
            return current != null && current.getState().isApplied();
        }, "Flyway 迁移已应用到最新"));
        checks.add(check("world", "世界挂载", () -> projectId != null && safeClock(projectId) != null,
                projectId == null ? "当前没有挂载的世界（多数页面会显示空态）" : "世界已挂载，可读实时数据"));
        checks.add(check("aiprovider", "AI 供应商配置", () -> count("select count(*) from actor_model_provider where enabled = 1") > 0,
                "至少有一个启用中的模型供应商（否则 AI 调用 fail-closed）"));
        checks.add(check("decision", "决策模型 判定层", () -> {
            String v = environment.getProperty("holzyn.actor.ai.decision.enabled", "false");
            return Boolean.parseBoolean(v);
        }, "决策模型 判定开关（关闭时判定层静默，只剩规则兜底）"));
        checks.add(check("datadir", "数据目录可写", () -> {
            Path dir = dataDir();
            return Files.isDirectory(dir) && Files.isWritable(dir);
        }, dataDir().toString()));
        checks.add(check("logfile", "日志文件", () -> {
            String f = environment.getProperty("holzyn.actor.log.file", "logs/holzyn-actor.log");
            File file = new File(f);
            return file.getParentFile() == null || file.getParentFile().exists() || file.getParentFile().mkdirs();
        }, environment.getProperty("holzyn.actor.log.file", "logs/holzyn-actor.log")));
        // 覆盖文件「不存在」是正常状态（全部参数用默认值），因此这里判的是「目录可写」而不是「文件存在」：
        // 曾经把不存在算成失败，结果每次全新环境自检都亮红灯，反而让真正的红灯被忽略。
        checks.add(check("override", "参数覆盖文件", () -> {
            Path file = DevOverrideFile.path();
            Path parent = file.getParent();
            boolean dirOk = parent == null || (Files.isDirectory(parent) && Files.isWritable(parent));
            return dirOk && (!Files.exists(file) || Files.isReadable(file));
        }, Files.exists(DevOverrideFile.path())
                ? "覆盖文件存在：重启后端会按文件内容覆盖 yml（" + DevOverrideFile.FILE_NAME + "）"
                : "暂无覆盖文件：全部参数使用 yml 默认值（首次改「需重启」参数时自动生成）"));

        long failed = checks.stream().filter(c -> Boolean.FALSE.equals(c.get("ok"))).count();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("checks", checks);
        out.put("failed", failed);
        return out;
    }

    /**
     * 系统环境（版本/JDK/OS/内存/目录/迁移/端口）。
     *
     * @return 环境 Map
     */
    public Map<String, Object> system() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("appVersion", appVersion());
        out.put("javaVersion", System.getProperty("java.version"));
        out.put("javaVendor", System.getProperty("java.vendor"));
        out.put("os", System.getProperty("os.name") + " " + System.getProperty("os.version") + " (" + System.getProperty("os.arch") + ")");
        out.put("cpuCores", Runtime.getRuntime().availableProcessors());
        Runtime rt = Runtime.getRuntime();
        Map<String, Object> mem = new LinkedHashMap<>();
        mem.put("maxMb", rt.maxMemory() / 1024 / 1024);
        mem.put("totalMb", rt.totalMemory() / 1024 / 1024);
        mem.put("freeMb", rt.freeMemory() / 1024 / 1024);
        mem.put("usedMb", (rt.totalMemory() - rt.freeMemory()) / 1024 / 1024);
        out.put("memory", mem);
        out.put("uptimeSeconds", ManagementFactory.getRuntimeMXBean().getUptime() / 1000);
        out.put("pid", ProcessHandle.current().pid());
        out.put("dataDir", dataDir().toString());
        out.put("logFile", environment.getProperty("holzyn.actor.log.file", "logs/holzyn-actor.log"));
        out.put("uploadDir", environment.getProperty("holzyn.actor.upload-dir", "./uploads"));
        out.put("serverPort", environment.getProperty("server.port", "18080"));
        out.put("timezone", System.getProperty("user.timezone"));
        out.put("overrideFile", DevOverrideFile.path().toString());
        out.put("overrideCount", paramService.pendingRestartCount());
        out.put("pendingRestart", paramService.hasPendingRestart());
        out.put("schemaVersion", schemaVersion());
        out.put("profile", String.join(",", environment.getActiveProfiles()));
        // 功能门禁（页面据此决定「要不要发请求」，而不是发了再吃 403）：
        // test-enabled=false 时测试/评估接口一律 403，属预期配置，不是故障。
        Map<String, Object> features = new LinkedHashMap<>();
        features.put("testEnabled", environment.getProperty("holzyn.actor.evaluation.test-enabled", Boolean.class, false));
        features.put("realtimeDecision", environment.getProperty("holzyn.actor.realtime-decision", Boolean.class, false));
        out.put("features", features);
        return out;
    }

    // ==================== 内部 ====================

    /**
     * 引擎运行态（世界时钟/暂停/倍率；未挂载时全部为 null）。
     */
    private Map<String, Object> engineBlock(Long projectId) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("projectId", projectId);
        m.put("projectName", projectName(projectId));
        var clock = safeClock(projectId);
        if (clock == null) {
            m.put("mounted", false);
            return m;
        }
        m.put("mounted", true);
        m.put("rate", clock.getRate());
        m.put("paused", clock.getPaused());
        m.put("worldRunning", clock.getWorldRunning());
        m.put("lastGameSecond", clock.getLastGameSecond());
        m.put("lastSimTime", clock.getLastSimTime() == null ? null : clock.getLastSimTime().toString());
        m.put("lastEngineRunAt", clock.getLastEngineRunAt() == null ? null : clock.getLastEngineRunAt().toString());
        try {
            m.put("gameTimeText", worldClockService.gameTimeTextOf(projectId,
                    worldClockService.currentEngineGameSecond(projectId)));
        } catch (Exception e) {
            m.put("gameTimeText", "");
        }
        return m;
    }

    /**
     * 内容规模（世界/角色/角色状态/记忆/AI 调用累计）。
     *
     * @param projectId 世界 ID（可空）
     *
     * @param mounted 世界是否已挂载（未挂载时世界库表不可读，只给世界观数）
     *
     * @return 内容块
     */
    private Map<String, Object> contentBlock(Long projectId, boolean mounted) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("worldCount", count("select count(*) from actor_project where deleted = 0"));
        if (mounted && projectId != null) {
            m.put("characterCount", characterRepository.countByProjectIdAndDeleted(projectId, 0));
            m.put("crowdCount", count("select count(*) from actor_profile where project_id = ? and deleted = 0 and type <> 'special'", projectId));
            m.put("aiCallTotal", count("select count(*) from actor_ai_call_log"));
            m.put("memoryTotal", count("select count(*) from actor_memory where deleted = 0"));
        } else {
            m.put("characterCount", null);
            m.put("crowdCount", null);
            m.put("aiCallTotal", null);
            m.put("memoryTotal", null);
        }
        return m;
    }

    /**
     * 成本块（今日/近 7 天调用数与 token）。
     *
     * @param mounted 世界是否已挂载（actor_ai_call_log 在世界库）
     *
     * @return 成本块
     */
    private Map<String, Object> costBlock(boolean mounted) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (!mounted) {
            m.put("todayCalls", null);
            m.put("todayTokens", null);
            m.put("weekCalls", null);
            m.put("weekTokens", null);
            m.put("failedToday", null);
            return m;
        }
        LocalDateTime todayStart = LocalDate.now().atStartOfDay();
        LocalDateTime weekStart = todayStart.minusDays(6);
        m.put("todayCalls", count("select count(*) from actor_ai_call_log where created_at >= ?", todayStart));
        m.put("todayTokens", count("select coalesce(sum(token_in + token_out), 0) from actor_ai_call_log where created_at >= ?", todayStart));
        m.put("weekCalls", count("select count(*) from actor_ai_call_log where created_at >= ?", weekStart));
        m.put("weekTokens", count("select coalesce(sum(token_in + token_out), 0) from actor_ai_call_log where created_at >= ?", weekStart));
        m.put("failedToday", count("select count(*) from actor_ai_call_log where created_at >= ? and status in ('failed','degraded')", todayStart));
        return m;
    }

    /**
     * 参数块（总数/已改/待重启）。
     */
    private Map<String, Object> paramBlock() {
        Map<String, Object> m = new LinkedHashMap<>();
        Map<String, Object> stats = paramService.stats();
        m.put("total", stats.get("total"));
        m.put("overridden", stats.get("overridden"));
        m.put("pendingRestart", stats.get("pendingRestart"));
        m.put("overrideFile", stats.get("overrideFile"));
        return m;
    }

    /**
     * 最近失败调用（最多 10 条，供总览「最近异常」卡）。
     *
     * @param mounted 世界是否已挂载（actor_ai_call_log 在世界库；未挂载时返回空列表）
     *
     * @return 失败调用行
     */
    private List<Map<String, Object>> recentErrors(boolean mounted) {
        if (!mounted) {
            return List.of();
        }
        try {
            return jdbcTemplate.query(
                    "select id, scene, model, status, error_message, created_at from actor_ai_call_log "
                            + "where status in ('failed','degraded') order by id desc limit 10",
                    (rs, i) -> {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("id", rs.getLong("id"));
                        m.put("scene", rs.getString("scene"));
                        m.put("model", rs.getString("model"));
                        m.put("status", rs.getString("status"));
                        m.put("errorMessage", rs.getString("error_message"));
                        m.put("createdAt", String.valueOf(rs.getTimestamp("created_at")));
                        return m;
                    });
        } catch (Exception e) {
            return List.of();
        }
    }

    /**
     * 自检单条执行器（异常视为 fail，不抛）。
     */
    private Map<String, Object> check(String key, String name, java.util.function.BooleanSupplier test, String detail) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("key", key);
        m.put("name", name);
        boolean ok;
        try {
            ok = test.getAsBoolean();
        } catch (Exception e) {
            ok = false;
        }
        m.put("ok", ok);
        m.put("detail", detail);
        return m;
    }

    /**
     * 世界时钟（未挂载返回 null）。
     *
     * @param projectId 世界 ID
     *
     * @return 世界时钟实体（未挂载/异常为 null）
     */
    private ActorWorldClock safeClock(Long projectId) {
        if (projectId == null) {
            return null;
        }
        try {
            return worldClockService.requireClock(projectId);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 项目名（不存在返回 null）。
     */
    private String projectName(Long projectId) {
        if (projectId == null) {
            return null;
        }
        return projectRepository.findById(projectId).map(ActorProject::getName).orElse(null);
    }

    /**
     * 数据目录（与全仓口径一致）。
     */
    private Path dataDir() {
        String dir = System.getProperty(DataDirResolver.ENV_DATA_DIR);
        if (dir == null || dir.isBlank()) {
            dir = environment.getProperty("holzyn.actor.data-dir", "./data");
        }
        return Path.of(dir).toAbsolutePath();
    }

    /**
     * 计数查询（失败返回 0）。
     */
    private long count(String sql, Object... args) {
        try {
            Long v = jdbcTemplate.queryForObject(sql, Long.class, args);
            return v == null ? 0 : v;
        } catch (Exception e) {
            log.debug("[开发者模式] 计数查询失败：{} - {}", sql, e.getMessage());
            return 0;
        }
    }

    /**
     * 迁移版本（失败返回 null）。
     */
    private String schemaVersion() {
        try {
            var current = flyway.info().current();
            return current == null || current.getVersion() == null ? null : current.getVersion().getVersion();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 应用版本（jar manifest 优先，开发态回退固定串）。
     */
    private String appVersion() {
        String v = getClass().getPackage() == null ? null : getClass().getPackage().getImplementationVersion();
        return v == null ? "dev" : v;
    }
}
