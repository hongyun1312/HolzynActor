package com.holzyn.actor.common;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * DataDirResolver 单测（2026-08-19 克隆即用数据一致性：IntelliJ backend 工作目录重定向到仓库根；
 * 2026-10-10 桌面端封装：打包桌面模式 LOCALAPPDATA 数据目录）。
 * <p>覆盖：redirectRoot 纯函数各分支——backend 末段重定向到父目录 / 其他目录不重定向 / 空与 null /
 * Windows 风格路径 / backend 同名但不在末段（backend-tools）不误伤；打包桌面模式
 * （{@code -Dholzyn.actor.packaged=true}）isPackaged 判定与 packagedRoot 数据根目录推导。
 * 断言用 {@link Path#of} 构造期望值，跨平台（Windows 反斜杠 / Unix 正斜杠）均成立。</p>
 * <p>所属模块：test/common（启动期工具）</p>
 */
class DataDirResolverTest {

    /** backend 末段 → 返回父目录（仓库根），跨平台路径 */
    @Test
    void backendCwdRedirectsToParent() {
        Path cwd = Path.of("/repo", "HolzynActor", "backend");
        Path root = DataDirResolver.redirectRoot(cwd.toString());
        assertNotNull(root);
        assertEquals(Path.of("/repo", "HolzynActor"), root);
    }

    /** Windows 风格 backend 路径 → 返回父目录（仅 Windows 生效；Unix 下反斜杠路径不适用，跳过） */
    @Test
    void windowsBackendCwdRedirectsToParent() {
        assumeTrue(java.io.File.separatorChar == '\\', "Windows-only path assertion");
        Path cwd = Path.of("C:\\Users\\me\\HolzynActor\\backend");
        Path root = DataDirResolver.redirectRoot(cwd.toString());
        assertNotNull(root);
        assertEquals(Path.of("C:\\Users\\me\\HolzynActor"), root);
    }

    /** 非 backend 末段（如仓库根 / 其它目录）→ 不重定向 */
    @Test
    void nonBackendCwdDoesNotRedirect() {
        assertNull(DataDirResolver.redirectRoot(Path.of("/repo", "HolzynActor").toString()));
        assertNull(DataDirResolver.redirectRoot(Path.of("/repo", "HolzynActor", "data").toString()));
        assertNull(DataDirResolver.redirectRoot(Path.of("/home", "user").toString()));
    }

    /** 空 / null 工作目录 → 不重定向（安全兜底） */
    @Test
    void blankOrNullCwdDoesNotRedirect() {
        assertNull(DataDirResolver.redirectRoot(null));
        assertNull(DataDirResolver.redirectRoot(""));
        assertNull(DataDirResolver.redirectRoot("   "));
    }

    /** backend 同名但不在末段（如 backend-tools）→ 不重定向 */
    @Test
    void similarNameDoesNotRedirect() {
        assertNull(DataDirResolver.redirectRoot(Path.of("/repo", "backend-tools").toString()));
        assertNull(DataDirResolver.redirectRoot(Path.of("/repo", "backendx").toString()));
    }

    // ---- 打包桌面模式（2026-10-10 桌面端封装）----

    /** 未设置 holzyn.actor.packaged → 非打包模式（默认） */
    @Test
    void packagedFlagDefaultsFalse() {
        String saved = System.getProperty(DataDirResolver.PROP_PACKAGED);
        try {
            System.clearProperty(DataDirResolver.PROP_PACKAGED);
            assertFalse(DataDirResolver.isPackaged());
        } finally {
            restore(saved);
        }
    }

    /** 设置 holzyn.actor.packaged=true（jpackage --java-options）→ 打包模式 */
    @Test
    void packagedFlagTrueWhenPropertySet() {
        String saved = System.getProperty(DataDirResolver.PROP_PACKAGED);
        try {
            System.setProperty(DataDirResolver.PROP_PACKAGED, "true");
            assertTrue(DataDirResolver.isPackaged());
            System.setProperty(DataDirResolver.PROP_PACKAGED, "TRUE");
            assertTrue(DataDirResolver.isPackaged());
        } finally {
            restore(saved);
        }
    }

    /** 设置 false / 任意非 true → 非打包模式 */
    @Test
    void packagedFlagFalseForNonTrueValues() {
        String saved = System.getProperty(DataDirResolver.PROP_PACKAGED);
        try {
            System.setProperty(DataDirResolver.PROP_PACKAGED, "false");
            assertFalse(DataDirResolver.isPackaged());
            System.setProperty(DataDirResolver.PROP_PACKAGED, "yes");
            assertFalse(DataDirResolver.isPackaged());
        } finally {
            restore(saved);
        }
    }

    /** LOCALAPPDATA 存在（Windows）→ 数据根 = %LOCALAPPDATA%\HolzynActor */
    @Test
    void packagedRootPrefersLocalAppData() {
        Path root = DataDirResolver.packagedRoot("C:\\Users\\me\\AppData\\Local", "/home/me");
        assertNotNull(root);
        assertEquals(Path.of("C:\\Users\\me\\AppData\\Local").resolve("HolzynActor"), root);
    }

    /** LOCALAPPDATA 为空 → 回退 user.home/.holzynactor */
    @Test
    void packagedRootFallsBackToUserHome() {
        assertEquals(Path.of("/home/me").resolve(".holzynactor"),
                DataDirResolver.packagedRoot(null, "/home/me"));
        assertEquals(Path.of("/home/me").resolve(".holzynactor"),
                DataDirResolver.packagedRoot("   ", "/home/me"));
        assertEquals(Path.of("C:\\Users\\me").resolve(".holzynactor"),
                DataDirResolver.packagedRoot("", "C:\\Users\\me"));
    }

    /** LOCALAPPDATA 与 user.home 均不可得 → null（调用方零干预，走 application.yml 默认） */
    @Test
    void packagedRootNullWhenNothingAvailable() {
        assertNull(DataDirResolver.packagedRoot(null, null));
        assertNull(DataDirResolver.packagedRoot("", ""));
        assertNull(DataDirResolver.packagedRoot("  ", "  "));
    }

    /** 测试结束恢复系统属性原值，避免污染并行/后续测试 */
    private static void restore(String saved) {
        if (saved == null) {
            System.clearProperty(DataDirResolver.PROP_PACKAGED);
        } else {
            System.setProperty(DataDirResolver.PROP_PACKAGED, saved);
        }
    }
}
