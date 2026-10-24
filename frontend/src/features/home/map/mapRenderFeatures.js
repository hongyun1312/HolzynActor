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

// 要素绘制：建筑 / 农田 / 林地 / 广场 / 围墙 / 码头 / 桥。
//
// 这一层决定了「像不像一个真地方」，逐类说明为什么这么画：
//
//   **建筑**（民居/商铺/工坊/公共，438 栋）
//     卫星俯视看到的不是"房子"，而是**屋顶 + 影子**。所以每栋画四层：
//     ① 投影（西北来光 → 影子落东南，长度随层高）；② 挑檐（墙面色外扩 0.35m，让屋顶"盖"在墙上）；
//     ③ 双坡屋面（同色分向阳/背阴两面，脊线分开）；④ 瓦垄线与门位（仅近景）。
//     少了①②，一栋房子就是一块贴纸；少了③，整个村子是一堆同色调方块——这正是「难看」的来源。
//
//   **农田**（69 块）
//     只画纯色块会像有人拿马克笔涂了几块地。真实航拍里田是**条纹**的（垄沟/作物行），
//     边界是**田埂**（深色细边）。条纹方向跟地块长轴走，颜色按 code 哈希在四种作物里取，
//     相邻田地才不会像复制粘贴。
//
//   **林地**（2 片 —— 数量偏少，属 S4 待补，见开发路径 §11）
//     先铺一层压暗的林下地被，再撒林冠圆（按 code 确定性分布），形成"团簇"而不是"圆点阵"。
//
//   **广场/码头/桥**
//     广场是浅石铺地 + 2 米网格缝；码头/桥是木作 + 板缝，且**桥必须压在河道之上**，
//     所以要素层整体画在道路层之后。
import { Container, Graphics } from 'pixi.js'
import {
  CANOPY, FIELD_CROPS, PAVING, SHADOW, SHADOW_ALPHA, SHADOW_PER_METER, TIMBER, WALL_RUN,
  pick, roofOf, shade, toColor, wallOf
} from './mapPalette'
import { axes, hash32, rectCorners, unit } from './mapGeometry'

/** 每层楼的建筑高度（米）——用于算影子长度与"三层楼"的辨识度。 */
const FLOOR_HEIGHT = 3.2

/** 挑檐外扩（米）：屋顶比墙体外扩的量，边缘露出一圈墙色。 */
const EAVE = 0.35

/** 影子方向（太阳方位角 315°，即西北来光 → 影子朝东南）。 */
const SUN = { x: Math.SQRT1_2, z: Math.SQRT1_2 }

/** 迎光方向（SUN 的反向，即西北）——屋面某个坡面的外法线与它点积越大就越亮。 */
const SUN_DIR = { x: -Math.SQRT1_2, z: -Math.SQRT1_2 }

/**
 * 屋面明暗差（相对屋顶色的比例）。
 *
 * <p>这个值决定"看起来是不是坡屋顶"：0.08（改之前的值）在深色瓦顶上根本看不出两面，
 * 一栋房子就退化成一块贴纸；0.17 时两个坡面差 34%，在任何缩放下都能读出屋脊。</p>
 */
const FACE_LIGHT = 0.17

/** 细节开关阈值（px/米）：低于它不画影子/瓦垄/门位（不足一像素，画了只是噪点）。 */
export const DETAIL_PX_PER_M = 0.75

/** 近景阈值（px/米）：瓦垄、门窗、田垄细节从这一档开始画。 */
export const FINE_PX_PER_M = 1.6

/** 低于该比例尺（px/米）不再逐栋画建筑，改由聚合层表现聚落（决策 #1）。 */
export const MIN_BUILDING_PX_PER_M = 0.24

/**
 * 构建要素层（单层，内部按类型排好序；缩放到细节分档时由相机整层重建）。
 *
 * <p>为什么整层重建而不是"两层切换可见性"：远景/近景的差别不只是"有没有影子"，
 * 还在于**建筑是否要画**（极小比例尺下一栋 8 米的房子不足 2 像素，画出来只是脏点，
 * 该换成村落轮廓）。用两份常驻图层会同时吃掉内存和渲染调用，而整层重建只要 10~20ms、
 * 且只在跨越分档阈值时发生。</p>
 *
 * @param {object} bundle 渲染包
 * @param {{pxPerM?:number, onPick?:Function}} opts 缩放与拾取回调
 * @returns {Container} 要素层
 */
export function buildFeatureLayer(bundle, opts = {}) {
  const layer = new Container()
  layer.label = 'features'
  const features = Array.isArray(bundle?.features) ? bundle.features : []
  const pxPerM = Number(opts.pxPerM) || 1
  const onPick = typeof opts.onPick === 'function' ? opts.onPick : null

  // 绘制顺序：地类（田/林/广场）先，实体（围墙/木作/建筑）后——建筑永远压在地类上。
  const order = { field: 0, forest: 1, plaza: 2, wall: 3, bridge: 4, pier: 5, building: 6 }
  const sorted = [...features].sort(
    (a, b) => (order[a?.kind] ?? 9) - (order[b?.kind] ?? 9)
  )

  for (const f of sorted) {
    // 极小比例尺：一栋 8 米房子不足 2 像素，逐栋画只会得到一层脏点。
    // 此时只保留地类（田/林/广场）与木作，村落轮廓由聚合层负责（决策 #1 的低倍表现）。
    if (pxPerM < MIN_BUILDING_PX_PER_M && (f?.kind === 'building' || f?.kind === 'wall')) {
      continue
    }
    const g = new Graphics()
    switch (f?.kind) {
      case 'field':
        drawField(g, f, pxPerM)
        break
      case 'forest':
        drawForest(g, f, pxPerM)
        break
      case 'plaza':
        drawPlaza(g, f, pxPerM)
        break
      case 'wall':
        drawWall(g, f)
        break
      case 'bridge':
      case 'pier':
        drawTimber(g, f, pxPerM)
        break
      default:
        drawBuilding(g, f, pxPerM)
    }
    g.label = `${f?.kind || 'feature'}:${f?.code || ''}`
    if (onPick) {
      g.eventMode = 'static'
      g.cursor = 'pointer'
      g.on('pointertap', () => onPick(f))
      g.__feature = f
    }
    layer.addChild(g)
  }
  return layer
}

// ==================== 建筑 ====================

/**
 * 一栋房子的局部坐标系（米）。
 *
 * <p>w/d 与 polygon 是同一份几何的两种表示，这里统一按 (x,z,w,d,rot) 重建，
 * 这样"哪条边是长轴"永远是已知的——屋脊、瓦垄、四坡屋顶都靠这个信息，
 * 拿 polygon 去猜长轴只会在正方形房子上摇摆。</p>
 *
 * @param {object} f 要素
 * @param {number} eave 挑檐外扩（米）
 * @returns {{cx:number,cz:number,long:number[],cross:number[],halfLong:number,halfCross:number,u:number[],v:number[]}}
 */
function frameOf(f, eave = 0) {
  const w = Math.max(0.6, Number(f?.w) || 1)
  const d = Math.max(0.6, Number(f?.d) || 1)
  const longIsU = w >= d
  const { u, v } = axes(f?.rot)
  const halfLong = (longIsU ? w : d) / 2 + eave
  const halfCross = (longIsU ? d : w) / 2 + eave
  return {
    cx: Number(f?.x) || 0,
    cz: Number(f?.z) || 0,
    u,
    v,
    long: longIsU ? u : v,
    cross: longIsU ? v : u,
    halfLong,
    halfCross
  }
}

/**
 * 局部坐标 (a 沿长轴, c 沿短轴) → 世界坐标。
 *
 * @param {object} fr {@link frameOf} 的结果
 * @param {number} a 长轴方向偏移（米）
 * @param {number} c 短轴方向偏移（米）
 * @returns {number[]} [x,z]
 */
function at(fr, a, c) {
  return [fr.cx + fr.long[0] * a + fr.cross[0] * c, fr.cz + fr.long[1] * a + fr.cross[1] * c]
}

/** 屋面高度（米）——按层高算，影子长度与"几层楼"直接相关。 */
function roofHeight(f) {
  return FLOOR_HEIGHT * Math.max(1, Math.min(4, Number(f?.floors) || 1))
}

/**
 * 一栋房子：投影 → 墙体 → 屋面（按屋顶形制分坡面）→ 屋脊 → 瓦垄/门位。
 *
 * <p>为什么屋面必须按 roofShape 分：数据里有 gable/hip/shed/flat/thatch 五种形制，
 * 一律画成双坡的话，四坡的祠堂、单坡的敞棚、平顶的盐灶全变成同一种「两块拼一起的卡片」，
 * 屋顶这一层信息就白丢了。分坡面之后，同样一片村子立刻能看出「哪间是正屋、哪间是棚」。</p>
 *
 * @param {Graphics} g 目标
 * @param {object} f 要素
 * @param {number} pxPerM 当前缩放
 */
function drawBuilding(g, f, pxPerM) {
  const detailed = pxPerM >= DETAIL_PX_PER_M
  const fine = pxPerM >= FINE_PX_PER_M
  const roof = roofOf(f)
  const wall = wallOf(f)
  const shape = String(f?.roofShape || 'gable')

  // ① 院土不在这里画：一整片聚落的"地被"由 mapRenderGround 统一铺（单个 Graphics 并集填充，
  //    颜色均一）。逐栋铺一张外扩方块会得到满地"地垫"——每栋房脚下一块亮边矩形，
  //    比不铺更假。

  // ② 投影：沿太阳方向拉长，长度随层高（三层楼影子明显长于平房）
  if (detailed) {
    const len = roofHeight(f) * SHADOW_PER_METER
    const corners = rectCorners(f)
    poly(g, corners.map(([x, z]) => [x + SUN.x * len, z + SUN.z * len]))
    g.fill({ color: SHADOW, alpha: SHADOW_ALPHA })
  }

  // ③ 墙体：俯视图里屋顶比墙体大一圈，露出来的这一圈就是墙（白墙是村镇的底色）。
  //    压暗 0.22 是"檐下背光"，不压的话这一圈会亮成描边，整栋房子像贴纸。
  //    圆顶/尖顶的塔楼墙身也是圆的——方墙 + 圆顶会在四个角露出墙角色块。
  const base = frameOf(f)
  const round = shape === 'dome' || shape === 'spire'
  if (round) {
    g.circle(base.cx, base.cz, Math.min(base.halfLong, base.halfCross) + EAVE)
  } else {
    poly(g, [at(base, -base.halfLong, -base.halfCross), at(base, base.halfLong, -base.halfCross),
      at(base, base.halfLong, base.halfCross), at(base, -base.halfLong, base.halfCross)])
  }
  g.fill({ color: shade(wall, -0.22), alpha: 1 })

  // ④ 屋面：挑檐外扩后再按形制分坡面，逐面上光
  const fr = frameOf(f, EAVE)
  drawRoof(g, fr, shape, roof, detailed, fine)

  // ⑤ 门位：朝向已知（doorX/doorZ/doorDir），画一小段深色门洞，让"门朝街"可读
  if (fine && Number.isFinite(Number(f?.doorX))) {
    const dir = ((Number(f?.doorDir) || 0) * Math.PI) / 180
    const dx = Math.cos(dir)
    const dz = Math.sin(dir)
    g.moveTo(Number(f.doorX) - dz * 0.5, Number(f.doorZ) + dx * 0.5)
    g.lineTo(Number(f.doorX) + dz * 0.5, Number(f.doorZ) - dx * 0.5)
    g.stroke({ width: 0.28, color: shade(wall, -0.62), alpha: 0.9, cap: 'round' })
  }
}

/**
 * 屋面：把每种形制拆成若干坡面，逐面按朝向给明暗。
 *
 * <p>面法线与「迎光方向」（西北）的点积决定明暗，所以同一栋房子北坡亮、南坡暗，
 * 与影子方向（东南）自洽——光、影、面三者一致，眼睛才认这是立体的屋顶。</p>
 *
 * @param {Graphics} g 目标
 * @param {object} fr {@link frameOf} 的结果（已含挑檐）
 * @param {string} shape 屋顶形制
 * @param {number} roof 屋顶色
 * @param {boolean} detailed 是否画脊线
 * @param {boolean} fine 是否画瓦垄
 */
function drawRoof(g, fr, shape, roof, detailed, fine) {
  const { halfLong: A, halfCross: C, long, cross } = fr
  const body = shape === 'thatch' ? shade(roof, 0.06) : roof

  if (shape === 'flat') {
    // 平顶：整块 + 一圈女儿墙（俯视时平顶的辨识点就是"边上一圈压暗的矮墙"）
    poly(g, [at(fr, -A, -C), at(fr, A, -C), at(fr, A, C), at(fr, -A, C)])
    g.fill({ color: shade(body, 0.04), alpha: 1 })
    const rim = Math.min(0.45, A * 0.35, C * 0.35)
    poly(g, [at(fr, -A, -C), at(fr, A, -C), at(fr, A, C), at(fr, -A, C)], true)
    g.stroke({ width: rim * 2, color: shade(body, -0.22), alpha: 0.9 })
    if (fine) {
      // 平顶上的杂物（晒的东西/水箱）：固定位置，不随缩放跳动
      for (let i = 0; i < 3; i++) {
        const a = (unit(`flat${i}`, 1) - 0.5) * A * 1.2
        const c = (unit(`flat${i}`, 2) - 0.5) * C * 1.2
        const s = 0.4 + unit(`flat${i}`, 3) * 0.7
        const [px, pz] = at(fr, a, c)
        g.rect(px - s / 2, pz - s / 2, s, s)
        g.fill({ color: shade(body, -0.3), alpha: 0.8 })
      }
    }
    return
  }

  if (shape === 'dome' || shape === 'spire') {
    // 塔类（灯塔/井亭式攒尖顶）：平面投影是一个圆。
    // 俯视看塔只有一个特征认得出——**塔顶那一小圈亮**。画成"红球 + 四条辐射线"
    // 会得到一颗山楂，所以这里按"塔身圈 → 受光面 → 灯室 → 塔顶尖点"来分层。
    const r = Math.min(A, C)
    const [cx, cz] = [fr.cx, fr.cz]
    g.circle(cx, cz, r)
    g.fill({ color: shade(body, -0.10), alpha: 1 })
    // 受光面（西北）：圆心往迎光方向偏一小块，塔身立刻鼓起来
    g.circle(cx + SUN_DIR.x * r * 0.26, cz + SUN_DIR.z * r * 0.26, r * 0.62)
    g.fill({ color: shade(body, FACE_LIGHT * 0.9), alpha: 0.9 })
    if (shape === 'spire') {
      // 灯室：暖白一圈 + 中心亮点（灯塔在卫星图上就是一个亮斑）
      g.circle(cx, cz, r * 0.44)
      g.fill({ color: 0xf3e2ac, alpha: 0.9 })
      g.circle(cx, cz, r * 0.2)
      g.fill({ color: 0xfffbe8, alpha: 0.95 })
    } else {
      g.circle(cx, cz, r * 0.3)
      g.fill({ color: shade(body, -FACE_LIGHT), alpha: 0.8 })
    }
    // 塔身外圈：把塔和院子分开
    g.circle(cx, cz, r)
    g.stroke({ width: Math.max(0.14, r * 0.09), color: shade(body, -0.45), alpha: 0.65 })
    return
  }

  // 以下都是矩形坡屋顶：gable（双坡）/ hip（四坡）/ shed（单坡）/ thatch（茅草，按四坡处理）
  const hip = shape === 'hip' || shape === 'thatch'
  const inset = Math.min(C, A * 0.55)
  const rA = hip ? -A + inset : -A
  const rB = hip ? A - inset : A
  const faces = []
  // 两个长坡
  faces.push({ pts: [at(fr, rA, 0), at(fr, rB, 0), at(fr, A, C), at(fr, -A, C)], n: cross })
  faces.push({ pts: [at(fr, rA, 0), at(fr, rB, 0), at(fr, A, -C), at(fr, -A, -C)], n: [-cross[0], -cross[1]] })
  if (hip) {
    faces.push({ pts: [at(fr, rB, 0), at(fr, A, C), at(fr, A, -C)], n: long })
    faces.push({ pts: [at(fr, rA, 0), at(fr, -A, C), at(fr, -A, -C)], n: [-long[0], -long[1]] })
  }
  if (shape === 'shed') {
    // 单坡不是"两坡的一半"，它只有**一个**面。之前把它拆成两个面按朝向来算明暗，
    // 结果两个面亮度几乎相同 → 一栋厢房在图上就是一块纯色平行四边形（cardboard）。
    // 真实单坡的读法靠两件事：① 整个面上光，比墙亮；② 低檐那道**水渍/泥渍**带，越靠檐口越脏。
    faces.length = 0
    faces.push({ pts: [at(fr, -A, -C), at(fr, A, -C), at(fr, A, C), at(fr, -A, C)], fixed: FACE_LIGHT * 0.55 })
  }
  for (const face of faces) {
    // fixed = 不随朝向变的固定明暗（单坡用）。坡屋顶的明暗必须来自法线，单坡没有"另一面"可比，
    // 硬按朝向算会让同一排厢房随街道走向忽明忽暗，反而露出破绽。
    const lit = face.n ? face.n[0] * SUN_DIR.x + face.n[1] * SUN_DIR.z : 0
    const amount = face.fixed ?? FACE_LIGHT * lit * (face.lift ?? 1)
    poly(g, face.pts)
    g.fill({ color: shade(body, amount), alpha: 1 })
  }
  if (shape === 'shed') {
    // 低檐水渍带：檐口一线常年的雨水/泥点，一条渐深的暗带
    const bandD = Math.min(C * 0.42, 1.1)
    poly(g, [at(fr, -A, C - bandD), at(fr, A, C - bandD), at(fr, A, C), at(fr, -A, C)])
    g.fill({ color: shade(body, -0.30), alpha: 0.55 })
    poly(g, [at(fr, -A, C - bandD * 0.35), at(fr, A, C - bandD * 0.35), at(fr, A, C), at(fr, -A, C)])
    g.fill({ color: shade(body, -0.42), alpha: 0.5 })
  }

  // ④ 脊线：屋脊是最能让人"看出这是坡屋顶"的一根线（宽度按米给，不然放大就消失）
  if (detailed) {
    // 单坡没有屋脊，最高的那条边在 -C 一侧（檐口在 +C 一侧）
    const ridgeA = shape === 'shed' ? at(fr, -A, -C) : at(fr, rA, 0)
    const ridgeB = shape === 'shed' ? at(fr, A, -C) : at(fr, rB, 0)
    g.moveTo(...ridgeA)
    g.lineTo(...ridgeB)
    g.stroke({ width: Math.max(0.16, 0.22), color: shade(body, -0.42), alpha: 0.85, cap: 'round' })
    // 檐口一圈细压边：屋顶与墙之间那道暗缝，去掉它房子会重新变成"描边贴纸"
    poly(g, [at(fr, -A, -C), at(fr, A, -C), at(fr, A, C), at(fr, -A, C)], true)
    g.stroke({ width: Math.max(0.1, 0.14), color: shade(body, -0.5), alpha: 0.55 })
  }

  // ⑤ 瓦垄（近景）：沿坡向等距细线，密度按开间而非固定像素
  if (fine && shape !== 'thatch') {
    const step = 1.1
    const n = Math.min(24, Math.floor((A * 2) / step))
    for (let i = 1; i < n; i++) {
      const t = -A + (i * (A * 2)) / n
      g.moveTo(...at(fr, t, C))
      g.lineTo(...at(fr, t, -C))
      g.stroke({ width: 0.1, color: shade(body, -0.24), alpha: 0.4 })
    }
  } else if (fine) {
    // 茅草顶没有瓦垄，改成横向草束（沿长轴的三道浅带）
    for (let i = -1; i <= 1; i++) {
      g.moveTo(...at(fr, -A, i * C * 0.5))
      g.lineTo(...at(fr, A, i * C * 0.5))
      g.stroke({ width: Math.max(0.12, 0.16), color: shade(body, 0.14), alpha: 0.3 })
    }
  }
}

// ==================== 农田 ====================

/**
 * 一块田：作物底色 + 垄沟条纹 + 田埂边。
 *
 * @param {Graphics} g 目标
 * @param {object} f 要素
 * @param {number} pxPerM 当前缩放
 */
function drawField(g, f, pxPerM) {
  const corners = rectCorners(f)
  // 作物色以后端给的 roofColor 为准（后端按 6 种作物 + 菜畦调过一轮），
  // 前端再按 code 哈希取色会把整片农区压成 6 种黄绿——同一块地上就分不出"这块种麦、那块种豆"。
  const given = typeof f?.roofColor === 'string' && /^#[0-9a-fA-F]{6}$/.test(f.roofColor)
  const crop = given ? toColor(f.roofColor) : toColor(pick(FIELD_CROPS, hash32(f?.code)))
  const garden = String(f?.semantic || '').includes('garden')
  const w = Number(f?.w) || 1
  const d = Number(f?.d) || 1
  const longIsU = w >= d
  const { u, v } = axes(f?.rot)
  const long = longIsU ? u : v
  const cross = longIsU ? v : u
  const halfLong = (longIsU ? w : d) / 2
  const halfCross = (longIsU ? d : w) / 2
  const cx = Number(f?.x) || 0
  const cz = Number(f?.z) || 0

  poly(g, corners)
  g.fill({ color: crop, alpha: 1 })

  // 垄沟分两档画：
  //   ① 粗垄（3.2 米一档）——整图视野下（0.5 px/米）刚好 1.5 像素，看得见"这块地是条纹的"；
  //   ② 细垄（1.1 米一档）——放大后才画，近景要看得出是作物行。
  // 只画细垄的话，缩到全图时条纹全部落到亚像素，田块就退化成一块纯色板（plywood）。
  const ridged = (step, minPx, widthM, alpha) => {
    if (pxPerM < minPx) return
    const n = Math.min(60, Math.max(2, Math.floor((halfCross * 2) / step)))
    const dark = shade(crop, -0.2)
    for (let i = 0; i < n; i++) {
      const t = -halfCross + ((i + 0.5) * (halfCross * 2)) / n
      const px = cx + cross[0] * t
      const pz = cz + cross[1] * t
      g.moveTo(px - long[0] * halfLong, pz - long[1] * halfLong)
      g.lineTo(px + long[0] * halfLong, pz + long[1] * halfLong)
      g.stroke({ width: widthM, color: dark, alpha })
    }
  }
  ridged(3.2, 0.30, Math.max(0.22, 0.45), 0.28)
  if (!garden) {
    ridged(1.1, 1.1, 0.3, 0.30)
  }

  // 田埂：深色细边（没有它，田块和草地糊在一起）。菜畦是院里的，边要细一点。
  poly(g, corners, true)
  g.stroke({ width: garden ? 0.28 : 0.5, color: shade(crop, garden ? -0.35 : -0.45), alpha: 0.8 })
}

// ==================== 林地 ====================

/**
 * 一片林的轮廓半径系数（0.62~1.0）——按角度做 3 层确定性起伏。
 *
 * <p>为什么不能再画矩形：林地是 S4 按外接框生成的要素（w×d、rot=0），
 * 直接把外接框填成半透明绿，在图上就是一个**边缘笔直的绿方块**；夜里整片地形压暗后，
 * 这个方块比周围亮、边界还是一刀切，看起来像"贴图没加载出来"。真实林地边缘是犬牙交错的。</p>
 *
 * @param {string} code 要素 code（同一条林每次重画都一样）
 * @param {number} a    角度（弧度）
 * @return {number} 半径系数
 */
function forestRadius(code, a) {
  return (
    0.80 +
    0.12 * Math.sin(a * 3 + unit(code, 11) * 6.28) +
    0.07 * Math.sin(a * 7 + unit(code, 12) * 6.28) +
    0.05 * Math.sin(a * 13 + unit(code, 13) * 6.28)
  )
}

/**
 * 一片林：**不规则林缘** + 林下地被 + 确定性分布的林冠团。
 *
 * <p>旧实现先 `fill(rectCorners(f))` 再撒树：矩形边界在图上极其刺眼（尤其夜间），
 * 这不是"林地"，是"一块绿色的贴纸"。现在改成：</p>
 * <ol>
 *   <li>用 32 个采样点 + {@link forestRadius} 画一个有机轮廓，填一层很淡的林下暗色；</li>
 *   <li>树冠团按同一轮廓收进去——林缘的树稀疏、林心的树密，边界自然散开；</li>
 *   <li>不再描边。林地的边界应该由"树越来越稀"表现出来，而不是一条线。</li>
 * </ol>
 *
 * @param {Graphics} g 目标
 * @param {object} f 要素
 * @param {number} pxPerM 当前缩放
 */
function drawForest(g, f, pxPerM) {
  const cx = Number(f?.x) || 0
  const cz = Number(f?.z) || 0
  const w = Math.max(2, Number(f?.w) || 2)
  const d = Math.max(2, Number(f?.d) || 2)
  const rot = ((Number(f?.rot) || 0) * Math.PI) / 180
  const cos = Math.cos(rot)
  const sin = Math.sin(rot)
  const code = f?.code

  // ① 有机林缘：椭圆基底 × 角度起伏。填充极淡（0.22），只用来压住"林地地面更暗"这一层，
  //   轮廓本身不描边，所以看不出任何几何形状。
  const points = []
  const steps = 32
  for (let i = 0; i < steps; i++) {
    const a = (i / steps) * Math.PI * 2
    const k = forestRadius(code, a)
    points.push([(w / 2) * k * Math.cos(a), (d / 2) * k * Math.sin(a)])
  }
  g.beginPath()
  const start = points[0]
  g.moveTo(cx + start[0] * cos - start[1] * sin, cz + start[0] * sin + start[1] * cos)
  for (let i = 1; i < points.length; i++) {
    const p = points[i]
    g.lineTo(cx + p[0] * cos - p[1] * sin, cz + p[0] * sin + p[1] * cos)
  }
  g.closePath()
  g.fill({ color: 0x35482c, alpha: 0.22 })

  const count = Math.max(4, Math.min(160, Math.round((w * d) / 26)))
  for (let i = 0; i < count; i++) {
    const tx = (unit(code, i * 3) - 0.5) * w
    const tz = (unit(code, i * 3 + 1) - 0.5) * d
    // 树心按「到林缘的距离」加权：边缘的树留在原地但更小、更淡，于是林缘是渐隐的
    const ang = Math.atan2(tz, tx)
    const rn = forestRadius(code, ang)
    const nx = tx / (w / 2)
    const nz = tz / (d / 2)
    const rad = Math.hypot(nx, nz) / Math.max(0.35, rn)
    if (rad > 1.0) {
      continue
    }
    const edge = Math.max(0, Math.min(1, (1.0 - rad) * 1.8))
    const r = (1.1 + unit(code, i * 3 + 2) * 1.9) * (0.72 + 0.28 * edge)
    const px = cx + tx * cos - tz * sin
    const pz = cz + tx * sin + tz * cos
    g.circle(px, pz, r)
    g.fill({
      color: toColor(pick(CANOPY, i + hash32(code))),
      alpha: 0.55 + 0.42 * edge,
    })
    // 树冠偏侧受光：加一个错位的浅色小圆，团簇立刻有体积
    if (pxPerM >= 0.6 && edge > 0.45) {
      g.circle(px - r * 0.22, pz - r * 0.22, r * 0.55)
      g.fill({ color: toColor(pick(CANOPY, i + 1 + hash32(code))), alpha: 0.35 * edge })
    }
  }
}

// ==================== 广场 ====================

/** 广场铺装缝隙（米）——跟真实石板/方砖同量级，2 米一档。 */
const PAVING_JOINT = 2.0

/**
 * 广场/晒场：石板铺地 + 铺装缝 + 压边。
 *
 * <p>只填一个纯色矩形的话，它在图上就是一块"没加载出来的灰方块"——
 * 真实俯视的广场全是**缝**：2 米一档的方砖线 + 四周一圈压暗的路缘。
 * 缝必须按局部坐标逐条画（按屏幕像素画会随缩放糊成一片）。</p>
 *
 * @param {Graphics} g 目标
 * @param {object} f 要素
 * @param {number} pxPerM 当前缩放
 */
function drawPlaza(g, f, pxPerM) {
  const paving = toColor(PAVING)
  const isRect = !(Array.isArray(f?.polygon) && f.polygon.length >= 3) || f.polygon.length === 4
  const frC = frameOf(f)
  const outline = isRect
    ? [at(frC, -frC.halfLong, -frC.halfCross), at(frC, frC.halfLong, -frC.halfCross),
      at(frC, frC.halfLong, frC.halfCross), at(frC, -frC.halfLong, frC.halfCross)]
    : f.polygon

  poly(g, outline)
  g.fill({ color: paving, alpha: 1 })

  // 石板：一格一块，逐块深浅不同。
  // 铺装最怕"整块纯色"——纯色 + 细缝还是像一块没加载的贴图；
  // 真实石板场是几十块深浅不一的石头拼出来的，饱和度/亮度都有肉眼可见的抖动。
  if (isRect && pxPerM >= 1.2) {
    const { halfLong: A, halfCross: C } = frC
    const cols = Math.max(1, Math.round((A * 2) / PAVING_JOINT))
    const rows = Math.max(1, Math.round((C * 2) / PAVING_JOINT))
    const sw = (A * 2) / cols
    const sh = (C * 2) / rows
    for (let i = 0; i < cols; i++) {
      for (let j = 0; j < rows; j++) {
        // 错缝：石板场不会横平竖直对齐，奇偶行各错半块
        const shift = (j % 2) * 0.5
        const a0 = -A + ((i + shift) % cols) * sw
        const c0 = -C + j * sh
        const v = unit(`${f?.code || 'plaza'}#slab`, i * 37 + j * 11)
        poly(g, [at(frC, a0, c0), at(frC, a0 + sw, c0),
          at(frC, a0 + sw, c0 + sh), at(frC, a0, c0 + sh)])
        g.fill({ color: shade(paving, (v - 0.5) * 0.16 - 0.05), alpha: 0.9 })
      }
    }
  }

  // 铺装缝：低于 0.8 px/米 时缝隙不足一像素，画了只会把广场糊成一块脏灰，所以只在近景画
  if (isRect && pxPerM >= 0.8) {
    const line = shade(paving, -0.26)
    const { halfLong: A, halfCross: C } = frC
    for (let a = -A + PAVING_JOINT; a < A - 0.2; a += PAVING_JOINT) {
      g.moveTo(...at(frC, a, -C))
      g.lineTo(...at(frC, a, C))
    }
    for (let c = -C + PAVING_JOINT; c < C - 0.2; c += PAVING_JOINT) {
      g.moveTo(...at(frC, -A, c))
      g.lineTo(...at(frC, A, c))
    }
    g.stroke({ width: Math.max(0.14, 0.18), color: line, alpha: 0.26 })
  }

  // 路缘：广场四周围一圈压暗的边石，把铺地和草地/土路分开
  poly(g, outline, true)
  g.stroke({ width: Math.max(0.35, 0.5), color: shade(paving, -0.5), alpha: 0.5 })
}

// ==================== 围墙 ====================

/**
 * 院落围墙：薄条 + 一顶亮边（俯视时墙顶是"一条亮线"）。
 *
 * <p>墙必须比屋顶暗：墙是"土的/石的"，屋顶是"瓦的"。墙画亮了，一整个院子看着
 * 像被人用白笔描了一圈，比不画墙还假。</p>
 *
 * @param {Graphics} g 目标
 * @param {object} f 要素
 */
function drawWall(g, f) {
  const corners = rectCorners(f)
  poly(g, corners)
  g.fill({ color: toColor(WALL_RUN), alpha: 1 })
  // 墙顶受光：只给朝西北的那条长边一点亮，其它边不动（不然墙顶会亮成一圈白线）
  const fr = frameOf(f)
  const { long } = fr
  const lit = -long[0] * SUN_DIR.x - long[1] * SUN_DIR.z
  if (lit > 0.2) {
    g.moveTo(...at(fr, -fr.halfLong, -fr.halfCross))
    g.lineTo(...at(fr, fr.halfLong, -fr.halfCross))
    g.stroke({ width: Math.max(0.12, 0.16), color: shade(toColor(WALL_RUN), 0.16), alpha: 0.6 })
  }
}

// ==================== 木作（码头/桥） ====================

/**
 * 木栈桥/桥面：木色底 + 板缝。
 *
 * @param {Graphics} g 目标
 * @param {object} f 要素
 * @param {number} pxPerM 当前缩放
 */
function drawTimber(g, f, pxPerM) {
  const corners = rectCorners(f)
  poly(g, corners)
  g.fill({ color: toColor(TIMBER), alpha: 1 })
  if (pxPerM >= 0.8) {
    const w = Number(f?.w) || 1
    const d = Number(f?.d) || 1
    const longIsU = w >= d
    const { u, v } = axes(f?.rot)
    const long = longIsU ? u : v
    const cross = longIsU ? v : u
    const halfLong = (longIsU ? w : d) / 2
    const halfCross = (longIsU ? d : w) / 2
    const cx = Number(f?.x) || 0
    const cz = Number(f?.z) || 0
    const step = 0.7
    const n = Math.min(60, Math.max(2, Math.floor((halfLong * 2) / step)))
    for (let i = 1; i < n; i++) {
      const t = -halfLong + (i * (halfLong * 2)) / n
      const px = cx + long[0] * t
      const pz = cz + long[1] * t
      g.moveTo(px + cross[0] * halfCross, pz + cross[1] * halfCross)
      g.lineTo(px - cross[0] * halfCross, pz - cross[1] * halfCross)
      g.stroke({ width: 0.08, color: shade(toColor(TIMBER), -0.35), alpha: 0.6 })
    }
  }
}

// ==================== 小工具 ====================

/**
 * 写一个多边形路径（不描边不填充，由调用方决定）。
 *
 * @param {Graphics} g 目标
 * @param {number[][]} pts 顶点（米）
 * @param {boolean} close 是否闭合
 */
function poly(g, pts, close = false) {
  if (!Array.isArray(pts) || pts.length === 0) return
  g.moveTo(Number(pts[0][0]) || 0, Number(pts[0][1]) || 0)
  for (let i = 1; i < pts.length; i++) {
    g.lineTo(Number(pts[i][0]) || 0, Number(pts[i][1]) || 0)
  }
  if (close) g.lineTo(Number(pts[0][0]) || 0, Number(pts[0][1]) || 0)
}
