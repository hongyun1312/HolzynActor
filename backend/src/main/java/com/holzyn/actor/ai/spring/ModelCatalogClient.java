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

import com.holzyn.actor.common.BizException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 供应商模型目录拉取（OpenAI 兼容 {@code GET {baseUrl}/models}）。
 *
 * <p>为什么单独一个组件而不是塞进 {@link ChatModelProbe}：探测是"发一次最小对话"，
 * 目录是"读一次清单"，两者的失败语义与超时预算不同；混在一起会让设置页的
 * 「测试连接」与「获取全部模型」互相牵连（模型清单 404 的供应商仍可能能对话）。</p>
 *
 * <p>兼容三种常见返回：OpenAI 的 {@code {"data":[{"id":"..."}]}}、Ollama 风格的
 * {@code {"models":[{"name":"..."}]}}、以及裸数组 {@code ["..."]}。解析失败只报"返回格式无法识别"，
 * 不把原始响应体整段回显给前端（可能含供应商内部信息）。</p>
 *
 * <p>所属模块：ai/spring（AI 供应商接入）</p>
 */
@Slf4j
@Component
public class ModelCatalogClient {

    /** 连接超时：目录接口通常比对话快，8 秒足够 */
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(8);

    /** 请求超时：部分网关在代理上游清单时会慢，给到 20 秒 */
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(20);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();

    /**
     * 拉取供应商全部模型 ID。
     *
     * @param baseUrl 供应商 Base URL（可带 /v1，可带结尾斜杠）
     * @param apiKey  明文 Key（服务端内存内使用）
     *
     * @return 去重并排序后的模型 ID 列表（可能为空数组）
     *
     * @throws BizException 502 网络失败/非 2xx/响应无法解析
     */
    public List<String> listModels(String baseUrl, String apiKey) {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new BizException(400, "Base URL 不能为空");
        }
        if (apiKey == null || apiKey.isBlank()) {
            throw new BizException(400, "API Key 不能为空");
        }
        String url = modelsUrl(baseUrl);
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(REQUEST_TIMEOUT)
                .header("Authorization", "Bearer " + apiKey)
                .header("Accept", "application/json")
                .GET()
                .build();
        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BizException(502, "拉取模型列表被中断，请重试");
        } catch (IOException | RuntimeException e) {
            log.warn("[模型目录] 请求失败 url={}：{}", url, e.toString());
            String detail = e.getMessage() == null || e.getMessage().isBlank()
                    ? e.getClass().getSimpleName() : e.getMessage();
            throw new BizException(502, "拉取模型列表失败（网络/地址不可达）：" + detail);
        }
        if (response.statusCode() / 100 != 2) {
            String detail = abbreviate(response.body());
            throw new BizException(502, "拉取模型列表失败：HTTP " + response.statusCode()
                    + (detail.isBlank() ? "" : "（" + detail + "）"));
        }
        List<String> models = parse(response.body());
        log.info("[模型目录] 拉取成功 url={} 模型数={}", url, models.size());
        return models;
    }

    /**
     * Base URL → 清单地址：去掉结尾斜杠；已经以 /models 结尾则不再追加。
     *
     * @param baseUrl 供应商 Base URL
     *
     * @return {@code .../models} 地址
     */
    static String modelsUrl(String baseUrl) {
        String base = baseUrl.trim();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base.endsWith("/models") ? base : base + "/models";
    }

    /**
     * 解析模型清单响应（兼容 data / models / 裸数组三种形态）。
     *
     * @param body 响应体
     *
     * @return 去重排序后的模型 ID 列表
     *
     * @throws BizException 502 无法解析
     */
    static List<String> parse(String body) {
        if (body == null || body.isBlank()) {
            return List.of();
        }
        JsonNode root;
        try {
            root = MAPPER.readTree(body);
        } catch (RuntimeException e) {
            throw new BizException(502, "模型列表返回的不是合法 JSON，无法识别该供应商的清单格式");
        }
        Set<String> ids = new LinkedHashSet<>();
        collect(root.path("data"), ids);
        collect(root.path("models"), ids);
        if (root.isArray()) {
            collect(root, ids);
        }
        List<String> out = new ArrayList<>(ids);
        out.sort(String::compareToIgnoreCase);
        return out;
    }

    /** 从数组节点里收集 id/name（字符串元素直接当模型名） */
    private static void collect(JsonNode array, Set<String> ids) {
        if (array == null || !array.isArray()) {
            return;
        }
        for (JsonNode node : array) {
            if (node == null) {
                continue;
            }
            if (node.isTextual()) {
                add(ids, node.asText());
                continue;
            }
            add(ids, text(node, "id"));
            add(ids, text(node, "name"));
            add(ids, text(node, "model"));
        }
    }

    /** 取文本字段（缺失/非文本返回 null） */
    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value == null || value.isMissingNode() || value.isNull() || !value.isTextual()
                ? null : value.asText();
    }

    /** 非空才加入（去首尾空白） */
    private static void add(Set<String> ids, String value) {
        if (value == null) {
            return;
        }
        String v = value.trim();
        if (!v.isEmpty()) {
            ids.add(v);
        }
    }

    /** 错误详情截断（避免把整段 HTML 错误页回显给前端） */
    private static String abbreviate(String text) {
        if (text == null) {
            return "";
        }
        String oneLine = text.replaceAll("\\s+", " ").trim();
        return oneLine.length() <= 160 ? oneLine : oneLine.substring(0, 160) + "…";
    }
}
