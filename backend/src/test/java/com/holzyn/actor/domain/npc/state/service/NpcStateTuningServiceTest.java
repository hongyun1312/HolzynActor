package com.holzyn.actor.domain.npc.state.service;

import com.holzyn.actor.common.BizException;
import com.holzyn.actor.domain.npc.state.config.NpcStateProperties;
import com.holzyn.actor.domain.npc.state.service.NpcStateTuningService.TuningRow;
import com.holzyn.actor.domain.settings.entity.ActorSetting;
import com.holzyn.actor.domain.settings.repository.ActorSettingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * NPC 状态模块调参服务单元测试（状态模块 M4，domain/npc/state/service，同包）。
 * <p>职责：验证——注册表全量可调参数（生效值/默认值/覆盖标记）、保存覆盖（写库 + 实时应用
 * bean 生效）、值类型解析（double/int/boolean/非法 400）、单键重置（恢复默认 + 删覆盖）、
 * 一键全部恢复默认、未知键 400、启动加载（覆盖应用到 bean）。</p>
 * <p>设计依据：《NPC状态模块_主开发方案_V1.0》§11（配置）/ §12 M4（调参）。</p>
 */
class NpcStateTuningServiceTest {

    private ActorSettingRepository settingRepository;
    private NpcStateProperties props;
    private NpcStateTuningService service;

    /** 内存覆盖仓库模拟：upsert/delete/exists 可读，模拟真实 DB 语义 */
    private final List<ActorSetting> settingStore = new ArrayList<>();

    @BeforeEach
    void setUp() {
        settingRepository = Mockito.mock(ActorSettingRepository.class);
        props = new NpcStateProperties();
        when(settingRepository.findBySettingKey(anyString())).thenAnswer(inv -> {
            String key = inv.getArgument(0);
            return settingStore.stream().filter(s -> key.equals(s.getSettingKey())).findFirst();
        });
        when(settingRepository.findAllByOrderByIdAsc()).thenAnswer(inv -> new ArrayList<>(settingStore));
        when(settingRepository.existsBySettingKey(anyString())).thenAnswer(inv ->
                settingStore.stream().anyMatch(s -> inv.getArgument(0).equals(s.getSettingKey())));
        when(settingRepository.save(any(ActorSetting.class))).thenAnswer(inv -> {
            ActorSetting s = inv.getArgument(0);
            if (s.getId() == null) {
                s.setId((long) (settingStore.size() + 1));
                settingStore.add(s);
            }
            return s;
        });
        Mockito.doAnswer(inv -> {
            String key = inv.getArgument(0);
            settingStore.removeIf(s -> key.equals(s.getSettingKey()));
            return null;
        }).when(settingRepository).deleteBySettingKey(anyString());
        Mockito.doAnswer(inv -> {
            settingStore.clear();
            return null;
        }).when(settingRepository).deleteAll(Mockito.any());
        service = new NpcStateTuningService(props, settingRepository);
        // 手动触发 @PostConstruct（单测不跑 Spring 容器）
        invokeInit(service);
    }

    /** 反射调用私有 init()（@PostConstruct 在单测容器外不自动执行） */
    private static void invokeInit(NpcStateTuningService target) {
        try {
            java.lang.reflect.Method m = NpcStateTuningService.class.getDeclaredMethod("init");
            m.setAccessible(true);
            m.invoke(target);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // ==================== 注册表与读接口 ====================

    @Test
    void effective_returnsAllGroupsWithDefaults() {
        List<TuningRow> rows = service.effective();
        assertFalse(rows.isEmpty());
        // 覆盖典型参数存在且生效值=默认值（未覆盖）
        TuningRow hunger = rows.stream().filter(r -> "decay.hunger-per-hour".equals(r.key())).findFirst().orElseThrow();
        assertEquals("decay", hunger.group());
        assertEquals("1.2", hunger.value());
        assertEquals("1.2", hunger.defaultValue());
        assertFalse(hunger.overridden());
        // 全量组覆盖：衰减/紧急/健康/恢复/情绪/注入/驱动/历史
        assertTrue(rows.stream().anyMatch(r -> "decay".equals(r.group())));
        assertTrue(rows.stream().anyMatch(r -> "urgency".equals(r.group())));
        assertTrue(rows.stream().anyMatch(r -> "health".equals(r.group())));
        assertTrue(rows.stream().anyMatch(r -> "recovery".equals(r.group())));
        assertTrue(rows.stream().anyMatch(r -> "emotion".equals(r.group())));
        assertTrue(rows.stream().anyMatch(r -> "inject".equals(r.group())));
        assertTrue(rows.stream().anyMatch(r -> "drive".equals(r.group())));
        assertTrue(rows.stream().anyMatch(r -> "history".equals(r.group())));
    }

    // ==================== 保存覆盖 ====================

    @Test
    void save_persistsAndAppliesToBean() {
        String applied = service.save("decay.hunger-per-hour", "0.8");
        assertEquals("0.8", applied);
        assertEquals(0.8d, props.getDecay().getHungerPerHour(), "保存后 bean 应立即生效");
        assertEquals(1, settingStore.size());
        assertEquals("npc-state.decay.hunger-per-hour", settingStore.get(0).getSettingKey());
        assertEquals("0.8", settingStore.get(0).getSettingValue());
        // effective 显示覆盖标记
        TuningRow row = service.effective().stream()
                .filter(r -> "decay.hunger-per-hour".equals(r.key())).findFirst().orElseThrow();
        assertTrue(row.overridden());
        assertEquals("0.8", row.value());
    }

    @Test
    void save_overwritesExisting() {
        service.save("decay.hunger-per-hour", "0.8");
        service.save("decay.hunger-per-hour", "1.0");
        assertEquals(1, settingStore.size(), "同键重复保存应更新而非新增");
        assertEquals("1.0", settingStore.get(0).getSettingValue());
        assertEquals(1.0d, props.getDecay().getHungerPerHour());
    }

    @Test
    void save_booleanAndIntTypes() {
        service.save("decay.taper", "true");
        assertTrue(props.getDecay().isTaper());
        service.save("history.max-per-npc", "240");
        assertEquals(240, props.getHistory().getMaxPerNpc());
    }

    @Test
    void save_invalidValueThrows400() {
        BizException ex = assertThrows(BizException.class,
                () -> service.save("decay.hunger-per-hour", "abc"));
        assertEquals(400, ex.getCode());
        assertTrue(settingStore.isEmpty(), "非法值不应落库");
    }

    @Test
    void save_blankOrUnknownKeyThrows400() {
        assertThrows(BizException.class, () -> service.save("decay.hunger-per-hour", "  "));
        assertThrows(BizException.class, () -> service.save("unknown.key", "1.0"));
    }

    // ==================== 单键重置 ====================

    @Test
    void reset_restoresDefaultAndDeletes() {
        service.save("decay.hunger-per-hour", "0.8");
        String def = service.reset("decay.hunger-per-hour");
        assertEquals("1.2", def, "重置应返回默认值");
        assertEquals(1.2d, props.getDecay().getHungerPerHour(), "bean 应恢复默认");
        assertTrue(settingStore.isEmpty(), "覆盖记录应删除");
        TuningRow row = service.effective().stream()
                .filter(r -> "decay.hunger-per-hour".equals(r.key())).findFirst().orElseThrow();
        assertFalse(row.overridden());
    }

    @Test
    void reset_unknownKeyThrows400() {
        assertThrows(BizException.class, () -> service.reset("unknown.key"));
    }

    // ==================== 一键全部恢复默认 ====================

    @Test
    void resetAll_clearsAllAndRestoresDefaults() {
        service.save("decay.hunger-per-hour", "0.8");
        service.save("emotion.mood-weight", "0.5");
        service.save("history.max-per-npc", "300");
        int count = service.resetAll();
        assertEquals(3, count);
        assertTrue(settingStore.isEmpty());
        assertEquals(1.2d, props.getDecay().getHungerPerHour());
        assertEquals(0.3d, props.getEmotion().getMoodWeight());
        assertEquals(168, props.getHistory().getMaxPerNpc());
        assertTrue(service.effective().stream().noneMatch(TuningRow::overridden));
    }

    // ==================== 启动加载 ====================

    @Test
    void init_loadsPersistedOverrides() {
        // 预置一条覆盖（模拟上次会话保存的）
        ActorSetting s = new ActorSetting();
        s.setSettingKey("npc-state.decay.hunger-per-hour");
        s.setSettingValue("0.5");
        settingStore.add(s);
        // 重新构造服务并触发 init（模拟重启）
        NpcStateTuningService service2 = new NpcStateTuningService(props, settingRepository);
        invokeInit(service2);
        assertEquals(0.5d, props.getDecay().getHungerPerHour(), "启动加载覆盖应应用到 bean");
        TuningRow row = service2.effective().stream()
                .filter(r -> "decay.hunger-per-hour".equals(r.key())).findFirst().orElseThrow();
        assertTrue(row.overridden());
        assertEquals("0.5", row.value());
    }

    @Test
    void init_skipsUnknownKeys() {
        ActorSetting s = new ActorSetting();
        s.setSettingKey("npc-state.unknown.key");
        s.setSettingValue("1.0");
        settingStore.add(s);
        NpcStateTuningService service2 = new NpcStateTuningService(props, settingRepository);
        invokeInit(service2);
        // 未注册键应被跳过，不影响任何已知参数
        assertEquals(1.2d, props.getDecay().getHungerPerHour());
    }
}
