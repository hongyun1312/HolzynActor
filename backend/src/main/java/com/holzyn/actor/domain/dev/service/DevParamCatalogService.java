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

import tools.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.ApplicationContext;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.stereotype.Service;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 开发者模式「全量可调参数目录」。
 *
 * <p><b>为什么需要目录，而不是把参数一项项写死</b>：引擎参数分布在 30 个
 * {@code @ConfigurationProperties} bean 与两份 yml（{@code application.yml} /
 * {@code application-actor.yml}）里，手工登记一遍必然与代码脱节——新增一个配置项就漏一个，
 * 面板上「改不了」的参数会被误当成「没有这个参数」。所以这里做的是**枚举**：</p>
 *
 * <ol>
 *   <li><b>Bean 键</b>（{@code effect=immediate}）：扫描上下文中全部 {@code @ConfigurationProperties}，
 *       按 prefix + 字段路径递归拍平。写入时反射直改 bean 字段，<b>当场生效</b>。</li>
 *   <li><b>环境键</b>（{@code effect=restart}）：遍历 Environment 里所有可枚举 PropertySource，
 *       取 {@code holzyn.*} 前缀中未被第 1 类覆盖的键（典型是只被 {@code @Value} 读取的开关与阈值）。
 *       写入只落覆盖文件，重启后端后由 {@link DevOverrideEnvironmentPostProcessor} 生效。</li>
 *   <li><b>基础设施键</b>（只读）：{@code server.*} / {@code spring.datasource.*} / {@code spring.jpa.*} 等，
 *       展示当前值并明确说明「改应用配置文件后重启」，<b>不接受写入</b>——这些键改错会让应用连不上/连错库。</li>
 * </ol>
 *
 * <p><b>默认值口径</b>：取两份 yml 的声明值（启动时解析一次并缓存），用于 UI 展示
 * 「默认 vs 当前」以及单键「恢复默认」。yml 里写的是 {@code ${ENV:default}} 形式时会解出 default
 * 部分——因为 UI 要回答的是「没被改过应该是多少」。</p>
 *
 * <p><b>结构化字段</b>（List/Map/数组）：可读不可写（面板标注「结构化参数」），
 * 避免把 JSON 编辑器的转义/类型问题带进调参主链路。</p>
 *
 * <p>所属模块：domain/dev（开发者模式控制台）</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DevParamCatalogService {

    /** 立即生效（bean 反射直改） */
    public static final String EFFECT_IMMEDIATE = "immediate";

    /** 需重启后端（只写覆盖文件） */
    public static final String EFFECT_RESTART = "restart";

    /** 参数来源：@ConfigurationProperties bean */
    public static final String SOURCE_BEAN = "bean";

    /** 参数来源：Environment（yml / 环境变量） */
    public static final String SOURCE_ENV = "env";

    /** 参数来源：Spring 基础设施（只读） */
    public static final String SOURCE_SYSTEM = "system";

    /** 递归拍平嵌套对象的最大深度（防病态配置类把面板撑爆） */
    private static final int MAX_DEPTH = 6;

    /** 只读的 Spring 基础设施前缀（改错的代价是「起不来」或「连错库」，面板不给写） */
    private static final List<String> READONLY_PREFIXES = List.of(
            "server.", "spring.datasource.", "spring.jpa.", "spring.flyway.", "spring.servlet.",
            "spring.jackson.", "spring.config.", "spring.lifecycle.", "logging.");

    /** 危险键片段（改之前必须二次确认：会直接改变世界推进或数据落点） */
    private static final List<String> DANGER_FRAGMENTS = List.of(
            "data-dir", "root-dir", "db-url", "world-store.", "log.file", "auto-resume",
            "allow-multi-mount", "compact-on-close", "migrate-legacy", "test-enabled",
            "ai.enabled", "decision.enabled", "mapgen.ai-enabled", "gateway-concurrency.max-concurrent");

    /** yml 占位符正则（含嵌套时靠循环剥离最内层） */
    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([^{}]*)}");

    private final ApplicationContext context;
    private final ConfigurableEnvironment environment;
    private final ObjectMapper objectMapper;

    /** 参数元数据缓存（键 → 元数据）；首次访问构建，之后不变（配置类在运行期不增加） */
    private volatile Map<String, ParamMeta> metas;

    /** yml 声明默认值（键 → 值）；启动时解析一次 */
    private volatile Map<String, String> ymlDefaults;

    /**
     * 参数元数据。
     *
     * @param key 完整点分键
     *
     * @param domain 归属功能域（世界/角色/AI/记忆/系统…，供面板分组）
     *
     * @param group 组名（嵌套对象名或域内二级分组）
     *
     * @param label 中文标签（词表映射，未命中保留英文原词）
     *
     * @param type 值类型（int/double/boolean/string/enum/json…）
     *
     * @param effect 生效方式（immediate / restart）
     *
     * @param danger 是否危险键（写入需 confirm）
     *
     * @param writable 是否允许写入
     *
     * @param source 来源（bean / env / system）
     *
     * @param holder 持有该字段的对象实例（写入时反射直改它）
     *
     * @param field 字段
     */
    public record ParamMeta(String key, String domain, String group, String label, String type,
                            String effect, boolean danger, boolean writable, String source,
                            Object holder, Field field) {

        /**
         * 读取当前值（结构化类型转 JSON 文本；读取失败返回空串）。
         *
         * @return 当前值字符串
         */
        public String readValue() {
            try {
                Object raw = field.get(holder);
                if (raw == null) {
                    return "";
                }
                if (raw instanceof Collection<?> || raw instanceof Map<?, ?>) {
                    return String.valueOf(raw);
                }
                return String.valueOf(raw);
            } catch (Exception e) {
                return "";
            }
        }

        /**
         * 字段声明类型。
         *
         * @return 类型
         */
        public Class<?> fieldType() {
            return field.getType();
        }
    }

    /**
     * 全量参数元数据（键升序）。
     *
     * @return 元数据映射
     */
    public Map<String, ParamMeta> metas() {
        Map<String, ParamMeta> local = metas;
        if (local == null) {
            synchronized (this) {
                if (metas == null) {
                    metas = build();
                }
                local = metas;
            }
        }
        return local;
    }

    /**
     * 取单个参数元数据。
     *
     * @param key 参数键
     *
     * @return 元数据（未注册为空）
     */
    public ParamMeta meta(String key) {
        return key == null ? null : metas().get(key);
    }

    /**
     * yml 声明默认值（键 → 值）。
     *
     * @return 默认值映射
     */
    public Map<String, String> ymlDefaults() {
        Map<String, String> local = ymlDefaults;
        if (local == null) {
            synchronized (this) {
                if (ymlDefaults == null) {
                    ymlDefaults = loadYmlDefaults();
                }
                local = ymlDefaults;
            }
        }
        return local;
    }

    /**
     * 读取参数当前运行值（不反射字段，直接问 Environment——对 env 键与 system 键都成立）。
     *
     * @param key 参数键
     *
     * @return 当前值（空串表示未设置）
     */
    public String currentValue(String key) {
        ParamMeta meta = meta(key);
        if (meta != null && SOURCE_BEAN.equals(meta.source()) && meta.holder() != null && meta.field() != null) {
            return meta.readValue();
        }
        String v = environment.getProperty(key);
        return v == null ? "" : v;
    }

    // ==================== 元数据构建 ====================

    /**
     * 构建全量元数据（bean 拍平 + 环境键 + 基础设施只读键）。
     *
     * @return 键升序的元数据映射
     */
    private Map<String, ParamMeta> build() {
        Map<String, ParamMeta> out = new TreeMap<>();
        int beanCount = collectBeans(out);
        int envCount = collectEnvironmentKeys(out);
        log.info("[开发者模式] 参数目录构建完成：bean 键 {} 个，环境键 {} 个，合计 {} 个", beanCount, envCount, out.size());
        return Collections.unmodifiableMap(out);
    }

    /**
     * 扫描 {@code @ConfigurationProperties} bean 并递归拍平其字段。
     *
     * @param out 输出（键 → 元数据）
     *
     * @return 新增的 bean 键数量
     */
    private int collectBeans(Map<String, ParamMeta> out) {
        int before = out.size();
        Map<String, Object> beans;
        try {
            beans = context.getBeansWithAnnotation(ConfigurationProperties.class);
        } catch (Exception e) {
            log.warn("[开发者模式] 扫描 @ConfigurationProperties 失败：{}", e.getMessage());
            return 0;
        }
        for (Map.Entry<String, Object> entry : beans.entrySet()) {
            Object bean = entry.getValue();
            if (bean == null) {
                continue;
            }
            Class<?> cls = bean.getClass();
            ConfigurationProperties ann = AnnotatedElementUtils.findMergedAnnotation(cls, ConfigurationProperties.class);
            if (ann == null) {
                continue;
            }
            String prefix = ann.prefix() == null || ann.prefix().isBlank() ? ann.value() : ann.prefix();
            if (prefix == null || prefix.isBlank()) {
                continue;
            }
            try {
                walk(out, prefix, bean, cls, "", 0, new IdentityHashMap<>());
            } catch (Exception e) {
                log.warn("[开发者模式] 拍平配置类失败（跳过）：{} - {}", cls.getSimpleName(), e.getMessage());
            }
        }
        return out.size() - before;
    }

    /**
     * 递归拍平一个配置对象。
     *
     * @param out 输出
     *
     * @param prefix 键前缀
     *
     * @param holder 当前对象实例
     *
     * @param type 当前对象类型
     *
     * @param group 当前组名（嵌套对象名）
     *
     * @param depth 递归深度
     *
     * @param path 递归路径上的对象（去环）
     */
    private void walk(Map<String, ParamMeta> out, String prefix, Object holder, Class<?> type,
                      String group, int depth, IdentityHashMap<Object, Boolean> path) {
        if (depth > MAX_DEPTH || holder == null || path.containsKey(holder)) {
            return;
        }
        path.put(holder, Boolean.TRUE);
        try {
            for (Field field : allFields(type)) {
                if (Modifier.isStatic(field.getModifiers()) || field.isSynthetic() || Modifier.isTransient(field.getModifiers())) {
                    continue;
                }
                if (field.getName().startsWith("$$")) {
                    continue;
                }
                field.setAccessible(true);
                String key = prefix + "." + kebab(field.getName());
                Object value;
                try {
                    value = field.get(holder);
                } catch (Exception e) {
                    continue;
                }
                Class<?> ft = field.getType();
                if (isNestedConfig(ft)) {
                    walk(out, key, value, ft, kebab(field.getName()), depth + 1, path);
                    continue;
                }
                boolean structured = Collection.class.isAssignableFrom(ft) || Map.class.isAssignableFrom(ft) || ft.isArray();
                String typeName = structured ? "json" : simpleTypeName(ft);
                String label = labelOf(key);
                String domain = domainOf(key);
                if (structured) {
                    // 结构化参数：可读不可写（面板标注，避免 JSON 编辑把类型写坏）
                    put(out, new ParamMeta(key, domain, group, label, typeName, EFFECT_RESTART,
                            isDangerous(key), false, SOURCE_BEAN, holder, field));
                } else {
                    put(out, new ParamMeta(key, domain, group, label, typeName, EFFECT_IMMEDIATE,
                            isDangerous(key), true, SOURCE_BEAN, holder, field));
                }
            }
        } finally {
            path.remove(holder);
        }
    }

    /**
     * 遍历 Environment 中可枚举的属性名，补齐未被 bean 覆盖的 {@code holzyn.*} 键。
     *
     * @param out 输出
     *
     * @return 新增的键数量
     */
    private int collectEnvironmentKeys(Map<String, ParamMeta> out) {
        int before = out.size();
        Set<String> names = new LinkedHashSet<>();
        for (PropertySource<?> ps : environment.getPropertySources()) {
            if (ps instanceof EnumerablePropertySource<?> eps) {
                try {
                    names.addAll(List.of(eps.getPropertyNames()));
                } catch (Exception e) {
                    log.debug("[开发者模式] 跳过不可枚举属性源：{}", ps.getName());
                }
            }
        }
        for (String name : names) {
            if (name == null || out.containsKey(name)) {
                continue;
            }
            if (name.startsWith("holzyn.")) {
                String value = environment.getProperty(name);
                put(out, new ParamMeta(name, domainOf(name), "env", labelOf(name), guessType(value),
                        EFFECT_RESTART, isDangerous(name), true, SOURCE_ENV, null, null));
            } else if (READONLY_PREFIXES.stream().anyMatch(name::startsWith)) {
                String value = environment.getProperty(name);
                put(out, new ParamMeta(name, "system", "infrastructure", labelOf(name), guessType(value),
                        EFFECT_RESTART, false, false, SOURCE_SYSTEM, null, null));
            }
        }
        return out.size() - before;
    }

    /**
     * 同名键只保留先注册者（bean 优先于 env：bean 键可立即生效，信息更完整）。
     *
     * @param out 输出
     *
     * @param meta 元数据
     */
    private void put(Map<String, ParamMeta> out, ParamMeta meta) {
        out.putIfAbsent(meta.key(), meta);
    }

    /**
     * 取类及其父类的全部字段（不含 Object）。
     *
     * @param type 类型
     *
     * @return 字段列表
     */
    private static List<Field> allFields(Class<?> type) {
        List<Field> out = new ArrayList<>();
        Class<?> cur = type;
        while (cur != null && cur != Object.class && cur.getName().startsWith("com.holzyn")) {
            Collections.addAll(out, cur.getDeclaredFields());
            cur = cur.getSuperclass();
        }
        return out;
    }

    /**
     * 是否是「需要继续下钻的配置对象」（本项目包内的非简单类型）。
     *
     * @param type 字段类型
     *
     * @return true=嵌套配置对象
     */
    private static boolean isNestedConfig(Class<?> type) {
        if (type.isPrimitive() || type.isEnum() || type.isArray()) {
            return false;
        }
        if (Collection.class.isAssignableFrom(type) || Map.class.isAssignableFrom(type)) {
            return false;
        }
        if (isSimpleType(type)) {
            return false;
        }
        return type.getName().startsWith("com.holzyn");
    }

    /**
     * 是否为「单值类型」（不需要下钻，能字符串往返）。
     *
     * @param type 类型
     *
     * @return true=单值类型
     */
    private static boolean isSimpleType(Class<?> type) {
        return type.isPrimitive()
                || type.isEnum()
                || CharSequence.class.isAssignableFrom(type)
                || Number.class.isAssignableFrom(type)
                || Boolean.class == type
                || Character.class == type
                || Duration.class == type
                || Instant.class == type
                || LocalDate.class == type
                || LocalTime.class == type
                || LocalDateTime.class == type
                || OffsetDateTime.class == type
                || Path.class.isAssignableFrom(type)
                || Class.class == type;
    }

    /**
     * 类型名（供面板显示与前端控件选择）。
     *
     * @param type 类型
     *
     * @return 类型名
     */
    private static String simpleTypeName(Class<?> type) {
        if (type.isEnum()) {
            return "enum";
        }
        if (type == int.class || type == Integer.class) {
            return "int";
        }
        if (type == long.class || type == Long.class) {
            return "long";
        }
        if (type == double.class || type == Double.class || type == float.class || type == Float.class) {
            return "double";
        }
        if (type == boolean.class || type == Boolean.class) {
            return "boolean";
        }
        if (durationLike(type)) {
            return "duration";
        }
        return CharSequence.class.isAssignableFrom(type) ? "string" : type.getSimpleName();
    }

    /**
     * 是否时长类（面板用「300s / 5m」这类文本编辑）。
     *
     * @param type 类型
     *
     * @return true=时长类
     */
    private static boolean durationLike(Class<?> type) {
        return Duration.class == type || Instant.class == type || LocalTime.class == type
                || LocalDate.class == type || LocalDateTime.class == type || OffsetDateTime.class == type;
    }

    /**
     * 按当前值猜类型（环境键没有声明类型可用）。
     *
     * @param value 值
     *
     * @return 类型名
     */
    private static String guessType(String value) {
        if (value == null || value.isBlank()) {
            return "string";
        }
        String v = value.trim();
        if ("true".equalsIgnoreCase(v) || "false".equalsIgnoreCase(v)) {
            return "boolean";
        }
        if (v.matches("-?\\d+")) {
            return "int";
        }
        if (v.matches("-?\\d+\\.\\d+([eE][-+]?\\d+)?")) {
            return "double";
        }
        return "string";
    }

    /**
     * 字段/键 → 短横线命名（与 Spring 松散绑定一致）。
     *
     * @param camel camelCase 名称
     *
     * @return kebab-case
     */
    private static String kebab(String camel) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < camel.length(); i++) {
            char c = camel.charAt(i);
            if (Character.isUpperCase(c)) {
                if (i > 0) {
                    sb.append('-');
                }
                sb.append(Character.toLowerCase(c));
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    // ==================== 中文标签与归属 ====================

    /** 词表：kebab 片段 → 中文（未命中保留原词，宁可中英混排也不猜错语义） */
    private static final Map<String, String> WORDS = Map.ofEntries(
            Map.entry("decay", "衰减"), Map.entry("hunger", "饥饿"), Map.entry("thirst", "口渴"),
            Map.entry("energy", "精力"), Map.entry("bladder", "膀胱"), Map.entry("hygiene", "清洁"),
            Map.entry("social", "社交"), Map.entry("fun", "趣味"), Map.entry("comfort", "舒适"),
            Map.entry("health", "健康"), Map.entry("mood", "心情"), Map.entry("sleep", "睡眠"),
            Map.entry("restore", "恢复"), Map.entry("taper", "放缓"), Map.entry("threshold", "阈值"),
            Map.entry("enabled", "启用"), Map.entry("disabled", "禁用"), Map.entry("max", "上限"),
            Map.entry("min", "下限"), Map.entry("size", "大小"), Map.entry("count", "数量"),
            Map.entry("timeout", "超时"), Map.entry("retries", "重试次数"), Map.entry("retry", "重试"),
            Map.entry("temperature", "采样温度"), Map.entry("tokens", "Token 数"), Map.entry("token", "Token"),
            Map.entry("ratio", "比例"), Map.entry("scale", "缩放"), Map.entry("interval", "间隔"),
            Map.entry("window", "窗口"), Map.entry("model", "模型"), Map.entry("url", "地址"),
            Map.entry("port", "端口"), Map.entry("path", "路径"), Map.entry("dir", "目录"),
            Map.entry("file", "文件"), Map.entry("days", "天数"), Map.entry("day", "天"),
            Map.entry("hours", "小时"), Map.entry("hour", "小时"), Map.entry("seconds", "秒"),
            Map.entry("ms", "毫秒"), Map.entry("budget", "预算"), Map.entry("per", "每"),
            Map.entry("tick", "世界步"), Map.entry("rate", "速率"), Map.entry("speed", "速度"),
            Map.entry("level", "档位"), Map.entry("tier", "档位"), Map.entry("mode", "模式"),
            Map.entry("top", "Top"), Map.entry("k", "K"), Map.entry("cache", "缓存"),
            Map.entry("score", "分数"), Map.entry("limit", "上限"), Map.entry("depth", "深度"),
            Map.entry("weight", "权重"), Map.entry("alpha", "Alpha"), Map.entry("beta", "Beta"),
            Map.entry("factor", "系数"), Map.entry("duration", "时长"), Map.entry("retention", "保留"),
            Map.entry("maintenance", "维护"), Map.entry("concurrency", "并发"), Map.entry("pool", "池"),
            Map.entry("archive", "归档"), Map.entry("compact", "压缩"), Map.entry("mirror", "镜像"),
            Map.entry("refresh", "刷新"), Map.entry("open", "打开"), Map.entry("close", "关闭"),
            Map.entry("mount", "挂载"), Map.entry("legacy", "旧版"), Map.entry("migrate", "迁移"),
            Map.entry("root", "根"), Map.entry("data", "数据"), Map.entry("log", "日志"),
            Map.entry("clock", "时钟"), Map.entry("resume", "恢复推进"), Map.entry("auto", "自动"),
            Map.entry("guard", "护栏"), Map.entry("duplicate", "重复"), Map.entry("dice", "Dice"),
            Map.entry("routing", "场景路由"), Map.entry("scene", "场景"), Map.entry("default", "默认"),
            Map.entry("max-tokens", "Token 上限"), Map.entry("headroom", "预留"), Map.entry("rag", "RAG"),
            Map.entry("thinking", "思考"), Map.entry("effort", "强度"), Map.entry("semantic", "语义"),
            Map.entry("allow", "允许"), Map.entry("query", "查询"), Map.entry("length", "长度"),
            Map.entry("time", "时间"), Map.entry("map", "地图"), Map.entry("gen", "生成"),
            Map.entry("ai", "AI"), Map.entry("npc", "NPC"), Map.entry("npc-state", "NPC 状态"),
            Map.entry("world", "世界"), Map.entry("actor", "引擎"), Map.entry("growth", "成长"),
            Map.entry("persona", "人格"), Map.entry("memory", "记忆"), Map.entry("macro", "宏观"),
            Map.entry("meso", "中观"), Map.entry("micro", "微观"), Map.entry("climate", "气候"),
            Map.entry("environment", "环境"), Map.entry("emergence", "涌现"), Map.entry("evolution", "演化"),
            Map.entry("dramatize", "戏剧化"), Map.entry("frame", "世界帧"), Map.entry("info", "信息"),
            Map.entry("unity", "Unity"), Map.entry("assistant", "AI 助手"), Map.entry("perception", "感知"),
            Map.entry("behavior", "行为"), Map.entry("diversity", "多样性"), Map.entry("realtime", "实时"),
            Map.entry("decision", "决策"), Map.entry("action", "行动"), Map.entry("agent", "智能体"),
            Map.entry("knowledge", "知识"), Map.entry("vector", "向量"), Map.entry("store", "库"),
            Map.entry("gateway", "网关"), Map.entry("ab", "A/B"),
            Map.entry("probe", "探测"), Map.entry("override", "覆盖"), Map.entry("batch", "批量"),
            Map.entry("max-concurrent", "最大并发"), Map.entry("wait", "等待"), Map.entry("chunk", "分块"),
            Map.entry("embedding", "向量化"), Map.entry("dimension", "维度"), Map.entry("top-k", "Top-K"));

    /**
     * 键 → 中文标签（末两段 + 词表；未命中保留英文原词）。
     *
     * @param key 参数键
     *
     * @return 标签
     */
    static String labelOf(String key) {
        String[] seg = key.split("\\.");
        StringBuilder sb = new StringBuilder();
        int start = Math.max(0, seg.length - 3);
        for (int i = start; i < seg.length; i++) {
            String s = seg[i];
            String zh = WORDS.get(s);
            if (zh == null) {
                // 片段切分：max-tokens 这类整体未命中时按 '-' 拆开再翻
                StringBuilder part = new StringBuilder();
                for (String p : s.split("-")) {
                    String pz = WORDS.get(p);
                    if (pz != null) {
                        part.append(pz);
                    } else if (!p.isEmpty()) {
                        if (part.length() > 0) {
                            part.append(' ');
                        }
                        part.append(p);
                    }
                }
                zh = part.toString();
            }
            if (zh == null || zh.isBlank()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(" · ");
            }
            sb.append(zh);
        }
        return sb.length() == 0 ? key : sb.toString();
    }

    /**
     * 键 → 功能域（与开发者页 9 个一级 Tab 对齐）。
     *
     * @param key 参数键
     *
     * @return 域 key
     */
    static String domainOf(String key) {
        String k = key.startsWith("holzyn.actor.") ? key.substring("holzyn.actor.".length()) : key;
        String head = k.contains(".") ? k.substring(0, k.indexOf('.')) : k;
        return switch (head) {
            case "npc-state", "npc", "physio", "emotion", "growth" -> "npc";
            case "memory", "reflection", "archive" -> "memory";
            case "ai", "assistant", "prompt", "rag", "knowledge", "routine" -> "ai";
            case "agent", "perception", "action", "realtime-decision", "decision", "behavior-diversity" -> "agent";
            case "world", "macro", "meso", "micro", "climate", "environment", "emergence", "evolution",
                 "evolution-dramatize", "world-frame", "world-info", "world-map", "mapgen", "worldgen",
                 "behavior" -> "world";
            case "unity" -> "tools";
            default -> "system";
        };
    }

    /**
     * 是否危险键（写入需二次确认）。
     *
     * @param key 参数键
     *
     * @return true=危险
     */
    static boolean isDangerous(String key) {
        for (String frag : DANGER_FRAGMENTS) {
            if (key.contains(frag)) {
                return true;
            }
        }
        return false;
    }

    // ==================== yml 默认值 ====================

    /**
     * 解析两份 yml 的声明值（键 → 值，占位符解出 default 部分）。
     *
     * @return 默认值映射
     */
    private Map<String, String> loadYmlDefaults() {
        Map<String, String> out = new TreeMap<>();
        for (String resource : List.of("application.yml", "application-actor.yml")) {
            try (InputStream in = getClass().getClassLoader().getResourceAsStream(resource)) {
                if (in == null) {
                    continue;
                }
                Object loaded = new Yaml().load(in);
                if (loaded instanceof Map<?, ?> map) {
                    flattenYml("", map, out);
                }
            } catch (Exception e) {
                log.warn("[开发者模式] 解析 {} 失败（默认值将以运行值为准）：{}", resource, e.getMessage());
            }
        }
        return out;
    }

    /**
     * 拍平 yml 树为「点分键 → 字符串值」。
     *
     * @param prefix 前缀
     *
     * @param node 当前节点
     *
     * @param out 输出
     */
    private void flattenYml(String prefix, Map<?, ?> node, Map<String, String> out) {
        for (Map.Entry<?, ?> e : node.entrySet()) {
            String key = prefix.isEmpty() ? String.valueOf(e.getKey()) : prefix + "." + e.getKey();
            Object v = e.getValue();
            if (v instanceof Map<?, ?> sub) {
                flattenYml(key, sub, out);
            } else if (v != null) {
                out.put(key, resolvePlaceholder(String.valueOf(v)));
            }
        }
    }

    /**
     * 解出 {@code ${ENV:default}} 的 default（嵌套占位符循环剥离最内层；无 default 返回空）。
     *
     * @param raw 原始值
     *
     * @return 解析后的值
     */
    static String resolvePlaceholder(String raw) {
        if (raw == null) {
            return "";
        }
        String cur = raw;
        for (int i = 0; i < 6; i++) {
            Matcher m = PLACEHOLDER.matcher(cur);
            if (!m.find()) {
                break;
            }
            String body = m.group(1);
            int colon = body.indexOf(':');
            String replacement = colon >= 0 ? body.substring(colon + 1) : "";
            cur = cur.substring(0, m.start()) + replacement + cur.substring(m.end());
        }
        return cur;
    }

    /**
     * 参数行（面板数据源）。
     *
     * @param key 键
     *
     * @param domain 功能域
     *
     * @param group 组
     *
     * @param label 中文标签
     *
     * @param type 类型
     *
     * @param value 当前生效值
     *
     * @param defaultValue yml 默认值
     *
     * @param overridden 是否已被覆盖
     *
     * @param effect 生效方式
     *
     * @param danger 是否危险
     *
     * @param writable 是否可写
     *
     * @param source 来源
     */
    public record ParamRow(String key, String domain, String group, String label, String type,
                           String value, String defaultValue, boolean overridden,
                           String effect, boolean danger, boolean writable, String source) {
    }

    /**
     * 结构化值 → 可读 JSON 文本（供面板只读展示）。
     *
     * @param value 值
     *
     * @return 文本
     */
    public String toJsonText(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return String.valueOf(value);
        }
    }

    /**
     * 参数行序列化辅助：把元数据 + 覆盖值合成一行（DevParamService 调用）。
     *
     * @param meta 元数据
     *
     * @param override 覆盖值（无覆盖为空）
     *
     * @param defaultValue yml 默认值
     *
     * @return 行
     */
    public ParamRow rowOf(ParamMeta meta, String override, String defaultValue) {
        String value = override == null ? currentValue(meta.key()) : override;
        return new ParamRow(meta.key(), meta.domain(), meta.group(), meta.label(), meta.type(),
                value, defaultValue == null ? "" : defaultValue, override != null,
                meta.effect(), meta.danger(), meta.writable(), meta.source());
    }

}
