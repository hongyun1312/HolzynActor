package com.holzyn.actor.domain.mapnav.service;

import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * AI 意图驻留锁。
 *
 * <p><b>为什么需要它</b>：实测里 AI 决定「顺路去米行讨碗水喝」，权威路径建成、人也走到了
 * （{@code actor_npc_path} status=arrived），但 100 游戏秒后日程就把他叫走——AI 的意图只是
 * 给日程让了 100 秒的路。世界看着在跑，人却从来没有「因为要去那儿而真的在那儿待一会儿」。</p>
 *
 * <p><b>口径</b>：一次 AI 行动成功建立权威路径后，登记「到站时刻 + 驻留窗口」；日程推进
 * （{@link NpcMovementService#tick}）在窗口内不发起新的日程移动，只维持心跳。窗口过期自动失效，
 * 不需要清理线程；世界重开后锁是空的（进程内状态，与既有「引擎内存态」一致）。</p>
 *
 * <p><b>不锁什么</b>：不阻止 AI 自己的新决策（新意图会覆盖旧锁），不阻止打断（被搭话、
 * 生理紧急），也不阻止玩家/调试摆位——只阻止「日程按表把人拖走」这一种。</p>
 */
@Service
public class NpcIntentLock {

    /** 一次意图驻留。{@code untilGameSecond} 之前，日程不抢人。 */
    public record Hold(String npcRef, long untilGameSecond, String featureCode, String action,
                       String npcType, long sinceGameSecond) {

        /** 剩余游戏秒（<=0 表示已过期）。 */
        public long remaining(long gameSecond) {
            return untilGameSecond - gameSecond;
        }
    }

    private final Map<String, Hold> holds = new ConcurrentHashMap<>();

    private static String key(Long projectId, String npcRef) {
        return projectId + ":" + npcRef;
    }

    /**
     * 登记/覆盖一次意图驻留。
     *
     * @param projectId       项目
     * @param npcRef          角色 ref（npc:12）
     * @param untilGameSecond 驻留截止游戏秒（到站时刻 + 窗口）
     * @param featureCode     目的要素 code（可空）
     * @param action          AI 原始动作文本（日志/排查用）
     * @param npcType         角色类型（special/ordinary）
     */
    public void hold(Long projectId, String npcRef, long untilGameSecond, String featureCode,
                     String action, String npcType) {
        if (projectId == null || npcRef == null || npcRef.isBlank()) {
            return;
        }
        holds.put(key(projectId, npcRef),
                new Hold(npcRef, untilGameSecond, featureCode, action, npcType, 0L));
    }

    /**
     * 当前是否处于驻留窗口内。
     *
     * @return 命中且未过期返回该 Hold，否则 null（顺手清掉过期项，避免无界增长）
     */
    public Hold holding(Long projectId, String npcRef, long gameSecond) {
        if (projectId == null || npcRef == null) {
            return null;
        }
        String k = key(projectId, npcRef);
        Hold h = holds.get(k);
        if (h == null) {
            return null;
        }
        if (gameSecond >= h.untilGameSecond()) {
            holds.remove(k, h);
            return null;
        }
        return h;
    }

    /** 主动清除（例如角色被打断、被摆位、或新的日程段已开始）。 */
    public void clear(Long projectId, String npcRef) {
        if (projectId == null || npcRef == null) {
            return;
        }
        holds.remove(key(projectId, npcRef));
    }

    /** 清空（测试/世界切换用）。 */
    public void clearAll() {
        holds.clear();
    }

    /** 当前持有锁的角色数（只读观测）。 */
    public int size() {
        return holds.size();
    }
}
