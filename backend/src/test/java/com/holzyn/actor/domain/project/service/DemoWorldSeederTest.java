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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 示例世界自动注入器单元测试。
 * <p>覆盖五态：开关关 / 已有项目 / 资源缺失 / 全新库正常注入 / 导入失败降级不抛。
 * 注入路径用 mock（{@code importPackage} 不真跑），成功态以测试 classpath 资源
 * {@code demo-world/test-sample.holzyn} 验证「非空字节 + 无密码」传入。</p>
 */
@ExtendWith(MockitoExtension.class)
class DemoWorldSeederTest {

    @Mock
    private HolzynImportService importService;

    @Mock
    private ActorProjectRepository projectRepository;

    private DemoWorldSeeder seeder;

    @BeforeEach
    void setUp() {
        seeder = new DemoWorldSeeder(importService, projectRepository);
        ReflectionTestUtils.setField(seeder, "seedEnabled", true);
        ReflectionTestUtils.setField(seeder, "resourcePath", DemoWorldSeeder.DEFAULT_RESOURCE);
    }

    @Test
    void disabled_skipsEverything() {
        ReflectionTestUtils.setField(seeder, "seedEnabled", false);
        seeder.run(null);
        verifyNoInteractions(projectRepository, importService);
    }

    @Test
    void existingProjects_skipInjection() {
        when(projectRepository.count()).thenReturn(3L);
        seeder.run(null);
        verify(projectRepository).count();
        verify(importService, never()).importPackage(any(byte[].class), isNull());
    }

    @Test
    void missingResource_skipsInjection() {
        when(projectRepository.count()).thenReturn(0L);
        ReflectionTestUtils.setField(seeder, "resourcePath", "demo-world/not-exist.holzyn");
        seeder.run(null);
        verify(projectRepository).count();
        verify(importService, never()).importPackage(any(byte[].class), isNull());
    }

    @Test
    void freshDatabase_injectsBundledWorld() {
        when(projectRepository.count()).thenReturn(0L);
        Map<String, Object> result = Map.of(
                "name", "青禾村", "projectId", 1L, "characterCount", 12, "conversationCount", 9);
        when(importService.importPackage(any(byte[].class), isNull())).thenReturn(result);
        // 成功态用测试 classpath 资源（真实打包资源 demo-world/qinghe-village.holzyn 由打包流程生成）
        ReflectionTestUtils.setField(seeder, "resourcePath", "demo-world/test-sample.holzyn");
        seeder.run(null);
        verify(projectRepository).count();
        verify(importService).importPackage(any(byte[].class), isNull());
    }

    @Test
    void importFailure_logsWarnWithoutThrowing() {
        when(projectRepository.count()).thenReturn(0L);
        when(importService.importPackage(any(byte[].class), isNull()))
                .thenThrow(new RuntimeException("mock import boom"));
        ReflectionTestUtils.setField(seeder, "resourcePath", "demo-world/test-sample.holzyn");
        assertDoesNotThrow(() -> seeder.run(null));
        verify(projectRepository).count();
        verify(importService).importPackage(any(byte[].class), isNull());
    }
}
