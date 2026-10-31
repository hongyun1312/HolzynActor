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

package com.holzyn.actor.domain.worldmap.dto;

import lombok.Data;

/**
 * 世界地图生成请求（POST /api/worlds/{id}/world-map/generate）。
 * <p>幂等可重跑：同参数重复调用覆盖式重建（摆放/住所全量重建或保留既有由 regenerateHomes 控制）。</p>
 */
@Data
public class WorldMapGenerateRequest {

    /** 随机种子（空=按项目派生，可复现同项目布局） */
    private Long seed;

    /** 风格覆盖（village/town/forest…；空=沿用场景 styleCode 或 village） */
    private String styleOverride;

    /** 是否重建住所（true=全量重建；false=保留既有住所仅补缺；空=按调用方默认） */
    private Boolean regenerateHomes;
}
