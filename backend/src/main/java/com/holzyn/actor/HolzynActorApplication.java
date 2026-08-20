package com.holzyn.actor;

import com.holzyn.actor.common.DataDirResolver;
import com.holzyn.actor.common.H2LockGuard;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * HolzynActor（NPC 角色 AI 驱动模块）启动类。
 * <p>职责：Spring Boot 应用入口，初始化独立后端服务（项目/世界观、角色卡、对话、行动、
 * 普通型 NPC、知识库记忆、模型供应商管理），并承载独立登录注册（复用 Casdoor OIDC 配置）。</p>
 * <p>模块：com.holzyn.actor（第 8 模块，独立部署 CT110 / actor.holzyn.com）</p>
 */
@SpringBootApplication
@EnableScheduling
public class HolzynActorApplication {

    /**
     * 应用主入口。
     * <p>2026-08-18 追加 H2LockGuard：Hibernate 初始化前探测本地 H2 文件库是否被另一实例占用，
     * 命中时给出清晰中文提示并退出（而非一屏 H2 原始堆栈）。</p>
     * <p>2026-08-19 追加 DataDirResolver.normalizeDefaults()：IntelliJ 以 backend 为工作目录运行时
     * 把数据/上传目录重定向到仓库根 data/、uploads/（克隆即用数据一致性，详见 DataDirResolver）。</p>
     *
     * @param args 启动参数
     */
    public static void main(String[] args) {
        // 先归一化数据目录（在 Spring 上下文创建前设置系统属性，数据源 URL 占位符才会解析到重定向路径）
        DataDirResolver.normalizeDefaults();
        SpringApplication app = new SpringApplication(HolzynActorApplication.class);
        app.addListeners(new H2LockGuard());
        app.run(args);
    }
}