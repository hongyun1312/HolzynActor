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
 * 布局提交的结果 / 单条编辑项。
 *
 * <p>为什么把「拒绝原因」做成一整表而不是抛一个异常：用户一次拖 5 栋房子，
 * 第二栋压路、第四栋落水——他要看到的是<b>每一条为什么不行</b>，而不是一句「保存失败」。
 * 前端据此在对应建筑上打红标，用户改完一条再提交。</p>
 */
@Data
public class LayoutEditResultVO {

    /** 是否整体成功（整批原子：有一条不过就 false） */
    private boolean ok;

    /** 失败/提示信息（成功时为空） */
    private String error;

    /** 是否因为修订号过期被拒（前端据此提示「刷新后重试」） */
    private boolean stale;

    /** 提交后的布局修订号 */
    private int revision;

    /** 成功应用的元素 code */
    private List<String> applied;

    /** 被拒绝的编辑项（逐条原因） */
    private List<Rejected> rejected;

    /** 警告（已自动修正的项，如「已补一条接入路」） */
    private List<String> warnings;

    /** 受影响并已跟随更新的日程段数 */
    private int affectedRoutines;

    /** 受影响并已跟随更新的住所数 */
    private int affectedHomes;

    /** 随院落整组移动的附属建筑数 */
    private int affectedSiblings;

    /**
     * 一条编辑项（前端提交的最小单元）。
     */
    @Data
    public static class Edit {

        /** 要素 code */
        private String code;

        /** 新中心 X（米；null=不变） */
        private Double x;

        /** 新中心 Z（米；null=不变） */
        private Double z;

        /** 新朝向（度；null=不变） */
        private Double rotation;

        /** 是否整院联动（默认 true） */
        private Boolean moveGroup;
    }

    /**
     * 一条拒绝原因。
     */
    @Data
    public static class Rejected {

        /** 要素 code */
        private String code;

        /** 原因（人话：「压住 中街」「与 沈家大宅 重叠」） */
        private String reason;
    }
}
