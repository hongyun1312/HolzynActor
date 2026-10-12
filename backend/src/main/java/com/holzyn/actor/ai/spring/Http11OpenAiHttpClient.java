/*
 * Copyright (c) 2026 HONGYUN
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package com.holzyn.actor.ai.spring;

import com.openai.core.http.HttpClient;
import com.openai.core.http.HttpMethod;
import com.openai.core.http.HttpRequest;
import com.openai.core.http.HttpRequestBody;
import com.openai.core.http.HttpResponse;
import com.openai.core.http.Headers;
import com.openai.core.RequestOptions;
import lombok.extern.slf4j.Slf4j;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.Protocol;
import okio.BufferedSink;

import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 严格超时 OpenAI HTTP 客户端。
 * <p><b>背景（为什么前三轮修复全部无效——jstack 三连实证）</b>：Spring AI 2.0.1 默认 OkHttp 客户端
 * 对「服务端半死」（连接建立、请求已发出，但服务端既不响应也不关闭）的 HTTP/2 悬挂<b>无解</b>：</p>
 * <ul>
 * <li><b>socket readTimeout 对 HTTP/2 无效</b>：OkHttp 启动 HTTP/2 连接时会把 socket SO_TIMEOUT
 * 清零（多路复用连接由独立 reader 线程统一读 socket，单流超时不能按 socket 超时处理）——
 * jstack 实证 reader 线程（"OkHttp ark.cn-beijing.volces.com"）在 SSL socket read 上
 * 阻塞 1144s 无超时；</li>
 * <li><b>callTimeout 到期仅发 RST_STREAM，不关 socket</b>：RealCall.cancel 对 HTTP/2 只标记流取消
 * + writeSynReset；服务端半死时 RST 无人处理，reader 线程仍阻塞在 socket read，
 * {@code Http2Stream.waitForIo} 的 Object.wait 永远不被唤醒（notify 只来自 reader 收到的帧）——
 * jstack 实证 scheduling-1 卡在 waitForIo 214s / 351s / 1335s 均不返回；</li>
 * <li><b>httpClientBuilderCustomizer 无效</b>同理：customizer 改的是 socket 级超时，
 * HTTP/2 悬挂不吃 socket 超时。</li>
 * </ul>
 * <p><b>本实现（为什么这次能断）</b>：</p>
 * <ol>
 * <li><b>禁用 HTTP/2、强制 HTTP/1.1</b>——HTTP/1.1 下请求线程直接阻塞于自身 socket read
 * （无独立 reader 线程），且 HTTP/1.1 连接是 call 独占的，取消必然关闭连接打断 read；</li>
 * <li><b>自建看门狗</b>（ScheduledExecutor + {@code call.cancel()}）——不依赖 OkHttp 内部
 * readTimeout/callTimeout 是否真的生效（第三轮 jstack 实证 HTTP/1.1 下 readTimeout 配置了
 * SO_TIMEOUT 仍 290s 未断，机制不可靠）；到期强制 cancel：HTTP/1.1 下 RealCall.cancel →
 * （反编译实证）→ 阻塞 read 立即抛 SocketException → 引擎线程释放继续下一轮。</li>
 * </ol>
 * <p><b>取舍</b>：HTTP/1.1 每次请求需要独立的 TLS 握手（不再走 HTTP/2 多路复用），单次握手
 * 约百毫秒级，相对 AI 调用 2~21s 的生成时长占比可忽略；换来的是引擎线程永不被悬挂卡死
 * （24h 不间断运行目标）。maxRetries 仍须为 0（RetryingHttpClient 对超时 IOException 会重试）。</p>
 * <p>实现自 {@code com.openai.core.http.HttpClient} 接口（openai-java 4.49），请求/响应转换
 * 语义对齐 Spring AI 的 {@code SpringAiOpenAiHttpClient}（URL/方法/头/体 + X-Stainless 提示头），
 */
@Slf4j
public class Http11OpenAiHttpClient implements HttpClient {

    /** 底层 OkHttp 客户端（协议锁定 HTTP/1.1，四段超时全部显式配置） */
    private final okhttp3.OkHttpClient client;

    /** 是否持有自建 dispatcher 线程池（close 时需关闭） */
    private final boolean ownsDispatcher;

    /** 超时看门狗执行器（daemon，单线程，复用） */
    private final ScheduledExecutorService watchdog = Executors.newSingleThreadScheduledExecutor(
            new ThreadFactory() {
                private final AtomicInteger seq = new AtomicInteger();

                @Override
                public Thread newThread(Runnable r) {
                    Thread t = new Thread(r, "holzyn-ai-timeout-watchdog-" + seq.incrementAndGet());
                    t.setDaemon(true);
                    return t;
                }
            });

    /**
     * 构造：显式配置四段超时 + 强制 HTTP/1.1。
     *
     * @param connectTimeout 连接超时（必填，&gt;0）
     *
     * @param readTimeout socket 读超时（必填，&gt;0——HTTP/1.1 下悬挂 AI 调用的硬性打断手段）
     *
     * @param writeTimeout 写超时（必填，&gt;0）
     *
     * @param callTimeout 整个调用生命周期超时（必填，&gt;0——双保险兜底）
     */
    public Http11OpenAiHttpClient(Duration connectTimeout, Duration readTimeout,
                                  Duration writeTimeout, Duration callTimeout) {
        this.client = new okhttp3.OkHttpClient.Builder()
                .connectTimeout(connectTimeout)
                .readTimeout(readTimeout)
                .writeTimeout(writeTimeout)
                .callTimeout(callTimeout)
                // ⚠️ 关键：禁用 HTTP/2 多路复用——HTTP/2 悬挂（waitForIo）是 OkHttp 无法打断的死角，
                // HTTP/1.1 下 readTimeout 直接作用于请求线程的 socket read，悬挂必断
                .protocols(List.of(Protocol.HTTP_1_1))
                // 连接层失败不自动重试（openai-java 上层 RetryingHttpClient 已有 maxRetries 控制；
                // 避免 OkHttp 对非幂等 POST 在悬挂超时后盲目重放）
                .retryOnConnectionFailure(false)
                .build();
        this.ownsDispatcher = true;
    }

    /**
     * 同步执行（引擎/对话网关非流式调用路径）。
     * <p><b>硬超时看门狗</b>：调度一个到期 {@code call.cancel()} 的任务——HTTP/1.1 下
     * RealCall.cancel → Http1ExchangeCodec.cancel → RealConnection.cancel →
     * {@code Util.closeQuietly(rawSocket)}（反编译实证），阻塞中的 socket read 立即抛
     * SocketException → 悬挂 AI 调用被强制打断 → 包装为 OpenAIIoException("Request failed")。
     * 超时时长取 client.callTimeoutMillis（&gt;0），兜底 130s。</p>
     */
    @Override
    public HttpResponse execute(HttpRequest request, RequestOptions options) {
        okhttp3.Request okRequest = toOkHttpRequest(request);
        Call call = client.newCall(okRequest);
        long timeoutMillis = client.callTimeoutMillis() > 0 ? client.callTimeoutMillis() : 320_000L;
        ScheduledFuture<?> kill = watchdog.schedule(() -> {
            if (!call.isCanceled()) {
                log.warn("[AI超时] 看门狗触发：悬挂 AI 调用已强制取消 url={}（等待 {}s 无响应）",
                        request.url(), timeoutMillis / 1000L);
                call.cancel();
            }
        }, timeoutMillis, TimeUnit.MILLISECONDS);
        try {
            okhttp3.Response okResponse = call.execute();
            return new OkHttpResponse(okResponse);
        } catch (IOException e) {
            throw new com.openai.errors.OpenAIIoException("Request failed", e);
        } finally {
            kill.cancel(false);
            closeQuietly(request.body());
        }
    }

    /**
     * 异步执行（对话网关流式路径走 executeAsync 的兜底实现；本项目流式另有 SSE 通道，
     * 此实现保持接口契约可用即可）。
     */
    @Override
    public CompletableFuture<HttpResponse> executeAsync(HttpRequest request, RequestOptions options) {
        okhttp3.Request okRequest = toOkHttpRequest(request);
        CompletableFuture<HttpResponse> future = new CompletableFuture<>();
        client.newCall(okRequest).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                closeQuietly(request.body());
                future.completeExceptionally(e);
            }

            @Override
            public void onResponse(Call call, okhttp3.Response okResponse) {
                closeQuietly(request.body());
                future.complete(new OkHttpResponse(okResponse));
            }
        });
        return future;
    }

    /** 关闭底层 dispatcher 线程池（应用停机时由 Spring 容器调用）。 */
    @Override
    public void close() {
        if (ownsDispatcher) {
            client.dispatcher().executorService().shutdown();
        }
    }

    // ==================== 请求转换（openai-java HttpRequest → okhttp Request） ====================

    /**
     * openai-java 请求 → okhttp 请求（URL/方法/头/体 + X-Stainless 提示头，语义对齐
     * SpringAiOpenAiHttpClient.toRequestWithStainlessHeaders）。
     */
    private okhttp3.Request toOkHttpRequest(HttpRequest req) {
        okhttp3.RequestBody body = null;
        if (req.body() != null) {
            HttpRequestBody rb = req.body();
            MediaType mediaType = rb.contentType() == null ? null : MediaType.parse(rb.contentType());
            body = new okhttp3.RequestBody() {
                @Override
                public MediaType contentType() {
                    return mediaType;
                }

                @Override
                public long contentLength() {
                    return rb.contentLength();
                }

                @Override
                public void writeTo(BufferedSink sink) throws IOException {
                    rb.writeTo(sink.outputStream());
                }
            };
        } else if (requiresBody(req.method())) {
            // POST/PUT/PATCH 必须有请求体（okhttp 约束）；对齐 Spring AI 空体兜底
            body = okhttp3.RequestBody.create(new byte[0], null);
        }
        okhttp3.Request.Builder builder = new okhttp3.Request.Builder()
                .url(req.url())
                .method(req.method().name(), body);
        for (String name : req.headers().names()) {
            for (String value : req.headers().values(name)) {
                builder.addHeader(name, value);
            }
        }
        // X-Stainless 提示头（与 Spring AI 实现一致：读超时/重试计数/客户端超时）
        if (client.readTimeoutMillis() > 0) {
            builder.addHeader("X-Stainless-Read-Timeout", String.valueOf(client.readTimeoutMillis() / 1000L));
        }
        builder.addHeader("X-Stainless-Retry-Count", "0");
        if (client.callTimeoutMillis() > 0) {
            builder.addHeader("X-Stainless-Client-Timeout", String.valueOf(client.callTimeoutMillis() / 1000L));
        }
        return builder.build();
    }

    /** POST/PUT/PATCH 需要请求体（对齐 SpringAiOpenAiHttpClient.requiresBody 语义）。 */
    private static boolean requiresBody(HttpMethod method) {
        return method == HttpMethod.POST || method == HttpMethod.PUT || method == HttpMethod.PATCH;
    }

    // ==================== 响应适配（okhttp Response → openai-java HttpResponse） ====================

    /** okhttp 响应适配（延迟流式读 body，close 释放连接）。 */
    private static final class OkHttpResponse implements HttpResponse {
        private final okhttp3.Response response;

        OkHttpResponse(okhttp3.Response response) {
            this.response = response;
        }

        @Override
        public int statusCode() {
            return response.code();
        }

        @Override
        public Headers headers() {
            Headers.Builder hb = Headers.builder();
            for (int i = 0; i < response.headers().size(); i++) {
                hb.put(response.headers().name(i), response.headers().value(i));
            }
            return hb.build();
        }

        @Override
        public InputStream body() {
            okhttp3.ResponseBody rb = response.body();
            return rb == null ? InputStream.nullInputStream() : rb.byteStream();
        }

        @Override
        public void close() {
            response.close();
        }
    }

    /** 请求体静默关闭（对齐 SpringAiOpenAiHttpClient 的 body.close 语义）。 */
    private static void closeQuietly(HttpRequestBody body) {
        if (body != null) {
            try {
                body.close();
            } catch (Exception ignored) {
                // 关闭失败不影响主流程
            }
        }
    }
}
