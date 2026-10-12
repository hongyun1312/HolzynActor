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

package com.holzyn.actor.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Web MVC 静态资源映射（世界沙盘 S3 补全，）。
 * <p>职责：把 {@code /uploads/**} URL 映射到上传根目录（{@code holzyn.actor.upload-dir}，
 * 默认仓库根 uploads/，由 {@code DataDirResolver} 统一重定向），使资产数据包
 * （uploads/scene-assets/&lt;pack&gt;/models/*.glb 等）可直接经 HTTP 加载——
 * 前端 dev 经 Vite 代理 {@code /uploads → 8080}，生产 jar 形态同源直接访问。</p>
 * <p>背景：世界沙盘 S5 设计「文生 3D 资产目录接入（uploads/scene-assets + actor_scene_asset）」；
 * 本轮 S3 补全先把资产目录服务化（GLB 运行时读取，不打进 jar，jar 体积保持 ~193MB）。</p>
 * <p>注意：uploads/ 已在 .gitignore（运行时资产不入库）；zip 数据包（data/sandbox-xianxia-v1.zip）
 * 随仓库提交作为权威源，由导入脚本解包到 uploads/scene-assets/ 后经本映射服务。</p>
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    /** 上传根目录（application.yml holzyn.actor.upload-dir；DataDirResolver 已重定向到仓库根 uploads/） */
    private final String uploadDir;

    public WebMvcConfig(@Value("${holzyn.actor.upload-dir:./uploads}") String uploadDir) {
        this.uploadDir = uploadDir;
    }

    /**
     * 注册 /uploads/** → file:&lt;upload-dir&gt;/ 静态映射（目录不存在时也注册，请求 404 由框架兜底）。
     *
     * @param registry 资源处理器注册表
     */
    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        Path dir = Paths.get(uploadDir).toAbsolutePath().normalize();
        // 结尾补分隔符（file: 前缀 + 目录必须以 / 结尾才按目录解析）
        String location = "file:" + dir.toString().replace('\\', '/') + "/";
        registry.addResourceHandler("/uploads/**").addResourceLocations(location);
    }
}
