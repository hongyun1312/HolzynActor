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

import org.springframework.stereotype.Component;

/**
 * 「当前挂载世界」的唯一持有者（V1.1.2-beta.1）。
 *
 * <p>为什么单独抽一个小对象而不是让路由数据源直接依赖会话服务：会话服务需要数据源工厂，
 * 数据源工厂要读路径，路由数据源要读"当前挂载的是哪个世界"——三者互相引用会立刻形成
 * Bean 环（本仓库实测过 {@code physioNeedService → npcStateService → Router} 的环，
 * 只有真实启动才炸）。把"当前挂载谁"抽成零依赖的值对象，环就断在它这里。</p>
 *
 * <p>{@code volatile} 是必须的：挂载/卸载发生在 HTTP 线程，读取发生在引擎线程、SSE 线程、
 * 定时任务线程。没有 volatile 时读线程可能长期看到旧的 uid——这比其他地方的可见性问题
 * 更危险，因为它会让写入落到**上一个**世界的库里。</p>
 *
 * <p>所属模块：domain/worldstore（世界存档存储）</p>
 */
@Component
public class MountedWorld {

    /** 当前挂载的世界 uid；null = 未挂载（所有访问走目录库） */
    private volatile String uid;

    /**
     * 当前挂载的世界 uid。
     *
     * @return uid，未挂载时为 {@code null}
     */
    public String uid() {
        return uid;
    }

    /** 是否有世界处于挂载状态 */
    public boolean isMounted() {
        return uid != null;
    }

    /**
     * 标记为已挂载（只允许会话服务在完成迁移与镜像重建后调用）。
     *
     * @param worldUid 世界 uid
     */
    void mount(String worldUid) {
        this.uid = WorldPaths.requireSafeUid(worldUid);
    }

    /** 清除挂载标记（必须在关闭世界库连接池**之前**调用，避免新请求再连上即将关闭的池） */
    void unmount() {
        this.uid = null;
    }

    /**
     * 是否就是这个世界处于挂载状态。
     *
     * @param worldUid 待比较的 uid
     *
     * @return true = 已挂载且为该世界
     */
    public boolean isMounted(String worldUid) {
        return uid != null && uid.equals(worldUid);
    }
}
