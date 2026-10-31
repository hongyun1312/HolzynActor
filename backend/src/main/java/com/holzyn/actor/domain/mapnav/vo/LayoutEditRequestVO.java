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

package com.holzyn.actor.domain.mapnav.vo;

import lombok.Data;

import java.util.List;

/**
 * 布局提交请求体（{@code POST /map/layout/edits}）。
 *
 * <p>为什么带 {@code baseRevision}：地图页可能同时开着两个窗口（或者用户一边拖一边
 * 让世界跑了一轮重生成）。带上「我基于哪一版改的」，服务端就能在版本变了时直接拒绝，
 * 而不是把两份互相矛盾的坐标按提交顺序后写的覆盖先写的。</p>
 */
@Data
public class LayoutEditRequestVO {

    /** 前端拿到的布局修订号（null=不校验，仅用于导入/脚本） */
    private Integer baseRevision;

    /** 编辑项 */
    private List<LayoutEditResultVO.Edit> edits;
}
