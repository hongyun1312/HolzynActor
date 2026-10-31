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
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.Transaction;
import org.hibernate.boot.registry.StandardServiceRegistry;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.hibernate.cfg.Configuration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 写守卫在<b>真实 Hibernate</b> 下的接线验证（V1.1.2-beta.1 M2）。
 *
 * <p>为什么非要起一个 SessionFactory：{@link GlobalMirrorWriteGuard} 是一个
 * {@code StatementInspector}，它能否生效完全取决于"Spring 把一个<b>实例</b>塞进
 * {@code hibernate.session_factory.statement_inspector}"这个约定是否被 Hibernate 接受。
 * 如果 Hibernate 只接受类名（自己 {@code new} 一个），那么 Spring 注入的
 * {@link MountedWorld} 就丢了——守卫永远不会触发，而且<b>不会报任何错</b>。
 * 纯单元测试（{@link GlobalMirrorWriteGuardTest}）测的只是判定逻辑，测不出这个坑。</p>
 *
 * <p>因此本测试按 Spring Boot 的真实路径装配：{@code applySetting(key, 实例)}（Spring 通过
 * {@code PersistenceUnitInfo.getProperties()} 的 {@link java.util.Map Map&lt;String,Object&gt;}
 * 走同一条路），然后真的 persist 两条实体，看守卫是否拦在世界库镜像表上。</p>
 *
 * <p>所属模块：test/domain/worldstore</p>
 */
class GlobalMirrorWriteGuardHibernateTest {

    private static final AtomicInteger SEQ = new AtomicInteger();

    private final MountedWorld mounted = new MountedWorld();

    private final GlobalMirrorWriteGuard guard = new GlobalMirrorWriteGuard(mounted);

    private SessionFactory sessionFactory;

    private String url;

    /** 被测实体之一：映射到镜像表 actor_setting（世界库里只读） */
    @Entity
    @Table(name = "actor_setting")
    public static class MirrorEntity {
        @Id
        private Long id;
        @Column(name = "setting_key")
        private String settingKey;

        public MirrorEntity() {
        }

        MirrorEntity(Long id, String key) {
            this.id = id;
            this.settingKey = key;
        }
    }

    /** 被测实体之二：映射到一张世界库专属表（应放行） */
    @Entity
    @Table(name = "zz_guard_world_table")
    public static class WorldEntity {
        @Id
        private Long id;
        @Column(name = "name")
        private String name;

        public WorldEntity() {
        }

        WorldEntity(Long id, String name) {
            this.id = id;
            this.name = name;
        }
    }

    @BeforeEach
    void setUp() {
        url = "jdbc:h2:mem:guard_" + SEQ.incrementAndGet()
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1";
        StandardServiceRegistry registry = new StandardServiceRegistryBuilder()
                .applySetting("hibernate.connection.driver_class", "org.h2.Driver")
                .applySetting("hibernate.connection.url", url)
                .applySetting("hibernate.connection.username", "sa")
                .applySetting("hibernate.connection.password", "")
                .applySetting("hibernate.dialect", "org.hibernate.dialect.H2Dialect")
                .applySetting("hibernate.hbm2ddl.auto", "create-drop")
                // 关键：传实例（Spring 的 HibernatePropertiesCustomizer 就是这么塞的）
                .applySetting("hibernate.session_factory.statement_inspector", guard)
                .build();
        sessionFactory = new Configuration()
                .addAnnotatedClass(MirrorEntity.class)
                .addAnnotatedClass(WorldEntity.class)
                .buildSessionFactory(registry);
        WorldContext.clear();
    }

    @AfterEach
    void tearDown() {
        // 必须先卸载：create-drop 在 close 时会 drop 表，若仍挂载会被守卫拦下（这正是守卫在工作）
        mounted.unmount();
        WorldContext.clear();
        if (sessionFactory != null) {
            sessionFactory.close();
        }
    }

    @Test
    @DisplayName("接线：守卫实例被 Hibernate 接受并真的被回调（否则它会静默失效）")
    void inspectorInstanceIsActuallyInvoked() {
        // 必须在"已挂载"状态下写一条普通世界表：只有这条路径才会走到解析与缓存
        //（未挂载时守卫读一次 volatile 就返回，这是性能设计，不是没装）
        mounted.mount("uid-hibernate");
        persist(new WorldEntity(1L, "挂载后写世界表"));
        assertTrue(guard.cachedStatements() > 0,
                "守卫一次都没被回调：说明 statement_inspector 的实例没有被 Hibernate 采用，守卫等于没装");
        assertNotNull(sessionFactory);
    }

    @Test
    @DisplayName("世界上下文写镜像表：拦下并抛 409（Hibernate 可能包装异常，需沿 cause 链找人话）")
    void blocksMirrorEntityInWorldContext() {
        mounted.mount("uid-hibernate");
        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> persist(new MirrorEntity(1L, "写进世界库就丢了")));
        BizException blocked = findBiz(thrown);
        assertTrue(blocked != null, "异常链里没有 BizException：守卫没生效，实际异常=" + thrown);
        assertTrue(blocked.getMessage().contains("actor_setting"), blocked.getMessage());
    }

    @Test
    @DisplayName("显式 GLOBAL：同一实体的写放行（全局设置在目录库必须能写）")
    void allowsMirrorEntityInGlobalContext() {
        mounted.mount("uid-hibernate");
        assertDoesNotThrow(() -> WorldContext.runAsGlobal(() -> persist(new MirrorEntity(2L, "目录库写入"))));
    }

    @Test
    @DisplayName("世界表：已挂载也放行（守卫不许误伤 101 张世界表）")
    void allowsWorldEntityInWorldContext() {
        mounted.mount("uid-hibernate");
        assertDoesNotThrow(() -> persist(new WorldEntity(2L, "世界自己的数据")));
    }

    /** 真 persist（提交时会 flush，异常可能在 commit 抛出） */
    private void persist(Object entity) {
        try (Session session = sessionFactory.openSession()) {
            Transaction tx = session.beginTransaction();
            try {
                session.persist(entity);
                tx.commit();
            } catch (RuntimeException e) {
                if (tx.isActive()) {
                    tx.rollback();
                }
                throw e;
            }
        }
    }

    /** 沿 cause 链找 BizException */
    private static BizException findBiz(Throwable t) {
        Throwable cur = t;
        int guard = 0;
        while (cur != null && guard++ < 12) {
            if (cur instanceof BizException biz) {
                return biz;
            }
            cur = cur.getCause();
        }
        return null;
    }
}
