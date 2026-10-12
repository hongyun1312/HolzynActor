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

package com.holzyn.actor.domain.account.controller;

import com.holzyn.actor.common.R;
import com.holzyn.actor.domain.account.service.LocalAccountService;
import com.holzyn.actor.domain.account.vo.LocalAccountVO;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 本地个人账户控制器（本地单用户模式，替代原登录/鉴权接口）。
 * <p>职责：提供本地账户的读取/保存/完成首次向导/会话信息接口：
 * GET /api/local-account 获取账户（含 onboarded 标记）；
 * PUT /api/local-account 保存/更新账户（所有字段选填，onboarded 置 1 视为完成向导）；
 * POST /api/local-account/onboarded 单独标记首次向导完成；
 * GET /api/local-account/me 返回会话信息（供前端判断是否已完成首次设置，替代原 /api/auth/me）。</p>
 */
@RestController
@RequestMapping("/api/local-account")
@RequiredArgsConstructor
public class LocalAccountController {

    private final LocalAccountService localAccountService;

    /**
     * 获取本地账户（不存在时惰性创建空档案返回）。
     *
     * @return 本地账户 VO（含 onboarded 标记）
     */
    @GetMapping
    public R<LocalAccountVO> get() {
        return R.ok(localAccountService.toVO(localAccountService.getOrCreate()));
    }

    /**
     * 保存/更新本地账户（首次向导提交与设置页编辑共用）。
     * <p>约定：请求体各字段选填；传入的字段以非 null 覆盖；onboarded=1 一并标记完成首次向导。</p>
     *
     * @param vo 本地账户请求内容
     *
     * @return 保存后的 VO
     */
    @PutMapping
    public R<LocalAccountVO> save(@RequestBody LocalAccountVO vo) {
        return R.ok(localAccountService.save(vo));
    }

    /**
     * 单独标记首次设置向导完成（跳过设置时的「以后再说」入口）。
     *
     * @return 更新后的 VO
     */
    @PostMapping("/onboarded")
    public R<LocalAccountVO> markOnboarded() {
        return R.ok(localAccountService.markOnboarded());
    }

    /**
     * 会话信息（替代原 /api/auth/me）：返回是否已完成首次设置 + 本地账户信息。
     */
    @GetMapping("/me")
    public R<Map<String, Object>> me() {
        LocalAccountVO vo = localAccountService.toVO(localAccountService.getOrCreate());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("onboarded", Integer.valueOf(1).equals(vo.getOnboarded()));
        body.put("account", vo);
        return R.ok(body);
    }

    /**
     * 记录「上次所在项目」（世界沙盘 S1：应用启动直达上次项目）。
     * <p>前端在进入项目空间时调用；请求体 { projectId }；只写不清（返回画廊不清空）。</p>
     *
     * @param body 请求体 { projectId }
     *
     * @return 更新后的 VO
     */
    @PostMapping("/last-project")
    public R<LocalAccountVO> setLastProject(@RequestBody Map<String, Object> body) {
        Long projectId = null;
        Object raw = body == null ? null : body.get("projectId");
        if (raw instanceof Number n) {
            projectId = n.longValue();
        } else if (raw != null) {
            projectId = Long.valueOf(String.valueOf(raw));
        }
        return R.ok(localAccountService.setLastProject(projectId));
    }
}
