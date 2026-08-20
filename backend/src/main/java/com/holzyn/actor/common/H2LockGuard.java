package com.holzyn.actor.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.core.env.Environment;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

/**
 * H2 数据库文件锁启动守卫（2026-08-18 新增）。
 * <p>职责：在 Spring 上下文 / Hibernate 初始化之前，探测本地 H2 文件库是否已被另一个
 * HolzynActor 实例独占（H2 单实例文件锁），命中时输出**清晰中文提示**并退出，
 * 避免抛一屏 H2 原始堆栈（"Database may be already in use" / "The file is locked"）
 * 让用户误以为是代码故障。</p>
 * <p>设计：</p>
 * <ul>
 *   <li>监听 {@link ApplicationEnvironmentPreparedEvent}（环境已就绪、数据源 URL 已解析含环境变量），
 *       仅对 {@code jdbc:h2:file:} 路径探测（本地文件库独占锁；内存库/远程 MySQL 不检查）；</li>
 *   <li>探测方式：用同一 URL 建立一次 JDBC 连接，能连上即未被占用（关闭后放行）；
 *       连不上且错误为「already in use / locked / 90020」→ 判定被另一实例占用 → 中文提示 + System.exit(1)；</li>
 *   <li><b>fail-open</b>：任何其他异常（驱动缺失/路径无效等）一律放行，交由启动流程给出更准确的错误，
 *       绝不阻塞正常启动。</li>
 * </ul>
 * <p>背景：本地 H2 文件库被运行进程独占锁定（OS 级文件锁），两个后端实例无法共用同一份数据；
 * 常见误触发场景为「IntelliJ 与 java -jar / run.bat 同时运行」或「遗留的后台 jar 未退出」。</p>
 * <p>所属模块：common（通用组件）</p>
 */
public class H2LockGuard implements ApplicationListener<ApplicationEnvironmentPreparedEvent> {

    private static final Logger log = LoggerFactory.getLogger(H2LockGuard.class);

    @Override
    public void onApplicationEvent(ApplicationEnvironmentPreparedEvent event) {
        Environment env = event.getEnvironment();
        String url = env.getProperty("spring.datasource.url", "");
        // 仅检查本地 H2 文件库（单实例独占锁只发生在文件库；内存库/远程 MySQL 跳过）
        if (url == null || !url.startsWith("jdbc:h2:file:")) {
            return;
        }
        try {
            Class.forName("org.h2.Driver");
        } catch (ClassNotFoundException e) {
            return; // H2 驱动缺失（正常运行时不会发生）：放行，由后续启动流程处理
        }
        String user = env.getProperty("spring.datasource.username", "sa");
        String pass = env.getProperty("spring.datasource.password", "");
        try (Connection ignored = DriverManager.getConnection(url, user, pass)) {
            // 能连上 = 未被占用：正常关闭连接释放探针锁后放行
        } catch (SQLException e) {
            String msg = e.getMessage() == null ? "" : e.getMessage();
            boolean locked = msg.contains("already in use") || msg.contains("locked")
                    || msg.contains("90020");
            if (locked) {
                // 被另一实例占用：清晰中文提示并退出（H2 单实例文件锁，属预期行为而非代码故障）
                System.err.println();
                System.err.println("============================================================");
                System.err.println("[HolzynActor] 启动失败：H2 数据库被另一个实例占用");
                System.err.println("  数据库：" + url);
                System.err.println("  H2 为单实例文件锁：同一时刻只允许一个后端进程访问 data/holzyn-actor.mv.db。");
                System.err.println("  请先关闭其他后端进程（IntelliJ 运行 / run.bat / java -jar / 后台残留 java），");
                System.err.println("  再重新启动本应用。");
                System.err.println("============================================================");
                System.err.println();
                System.exit(1);
            } else {
                // 其他连接错误（如路径无效）：仅告警，放行交给启动流程给出更准确的错误
                log.warn("H2 连接预检异常（放行，交由启动流程处理）: {}", msg);
            }
        }
    }
}
