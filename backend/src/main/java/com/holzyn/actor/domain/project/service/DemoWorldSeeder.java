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

package com.holzyn.actor.domain.project.service;

import com.holzyn.actor.domain.project.repository.ActorProjectRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.Map;

/**
 * 示例世界自动注入器（首次建库开箱即用）。
 * <p>职责：应用启动后，若项目表为空（全新数据库）且开关开启，则把内置示例世界
 * （classpath:{@code demo-world/qinghe-village.holzyn}，青禾村成品 .holzyn 包）经既有
 * {@link HolzynImportService#importPackage} 注入数据库——用户首次运行即看到一个
 * 有角色、有地图、有记忆的活村庄，无需手动配置模型造世界。</p>
 * <p><b>幂等</b>：项目表非空 → 跳过（数据库非首次建库）；导入服务自身另按 projectUid 防重。
 * 用户删掉示例世界后再次空库启动会重新注入（「回到初始示例」语义）。</p>
 * <p><b>失败语义</b>：任何失败仅 WARN 不阻断启动（用户可稍后手动导入同名 .holzyn 包）。</p>
 * <p><b>配置</b>：开关 {@code holzyn.actor.demo-world.seed-enabled}（默认 true，可关）；
 * 资源路径 {@code holzyn.actor.demo-world.resource}（classpath，可覆盖）。
 * 与 {@code WorldGenWorkflowSeeder}/{@code EmotionAnchorSeeder} 同为启动 seed 设施，
 * 零 AI 调用（导入是纯数据还原，RAG 知识同步走提交后异步、失败不阻断）。</p>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class DemoWorldSeeder implements ApplicationRunner {

    /** 内置示例世界默认资源路径（classpath） */
    public static final String DEFAULT_RESOURCE = "demo-world/qinghe-village.holzyn";

    private final HolzynImportService importService;
    private final ActorProjectRepository projectRepository;

    /** 自动注入开关（默认开；测试/无示例世界环境可显式关闭） */
    @Value("${holzyn.actor.demo-world.seed-enabled:true}")
    private boolean seedEnabled;

    /** 示例世界资源路径（classpath，可覆盖） */
    @Value("${holzyn.actor.demo-world.resource:" + DEFAULT_RESOURCE + "}")
    private String resourcePath;

    @Override
    public void run(ApplicationArguments args) {
        try {
            if (!seedEnabled) {
                log.info("[示例世界] 自动注入已关闭（holzyn.actor.demo-world.seed-enabled=false），跳过");
                return;
            }
            long projectCount = projectRepository.count();
            if (projectCount > 0) {
                log.info("[示例世界] 检测到 {} 个项目，数据库非首次建库，跳过示例世界注入", projectCount);
                return;
            }
            Resource resource = new ClassPathResource(resourcePath);
            if (!resource.exists()) {
                log.warn("[示例世界] 内置示例世界资源缺失（classpath:{}），跳过注入（可手动导入）", resourcePath);
                return;
            }
            byte[] bytes;
            try (InputStream in = resource.getInputStream()) {
                bytes = in.readAllBytes();
            }
            Map<String, Object> result = importService.importPackage(bytes, null);
            log.info("[示例世界] 首次建库已注入示例世界：{}（projectId={}，角色 {} · 会话 {}）",
                    result.get("name"), result.get("projectId"),
                    result.get("characterCount"), result.get("conversationCount"));
        } catch (Exception e) {
            log.warn("[示例世界] 注入失败（不阻断启动，可稍后手动导入 demo-world/ 下同名包）: {}", e.getMessage());
        }
    }
}
