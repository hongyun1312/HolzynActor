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

// 地图舞台：PixiJS v8 的相机 + 图层管理 + LOD + 昼夜。
//
// 设计取舍（三条都会影响"手感"，值得记住）：
//  ① **相机在米上做，不在像素上做**：`scale` = 每米多少像素。地图数据全是米，
//     于是"缩放"是一个乘数、"平移"是米到屏幕的偏移，不会出现"改了 DPI 就错位"。
//  ② **拾取自己做，不依赖 Pixi 事件系统**：交互只有三类（点击选中、拖拽平移、滚轮缩放），
//     而 Pixi v8 的 FederatedEvent 与自定义拖拽容易互相抢事件。这里直接在 canvas 上
//     听 DOM 事件、把屏幕坐标反算成米、用多边形包含判定命中——行为确定、易测、无抢事件问题。
//  ③ **LOD 按"分档整层重建"**：跨档时重建要素层（远景纯色块 / 中景带阴影 / 近景带瓦垄），
//     档内不重建。比"每帧改细节"省得多，也比"两份常驻图层"省内存。
//
// ⚠ PixiJS v8 的 multiply/screen 等混合模式被拆到 `pixi.js/advanced-blend-modes` 子包，
//   不 import 它时 `blendMode = 'multiply'` 会静默退化成普通混合（夜景变成一层蓝糊）。
import 'pixi.js/advanced-blend-modes'
import { Application, Assets, Container, Graphics, Sprite } from 'pixi.js'
import { buildFeatureLayer, DETAIL_PX_PER_M, FINE_PX_PER_M } from './mapRenderFeatures'
import { buildGroundLayer } from './mapRenderGround'
import { buildRoadLayer } from './mapRenderRoads'
import { drawCharacters } from './mapRenderCharacters'
import { NIGHT_TINT } from './mapPalette'
import { boundsOf, pointAtArc, polylineLength } from './mapGeometry'

/** 缩放范围（px/米）：0.12 ≈ 整岛塞进窄栏；24 ≈ 一栋房子占满屏幕。 */
const MIN_SCALE = 0.12
const MAX_SCALE = 24

/** 点击容差（屏幕像素）：按下到抬起位移超过它就当拖拽，不触发拾取。 */
const TAP_SLOP = 5

/** 聚合层显示阈值（px/米）：低于它不画单体建筑，只画聚落轮廓（决策 #1）。 */
const CLUSTER_PX_PER_M = 0.24

/** 时间流速：现实 1 秒 = 世界 1 分钟（一天 24 分钟）。 */
const MINUTES_PER_SECOND = 1

/**
 * 现实 1 秒 = 世界 60 秒（与 MINUTES_PER_SECOND 同源，只是换成秒）。
 *
 * <p>为什么要有这个换算：服务端给的是「游戏秒」（轨迹的出发/到达都是游戏秒），
 * 而前端推进用的是真实毫秒。两个单位不统一，插值就会慢 60 倍或快 60 倍——
 * 表现是「人走到一半突然瞬移到终点」。</p>
 */
const GAME_SECONDS_PER_REAL_SECOND = MINUTES_PER_SECOND * 60

/** 角色重绘节流（毫秒）：点跟着时钟走，不需要每帧重画。 */
const CHAR_TICK_MS = 120

/**
 * 地图舞台。
 *
 * <pre>
 * const stage = new MapStage(el, { onPick, onView })
 * await stage.init()
 * stage.setBundle(bundle)       // 道路 + 要素 + 聚合
 * await stage.setTerrain(url)   // 底图（URL 带版本号）
 * stage.fitToSettlement()       // 首屏对准聚落，而不是对着大海
 * </pre>
 */
export class MapStage {
  /**
   * @param {HTMLElement} host 挂载点（尺寸由它决定）
   * @param {{onPick?:Function, onView?:Function}} opts 回调
   */
  constructor(host, opts = {}) {
    this.host = host
    this.onPick = typeof opts.onPick === 'function' ? opts.onPick : null
    this.onView = typeof opts.onView === 'function' ? opts.onView : null
    this.onClock = typeof opts.onClock === 'function' ? opts.onClock : null
    this.app = null
    this.root = new Container()
    this.terrainLayer = new Container()
    this.groundLayer = null
    this.roadLayer = null
    this.featureLayer = null
    this.clusterLayer = null
    this.highlight = new Graphics()
    this.nightOverlay = null
    this.terrainSprite = null
    this.bundle = null
    this.scale = 1
    this.tx = 0
    this.ty = 0
    this.band = ''
    this.roadBand = ''
    this.night = 0
    this.bounds = null
    this.hitCache = []
    this.highlightCode = null
    this.charLayer = null
    this.characters = []
    this.show = { roads: true, features: true, clusters: true, characters: true, base: true, roadGrades: null }
    this.destroyed = false
    this.drag = null
    // ---- 写实底图（composite.png 三档 LOD）----
    this.dayLods = null          // { '4096': url, '2048': url, '1024': url }
    this.nightLods = null
    this.compositeSprite = null  // 写实底图 Sprite（地图空间，尺寸 = 地图米数）
    this.compositeSize = 0       // 当前加载的档位（1024/2048/4096）
    this.compositeNight = false  // 当前是否显示夜景底图
    this._compositeSeq = 0       // 异步切档的序号，防旧图覆盖新图
    // ---- 环境（季节/天气/潮汐）----
    this.ambient = null
    this.ambientLayer = null
    // ---- 角色时钟（服务端权威坐标 → 图上位置）----
    this.gameMinute = null       // 当天第几分钟（null=未启动）
    this._charTimer = null
    this._serverGameSecond = 0   // 服务端给的「此刻游戏秒」（快照锚点）
    this._serverAt = 0           // 快照到达时的本地时间戳
    this._clockPaused = false    // 世界是否暂停（暂停就停表，别自己往前跑）
    this._actors = []            // 服务端角色快照（含在途轨迹）
    // ---- 布局编辑（拖拽/旋转草稿；保存前只在本地生效）----
    this.editMode = false
    this.drafts = new Map()      // code → {code,name,baseX,baseZ,baseRot,x,z,rot}
    this.editSelectedCode = ''   // 当前选中的建筑 code
    this.featureDrag = null      // 拖拽建筑中的指针状态
  }

  /**
   * 初始化 Pixi（WebGL）。
   *
   * @returns {Promise<void>}
   */
  async init() {
    const app = new Application()
    await app.init({
      background: '#0b1a24',
      antialias: true,
      resizeTo: this.host,
      resolution: Math.min(2, window.devicePixelRatio || 1),
      autoDensity: true,
      preference: 'webgl'
    })
    if (this.destroyed) {
      app.destroy(true, { children: true })
      return
    }
    this.app = app
    this.host.appendChild(app.canvas)
    Object.assign(app.canvas.style, {
      width: '100%',
      height: '100%',
      display: 'block',
      touchAction: 'none'
    })

    this.root.label = 'map-root'
    this.terrainLayer.label = 'terrain'
    this.highlight.label = 'highlight'
    this.root.addChild(this.terrainLayer)
    app.stage.addChild(this.root)

    // 角色点层：挂在 stage 上（未缩放），每帧按相机重投影 —— 点永远 4 像素
    this.charLayer = new Graphics()
    this.charLayer.label = 'characters'
    app.stage.addChild(this.charLayer)

    // 昼夜：全屏乘算色片（世界之上、UI 之下）
    this.nightOverlay = new Graphics()
    this.nightOverlay.label = 'night'
    this.nightOverlay.blendMode = 'multiply'
    this.nightOverlay.visible = false
    app.stage.addChild(this.nightOverlay)

    this._bindDom()
    this.applyCamera()
  }

  /**
   * 装入渲染包。
   *
   * @param {object} bundle /map/bundle 的 data
   */
  setBundle(bundle) {
    this.bundle = bundle || null
    // 新数据 = 服务端真相：本地草稿一律作废（保存成功后也会走到这里）
    this.drafts = new Map()
    this.editSelectedCode = ''
    this.featureDrag = null
    this.bounds = boundsOf((bundle?.features || []).filter((f) => f?.kind === 'building'))
    this.hitCache = (bundle?.features || []).map((f) => ({
      f,
      corners: Array.isArray(f?.polygon) && f.polygon.length >= 3
        ? f.polygon.map((p) => [Number(p[0]) || 0, Number(p[1]) || 0])
        : null
    }))
    this.band = ''
    this.roadBand = ''
    // 写实底图：后端三档 LOD。没有就保持旧的地形底图（不白屏、不报错）。
    const ov = bundle?.overview || null
    this.dayLods = ov?.lods || (ov?.compositeUrl ? { 4096: ov.compositeUrl } : null)
    this.nightLods = ov?.nightLods || (ov?.compositeNightUrl ? { 4096: ov.compositeNightUrl } : null)
    this.setAmbient(bundle?.ambient || null)
    this.rebuildGround()
    this.rebuildRoads()
    this.rebuildFeatures(true)
    this.rebuildCluster()
    // 有写实底图就叠上去；没有就走原来的地形贴图
    if (this.dayLods) this.applyLod(true)
    else this.setCompositeVisible(false)
    // 角色日程：有就按时钟在图上走动
    this.buildCharactersFromBundle(bundle)
    this.applyCamera()
  }

  /**
   * 写实底图开/关（关掉回到矢量绘制；矢量层不会因为底图存在而消失，只是变淡）。
   *
   * @param {boolean} on 是否显示
   */
  setCompositeVisible(on) {
    this.show.base = on !== false
    if (this.compositeSprite) this.compositeSprite.visible = this.show.base
  }

  /**
   * 按当前缩放选 LOD 档位并异步加载写实底图。
   *
   * <p>切档不闪白：新图加载完成后才加到图层里，旧图最后移除；并用序号丢弃过期的加载结果
   * （快速连续缩放会同时发起好几档请求，后回来的旧档会盖掉新档）。</p>
   *
   * @param {boolean} force 强制重新加载（换数据时）
   */
  async applyLod(force = false) {
    if (!this.app || !this.dayLods) return
    const size = this.scale < 0.5 ? 1024 : this.scale < 2 ? 2048 : 4096
    const night = this.night > 0.5 && this.nightLods
    if (!force && size === this.compositeSize && night === this.compositeNight
      && this.compositeSprite) return
    const sources = night ? this.nightLods : this.dayLods
    const url = sources?.[String(size)] || sources?.['4096'] || sources?.['2048'] || sources?.['1024']
    if (!url) return
    const seq = ++this._compositeSeq
    let texture = null
    try {
      texture = await Assets.load(url)
    } catch (e) {
      // 底图加载失败不该影响地图本身（还有矢量层可看）
      return
    }
    if (this.destroyed || !this.app || seq !== this._compositeSeq) return
    if (texture?.source) texture.source.scaleMode = 'linear'
    const sprite = new Sprite(texture)
    sprite.label = 'composite-sprite'
    const meters = Number(this.bundle?.overview?.widthMeters) || 1024
    sprite.width = meters
    sprite.height = meters
    sprite.visible = this.show.base !== false
    const old = this.compositeSprite
    this.terrainLayer.addChild(sprite)
    this.compositeSprite = sprite
    this.compositeSize = size
    this.compositeNight = night
    if (old) {
      this.terrainLayer.removeChild(old)
      old.destroy()
    }
    // 写实底图在时，矢量层降为「叠加信息层」：道路/要素调淡，地面层隐藏（底图已经有地面了）
    this.applyCompositeOpacity()
  }

  /** 有写实底图时矢量层半透明、地面层隐藏；没有时恢复原样。 */
  applyCompositeOpacity() {
    const has = !!this.compositeSprite
    if (this.groundLayer) this.groundLayer.visible = !has && this.show.features
    if (this.roadLayer) this.roadLayer.alpha = has ? 0.55 : 1
    if (this.featureLayer) this.featureLayer.alpha = has ? 0.9 : 1
  }

  /**
   * 环境快照（季节/天气/潮汐/湿气）：来自 bundle.ambient。
   *
   * @param {object|null} ambient 环境
   */
  setAmbient(ambient) {
    this.ambient = ambient || null
    this.rebuildAmbient()
    if (ambient && typeof ambient.isNight === 'boolean') {
      this.setNight(ambient.isNight ? 0.75 : 0.05)
    }
  }

  /** 环境色叠加层（季节色 + 雨湿冷灰）。只做一次重绘，不参与每帧。 */
  rebuildAmbient() {
    if (!this.app) return
    const g = this.ambientLayer || new Graphics()
    g.clear()
    g.label = 'ambient'
    const a = this.ambient
    if (a) {
      const meters = Number(this.bundle?.overview?.widthMeters) || 1024
      const tint = Number(String(a.groundTint || '').replace('#', '0x'))
      const strength = Number(a.tintStrength) || 0
      if (Number.isFinite(tint) && strength > 0.001) {
        g.rect(0, 0, meters, meters)
        g.fill({ color: tint, alpha: Math.min(0.28, strength) })
      }
      const wet = Number(a.wetness) || 0
      if (wet > 0.3) {
        g.rect(0, 0, meters, meters)
        g.fill({ color: 0x4a5a66, alpha: Math.min(0.16, (wet - 0.3) * 0.22) })
      }
    }
    if (!this.ambientLayer) {
      this.ambientLayer = g
      this.root.addChild(g)
    }
    this.ensureOrder()
  }

  /**
   * 换地形底图。
   *
   * <p>用 `Assets.load` 而不是 `Texture.from(url)`：v8 里后者只接受"已在缓存里的别名"，
   * 传 URL 会拿到空纹理（表现是地图一片纯背景色，且没有任何报错）——这个坑很隐蔽。</p>
   *
   * @param {string} url 相对/绝对地址
   * @returns {Promise<void>}
   */
  async setTerrain(url) {
    if (!this.app || !url) return
    const texture = await Assets.load(url)
    if (this.destroyed || !this.app) return
    // 卫星底图用线性采样：放大时是"更近的航拍"，不是马赛克方块
    if (texture?.source) texture.source.scaleMode = 'linear'
    const sprite = new Sprite(texture)
    sprite.label = 'terrain-sprite'
    const old = this.terrainLayer.children.slice()
    this.terrainLayer.addChild(sprite)
    for (const child of old) {
      this.terrainLayer.removeChild(child)
      child.destroy()
    }
    this.terrainSprite = sprite
  }

  // ==================== 图层 ====================

  /** 聚落地面层（与比例尺无关，换数据时重建一次）。 */
  rebuildGround() {
    if (!this.app) return
    const next = buildGroundLayer(this.bundle)
    if (this.groundLayer) {
      this.root.removeChild(this.groundLayer)
      this.groundLayer.destroy({ children: true })
    }
    this.groundLayer = next
    this.ensureOrder()
  }

  /** 道路层（比例尺跨档时重建：低倍下路基不足 1 像素，画了只是糊）。 */
  rebuildRoads() {
    if (!this.app || !this.bundle) return
    const next = buildRoadLayer(this.bundle, {
      pxPerM: this.scale,
      grades: this.show.roadGrades
    })
    next.visible = this.show.roads
    if (this.roadLayer) {
      this.root.removeChild(this.roadLayer)
      this.roadLayer.destroy({ children: true })
    }
    this.roadLayer = next
    this.ensureOrder()
  }

  /**
   * 调试用：只看某几个等级的道路（0=田埂土路 / 1=村庄道路 / 2=镇内主街 / 3=干线官道）。
   *
   * <p>为什么要这个开关：验收时经常需要确认「某条多余的路是哪个等级生成的」，
   * 越过地形纹理、只留一层路，一眼就能看出是官道/主街/村道/田埂哪一档的问题。</p>
   *
   * @param {number[]|null} grades 要显示的等级；null = 全部显示
   */
  setRoadGrades(grades) {
    this.show.roadGrades = Array.isArray(grades) ? [...grades] : null
    this.rebuildRoads()
  }

  /**
   * 要素层（跨档重建）。
   *
   * @param {boolean} force 强制重建（换数据时必须）
   */
  rebuildFeatures(force = false) {
    if (!this.app || !this.bundle) return
    const band = this.bandOf(this.scale)
    if (!force && band === this.band) return
    this.band = band
    const next = buildFeatureLayer(this.bundle, { pxPerM: this.scale })
    next.visible = this.show.features
    if (this.featureLayer) {
      this.root.removeChild(this.featureLayer)
      this.featureLayer.destroy({ children: true })
    }
    this.featureLayer = next
    this.ensureOrder()
    // LOD 换档会重建整个要素层：草稿（拖了一半的房子）必须重新贴回去，
    // 否则用户轻轻滚一下滚轮，刚拖好的位置就"弹回原位"了。
    this.reapplyDrafts()
  }

  /** 聚合层（极低倍下的聚落轮廓）。 */
  rebuildCluster() {
    if (!this.app) return
    const g = new Graphics()
    g.label = 'clusters'
    for (const c of this.bundle?.groups || []) {
      const cx = Number(c?.cx)
      const cz = Number(c?.cz)
      const r = Number(c?.radius)
      if (![cx, cz, r].every(Number.isFinite) || r <= 0) continue
      g.circle(cx, cz, r * 1.05)
      g.fill({ color: 0xe8d9b8, alpha: 0.15 })
    }
    g.visible = this.show.clusters
    if (this.clusterLayer) {
      this.root.removeChild(this.clusterLayer)
      this.clusterLayer.destroy({ children: true })
    }
    this.clusterLayer = g
    this.ensureOrder()
  }

  /** 固定层序：地形 → 环境色 → 聚落地面 → 道路 → 聚合 → 要素 → 高亮（高亮永远最上面）。 */
  ensureOrder() {
    if (!this.root) return
    const order = [this.terrainLayer, this.ambientLayer, this.groundLayer, this.roadLayer,
      this.clusterLayer, this.featureLayer, this.highlight]
    this.root.removeChildren()
    for (const layer of order) {
      if (layer) this.root.addChild(layer)
    }
  }

  /**
   * 图层开关（对应旧组件的 道路/建筑 开关）。
   *
   * @param {{roads?:boolean, features?:boolean, clusters?:boolean}} show 开关
   */
  setLayers(show = {}) {
    this.show = { ...this.show, ...show }
    if (this.roadLayer) this.roadLayer.visible = this.show.roads
    if (this.groundLayer) this.groundLayer.visible = this.show.features
    if (this.featureLayer) this.featureLayer.visible = this.show.features
    if (this.clusterLayer) {
      this.clusterLayer.visible = this.show.clusters && this.scale < CLUSTER_PX_PER_M * 1.6
    }
    if (this.compositeSprite) this.compositeSprite.visible = this.show.base !== false
    if (this.ambientLayer) this.ambientLayer.visible = this.show.base !== false
    this.applyCompositeOpacity()
    this.redrawCharacters()
  }

  // ==================== 相机 ====================

  /**
   * 视图分档（决定要素层细节等级）。
   *
   * @param {number} s px/米
   * @returns {string} cluster / flat / detail / fine
   */
  bandOf(s) {
    if (s < CLUSTER_PX_PER_M) return 'cluster'
    if (s < DETAIL_PX_PER_M) return 'flat'
    if (s < FINE_PX_PER_M) return 'detail'
    return 'fine'
  }

  /** 把相机套到渲染树上（唯一写 transform 的入口）。 */
  applyCamera() {
    if (!this.app) return
    this.root.scale.set(this.scale)
    this.root.position.set(this.tx, this.ty)
    if (this.clusterLayer) {
      this.clusterLayer.visible = this.show.clusters && this.scale < CLUSTER_PX_PER_M * 1.6
    }
    this.redrawCharacters()
    this.layoutNight()
    this.scheduleLod()
    this.emitView()
  }

  /**
   * 设置角色点（米坐标；由调用方决定坐标从哪来）。
   *
   * @param {object[]} items [{ key, x, z, special, selected }]
   */
  setCharacters(items) {
    this.characters = Array.isArray(items) ? items : []
    this.redrawCharacters()
  }

  // ==================== 角色日程（按世界时钟走动） ====================

  /**
   * 从渲染包装角色，并启动「世界时钟 → 图上位置」的驱动。
   *
   * <p><b>为什么角色要按时钟走</b>：一张标着「12 户 36 人」的图，如果所有人点永远钉在自家门口，
   * 那它只是一张图，不是一座镇。后端把每个人的一天拆成日程段（睡/家务/干活/吃饭/跑腿/闲聊），
   * 每段都带「在哪个要素、什么坐标」；这里只要把「现在是第几分钟」代进去，
   * 白天的镇子就会自然出现「田里有人、渡口有人、广场有人」的分布——这才是活的镇子。</p>
   *
   * @param {object|null} bundle 渲染包
   */
  buildCharactersFromBundle(bundle) {
    this.stopCharacterClock()
    const raw = Array.isArray(bundle?.characters) ? bundle.characters : []
    this.characters = raw.map((c) => {
      const segs = Array.isArray(c?.segments) ? c.segments : []
      const first = segs[0] || null
      const x = Number(c?.x)
      const z = Number(c?.z)
      // 后端已经把「此刻坐标」叠进来了；没有就退回日程首段落点（不能退到 (0,0)：那是海角）
      const hasPos = Number.isFinite(x) && Number.isFinite(z) && (x !== 0 || z !== 0)
      return {
        key: c?.ref || c?.name || '',
        ref: c?.ref || '',
        name: c?.name || '',
        activity: c?.activity || '',
        activityLabel: c?.activityLabel || '',
        indoor: !!c?.indoor,
        segments: segs,
        x: hasPos ? x : Number(first?.x ?? 0),
        z: hasPos ? z : Number(first?.z ?? 0),
        heading: Number(c?.heading ?? 0),
        status: c?.status || 'static',
        path: null,
        special: !!c?.special,
        selected: false
      }
    }).filter((c) => c.ref || c.segments.length > 0)
    if (bundle?.clock) this.applyClock(bundle.clock)
    if (this.characters.length === 0) {
      this.redrawCharacters()
      return
    }
    this.placeFromActors()
    this.redrawCharacters()
    this.startCharacterClock()
  }

  /**
   * 采用服务端时钟（唯一时间源）。
   *
   * <p><b>为什么前端不再自己起一条时间河</b>：旧实现从 ambient.minuteOfDay 起算、按 1 秒 1 分钟
   * 一路往前跑——世界暂停了它照跑，世界倍速了它还是 1:1。于是「后端说天黑了、图上还是下午」。
   * 现在时间由服务端给锚点 + 本地只在两次快照之间推算，暂停即停表。</p>
   *
   * @param {object} clock {gameSecond, paused, rate}
   */
  applyClock(clock) {
    const gs = Number(clock?.gameSecond)
    if (!Number.isFinite(gs)) return
    this._serverGameSecond = gs
    this._serverAt = nowMs()
    this._clockPaused = !!clock?.paused
    this.gameMinute = ((gs / 60) % 1440 + 1440) % 1440
  }

  /**
   * 应用一次服务端角色快照（{@code GET /map/actors}）。
   *
   * <p>坐标与轨迹都来自后端：前端只做「按游戏秒在折线上取点」这一件事。
   * 这样图上演的位置就是 AI 决策读到的位置（验收 A4 的对账基础）。</p>
   *
   * @param {object} payload {clock, actors:[{ref,x,z,heading,status,indoor,activity,path}]}
   */
  applyActorSnapshot(payload) {
    if (!payload) return
    if (payload.clock) this.applyClock(payload.clock)
    const actors = Array.isArray(payload.actors) ? payload.actors : []
    if (actors.length === 0) return
    this._actors = actors
    const byRef = new Map(this.characters.map((c) => [c.ref, c]))
    for (const a of actors) {
      const c = byRef.get(a?.ref)
      if (!c) continue
      c.activity = a.activity || c.activity || ''
      c.indoor = !!a.indoor
      c.heading = Number(a.heading || 0)
      c.status = a.status || 'static'
      const path = a.path && Array.isArray(a.path.points) && a.path.points.length >= 2 ? a.path : null
      c.path = path
      if (!path) {
        const x = Number(a.x)
        const z = Number(a.z)
        if (Number.isFinite(x) && Number.isFinite(z)) {
          c.x = x
          c.z = z
        }
      }
    }
    this.placeFromActors()
    this.redrawCharacters()
  }

  /** 此刻的游戏秒（暂停则冻结在快照锚点）。 */
  currentGameSecond() {
    if (this._clockPaused || !this._serverAt) return this._serverGameSecond
    return this._serverGameSecond + ((nowMs() - this._serverAt) / 1000) * GAME_SECONDS_PER_REAL_SECOND
  }

  /** 启动定时器（节流重绘；不占渲染帧）。 */
  startCharacterClock() {
    if (this._charTimer) return
    this._days = Math.floor(this.currentGameSecond() / 86400)
    const tick = () => {
      if (this.destroyed || !this.app) return
      const gs = this.currentGameSecond()
      const day = Math.floor(gs / 86400)
      const crossedDay = day !== this._days
      if (crossedDay) this._days = day
      const wrapped = ((gs / 60) % 1440 + 1440) % 1440
      this.gameMinute = wrapped
      this.placeFromActors()
      this.redrawCharacters()
      if (this.onClock) this.onClock({ minute: wrapped, crossedDay })
    }
    this._charTimer = setInterval(tick, CHAR_TICK_MS)
  }

  /** 停掉时钟（换数据/销毁时必须调用，否则闭包会一直持有舞台）。 */
  stopCharacterClock() {
    if (this._charTimer) {
      clearInterval(this._charTimer)
      this._charTimer = null
    }
  }

  /**
   * 把每个角色放到他此刻的位置：有轨迹就沿轨迹按游戏秒取点，没有就停在服务端坐标。
   *
   * <p><b>为什么这里不再算「从上一段走到下一段」</b>：路线已经在后端算好了（沿路、绕房、过桥），
   * 前端再插值一次只会得到一条「两点一线的穿墙斜线」，而且与服务端对不上。
   * 现在这里只做一件纯函数的事：{@code pointAt(折线, t × 总长)}。</p>
   */
  placeFromActors() {
    const gs = this.currentGameSecond()
    for (const c of this.characters) {
      const path = c.path
      if (!path) continue
      const points = path.points
      const depart = Number(path.departGameSecond)
      const arrive = Number(path.arriveGameSecond)
      const total = polylineLength(points)
      if (!Number.isFinite(total) || total <= 0) continue
      const span = Math.max(1, (Number.isFinite(arrive) ? arrive : depart) - (Number.isFinite(depart) ? depart : 0))
      const t = Math.max(0, Math.min(1, (gs - (Number.isFinite(depart) ? depart : 0)) / span))
      const p = pointAtArc(points, t * total)
      if (!p) continue
      c.x = p[0]
      c.z = p[1]
    }
  }

  /** 选中一个角色（按 ref/key 匹配）。 */
  selectCharacter(ref) {
    const key = String(ref || '')
    for (const c of this.characters) {
      c.selected = !!key && (c.ref === key || c.key === key)
    }
    this.redrawCharacters()
  }

  // ==================== 布局编辑（拖拽/旋转/草稿） ====================

  /**
   * 开/关编辑态。
   *
   * <p>编辑态下：点建筑 = 选中（建立一个草稿），拖 = 移动（0.5 米吸附），
   * 空白处拖 = 平移地图。关掉编辑态会把草稿全部还原——「取消」不该留下半成品。</p>
   *
   * @param {boolean} on 是否编辑
   */
  setEditMode(on) {
    const next = !!on
    if (this.editMode && !next) this.clearDrafts(true)
    this.editMode = next
    if (this.host) this.host.style.cursor = next ? 'default' : ''
    this.emitEdit()
  }

  /** 编辑态摘要（给 Vue 面板渲染）。 */
  editSnapshot() {
    const drafts = []
    for (const d of this.drafts.values()) {
      if (d.x === d.baseX && d.z === d.baseZ && d.rot === d.baseRot) continue
      drafts.push({ code: d.code, name: d.name, x: d.x, z: d.z, rot: d.rot })
    }
    let selected = null
    if (this.editSelectedCode) {
      const d = this.drafts.get(this.editSelectedCode)
      const f = this.featureOf(this.editSelectedCode)
      selected = {
        code: this.editSelectedCode,
        name: (f && f.name) || this.editSelectedCode,
        x: d ? d.x : Number(f?.x) || 0,
        z: d ? d.z : Number(f?.z) || 0,
        rot: d ? d.rot : Number(f?.rot) || 0,
        moved: !!d && (d.x !== d.baseX || d.z !== d.baseZ || d.rot !== d.baseRot)
      }
    }
    return { on: this.editMode, drafts, selected }
  }

  /** 业务要素（bundle 里那条）。 */
  featureOf(code) {
    return (this.bundle?.features || []).find((f) => f && f.code === code) || null
  }

  /** 屏幕点选中建筑（建立一个草稿）。 */
  selectFeatureAt(mx, mz) {
    const hit = this.pickAt(mx, mz)
    if (!hit || hit.kind !== 'building') {
      this.editSelectedCode = ''
      this.emitEdit()
      return null
    }
    this.editSelectedCode = hit.code
    this.ensureDraft(hit)
    this.emitEdit()
    return hit
  }

  /** 建立草稿（记住原值，供取消/还原用）。 */
  ensureDraft(f) {
    if (!this.drafts.has(f.code)) {
      this.drafts.set(f.code, {
        code: f.code,
        name: f.name || f.code,
        baseX: Number(f.x) || 0,
        baseZ: Number(f.z) || 0,
        baseRot: Number(f.rot) || 0,
        x: Number(f.x) || 0,
        z: Number(f.z) || 0,
        rot: Number(f.rot) || 0
      })
    }
    return this.drafts.get(f.code)
  }

  /** 找到某栋建筑的 Pixi 图元。 */
  graphicsOf(code) {
    const kids = this.featureLayer?.children || []
    return kids.find((g) => g && g.__feature && g.__feature.code === code) || null
  }

  /**
   * 把草稿画到图上。
   *
   * <p>为什么用 pivot + rotation 而不是重建图元：图元是按绝对米坐标画的，
   * 把 pivot 设到原中心、position 设到新中心，就等于「绕自己的中心转 + 平移到新位置」，
   * 一次赋值就能移动一整栋带影子/挑檐/瓦垄的房子——重建一次要几十毫秒，拖拽会掉帧。</p>
   */
  applyDraftVisual(d) {
    if (!d) return
    const g = this.graphicsOf(d.code)
    if (!g) return
    g.pivot.set(d.baseX, d.baseZ)
    g.position.set(d.x, d.z)
    g.rotation = ((d.rot - d.baseRot) * Math.PI) / 180
  }

  /** 重建要素层后把草稿贴回去。 */
  reapplyDrafts() {
    for (const d of this.drafts.values()) this.applyDraftVisual(d)
  }

  /** 拖动选中建筑到（米）。 */
  moveSelectedTo(x, z) {
    const d = this.drafts.get(this.editSelectedCode)
    if (!d) return
    d.x = Math.round((Number(x) || 0) * 2) / 2
    d.z = Math.round((Number(z) || 0) * 2) / 2
    this.applyDraftVisual(d)
    this.emitEdit()
  }

  /** 旋转选中建筑（度，15 度吸附）。 */
  rotateSelected(deltaDeg) {
    const d = this.drafts.get(this.editSelectedCode)
    if (!d) return
    const next = d.rot + (Number(deltaDeg) || 0)
    d.rot = ((Math.round(next / 15) * 15) % 360 + 360) % 360
    this.applyDraftVisual(d)
    this.emitEdit()
  }

  /** 把选中建筑放回原位。 */
  resetSelected() {
    const d = this.drafts.get(this.editSelectedCode)
    if (!d) return
    d.x = d.baseX
    d.z = d.baseZ
    d.rot = d.baseRot
    this.applyDraftVisual(d)
    this.emitEdit()
  }

  /**
   * 清空草稿。
   *
   * @param {boolean} revert true=把图元还原到服务端坐标（取消编辑/保存成功重拉）
   */
  clearDrafts(revert = true) {
    if (revert) {
      for (const d of this.drafts.values()) {
        d.x = d.baseX
        d.z = d.baseZ
        d.rot = d.baseRot
        this.applyDraftVisual(d)
      }
    }
    this.drafts.clear()
    this.editSelectedCode = ''
    this.featureDrag = null
    this.emitEdit()
  }

  /** 待提交的编辑（只回变更项）。 */
  draftEdits() {
    const out = []
    for (const d of this.drafts.values()) {
      if (d.x === d.baseX && d.z === d.baseZ && d.rot === d.baseRot) continue
      out.push({ code: d.code, x: d.x, z: d.z, rotation: d.rot, moveGroup: true })
    }
    return out
  }

  /** 通知外部「编辑态变了」。 */
  emitEdit() {
    if (typeof this.onEditChange === 'function') this.onEditChange(this.editSnapshot())
  }

  /** 按当前相机重投影角色点（屏幕空间，恒定像素大小）。 */
  redrawCharacters() {
    if (!this.charLayer || !this.app) return
    this.charLayer.visible = this.show.characters !== false
    if (!this.charLayer.visible) {
      this.charLayer.clear()
      return
    }
    drawCharacters(this.charLayer, this.characters, {
      scale: this.scale,
      tx: this.tx,
      ty: this.ty,
      width: this.host.clientWidth || 1,
      height: this.host.clientHeight || 1,
      night: this.night
    })
  }

  /**
   * 以某屏幕点为中心缩放（光标下那一点不动）。
   *
   * @param {number} factor 倍数（>1 放大）
   * @param {{x:number,y:number}} anchor 屏幕坐标（相对 canvas）
   */
  zoomAt(factor, anchor) {
    const next = Math.max(MIN_SCALE, Math.min(MAX_SCALE, this.scale * factor))
    if (next === this.scale) return
    const k = next / this.scale
    this.tx = anchor.x - (anchor.x - this.tx) * k
    this.ty = anchor.y - (anchor.y - this.ty) * k
    this.scale = next
    this.clampCamera()
    this.applyCamera()
  }

  /**
   * 平移（屏幕像素）。
   *
   * @param {number} dx 横向
   * @param {number} dy 纵向
   */
  panBy(dx, dy) {
    this.tx += dx
    this.ty += dy
    this.clampCamera()
    this.applyCamera()
  }

  /**
   * 缩放到指定倍数并以某米坐标居中（"定位到某地点"用）。
   *
   * @param {number} mx 米 X
   * @param {number} mz 米 Z
   * @param {number} scale px/米
   */
  centerOn(mx, mz, scale) {
    const vw = this.host.clientWidth || 1
    const vh = this.host.clientHeight || 1
    this.scale = Math.max(MIN_SCALE, Math.min(MAX_SCALE, Number(scale) || 1))
    this.tx = vw / 2 - mx * this.scale
    this.ty = vh / 2 - mz * this.scale
    this.clampCamera()
    this.applyCamera()
  }

  /** 对准聚落（首屏不该对着大海）。 */
  fitToSettlement() {
    if (!this.app) return
    const vw = this.host.clientWidth || 1
    const vh = this.host.clientHeight || 1
    const size = Number(this.bundle?.overview?.widthMeters) || 1024
    const box = this.bounds || { minX: 0, minZ: 0, maxX: size, maxZ: size }
    const bw = Math.max(30, box.maxX - box.minX)
    const bh = Math.max(30, box.maxZ - box.minZ)
    const pad = 1.25
    this.scale = Math.max(MIN_SCALE, Math.min(MAX_SCALE, Math.min(vw / (bw * pad), vh / (bh * pad))))
    this.tx = vw / 2 - ((box.minX + box.maxX) / 2) * this.scale
    this.ty = vh / 2 - ((box.minZ + box.maxZ) / 2) * this.scale
    this.clampCamera()
    this.applyCamera()
  }

  /** 复位到全图。 */
  fitToMap() {
    if (!this.app) return
    const vw = this.host.clientWidth || 1
    const vh = this.host.clientHeight || 1
    const size = Number(this.bundle?.overview?.widthMeters) || 1024
    this.scale = Math.max(MIN_SCALE, Math.min(vw / size, vh / size))
    this.tx = (vw - size * this.scale) / 2
    this.ty = (vh - size * this.scale) / 2
    this.clampCamera()
    this.applyCamera()
  }

  /** 相机边界：地图不能被拖出视口（至少留 15% 可见）。 */
  clampCamera() {
    const vw = this.host.clientWidth || 1
    const vh = this.host.clientHeight || 1
    const size = Number(this.bundle?.overview?.widthMeters) || 1024
    const margin = 0.15
    this.tx = Math.max(-size * this.scale + vw * margin, Math.min(vw * (1 - margin), this.tx))
    this.ty = Math.max(-size * this.scale + vh * margin, Math.min(vh * (1 - margin), this.ty))
  }

  /**
   * 屏幕坐标 → 米坐标。
   *
   * @param {number} sx 屏幕 X（相对 canvas）
   * @param {number} sy 屏幕 Y
   * @returns {{x:number,z:number}} 米坐标
   */
  toMap(sx, sy) {
    return { x: (sx - this.tx) / this.scale, z: (sy - this.ty) / this.scale }
  }

  // ==================== 昼夜 ====================

  /**
   * 夜色强度。
   *
   * @param {number} night 0 = 正午，1 = 深夜
   */
  setNight(night) {
    const before = this.night
    this.night = Math.max(0, Math.min(1, Number(night) || 0))
    this.layoutNight()
    // 昼夜跨过半档：换夜景底图（后端烘的夜版），而不仅仅是压一层蓝。
    if ((before > 0.5) !== (this.night > 0.5)) this.applyLod()
  }

  /** 夜色铺满视口。 */
  layoutNight() {
    if (!this.nightOverlay || !this.app) return
    const vw = this.host.clientWidth || 1
    const vh = this.host.clientHeight || 1
    this.nightOverlay.clear()
    this.nightOverlay.visible = this.night > 0.02
    if (!this.nightOverlay.visible) return
    this.nightOverlay.rect(0, 0, vw, vh)
    // 最大只压到 0.36：夜色是"换了一层氛围"，不是"把地图关掉"。
    // 0.52 那一档在实测里已经过了头——白天的地表纹理全被乘成深蓝泥，
    // 屋顶颜色也整体偏蓝（棕色屋顶看起来是灰蓝的），等于把整张图的美术白做了。
    // 0.06 + 0.30 既看得出是夜里，又还认得出屋顶、道路和田。
    this.nightOverlay.fill({ color: NIGHT_TINT, alpha: 0.06 + this.night * 0.30 })
  }

  // ==================== 拾取 ====================

  /**
   * 在米坐标上找最上面的要素（后画的优先，与绘制顺序一致）。
   *
   * @param {number} mx 米 X
   * @param {number} mz 米 Z
   * @returns {object|null} 命中的要素
   */
  pickAt(mx, mz) {
    let hit = null
    for (const h of this.hitCache) {
      if (pointInPolygon(mx, mz, h.corners)) hit = h.f
    }
    return hit
  }

  /**
   * 高亮某个要素（选中态）。
   *
   * @param {string|null} code 要素 code
   */
  setHighlight(code) {
    this.highlightCode = code || null
    if (!this.highlight) return
    this.highlight.clear()
    if (!this.highlightCode) return
    const item = this.hitCache.find((h) => h?.f?.code === this.highlightCode)
    const c = item?.corners
    if (!c || c.length < 3) return
    const cx = Number(item.f.x) || 0
    const cz = Number(item.f.z) || 0
    const k = 1.28
    const at = (p) => [cx + (p[0] - cx) * k, cz + (p[1] - cz) * k]
    const first = at(c[0])
    this.highlight.moveTo(first[0], first[1])
    for (let i = 1; i < c.length; i++) {
      const p = at(c[i])
      this.highlight.lineTo(p[0], p[1])
    }
    this.highlight.lineTo(first[0], first[1])
    this.highlight.stroke({
      width: Math.min(1.4, 3.2 / Math.max(0.25, this.scale)),
      color: 0xffe6a8,
      alpha: 0.95
    })
  }

  // ==================== 事件与生命周期 ====================

  /** DOM 事件绑定（滚轮缩放 / 拖拽平移 / 双击对准聚落 / 点击拾取）。 */
  _bindDom() {
    const canvas = this.app.canvas
    const local = (e) => {
      const rect = canvas.getBoundingClientRect()
      return { x: e.clientX - rect.left, y: e.clientY - rect.top }
    }
    this._onWheel = (e) => {
      e.preventDefault()
      this.zoomAt(Math.exp(-e.deltaY * 0.0016), local(e))
    }
    this._onPointerDown = (e) => {
      if (e.button !== 0) return
      const p = local(e)
      if (this.editMode) {
        const m = this.toMap(p.x, p.y)
        const hit = this.pickAt(m.x, m.z)
        if (hit && hit.kind === 'building') {
          const f = this.selectFeatureAt(m.x, m.z)
          const d = this.drafts.get(f?.code)
          if (d) {
            this.featureDrag = { id: e.pointerId, sx: p.x, sy: p.y, ox: d.x, oz: d.z }
            canvas.setPointerCapture?.(e.pointerId)
          }
          return
        }
        // 空白处：清掉选中，继续按「平移地图」处理（编辑态也要能挪视角）
        this.editSelectedCode = ''
        this.emitEdit()
      }
      this.drag = { id: e.pointerId, sx: p.x, sy: p.y, lx: p.x, ly: p.y, moved: 0 }
      canvas.setPointerCapture?.(e.pointerId)
    }
    this._onPointerMove = (e) => {
      // 拖动建筑（编辑态）：位移换算成米、0.5 米吸附、实时改图元
      if (this.featureDrag && e.pointerId === this.featureDrag.id) {
        const p = local(e)
        const dxm = (p.x - this.featureDrag.sx) / this.scale
        const dzm = (p.y - this.featureDrag.sy) / this.scale
        const d = this.drafts.get(this.editSelectedCode)
        if (d) {
          d.x = Math.round((this.featureDrag.ox + dxm) * 2) / 2
          d.z = Math.round((this.featureDrag.oz + dzm) * 2) / 2
          this.applyDraftVisual(d)
          this.emitEdit()
        }
        return
      }
      if (!this.drag || e.pointerId !== this.drag.id) return
      const p = local(e)
      const dx = p.x - this.drag.lx
      const dy = p.y - this.drag.ly
      this.drag.lx = p.x
      this.drag.ly = p.y
      this.drag.moved += Math.abs(dx) + Math.abs(dy)
      if (this.drag.moved > TAP_SLOP) {
        canvas.style.cursor = 'grabbing'
        this.panBy(dx, dy)
      }
    }
    this._onPointerUp = (e) => {
      if (this.featureDrag && e.pointerId === this.featureDrag.id) {
        this.featureDrag = null
        return
      }
      if (!this.drag || e.pointerId !== this.drag.id) return
      const d = this.drag
      this.drag = null
      canvas.style.cursor = ''
      if (d.moved > TAP_SLOP) return
      const m = this.toMap(d.sx, d.sy)
      const hit = this.pickAt(m.x, m.z)
      this.setHighlight(hit?.code || null)
      if (this.onPick) this.onPick(hit, m)
    }
    this._onDblClick = () => this.fitToSettlement()
    canvas.addEventListener('wheel', this._onWheel, { passive: false })
    canvas.addEventListener('pointerdown', this._onPointerDown)
    canvas.addEventListener('pointermove', this._onPointerMove)
    canvas.addEventListener('pointerup', this._onPointerUp)
    canvas.addEventListener('pointercancel', this._onPointerUp)
    canvas.addEventListener('dblclick', this._onDblClick)
    this._onResize = () => this.applyCamera()
    window.addEventListener('resize', this._onResize)
    this.resizeObserver = typeof ResizeObserver === 'function'
      ? new ResizeObserver(() => this._onResize())
      : null
    this.resizeObserver?.observe(this.host)
  }

  /** 下一帧处理 LOD（合并同一帧内的多次缩放）。 */
  scheduleLod() {
    if (this._lodPending) return
    this._lodPending = true
    const run = () => {
      this._lodPending = false
      if (this.destroyed || !this.app) return
      this.rebuildFeatures(false)
      const nextBand = this.bandOf(this.scale)
      if (nextBand !== this.roadBand) {
        this.roadBand = nextBand
        this.rebuildRoads()
      }
      // 写实底图按缩放换档（<0.5 用 1024、<2 用 2048、否则 4096）
      if (this.dayLods) this.applyLod(false)
    }
    if (typeof requestAnimationFrame === 'function') requestAnimationFrame(run)
    else setTimeout(run, 16)
  }

  /** 通知外部视图变化。 */
  emitView() {
    if (!this.onView) return
    this.onView({ scale: this.scale, band: this.bandOf(this.scale) })
  }

  /** 销毁（组件卸载时必须调用；Pixi 的 canvas 与 GPU 资源要显式释放）。 */
  destroy() {
    this.destroyed = true
    this.stopCharacterClock()
    this._compositeSeq++
    const canvas = this.app?.canvas
    if (canvas) {
      canvas.removeEventListener('wheel', this._onWheel)
      canvas.removeEventListener('pointerdown', this._onPointerDown)
      canvas.removeEventListener('pointermove', this._onPointerMove)
      canvas.removeEventListener('pointerup', this._onPointerUp)
      canvas.removeEventListener('pointercancel', this._onPointerUp)
      canvas.removeEventListener('dblclick', this._onDblClick)
    }
    window.removeEventListener('resize', this._onResize)
    this.resizeObserver?.disconnect()
    this.resizeObserver = null
    if (this.app) {
      this.app.destroy(true, { children: true, texture: false })
      this.app = null
    }
    this.root = null
    this.charLayer = null
    this.characters = []
    this.hitCache = []
    this.compositeSprite = null
    this.ambientLayer = null
  }
}

/**
 * 高精度时钟（performance 优先，退回 Date）。
 *
 * @returns {number} 毫秒
 */
function nowMs() {
  if (typeof performance !== 'undefined' && typeof performance.now === 'function') {
    return performance.now()
  }
  return Date.now()
}

/**
 * 点是否在多边形内（射线法；凸/凹都成立）。
 *
 * @param {number} x 点 X
 * @param {number} z 点 Z
 * @param {number[][]|null} poly 顶点
 * @returns {boolean} 是否命中
 */
export function pointInPolygon(x, z, poly) {
  if (!Array.isArray(poly) || poly.length < 3) return false
  let inside = false
  for (let i = 0, j = poly.length - 1; i < poly.length; j = i++) {
    const xi = poly[i][0]
    const zi = poly[i][1]
    const xj = poly[j][0]
    const zj = poly[j][1]
    const crosses = (zi > z) !== (zj > z)
    if (!crosses) continue
    const denom = zj - zi || 1e-9
    if (x < ((xj - xi) * (z - zi)) / denom + xi) inside = !inside
  }
  return inside
}
