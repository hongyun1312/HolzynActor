package com.holzyn.actor.config;

import com.holzyn.actor.domain.worldstore.WorldStoreProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 世界存档存储配置校验（V1.1.2-beta.1 M1）。
 *
 * <p>为什么必须有这个测试：本仓库没有任何 {@code @SpringBootTest}（AGENTS.md §8.4-3），
 * yml 键名拼错、占位符写坏、默认值写反都不会被既有套件发现，只在真实启动/真实搬存档时炸。
 * 沿 {@code WorldConfigYamlTest} 先例：只加载 yml，不启 Spring 上下文。</p>
 *
 * <p>锁定内容：</p>
 * <ol>
 *   <li>{@code holzyn.actor.world-store.*} 键全部存在且默认值符合设计
 *       （root-dir/archive-dir 留空派生、registry-file=holzyn-registry、pool-size=4、
 *       compact-on-close=true、migrate-legacy=true）；</li>
 *   <li><b>allow-multi-mount 必须为 false</b>——单活动世界是"路由上下文丢失也安全"的前提，
 *       这条一旦被改成 true，分档方案的安全性论证整体失效；</li>
 *   <li><b>Flyway 自动装配必须关闭</b>、目录库位置必须走
 *       {@link com.holzyn.actor.domain.worldstore.RegistrySchema#LOCATION}（M3 改程序化，
 *       改回自动装配会让目录库路径与 {@code WorldPaths} 分叉）；</li>
 *   <li>该配置段能被 {@link WorldStoreProperties} 按生产方式绑定（键名与字段对得上）。</li>
 * </ol>
 *
 * <p>所属模块：test/config（配置校验）</p>
 */
class WorldStoreYamlTest {

    /** 合并加载 application.yml + application-actor.yml（与真实启动的配置合并口径一致） */
    private static StandardEnvironment env() throws IOException {
        List<PropertySource<?>> sources = new YamlPropertySourceLoader()
                .load("application", new ClassPathResource("application.yml"));
        assertTrue(!sources.isEmpty(), "application.yml 加载结果为空");
        List<PropertySource<?>> actorSources = new YamlPropertySourceLoader()
                .load("application-actor", new ClassPathResource("application-actor.yml"));
        assertTrue(!actorSources.isEmpty(), "application-actor.yml 加载结果为空");
        StandardEnvironment environment = new StandardEnvironment();
        sources.forEach(environment.getPropertySources()::addLast);
        actorSources.forEach(environment.getPropertySources()::addLast);
        return environment;
    }

    private static String strAt(StandardEnvironment env, String key) {
        String raw = env.getProperty(key);
        assertNotNull(raw, "缺失或键名拼错：" + key);
        return raw.trim();
    }

    @Test
    @DisplayName("world-store 键位齐全：默认 root-dir 派生、registry-file=holzyn-registry、pool-size=4")
    void worldStoreKeysResolveToDesignDefaults() throws IOException {
        StandardEnvironment env = env();

        // root-dir 留空 → 由 WorldPaths 派生为 <data-dir>/worlds（写死绝对路径会让存档不可搬移）
        String rootDir = env.getProperty("holzyn.actor.world-store.root-dir");
        assertTrue(rootDir == null || rootDir.isBlank(),
                "root-dir 默认必须留空（派生 <data-dir>/worlds），实际=" + rootDir);

        assertEquals("holzyn-registry", strAt(env, "holzyn.actor.world-store.registry-file"));
        assertEquals("4", strAt(env, "holzyn.actor.world-store.pool-size"));
        assertEquals("true", strAt(env, "holzyn.actor.world-store.compact-on-close"));
        assertEquals("true", strAt(env, "holzyn.actor.world-store.mirror-refresh-on-open"));
    }

    @Test
    @DisplayName("M3 键位：migrate-legacy 默认 true、archive-dir 默认留空派生")
    void legacyMigrationKeysArePresent() throws IOException {
        StandardEnvironment env = env();
        assertEquals("true", strAt(env, "holzyn.actor.world-store.migrate-legacy"),
                "migrate-legacy 默认必须为 true：升级安装的用户第一次启动就该自动拆库，不该要求手工步骤");
        String archiveDir = env.getProperty("holzyn.actor.world-store.archive-dir");
        assertTrue(archiveDir == null || archiveDir.isBlank(),
                "archive-dir 默认必须留空（派生 <data-dir>/archive），实际=" + archiveDir);
    }

    @Test
    @DisplayName("安全前提：allow-multi-mount 必须为 false（单活动世界模型）")
    void allowMultiMountMustStayFalse() throws IOException {
        StandardEnvironment env = env();
        assertEquals("false", strAt(env, "holzyn.actor.world-store.allow-multi-mount"),
                "allow-multi-mount 必须为 false：它是「路由上下文丢失也写不错库」的唯一前提");
    }

    @Test
    @DisplayName("M3：Flyway 自动装配关闭，目录库改程序化迁移（db/migration/global）")
    void flywayIsProgrammaticOnGlobalLocation() throws IOException {
        StandardEnvironment env = env();
        assertEquals("false", strAt(env, "spring.flyway.enabled"),
                "spring.flyway.enabled 必须为 false：自动装配只认 spring.datasource.url，"
                        + "与 WorldPaths 单一派生目录库位置相冲突（会造成世界与索引分处两个目录）");

        // 程序化位置必须指向目录库基线，且基线文件真实存在（改了字符串没改目录 → 启动即失败）
        assertEquals("classpath:db/migration/global", com.holzyn.actor.domain.worldstore.RegistrySchema.LOCATION);
        assertTrue(new ClassPathResource("db/migration/global/V100__global_baseline.sql").exists(),
                "目录库基线缺失：classpath:db/migration/global/V100__global_baseline.sql");

        // spring.datasource.url 降级为兜底，但仍必须指向目录库（指向旧库就等于把旧库当目录库）
        String url = strAt(env, "spring.datasource.url");
        assertTrue(url.contains("holzyn-registry"),
                "spring.datasource.url 作为兜底也必须指向 holzyn-registry，实际=" + url);
        assertFalse(url.contains("holzyn-actor"), "兜底 URL 不允许再指向旧单库，实际=" + url);
    }

    @Test
    @DisplayName("绑定：holzyn.actor.world-store 能被 WorldStoreProperties 按生产方式绑定")
    void propertiesBindFromYaml() throws IOException {
        StandardEnvironment env = env();
        WorldStoreProperties props = Binder.get(env)
                .bind("holzyn.actor.world-store", WorldStoreProperties.class)
                .orElseThrow(() -> new AssertionError("world-store 段无法绑定到 WorldStoreProperties"));

        assertEquals("holzyn-registry", props.getRegistryFile());
        assertEquals(4, props.getPoolSize());
        assertTrue(props.isCompactOnClose());
        assertTrue(props.isMirrorRefreshOnOpen());
        assertFalse(props.isAllowMultiMount(), "allow-multi-mount 绑定结果必须为 false");
        assertTrue(props.isMigrateLegacy(), "migrate-legacy 绑定结果必须为 true");
        assertTrue(props.getArchiveDir() == null || props.getArchiveDir().isBlank(),
                "archive-dir 绑定后应为空串（派生），实际=" + props.getArchiveDir());
        assertTrue(props.getRootDir() == null || props.getRootDir().isBlank(),
                "root-dir 绑定后应为空串（派生），实际=" + props.getRootDir());
    }
}
