package com.holzyn.actor.domain.growth.persona;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * PersonaRevisionService 单元测试（O6 轨道 G1b：actor_profile_revision 版本历史）。
 * <p>职责：验证版本快照的追加口径（参数非法不落行 / 失败只告警不抛出）、列表升序、
 * 单版查询；全部为纯逻辑测试（仓库 Mockito）。</p>
 * <p>所属模块：domain/growth/persona（测试）。</p>
 */
@ExtendWith(MockitoExtension.class)
class PersonaRevisionServiceTest {

    @Mock
    private ActorProfileRevisionRepository revisionRepository;

    @InjectMocks
    private PersonaRevisionService service;

    @Test
    @DisplayName("快照追加：合法参数落一行（字段完整透传）")
    void snapshotPersistsRow() {
        service.snapshot(1L, 42L, 3, "{\"schema_version\":2}", "edited", "manual", "manual", "备注");
        verify(revisionRepository).save(any(ActorProfileRevision.class));
    }

    @Test
    @DisplayName("快照追加：profileId 为空或版本号非法时不落行")
    void snapshotSkipsInvalidArgs() {
        service.snapshot(1L, null, 1, "{}", "edited", null, null, null);
        service.snapshot(1L, 42L, 0, "{}", "edited", null, null, null);
        verify(revisionRepository, never()).save(any(ActorProfileRevision.class));
    }

    @Test
    @DisplayName("快照追加：仓库抛异常时只告警不抛出（观测数据不反噬业务）")
    void snapshotSwallowsRepositoryError() {
        when(revisionRepository.save(any(ActorProfileRevision.class)))
                .thenThrow(new RuntimeException("db down"));
        // 不抛异常即为通过（内部 catch + warn）
        service.snapshot(1L, 42L, 1, "{}", "generated", null, null, null);
    }

    @Test
    @DisplayName("版本列表：按版本号升序透传仓库结果")
    void listVersionsAscending() {
        ActorProfileRevision r1 = new ActorProfileRevision();
        r1.setPersonaVersion(1);
        ActorProfileRevision r2 = new ActorProfileRevision();
        r2.setPersonaVersion(2);
        when(revisionRepository.findByProfileIdOrderByPersonaVersionAsc(42L)).thenReturn(List.of(r1, r2));
        List<ActorProfileRevision> out = service.listVersions(42L);
        assertEquals(2, out.size());
        assertSame(r1, out.get(0));
        assertSame(r2, out.get(1));
    }

    @Test
    @DisplayName("版本列表：profileId 为空返回空列表（不查库）")
    void listVersionsNullSafe() {
        assertEquals(0, service.listVersions(null).size());
        verify(revisionRepository, never()).findByProfileIdOrderByPersonaVersionAsc(any());
    }

    @Test
    @DisplayName("单版查询：存在返回该行，不存在返回 null")
    void findVersion() {
        ActorProfileRevision r = new ActorProfileRevision();
        r.setPersonaVersion(2);
        when(revisionRepository.findByProfileIdAndPersonaVersion(42L, 2)).thenReturn(Optional.of(r));
        assertSame(r, service.findVersion(42L, 2));
        when(revisionRepository.findByProfileIdAndPersonaVersion(42L, 9)).thenReturn(Optional.empty());
        assertNull(service.findVersion(42L, 9));
        assertNull(service.findVersion(42L, null));
        assertNull(service.findVersion(null, 1));
    }
}
