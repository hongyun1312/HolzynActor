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

package com.holzyn.actor.domain.unity.ws;

import com.holzyn.actor.domain.unity.config.UnityProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.util.Map;

/**
 * Unity WebSocket 通道配置（U 轨道 U1 服务端对接层，）——
 * 注册 {@code /ws/unity/{projectId}} 原生 WebSocketHandler（不经 STOMP，单实例原型最简正解）。
 * <p>握手拦截器：从 URI 路径解析 projectId 写入会话属性（{@link UnityWsHandler#ATTR_PROJECT_ID}），
 * 处理器据此做项目校验/注册/激活。</p>
 * <p>跨域：本地单用户应用 + Unity 原生客户端（C# ClientWebSocket 无 Origin 头）+ 调试前端
 * dev（5175 跨源连接 18080），放开 Origin 模式（不做浏览器跨站防护场景；如需公网暴露须收紧）。</p>
 */
@Configuration
@EnableWebSocket
@RequiredArgsConstructor
public class UnityWsConfig implements WebSocketConfigurer {

    private final UnityWsHandler unityWsHandler;
    private final UnityProperties props;

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        if (!props.isEnabled()) {
            return;
        }
        registry.addHandler(unityWsHandler, props.getWsPath() + "/{projectId}")
                .addInterceptors(new ProjectHandshakeInterceptor())
                // 本地单用户应用：Unity 原生客户端（无 Origin）+ 调试前端 dev（5175 跨源）均需放行；
                // 公网暴露场景须按部署收紧（勿开公网）
                .setAllowedOriginPatterns("*");
    }

    /**
     * 握手拦截器：/ws/unity/{projectId} → 解析 projectId 放入会话属性。
     */
    public static class ProjectHandshakeInterceptor implements HandshakeInterceptor {

        @Override
        public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                       WebSocketHandler wsHandler, Map<String, Object> attributes) {
            try {
                String path = request.getURI().getPath();
                if (path == null) {
                    return false;
                }
                int idx = path.lastIndexOf('/');
                if (idx < 0 || idx == path.length() - 1) {
                    return false;
                }
                attributes.put(UnityWsHandler.ATTR_PROJECT_ID, Long.parseLong(path.substring(idx + 1)));
                return true;
            } catch (NumberFormatException e) {
                return false;
            }
        }

        @Override
        public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler wsHandler, Exception exception) {
            // 无需后处理
        }
    }
}
