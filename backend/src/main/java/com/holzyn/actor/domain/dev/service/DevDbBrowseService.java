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

import com.holzyn.actor.common.BizException;
import com.holzyn.actor.domain.debug.service.DebugDbService;
import com.holzyn.actor.domain.worldstore.RegistryTables;
import com.holzyn.actor.domain.worldstore.WorldDataSourceFactory;
import com.holzyn.actor.domain.worldstore.WorldPaths;
import com.holzyn.actor.domain.worldstore.WorldRegistryService;
import com.holzyn.actor.domain.worldstore.WorldSummaryVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Stream;

/**
 * 开发者数据页 · 分库只读浏览服务（{@code /api/dev/db/**}）。
 *
 * <p><b>为什么不能继续复用 {@code /api/debug/db/*}</b>：那套接口走 {@code JdbcTemplate}
 * 的<b>路由数据源</b>——查哪个库取决于「此刻有没有挂载世界」，于是同一个「数据」页
 * 在挂载态展示世界库、在未挂载态展示目录库，表清单与行数会随挂载状态跳变。
 * 开发者排查问题时最怕的正是这种「看到的库不可控」。</p>
 *
 * <p>本服务把存储形态显式分成两类，并允许指定目标：</p>
 * <ul>
 *   <li><b>系统库</b>（{@code holzyn-registry.mv.db}，目录库）：应用/账户/设置/供应商/Prompt/
 *       用量与 AI 调用日志，共 {@link RegistryTables#ALL} 16 张；</li>
 *   <li><b>存档库</b>（{@code worlds/&lt;uid&gt;/world.mv.db} + 同目录资源）：每个存档一套
 *       彼此独立的 H2 文件，业务表 60+ 张（角色/记忆/事件/时钟/沙盘…）。未挂载的存档以
 *       <b>只读连接</b>打开，绝不触发建库或 Flyway 迁移。</li>
 * </ul>
 *
 * <p>表名与敏感列仍由 {@link DebugDbService} 的白名单/黑名单把关（同一份规则，不复制）。</p>
 *
 * <p>所属模块：domain/dev（开发者模式控制台）</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DevDbBrowseService {

    /** 系统库范围标识 */
    public static final String SCOPE_SYSTEM = "system";

    /** 存档库范围标识 */
    public static final String SCOPE_WORLD = "world";

    /** 存档目录结构最多返回的条目数（防一次拉爆响应） */
    private static final int MAX_FILES = 400;

    /** 目录遍历最大深度（worlds/&lt;uid&gt;/a/b 足够看清结构） */
    private static final int MAX_DEPTH = 3;

    /** 目录库连接源（显式指名，不靠「当前上下文恰好是全局」） */
    @Autowired
    @Qualifier("registryDataSource")
    private DataSource registryDataSource;

    private final WorldPaths paths;

    private final WorldRegistryService registryService;

    private final WorldDataSourceFactory dataSourceFactory;

    private final DebugDbService debugDbService;

    /** H2 用户（与世界库连接池同源，避免只读连接被拒） */
    @Value("${spring.datasource.username:sa}")
    private String dbUser = "sa";

    /** H2 口令（默认空） */
    @Value("${spring.datasource.password:}")
    private String dbPassword = "";

    // ==================== 范围清单 ====================

    /**
     * 数据页可选的「库范围」：系统库一项 + 每个存档一项。
     *
     * @return {system:{...}, archives:[...]}
     */
    public Map<String, Object> scopes() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("system", systemScope());

        List<Map<String, Object>> archives = new ArrayList<>();
        for (WorldSummaryVO w : registryService.list()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("scope", SCOPE_WORLD);
            m.put("worldId", w.id());
            m.put("worldUid", w.worldUid());
            m.put("name", w.name());
            m.put("mounted", w.mounted());
            m.put("status", w.worldStatus());
            m.put("statusLabel", w.worldStatusLabel());
            m.put("dirPresent", w.dirPresent());
            m.put("dbPresent", w.dbPresent());
            m.put("lockPresent", w.lockPresent());
            m.put("sizeBytes", w.sizeBytes());
            m.put("gameSecond", w.gameSecond());
            m.put("characterCount", w.characterCount());
            m.put("dbPath", w.worldUid() == null ? null : relative(paths.worldDbFile(w.worldUid())));
            m.put("dirPath", w.worldUid() == null ? null : relative(paths.worldDir(w.worldUid())));
            m.put("updatedAt", w.updatedAt());
            archives.add(m);
        }
        archives.sort(Comparator.comparing(m -> String.valueOf(m.get("name"))));
        out.put("archives", archives);
        return out;
    }

    /**
     * 系统库（目录库）范围描述。
     *
     * @return 范围信息
     */
    private Map<String, Object> systemScope() {
        Path file = paths.registryDbFile();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("scope", SCOPE_SYSTEM);
        m.put("label", "系统库（应用）");
        m.put("dbPath", relative(file));
        m.put("dataDir", relative(paths.dataDir()));
        m.put("exists", Files.isRegularFile(file));
        m.put("sizeBytes", fileSize(file));
        m.put("tableCount", RegistryTables.ALL.size());
        m.put("note", "账户 / 设置 / 供应商 / Prompt / 用量 / AI 调用日志 / 世界索引；与世界存档无关的全局数据。");
        return m;
    }

    // ==================== 表清单 / 浏览 ====================

    /**
     * 指定范围内的表清单（含行数与列数）。
     *
     * @param scope 范围（system / world）
     *
     * @param worldId 存档 ID（scope=world 时必填）
     *
     * @return 表信息列表
     */
    public List<Map<String, Object>> tables(String scope, Long worldId) {
        if (SCOPE_SYSTEM.equals(normalizeScope(scope))) {
            JdbcTemplate jdbc = new JdbcTemplate(registryDataSource);
            return debugDbService.tables(jdbc, RegistryTables.SET);
        }
        String uid = requireWorldUid(worldId);
        Set<String> whitelist = DebugDbService.whitelistTables();
        return withWorldDb(uid, (jdbc) -> debugDbService.tables(jdbc, whitelist));
    }

    /**
     * 指定范围内只读分页浏览一张表。
     *
     * @param scope 范围
     *
     * @param worldId 存档 ID
     *
     * @param table 表名
     *
     * @param page 页码
     *
     * @param size 每页条数
     *
     * @param keyword 关键字
     *
     * @return 分页结果
     */
    public Map<String, Object> browse(String scope, Long worldId, String table, int page, int size, String keyword) {
        if (SCOPE_SYSTEM.equals(normalizeScope(scope))) {
            JdbcTemplate jdbc = new JdbcTemplate(registryDataSource);
            return debugDbService.browse(jdbc, RegistryTables.SET, table, page, size, keyword);
        }
        String uid = requireWorldUid(worldId);
        Set<String> whitelist = DebugDbService.whitelistTables();
        return withWorldDb(uid, (jdbc) -> debugDbService.browse(jdbc, whitelist, table, page, size, keyword));
    }

    /**
     * 某存档的目录结构（存档形态：世界库文件 + 清单 + 资源目录）。
     *
     * @param worldId 存档 ID
     *
     * @return {worldId,worldUid,name,dirPath,dbPath,sizeBytes,entries:[...]}
     */
    public Map<String, Object> files(Long worldId) {
        WorldSummaryVO w = registryService.findById(worldId)
                .orElseThrow(() -> new BizException(404, "存档不存在：" + worldId));
        Path dir = paths.worldDir(w.worldUid());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("worldId", w.id());
        out.put("worldUid", w.worldUid());
        out.put("name", w.name());
        out.put("mounted", w.mounted());
        out.put("dirPath", relative(dir));
        out.put("dbPath", relative(paths.worldDbFile(w.worldUid())));
        out.put("sizeBytes", w.sizeBytes());
        out.put("dirPresent", w.dirPresent());

        List<Map<String, Object>> entries = new ArrayList<>();
        if (Files.isDirectory(dir)) {
            try (Stream<Path> stream = Files.walk(dir, MAX_DEPTH)) {
                stream.filter(p -> !p.equals(dir))
                        .sorted(Comparator.comparing(Path::toString))
                        .limit(MAX_FILES)
                        .forEach(p -> {
                            Map<String, Object> e = new LinkedHashMap<>();
                            boolean isDir = Files.isDirectory(p);
                            e.put("path", dir.relativize(p).toString().replace('\\', '/'));
                            e.put("dir", isDir);
                            e.put("depth", p.getNameCount() - dir.getNameCount());
                            e.put("sizeBytes", isDir ? null : fileSize(p));
                            entries.add(e);
                        });
            } catch (IOException e) {
                log.warn("[dev-db] 读取存档目录失败 uid={}: {}", w.worldUid(), e.getMessage());
                out.put("error", e.getMessage());
            }
        }
        out.put("entries", entries);
        return out;
    }

    // ==================== 连接与工具 ====================

    /**
     * 在指定存档的库上执行只读操作。
     *
     * <p><b>为什么未挂载时用 {@link DriverManager} 而不是工厂</b>：
     * {@link WorldDataSourceFactory#worldDataSource(String)} 会「建库 + 跑世界库迁移」——
     * 只是看一眼表清单，不该有创建/升级数据库的副作用。这里对<b>已打开的池</b>直接复用
     * （同一 JVM 内对同一 H2 文件无法开第二个不同参数的连接），对<b>未挂载</b>的存档
     * 用 {@code ACCESS_MODE_DATA=r} 只读打开，读完即关，不落任何写。</p>
     *
     * @param worldUid 世界 uid
     *
     * @param action 操作
     *
     * @param <T> 返回类型
     *
     * @return 操作结果
     */
    private <T> T withWorldDb(String worldUid, Function<JdbcTemplate, T> action) {
        DataSource pooled = dataSourceFactory.peek(worldUid);
        if (pooled != null) {
            return action.apply(new JdbcTemplate(pooled));
        }
        Path dbFile = paths.worldDbFile(worldUid);
        if (!Files.isRegularFile(dbFile)) {
            throw new BizException(409, "该存档的世界库文件不存在：" + relative(dbFile));
        }
        String url = paths.worldDbUrl(worldUid) + ";ACCESS_MODE_DATA=r";
        try (Connection conn = DriverManager.getConnection(url, dbUser, dbPassword)) {
            SingleConnectionDataSource ds = new SingleConnectionDataSource(conn, true);
            try {
                return action.apply(new JdbcTemplate(ds));
            } finally {
                ds.destroy();
            }
        } catch (SQLException e) {
            throw new BizException(500, "打开存档库失败（可能正被其他进程占用）：" + e.getMessage());
        }
    }

    /**
     * 解析 worldId → uid（并校验存档存在）。
     *
     * @param worldId 存档 ID
     *
     * @return 世界 uid
     */
    private String requireWorldUid(Long worldId) {
        if (worldId == null) {
            throw new BizException(400, "浏览存档库必须指定 worldId");
        }
        return registryService.findById(worldId)
                .orElseThrow(() -> new BizException(404, "存档不存在：" + worldId))
                .worldUid();
    }

    /**
     * 规范化范围参数（默认存档库：数据页最常看的是世界业务数据）。
     *
     * @param scope 原始范围
     *
     * @return system / world
     */
    public static String normalizeScope(String scope) {
        return SCOPE_SYSTEM.equalsIgnoreCase(scope == null ? "" : scope.trim()) ? SCOPE_SYSTEM : SCOPE_WORLD;
    }

    /**
     * 相对数据根目录的展示路径（绝对路径对排查没用，还会暴露本机目录结构）。
     *
     * @param p 路径
     *
     * @return 相对路径（无法相对时返回文件名）
     */
    private String relative(Path p) {
        if (p == null) {
            return null;
        }
        try {
            return paths.dataDir().relativize(p.toAbsolutePath().normalize()).toString().replace('\\', '/');
        } catch (IllegalArgumentException e) {
            return p.getFileName() == null ? p.toString() : p.getFileName().toString();
        }
    }

    /**
     * 文件大小（不存在或读失败返回 null）。
     *
     * @param p 路径
     *
     * @return 字节数
     */
    private Long fileSize(Path p) {
        try {
            return Files.isRegularFile(p) ? Files.size(p) : null;
        } catch (IOException e) {
            return null;
        }
    }
}
