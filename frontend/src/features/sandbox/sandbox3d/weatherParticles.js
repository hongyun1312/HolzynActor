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

/**
 * 世界沙盘 3D · 天气粒子层（世界沙盘 S4 · 环境可视化，features/sandbox/sandbox3d）。
 * <p>职责：按世界帧天气条件渲染雨/雪粒子（THREE.Points 单 draw call）——</p>
 * <ul>
 * <li>雨：淡蓝粒子高速下落 + 轻微侧飘；</li>
 * <li>雪：白色粒子缓降 + 正弦摆荡（随风摇摆感）；</li>
 * <li>晴/阴/未知：无粒子（隐藏）。</li>
 * </ul>
 * <p>覆盖区域 = 场景正方形（size×size 中心化），粒子在区域顶部随机生成、落到底部回卷，
 * 数量级 2k 级（JS 逐帧位移算术，SwiftShader 下无压力）。</p>
 * <p>生命周期：setWeather(condition) 切换/关闭 → update(dt) 每帧推进 → dispose() 释放。</p>
 */
import * as THREE from 'three'

/** 雨粒子数量（100m 场景密度；500m 场景按面积比例可放大，本版固定够用） */
const RAIN_COUNT = 2500
/** 雪粒子数量 */
const SNOW_COUNT = 1400

/** 粒子分布高度（米，区域顶部之上） */
const PARTICLE_TOP = 16
/** 粒子回卷高度（低于该值回到顶部） */
const PARTICLE_BOTTOM = -1

/** 雨：下落速度（米/秒）与侧飘 */
const RAIN_FALL = 24
const RAIN_DRIFT = 1.6
/** 雪：缓降速度与摆荡幅度/频率 */
const SNOW_FALL = 1.4
const SNOW_SWAY_AMP = 0.9
const SNOW_SWAY_FREQ = 1.8

/**
 * 天气粒子层。
 */
export class WeatherParticleLayer {
  /**
   * @param {THREE.Scene} scene 目标场景
   *
   * @param {number} size 场景边长（米，正方形）
   */
  constructor(scene, size) {
    this.scene = scene
    this.size = size || 500
    this.points = null
    this.geometry = null
    this.positions = null
    this.phase = null
    this.mode = null // 'rain' | 'snow' | null（null=无粒子）
    this.count = 0
  }

  /**
   * 切换天气（晴/阴/未知 → 关闭；雨 → 雨粒子；雪 → 雪粒子）。
   *
   * @param {string|null|undefined} condition 天气条件（后端帧 weather condition）
   */
  setWeather(condition) {
    const mode = condition === '雨' ? 'rain' : condition === '雪' ? 'snow' : null
    if (mode === this.mode) return
    this.clear()
    this.mode = mode
    if (mode === 'rain') this.build('rain', RAIN_COUNT, 0x9db8e8, 0.5)
    else if (mode === 'snow') this.build('snow', SNOW_COUNT, 0xffffff, 0.85)
  }

  /** 构建粒子系统（Points 单 draw call） */
  build(mode, count, color, opacity) {
    const half = this.size / 2
    this.count = count
    this.positions = new Float32Array(count * 3)
    this.phase = new Float32Array(count)
    for (let i = 0; i < count; i++) {
      this.positions[i * 3] = (Math.random() * 2 - 1) * half
      this.positions[i * 3 + 1] = PARTICLE_BOTTOM + Math.random() * (PARTICLE_TOP - PARTICLE_BOTTOM)
      this.positions[i * 3 + 2] = (Math.random() * 2 - 1) * half
      this.phase[i] = Math.random() * Math.PI * 2
    }
    this.geometry = new THREE.BufferGeometry()
    this.geometry.setAttribute('position', new THREE.BufferAttribute(this.positions, 3))
    const material = new THREE.PointsMaterial({
      color,
      size: mode === 'rain' ? 0.14 : 0.3,
      transparent: true,
      opacity,
      depthWrite: false,
      sizeAttenuation: true
    })
    this.points = new THREE.Points(this.geometry, material)
    this.points.frustumCulled = false // 粒子散布全区，禁用视锥裁剪防整组消失
    this.scene.add(this.points)
  }

  /**
   * 每帧推进（雨：快速下落+侧飘；雪：缓降+正弦摆荡；越界回卷）。
   *
   * @param {number} dt 帧间隔（真实秒）
   */
  update(dt) {
    if (!this.points || !this.positions) return
    const half = this.size / 2
    const n = this.count
    const isRain = this.mode === 'rain'
    for (let i = 0; i < n; i++) {
      const j = i * 3
      if (isRain) {
        this.positions[j + 1] -= RAIN_FALL * dt
        this.positions[j] += (Math.random() - 0.5) * RAIN_DRIFT * dt
      } else {
        this.positions[j + 1] -= SNOW_FALL * dt
        this.phase[i] += SNOW_SWAY_FREQ * dt
        this.positions[j] = Math.sin(this.phase[i]) * SNOW_SWAY_AMP
      }
      if (this.positions[j + 1] < PARTICLE_BOTTOM) {
        this.positions[j + 1] = PARTICLE_TOP
        this.positions[j] = (Math.random() * 2 - 1) * half
        this.positions[j + 2] = (Math.random() * 2 - 1) * half
      }
    }
    this.geometry.attributes.position.needsUpdate = true
  }

  /** 当前粒子模式（dev 钩子/冒烟断言用） */
  currentMode() {
    return this.mode
  }

  /** 释放 */
  clear() {
    if (this.points) {
      this.scene.remove(this.points)
      this.geometry.dispose()
      this.points.material.dispose()
      this.points = null
      this.geometry = null
      this.positions = null
      this.phase = null
      this.mode = null
      this.count = 0
    }
  }

  /** 图层卸载 */
  dispose() {
    this.clear()
    this.scene = null
  }
}
