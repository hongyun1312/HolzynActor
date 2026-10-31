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
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * 关闭世界前从世界库读取摘要（V1.1.2-beta.1）。
 *
 * <p>为什么必须在关闭<b>之前</b>读：关闭会把连接池销毁（并执行 {@code SHUTDOWN COMPACT}），
 * 之后世界库文件不可用；而目录库的卡片需要"游戏内时间 + 角色数"才能在没有挂载世界时
 * 显示得像存档列表。因此顺序固定：先取摘要 → 再关库 → 最后回写索引。</p>
 *
 * <p>为什么直连世界池而不是走路由数据源：此刻世界确实挂载着，但这一读是"针对某个具体世界"的
 * 显式动作；走路由会把正确性寄托在"当前线程没被标成 GLOBAL"上，一旦调用点被
 * {@code @GlobalContext} 包住就会静默读到目录库的 {@code actor_world_clock}（空表）——
 * 摘要变成 0，用户看到"世界时间归零"。直连池没有这个隐患。</p>
 *
 * <p>失败策略：读不到就返回 {@code null} 字段（索引回写用 {@code coalesce} 保留上次的值），
 * 绝不让"摘要读失败"阻止世界关闭——那会让用户卡在一个关不掉的世界里。</p>
 *
 * <p>所属模块：domain/worldstore（世界存档存储）</p>
 */
@Slf4j
@Component
public class WorldSummaryReader {

    /**
     * 世界摘要快照。
     *
     * @param gameSecond     世界时间（{@code actor_world_clock.last_game_second}）
     * @param characterCount 未删除角色数（{@code actor_profile}）
     */
    public record Snapshot(Long gameSecond, Long characterCount) {
    }

    /**
     * 跨世界用量摘要（世界关闭时回写到目录库 {@code actor_project} 的三个摘要列，M4）。
     *
     * <p>为什么要在关闭时算：{@code actor_usage_log} 是世界表，目录库没有它；而首页用量 Tab
     * 必须在"不打开任何世界库"时给出总量——唯一办法就是世界关闭时把总量抄一份进目录库。</p>
     *
     * @param tokenIn  输入 token 合计（{@code actor_usage_log.token_in}）
     * @param tokenOut 输出 token 合计（{@code actor_usage_log.token_out}）
     * @param aiCalls  调用次数（行数）
     */
    public record Usage(Long tokenIn, Long tokenOut, Long aiCalls) {
    }

    /**
     * 读取一个世界的摘要。
     *
     * @param world 世界库数据源（不能为空）
     * @param projectId 该世界的 {@code actor_project.id}
     *
     * @return 快照（字段可为 null）
     */
    public Snapshot read(DataSource world, long projectId) {
        if (world == null) {
            return new Snapshot(null, null);
        }
        return new Snapshot(readGameSecond(world, projectId), readCharacterCount(world, projectId));
    }

    /**
     * 读取一个世界的跨世界用量摘要（世界库仍可访问时必须调用，即关闭<b>之前</b>）。
     *
     * @param world 世界库数据源（不能为空）
     * @param projectId 该世界的 {@code actor_project.id}
     *
     * @return 用量摘要（读失败时字段为 null，回写用 {@code coalesce} 保留旧值）
     */
    public Usage readUsage(DataSource world, long projectId) {
        if (world == null) {
            return new Usage(null, null, null);
        }
        String sql = "select coalesce(sum(token_in), 0), coalesce(sum(token_out), 0), count(*) "
                + "from actor_usage_log where project_id = ?";
        try (Connection c = world.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, projectId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return new Usage(rs.getLong(1), rs.getLong(2), rs.getLong(3));
                }
            }
        } catch (SQLException e) {
            log.warn("[world-store] 读取世界用量摘要失败 projectId={}（保留上次快照）：{}", projectId, e.toString());
        }
        return new Usage(null, null, null);
    }

    /** 世界时间：唯一权威是 actor_world_clock.last_game_second */
    private Long readGameSecond(DataSource world, long projectId) {
        String sql = "select last_game_second from actor_world_clock where project_id = ?";
        try (Connection c = world.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, projectId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    long v = rs.getLong(1);
                    return rs.wasNull() ? null : v;
                }
            }
        } catch (SQLException e) {
            log.warn("[world-store] 读取世界时间失败 projectId={}（保留上次快照）：{}", projectId, e.toString());
        }
        return null;
    }

    /** 角色数：actor_profile 里未删除的行 */
    private Long readCharacterCount(DataSource world, long projectId) {
        String sql = "select count(*) from actor_profile where project_id = ? and (deleted is null or deleted = 0)";
        try (Connection c = world.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, projectId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : null;
            }
        } catch (SQLException e) {
            log.warn("[world-store] 读取角色数失败 projectId={}（保留上次快照）：{}", projectId, e.toString());
        }
        return null;
    }
}
