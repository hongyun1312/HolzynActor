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

package com.holzyn.actor.domain.world.vo;

import lombok.Data;
import java.time.LocalDateTime;

/**
 * 世界时钟视图对象。
 * <p>职责：向前端暴露项目游戏时钟状态——速率/锚点/暂停开关 + 世界时间（gameHour/gameSecond/day/
 * hourOfDay/periodText/gameTimeText）+ 最近推进摘要。</p>
 * <p>时间口径统一：上述世界时间字段一律取<b>引擎推进位置</b>（lastGameSecond）——
 * 真实锚点换算降级为诊断字段（flowingGameSecond/flowingGameHour/clockLagGameSeconds）。</p>
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

    /** 当前游戏时刻（自锚点起的游戏小时数； 起与引擎推进位置同源=世界权威时间） */
    private Long gameHour;

    /** 当前游戏时刻（自纪元起的游戏<b>总秒数</b>； 起与 lastGameSecond 同源=权威世界时间） */
    private Long gameSecond;

    /**
     * 流动换算游戏秒（<b>诊断口径</b>，）：按真实锚点 × 速率推算的「真实流逝对应值」，
     * 不再作为世界时间权威——与 gameSecond 的差值即世界落后真实流逝的量（世界未开启/应用关闭/
     * 引擎被 AI 拖慢期间累积）；长跑观测世界推进是否掉速看此字段
     */
    private Long flowingGameSecond;

    /** 流动换算游戏小时（诊断口径，见 flowingGameSecond） */
    private Long flowingGameHour;

    /**
     * 世界滞后量（游戏秒，）= flowingGameSecond − gameSecond：≥0 表示世界时间落后于
     * 「真实流逝 × 速率」的推算值；世界开启且引擎跟得上时≈0，未开启/被拖慢时持续增大
     */
    private Long clockLagGameSeconds;

    /** 历法名 */
    private String calendarName;

    /** 世界历完整时间文本：「XX历 YYYY年MM月DD日 HH时MM分SS秒」 */
    private String gameTimeText;

    /** 暂停时刻 */
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

    /** 引擎最近推进到的游戏<b>秒</b>级位置 */
    private Long lastGameSecond;

    /** 当前游戏分钟（当日小时内，0-59； 前端实时时钟便捷字段，floor(gameSecond/60)%60） */
    private Integer gameMinute;

    /** 当日游戏分钟（0-1439； 前端实时时钟便捷字段，floor(gameSecond/60)%1440） */
    private Integer gameDayMinute;

    /**
     * 后端发送本载荷的真实毫秒时间戳（实时插值锚点：前端按
     * gameSecond_now = gameSecond + (now - refRealTime)/1000 × rate 本地连续跳动，暂停不跳）
     */
    private Long refRealTime;

    /**
     * 引擎实际推进位置（游戏秒，：与 lastGameSecond 同源——前端世界时钟<b>显示口径切换为
     * 引擎实际位置</b>「世界的实际时间」而非流动时钟换算时间：AI 慢/引擎停顿不虚快）
     */
    private Long engineGameSecond;

    /**
     * 引擎位置插值锚点真实毫秒（：构建 VO 时的时间戳——前端按
     * 插值：单个引擎扫描周期内平滑推进，超出即冻结等待下一次引擎推进——不虚快）
     */
    private Long engineRefRealTime;

    /**
     * tick 队列授权前沿（游戏秒，P0-01 根治新增）：墙钟/Unity 已授权、尚未被世界消费的队列尾。
     * <p>与 {@link #engineGameSecond}（已消费位置）之差 = 未消费积压（{@link #backlogGameSeconds}）——
     * 调试面板「引擎位置 vs 墙钟推算位置」的队列口径第二线（叠加既有 flowingGameSecond 诊断线）。</p>
     */
    private Long authorizedTargetSecond;

    /**
     * 未消费积压（游戏秒，P0-01 根治新增）= authorizedTargetSecond − 已消费位置；0=已追平。
     */
    private Long backlogGameSeconds;

    /**
     * 显式丢弃累计（游戏秒，P0-01 根治新增）：队列溢出（容量上限）被显式丢弃、未进入世界历史的部分。
     */
    private Long droppedGameSeconds;

    /**
     * 引擎单次推进插值上限（游戏秒，：= tick-game-minutes×60 默认 300——前端插值钳制值，
     * 保证显示的「实际时间」永不超前引擎位置超过一个感知 tick）
     */
    private Integer engineTickGameSeconds;

    /** 暂停开关：0推进/1暂停 */
    private Integer paused;

    /** 世界持续运行常驻开关（B3）：0 关 / 1 开（存量 NULL 视为 0=关，需手动开启） */
    private Integer worldRunning;

    /** 引擎最近一次真正推进的真实时刻（B3 回补基准，替代活跃窗口） */
    private LocalDateTime lastEngineRunAt;

    /** 最近推进摘要 */
    private String lastSummary;
}
