package com.holzyn.actor.common;

import com.holzyn.actor.common.R;
import com.holzyn.actor.common.ErrorResponse;
import com.holzyn.actor.common.BizException;
import com.holzyn.actor.ai.AiCallException;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.context.request.async.AsyncRequestTimeoutException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;

/**
 * 全局异常处理器。
 * <p>职责：捕获控制器抛出的各类异常，统一返回 R 包装的错误响应（含 ErrorResponse 详情）。</p>
 * <p>设计原则（AI-DEVELOPMENT-GUIDELINES P0-04）：所有外部调用必须有全局异常兜底，
 * 不向前端暴露堆栈信息。</p>
 * <p>所属模块：controller/common（通用控制器）</p>
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * 参数校验失败（@Valid 注解触发）。
     *
     * @param e 校验异常
     * @return 400 错误响应，error 中包含校验失败的字段名与提示
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public R<Void> handleValidation(MethodArgumentNotValidException e) {
        FieldError fe = e.getBindingResult().getFieldError();
        String field = fe != null ? fe.getField() : "";
        String msg = fe != null ? fe.getField() + ": " + fe.getDefaultMessage() : "参数校验失败";
        String detail = fe != null ? fe.getDefaultMessage() : "请求参数不满足校验约束";
        log.warn("参数校验失败: {}", msg);
        return R.fail(400, msg, ErrorResponse.of(field, detail));
    }

    /**
     * 请求参数类型不匹配（如路径/查询参数 id 传了字符串 "null"）。
     * 统一返回 400 而非 500，避免参数类错误刷 ERROR 日志。
     *
     * @param e 类型不匹配异常
     * @return 400 错误响应
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public R<Void> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        String name = e.getName();
        String value = e.getValue() == null ? "null" : String.valueOf(e.getValue());
        String msg = "请求参数格式错误：参数 " + name + " 应为数字（收到：" + value + "）";
        log.warn("请求参数类型不匹配: {}", msg);
        return R.fail(400, msg, ErrorResponse.of(name, "参数类型不匹配"));
    }

    /**
     * SSE 流式请求超时（AsyncRequestTimeoutException）。
     * SSE 超时属正常现象（前端 EventSource 会自动重连），且请求为 text/event-stream 没有 JSON 转换器，
     * 这里静默处理，避免落入 RuntimeException handler 尝试返回 R JSON 造成二次报错。
     *
     * @param e SSE 超时异常
     */
    @ExceptionHandler(AsyncRequestTimeoutException.class)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void handleAsyncTimeout(AsyncRequestTimeoutException e) {
        log.info("SSE 流式请求超时（客户端可能已断开，前端将重连）");
    }

    /**
     * SSE 客户端主动断开（AsyncRequestNotUsableException）。
     * 用户停止播放/离开页面时前端关闭 EventSource，容器异步错误通知抛此异常；
     * 连接已不可用且请求为 text/event-stream（无 JSON 转换器），这里静默处理，
     * 避免落入 Exception handler 尝试返回 R JSON 造成「No converter for R」二次报错刷 ERROR。
     *
     * @param e 客户端断开异常
     */
    @ExceptionHandler(AsyncRequestNotUsableException.class)
    public void handleAsyncNotUsable(AsyncRequestNotUsableException e) {
        log.info("SSE 客户端连接已断开（用户停止播放或离开页面）");
    }

    /**
     * 非法参数异常。
     *
     * @param e 非法参数异常
     * @return 400 错误响应
     */
    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public R<Void> handleIllegalArgument(IllegalArgumentException e) {
        log.warn("非法参数: {}", e.getMessage());
        return R.fail(400, "请求参数错误", ErrorResponse.of(e.getMessage()));
    }

    /**
     * 实体未找到异常。
     *
     * @param e 实体未找到异常
     * @return 404 错误响应
     */
    @ExceptionHandler(EntityNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public R<Void> handleEntityNotFound(EntityNotFoundException e) {
        log.warn("实体未找到: {}", e.getMessage());
        return R.fail(404, "资源不存在", ErrorResponse.of(e.getMessage()));
    }



    /**
     * 业务异常（BizException）：按异常携带的状态码返回（默认 400）。
     *
     * @param e 业务异常
     * @return 对应状态码的 R 错误响应
     */
    @ExceptionHandler(BizException.class)
    public ResponseEntity<R<Void>> handleBiz(BizException e) {
        log.warn("业务异常[{}]: {}", e.getCode(), e.getMessage());
        HttpStatus status = HttpStatus.resolve(e.getCode());
        if (status == null) status = HttpStatus.BAD_REQUEST;
        return ResponseEntity.status(status).body(R.fail(e.getCode(), e.getMessage(), ErrorResponse.of(e.getMessage())));
    }

    /**
     * AI 调用异常（AiCallException）：返回 400 业务失败（如未配置 API / Key 无效）。
     *
     * @param e AI 调用异常
     * @return 400 错误响应（中文友好提示）
     */
    @ExceptionHandler(AiCallException.class)
    public R<Void> handleAiCall(AiCallException e) {
        log.warn("AI 调用失败: {}", e.getMessage());
        return R.fail(400, e.getMessage(), ErrorResponse.of(e.getMessage()));
    }


    /**
     * 静态资源/路径不存在异常（如前端调用了未实现的 /api/** 占位接口）。
     * 统一返回 404 而非 500，避免无意义的错误日志噪音。
     *
     * @param e 资源不存在异常
     * @return 404 错误响应
     */
    @ExceptionHandler(NoResourceFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public R<Void> handleNoResource(NoResourceFoundException e) {
        return R.fail(404, "接口不存在：" + e.getResourcePath(), ErrorResponse.of("NOT_FOUND"));
    }
    /**
     * 运行时异常兜底。
     *
     * @param e 运行时异常
     * @return 500 错误响应
     */
    @ExceptionHandler(RuntimeException.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public R<Void> handleRuntime(RuntimeException e) {
        log.error("运行时异常: {}", e.getMessage(), e);
        return R.fail(500, "服务器内部错误", ErrorResponse.of(e.getMessage()));
    }

    /**
     * 全局兜底异常。
     *
     * @param e 未被其他 handler 匹配的异常
     * @return 500 错误响应
     */
    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public R<Void> handleException(Exception e) {
        log.error("未处理异常: {}", e.getMessage(), e);
        return R.fail(500, "服务器内部错误", ErrorResponse.of(e.getMessage()));
    }
}