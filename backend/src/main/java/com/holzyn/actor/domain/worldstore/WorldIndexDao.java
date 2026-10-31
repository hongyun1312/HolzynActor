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

import com.holzyn.actor.common.BizException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.TreeSet;

/**
 * 目录库 {@code actor_project} 里"存档索引列"的唯一写入口（V1.1.2-beta.1）。
 *
 * <p><b>为什么用裸 JDBC 而不是 JPA 实体</b>：三条硬理由。</p>
 * <ol>
 *   <li><b>Flyway 必须是 schema 唯一权威</b>（AGENTS §8.5.2）。把 12 个新列加进
 *       {@code ActorProject} 实体，启动期的 {@code DatabaseStructureValidator} 会自动
 *       {@code ALTER TABLE ADD COLUMN} 补列——那等于又回到"JPA 悄悄改表"，迁移漏写也看不出来。</li>
 *   <li><b>必须先于世界库可用</b>：存档列表要在"没有挂载世界"的状态下工作，走路由数据源会
 *       引入"此刻路由到哪"的不确定性；本类直接用目录库连接池，物理上不可能读错库。</li>
 *   <li><b>可测</b>：真 H2 库上直接断言"收编后列表能读出来"，不需要 Mock JPA 仓储。</li>
 * </ol>
 *
 * <p><b>与 {@code ProjectService} 的列归属划分</b>（AGENTS §8.1.4 单一写入口按列落实）：
 * {@code ProjectService}（JPA）继续独占 {@code name / summary / cover_url / code / status /
 * deleted}；本类只写 {@link #INDEX_COLUMNS} 里那 12 列，两者<b>列集合不相交</b>。
 * {@code updated_at} 两边都会写（它表达的是"这一行最近被动过"，无法归属单方）。</p>
 *
 * <p><b>过渡态兼容</b>：M2/M3 期间目录库可能还是旧的单体库（没有 V101 的列）。读路径对缺失列
 * 回退 {@code null}（列表页照常渲染），写路径则显式报 503 让人去跑迁移——不像"读也炸"
 * 那样把整个存档列表变成 500。</p>
 *
 * <p>所属模块：domain/worldstore（世界存档存储）</p>
 */
@Slf4j
@Component
public class WorldIndexDao {

    /** 本类独占写入的索引列（V101/V201 新增列 + V104 初始化快照三列 + V105 分类两列） */
    public static final List<String> INDEX_COLUMNS = List.of(
            "data_dir_name", "last_opened_at", "last_closed_at", "game_second", "character_count",
            "size_bytes", "world_status", "manifest_json", "token_in_total", "token_out_total",
            "ai_call_count", "schema_version", "init_status", "init_done", "init_total",
            "genre", "tone");

    /** 写路径必需的最小列集合（缺一个就没法维护索引） */
    private static final List<String> REQUIRED_FOR_WRITE = List.of("world_status", "data_dir_name", "last_opened_at");

    private final DataSource registryDataSource;

    /** 实际存在的列（首次访问时探测并缓存；过渡态下可能缺列） */
    private volatile Set<String> actualColumns;

    public WorldIndexDao(@Qualifier("registryDataSource") DataSource registryDataSource) {
        this.registryDataSource = registryDataSource;
    }

    /**
     * 一行存档索引（纯数据库字段，磁盘事实由 {@link WorldRegistryService} 补充）。
     *
     * @param id             主键
     * @param worldUid       世界身份
     * @param name           世界名
     * @param summary        概要
     * @param coverUrl       封面
     * @param status         世界状态
     * @param gameSecond     世界时间快照
     * @param characterCount 角色数快照
     * @param sizeBytes      体积快照
     * @param lastOpenedAt   最近挂载时间
     * @param lastClosedAt   最近卸载时间
     * @param createdAt      创建时间
     * @param updatedAt      更新时间
     * @param schemaVersion  建库版本
     * @param initStatus     初始化完成度快照（NOT_STARTED/PARTIAL/DONE/DEGRADED；NULL=未知）
     * @param initDone       已完成步骤数快照
     * @param initTotal      步骤总数快照
     */
    public record WorldIndexRow(Long id,
                                String worldUid,
                                String name,
                                String summary,
                                String coverUrl,
                                WorldStatus status,
                                Long gameSecond,
                                Long characterCount,
                                Long sizeBytes,
                                LocalDateTime lastOpenedAt,
                                LocalDateTime lastClosedAt,
                                LocalDateTime createdAt,
                                LocalDateTime updatedAt,
                                String schemaVersion,
                                String initStatus,
                                Integer initDone,
                                Integer initTotal,
                                String genre,
                                String tone) {
    }

    /**
     * 关闭世界时回写的摘要（空值表示"这次没读到"，用 coalesce 保留旧值）。
     *
     * @param closedAt       关闭时间
     * @param gameSecond     世界时间（{@code actor_world_clock.last_game_second}）
     * @param characterCount 角色数
     * @param sizeBytes      世界目录体积
     * @param manifestJson   清单快照
     * @param schemaVersion  建库版本
     */
    public record ClosedSummary(LocalDateTime closedAt,
                                Long gameSecond,
                                Long characterCount,
                                Long sizeBytes,
                                String manifestJson,
                                String schemaVersion) {
    }

    // ------------------------------------------------------------------ 读

    /**
     * 读取全部未删除的存档索引行（按 id 升序，顺序稳定）。
     *
     * @return 索引行列表
     */
    public List<WorldIndexRow> readAll() {
        String sql = "select id, project_uid, name, summary, cover_url, created_at, updated_at, "
                + sel("world_status") + " as ws, "
                + sel("game_second") + " as gs, "
                + sel("character_count") + " as cc, "
                + sel("size_bytes") + " as sb, "
                + sel("last_opened_at") + " as lo, "
                + sel("last_closed_at") + " as lc, "
                + sel("schema_version") + " as sv, "
                + sel("init_status") + " as ist, "
                + sel("init_done") + " as idn, "
                + sel("init_total") + " as itt, "
                + sel("genre") + " as gn, "
                + sel("tone") + " as tn "
                + "from actor_project where deleted is null or deleted = 0 order by id asc";
        List<WorldIndexRow> out = new ArrayList<>();
        try (Connection c = registryDataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                out.add(map(rs));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("读取存档列表失败（目录库 actor_project）", e);
        }
        return out;
    }

    /**
     * 按 id 读取一行。
     *
     * @param id 主键
     *
     * @return 索引行；不存在（或已软删）返回空
     */
    public Optional<WorldIndexRow> readById(long id) {
        return readAll().stream().filter(r -> r.id() != null && r.id() == id).findFirst();
    }

    /**
     * 按世界身份读取一行。
     *
     * @param worldUid 世界 uid
     *
     * @return 索引行；不存在返回空
     */
    public Optional<WorldIndexRow> readByUid(String worldUid) {
        if (worldUid == null || worldUid.isBlank()) {
            return Optional.empty();
        }
        return readAll().stream().filter(r -> worldUid.equals(r.worldUid())).findFirst();
    }

    /**
     * uid → 本地 id（挂载流程用）。
     *
     * @param worldUid 世界 uid
     *
     * @return id；不存在返回空
     */
    public OptionalLong findIdByUid(String worldUid) {
        return readByUid(worldUid).map(r -> OptionalLong.of(r.id())).orElseGet(OptionalLong::empty);
    }

    // ------------------------------------------------------------------ 写

    /**
     * 收编：为一个磁盘上已存在、目录库里还没有索引行的世界插入索引行。
     *
     * @param worldUid      世界 uid（= 目录名）
     * @param name          世界名（取 manifest）
     * @param userId        归属用户
     * @param status        世界状态
     * @param schemaVersion 建库版本（可空）
     * @param manifestJson  清单快照（可空）
     * @param now           当前时间
     *
     * @return 新行主键
     *
     * @throws BizException 目录库缺索引列（迁移未完成）
     */
    public long insertIndexRow(String worldUid, String name, Long userId, WorldStatus status,
                               String schemaVersion, String manifestJson, LocalDateTime now) {
        requireWritableColumns();
        String sql = "insert into actor_project "
                + "(project_uid, user_id, name, status, deleted, created_at, updated_at, "
                + "data_dir_name, world_status, schema_version, manifest_json) "
                + "values (?, ?, ?, 0, 0, ?, ?, ?, ?, ?, ?)";
        try (Connection c = registryDataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql, java.sql.Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, worldUid);
            ps.setLong(2, userId);
            ps.setString(3, name == null || name.isBlank() ? worldUid : name);
            ps.setTimestamp(4, Timestamp.valueOf(now));
            ps.setTimestamp(5, Timestamp.valueOf(now));
            ps.setString(6, worldUid);
            ps.setString(7, status.code());
            ps.setString(8, schemaVersion);
            ps.setString(9, manifestJson);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) {
                    return keys.getLong(1);
                }
            }
            // 驱动不返回自增键时回查（H2 支持，兜底而已）
            return findIdByUid(worldUid).orElseThrow(() ->
                    new IllegalStateException("收编后回查不到索引行：uid=" + worldUid));
        } catch (SQLException e) {
            throw new IllegalStateException("收编世界失败（插入 actor_project）：uid=" + worldUid, e);
        }
    }

    /**
     * 标记已挂载（同时刷新"最近打开时间"）。
     *
     * @param id    主键
     * @param now   当前时间
     * @param uid   世界 uid（写入 data_dir_name，便于人工核对）
     */
    public void markMounted(long id, String uid, LocalDateTime now) {
        execute("update actor_project set world_status = ?, last_opened_at = ?, updated_at = ?, data_dir_name = ? where id = ?",
                WorldStatus.MOUNTED.code(), Timestamp.valueOf(now), Timestamp.valueOf(now), uid, id);
    }

    /**
     * 把索引行补齐成"可分档存储的世界壳"（uid + 目录名 + 建库版本）。
     *
     * <p>为什么需要这个方法：{@code ProjectImportService} / {@code WorldParseService} 这两条新建流程
     * 走的是老的 {@code ProjectService.create}（JPA 直接插 {@code actor_project} 实体），插出来的行
     * 没有 {@code project_uid}、也没有 {@code data_dir_name}——分档存储后这种行**永远打不开**
     * （挂载时缺 uid 直接 409）。这里补的就是那两个字段，不动其余列。</p>
     *
     * @param id            主键
     * @param worldUid      世界 uid（= 目录名）
     * @param schemaVersion 建库版本（可空）
     * @param now           当前时间
     */
    public void bindShell(long id, String worldUid, String schemaVersion, LocalDateTime now) {
        requireWritableColumns();
        execute("update actor_project set project_uid = ?, data_dir_name = ?, schema_version = ?, "
                        + "world_status = ?, updated_at = ? where id = ?",
                worldUid, worldUid, schemaVersion, WorldStatus.CLOSED.code(), Timestamp.valueOf(now), id);
    }

    /**
     * 标记已卸载并回写摘要（空值用 coalesce 保留旧值）。
     *
     * @param id      主键
     * @param summary 摘要
     */
    public void markClosed(long id, ClosedSummary summary) {
        Timestamp at = Timestamp.valueOf(summary.closedAt());
        execute("update actor_project set world_status = ?, last_closed_at = ?, updated_at = ?, "
                        + "game_second = coalesce(?, game_second), "
                        + "character_count = coalesce(?, character_count), "
                        + "size_bytes = coalesce(?, size_bytes), "
                        + "manifest_json = coalesce(?, manifest_json), "
                        + "schema_version = coalesce(?, schema_version) "
                        + "where id = ?",
                WorldStatus.CLOSED.code(), at, at,
                summary.gameSecond(), summary.characterCount(), summary.sizeBytes(),
                summary.manifestJson(), summary.schemaVersion(), id);
    }

    /**
     * 一次性迁移落地后的索引回写（关闭态 + 世界目录名 + 摘要）。
     *
     * <p>为什么不复用 {@link #markClosed}：迁移后这一行<b>从未被挂载过</b>，而
     * {@code data_dir_name} 必须在此刻就写上（列表页/诊断都要靠它确认"这一行对应哪个目录"），
     * 只有挂载路径才会写它——不补这一步，迁移出来的世界在列表里 {@code dataDirName} 一直是空的。</p>
     *
     * <p>刻意仍由本类写：{@code actor_project} 的 12 个索引列只有一个写入口（AGENTS §8.1.4），
     * 迁移器不允许自己拼 SQL 去改它们。</p>
     *
     * @param id      主键
     * @param uid     世界 uid（写入 data_dir_name）
     * @param summary 摘要（关闭时间/游戏内时间/角色数/体积/清单快照/版本）
     * @param now     当前时间（updated_at）
     */
    public void markMigrated(long id, String uid, ClosedSummary summary, LocalDateTime now) {
        Timestamp closedAt = Timestamp.valueOf(summary.closedAt());
        execute("update actor_project set world_status = ?, data_dir_name = ?, last_closed_at = ?, "
                        + "updated_at = ?, "
                        + "game_second = coalesce(?, game_second), "
                        + "character_count = coalesce(?, character_count), "
                        + "size_bytes = coalesce(?, size_bytes), "
                        + "manifest_json = coalesce(?, manifest_json), "
                        + "schema_version = coalesce(?, schema_version) "
                        + "where id = ?",
                WorldStatus.CLOSED.code(), uid, closedAt, Timestamp.valueOf(now),
                summary.gameSecond(), summary.characterCount(), summary.sizeBytes(),
                summary.manifestJson(), summary.schemaVersion(), id);
    }

    /**
     * 回写跨世界用量摘要（{@code token_in_total / token_out_total / ai_call_count}）。
     *
     * <p>为什么单独一条 update 而不是并进 {@link #markClosed}：这三个数字来自世界的
     * {@code actor_usage_log}，只有"世界关闭前"能算（{@link WorldSummaryReader#readUsage}）；
     * 而 {@link ClosedSummary} 的语义是"关闭那一刻的存档卡片快照"，把用量塞进去会让
     * 这个 record 同时承担两种来源的数据。分成两次写，任一次失败都不影响另一次
     * （摘要失败不阻止关世界，是本类既有的失败策略）。</p>
     *
     * <p>空值用 {@code coalesce} 保留旧值：读世界用量失败时不把历史汇总清零。</p>
     *
     * @param id       主键
     * @param tokenIn  输入 token 合计（可空）
     * @param tokenOut 输出 token 合计（可空）
     * @param aiCalls  调用次数（可空）
     */
    public void updateUsageSummary(long id, Long tokenIn, Long tokenOut, Long aiCalls) {
        execute("update actor_project set token_in_total = coalesce(?, token_in_total), "
                        + "token_out_total = coalesce(?, token_out_total), "
                        + "ai_call_count = coalesce(?, ai_call_count) where id = ?",
                tokenIn, tokenOut, aiCalls, id);
    }

    /**
     * 改名 / 封面 / 概要（只写目录库，不碰世界库）。
     *
     * <p>为什么不让 {@code ProjectService.update()} 走 JPA：{@code ActorProject} 是"全局实体 + 世界内只读镜像"，
     * 世界挂载时 JPA {@code save()} 会打到世界库的镜像行上（{@link GlobalMirrorWriteGuard} 会直接抛），
     * 而改名本来就是目录库语义——用索引的唯一写入口最稳。</p>
     *
     * <p>空值用 {@code coalesce} 保留旧值：前端只提交改动的字段时，不传的字段不该被清成 null。</p>
     *
     * @param id       主键
     * @param name     新世界名（可空）
     * @param summary  新概要（可空）
     * @param coverUrl 新封面（可空）
     * @param now      当前时间（updated_at）
     */
    public void updateMeta(long id, String name, String summary, String coverUrl, LocalDateTime now) {
        execute("update actor_project set name = coalesce(?, name), summary = coalesce(?, summary), "
                        + "cover_url = coalesce(?, cover_url), updated_at = ? where id = ?",
                name, summary, coverUrl, Timestamp.valueOf(now), id);
    }

    /**
     * 只写世界分类与程度（画廊卡片标签的展示快照）。
     *
     * <p>为什么单独一个方法而不是并进 {@link #updateMeta}：那两个是展示列、可以在世界还没挂载时
     * 先写；而这个写的值来自世界库的 {@code actor_world_setting}，只有世界观保存成功后才同步，
     * 语义上属于"派生快照"。用 {@code coalesce} 保留旧值，避免只提交其一时把另一列清掉。</p>
     *
     * @param id     主键
     * @param genre  世界类别（中文标签，可空）
     * @param tone   世界程度编码（casual/light/standard/hard，可空）
     */
    public void updateClassification(long id, String genre, String tone) {
        execute("update actor_project set genre = coalesce(?, genre), tone = coalesce(?, tone), "
                + "updated_at = ? where id = ?", genre, tone, Timestamp.valueOf(LocalDateTime.now()), id);
    }

    /**
     * 只写封面（允许写成 {@code null} = 清除封面）。     *
     * <p>为什么不复用 {@link #updateMeta(long, String, String, String, LocalDateTime)}：它用
     * {@code coalesce} 保留旧值，传 null 等于"不改"，于是"移除封面"这个动作根本没有写入口
     * ——封面文件删了而 {@code cover_url} 仍指着它，画廊就会一直请求一个 404。
     * 本方法按字面写值，是清除封面的唯一入口。</p>
     *
     * @param id       主键
     * @param coverUrl 新封面文件名（相对世界目录，如 {@code cover.png}；null = 清除）
     * @param now      当前时间（updated_at）
     */
    public void setCover(long id, String coverUrl, LocalDateTime now) {
        execute("update actor_project set cover_url = ?, updated_at = ? where id = ?",
                coverUrl, Timestamp.valueOf(now), id);
    }

    /**
     * 归档删除：软删索引行（世界目录已被移进 {@code archive/deleted-worlds/}）。
     *
     * <p>为什么只软删不硬删：列表/详情/统计全按 {@code deleted=0} 过滤，软删即"用户看不见"；
     * 而硬删会让"这个 id 曾经存在过"的痕迹一起消失——排查误删时恰恰需要那条索引
     * （世界名、uid、删前摘要）。目录已移走，索引留着不占空间。</p>
     *
     * @param id  主键
     * @param now 当前时间（updated_at）
     */
    public void markDeleted(long id, LocalDateTime now) {
        execute("update actor_project set deleted = 1, updated_at = ? where id = ?",
                Timestamp.valueOf(now), id);
    }

    /**
     * 硬删索引行（只用于"新建世界失败时的回滚"）。
     *
     * <p>与 {@link #markDeleted} 的分工：新建到一半失败（目录建不出来 / manifest 写不进 /
     * 世界库迁移失败）时，这一行是<b>本进程刚插进去的垃圾</b>，留着会让列表里出现一个
     * 永远打不开的"空世界"；此时必须彻底删掉，而不是软删。</p>
     *
     * @param id 主键
     */
    public void deleteRow(long id) {
        execute("delete from actor_project where id = ?", id);
    }

    /**
     * 只改状态（收编时标记 missing / incompatible / broken）。
     *
     * @param id     主键
     * @param status 目标状态
     * @param now    当前时间
     */
    public void markStatus(long id, WorldStatus status, LocalDateTime now) {
        execute("update actor_project set world_status = ?, updated_at = ? where id = ?",
                status.code(), Timestamp.valueOf(now), id);
    }

    /**
     * 只改世界初始化完成度快照（V104 三列；画廊卡片角标用）。
     *
     * <p>归属：初始化状态服务在「每步执行后 / 打开世界 / 新建世界」时调用，
     * 把世界库里的权威判定结果缓存到目录库，让画廊不在"没打开世界"时必须开库。</p>
     *
     * @param id     主键（= 项目 ID）
     * @param status 完成度：NOT_STARTED / PARTIAL / DONE / DEGRADED
     * @param done   已完成步骤数
     * @param total  步骤总数
     */
    public void updateInitSummary(long id, String status, Integer done, Integer total) {
        execute("update actor_project set init_status = ?, init_done = ?, init_total = ?, updated_at = ? where id = ?",
                status, done, total, Timestamp.valueOf(LocalDateTime.now()), id);
    }

    /**
     * 只改世界初始化「步骤明细」JSON（V104 init_steps 列）。
     *
     * <p>为什么要明细而不是只看实时数据：初始化必须区分「从未跑过」与「跑过但 AI 失败 / 用户跳过」——
     * 例如角色卡步骤失败时角色表本来就是空的，实时校验与"没跑过"完全一样，向导就没法告诉用户
     * 失败原因、也没法判断哪些可选步骤已被显式跳过。</p>
     *
     * @param id   主键（= 项目 ID）
     * @param json 步骤状态 JSON（null/空串表示清空）
     */
    public void updateInitSteps(long id, String json) {
        execute("update actor_project set init_steps = ?, updated_at = ? where id = ?",
                json, Timestamp.valueOf(LocalDateTime.now()), id);
    }

    /**
     * 读世界初始化「步骤明细」JSON（缺列 / 缺行 / 解析失败一律返回 null，调用方按"无状态"处理）。
     *
     * @param id 主键（= 项目 ID）。注意：必须在挂载世界之外也能读，故只查目录库。
     *
     * @return 步骤状态 JSON 原文，读不到为 null
     */
    public String readInitSteps(long id) {
        if (!columns().contains("init_steps")) {
            return null;
        }
        try (Connection c = registryDataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("select init_steps from actor_project where id = ?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        } catch (SQLException e) {
            log.debug("[world-store] 读取初始化步骤状态失败（按无状态处理）：id={}, {}", id, e.getMessage());
            return null;
        }
    }

    /**
     * 启动时把残留的 {@code mounted} 归零为 {@code closed}。
     *
     * <p>进程上次被强杀时状态会停在 {@code mounted}；不改的话列表页永远显示"运行中"，
     * 而实际没有任何进程持有它，用户也点不开（状态不可挂载）。</p>
     *
     * @param now 当前时间
     *
     * @return 被归零的行数
     */
    public int resetMountedOnStartup(LocalDateTime now) {
        return execute("update actor_project set world_status = ?, updated_at = ? where world_status = ?",
                WorldStatus.CLOSED.code(), Timestamp.valueOf(now), WorldStatus.MOUNTED.code());
    }

    // ------------------------------------------------------------------ 内部

    /** 列存在则用列名，不存在用 NULL（过渡态容错） */
    private String sel(String column) {
        return columns().contains(column) ? column : "null";
    }

    /** 实际列集合（缓存） */
    Set<String> columns() {
        Set<String> cached = actualColumns;
        if (cached != null) {
            return cached;
        }
        synchronized (this) {
            if (actualColumns == null) {
                Set<String> found = new TreeSet<>();
                try (Connection c = registryDataSource.getConnection();
                     PreparedStatement ps = c.prepareStatement(
                             "select column_name from information_schema.columns "
                                     + "where lower(table_name) = 'actor_project'");
                     ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        found.add(rs.getString(1).toLowerCase(java.util.Locale.ROOT));
                    }
                } catch (SQLException e) {
                    throw new IllegalStateException("读取目录库 actor_project 列信息失败", e);
                }
                actualColumns = Collections.unmodifiableSet(new LinkedHashSet<>(found));
                if (!actualColumns.containsAll(INDEX_COLUMNS)) {
                    // INFO 不是 WARN：M2 过渡态（旧单体库尚未迁移）下这是"正常"，每次启动都会出现；
                    // 真正的错误在写路径上以 503 大声报出，不靠这条日志提醒。
                    log.info("[world-store] 目录库 actor_project 缺少存档索引列（迁移未完成，列表只读可用）：缺 {}",
                            missingColumns());
                }
            }
            return actualColumns;
        }
    }

    /** 缺失的索引列 */
    public Set<String> missingColumns() {
        Set<String> missing = new TreeSet<>(INDEX_COLUMNS);
        missing.removeAll(columns());
        return missing;
    }

    /** 写前校验：缺列直接 503，而不是让 SQL 抛一个看不懂的"Column not found" */
    private void requireWritableColumns() {
        Set<String> missing = missingColumns();
        if (missing.isEmpty()) {
            return;
        }
        throw new BizException(503, "目录库缺少存档索引列 " + missing
                + "（V101 迁移未执行）。请先完成世界数据分存档迁移再打开存档列表。");
    }

    /** 执行一条更新（统一异常包装） */
    private int execute(String sql, Object... args) {
        requireWritableColumns();
        try (Connection c = registryDataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            return ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("更新存档索引失败：" + sql, e);
        }
    }

    /** 行 → 索引行 */
    private static WorldIndexRow map(ResultSet rs) throws SQLException {
        return new WorldIndexRow(
                rs.getLong("id"),
                rs.getString("project_uid"),
                rs.getString("name"),
                rs.getString("summary"),
                rs.getString("cover_url"),
                WorldStatus.of(rs.getString("ws")),
                nullableLong(rs, "gs"),
                nullableLong(rs, "cc"),
                nullableLong(rs, "sb"),
                nullableTime(rs, "lo"),
                nullableTime(rs, "lc"),
                nullableTime(rs, "created_at"),
                nullableTime(rs, "updated_at"),
                rs.getString("sv"),
                rs.getString("ist"),
                nullableInt(rs, "idn"),
                nullableInt(rs, "itt"),
                rs.getString("gn"),
                rs.getString("tn"));
    }

    private static Long nullableLong(ResultSet rs, String column) throws SQLException {
        long v = rs.getLong(column);
        return rs.wasNull() ? null : v;
    }

    private static Integer nullableInt(ResultSet rs, String column) throws SQLException {
        int v = rs.getInt(column);
        return rs.wasNull() ? null : v;
    }

    private static LocalDateTime nullableTime(ResultSet rs, String column) throws SQLException {
        Timestamp ts = rs.getTimestamp(column);
        return ts == null ? null : ts.toLocalDateTime();
    }
}
