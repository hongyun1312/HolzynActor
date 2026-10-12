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
 * 世界沙盘 3D · NPC 名牌/气泡层（世界沙盘 S4 · 信息层，features/sandbox/sandbox3d）。
 * <p>职责：以 CSS2D（HTML 覆盖层，跟随 3D 位置，主方案 §7.3 技术选型）渲染三类信息：</p>
 * <ul>
 * <li><b>头顶名牌</b>（常显）：NPC 名字——特殊金色 / 普通蓝色（与几何标记同色系），
 * 选中白色高亮；只显名字不显身份（S4）；</li>
 * <li><b>NPC 动态气泡</b>（变化时弹出、数秒淡出）：动作 / 状态 / 想法 / 对话 / 事件五类
 * （图标 + 文本，按类配色），同一 NPC 同时最多一个气泡（新气泡替换旧气泡）；</li>
 * <li><b>地点锚定气泡</b>（事件可视化）：无当事人的世界事件在对应地点坐标弹一次性气泡
 * （不绑定 NPC，独立生命周期，到期淡出）。</li>
 * </ul>
 * <p>实现：所有 CSS2DObject 加入目标 THREE.Scene，由 CSS2DRenderer 每帧投影（恒定屏幕大小）；
 * 本层 DOM 容器 pointer-events:none——点击穿透到 WebGL 画布，拾取统一走 Raycaster（S4）。</p>
 * <p>生命周期：setNpcs() 同步名牌 → syncPositions() 每帧跟随标记插值位置 → showBubble()/
 * showLocationBubble() 弹气泡 → setSelected() 名牌高亮 → setSize() 随容器缩放 → dispose() 释放。</p>
 */
import * as THREE from 'three'
import { CSS2DRenderer, CSS2DObject } from 'three/addons/renderers/CSS2DRenderer.js'

/** 名牌中心高度（米，标记高 1.8 + 余量） */
const TAG_Y = 2.7
/** 气泡中心高度（米，名牌上方） */
const BUBBLE_Y = 3.9
/** 气泡默认展示时长（毫秒；S4 「变化时弹出、几秒消失」） */
const BUBBLE_DEFAULT_MS = 4000

/** 气泡类型 → 图标/样式类（颜色在 CSS 中按类定义） */
const BUBBLE_KINDS = {
  action: { icon: '⚒', cls: 'bubble-action' },
  state: { icon: '●', cls: 'bubble-state' },
  thought: { icon: '💭', cls: 'bubble-thought' },
  talk: { icon: '💬', cls: 'bubble-talk' },
  event: { icon: '⚡', cls: 'bubble-event' }
}

/**
 * NPC 名牌/气泡图层。
 */
export class NpcLabelLayer {
  /**
   * @param {HTMLElement} container 承载容器（WebGL 画布所在容器；本层 DOM 叠加其上）
   *
   * @param {THREE.Scene} scene 目标场景（CSS2DObject 挂入场景图，CSS2DRenderer 才能投影）
   */
  constructor(container, scene) {
    this.container = container
    this.scene = scene
    this.renderer = new CSS2DRenderer()
    this.renderer.domElement.className = 'sb3d-labels'
    this.renderer.domElement.style.position = 'absolute'
    this.renderer.domElement.style.top = '0'
    this.renderer.domElement.style.left = '0'
    this.renderer.domElement.style.pointerEvents = 'none'
    this.renderer.domElement.style.zIndex = '2'
    container.appendChild(this.renderer.domElement)

    this.entries = new Map() //
    this.locationBubbles = new Set() // 地点锚定气泡 { obj: CSS2DObject, timer: number|null }
    this.selectedRef = null
  }

  /**
   * 全量同步名牌（与 NpcMarkerLayer.setNpcs 同批次调用；/退场/类型/名字变化都走这里）。
   */
  setNpcs(npcs) {
    const seen = new Set()
    for (const n of npcs || []) {
      if (!n || !n.npcRef) continue
      seen.add(n.npcRef)
      const isSpecial = n.npcType === 'special' || (n.npcType !== 'crowd' && n.npcType !== 'common')
      let e = this.entries.get(n.npcRef)
      if (!e) {
        e = { tag: null, bubble: null, timer: null }
        const el = document.createElement('div')
        el.className = 'sb3d-tag'
        const nameEl = document.createElement('span')
        nameEl.className = 'sb3d-tag-name'
        el.appendChild(nameEl)
        e.tag = new CSS2DObject(el)
        this.scene.add(e.tag)
        this.entries.set(n.npcRef, e)
      }
      // 名字 + 类型类（特殊金 / 普通蓝；仅当变化时改 DOM）
      const tagEl = e.tag.element
      const nameEl = tagEl.querySelector('.sb3d-tag-name')
      const name = n.npcName || n.npcRef
      if (nameEl.textContent !== name) nameEl.textContent = name
      const cls = isSpecial ? 'special' : 'crowd'
      if (!tagEl.classList.contains(cls)) {
        tagEl.classList.remove('special', 'crowd')
        tagEl.classList.add(cls)
      }
      if (this.selectedRef === n.npcRef) tagEl.classList.add('selected')
      // 位置跟随（标记层未同步前先给初始点；syncPositions 每帧修正）
      if (Number.isFinite(n.posX) && Number.isFinite(n.posZ)) {
        e.tag.position.set(n.posX, TAG_Y, n.posZ)
      }
    }
    // 退场：移除名牌 + 气泡
    for (const ref of [...this.entries.keys()]) {
      if (seen.has(ref)) continue
      const e = this.entries.get(ref)
      this.removeBubble(e)
      this.scene.remove(e.tag)
      e.tag.element.parentElement?.removeChild(e.tag.element)
      this.entries.delete(ref)
    }
  }

  /**
   * 每帧同步名牌/气泡世界坐标（从标记层 entries 读取插值后的 cur 位置，保证名牌跟随移动）。
   */
  syncPositions(markerEntries) {
    if (!markerEntries) return
    for (const [ref, e] of this.entries.entries()) {
      const m = markerEntries.get(ref)
      if (!m) continue
      const x = m.cur.x
      const z = m.cur.z
      e.tag.position.set(x, TAG_Y, z)
      if (e.bubble) e.bubble.position.set(x, BUBBLE_Y, z)
    }
  }

  /**
   * 弹一条 NPC 动态气泡（同 NPC 新气泡替换旧气泡；到期自动淡出移除）。
   *
   * @param {string} npcRef NPC 引用
   */
  showBubble(npcRef, opt = {}) {
    const e = this.entries.get(npcRef)
    if (!e) return
    const kind = BUBBLE_KINDS[opt.kind] ? opt.kind : 'thought'
    const text = String(opt.text || '').slice(0, 80)
    if (!text) return

    // 复用既有气泡元素（替换内容重启计时）——保证同时最多一个气泡
    if (!e.bubble) {
      const el = createBubbleEl()
      e.bubble = new CSS2DObject(el)
      this.scene.add(e.bubble)
      e.bubble.position.set(e.tag.position.x, BUBBLE_Y, e.tag.position.z)
    }
    fillBubbleEl(e.bubble.element, kind, text)

    // 到期淡出（重启计时；防泄漏定时器）
    if (e.timer) clearTimeout(e.timer)
    const duration = opt.durationMs || BUBBLE_DEFAULT_MS
    e.timer = setTimeout(() => {
      e.bubble.element.classList.add('hide')
      setTimeout(() => {
        // 淡出动画结束后移除（若期间被新气泡复用则不重复移除）
        if (e.bubble && e.bubble.element.classList.contains('hide')) this.removeBubble(e)
      }, 500)
    }, duration)
  }

  /**
   * 弹一条地点锚定气泡（事件可视化：无当事人的世界事件在地点坐标弹一次性气泡）。
   *
   * @param {number} x 世界坐标 X（中心化）
   *
   * @param {number} z 世界坐标 Z（中心化）
   *
   * @param {{kind?: string, text: string, durationMs?: number}} opt 气泡内容（默认 kind=event）
   */
  showLocationBubble(x, z, opt = {}) {
    const kind = BUBBLE_KINDS[opt.kind] ? opt.kind : 'event'
    const text = String(opt.text || '').slice(0, 80)
    if (!text) return
    const el = createBubbleEl()
    const obj = new CSS2DObject(el)
    obj.position.set(x, BUBBLE_Y, z)
    this.scene.add(obj)
    fillBubbleEl(el, kind, text)

    const rec = { obj, timer: null }
    this.locationBubbles.add(rec)
    const duration = opt.durationMs || 6000 // 事件气泡稍长，便于阅读
    rec.timer = setTimeout(() => {
      el.classList.add('hide')
      setTimeout(() => {
        this.scene.remove(obj)
        el.parentElement?.removeChild(el)
        this.locationBubbles.delete(rec)
      }, 500)
    }, duration)
  }

  /** 移除 NPC 气泡（清定时器 + 从场景/DOM 移除） */
  removeBubble(e) {
    if (e.timer) { clearTimeout(e.timer); e.timer = null }
    if (e.bubble) {
      this.scene.remove(e.bubble)
      const el = e.bubble.element
      el.parentElement?.removeChild(el)
      e.bubble = null
    }
  }

  /** 选中高亮（名牌加 selected 类，白色边框高亮） */
  setSelected(npcRef) {
    if (this.selectedRef === npcRef) return
    if (this.selectedRef) {
      const prev = this.entries.get(this.selectedRef)
      if (prev) prev.tag.element.classList.remove('selected')
    }
    this.selectedRef = npcRef || null
    if (this.selectedRef) {
      const cur = this.entries.get(this.selectedRef)
      if (cur) cur.tag.element.classList.add('selected')
    }
  }

  /** 渲染（CSS2D 投影；与 WebGL 渲染循环同帧调用） */
  render(scene, camera) {
    if (this.renderer && scene && camera) this.renderer.render(scene, camera)
  }

  /** 容器尺寸变化（WebGL 画布 resize 同参） */
  setSize(w, h) {
    if (this.renderer) this.renderer.setSize(w || 1, h || 1)
  }

  /** 当前名牌数（dev 钩子/冒烟断言用） */
  tagCount() {
    return this.entries.size
  }

  /** 当前活动气泡数（NPC 气泡 + 地点气泡；dev 钩子/冒烟断言用） */
  bubbleCount() {
    let n = 0
    for (const e of this.entries.values()) if (e.bubble) n++
    return n + this.locationBubbles.size
  }

  /** 图层卸载（移除全部场景对象与 DOM 元素） */
  dispose() {
    for (const ref of [...this.entries.keys()]) {
      const e = this.entries.get(ref)
      this.removeBubble(e)
      this.scene.remove(e.tag)
      const el = e.tag.element
      el.parentElement?.removeChild(el)
      this.entries.delete(ref)
    }
    for (const rec of [...this.locationBubbles]) {
      if (rec.timer) clearTimeout(rec.timer)
      this.scene.remove(rec.obj)
      rec.obj.element.parentElement?.removeChild(rec.obj.element)
      this.locationBubbles.delete(rec)
    }
    if (this.renderer) {
      this.renderer.domElement.parentElement?.removeChild(this.renderer.domElement)
      this.renderer = null
    }
    this.scene = null
  }
}

/** 创建气泡 DOM 元素（图标 + 文本） */
function createBubbleEl() {
  const el = document.createElement('div')
  el.className = 'sb3d-bubble'
  const iconEl = document.createElement('span')
  iconEl.className = 'sb3d-bubble-icon'
  const textEl = document.createElement('span')
  textEl.className = 'sb3d-bubble-text'
  el.appendChild(iconEl)
  el.appendChild(textEl)
  return el
}

/** 填充气泡内容（图标 + 文本 + 类型样式类；移除旧类/隐藏态） */
function fillBubbleEl(el, kind, text) {
  el.classList.remove('hide', ...Object.keys(BUBBLE_KINDS).map((k) => 'bubble-' + k))
  el.classList.add(BUBBLE_KINDS[kind].cls)
  el.querySelector('.sb3d-bubble-icon').textContent = BUBBLE_KINDS[kind].icon
  el.querySelector('.sb3d-bubble-text').textContent = text
}
