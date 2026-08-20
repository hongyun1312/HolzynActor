package com.holzyn.actor.domain.world.vo;

import lombok.Data;
import java.time.LocalDateTime;

/**
 * 世界时钟视图对象（P4-2 世界时钟 API 返回）。
 * <p>职责：向前端暴露项目游戏时钟状态——速率/锚点/暂停开关 + 实时换算的游戏时刻
 * （gameHour 自锚点起的游戏小时数、day 第几天、hourOfDay 当日小时、periodText 时段描述）+ 最近推进摘要。</p>
 * <p>所属模块：model/vo（视图对象层）</p>
 */
@Data
public class WorldClockVO {

    /** 项目 ID */
    private Long projectId;

    /** 速率：每真实小时推进的游戏小时数（默认 24=1真实小时1游戏日） */
    private Integer rate;

    /** 真实时刻锚点 */
    private LocalDateTime worldStartAt;

    /** 锚点对应的游戏起始时刻（小时数） */
    private Long worldStartGameHour;

    /** 当前游戏时刻（自锚点起的游戏小时数） */
    private Long gameHour;

    /** 当前游戏时刻（自锚点起的游戏<b>总秒数</b>，秒级换算，vP5-7.11） */
    private Long gameSecond;

    /** 历法名（世界观名+历，缺省「世界历」，vP5-7.11） */
    private String calendarName;

    /** 世界历完整时间文本：「XX历 YYYY年MM月DD日 HH时MM分SS秒」（vP5-7.11） */
    private String gameTimeText;

    /** 暂停时刻（真实时刻；未暂停 null，vP5-7.11 暂停冻结用） */
    private LocalDateTime pausedAt;

    /** 当前第几天（gameHour/24+1） */
    private Long day;

    /** 当日小时（0-23） */
    private Integer hourOfDay;

    /** 时段描述（深夜/清晨/上午/正午/下午/傍晚/夜晚） */
    private String periodText;

    /** 最近一次模拟推进的真实时刻 */
    private LocalDateTime lastSimTime;

    /** 最近推进到的游戏时刻（小时数） */
    private Long lastGameHour;

    /** 暂停开关：0推进/1暂停 */
    private Integer paused;

    /** 最近推进摘要 */
    private String lastSummary;
}
