package com.holzyn.actor.ai;

/**
 * AI 调用异常。
 * <p>用途：统一标识 AI 接入层（连通性测试 / 对话补全 / 配置解析）的业务错误，
 * 消息面向用户为中文友好提示，由全局异常处理器兜底返回。</p>
 * <p>所属模块：service/ai（AI 接入层）</p>
 */
public class AiCallException extends RuntimeException {

    /**
     * 构造异常。
     *
     * @param message 中文错误提示
     */
    public AiCallException(String message) {
        super(message);
    }

    /**
     * 构造异常（含根因，用于日志排障）。
     *
     * @param message 中文错误提示
     * @param cause   原始异常
     */
    public AiCallException(String message, Throwable cause) {
        super(message, cause);
    }
}
