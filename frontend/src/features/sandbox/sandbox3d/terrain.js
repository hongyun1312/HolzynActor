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
 * 世界沙盘 3D · 像素地形层（S3 补全，features/sandbox/sandbox3d）。
 * <p>职责：把场景布局数据渲染为「像素色块地面」——程序地形（style+seed 确定性噪声分类，
 * 10 类地形色板，1 块=1 米）写入 canvas 纹理（NearestFilter 干净像素块，单 draw call），
 * 再叠加河/路栅格化色带（Bresenham 线段 + 宽度扩散，数据来自 layout_json.rivers/roads）。</p>
 * <p>设计依据：主方案 §8.1~§8.4（正方形像素地形 + 程序地形参数 + 河路色带，存储只存参数不存网格）。</p>
 */
import * as THREE from 'three'

/** 10 类地形默认色板（主方案 §8.2；风格色板可覆盖） */
const PALETTE = {
  plain: 0xa8d88a,
  grass: 0x7ec850,
  forest: 0x3e8e4a,
  snow: 0xf0f4f8,
  water: 0x5ba6d9,
  deep_water: 0x1f5fa8,
  beach: 0xe8d79b,
  desert: 0xe3c878,
  mountain: 0x9a8a78,
  road: 0xc9b79c
}

/** 风格 → 程序地形分类阈值（噪声域 [0,1]；hills 中等起伏：草/平原为主 + 林地斑块 + 少量山地） */
const STYLE_RULES = {
  plain: { base: 'plain', grass: 0.45, forest: 0.68, mountain: 0.95, water: 0.92 },
  hills: { base: 'grass', grass: 0.4, forest: 0.62, mountain: 0.9, water: 0.94 },
  mountain: { base: 'mountain', grass: 0.35, forest: 0.55, mountain: 0.78, water: 0.96 },
  seaside: { base: 'grass', grass: 0.42, forest: 0.7, mountain: 0.95, water: 0.5 },
  desert: { base: 'desert', grass: 0.35, forest: 0.92, mountain: 0.9, water: 0.9 },
  snow: { base: 'snow', grass: 0.3, forest: 0.6, mountain: 0.82, water: 0.9 }
}

/** 确定性伪随机（mulberry32；同 seed 同序列，可复现地形） */
function mulberry32(seed) {
  let a = seed >>> 0
  return () => {
    a |= 0
    a = (a + 0x6d2b79f5) | 0
    let t = Math.imul(a ^ (a >>> 15), 1 | a)
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296
  }
}

/** 2D 值噪声（双线性插值；格子点随机来自确定性 PRNG） */
function makeNoise(seed, size, cell) {
  const rand = mulberry32(seed)
  const gridSize = Math.max(4, Math.ceil(size / cell) + 2)
  const grid = new Float32Array(gridSize * gridSize)
  for (let i = 0; i < grid.length; i++) grid[i] = rand()
  return (x, z) => {
    const gx = x / cell
    const gz = z / cell
    const ix = Math.floor(gx)
    const iz = Math.floor(gz)
    const fx = gx - ix
    const fz = gz - iz
    const sx = fx * fx * (3 - 2 * fx)
    const sz = fz * fz * (3 - 2 * fz)
    const at = (gi, gj) => grid[((iz + gj + gridSize) % gridSize) * gridSize + ((ix + gi + gridSize) % gridSize)]
    const a = at(0, 0)
    const b = at(1, 0)
    const c = at(0, 1)
    const d = at(1, 1)
    return a + (b - a) * sx + (c - a) * sz + (a - b - c + d) * sx * sz
  }
}

/** 多倍频 fbm 噪声（3 倍频，值域约 [0,1]） */
function fbm(noise, x, z) {
  let v = 0
  let amp = 0.55
  let freq = 1
  for (let o = 0; o < 3; o++) {
    v += noise(x * freq, z * freq) * amp
    amp *= 0.5
    freq *= 2
  }
  return Math.min(1, Math.max(0, v))
}

/** 河/路线段栅格化：Bresenham 连接路径点 + 宽度扩散（宽度按米=格） */
function rasterizePath(img, size, path, width, color) {
  const pts = (path || []).map((p) => [Math.round(p[0]), Math.round(p[1])]).filter(([x, z]) => x >= 0 && x < size && z >= 0 && z < size)
  if (pts.length === 0) return
  const r = Math.max(1, Math.round((width || 4) / 2))
  const paint = (cx, cz) => {
    for (let dz = -r; dz <= r; dz++) {
      for (let dx = -r; dx <= r; dx++) {
        if (dx * dx + dz * dz > r * r) continue
        const x = cx + dx
        const z = cz + dz
        if (x >= 0 && x < size && z >= 0 && z < size) {
          const idx = (z * size + x) * 4
          img.data[idx] = (color >> 16) & 255
          img.data[idx + 1] = (color >> 8) & 255
          img.data[idx + 2] = color & 255
          img.data[idx + 3] = 255
        }
      }
    }
  }
  paint(pts[0][0], pts[0][1])
  for (let i = 1; i < pts.length; i++) {
    let x0 = pts[i - 1][0]
    let z0 = pts[i - 1][1]
    const x1 = pts[i][0]
    const z1 = pts[i][1]
    const dx = Math.abs(x1 - x0)
    const dz = Math.abs(z1 - z0)
    const sx = x0 < x1 ? 1 : -1
    const sz = z0 < z1 ? 1 : -1
    let err = dx - dz
    for (;;) {
      paint(x0, z0)
      if (x0 === x1 && z0 === z1) break
      const e2 = 2 * err
      if (e2 > -dz) { err -= dz; x0 += sx }
      if (e2 < dx) { err += dx; z0 += sz }
    }
  }
}

/**
 * 像素地形图层。
 * <p>生命周期：build() 重建（布局/尺寸变化时），dispose() 释放。单 mesh，canvas 纹理 + NearestFilter。</p>
 */
export class TerrainLayer {
  /**
   * @param {THREE.Scene} scene 目标场景
   */
  constructor(scene) {
    this.scene = scene
    this.mesh = null
    this.texture = null
    this.canvas = null
  }

  /**
   * 重建地形（程序地形 + 河路色带；同 style+seed 输出可复现）。
   */
  build(cfg = {}) {
    const size = Number.isFinite(cfg.size) && cfg.size >= 100 ? Math.floor(cfg.size) : 500
    const styleCode = cfg.styleCode || 'plain'
    const seed = Number.isFinite(cfg.seed) ? cfg.seed : 20260914
    const rules = STYLE_RULES[styleCode] || STYLE_RULES.plain
    const palette = { ...PALETTE, ...(cfg.palette || {}) }

    // 1) 程序地形 → 像素缓冲（fbm 噪声 → 阈值分类）
    // S3.2：噪声格密度随场景尺寸等比缩放（k=size/500）——500m 与历史输出完全一致
    // （90/45/16 格），100m 保持同等细节密度（18/9/3 格），避免小场景退化成单一色块
    const canvas = document.createElement('canvas')
    canvas.width = size
    canvas.height = size
    const ctx = canvas.getContext('2d')
    const img = ctx.createImageData(size, size)
    const k = size / 500
    const noise = makeNoise(seed ^ 0x9e3779b9, size, Math.max(8, Math.round(90 * k)))
    const coarse = makeNoise((seed ^ 0x51ab3f7d) >>> 0, size, Math.max(4, Math.round(45 * k)))
    const fine = makeNoise((seed ^ 0xdeadbeef) >>> 0, size, Math.max(3, Math.round(16 * k)))
    for (let z = 0; z < size; z++) {
      for (let x = 0; x < size; x++) {
        let v = fbm(noise, x, z)
        // 大尺度扰动：局部风格差异（丘陵感）
        v = v * 0.7 + coarse(x / 2, z / 2) * 0.3
        let type = rules.base
        if (v > rules.water && styleCode === 'seaside') type = 'water'
        else if (v > rules.mountain) type = 'mountain'
        else if (v > rules.forest) type = 'forest'
        else if (v > rules.grass) type = 'grass'
        // 细尺度点缀：grass 上少量 plain 斑块
        if (type === 'grass' && fine(x, z) > 0.82) type = 'plain'
        if (type === 'plain' && fine(x, z) < 0.06) type = 'grass'
        const color = palette[type] ?? PALETTE.grass
        const idx = (z * size + x) * 4
        img.data[idx] = (color >> 16) & 255
        img.data[idx + 1] = (color >> 8) & 255
        img.data[idx + 2] = color & 255
        img.data[idx + 3] = 255
      }
    }

    // 2) 河/路栅格化色带（覆盖于基础地形之上；数据坐标 0..size）
    for (const r of cfg.rivers || []) rasterizePath(img, size, r.path, r.width, palette.water ?? PALETTE.water)
    for (const r of cfg.roads || []) rasterizePath(img, size, r.path, r.width, palette.road ?? PALETTE.road)

    // 3) canvas → 纹理 → 平面（中心原点 [-size/2, size/2]，y=0；NearestFilter 像素块）
    ctx.putImageData(img, 0, 0)
    this.canvas = canvas
    const texture = new THREE.CanvasTexture(canvas)
    texture.magFilter = THREE.NearestFilter
    texture.minFilter = THREE.NearestFilter
    texture.generateMipmaps = false
    texture.colorSpace = THREE.SRGBColorSpace
    this.texture = texture

    const mesh = new THREE.Mesh(
      new THREE.PlaneGeometry(size, size),
      new THREE.MeshBasicMaterial({ map: texture })
    )
    mesh.rotation.x = -Math.PI / 2
    mesh.position.y = 0

    this.disposeMesh()
    this.mesh = mesh
    this.scene.add(mesh)
  }

  /** 移除并释放旧 mesh（几何体 + 材质 + 纹理） */
  disposeMesh() {
    if (this.mesh) {
      this.scene.remove(this.mesh)
      this.mesh.geometry.dispose()
      if (this.mesh.material) {
        this.mesh.material.map?.dispose()
        this.mesh.material.dispose()
      }
      this.mesh = null
    }
  }

  /** 图层卸载（释放纹理/画布） */
  dispose() {
    this.disposeMesh()
    if (this.texture) { this.texture.dispose(); this.texture = null }
    this.canvas = null
  }
}
