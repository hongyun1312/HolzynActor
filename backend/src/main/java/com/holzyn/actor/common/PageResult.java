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

package com.holzyn.actor.common;

import lombok.Data;
import java.util.List;

/**
 * 统一分页响应结果。
 * <p>职责：封装列表型 API 的分页返回数据，包含数据列表与分页元信息。</p>
 *
 * @param <T>列表元素类型（如 TaskVO、ProjectVO 等）
 */
@Data
public class PageResult<T> {

    /** 当前页的数据列表 */
    private List<T> list;

    /** 总记录数 */
    private long total;

    /** 当前页码 */
    private int page;

    /** 每页条数 */
    private int size;

    /** 总页数 */
    private int totalPages;

    /**
     * 构造分页结果。
     *
     * @param list 当前页数据列表
     *
     * @param total 总记录数
     *
     * @param page 当前页码
     *
     * @param size 每页条数
     *
     * @param totalPages 总页数
     */
    public PageResult(List<T> list, long total, int page, int size, int totalPages) {
        this.list = list;
        this.total = total;
        this.page = page;
        this.size = size;
        this.totalPages = totalPages;
    }

    /**
     * 工厂方法：从列表和分页参数快速构造分页结果。
     * <p>自动计算总页数（向上取整）。</p>
     *
     * @param list 当前页数据列表
     *
     * @param total 总记录数
     *
     * @param page 当前页码
     *
     * @param size 每页条数
     *
     * @return 填充完成的 PageResult 对象
     */
    public static <T> PageResult<T> of(List<T> list, long total, int page, int size) {
        int totalPages = size > 0 ? (int) Math.ceil((double) total / size) : 0;
        return new PageResult<>(list, total, page, size, totalPages);
    }
}