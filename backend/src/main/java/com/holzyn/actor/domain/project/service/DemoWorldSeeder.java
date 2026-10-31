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
import com.holzyn.actor.domain.worldstore.WorldArchiveService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.io.InputStream;

/**
 * 首次建库时注入内置示例世界（成品存档，用户一打开就能看见完整世界）。
 *
 * <p><b>V1.1.2-beta.1 M6 变更</b>：以前是"把一个 v2 逐表 JSON 包灌进单库"，分档存储之后那条链路
 * 已经被删除（设计 §3.11 Q4：不再保留 v2 导入）。现在内置资源是<b>一个 v3 存档包</b>
 * （{@code .holzyn} = 世界目录 zip），直接走 {@link WorldArchiveService#importPackage}——
 * 与用户手动"导入存档"是同一条代码路径，因此导入正确性（uid 冲突改写、{@code project_id} 改写、
 * 清单来源标注）在每次全新安装时都会被真实验证一次。</p>
 *
 * <p>准入：只在目录库"一行世界都没有"时执行。已有任何世界（用户的真实存档）时一律跳过——
 * 示例世界是引导，不是"每次启动补一个"。</p>
 *
 * <p><b>内置包默认不随仓库分发（M6）</b>：v2 的 {@code demo-world/qingyu-town.holzyn}（0.11 MB 逐表 JSON）
 * 已经无法被本版本导入，而 v3 包 = 整个世界目录，实测同一个世界是 ~12 MB（世界库本身已压缩，
 * 再 zip 也压不动），放进仓库会让历史体积翻倍。<b>行为</b>：资源缺失时打一条 WARN 并跳过
 * （不阻断启动），用户可用存档选单的「导入存档」导入任意 {@code .holzyn}。
 * 发布流程若需要开机即有示例世界，把 v3 包放到 {@code classpath:demo-world/qingyu-town.holzyn} 即可生效。</p>
 *
 * <p>配置：开关 {@code holzyn.actor.demo-world.seed-enabled}（默认 true，可关）；
 * 资源路径 {@code holzyn.actor.demo-world.resource}（classpath，可覆盖）。</p>
 *
 * <p>所属模块：project 域的 service 包（历史位置，与 ProjectService 同包；前端路由与 HTTP 前缀早已是 /worlds）</p>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class DemoWorldSeeder implements ApplicationRunner {

    /** 内置示例世界包（v3 存档包） */
    public static final String DEFAULT_RESOURCE = "demo-world/qingyu-town.holzyn";

    /** 存档导入（v3） */
    private final WorldArchiveService archiveService;

    /** 目录库索引（数一行都没有才注入） */
    private final ActorProjectRepository projectRepository;

    /** 是否允许自动注入示例世界 */
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
                log.info("[示例世界] 检测到 {} 个世界，非首次建库，跳过示例世界注入", projectCount);
                return;
            }
            Resource resource = new ClassPathResource(resourcePath);
            if (!resource.exists()) {
                log.warn("[示例世界] 内置示例世界包缺失（classpath:{}），跳过注入（可用「导入存档」手动导入）", resourcePath);
                return;
            }
            WorldArchiveService.ImportResult result;
            try (InputStream in = resource.getInputStream()) {
                // userId 固定 1：首次建库时本地账户尚未创建（同一批 seed 里账户由首启向导建）
                result = archiveService.importPackage(in, resourcePath, 1L);
            }
            log.info("[示例世界] 首次建库已注入示例世界：{}（worldId={}，角色 {}，世界时间 {}，耗时 {}ms）",
                    result.name(), result.worldId(), result.characterCount(),
                    result.gameSecond(), result.elapsedMs());
        } catch (Exception e) {
            log.warn("[示例世界] 注入失败（不阻断启动，可稍后用「导入存档」手动导入）: {}", e.getMessage());
        }
    }
}
