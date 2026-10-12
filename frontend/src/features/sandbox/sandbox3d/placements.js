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
 * 世界沙盘 3D · 资产摆放层（S3 补全，features/sandbox/sandbox3d；S3.2 会话级缓存优化）。
 * <p>职责：按场景摆放表（actor_sandbox_placement VO：assetId/posX/posZ/rotY/scale + filePath）
 * 加载 GLB 资产并落位——同一资产 URL 只加载一次（<b>会话级共享缓存</b>，克隆复用），加载进度回调；
 * 加载失败/未登记资产回退「占位盒」（空态兜底，主方案 S3）。</p>
 * <p><b>S3.2 会话级缓存</b>（用户确认）：GLB 解析结果（含几何/材质/GPU 资源）缓存在模块级
 * {@code sharedGltfCache}，跨图层实例共享、本次会话内离开再进入不重新下载/解码（秒开）；
 * 组件卸载/图层 dispose 只释放本实例的占位盒与引用，<b>不清除共享缓存</b>——刷新页面才会重新加载。
 * 缓存命中时摆放跳过分批延迟（旧逻辑每批 120ms 上限 4s），二次进入立即落位。</p>
 * <p>坐标口径：摆放表坐标 x/z ∈ [0,size)（主方案 §8.1），渲染时偏移 -size/2 到中心化
 * [-size/2, size/2]；GLB 规范：1 单位=1 米、原点=底部中心、+Z 前向、rotationY=0 面朝 +z。</p>
 */
import * as THREE from 'three'
import { GLTFLoader } from 'three/addons/loaders/GLTFLoader.js'
import { MeshoptDecoder } from 'three/addons/libs/meshopt_decoder.module.js'

/** 占位盒尺寸（米；加载失败/无 GLB 路径的资产显示半透明橙色盒） */
const PLACEHOLDER_SIZE = 2

/**
 * 会话级共享 GLB 缓存：url → Promise<THREE.Group|null>。
 * <p>模块级单例：所有 PlacementLayer 实例共享，首次加载后本次会话内跨组件复用；
 * 缓存克隆共享几何/材质（Mesh.clone 浅拷贝引用），故图层清理时不得 dispose 共享资源。</p>
 */
const sharedGltfCache = new Map()

/**
 * 资产摆放图层。
 * <p>生命周期：load() 重建（摆放/尺寸变化时），dispose() 释放本实例（共享缓存保留）。</p>
 */
export class PlacementLayer {
  /**
   * @param {THREE.Scene} scene 目标场景
   */
  constructor(scene, opts = {}) {
    this.scene = scene
    this.onProgress = opts.onProgress
    this.group = new THREE.Group()
    this.scene.add(this.group)
    this.loader = new GLTFLoader()
    this.loader.setMeshoptDecoder(MeshoptDecoder)
    this.cache = sharedGltfCache // url → Promise<THREE.Group|null>（会话级共享）
    this.loaded = 0
    this.total = 0
    this.epoch = 0 // 世代号：load() 递增，异步回调/定时器据此丢弃过期批次（防重复摆放）
    this.pendingTimers = new Set() // 挂起的分批定时器（load() 重入时取消）
  }

  /**
   * 重建摆放（先取消挂起定时器 + 清空再按列表加载；同 URL 复用共享缓存克隆）。
   * <p>S3.2：URL 已在共享缓存时跳过分批延迟（旧逻辑 i*120ms 上限 4s），二次进入立即落位。</p>
   * <p>防重复摆放（S3.2 冒烟抓修）：overview 与 SSE sandbox:init 会先后触发两次 load()——
   * 旧实现不清上一轮的 setTimeout，clear() 后挂起定时器仍落位导致 children 翻倍（134/136）；
   * 现在 load() 先取消挂起定时器，且每批带世代号，过期批次的异步回调直接丢弃。</p>
   *
   * @param {{size?: number}} [opts] 场景边长（坐标偏移基准）
   */
  load(placements, opts = {}) {
    // 取消上一批挂起的分批定时器（避免 clear() 后过期批次继续落位 → 重复摆放）
    for (const t of this.pendingTimers) clearTimeout(t)
    this.pendingTimers.clear()
    const epoch = ++this.epoch
    this.clear()
    const size = Number.isFinite(opts.size) ? opts.size : 500
    const half = size / 2
    const list = placements || []
    this.total = list.length
    this.loaded = 0
    this.emitProgress()

    list.forEach((p, i) => {
      const url = p.filePath ? '/' + p.filePath : null
      const place = () => {
        this.pendingTimers.delete(timer)
        if (epoch !== this.epoch) return // 已换代：本批作废（防重复摆放）
        const x = p.posX - half
        const z = p.posZ - half
        if (!url) {
          this.addPlaceholder(p, x, z)
          this.tick(i)
          return
        }
        this.loadGltf(url).then((model) => {
          if (epoch !== this.epoch) return // 已换代：异步完成丢弃
          if (!this.group) return // 已卸载
          if (model) {
            const clone = model.clone(true)
            clone.position.set(x, p.posY || 0, z)
            clone.rotation.y = Number.isFinite(p.rotY) ? p.rotY : 0
            const sx = Number.isFinite(p.scaleX) ? p.scaleX : 1
            const sy = Number.isFinite(p.scaleY) ? p.scaleY : 1
            const sz = Number.isFinite(p.scaleZ) ? p.scaleZ : 1
            clone.scale.set(sx, sy, sz)
            clone.userData.assetId = p.assetId
            clone.userData.placementId = p.id
            clone.userData.name = p.name || p.assetId
            this.group.add(clone)
          } else {
            this.addPlaceholder(p, x, z)
          }
          this.tick(i)
        })
      }
      // 分批延迟仅用于首次下载（避免首帧同时发起几十个请求卡顿）；
      // 缓存命中（会话内二次进入）立即落位
      const delay = url && this.cache.has(url) ? 0 : Math.min(i * 120, 4000)
      const timer = setTimeout(place, delay)
      this.pendingTimers.add(timer)
    })
  }

  /** 加载单个 GLB（会话级共享缓存；失败返回 null） */
  loadGltf(url) {
    if (this.cache.has(url)) return this.cache.get(url)
    const promise = new Promise((resolve) => {
      this.loader.load(
        url,
        (gltf) => resolve(gltf.scene),
        () => {},
        () => resolve(null)
      )
    })
    this.cache.set(url, promise)
    return promise
  }

  /** 占位盒（加载失败/无 GLB：半透明橙色盒，保留坐标与名称，便于空态识别） */
  addPlaceholder(p, x, z) {
    const box = new THREE.Mesh(
      new THREE.BoxGeometry(PLACEHOLDER_SIZE, PLACEHOLDER_SIZE, PLACEHOLDER_SIZE),
      new THREE.MeshBasicMaterial({ color: 0xff8f3f, transparent: true, opacity: 0.6 })
    )
    box.position.set(x, PLACEHOLDER_SIZE / 2, z)
    box.userData.assetId = p.assetId
    box.userData.placementId = p.id
    box.userData.name = p.name || p.assetId
    box.userData.placeholder = true
    this.group.add(box)
  }

  /** 进度计数（完成一个摆放） */
  tick(i) {
    this.loaded++
    this.emitProgress()
  }

  /** 进度回调（loaded/total） */
  emitProgress() {
    if (this.onProgress) this.onProgress({ loaded: this.loaded, total: this.total })
  }

  /**
   * 清空摆放组。
   * <p>S3.2：仅释放<b>本实例独有</b>的占位盒资源；GLB 克隆与共享缓存同源
   * （几何/材质浅拷贝共享），不 dispose——否则会破坏共享缓存与其他实例。</p>
   */
  clear() {
    if (!this.group) return
    const children = [...this.group.children]
    this.group.clear()
    children.forEach((child) => {
      // 占位盒是本实例新建的独立几何/材质，需要释放；GLB 克隆共享缓存资源，跳过
      if (child.userData && child.userData.placeholder) {
        child.traverse((o) => {
          if (o.geometry) o.geometry.dispose()
          if (o.material) {
            const m = Array.isArray(o.material) ? o.material : [o.material]
            m.forEach((mm) => {
              if (mm.map) mm.map.dispose()
              mm.dispose()
            })
          }
        })
      }
    })
  }

  /** 图层卸载（取消挂起批次 + 清空本实例摆放；共享 GLB 缓存保留供会话内复用） */
  dispose() {
    for (const t of this.pendingTimers) clearTimeout(t)
    this.pendingTimers.clear()
    this.epoch++ // 作废所有在途异步回调（含 GLB 加载完成后的落位）
    this.clear()
    this.scene.remove(this.group)
    this.group = null
  }
}
