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

package com.holzyn.actor.ai.nanojev;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * NanoJev HTTP 执行器（可替换边界：单测注入内存 fake，冒烟/生产走真实 JDK HttpClient）。
 * <p>实现要点：</p>
 * <ul>
 * <li><b>超时三层</b>：connectTimeout（建连）+ request timeout（整次调用硬上限）双保险——
 * JDK HttpClient 的 timeout 覆盖「直到响应头到达」；读超时由 request timeout 兜底
 * （与 AI 层 Http11OpenAiHttpClient 看门狗同思路，防悬挂线程）；</li>
 * <li><b>鉴权</b>：Authorization: Bearer（与 /v1/chat/completions 同一 API Key）；</li>
 * <li><b>UTF-8</b>：请求/响应体统一 UTF-8 字节通道（中文 state/instructions 零乱码）。</li>
 * </ul>
 */
public interface NanoJevHttpExecutor {

    /**
     * HTTP 结果（状态码 + 响应体 + 端到端耗时）。
     *
     * @param status HTTP 状态码
     *
     * @param body 响应体（UTF-8 解码；失败可空）
     *
     * @param durationMs 端到端耗时（毫秒）
     */
    record HttpResult(int status, String body, long durationMs) {
    }

    /**
     * POST JSON（evaluate 端点）。
     *
     * @param url 完整 URL（base-url + /v1/nanojev/evaluate）
     *
     * @param apiKey Bearer Key（可空=不发鉴权头，内网无 Key 放行场景）
     *
     * @param bodyJson 请求体 JSON（UTF-8）
     *
     * @param connectSec 建连超时（秒，<=0 用默认 10）
     *
     * @param readSec 整次请求硬超时（秒，<=0 用默认 30）
     *
     * @return 结果（网络异常/超时抛 RuntimeException，由调用方 fail-closed）
     */
    HttpResult postJson(String url, String apiKey, String bodyJson, int connectSec, int readSec);

    /**
     * GET JSON（/v1/models 健康检查）。
     *
     * @param url 完整 URL（base-url + /v1/models）
     *
     * @param apiKey Bearer Key（可空）
     *
     * @param connectSec 建连超时（秒）
     *
     * @param readSec 整次请求硬超时（秒）
     *
     * @return 结果（网络异常/超时抛 RuntimeException）
     */
    HttpResult getJson(String url, String apiKey, int connectSec, int readSec);

    /**
     * 默认实现：JDK 21 内置 HttpClient（零第三方依赖，与项目 AI 层同源思路）。
     */
    class HttpNanoJevExecutor implements NanoJevHttpExecutor {

        private final HttpClient client;

        public HttpNanoJevExecutor() {
            this.client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(10))
                    .followRedirects(HttpClient.Redirect.NEVER)
                    .build();
        }

        /** 显式指定底层客户端（测试用：可注入 MockWebServer 目标等）。 */
        HttpNanoJevExecutor(HttpClient client) {
            this.client = client;
        }

        @Override
        public HttpResult postJson(String url, String apiKey, String bodyJson, int connectSec, int readSec) {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(readSec > 0 ? readSec : 30))
                    .header("Content-Type", "application/json; charset=utf-8")
                    .POST(HttpRequest.BodyPublishers.ofString(bodyJson == null ? "" : bodyJson,
                            java.nio.charset.StandardCharsets.UTF_8));
            if (apiKey != null && !apiKey.isBlank()) {
                b.header("Authorization", "Bearer " + apiKey);
            }
            return send(b.build(), connectSec);
        }

        @Override
        public HttpResult getJson(String url, String apiKey, int connectSec, int readSec) {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(readSec > 0 ? readSec : 30))
                    .GET();
            if (apiKey != null && !apiKey.isBlank()) {
                b.header("Authorization", "Bearer " + apiKey);
            }
            return send(b.build(), connectSec);
        }

        private HttpResult send(HttpRequest request, int connectSec) {
            long t0 = System.currentTimeMillis();
            try {
                HttpResponse<String> resp = client.send(request, HttpResponse.BodyHandlers.ofString(
                        java.nio.charset.StandardCharsets.UTF_8));
                return new HttpResult(resp.statusCode(), resp.body(),
                        System.currentTimeMillis() - t0);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("NanoJev HTTP 调用被中断", e);
            } catch (Exception e) {
                throw new RuntimeException("NanoJev HTTP 调用失败：" + e.getMessage(), e);
            }
        }
    }
}
