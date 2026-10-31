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

package com.holzyn.actor.domain.worldstore;

/**
 * 世界在目录库里的状态（{@code actor_project.world_status}，V1.1.2-beta.1）。
 *
 * <p>为什么必须有这个状态而不是只看"目录在不在"：分档后磁盘与索引是两份事实，随时可能不一致——
 * 用户手动删了目录、拷进来一个高版本存档、进程被强杀留下锁文件。状态列是把这些不一致
 * <b>显式记下来</b>的唯一位：列表页据此灰显/提示，挂载入口据此拒绝，而不是等到用户点进去
 * 才在半路失败。</p>
 *
 * <p>状态机：</p>
 * <pre>
 * closed ──open──▶ mounted ──close──▶ closed
 *   │                                  ▲
 *   ├─目录缺失──────▶ missing ──────────┘（目录被放回来 + 重启收编）
 *   ├─清单/版本不符──▶ incompatible
 *   └─库文件损坏/锁冲突▶ broken
 * </pre>
 *
 * <p>只有 {@link #CLOSED} 允许挂载：其余状态打开也只会以更糟的方式失败。</p>
 *
 * <p>所属模块：domain/worldstore（世界存档存储）</p>
 */
public enum WorldStatus {

    /** 冷存档：文件未打开、世界时间冻结、可挂载 */
    CLOSED("closed", "可继续"),

    /** 已挂载：库文件被本进程独占打开 */
    MOUNTED("mounted", "运行中"),

    /** 损坏：库文件打不开、锁残留、清单不可解析 */
    BROKEN("broken", "已损坏"),

    /** 目录缺失：索引行还在，磁盘目录不见了（不删行——可能只是被临时移走） */
    MISSING("missing", "目录缺失"),

    /** 版本不兼容：清单 formatVersion 与当前不符（拒绝静默升级） */
    INCOMPATIBLE("incompatible", "版本不兼容");

    private final String code;

    private final String label;

    WorldStatus(String code, String label) {
        this.code = code;
        this.label = label;
    }

    /** 落库值（小写；与 V101 的默认值 {@code 'closed'} 一致） */
    public String code() {
        return code;
    }

    /** 中文短标签（列表页直接显示，不再前端另写一套映射） */
    public String label() {
        return label;
    }

    /** 是否允许挂载（只有冷存档允许） */
    public boolean mountable() {
        return this == CLOSED;
    }

    /**
     * 解析落库值。
     *
     * <p>未知值一律回退 {@link #CLOSED} 而不是抛错：状态列是诊断信息，不是路由依据；
     * 让列表页因为一个拼错的状态值整页 500 没有意义（读不到就按最宽松处理，
     * 真正的失败会在挂载时以明确错误暴露）。</p>
     *
     * @param raw 落库值（可空）
     *
     * @return 状态枚举
     */
    public static WorldStatus of(String raw) {
        if (raw == null || raw.isBlank()) {
            return CLOSED;
        }
        String v = raw.trim().toLowerCase(java.util.Locale.ROOT);
        for (WorldStatus s : values()) {
            if (s.code.equals(v)) {
                return s;
            }
        }
        return CLOSED;
    }
}
