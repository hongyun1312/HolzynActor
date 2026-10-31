package com.holzyn.actor.config;

import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 世界库基线之后发生的表改名历史（旧名 → 新名），供 schema 治理测试归一表名。
 *
 * <p><b>为什么需要它</b>：{@code V200__world_baseline.sql} 已经装进了用户库，改它等于改
 * checksum，Flyway 校验会直接让应用启动失败；所以旧名只能由后续迁移（{@code V203}）收口。
 * 于是同一批表存在两种口径：分类清单 {@code db/worldsplit-tables.json} 与 ORM 实体用**新名**，
 * 基线 SQL 文本里仍是**旧名**。凡是"拿清单去基线里找 DDL"的断言都必须先归一，
 * 否则会误报「世界库基线里没有建表语句」。</p>
 *
 * <p>映射从迁移脚本本身解析，不在这里硬编码——再发生一次改名时，本类无需改动。</p>
 *
 * <p>所属模块：test/config（系统底层 schema 治理）</p>
 */
final class WorldSchemaRenames {

    /** 唯一收口改名的世界库迁移（若将来新增改名迁移，把文件加进下面的数组即可） */
    private static final String[] RENAME_MIGRATIONS = {
            "db/migration/world/V203__decision_model_rename.sql"
    };

    private static final Pattern TABLE_RENAME = Pattern.compile(
            "alter\\s+table\\s+(?:if\\s+exists\\s+)?([a-z0-9_]+)\\s+rename\\s+to\\s+([a-z0-9_]+)",
            Pattern.CASE_INSENSITIVE);

    private static volatile Map<String, String> cache;

    private WorldSchemaRenames() {
    }

    /** 旧名 → 新名 */
    static Map<String, String> tableRenames() {
        Map<String, String> cached = cache;
        if (cached != null) {
            return cached;
        }
        Map<String, String> out = new LinkedHashMap<>();
        for (String path : RENAME_MIGRATIONS) {
            Matcher m = TABLE_RENAME.matcher(text(path));
            while (m.find()) {
                out.put(m.group(1).toLowerCase(Locale.ROOT), m.group(2).toLowerCase(Locale.ROOT));
            }
        }
        cache = out;
        return out;
    }

    /**
     * 新名 → 基线里写的旧名（未改名的表原样返回）。
     *
     * <p>用于"按清单新名去 {@code V200} 文本里取建表语句"的场景。</p>
     */
    static String baselineNameOf(String table) {
        String want = table.toLowerCase(Locale.ROOT);
        for (Map.Entry<String, String> e : tableRenames().entrySet()) {
            if (e.getValue().equals(want)) {
                return e.getKey();
            }
        }
        return want;
    }

    private static String text(String classpathRelative) {
        ClassPathResource res = new ClassPathResource(classpathRelative);
        if (!res.exists()) {
            throw new IllegalStateException("资源缺失：" + classpathRelative);
        }
        try (var in = res.getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("读取迁移脚本失败：" + classpathRelative, e);
        }
    }
}
