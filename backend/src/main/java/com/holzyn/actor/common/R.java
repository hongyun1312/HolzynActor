package com.holzyn.actor.common;

import com.holzyn.actor.common.ErrorResponse;
import lombok.Data;

/**
 * 统一响应包装。
 * <p>职责：统一 REST API 返回结构 {code, message, data, error}。</p>
 * <p>设计原则：成功响应 error 为 null；失败响应 data 为 null、error 填充错误详情。</p>
 * <p>所属模块：model（通用模型层）</p>
 *
 * @param <T> 数据载荷类型
 */
@Data
public class R<T> {

    /** 业务状态码：200 成功，其余为失败 */
    private int code;

    /** 提示信息 */
    private String message;

    /** 数据载荷（成功时填充，失败时为 null） */
    private T data;

    /** 错误详情（失败时填充，成功时为 null） */
    private ErrorResponse error;

    /**
     * 构造成功响应（无错误详情）。
     *
     * @param data 业务数据载荷
     * @return 包装了成功状态码与数据的 R 对象
     */
    public static <T> R<T> ok(T data) {
        R<T> r = new R<>();
        r.code = 200;
        r.message = "ok";
        r.data = data;
        return r;
    }

    /**
     * 构造失败响应（无数据载荷）。
     *
     * @param code    错误状态码（如 400/404/500）
     * @param message 错误提示信息
     * @return 包装了错误状态码与提示的 R 对象
     */
    public static <T> R<T> fail(int code, String message) {
        R<T> r = new R<>();
        r.code = code;
        r.message = message;
        return r;
    }

    /**
     * 构造失败响应（含错误详情）。
     *
     * @param code    错误状态码
     * @param message 错误提示信息
     * @param error   错误详情对象（含字段名与详细描述）
     * @return 包装了错误状态码、提示与详情的 R 对象
     */
    public static <T> R<T> fail(int code, String message, ErrorResponse error) {
        R<T> r = new R<>();
        r.code = code;
        r.message = message;
        r.error = error;
        return r;
    }
}