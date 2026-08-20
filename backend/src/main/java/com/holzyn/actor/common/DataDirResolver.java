package com.holzyn.actor.common;

import java.nio.file.Path;

/**
 * 本地数据目录归一化（2026-08-19，克隆即用数据一致性加固）。
 * <p>背景：应用的数据目录默认取 {@code HOLOZYN_ACTOR_DATA_DIR}（缺省 {@code ./data}，相对工作目录）。
 * 克隆即用约定数据固定在仓库根 {@code <repo>/data}（H2 库随 Git 提交）；但 IntelliJ 直接运行
 * Spring Boot 主类时，默认工作目录是模块目录 {@code <repo>/backend}，导致连到 {@code <repo>/backend/data}
 * ——一个 git 忽略的旧库（缺最新内置 Prompt 模板等），出现「模板入库却不显示」等数据不一致问题。</p>
 * <p>职责：启动时（Spring 上下文创建前）归一化默认数据目录——仅当【用户未显式设置】
 * {@code HOLOZYN_ACTOR_DATA_DIR / HOLOZYN_ACTOR_UPLOAD_DIR}（环境变量或系统属性）且
 * 工作目录末段为 {@code backend} 时，将数据/上传目录重定向到仓库根 {@code ../data} / {@code ../uploads}。
 * 用户显式设置（run.bat/run-mvn.bat 已设置、exe 化后设 %APPDATA% 等）一律尊重，不做干预。</p>
 * <p>原理：在 {@code main()} 里以 System.setProperty 写入，Spring 解析 {@code ${HOLOZYN_ACTOR_DATA_DIR:./data}}
 * 占位符时系统属性优先于环境变量，因此数据源 URL 会解析到重定向后的路径。</p>
 * <p>所属模块：common（启动期工具）</p>
 */
public final class DataDirResolver {

    /** 数据目录环境变量/系统属性名 */
    public static final String ENV_DATA_DIR = "HOLOZYN_ACTOR_DATA_DIR";
    /** 上传目录环境变量/系统属性名 */
    public static final String ENV_UPLOAD_DIR = "HOLOZYN_ACTOR_UPLOAD_DIR";

    /** 工作目录末段为 backend（IntelliJ 模块目录）时，其父目录即仓库根 */
    private static final String BACKEND_DIR_NAME = "backend";

    private DataDirResolver() {
    }

    /**
     * 归一化默认数据/上传目录（幂等，可在 main 里调用一次）。
     * <p>规则：用户显式设置过数据或上传目录 → 跳过；工作目录末段非 backend → 跳过；
     * 否则将 {@code HOLOZYN_ACTOR_DATA_DIR}=&lt;仓库根&gt;/data、{@code HOLOZYN_ACTOR_UPLOAD_DIR}=&lt;仓库根&gt;/uploads
     * 写入系统属性（Spring 占位符解析时生效）。</p>
     */
    public static void normalizeDefaults() {
        // 用户显式指定（环境变量或 -D 系统属性任一存在）→ 尊重，不干预（exe 化/远程/CI 等场景）
        if (System.getenv(ENV_DATA_DIR) != null || System.getProperty(ENV_DATA_DIR) != null) {
            return;
        }
        Path root = redirectRoot(System.getProperty("user.dir"));
        if (root == null) {
            return;
        }
        System.setProperty(ENV_DATA_DIR, root.resolve("data").toAbsolutePath().toString());
        System.setProperty(ENV_UPLOAD_DIR, root.resolve("uploads").toAbsolutePath().toString());
    }

    /**
     * 计算重定向目标仓库根目录（纯函数，可独立单测）。
     * <p>仅当工作目录非空且末段为 {@code backend} 时返回其父目录（仓库根），否则返回 null。</p>
     *
     * @param cwd 工作目录（{@code user.dir}，可为 null）
     * @return 仓库根目录（可重定向时）或 null（不重定向）
     */
    static Path redirectRoot(String cwd) {
        if (cwd == null || cwd.isBlank()) {
            return null;
        }
        Path p = Path.of(cwd);
        String name = p.getFileName() == null ? "" : p.getFileName().toString();
        if (!BACKEND_DIR_NAME.equals(name)) {
            return null;
        }
        return p.getParent();
    }
}
