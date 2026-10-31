package com.holzyn.actor.domain.worldstore;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 世界上下文（GLOBAL 标记）语义（V1.1.2-beta.1 M2）。
 *
 * <p>这个类只有几十行，却是整套分档方案安全性论证的支点：如果"显式全局"标记会漏、
 * 会粘在复用线程上、或被异常路径遗留，后果就是**某个世界的写入落到目录库**
 * （目录库里根本没有那些表 → 500）或反过来**全局配置被写进某个世界**
 * （这个世界从此带着一份分叉的配置）。所以下面四条要死死钉住。</p>
 *
 * <p>所属模块：test/domain/worldstore</p>
 */
class WorldContextTest {

    @AfterEach
    void cleanup() {
        WorldContext.clear();
    }

    @Test
    @DisplayName("默认非全局：未显式进入时一律按『当前挂载世界』路由")
    void defaultIsNotGlobal() {
        assertFalse(WorldContext.isGlobal());
        assertEquals(0, WorldContext.depth());
    }

    @Test
    @DisplayName("作用域进出：关闭即还原，嵌套只在归零时退出")
    void scopeEntersAndRestores() {
        try (var outer = WorldContext.global()) {
            assertTrue(WorldContext.isGlobal());
            try (var inner = WorldContext.global()) {
                assertTrue(WorldContext.isGlobal(), "嵌套内仍是全局");
                assertEquals(2, WorldContext.depth());
            }
            assertTrue(WorldContext.isGlobal(), "内层退出后外层仍应生效（不能用布尔标记）");
            assertEquals(1, WorldContext.depth());
        }
        assertFalse(WorldContext.isGlobal());
        assertEquals(0, WorldContext.depth());
    }

    @Test
    @DisplayName("异常路径必须还原：抛出后不能让标记粘在当前线程上")
    void exceptionStillRestores() {
        assertThrows(IllegalStateException.class, () -> WorldContext.runAsGlobal(() -> {
            throw new IllegalStateException("boom");
        }));
        assertFalse(WorldContext.isGlobal(), "异常退出后全局标记必须已清除，否则后续请求会一直打目录库");
    }

    @Test
    @DisplayName("重复 close 幂等：不会把深度减成负数")
    void doubleCloseIsIdempotent() {
        var scope = WorldContext.global();
        scope.close();
        scope.close();
        assertFalse(WorldContext.isGlobal());
        assertEquals(0, WorldContext.depth());
    }

    @Test
    @DisplayName("线程隔离：一个线程进入全局不影响另一个线程")
    void threadIsolation() throws Exception {
        AtomicBoolean seenInOtherThread = new AtomicBoolean(true);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        try (var ignored = WorldContext.global()) {
            Thread t = new Thread(() -> {
                try {
                    seenInOtherThread.set(WorldContext.isGlobal());
                } catch (Throwable e) {
                    failure.set(e);
                }
            }, "world-context-probe");
            t.start();
            t.join(5_000);
            assertTrue(WorldContext.isGlobal(), "主线程自己仍应处于全局");
        }
        assertNull(failure.get());
        assertFalse(seenInOtherThread.get(), "全局标记不得跨线程泄漏（ThreadLocal 语义）");
    }

    @Test
    @DisplayName("callAsGlobal 返回值透传")
    void callAsGlobalReturnsValue() {
        String v = WorldContext.callAsGlobal(() -> {
            assertTrue(WorldContext.isGlobal());
            return "ok";
        });
        assertEquals("ok", v);
        assertFalse(WorldContext.isGlobal());
    }

    private static void assertNull(Object o) {
        if (o != null) {
            throw new AssertionError("不应有异常：" + o);
        }
    }
}
