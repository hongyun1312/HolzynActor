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
import com.holzyn.actor.common.PageResult;
import com.holzyn.actor.domain.dev.service.DevParamCatalogService.ParamMeta;
import com.holzyn.actor.domain.dev.service.DevParamCatalogService.ParamRow;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.convert.ConversionService;
import org.springframework.core.convert.support.DefaultConversionService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * 开发者模式参数写入 / 重置 / 审计服务。
 *
 * <p><b>单一写入口</b>：本类是 {@code actor_dev_param} 与 {@code actor_dev_audit} 的唯一写入者
 * （AGENTS §8.1.4），也是覆盖文件 {@code data/dev-param-overrides.properties} 的唯一维护者。
 * 其他模块只读这两个表。</p>
 *
 * <p><b>三类键的写入语义</b>：</p>
 * <ul>
 *   <li><b>bean 键</b>（{@code immediate}）：先按字段类型转换 → 反射直改 bean 字段（当场生效）→ 落库 + 落文件；</li>
 *   <li><b>env 键</b>（{@code restart}）：只落库 + 落文件，重启后端后生效（面板显式标注）；</li>
 *   <li><b>只读键</b>（{@code system} / 结构化）：直接 400，并在错误消息里说明为什么不能改。</li>
 * </ul>
 *
 * <p><b>为什么危险键要 confirm</b>：世界时钟、自动推进、数据目录、世界库参数这些键一旦误改，
 * 表现是「世界不动了 / 数据找不到」，而开发者当下往往正在看别的面板。接口层强制
 * {@code confirm=true} 才能写，前端据此弹二次确认——把「手滑」挡在数据之外。</p>
 *
 * <p>本类不含任何 AI 调用，故可安全使用 {@code @Transactional}（AGENTS §8.1.1 的约束只针对 AI 调用）。</p>
 *
 * <p>所属模块：domain/dev（开发者模式控制台）</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DevParamService {

    /** 转换器（String → 目标类型；非法值抛异常，由本类转成 400） */
    private static final ConversionService CONVERSION = DefaultConversionService.getSharedInstance();

    private final DevParamCatalogService catalog;
    /** 持久化入口（目录库；参数覆盖 + 审计的唯一写入者） */
    private final DevStoreDao store;

    /** 应用启动时刻（用于判断覆盖文件是否晚于本次启动 → 是否「有待重启生效的改动」） */
    private final Instant startedAt = Instant.now();

    // ==================== 读 ====================

    /**
     * 参数目录全量行（可按域/关键词过滤）。
     *
     * @param domain 功能域（可空=全部）
     *
     * @param keyword 关键词（匹配键/标签，可空）
     *
     * @return 参数行列表（键升序）
     */
    @Transactional(readOnly = true)
    public List<ParamRow> rows(String domain, String keyword) {
        Map<String, String> overrides = overrideMap();
        Map<String, String> defaults = catalog.ymlDefaults();
        List<ParamRow> out = new ArrayList<>();
        String kw = keyword == null ? "" : keyword.trim().toLowerCase(Locale.ROOT);
        for (ParamMeta meta : catalog.metas().values()) {
            if (domain != null && !domain.isBlank() && !domain.equals(meta.domain())) {
                continue;
            }
            if (!kw.isEmpty()) {
                String hay = (meta.key() + " " + meta.label() + " " + meta.group()).toLowerCase(Locale.ROOT);
                if (!hay.contains(kw)) {
                    continue;
                }
            }
            out.add(catalog.rowOf(meta, overrides.get(meta.key()), defaults.get(meta.key())));
        }
        return out;
    }

    /**
     * 参数目录统计（域 → 键数/已改数），供面板分组头部显示。
     *
     * @return 统计 Map
     */
    @Transactional(readOnly = true)
    public Map<String, Object> stats() {
        Map<String, int[]> byDomain = new TreeMap<>();
        for (ParamMeta meta : catalog.metas().values()) {
            int[] cell = byDomain.computeIfAbsent(meta.domain(), k -> new int[2]);
            cell[0]++;
            if (store.exists(meta.key())) {
                cell[1]++;
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        List<Map<String, Object>> domains = new ArrayList<>();
        for (Map.Entry<String, int[]> e : byDomain.entrySet()) {
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("domain", e.getKey());
            d.put("total", e.getValue()[0]);
            d.put("overridden", e.getValue()[1]);
            domains.add(d);
        }
        out.put("total", catalog.metas().size());
        out.put("overridden", store.count());
        out.put("domains", domains);
        out.put("pendingRestart", pendingRestartCount());
        out.put("overrideFile", DevOverrideFile.path().toString());
        return out;
    }

    /**
     * 已改参数（覆盖行 + 元数据合并；面板「已改」Tab 的数据源）。
     *
     * @return 参数行列表
     */
    @Transactional(readOnly = true)
    public List<ParamRow> overriddenRows() {
        Map<String, String> defaults = catalog.ymlDefaults();
        List<ParamRow> out = new ArrayList<>();
        for (Map<String, Object> p : store.overrideRows()) {
            String key = String.valueOf(p.get("key"));
            String value = String.valueOf(p.get("value"));
            String effect = String.valueOf(p.get("effect"));
            ParamMeta meta = catalog.meta(key);
            if (meta == null) {
                // 目录里已不存在的键（配置项被删/改名）：仍然回显，让开发者能把脏覆盖清掉
                out.add(new ParamRow(key, "unknown", "stale", key, "string",
                        value, defaults.getOrDefault(key, ""), true,
                        effect, false, true, "stale"));
                continue;
            }
            out.add(catalog.rowOf(meta, value, defaults.get(meta.key())));
        }
        return out;
    }

    /**
     * 是否存在「已改但还没重启」的覆盖（面板红点 + 重启提示）。
     *
     * @return true=存在待重启改动
     */
    public boolean hasPendingRestart() {
        java.nio.file.Path file = DevOverrideFile.path();
        try {
            if (!java.nio.file.Files.exists(file)) {
                return false;
            }
            return java.nio.file.Files.getLastModifiedTime(file).toInstant().isAfter(startedAt);
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * 待重启生效的覆盖条数（按 effect=restart 统计）。
     *
     * @return 条数
     */
    public int pendingRestartCount() {
        return store.countByEffect(DevParamCatalogService.EFFECT_RESTART);
    }

    // ==================== 写 ====================

    /**
     * 写入单个参数。
     *
     * @param key 参数键
     *
     * @param value 新值（字符串；bean 键会按字段类型转换）
     *
     * @param confirm 危险键二次确认标记
     *
     * @param operator 操作人（本地账户名，可空）
     *
     * @param page 来源页面（前端路由，可空）
     *
     * @return 写入后的生效值
     */
    @Transactional
    public String save(String key, String value, boolean confirm, String operator, String page) {
        ParamMeta meta = catalog.meta(key);
        if (meta == null) {
            throw new BizException(400, "未知参数：" + key + "（不在参数目录中，请刷新页面后重试）");
        }
        if (!meta.writable()) {
            if (DevParamCatalogService.SOURCE_SYSTEM.equals(meta.source())) {
                throw new BizException(400, "该键属于 Spring 基础设施（" + key + "），改错会导致应用连不上或连错库，"
                        + "请在 application.yml 中修改后重启");
            }
            throw new BizException(400, "结构化参数（List/Map/数组）不支持在面板中直接编辑：" + key
                    + "，请在 application.yml 中修改后重启");
        }
        if (value == null) {
            throw new BizException(400, "参数值不能为空：" + key);
        }
        if (meta.danger() && !confirm) {
            throw new BizException(400, "危险参数需要二次确认（confirm=true）：" + key);
        }
        String oldValue = catalog.currentValue(key);
        String applied = value.trim();

        if (DevParamCatalogService.SOURCE_BEAN.equals(meta.source())) {
            applied = applyToBean(meta, applied);
        } else {
            applied = applied.trim();
        }

        store.upsert(key, applied, meta.effect(), operator == null || operator.isBlank() ? "local" : operator);
        rewriteFile();

        audit("PARAM_SET", key, oldValue, applied, meta.effect(), operator, page,
                meta.danger() ? "危险参数（已二次确认）" : null);
        log.info("[开发者模式] 写入参数 {} = {}（原值 {}，生效方式 {}）", key, applied, oldValue, meta.effect());
        return applied;
    }

    /**
     * 单键恢复默认（删覆盖 + bean 回写 yml 默认值）。
     *
     * @param key 参数键
     *
     * @param operator 操作人
     *
     * @param page 来源页面
     *
     * @return 恢复后的值
     */
    @Transactional
    public String reset(String key, String operator, String page) {
        ParamMeta meta = catalog.meta(key);
        if (meta == null) {
            throw new BizException(400, "未知参数：" + key);
        }
        String oldValue = catalog.currentValue(key);
        store.delete(key);
        String def = catalog.ymlDefaults().get(key);
        String applied = oldValue;
        if (def != null && !def.isBlank() && DevParamCatalogService.SOURCE_BEAN.equals(meta.source()) && meta.writable()) {
            try {
                applied = applyToBean(meta, def);
            } catch (BizException e) {
                // yml 默认值在当前上下文里无法反解（如占位符未展开）：保留运行值，只清覆盖
                log.warn("[开发者模式] 恢复默认值失败（保留运行值）：{} - {}", key, e.getMessage());
            }
        }
        rewriteFile();
        audit("PARAM_RESET", key, oldValue, applied, meta.effect(), operator, page, "恢复 yml 默认值");
        log.info("[开发者模式] 参数恢复默认：{}（{} → {}）", key, oldValue, applied);
        return applied;
    }

    /**
     * 全部恢复默认（清空覆盖表 + 删除覆盖文件 + bean 回写默认值）。
     *
     * @param operator 操作人
     *
     * @param page 来源页面
     *
     * @param confirm 必须为 true
     *
     * @return 清除的覆盖条数
     */
    @Transactional
    public int resetAll(String operator, String page, boolean confirm) {
        if (!confirm) {
            throw new BizException(400, "全部恢复默认属于危险操作，需要 confirm=true");
        }
        List<Map<String, Object>> all = store.overrideRows();
        Map<String, String> defaults = catalog.ymlDefaults();
        for (Map<String, Object> p : all) {
            String key = String.valueOf(p.get("key"));
            ParamMeta meta = catalog.meta(key);
            if (meta == null || !meta.writable() || !DevParamCatalogService.SOURCE_BEAN.equals(meta.source())) {
                continue;
            }
            String def = defaults.get(key);
            if (def == null || def.isBlank()) {
                continue;
            }
            try {
                applyToBean(meta, def);
            } catch (BizException ignored) {
                // 单个键反解失败不影响其余键
            }
        }
        store.deleteAll();
        rewriteFile();
        audit("PARAM_RESET_ALL", "all", String.valueOf(all.size()), "0", DevParamCatalogService.EFFECT_RESTART,
                operator, page, "一键恢复默认（清除全部参数覆盖）");
        log.info("[开发者模式] 全部参数恢复默认：清除 {} 条覆盖", all.size());
        return all.size();
    }

    /**
     * 追加一条审计（供重启/导出等其他动作复用）。
     *
     * @param action 动作
     *
     * @param target 目标
     *
     * @param oldValue 旧值
     *
     * @param newValue 新值
     *
     * @param effect 生效方式
     *
     * @param operator 操作人
     *
     * @param page 来源页面
     *
     * @param note 备注
     */
    @Transactional
    public void audit(String action, String target, String oldValue, String newValue, String effect,
                      String operator, String page, String note) {
        store.insertAudit(action, target, oldValue, newValue, effect,
                operator == null || operator.isBlank() ? "local" : operator, page, note);
    }

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
    @Transactional(readOnly = true)
    public PageResult<Map<String, Object>> audits(int page, int size, String action, String target) {
        return store.auditPage(page, size, action, target);
    }

    // ==================== 内部 ====================

    /**
     * 覆盖值映射（键 → 值）。
     *
     * @return 覆盖映射
     */
    private Map<String, String> overrideMap() {
        return store.overrides();
    }

    /**
     * 把字符串写入 bean 字段（类型转换失败 → 400）。
     *
     * @param meta 元数据
     *
     * @param raw 原始字符串
     *
     * @return 写入后回读的实际值
     */
    private String applyToBean(ParamMeta meta, String raw) {
        if (meta.holder() == null || meta.field() == null) {
            return raw;
        }
        Object converted;
        try {
            converted = CONVERSION.convert(raw, meta.fieldType());
        } catch (Exception e) {
            throw new BizException(400, "参数值格式非法：" + meta.key() + " = " + raw
                    + "（应为 " + meta.type() + "）");
        }
        if (converted == null && meta.fieldType().isPrimitive()) {
            throw new BizException(400, "参数值格式非法：" + meta.key() + " = " + raw
                    + "（应为 " + meta.type() + "）");
        }
        try {
            meta.field().setAccessible(true);
            meta.field().set(meta.holder(), converted);
        } catch (Exception e) {
            throw new BizException(400, "参数写入失败（字段不可写）：" + meta.key());
        }
        return meta.readValue();
    }

    /**
     * 按当前覆盖表重写覆盖文件（DB → 文件单向同步）。
     */
    private void rewriteFile() {
        Map<String, String> map = new TreeMap<>(store.overrides());
        try {
            DevOverrideFile.writeAll(map);
        } catch (IOException e) {
            log.warn("[开发者模式] 覆盖文件写入失败（本次改动仅在内存与 DB 生效，重启后会丢失）：{}", e.getMessage());
        }
    }

    /**
     * 审计值截断（列宽 4000，避免超长 Prompt/JSON 把审计写入撑失败）。
     *
     * @param v 值
     *
     * @return 截断后的值
     */
    private static String truncate(String v) {
        if (v == null) {
            return null;
        }
        return v.length() <= 3900 ? v : v.substring(0, 3900) + "…(截断)";
    }
}
