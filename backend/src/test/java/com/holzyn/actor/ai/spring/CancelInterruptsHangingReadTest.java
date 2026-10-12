package com.holzyn.actor.ai.spring;

import okhttp3.Call;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 关键行为验证：HTTP/1.1 下从另一线程 call.cancel() 是否能打断阻塞中的 read
 * （RealCall.cancel → Http1ExchangeCodec.cancel → RealConnection.cancel → closeQuietly(rawSocket)）。
 * <p>生产实证 2026-09-08：人群 AI 调用悬挂 readResponseHeaders 240s+，看门狗 schedule 已调度但
 * 未打断——需要判定是「看门狗任务未执行」还是「cancel 关 socket 无法打断 SSL read」。本测试直接
 * 用另一个线程 cancel 悬挂调用，观察 execute 是否返回异常。</p>
 */
class CancelInterruptsHangingReadTest {

    @Test
    void cancelFromAnotherThreadInterruptsHangingRead() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            Thread hang = new Thread(() -> {
                try (Socket s = server.accept()) {
                    Thread.sleep(30_000); // 悬挂 30s，期间不响应
                } catch (IOException | InterruptedException ignored) {
                }
            });
            hang.setDaemon(true);
            hang.start();

            OkHttpClient client = new OkHttpClient.Builder()
                    .connectTimeout(Duration.ofSeconds(2))
                    .readTimeout(Duration.ofSeconds(30)) // 30s，远大于测试窗口
                    .writeTimeout(Duration.ofSeconds(2))
                    .protocols(java.util.List.of(okhttp3.Protocol.HTTP_1_1))
                    .build();
            Request req = new Request.Builder()
                    .url("http://127.0.0.1:" + server.getLocalPort() + "/hang")
                    .post(okhttp3.RequestBody.create(new byte[0], null))
                    .build();
            Call call = client.newCall(req);

            // 主线程在另一个线程里执行（模拟引擎线程），1.5s 后从测试线程 cancel
            AtomicReference<Exception> result = new AtomicReference<>();
            Thread exec = new Thread(() -> {
                try {
                    call.execute();
                    result.set(null); // 未抛出 = 未被 cancel 打断
                } catch (Exception e) {
                    result.set(e);
                }
            });
            exec.start();

            Thread.sleep(1500);
            call.cancel(); // 模拟看门狗
            exec.join(10_000);

            Exception e = result.get();
            assertNotNull(e, "cancel 后 execute 必须抛出异常（read 被打断），否则看门狗方案无效");
            System.out.println("cancel 打断成功，异常=" + e);
        }
    }
}
