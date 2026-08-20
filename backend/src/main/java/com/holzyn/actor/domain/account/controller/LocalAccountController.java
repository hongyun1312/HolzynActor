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
 * <p>所属模块：domain/account/controller（本地账户功能域-控制器）</p>
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
     *
     * @return { onboarded: boolean, account: LocalAccountVO }
     */
    @GetMapping("/me")
    public R<Map<String, Object>> me() {
        LocalAccountVO vo = localAccountService.toVO(localAccountService.getOrCreate());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("onboarded", Integer.valueOf(1).equals(vo.getOnboarded()));
        body.put("account", vo);
        return R.ok(body);
    }
}
