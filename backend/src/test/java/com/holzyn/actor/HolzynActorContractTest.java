package com.holzyn.actor;

import com.holzyn.actor.common.R;
import com.holzyn.actor.common.PageResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * HolzynActor 契约测试（P0 骨架）。
 * <p>职责：验证统一响应 R&lt;T&gt; 与分页 PageResult 的契约行为（对齐 shared 契约），
 * 不加载 Spring 上下文，避免依赖数据库。</p>
 * <p>所属模块：测试（contract）</p>
 */
class HolzynActorContractTest {

    /**
     * 成功响应契约：code=200、message=ok、data 透传、error 为 null。
     */
    @Test
    void okResponseContract() {
        R<String> r = R.ok("hello");
        assertEquals(200, r.getCode());
        assertEquals("ok", r.getMessage());
        assertEquals("hello", r.getData());
        assertNull(r.getError());
    }

    /**
     * 失败响应契约：错误码与提示正确、data 为 null。
     */
    @Test
    void failResponseContract() {
        R<Void> r = R.fail(400, "请求参数错误");
        assertEquals(400, r.getCode());
        assertEquals("请求参数错误", r.getMessage());
        assertNull(r.getData());
    }

    /**
     * 分页契约：totalPages 向上取整、字段对齐 shared 契约。
     */
    @Test
    void pageResultContract() {
        PageResult<String> p = PageResult.of(List.of("a", "b"), 5, 1, 2);
        assertEquals(2, p.getList().size());
        assertEquals(5, p.getTotal());
        assertEquals(1, p.getPage());
        assertEquals(2, p.getSize());
        assertEquals(3, p.getTotalPages());
    }
}