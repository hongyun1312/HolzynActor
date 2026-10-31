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

package com.holzyn.actor.domain.dev.controller;

import com.holzyn.actor.common.BizException;
import com.holzyn.actor.common.PageResult;
import com.holzyn.actor.common.R;
import com.holzyn.actor.domain.dev.service.DevDbBrowseService;
import com.holzyn.actor.domain.dev.service.DevNpcHologramService;
import com.holzyn.actor.domain.dev.service.DevParamCatalogService;
import com.holzyn.actor.domain.dev.service.DevParamCatalogService.ParamRow;
import com.holzyn.actor.domain.dev.service.DevParamService;
import com.holzyn.actor.domain.dev.service.DevSystemService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 开发者模式控制台 API（/api/dev）。
 *
 * <p><b>与 /api/debug 的分工</b>：`/api/debug/**` 是既有调试只读面（AI Trace / 日志 SSE / DB 浏览 /
 * 用量 / 漂移），继续复用、不搬迁；本控制器只补「开发者模式新增的三件事」：</p>
 * <ol>
 *   <li><b>参数调优</b>：全量参数目录的读与写（含生效方式标注、危险键二次确认、审计留痕）；</li>
 *   <li><b>NPC 全息</b>：把一个角色的位置/感知/需求/PAD/目标/决策/记忆/对话一次聚合；</li>
 *   <li><b>总览与自检</b>：引擎/内容/成本/参数四组指标 + 前置条件自检 + 系统环境。</li>
 * </ol>
 *
 * <p><b>为什么参数键用 query 而不是 path</b>：参数键是点分路径（如
 * {@code holzyn.actor.npc-state.decay.hunger-per-hour}），放进 path 变量在不同网关/容器的
 * 路径规范化下容易被截断或重编码；query 参数没有这个问题，也让「键里出现点/连字符」不再是坑。</p>
 *
 * <p><b>安全约定</b>：本地单用户、无鉴权（与 /api/debug 一致）；但所有写操作都要求
 * 明确的参数与危险键 confirm 标记，并全部落审计表。</p>
 *
 * <p>所属模块：domain/dev（开发者模式控制台）</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/dev")
@RequiredArgsConstructor
public class DevController {

    private final DevParamService paramService;
    private final DevParamCatalogService catalog;
    private final DevNpcHologramService hologramService;
    private final DevSystemService systemService;
    private final DevDbBrowseService dbBrowseService;

    // ==================== 总览 / 自检 / 环境 ====================

    /**
     * 开发者总览（引擎/内容/成本/参数 + 最近异常）。
     *
     * @param projectId 当前世界 ID（可空=未挂载）
     *
     * @return 总览
     */
    @GetMapping("/overview")
    public R<Map<String, Object>> overview(@RequestParam(required = false) Long projectId) {
        return R.ok(systemService.overview(projectId));
    }

    /**
     * 前置条件自检（DB/迁移/世界挂载/AI 供应商/决策模型/目录可写…）。
     *
     * @param projectId 当前世界 ID（可空）
     *
     * @return 自检结果
     */
    @GetMapping("/health")
    public R<Map<String, Object>> health(@RequestParam(required = false) Long projectId) {
        return R.ok(systemService.health(projectId));
    }

    /**
     * 系统环境（版本/JDK/OS/内存/目录/端口/迁移版本/覆盖文件）。
     *
     * @return 环境信息
     */
    @GetMapping("/system")
    public R<Map<String, Object>> system() {
        return R.ok(systemService.system());
    }

    // ==================== 参数目录 ====================

    /**
     * 参数目录（可按功能域/关键词过滤）。
     *
     * @param domain 功能域（可空=全部）
     *
     * @param keyword 关键词（键/标签，可空）
     *
     * @return {rows, stats}
     */
    @GetMapping("/params")
    public R<Map<String, Object>> params(@RequestParam(required = false) String domain,
                                         @RequestParam(required = false) String keyword) {
        List<ParamRow> rows = paramService.rows(domain, keyword);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("rows", rows);
        out.put("stats", paramService.stats());
        return R.ok(out);
    }

    /**
     * 参数目录统计（总数/已改/待重启/按域分布）。
     *
     * @return 统计
     */
    @GetMapping("/params/stats")
    public R<Map<String, Object>> paramStats() {
        return R.ok(paramService.stats());
    }

    /**
     * 已改参数（覆盖行）。
     *
     * @return 参数行列表
     */
    @GetMapping("/params/overridden")
    public R<List<ParamRow>> overridden() {
        return R.ok(paramService.overriddenRows());
    }

    /**
     * 写入单个参数。
     *
     * @param body 载荷 {key, value, confirm?, page?, operator?}
     *
     * @return {key, value, effect}
     */
    @PutMapping("/params")
    public R<Map<String, Object>> saveParam(@RequestBody Map<String, Object> body) {
        String key = str(body.get("key"));
        if (key == null || key.isBlank()) {
            throw new BizException(400, "请求体需包含 key");
        }
        Object rawValue = body.get("value");
        if (rawValue == null) {
            throw new BizException(400, "请求体需包含 value");
        }
        boolean confirm = Boolean.parseBoolean(String.valueOf(body.getOrDefault("confirm", "false")));
        String applied = paramService.save(key, String.valueOf(rawValue), confirm,
                str(body.get("operator")), str(body.get("page")));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("key", key);
        out.put("value", applied);
        var meta = catalog.meta(key);
        out.put("effect", meta == null ? null : meta.effect());
        return R.ok(out);
    }

    /**
     * 单键恢复默认。
     *
     * @param key 参数键
     *
     * @param page 来源页面（可空）
     *
     * @param operator 操作人（可空）
     *
     * @return {key, value}
     */
    @DeleteMapping("/params")
    public R<Map<String, Object>> resetParam(@RequestParam String key,
                                             @RequestParam(required = false) String page,
                                             @RequestParam(required = false) String operator) {
        String applied = paramService.reset(key, operator, page);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("key", key);
        out.put("value", applied);
        return R.ok(out);
    }

    /**
     * 全部恢复默认（危险操作，需 confirm=true）。
     *
     * @param body 载荷 {confirm, page?, operator?}
     *
     * @return {reset}
     */
    @PostMapping("/params/reset-all")
    public R<Map<String, Object>> resetAll(@RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> b = body == null ? Map.of() : body;
        boolean confirm = Boolean.parseBoolean(String.valueOf(b.getOrDefault("confirm", "false")));
        int n = paramService.resetAll(str(b.get("operator")), str(b.get("page")), confirm);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("reset", n);
        return R.ok(out);
    }

    // ==================== 审计 ====================

    /**
     * 审计分页（时间倒序）。
     *
     * @param page 页码（从 1 起）
     *
     * @param size 每页条数（上限 200）
     *
     * @param action 动作过滤（可空）
     *
     * @param target 目标片段过滤（可空）
     *
     * @return 分页结果
     */
    @GetMapping("/audit")
    public R<PageResult<Map<String, Object>>> audit(@RequestParam(defaultValue = "1") int page,
                                                    @RequestParam(defaultValue = "50") int size,
                                                    @RequestParam(required = false) String action,
                                                    @RequestParam(required = false) String target) {
        return R.ok(paramService.audits(page, size, action, target));
    }

    /**
     * 追加一条审计（供「保存并重启后端」「导出数据」等前端动作留痕）。
     *
     * @param body 载荷 {action, target, oldValue?, newValue?, effect?, page?, operator?, note?}
     *
     * @return {ok}
     */
    @PostMapping("/audit")
    public R<Map<String, Object>> appendAudit(@RequestBody Map<String, Object> body) {
        String action = str(body.get("action"));
        if (action == null || action.isBlank()) {
            throw new BizException(400, "请求体需包含 action");
        }
        paramService.audit(action, str(body.getOrDefault("target", "-")),
                str(body.get("oldValue")), str(body.get("newValue")), str(body.get("effect")),
                str(body.get("operator")), str(body.get("page")), str(body.get("note")));
        return R.ok(Map.of("ok", true));
    }

    // ==================== NPC 全息 ====================

    /**
     * 角色列表（特殊型 + 普通型，含状态摘要）。
     *
     * @param projectId 世界 ID
     *
     * @return 角色行列表
     */
    @GetMapping("/worlds/{projectId}/npcs")
    public R<List<Map<String, Object>>> npcs(@PathVariable Long projectId) {
        return R.ok(hologramService.list(projectId));
    }

    /**
     * NPC 全息详情（位置/感知/需求/PAD/目标/决策/记忆/对话/刺激）。
     *
     * @param projectId 世界 ID
     *
     * @param ref 角色引用（npc:{id} / 历史前缀 / 纯数字）
     *
     * @param gameSecond 游戏时刻（秒，可空=取引擎时钟）
     *
     * @return 全息聚合
     */
    @GetMapping("/worlds/{projectId}/npc-hologram")
    public R<Map<String, Object>> hologram(@PathVariable Long projectId,
                                           @RequestParam String ref,
                                           @RequestParam(required = false) Long gameSecond) {
        return R.ok(hologramService.hologram(projectId, ref, gameSecond));
    }

    // ==================== 数据域（系统库 / 存档库分开浏览） ====================

    /**
     * 数据页可选范围：系统库（目录库）+ 每个存档（独立世界库）。
     *
     * @return {system:{...}, archives:[...]}
     */
    @GetMapping("/db/scopes")
    public R<Map<String, Object>> dbScopes() {
        return R.ok(dbBrowseService.scopes());
    }

    /**
     * 指定范围的表清单（行数/列数）。
     *
     * @param scope 范围（system=系统库 / world=存档库，默认 world）
     *
     * @param worldId 存档 ID（scope=world 时必填）
     *
     * @return 表信息列表
     */
    @GetMapping("/db/tables")
    public R<List<Map<String, Object>>> dbTables(@RequestParam(required = false) String scope,
                                                 @RequestParam(required = false) Long worldId) {
        return R.ok(dbBrowseService.tables(scope, worldId));
    }

    /**
     * 指定范围的表只读分页浏览。
     *
     * @param table 表名（白名单）
     *
     * @param scope 范围
     *
     * @param worldId 存档 ID
     *
     * @param page 页码
     *
     * @param size 每页条数（上限 200）
     *
     * @param keyword 关键字（可空）
     *
     * @return 分页结果
     */
    @GetMapping("/db/{table}")
    public R<Map<String, Object>> dbBrowse(@PathVariable String table,
                                           @RequestParam(required = false) String scope,
                                           @RequestParam(required = false) Long worldId,
                                           @RequestParam(defaultValue = "1") int page,
                                           @RequestParam(defaultValue = "50") int size,
                                           @RequestParam(required = false) String keyword) {
        return R.ok(dbBrowseService.browse(scope, worldId, table, page, size, keyword));
    }

    /**
     * 某存档的目录结构（存档形态：世界库 + 清单 + 资源目录）。
     *
     * @param worldId 存档 ID
     *
     * @return 目录结构
     */
    @GetMapping("/db/files")
    public R<Map<String, Object>> dbFiles(@RequestParam Long worldId) {
        return R.ok(dbBrowseService.files(worldId));
    }

    /**
     * 空串归一化（null/空白 → null）。
     *
     * @param v 值
     *
     * @return 归一化后的字符串
     */
    private static String str(Object v) {
        if (v == null) {
            return null;
        }
        String s = String.valueOf(v);
        return s.isBlank() ? null : s;
    }
}
