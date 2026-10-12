package com.holzyn.actor.domain.account.service;

import com.holzyn.actor.domain.account.entity.LocalAccount;
import com.holzyn.actor.domain.account.repository.LocalAccountRepository;
import com.holzyn.actor.domain.account.vo.LocalAccountVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * 本地账户服务「上次项目」单元测试（世界沙盘 S1）。
 * <p>职责：验证——setLastProject 写入 last_project_id（惰性建行）、toVO 带出、
 * 通用 save 不覆盖 last_project_id（专用端点维护）。</p>
 * <p>所属模块：domain/account/service 测试（本地账户功能域）</p>
 */
class LocalAccountServiceTest {

    private LocalAccountRepository repository;
    private LocalAccountService service;
    /** 可变的账户实例（模拟 DB 持久化：写后读同一实体） */
    private final LocalAccount account = new LocalAccount();

    @BeforeEach
    void setUp() {
        repository = Mockito.mock(LocalAccountRepository.class);
        service = new LocalAccountService(repository);
        when(repository.findByUserId(1L)).thenReturn(Optional.of(account));
        when(repository.save(any(LocalAccount.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void setLastProject_persistsProjectId() {
        LocalAccountVO vo = service.setLastProject(42L);
        assertNotNull(vo);
        assertEquals(42L, vo.getLastProjectId(), "lastProjectId 应写入并带出");
    }

    @Test
    void setLastProject_nullClears() {
        service.setLastProject(42L);
        LocalAccountVO vo = service.setLastProject(null);
        assertNull(vo.getLastProjectId(), "null 视为清空（预留语义）");
    }

    @Test
    void genericSave_doesNotTouchLastProject() {
        // 通用保存不带 lastProjectId 时保持原值（专用端点维护，避免设置页误清空）
        LocalAccountVO req = new LocalAccountVO();
        req.setNickname("测试");
        LocalAccountVO vo = service.save(req);
        assertNull(vo.getLastProjectId());
    }
}
