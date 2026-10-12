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
 * 世界沙盘 3D · NPC 标记层（S3 补全 + S4 增强，features/sandbox/sandbox3d）。
 * <p>职责：把沙盘内 NPC（特殊+普通人群，坐标来自 actor_sandbox_state【已中心化】）渲染为
 * 简单 3D 几何标记（资源包无 NPC 模型）——特殊 NPC 圆锥 + 普通人群圆柱，
 * InstancedMesh 单 draw call；行走时按目标点线性插值（视觉移动），选中高亮（放大 + 提亮）。</p>
 * <p>S4 增强：新增<b>脚下光环</b>（RingGeometry 平铺贴地，特殊金/普通蓝/选中白，
 * 与标记同实例化池）——俯视角度下「哪个是 NPC、是特殊还是普通」一眼可辨；头顶名牌/气泡由
 * 配套的 {@code labelLayer.js}（CSS2D）承担，本层只管几何标记。</p>
 * <p>坐标口径：actor_sandbox_state 坐标为【中心化】[-size/2, size/2]（S3 坐标中心化决策），
 * 直接作为 Three.js 世界坐标使用，无需偏移。</p>
 */
import * as THREE from 'three'

/** 特殊 NPC 标记颜色（金） */
const COLOR_SPECIAL = 0xffd45c
/** 普通人群标记颜色（蓝） */
const COLOR_CROWD = 0x8fb1ff
/** 选中高亮颜色（白亮） */
const COLOR_SELECTED = 0xffffff

/** 标记高度（米，约人高） */
const MARKER_HEIGHT = 1.8
/** 标记半径（米） */
const MARKER_RADIUS = 0.55
/** 视觉移动速度（米/真实秒；仅插值表现，位置权威在后端） */
const WALK_SPEED = 6

/** S4：脚下光环内/外半径（米；比标记底座略大，俯视可见） */
const RING_INNER = 0.95
const RING_OUTER = 1.35
/** 光环离地高度（米，略高于地形贴图防 z-fighting） */
const RING_Y = 0.08

/**
 * NPC 标记图层。
 * <p>生命周期：setNpcs() 全量同步（SSE 增量由父组件合并后传入），update(dt) 每帧推进插值，
 * setSelected() 选中高亮，dispose() 释放。</p>
 */
export class NpcMarkerLayer {
  /**
   * @param {THREE.Scene} scene 目标场景
   */
  constructor(scene) {
    this.scene = scene
    this.group = new THREE.Group()
    this.scene.add(this.group)

    // 特殊 NPC：圆锥（金）；普通人群：圆柱（蓝）——两套 InstancedMesh
    this.specialMesh = new THREE.InstancedMesh(
      new THREE.ConeGeometry(MARKER_RADIUS, MARKER_HEIGHT, 10),
      new THREE.MeshStandardMaterial({ color: COLOR_SPECIAL, roughness: 0.6, metalness: 0.1 }),
      64
    )
    this.crowdMesh = new THREE.InstancedMesh(
      new THREE.CylinderGeometry(MARKER_RADIUS, MARKER_RADIUS * 0.7, MARKER_HEIGHT, 10),
      new THREE.MeshStandardMaterial({ color: COLOR_CROWD, roughness: 0.7, metalness: 0.1 }),
      256
    )
    this.specialMesh.instanceMatrix.setUsage(THREE.DynamicDrawUsage)
    this.crowdMesh.instanceMatrix.setUsage(THREE.DynamicDrawUsage)
    this.group.add(this.specialMesh, this.crowdMesh)

    // S4：脚下光环（单实例化池，逐实例颜色区分特殊/普通/选中；平铺贴地）
    this.ringMesh = new THREE.InstancedMesh(
      new THREE.RingGeometry(RING_INNER, RING_OUTER, 24),
      new THREE.MeshBasicMaterial({ transparent: true, opacity: 0.9, depthWrite: false }),
      320
    )
    this.ringMesh.instanceMatrix.setUsage(THREE.DynamicDrawUsage)
    this.ringMesh.instanceColor = new THREE.InstancedBufferAttribute(new Float32Array(320 * 3), 3)
    this.ringMesh.rotation.x = -Math.PI / 2 // 平铺贴地（法线朝上）
    this.group.add(this.ringMesh)

    this.entries = new Map() //
    this.specialCount = 0
    this.crowdCount = 0
    this.ringCount = 0 // 光环实例数（= 特殊+普通总数，逐实例颜色）
    this.selectedRef = null
    this.dummy = new THREE.Object3D()
    this.color = new THREE.Color()
  }

  /**
   * 全量同步 NPC 标记（父组件在 overview/SSE 变化后调用；/删除/位置变化都走这里）。
   */
  setNpcs(npcs) {
    const seen = new Set()
    for (const n of npcs || []) {
      if (!n || !n.npcRef) continue
      seen.add(n.npcRef)
      const isSpecial = n.npcType === 'special' || (n.npcType !== 'crowd' && n.npcType !== 'common')
      let e = this.entries.get(n.npcRef)
      if (!e) {
        const mesh = isSpecial ? this.specialMesh : this.crowdMesh
        const index = isSpecial ? this.specialCount++ : this.crowdCount++
        e = {
          mesh,
          index,
          ringIndex: this.ringCount++,
          npcType: isSpecial ? 'special' : 'common',
          cur: { x: 0, z: 0 },
          target: { x: 0, z: 0 },
          heading: n.heading ?? 0,
          walking: false
        }
        this.entries.set(n.npcRef, e)
        this.setCount(mesh, isSpecial ? this.specialCount : this.crowdCount)
        this.setRingCount(this.ringCount)
      }
      if (Number.isFinite(n.posX) && Number.isFinite(n.posZ)) {
        const nx = n.posX
        const nz = n.posZ
        const walking = n.moveState === 'walking'
        // 目标点更新：行走中以移动事件目标为准，到达/空闲直接吸附
        e.walking = walking
        if (!e.walking) {
          e.cur.x = nx
          e.cur.z = nz
        }
        e.target.x = nx
        e.target.z = nz
        if (Number.isFinite(n.heading)) e.heading = n.heading
      }
      if (e.walking && e.cur.x === e.target.x && e.cur.z === e.target.z) e.walking = false
    }
    // 删除已退场 NPC
    for (const ref of [...this.entries.keys()]) {
      if (seen.has(ref)) continue
      const e = this.entries.get(ref)
      this.freeInstance(e)
      this.entries.delete(ref)
    }
    this.applyAll()
  }

  /** 释放实例槽位（数组压缩：把末位实例移到空位，保持实例紧凑；标记与光环同步压缩） */
  freeInstance(e) {
    const isSpecial = e.npcType === 'special'
    const mesh = isSpecial ? this.specialMesh : this.crowdMesh
    const count = isSpecial ? this.specialCount : this.crowdCount
    const lastIndex = count - 1
    if (e.index !== lastIndex) {
      // 找到当前占用 lastIndex 的条目并迁移到 e.index
      for (const other of this.entries.values()) {
        if (other !== e && other.mesh === mesh && other.index === lastIndex) {
          other.index = e.index
          break
        }
      }
    }
    // 光环槽位同步压缩
    const lastRing = this.ringCount - 1
    if (e.ringIndex !== lastRing) {
      for (const other of this.entries.values()) {
        if (other !== e && other.ringIndex === lastRing) {
          other.ringIndex = e.ringIndex
          break
        }
      }
    }
    if (isSpecial) this.specialCount--
    else this.crowdCount--
    this.ringCount--
    this.setCount(mesh, count - 1)
    this.setRingCount(this.ringCount)
  }

  /** 实例数变化（InstancedMesh count 只能缩小；容量不足时重建） */
  setCount(mesh, count) {
    if (count <= mesh.count) {
      mesh.count = Math.max(0, count)
      return
    }
    // 容量不足：按需扩容重建（保留已写矩阵）
    const oldMatrix = mesh.instanceMatrix
    const oldColor = mesh.instanceColor
    const next = new THREE.InstancedMesh(mesh.geometry, mesh.material, Math.max(count * 2, 64))
    next.instanceMatrix.setUsage(THREE.DynamicDrawUsage)
    next.copy(mesh)
    next.count = count
    if (oldColor) oldColor.dispose()
    oldMatrix.dispose()
    if (mesh === this.specialMesh) {
      this.group.remove(this.specialMesh)
      this.specialMesh = next
      this.group.add(this.specialMesh)
      this.specialMesh.count = count
    } else {
      this.group.remove(this.crowdMesh)
      this.crowdMesh = next
      this.group.add(this.crowdMesh)
      this.crowdMesh.count = count
    }
    for (const e of this.entries.values()) {
      if (e.mesh === mesh) e.mesh = next
    }
  }

  /** 光环实例数变化（容量不足重建；逐实例颜色随 ringCount 重建） */
  setRingCount(count) {
    if (count <= this.ringMesh.count) {
      this.ringMesh.count = Math.max(0, count)
      return
    }
    const oldMatrix = this.ringMesh.instanceMatrix
    const oldColor = this.ringMesh.instanceColor
    const next = new THREE.InstancedMesh(this.ringMesh.geometry, this.ringMesh.material, Math.max(count * 2, 320))
    next.instanceMatrix.setUsage(THREE.DynamicDrawUsage)
    next.instanceColor = new THREE.InstancedBufferAttribute(new Float32Array(Math.max(count * 2, 320) * 3), 3)
    next.rotation.x = -Math.PI / 2
    next.count = count
    this.group.remove(this.ringMesh)
    this.ringMesh = next
    this.group.add(this.ringMesh)
    if (oldColor) oldColor.dispose()
    oldMatrix.dispose()
  }

  /** 全部实例矩阵/颜色重写（选中高亮 + 颜色区分；标记 + 脚下光环同步） */
  applyAll() {
    for (const e of this.entries.values()) {
      const selected = e.npcRef === this.selectedRef
      const scale = selected ? 1.35 : 1
      this.dummy.position.set(e.cur.x, MARKER_HEIGHT / 2, e.cur.z)
      this.dummy.rotation.set(0, e.heading ?? 0, 0)
      this.dummy.scale.set(scale, scale, scale)
      this.dummy.updateMatrix()
      e.mesh.setMatrixAt(e.index, this.dummy.matrix)
      this.color.set(selected ? COLOR_SELECTED : (e.npcType === 'special' ? COLOR_SPECIAL : COLOR_CROWD))
      e.mesh.setColorAt(e.index, this.color)

      // S4：脚下光环（贴地平铺，跟随 cur 位置；选中放大 + 白）
      this.dummy.position.set(e.cur.x, RING_Y, e.cur.z)
      this.dummy.rotation.set(0, 0, 0)
      this.dummy.scale.set(scale, scale, scale)
      this.dummy.updateMatrix()
      this.ringMesh.setMatrixAt(e.ringIndex, this.dummy.matrix)
      this.ringMesh.setColorAt(e.ringIndex, this.color)
    }
    if (this.specialCount > 0) {
      this.specialMesh.instanceMatrix.needsUpdate = true
      if (this.specialMesh.instanceColor) this.specialMesh.instanceColor.needsUpdate = true
    }
    if (this.crowdCount > 0) {
      this.crowdMesh.instanceMatrix.needsUpdate = true
      if (this.crowdMesh.instanceColor) this.crowdMesh.instanceColor.needsUpdate = true
    }
    if (this.ringCount > 0) {
      this.ringMesh.instanceMatrix.needsUpdate = true
      if (this.ringMesh.instanceColor) this.ringMesh.instanceColor.needsUpdate = true
    }
  }

  /** 设置选中 NPC（null=取消；高亮放大 + 白色） */
  setSelected(npcRef) {
    if (this.selectedRef === npcRef) return
    this.selectedRef = npcRef || null
    this.applyAll()
  }

  /**
   * 设置拾取粗判半径（S4 点击冒烟抓修，）。
   * <p>three.js {@code InstancedMesh.raycast} 先以几何自身 boundingSphere（中心=原点、半径≈几何
   * 包围盒）做粗判再逐实例精测——但 NPC 实例矩阵已平移到场景各处（±10m 乃至 ±50m），
   * 射线方向大多不经过原点小球 → 粗判直接 return → 标记永远点不中（S4 点击拾取实测 0 命中）。
   * 把粗判球放大到「覆盖全部实例」（中心=原点、半径=场景半对角线+余量），粗判不误杀，
   * 逐实例精测由 three 内部完成，命中 instanceId 正常返回。</p>
   *
   * @param {number} radius 粗判球半径（米，≥ 场景半对角线 + 标记高度余量）
   */
  setPickRadius(radius) {
    for (const m of [this.specialMesh, this.crowdMesh, this.ringMesh]) {
      m.boundingSphere = m.boundingSphere || new THREE.Sphere()
      m.boundingSphere.center.set(0, 0, 0)
      m.boundingSphere.radius = radius
    }
  }

  /**
   * 每帧推进：行走插值（向目标点以固定视觉速度移动；到达即停）。
   *
   * @param {number} dt 帧间隔（真实秒）
   */
  update(dt) {
    const step = WALK_SPEED * dt
    for (const e of this.entries.values()) {
      if (!e.walking) continue
      const dx = e.target.x - e.cur.x
      const dz = e.target.z - e.cur.z
      const dist = Math.hypot(dx, dz)
      if (dist <= step || dist <= 0.001) {
        e.cur.x = e.target.x
        e.cur.z = e.target.z
        e.walking = false
        continue
      }
      e.cur.x += (dx / dist) * step
      e.cur.z += (dz / dist) * step
      e.heading = Math.atan2(dx, dz)
    }
    this.applyAll()
  }

  /** 图层卸载 */
  dispose() {
    this.scene.remove(this.group)
    this.entries.clear()
    this.specialMesh.geometry.dispose()
    this.specialMesh.material.dispose()
    this.crowdMesh.geometry.dispose()
    this.crowdMesh.material.dispose()
    if (this.ringMesh) {
      this.ringMesh.geometry.dispose()
      this.ringMesh.material.dispose()
      if (this.ringMesh.instanceColor) this.ringMesh.instanceColor.dispose()
    }
    this.group = null
  }
}
