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
import org.hibernate.resource.jdbc.spi.StatementInspector;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 全局镜像写守卫：世界上下文里禁止写 12 张全局表（V1.1.2-beta.1）。
 *
 * <p><b>为什么需要它</b>：世界库里的 12 张全局表是<b>只读镜像</b>。一旦有代码在世界上下文
 * （即已挂载世界、默认路由指向世界库）里写 {@code actor_setting}/{@code actor_model_provider}
 * 这类表，写入就落在世界库的镜像上：本机看起来"设置保存成功"，实际目录库没变——
 * 重开世界时镜像被全量重刷，<b>用户刚改的配置原地蒸发</b>。这类静默丢失是本方案最危险的
 * 失败模式（比报错严重得多），所以必须是硬守卫而不是测试提醒。</p>
 *
 * <p><b>为什么用 Hibernate {@code StatementInspector} 而不是文档原计划的 Integrator 事件监听器</b>：
 * 两者都能覆盖全部 JPA 写入，但 Integrator 需要注册 3 组 {@code PreInsert/PreUpdate/PreDelete}
 * 监听器并依赖 Hibernate 7 的事件 API（版本升级即有变动风险）；{@code StatementInspector}
 * 只有一个方法，且能顺带看到 JPA 批量更新走出的原生 SQL。代价是只知道表名不知道实体名，
 * 故错误信息按表名报（用户看到"actor_setting"同样能定位）。</p>
 *
 * <p><b>为什么不需要"镜像同步豁免标志"</b>：{@link WorldMirrorSyncService} 走裸 JDBC，
 * 根本不经过 Hibernate，因此不会被本守卫拦到。少一个 ThreadLocal 就少一个"忘了设/忘了清"
 * 的泄漏点。</p>
 *
 * <p><b>性能</b>：世界未挂载时（当前默认状态）只做一次 volatile 读就返回；只有"已挂载且非全局"
 * 才解析 SQL，且按 SQL 文本缓存判定结果（同一条语句只解析一次）。</p>
 *
 * <p>所属模块：domain/worldstore（世界存档存储）</p>
 */
@Slf4j
@Component
public class GlobalMirrorWriteGuard implements StatementInspector {

    /** 写语句 → 被写表名（只认第一张表；多表写在这个仓库里不存在） */
    private static final Pattern[] WRITE_PATTERNS = {
            Pattern.compile("^insert\\s+into\\s+([\\w.`\"\\[\\]]+)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("^update\\s+([\\w.`\"\\[\\]]+)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("^delete\\s+from\\s+([\\w.`\"\\[\\]]+)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("^merge\\s+into\\s+([\\w.`\"\\[\\]]+)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("^replace\\s+into\\s+([\\w.`\"\\[\\]]+)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("^truncate\\s+table\\s+([\\w.`\"\\[\\]]+)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("^alter\\s+table\\s+([\\w.`\"\\[\\]]+)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("^drop\\s+table\\s+(?:if\\s+exists\\s+)?([\\w.`\"\\[\\]]+)", Pattern.CASE_INSENSITIVE)
    };

    /** 缓存上限：超过即整体清空（Hibernate 对字面量内联的语句文本会有大量变体） */
    private static final int CACHE_LIMIT = 4096;

    private static final String NOT_MIRROR = "";

    /** 当前挂载世界（不依赖会话服务，避免 Bean 环） */
    private final MountedWorld mountedWorld;

    /** SQL 文本 → 被写的镜像表名（空串 = 不是镜像表写） */
    private final Map<String, String> cache = new ConcurrentHashMap<>();

    public GlobalMirrorWriteGuard(MountedWorld mountedWorld) {
        this.mountedWorld = mountedWorld;
    }

    /**
     * Hibernate 每条语句执行前的回调。
     *
     * @param sql 即将执行的 SQL
     *
     * @return 原样返回 SQL（本守卫只做拦截，不改写语句）
     *
     * @throws BizException 世界上下文里写全局镜像表
     */
    @Override
    public String inspect(String sql) {
        if (sql == null || sql.isEmpty()) {
            return sql;
        }
        // 快路径：显式全局上下文（目录库）或未挂载世界 → 一律放行
        if (WorldContext.isGlobal()) {
            return sql;
        }
        String uid = mountedWorld.uid();
        if (uid == null) {
            return sql;
        }
        String table = writtenMirrorTable(sql);
        if (table == null) {
            return sql;
        }
        String message = "全局配置只能在目录库写入，当前处于世界上下文（uid=" + uid + "）：表 " + table
                + " 在世界库里是只读镜像，写在这里会在重开世界时被覆盖丢失。";
        log.error("[world-store] 拦截全局表写入 uid={} table={} sql={}", uid, table, sql);
        throw new BizException(409, message);
    }

    /**
     * 判定一条 SQL 是否在写镜像表（带缓存）。
     *
     * @param sql SQL 文本
     *
     * @return 镜像表名；不是镜像表写返回 {@code null}
     */
    String writtenMirrorTable(String sql) {
        String cached = cache.get(sql);
        if (cached != null) {
            return cached.isEmpty() ? null : cached;
        }
        String table = parseWrittenTable(sql);
        String value = (table != null && MirrorTables.isMirror(table)) ? table : NOT_MIRROR;
        if (cache.size() >= CACHE_LIMIT) {
            // 不追求精确淘汰：清空后重新解析的成本远低于无界增长的内存风险
            cache.clear();
        }
        cache.put(sql, value);
        return value.isEmpty() ? null : value;
    }

    /**
     * 解析 SQL 里被写的第一张表（纯函数，供测试直接调用）。
     *
     * <p>先剥掉前导块注释：Hibernate 生成的语句常以 {@code /* load ... *&#47;} 开头。</p>
     *
     * @param sql SQL 文本
     *
     * @return 表名（小写、去掉 schema 前缀与引号）；非写语句返回 {@code null}
     */
    static String parseWrittenTable(String sql) {
        if (sql == null) {
            return null;
        }
        String s = sql.strip();
        while (s.startsWith("/*")) {
            int end = s.indexOf("*/");
            if (end < 0) {
                return null;
            }
            s = s.substring(end + 2).strip();
        }
        if (s.isEmpty()) {
            return null;
        }
        for (Pattern p : WRITE_PATTERNS) {
            Matcher m = p.matcher(s);
            if (m.find()) {
                return normalize(m.group(1));
            }
        }
        return null;
    }

    /** 归一化标识符：去引号/反引号/方括号、去 schema 前缀、转小写 */
    private static String normalize(String raw) {
        String s = raw.replace("\"", "").replace("`", "").replace("[", "").replace("]", "");
        int dot = s.lastIndexOf('.');
        if (dot >= 0) {
            s = s.substring(dot + 1);
        }
        return s.toLowerCase(Locale.ROOT);
    }

    /** 已缓存的语句条数（诊断/测试用） */
    int cachedStatements() {
        return cache.size();
    }
}
