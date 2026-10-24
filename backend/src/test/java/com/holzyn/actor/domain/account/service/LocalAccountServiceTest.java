package com.holzyn.actor.domain.account.service;

import com.holzyn.actor.domain.account.entity.LocalAccount;
import com.holzyn.actor.domain.account.repository.LocalAccountRepository;
import com.holzyn.actor.domain.account.vo.LocalAccountVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
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
        // 标记类方法走「加行锁读取」入口（防并发整行写互相覆盖），mock 返回同一实体即可
        when(repository.findByUserIdForUpdate(1L)).thenReturn(Optional.of(account));
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

    @Test
    void markGuideCompleted_persistsAndStampsTime() {
        LocalAccountVO vo = service.markGuideCompleted();
        assertEquals(1, vo.getGuideCompleted(), "完成标记应写入并带出");
        assertNotNull(vo.getGuideCompletedAt(), "完成时间应写入（审计用）");
        assertTrue(service.isGuideCompleted(), "isGuideCompleted 应与完成标记同源");
    }

    @Test
    void resetGuide_clearsFlagAndTime() {
        service.markGuideCompleted();
        LocalAccountVO vo = service.resetGuide();
        assertEquals(0, vo.getGuideCompleted(), "重置后应回到未完成（教程中心重看）");
        assertNull(vo.getGuideCompletedAt(), "重置应清空完成时间");
        assertFalse(service.isGuideCompleted());
    }

    @Test
    void genericSave_doesNotOverwriteGuideCompleted() {
        // 通用保存（设置页编辑账户）不得把引导完成标记冲掉——否则「保存资料后重启又出现引导」
        service.markGuideCompleted();
        LocalAccountVO req = new LocalAccountVO();
        req.setGuideCompleted(0);
        req.setNickname("改个名");
        LocalAccountVO vo = service.save(req);
        assertEquals(1, vo.getGuideCompleted(), "guideCompleted 只由专用端点维护，通用保存不得覆盖");
    }

    @Test
    void markLayoutTourSeen_persistsAndResets() {
        LocalAccountVO seen = service.markLayoutTourSeen();
        assertEquals(1, seen.getLayoutTourSeen(), "界面导览已看过标记应写入并带出");
        LocalAccountVO reset = service.resetLayoutTourSeen();
        assertEquals(0, reset.getLayoutTourSeen(), "重置后应回到未看过");
    }

    @Test
    void genericSave_doesNotOverwriteLayoutTourSeen() {
        service.markLayoutTourSeen();
        LocalAccountVO req = new LocalAccountVO();
        req.setLayoutTourSeen(0);
        req.setNickname("再改个名");
        assertEquals(1, service.save(req).getLayoutTourSeen(), "layoutTourSeen 只由专用端点维护");
    }
}
