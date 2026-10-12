package com.holzyn.actor.ai.spring;

import com.openai.core.http.HttpMethod;
import com.openai.core.http.HttpRequest;
import com.openai.errors.OpenAIIoException;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Http11OpenAiHttpClient 单元测试（M5 已知问题修复·最终版，2026-09-07）。
 * <p>核心承诺：HTTP/1.1 + readTimeout 对「服务端半死」悬挂 100% 打断（HTTP/2 waitForIo 死角
 * 不存在）。测试用本地只 accept 不响应的 ServerSocket 模拟悬挂服务端，断言 readTimeout 内
 * 必然失败且根因是 SocketTimeoutException。</p>
 */
class Http11OpenAiHttpClientTest {

    /**
     * 悬挂服务端（accept 后不读不写不关）→ readTimeout 到期抛 OpenAIIoException（cause=SocketTimeoutException）。
     */
    @Test
    void hangingServerBreaksByReadTimeout() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            // 悬挂线程：接受连接后静默 5 秒（远超 1s readTimeout），期间不响应任何字节
            Thread hang = new Thread(() -> {
                try (Socket s = server.accept()) {
                    Thread.sleep(5000);
                } catch (IOException | InterruptedException ignored) {
                    // 测试线程关闭 server 后 accept 抛异常，忽略
                }
            });
            hang.setDaemon(true);
            hang.start();

            Http11OpenAiHttpClient client = new Http11OpenAiHttpClient(
                    Duration.ofSeconds(1), // connect
                    Duration.ofSeconds(1), // read——悬挂在此打断
                    Duration.ofSeconds(1), // write
                    Duration.ofSeconds(10)); // call 兜底（应轮不到）
            HttpRequest request = HttpRequest.builder()
                    .method(HttpMethod.POST)
                    .baseUrl("http://127.0.0.1:" + server.getLocalPort())
                    .build();

            // 悬挂连接建立后请求发出，服务端不响应 → 1s readTimeout 必断
            OpenAIIoException ex = assertThrows(OpenAIIoException.class,
                    () -> client.execute(request, null), "悬挂调用必须在 readTimeout 内失败");
            // 根因必须是 socket 读超时（HTTP/1.1 下 SO_TIMEOUT 直接生效）
            assertInstanceOf(SocketTimeoutException.class, ex.getCause(),
                    "失败根因应为 SocketTimeoutException（readTimeout 生效）");
        }
    }
}
