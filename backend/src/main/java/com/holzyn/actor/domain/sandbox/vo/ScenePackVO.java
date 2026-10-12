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

package com.holzyn.actor.domain.sandbox.vo;

import lombok.Data;

/**
 * 沙盘资产数据包摘要视图对象（世界沙盘 S3 补全，数据包列表）。
 * <p>职责：向沙盘页/导入流程展示 uploads/scene-assets/ 下可用数据包
 * （pack.id/名称/版本/描述/作者/资产数），数据来自各包 manifest.json 的 pack 段。</p>
 */
@Data
public class ScenePackVO {

    /** 数据包 ID（目录名，如 sandbox-xianxia-v1） */
    private String packId;

    /** 数据包名（manifest.pack.name） */
    private String name;

    /** 版本（manifest.pack.version） */
    private String version;

    /** 描述（manifest.pack.description） */
    private String description;

    /** 作者（manifest.pack.author） */
    private String author;

    /** 资产数（manifest.assets 长度） */
    private int assetCount;

    /** 单位（固定 meter） */
    private String unit;
}
