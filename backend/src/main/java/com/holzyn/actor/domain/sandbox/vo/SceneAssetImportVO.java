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
 * 沙盘资产包导入结果视图对象（世界沙盘 S3 补全，POST /assets/import）。
 * <p>职责：返回一次资产包导入的统计（新建/更新/跳过），供前端/调试确认导入完成。</p>
 */
@Data
public class SceneAssetImportVO {

    /** 数据包 ID */
    private String packId;

    /** 数据包名 */
    private String packName;

    /** 本次新建登记数 */
    private int registered;

    /** 本次更新（字段同步）数 */
    private int updated;

    /** 跳过数（缺 asset_id 等非法行） */
    private int skipped;

    /** manifest 总资产数 */
    private int total;
}
