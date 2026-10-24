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

package com.holzyn.actor.domain.mapgen.algo;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.util.List;

/**
 * 合成渲染器（S5 的自检用/海报用）——把地形底图 + 道路 + 建筑 + 农田 + 林地 + 码头 + 桥 画成一张图。
 * <p>前端正式渲染走 PixiJS 矢量层（缩放不糊），本类只用于<b>离线自检</b>：
 * 「路是不是顺地势」「房子是不是贴街、朝向一致」「农田是不是在镇外成片」——
 * 这些只有把东西叠在一张图上才看得出来。</p>
 */
public final class MapCompositePainter {

    /**
     * 太阳方位（度；0=正北，顺时针）。全图共用一套光源——真实航拍里所有影子都朝同一个方向，
     * 一旦每栋房子自己决定影子的方向，整片聚落立刻变成贴纸拼图。
     */
    private static final double SUN_AZIMUTH_DEG = 318;

    /** 太阳高度角（度）。影子长度 = 建筑高度 / tan(高度角)。 */
    private static final double SUN_ELEVATION_DEG = 34;

    private MapCompositePainter() {
    }

    /**
     * 写 PNG。
     *
     * @param img  图片
     * @param file 目标文件
     * @throws java.io.IOException 写失败
     */
    public static void write(BufferedImage img, java.io.File file) throws java.io.IOException {
        TerrainPainter.write(img, file);
    }

    /**
     * 合成一张图。
     *
     * @param t        地形
     * @param roads    道路（米）
     * @param features 要素（米）
     * @param size     输出边长（像素；可为 0 表示按格数 1:1）
     * @param scale    每格像素数
     * @return 图片
     */
    public static BufferedImage render(TerrainData t, List<MapPlacer.RoadSpec> roads,
                                       List<MapPlacer.PlacedFeature> features, int size, double scale) {
        int out = size > 0 ? size : (int) Math.round(t.size * scale);
        // 地形底图按**输出分辨率**直接绘制（不是 1024 画完再放大）：写实底图是 4 像素/米，
        // 放大插值会把草粒、树冠边缘、浪花线全糊成水彩。这里把每格像素数传下去。
        double ppm = out / (t.size * t.metersPerCell);
        BufferedImage base = TerrainPainter.render(t, ppm * t.metersPerCell, noCanopyMask(t, features));
        BufferedImage img = new BufferedImage(out, out, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);

        // 地形底图缩放到输出尺寸（同一尺寸时是 1:1 拷贝）
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(base, 0, 0, out, out, null);

        // ---------- ① 农田/林地/广场（先铺地面） ----------
        for (MapPlacer.PlacedFeature f : features) {
            String kind = f.kind();
            if ("field".equals(kind)) {
                String sem = f.semanticJson() == null ? "" : f.semanticJson();
                if (sem.contains("\"garden\":true")) {
                    // 菜畦是「人家的菜地」：小、深湿土、几行细绿。它不能和大田用同一套画法——
                    // 同一套画法下 68 块菜畦 + 24 条大田在图上糊成一片「黄绿碎纸」，
                    // 这正是旧图最像贴图错误的地方。
                    drawGarden(g, f, ppm);
                    continue;
                }
                // 大田：底色压深、垄沟沿地块自己的走向、再撒一层土色颗粒，最后描一圈田埂。
                // 真实航拍里每块田颜色都不一样（作物/墒情/翻耕时间），全都一个色就是「贴纸」；
                // 而没有田埂的田，在图上只是一张悬在草地上的黄纸片。
                String soil = f.roofColor() == null ? "#b0a471" : f.roofColor();
                Color soilColor = dim(parseColor(soil, new Color(168, 156, 100)), 0.94);
                fillPoly(g, f.polygon(), ppm, soilColor, null);
                // 垄沟沿田块自己的走向画——横平竖直的「草席纹」一眼就假
                hatchPoly(g, f.polygon(), ppm, new Color(122, 110, 66, 140), 3.0, f.rot());
                // 条田（长宽比 5:1）中间再加一道浅色垄背，读起来才是「一垄一垄」而不是一块布
                hatchPoly(g, f.polygon(), ppm, new Color(198, 186, 134, 70), 7.5, f.rot() + 90);
                specklePoly(g, f.polygon(), ppm, new Color(0, 0, 0, 16), 30, 2.4,
                        f.code().hashCode());
                strokePoly(g, f.polygon(), ppm, new Color(104, 94, 62, 190), 0.55);
            } else if ("forest".equals(kind)) {
                // 林地**不画**：地形底图本身已经按 forest 场画了冠层，这里再叠一个轴对齐矩形
                // 只会在航拍图上留下一块「幽灵方块」——边界笔直、和周围林相不连续，
                // 是整张图最像"贴图错误"的东西之一。要素仍然保留（日程/查询要用），只是不上色。
                continue;
            } else if ("plaza".equals(kind)) {
                drawPlaza(g, f, ppm);
            } else if ("quay".equals(kind)) {
                // 码头货场：岸上的硬地。它和广场都是「铺装地」，但读法完全不同——
                // 广场是镇中心（有井、有铺装缝、四面都是房子），货场是装卸场
                // （有车辙、系缆桩、货堆、渔网、缆绳）。两者用同一套画法就会
                // 变成「镇中心那口井长在海边」。
                drawQuay(g, f, ppm);
            }
        }

        // ---------- ② 道路（画在独立图层上，再按「陆地蒙版」压掉水面部分） ----------
        // 为什么要独立图层：路是走在陆地上的。旧实现里一条正街可以横穿湖面，
        // 俯视图上就是「一条马路压在水上」。蒙版取自地形高程/河道（连续值，岸线是软的），
        // 于是路到水边自然收进岸线，不会留一道硬切口。
        BufferedImage roadLayer = new BufferedImage(out, out, BufferedImage.TYPE_INT_ARGB);
        Graphics2D rg = roadLayer.createGraphics();
        rg.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        rg.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        drawRoads(rg, roads, ppm);
        BufferedImage landMask = landMask(t, out);
        if (landMask != null) {
            rg.setComposite(java.awt.AlphaComposite.DstIn);
            rg.drawImage(landMask, 0, 0, out, out, null);
            rg.setComposite(java.awt.AlphaComposite.SrcOver);
        }
        rg.dispose();
        g.drawImage(roadLayer, 0, 0, null);

        // ---------- ③ 建筑（投影 + 屋面 + 描边 + 门） ----------
        // 排序：先画矮的（院墙、棚、附属房），后画高的（主屋、塔）——
        // 高处的落影才能压到矮房子身上，反过来就成了「高楼的影子被平房盖住」。
        List<MapPlacer.PlacedFeature> drawn = new java.util.ArrayList<>();
        for (MapPlacer.PlacedFeature f : features) {
            String kind = f.kind();
            if ("wall".equals(kind) || "building".equals(kind)
                    || "pier".equals(kind) || "bridge".equals(kind)) {
                drawn.add(f);
            }
        }
        drawn.sort(java.util.Comparator.comparingDouble(MapPlacer.PlacedFeature::height));
        double[] sd = sunShadowDir();
        for (MapPlacer.PlacedFeature f : drawn) {
            String kind = f.kind();
            if ("pier".equals(kind)) {
                // 栈桥单独一套画法：它不是屋顶。木板、桩、系缆桩、并靠的渔船都是它自己的语言，
                // 用屋顶的「两坡明暗 + 屋脊」去画，得到的只是一根带影子的木条。
                drawPier(g, f, ppm);
                continue;
            }
            if ("wall".equals(kind)) {
                // 院墙：矮墙也有影子（2 米高的院墙在 34° 太阳下甩出 3 米影子），
                // 但没有屋顶明暗——墙是薄薄一道，画了坡向反而像房子。
                double wl = 2.0 / Math.tan(Math.toRadians(SUN_ELEVATION_DEG));
                fillShadow(g, f.polygon(), ppm, sd[0] * wl, sd[1] * wl, new Color(0, 0, 0, 34));
                fillPoly(g, f.polygon(), ppm, new Color(158, 148, 132), new Color(104, 96, 84));
                continue;
            }
            Color roof = parseColor(f.roofColor(), "pier".equals(kind) ? new Color(140, 108, 74)
                    : "bridge".equals(kind) ? new Color(138, 115, 80) : new Color(128, 92, 66));
            drawRoof(g, f, ppm, roof);
            strokePoly(g, f.polygon(), ppm, darker(roof), 0.9);
            // 门：朝路上一个小方块
            if ("building".equals(kind)) {
                double dx = f.doorX() * ppm;
                double dz = f.doorZ() * ppm;
                double s = Math.max(1.0, 1.1 * ppm);
                g.setColor(new Color(58, 44, 32));
                g.fillRect((int) Math.round(dx - s / 2), (int) Math.round(dz - s / 2),
                        (int) Math.round(s), (int) Math.round(s));
            }
        }
        g.dispose();
        return img;
    }

    /**
     * 夜景合成图——验收/氛围预览用。
     *
     * <p>为什么值得单独出一张：白天的俯视图看不出「这里住着人」。夜里最亮的东西恰恰是
     * 人活动的证据——<b>窗户的暖黄灯、广场灯下的地面反光、水面上的月光带</b>。
     * 白天图证明「布局对」，夜景图证明「有人住」。</p>
     *
     * @param t        地形
     * @param roads    道路（米）
     * @param features 要素（米）
     * @param size     输出边长（0 = 按格数 × scale）
     * @param scale    每格像素数
     * @return 夜景图
     */
    public static BufferedImage renderNight(TerrainData t, List<MapPlacer.RoadSpec> roads,
                                            List<MapPlacer.PlacedFeature> features,
                                            int size, double scale) {
        return renderNightFrom(t, roads, features, render(t, roads, features, size, scale));
    }

    /**
     * 用已经画好的白天图生成夜景（避免为了出夜景把整个白天图再画一遍）。
     *
     * <p>4096² 下「重画一遍白天图」= 6700 万像素的绘制 + 一次全图混合，实测要好几秒；
     * 而生成流程里白天图本来就要落盘，直接复用它是纯赚。</p>
     *
     * @param day 白天合成图
     * @return 夜景图
     */
    public static BufferedImage renderNightFrom(TerrainData t, List<MapPlacer.RoadSpec> roads,
                                                List<MapPlacer.PlacedFeature> features,
                                                BufferedImage day) {
        int w = day.getWidth();
        int h = day.getHeight();
        BufferedImage night = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        // ① 月光化：整体压暗偏冷（蓝通道保留最多），亮部不爆掉——真实夜景没有纯白天
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int rgb = day.getRGB(x, y);
                int r = (rgb >> 16) & 0xFF;
                int g = (rgb >> 8) & 0xFF;
                int b = rgb & 0xFF;
                int nr = (int) Math.min(255, r * 0.30 + 8);
                int ng = (int) Math.min(255, g * 0.37 + 11);
                int nb = (int) Math.min(255, b * 0.50 + 18);
                night.setRGB(x, y, (nr << 16) | (ng << 8) | nb);
            }
        }
        Graphics2D g = night.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        double ppm = w / (t.size * t.metersPerCell);
        // ② 窗灯：每栋房子一盏，位置按 code 哈希抖动，避免全在正中心（那会像打孔）
        for (MapPlacer.PlacedFeature f : features) {
            if (!"building".equals(f.kind())) {
                continue;
            }
            int seed = f.code() == null ? 7 : f.code().hashCode();
            double jx = (hash(1, seed) - 0.5) * f.w() * 0.45;
            double jz = (hash(2, seed) - 0.5) * f.d() * 0.45;
            double lx = (f.x() + jx) * ppm;
            double lz = (f.z() + jz) * ppm;
            // 灯下光晕：半径按「楼层越高灯越亮」放大一点
            double rad = Math.max(2.4, (1.6 + f.floors() * 0.55) * ppm * 2.2);
            float[] dist = {0f, 0.35f, 1f};
            Color[] cols = {new Color(255, 224, 158, 210), new Color(255, 196, 96, 92),
                    new Color(255, 180, 80, 0)};
            g.setPaint(new java.awt.RadialGradientPaint(
                    new java.awt.geom.Point2D.Double(lx, lz), (float) rad,
                    dist, cols));
            g.fill(new java.awt.geom.Ellipse2D.Double(lx - rad, lz - rad, rad * 2, rad * 2));
            // 灯芯：一小块实心暖黄，缩略图上这一点比光晕更关键
            double core = Math.max(1.1, 0.55 * ppm);
            g.setColor(new Color(255, 236, 190, 235));
            g.fill(new java.awt.geom.Ellipse2D.Double(lx - core / 2, lz - core / 2, core, core));
        }
        // ③ 广场/码头再补一盏公共灯（公共空间夜里不会全黑）
        for (MapPlacer.PlacedFeature f : features) {
            if (!"plaza".equals(f.kind()) && !"pier".equals(f.kind())) {
                continue;
            }
            double lx = f.x() * ppm;
            double lz = f.z() * ppm;
            double rad = Math.max(3.0, Math.max(f.w(), f.d()) * 0.5 * ppm);
            g.setPaint(new java.awt.RadialGradientPaint(
                    new java.awt.geom.Point2D.Double(lx, lz), (float) rad,
                    new float[]{0f, 1f},
                    new Color[]{new Color(255, 214, 150, 84), new Color(255, 200, 120, 0)}));
            g.fill(new java.awt.geom.Ellipse2D.Double(lx - rad, lz - rad, rad * 2, rad * 2));
        }
        g.dispose();
        return night;
    }

    // ==================== 绘制工具 ====================

    /**
     * 画道路：按等级从低到高，先路肩后路面。
     *
     * <p>「同色并集再一次性填充」的画法必须保留：逐条描边会在路口叠出半透明的圆帽，
     * 几条巷子交汇处就是一团发白的变形虫；并集之后路口只有一个边、一层色。</p>
     *
     * <p>但<b>路肩必须收窄</b>：旧版给主街加了 1.7 米路肩，在 1 米 1 像素的图上等于把
     * 5 米宽的街画成 7 米宽、两侧还各带一道深色边的「沥青公路」，一眼假。</p>
     *
     * @param g    画布
     * @param roads 道路
     * @param ppm  每米像素
     */
    private static void drawRoads(Graphics2D g, List<MapPlacer.RoadSpec> roads, double ppm) {
        java.util.Map<Integer, java.util.List<MapPlacer.RoadSpec>> byGrade = new java.util.TreeMap<>();
        for (MapPlacer.RoadSpec r : roads) {
            byGrade.computeIfAbsent(r.grade(), k -> new java.util.ArrayList<>()).add(r);
        }
        // 显示层级：**等级从低到高逐层画**——每个等级先画自己的路肩，再画自己的路面，
        // 画完才开始下一个等级。这样高等级路的「肩 + 面」整体压在低等级路之上，
        // 路口/并行的位置永远看到等级更高的那条（用户口径：官道在最上层，次级低一点）。
        // 旧实现是「先把所有等级的路肩铺一遍，再统一铺路面」，于是低等级路面会盖住
        // 高等级路肩，交叉口看起来是"两种路互相切开"。
        for (java.util.Map.Entry<Integer, java.util.List<MapPlacer.RoadSpec>> e : byGrade.entrySet()) {
            int grade = e.getKey();
            double shoulder = grade >= RoadGrade.STREET ? 1.0
                    : (grade >= RoadGrade.VILLAGE ? 0.7 : 0.3);
            g.setColor(shoulderColor(grade));
            g.fill(strokeArea(e.getValue(), ppm, r -> Math.max(1.0, r.width() + shoulder)));
            // 同一等级内按颜色并集一次性填充：逐条描边会在路口叠出半透明圆帽（一团发白的变形虫）
            java.util.Map<String, java.util.List<MapPlacer.RoadSpec>> byColor = new java.util.LinkedHashMap<>();
            for (MapPlacer.RoadSpec r : e.getValue()) {
                byColor.computeIfAbsent(hex(roadColor(r)), k -> new java.util.ArrayList<>()).add(r);
            }
            for (java.util.Map.Entry<String, java.util.List<MapPlacer.RoadSpec>> c : byColor.entrySet()) {
                g.setColor(parseColor(c.getKey(), new Color(170, 160, 142)));
                g.fill(strokeArea(c.getValue(), ppm, r -> Math.max(0.9, r.width())));
            }
            if (grade > RoadGrade.TRACK) {
                continue;
            }
            // 田埂土路的细节（中央车辙 + 边缘侵蚀）只作用于 0 级路，且必须紧跟着 0 级路面画：
            // 放到最后统一画会盖到高等级路上面，等于把"低等级在上"的规则又破坏一次。
            for (MapPlacer.RoadSpec r : e.getValue()) {
                strokePath(g, r.pathMeters(), ppm, Math.max(0.6, r.width() * 0.34),
                        new Color(188, 172, 142), 0.85);
                for (int p = 0; p < 2; p++) {
                    List<double[]> jit = jitter(r.pathMeters(), 0.9 + p * 0.7, r.code().hashCode() + p * 97);
                    strokePath(g, jit, ppm, Math.max(0.8, r.width() * (1.0 + 0.18 * p)),
                            new Color(146, 128, 96), 0.22);
                }
            }
        }
    }

    /**
     * 「不长树」掩膜：建筑/广场/码头/院墙外扩 4.5 米，农田外扩 2.5 米。
     *
     * <p><b>为什么农田只外扩 2.5 米而宅基地要 4.5 米</b>：宅基地是「清出来的场院」——
     * 房前屋后要晒粮、堆柴、走人，树离墙至少两三米；而田块之间本来就留田埂，
     * 田埂上长草、长一两棵树是正常的，扩太多会把田区外围的林地啃出一圈光秃秃的环。
     * 这个差别看起来细，但它是「村落边界自然」和「村子周围一圈秃」的分界。</p>
     *
     * @param t        地形
     * @param features 要素
     * @return 与格数同长的掩膜
     */
    private static boolean[] noCanopyMask(TerrainData t, List<MapPlacer.PlacedFeature> features) {
        int size = t.size;
        int n = size * size;
        boolean[] built = new boolean[n];
        boolean[] farm = new boolean[n];
        for (MapPlacer.PlacedFeature f : features) {
            if (f.polygon() == null) {
                continue;
            }
            String kind = f.kind();
            boolean isFarm = "field".equals(kind);
            boolean isBuilt = "building".equals(kind) || "plaza".equals(kind)
                    || "pier".equals(kind) || "bridge".equals(kind) || "wall".equals(kind);
            if (!isFarm && !isBuilt) {
                continue;
            }
            rasterPoly(f.polygon(), isFarm ? farm : built, size, t.metersPerCell);
        }
        boolean[] out = new boolean[n];
        stampClearance(built, out, size, t.metersPerCell, 4.5);
        stampClearance(farm, out, size, t.metersPerCell, 2.5);
        return out;
    }

    /** 多边形 → 栅格（格中心规则；点是否在多边形内用射线法）。 */
    private static void rasterPoly(double[][] poly, boolean[] dst, int size, double mpc) {
        double minX = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE;
        double minZ = Double.MAX_VALUE;
        double maxZ = -Double.MAX_VALUE;
        for (double[] pt : poly) {
            minX = Math.min(minX, pt[0]);
            maxX = Math.max(maxX, pt[0]);
            minZ = Math.min(minZ, pt[1]);
            maxZ = Math.max(maxZ, pt[1]);
        }
        int x0 = Math.max(0, (int) Math.floor(minX / mpc));
        int x1 = Math.min(size - 1, (int) Math.ceil(maxX / mpc));
        int z0 = Math.max(0, (int) Math.floor(minZ / mpc));
        int z1 = Math.min(size - 1, (int) Math.ceil(maxZ / mpc));
        for (int z = z0; z <= z1; z++) {
            for (int x = x0; x <= x1; x++) {
                if (pointInPoly((x + 0.5) * mpc, (z + 0.5) * mpc, poly)) {
                    dst[z * size + x] = true;
                }
            }
        }
    }

    /** 把 src 里的格子按「欧氏半径」外扩后并入 dst。 */
    private static void stampClearance(boolean[] src, boolean[] dst, int size, double mpc,
                                       double radiusMeters) {
        int r = (int) Math.ceil(radiusMeters / Math.max(0.05, mpc));
        int r2 = r * r;
        for (int z = 0; z < size; z++) {
            for (int x = 0; x < size; x++) {
                if (!src[z * size + x]) {
                    continue;
                }
                for (int dz = -r; dz <= r; dz++) {
                    int zz = z + dz;
                    if (zz < 0 || zz >= size) {
                        continue;
                    }
                    for (int dx = -r; dx <= r; dx++) {
                        int xx = x + dx;
                        if (xx < 0 || xx >= size || dx * dx + dz * dz > r2) {
                            continue;
                        }
                        dst[zz * size + xx] = true;
                    }
                }
            }
        }
    }

    /** 点是否在多边形内（射线法）。 */
    private static boolean pointInPoly(double x, double z, double[][] poly) {
        boolean in = false;
        for (int i = 0, j = poly.length - 1; i < poly.length; j = i++) {
            double xi = poly[i][0];
            double zi = poly[i][1];
            double xj = poly[j][0];
            double zj = poly[j][1];
            if ((zi > z) != (zj > z) && x < (xj - xi) * (z - zi) / (zj - zi) + xi) {
                in = !in;
            }
        }
        return in;
    }

    /**
     * 陆地蒙版：白色不透明 = 可以画路的陆地，透明 = 水面/河道。
     *
     * <p>格值只有 0/255，再双线性放大——岸线因此是连续灰阶。压到道路图层上之后，
     * 路在水边是「渐隐进岸线」而不是被一刀切掉，同时还顺手把桥头、码头引道的毛边修掉。</p>
     *
     * @param t   地形
     * @param out 输出边长（像素）
     * @return 蒙版（ARGB；无有效数据返回 null）
     */
    private static BufferedImage landMask(TerrainData t, int out) {
        if (out <= 0 || t.size <= 0) {
            return null;
        }
        BufferedImage m = new BufferedImage(t.size, t.size, BufferedImage.TYPE_INT_ARGB);
        for (int z = 0; z < t.size; z++) {
            for (int x = 0; x < t.size; x++) {
                int i = z * t.size + x;
                // 判定必须看「水/非水」而不是「高程是否低于海平面」：内陆湖的水面是高于海平面的，
                // 只看高程会把湖面当陆地，路照样横穿湖面（实测就是这样）。
                boolean land = !Biomes.isWater(t.biome[i]) && !t.riverMask[i]
                        && t.height[i] > t.seaLevel + 0.0005;
                m.setRGB(x, z, land ? 0xFFFFFFFF : 0x00000000);
            }
        }
        if (out == t.size) {
            return m;
        }
        BufferedImage scaled = new BufferedImage(out, out, BufferedImage.TYPE_INT_ARGB);
        Graphics2D gg = scaled.createGraphics();
        gg.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        gg.drawImage(m, 0, 0, out, out, null);
        gg.dispose();
        return scaled;
    }

    /** 裁一块放大存出来（自检细节用：一整张 1024 图上看不出贴街关系）。 */
    public static void writeCrop(BufferedImage src, java.io.File out,
                                 int x, int y, int w, int h, int scale) throws java.io.IOException {
        int cw = Math.max(1, Math.min(w, src.getWidth() - Math.max(0, x)));
        int ch = Math.max(1, Math.min(h, src.getHeight() - Math.max(0, y)));
        BufferedImage crop = src.getSubimage(Math.max(0, x), Math.max(0, y), cw, ch);
        BufferedImage dst = new BufferedImage(cw * scale, ch * scale, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = dst.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        g.drawImage(crop, 0, 0, cw * scale, ch * scale, null);
        g.dispose();
        write(dst, out);
    }

    /**
     * 路面颜色按等级区分：
     * 0 级田埂土路=浅夯土；1 级村庄道路=土褐/碎石；2 级主街=石板灰白；3 级官道=更亮的石板。
     * <p>颜色 + 宽度 + 路肩三重区分，玩家在最小缩放下也能一眼看出路的好坏。</p>
     */
    private static Color roadColor(MapPlacer.RoadSpec r) {
        String surface = r.surface() == null ? "" : r.surface();
        // 整体压暗 15%：旧图里主街是画面最亮的一条灰粉色带，比屋顶还抢眼——
        // 真实航拍上路面永远比屋顶暗、比草地浅，它是「被踩出来的土」，不是发光带。
        return switch (r.grade()) {
            case RoadGrade.TRACK -> new Color(134, 120, 92);
            case RoadGrade.VILLAGE -> "gravel".equals(surface)
                    ? new Color(112, 104, 87) : new Color(117, 99, 73);
            case RoadGrade.STREET -> "dirt".equals(surface)
                    ? new Color(126, 107, 82) : new Color(112, 105, 94);
            default -> new Color(126, 118, 104);
        };
    }

    /** 路肩颜色：低等级路只是被踩出来的，没有明显路肩；但路肩必须是「压实的土」而不是深色描边。 */
    private static Color shoulderColor(int grade) {
        return switch (RoadGrade.clamp(grade)) {
            case RoadGrade.TRACK -> new Color(112, 100, 77, 110);
            case RoadGrade.VILLAGE -> new Color(94, 82, 63, 150);
            case RoadGrade.STREET -> new Color(88, 82, 70, 170);
            default -> new Color(78, 71, 60, 180);
        };
    }

    private static void fillPoly(Graphics2D g, double[][] poly, double ppm, Color fill, Color stroke) {
        Path2D.Double p = pathOf(poly, ppm, 0);
        g.setColor(fill);
        g.fill(p);
        if (stroke != null) {
            g.setColor(stroke);
            g.setStroke(new BasicStroke(0.8f));
            g.draw(p);
        }
    }

    private static void fillPolyOffset(Graphics2D g, double[][] poly, double ppm, double off, Color fill) {
        Path2D.Double p = pathOf(poly, ppm, off);
        g.setColor(fill);
        g.fill(p);
    }

    /**
     * 光的行进方向（单位向量，米坐标系）——影子朝这个方向甩。
     *
     * <p>方位角是「光从哪儿来」，所以行进方向是它的反向；屏幕角 = 方位角 - 90°（0°在北＝-Z）。</p>
     *
     * @return 单位方向 {dx, dz}
     */
    private static double[] sunShadowDir() {
        double rad = Math.toRadians(SUN_AZIMUTH_DEG + 180);
        return new double[]{Math.cos(rad), Math.sin(rad)};
    }

    /**
     * 按给定方向平移多边形后填充（落影用）。
     *
     * @param g   画布
     * @param poly 多边形（米）
     * @param ppm 每米像素
     * @param dx  平移 X（米）
     * @param dz  平移 Z（米）
     * @param fill 填充色
     */
    private static void fillShadow(Graphics2D g, double[][] poly, double ppm,
                                   double dx, double dz, Color fill) {
        double[][] s = new double[poly.length][2];
        for (int i = 0; i < poly.length; i++) {
            s[i][0] = poly[i][0] + dx;
            s[i][1] = poly[i][1] + dz;
        }
        fillPoly(g, s, ppm, fill, null);
    }

    private static void strokePoly(Graphics2D g, double[][] poly, double ppm, Color c, double w) {
        Path2D.Double p = pathOf(poly, ppm, 0);
        g.setColor(c);
        g.setStroke(new BasicStroke((float) Math.max(0.6, w * ppm), BasicStroke.CAP_ROUND,
                BasicStroke.JOIN_ROUND));
        g.draw(p);
    }

    /**
     * 广场：铺装地面 + 石板缝 + 井台 + 缘石。
     * <p>只填一块浅灰矩形的话，它在俯视图里就是一个「浮在草地上的屋顶」——
     * 明明是高等级公共空间，看着却像一栋没画墙的房子。集镇广场该有的视觉线索
     * 是：<b>成排的铺装缝、四周的缘石、中间那口井</b>。</p>
     */
    private static void drawPlaza(Graphics2D g, MapPlacer.PlacedFeature f, double ppm) {
        fillPoly(g, f.polygon(), ppm, new Color(186, 176, 158), null);
        // 石块颗粒：深浅两层错位撒开——纯色块在俯视图里读不出「铺装」，只有颗粒能
        specklePoly(g, f.polygon(), ppm, new Color(96, 88, 74, 46), 90, 1.5, f.code().hashCode());
        specklePoly(g, f.polygon(), ppm, new Color(232, 224, 206, 54), 70, 1.2,
                f.code().hashCode() + 31);
        // 铺装缝：两个方向的细线，缝距 1.8 米
        hatchPoly(g, f.polygon(), ppm, new Color(150, 140, 124, 150), 1.8 * ppm, f.rot());
        hatchPoly(g, f.polygon(), ppm, new Color(150, 140, 124, 110), 1.8 * ppm, f.rot() + 90);
        // 缘石压在四周，广场才像「一块被围起来的场地」而不是一块草地上的灰斑
        strokePoly(g, f.polygon(), ppm, new Color(112, 104, 88), 0.9);
        // 井台：广场中心的一口井（俯视就是深色圆点 + 浅色井圈 + 一小段影子）
        double wpx = Math.max(3.0, 3.2 * ppm);
        double cx = f.x() * ppm;
        double cz = f.z() * ppm;
        double[] sd = sunShadowDir();
        g.setColor(new Color(0, 0, 0, 46));
        g.fill(new java.awt.geom.Ellipse2D.Double(cx - wpx / 2 + sd[0] * wpx * 0.7,
                cz - wpx / 2 + sd[1] * wpx * 0.7, wpx, wpx));
        g.setColor(new Color(112, 104, 90));
        g.fill(new java.awt.geom.Ellipse2D.Double(cx - wpx / 2, cz - wpx / 2, wpx, wpx));
        g.setColor(new Color(78, 72, 62));
        double in = wpx * 0.55;
        g.fill(new java.awt.geom.Ellipse2D.Double(cx - in / 2, cz - in / 2, in, in));
    }

    /**
     * 把要素的<b>局部坐标</b>（米，x 沿 length、z 沿 depth）换算到地图坐标。
     *
     * <p>画铺装缝、系缆桩、货堆这些"按要素自身朝向排布"的东西时，直接在地图坐标里算
     * 三角函数会把代码写成一片 sin/cos。这里统一用一个基准把局部坐标转出来。</p>
     *
     * @param f  要素（用它的中心与 rot）
     * @param lx 局部 X（沿 f.w() 方向，米）
     * @param lz 局部 Z（沿 f.d() 方向，米）
     * @return 地图坐标 {x, z}
     */
    private static double[] localOf(MapPlacer.PlacedFeature f, double lx, double lz) {
        double rad = Math.toRadians(f.rot());
        double c = Math.cos(rad);
        double s = Math.sin(rad);
        return new double[]{f.x() + c * lx - s * lz, f.z() + s * lx + c * lz};
    }

    /**
     * 码头货场：岸上的装卸场。
     *
     * <p>为什么不能复用广场的画法：广场的语言是「石板缝 + 缘石 + 中心一口井」，那是镇中心；
     * 货场的语言是<b>车辙、系缆桩、木箱、木桶、盘好的缆绳</b>。两者混用最荒唐的一种画法
     * 就是「镇中心那口井长在海边」。</p>
     */
    private static void drawQuay(Graphics2D g, MapPlacer.PlacedFeature f, double ppm) {
        int seed = f.code() == null ? 11 : f.code().hashCode();
        // ① 夯实的碎石地：比广场暗、更灰（是"压出来的硬土 + 碎石子"，不是磨平的石板）。
        // 注意不要画长条平行纹理——俯视图上那一条条等距直线读起来就是"木板铺的栈台"，
        // 而木板是栈桥的语言，不是货场的。货场的语言是"碎、脏、被压出来"。
        fillPoly(g, f.polygon(), ppm, new Color(146, 136, 114), null);
        specklePoly(g, f.polygon(), ppm, new Color(92, 82, 64, 58), 110, 1.3, seed);
        specklePoly(g, f.polygon(), ppm, new Color(198, 188, 162, 40), 85, 1.1, seed + 7);
        specklePoly(g, f.polygon(), ppm, new Color(120, 108, 84, 34), 46, 2.2, seed + 13);
        strokePoly(g, f.polygon(), ppm, new Color(96, 88, 70), 0.8);
        if (f.w() < 3 || f.d() < 3) {
            return;
        }
        // ② 车辙：从陆侧一直压到栈桥根，两条碾压痕——这是"有板车进出"的唯一线索。
        // 别画成两条亮白直线：在俯视图上那读起来就是"两条木板"。要淡、要窄、中间再压一道暗。
        double halfW = f.w() / 2 - 0.8;
        g.setStroke(new BasicStroke((float) Math.max(0.6, 0.42 * ppm)));
        g.setColor(new Color(176, 166, 142, 46));
        for (double lz : new double[]{-1.15, 1.15}) {
            double[] a = localOf(f, -halfW, lz);
            double[] b = localOf(f, halfW, lz);
            g.drawLine((int) Math.round(a[0] * ppm), (int) Math.round(a[1] * ppm),
                    (int) Math.round(b[0] * ppm), (int) Math.round(b[1] * ppm));
        }
        g.setColor(new Color(108, 96, 74, 34));
        g.setStroke(new BasicStroke((float) Math.max(0.8, 0.9 * ppm)));
        double[] m0 = localOf(f, -halfW, 0);
        double[] m1 = localOf(f, halfW, 0);
        g.drawLine((int) Math.round(m0[0] * ppm), (int) Math.round(m0[1] * ppm),
                (int) Math.round(m1[0] * ppm), (int) Math.round(m1[1] * ppm));
        // ③ 系缆桩：沿两条长边各排一列（间距 4.5 米），深色桩头 + 受光的一侧
        double bollard = Math.max(1.6, 0.34 * ppm);
        for (double lz : new double[]{-f.d() / 2 + 0.7, f.d() / 2 - 0.7}) {
            for (double lx = -halfW + 1.0; lx <= halfW - 0.5; lx += 4.5) {
                double[] p = localOf(f, lx, lz);
                double cx = p[0] * ppm;
                double cz = p[1] * ppm;
                g.setColor(new Color(52, 44, 34));
                g.fill(new java.awt.geom.Ellipse2D.Double(cx - bollard / 2, cz - bollard / 2, bollard, bollard));
                g.setColor(new Color(122, 108, 88, 180));
                g.fill(new java.awt.geom.Ellipse2D.Double(cx - bollard / 4, cz - bollard / 4,
                        bollard / 2, bollard / 2));
            }
        }
        // ④ 货堆：木箱（方块）+ 木桶（圆）+ 缆绳（同心圆）——位置按 code 哈希稳定抖动
        double[] sd = sunShadowDir();
        int crates = 3 + Math.floorMod(seed, 4);
        for (int i = 0; i < crates; i++) {
            double lx = (hash(i * 3 + 1, seed) - 0.5) * (f.w() - 4.0);
            double lz = (hash(i * 3 + 2, seed) - 0.5) * (f.d() - 3.4);
            double[] p = localOf(f, lx, lz);
            double side = (0.9 + hash(i * 3 + 3, seed) * 0.8) * ppm;
            double cx = p[0] * ppm;
            double cz = p[1] * ppm;
            // 落影
            g.setColor(new Color(0, 0, 0, 60));
            g.fill(new java.awt.geom.Rectangle2D.Double(cx - side / 2 + sd[0] * side * 0.5,
                    cz - side / 2 + sd[1] * side * 0.5, side, side));
            // 木箱：暖褐 + 一道压条
            g.setColor(i % 3 == 0 ? new Color(132, 96, 58) : new Color(150, 116, 74));
            g.fill(new java.awt.geom.Rectangle2D.Double(cx - side / 2, cz - side / 2, side, side));
            g.setColor(new Color(96, 68, 40, 200));
            g.setStroke(new BasicStroke((float) Math.max(0.6, 0.14 * ppm)));
            g.draw(new java.awt.geom.Rectangle2D.Double(cx - side / 2, cz - side / 2, side, side));
            g.drawLine((int) Math.round(cx - side / 2), (int) Math.round(cz),
                    (int) Math.round(cx + side / 2), (int) Math.round(cz));
        }
        // ⑤ 盘好的缆绳：两圈浅褐色圆环，靠水一侧
        for (int i = 0; i < 2; i++) {
            double[] p = localOf(f, (hash(i + 40, seed) - 0.5) * (f.w() - 5.0), f.d() / 2 - 2.2);
            double rr = (0.55 + hash(i + 41, seed) * 0.35) * ppm;
            g.setColor(new Color(120, 104, 76, 210));
            g.setStroke(new BasicStroke((float) Math.max(0.7, 0.22 * ppm)));
            g.draw(new java.awt.geom.Ellipse2D.Double(p[0] * ppm - rr, p[1] * ppm - rr, rr * 2, rr * 2));
            g.draw(new java.awt.geom.Ellipse2D.Double(p[0] * ppm - rr * 0.55, p[1] * ppm - rr * 0.55,
                    rr * 1.1, rr * 1.1));
        }
    }

    /**
     * 栈桥（含 T 型桥头）：入水的木板桥。
     *
     * <p>旧实现把它当屋顶画——两坡明暗 + 屋脊 + 出檐，结果是一根带影子的木条。
     * 栈桥在俯视图里的识别特征是：<b>竖向的木板块、两侧的桩、桥头的系缆桩、
     * 并靠的渔船</b>，以及"影子落在水面上"（不是陆地上）。</p>
     */
    private static void drawPier(Graphics2D g, MapPlacer.PlacedFeature f, double ppm) {
        String sem = f.semanticJson() == null ? "" : f.semanticJson();
        boolean head = sem.contains("\"head\":true");
        int seed = f.code() == null ? 23 : f.code().hashCode();
        double[] sd = sunShadowDir();
        // ① 水面上的影子：栈桥是"浮"着的，影子淡而偏冷（不是陆地上那种浓黑）
        fillShadow(g, f.polygon(), ppm, sd[0] * 2.6, sd[1] * 2.6, new Color(2, 22, 30, 70));
        // ② 桥面：湿木色（靠水的木头永远比岸上的深）
        fillPoly(g, f.polygon(), ppm, new Color(138, 108, 72), null);
        if (f.w() < 2.5 || f.d() < 1.5) {
            strokePoly(g, f.polygon(), ppm, new Color(74, 54, 36), 0.5);
            return;
        }
        // ③ 木板缝：垂直长轴铺设（间距 0.55 米），再按 4 米压一道横向大梁
        hatchPoly(g, f.polygon(), ppm, new Color(92, 70, 46, 165), Math.max(1.2, 0.55 * ppm), f.rot() + 90);
        hatchPoly(g, f.polygon(), ppm, new Color(74, 54, 36, 120), Math.max(1.5, 4.0 * ppm), f.rot());
        // ④ 桩：两侧每 3.2 米一根（露出水面的桩头，深色 + 一点受光）
        double pile = Math.max(1.6, 0.42 * ppm);
        double[] normal = {-Math.sin(Math.toRadians(f.rot())), Math.cos(Math.toRadians(f.rot()))};
        for (double s = -f.w() / 2 + 0.8; s <= f.w() / 2 - 0.4; s += 3.2) {
            for (double sideSign = -1; sideSign <= 1; sideSign += 2) {
                double[] p = localOf(f, s, sideSign * (f.d() / 2 + 0.05));
                double cx = p[0] * ppm;
                double cz = p[1] * ppm;
                g.setColor(new Color(48, 38, 26));
                g.fill(new java.awt.geom.Ellipse2D.Double(cx - pile / 2, cz - pile / 2, pile, pile));
                g.setColor(new Color(126, 110, 84, 150));
                g.fill(new java.awt.geom.Ellipse2D.Double(cx - pile / 4 - normal[0] * pile * 0.2,
                        cz - pile / 4 - normal[1] * pile * 0.2, pile / 2, pile / 2));
            }
        }
        // ⑤ 系缆桩：桥头两端各一个（比两侧的桩粗、顶上带浅色顶盖）
        if (!head) {
            for (double sideSign = -1; sideSign <= 1; sideSign += 2) {
                double[] p = localOf(f, f.w() / 2 - 0.9, sideSign * (f.d() / 2 - 0.6));
                double r = Math.max(2.0, 0.5 * ppm);
                g.setColor(new Color(40, 32, 24));
                g.fill(new java.awt.geom.Ellipse2D.Double(p[0] * ppm - r / 2, p[1] * ppm - r / 2, r, r));
                g.setColor(new Color(148, 132, 104, 190));
                g.fill(new java.awt.geom.Ellipse2D.Double(p[0] * ppm - r / 4, p[1] * ppm - r / 4, r / 2, r / 2));
            }
        }
        // ⑥ 并靠的渔船：只画在主栈桥上（桥头那段是给船掉头/并靠的横台）
        if (!head && f.w() >= 9.0 && f.w() > f.d() * 1.8) {
            int boats = 1 + Math.floorMod(seed, 2);
            for (int i = 0; i < boats; i++) {
                double sideSign = i % 2 == 0 ? 1 : -1;
                double off = sideSign * (f.d() / 2 + 1.5 + hash(i + 5, seed) * 0.6);
                double lx = (hash(i + 9, seed) - 0.5) * (f.w() - 7.0);
                drawBoat(g, f, lx, off, ppm, seed + i * 17);
            }
        }
        strokePoly(g, f.polygon(), ppm, new Color(72, 52, 34), 0.55);
    }

    /**
     * 一条并靠的小渔船：船壳 + 舱口 + 一道横座板 + 船头方向的一点反光。
     *
     * @param g    画布
     * @param pier 所属栈桥（借它的朝向）
     * @param lx   沿栈桥方向的局部偏移（米）
     * @param lz   垂直栈桥方向的局部偏移（米，正=朝法线一侧）
     * @param ppm  每米像素
     * @param seed 随机种子（同图稳定）
     */
    private static void drawBoat(Graphics2D g, MapPlacer.PlacedFeature pier, double lx, double lz,
                                 double ppm, int seed) {
        double len = 6.4;
        double beam = 2.1;
        double[] c = localOf(pier, lx, lz);
        double rad = Math.toRadians(pier.rot());
        double c1 = Math.cos(rad);
        double s1 = Math.sin(rad);
        double n1x = -s1;
        double n1z = c1;
        double[] sd = sunShadowDir();
        // 船壳：六边形（船头尖、船尾方）——用局部坐标拼，比椭圆更像船
        double[][] hullLocal = {
            {-len / 2, -beam / 2}, {len / 2 - 1.6, -beam / 2}, {len / 2, 0},
            {len / 2 - 1.6, beam / 2}, {-len / 2, beam / 2}, {-len / 2 - 0.5, 0}
        };
        double[][] hull = new double[hullLocal.length][];
        for (int i = 0; i < hullLocal.length; i++) {
            hull[i] = new double[]{c[0] + c1 * hullLocal[i][0] + n1x * hullLocal[i][1],
                    c[1] + s1 * hullLocal[i][0] + n1z * hullLocal[i][1]};
        }
        // 落影（落在水面上，淡而偏冷）
        fillShadow(g, hull, ppm, sd[0] * 1.6, sd[1] * 1.6, new Color(2, 22, 30, 80));
        fillPoly(g, hull, ppm, new Color(64, 58, 50), null);
        // 舱口：船中一段浅色内胆，读起来才是"空船"而不是一块木头
        double[][] well = offsetPoly(hull, -0.55);
        fillPoly(g, well, ppm, new Color(112, 88, 60), null);
        // 横座板：两道
        g.setColor(new Color(86, 66, 44, 200));
        g.setStroke(new BasicStroke((float) Math.max(0.6, 0.18 * ppm)));
        for (double t : new double[]{-1.1, 1.4}) {
            double[] a = new double[]{c[0] + c1 * t + n1x * (beam / 2 - 0.35), c[1] + s1 * t + n1z * (beam / 2 - 0.35)};
            double[] b = new double[]{c[0] + c1 * t - n1x * (beam / 2 - 0.35), c[1] + s1 * t - n1z * (beam / 2 - 0.35)};
            g.drawLine((int) Math.round(a[0] * ppm), (int) Math.round(a[1] * ppm),
                    (int) Math.round(b[0] * ppm), (int) Math.round(b[1] * ppm));
        }
        // 船头的一根短桅/缆桩
        double[] bow = new double[]{c[0] + c1 * (len / 2 - 1.0), c[1] + s1 * (len / 2 - 1.0)};
        double r = Math.max(1.4, 0.26 * ppm);
        g.setColor(new Color(42, 34, 26));
        g.fill(new java.awt.geom.Ellipse2D.Double(bow[0] * ppm - r / 2, bow[1] * ppm - r / 2, r, r));
        strokePoly(g, hull, ppm, new Color(40, 32, 24, 200), 0.5);
    }

    /**
     * 在多边形内撒一层随机色斑（石铺/土块的颗粒感）。
     *
     * <p>卫星图上「材质」是密集小颗粒读出来的：广场是一块纯色矩形时，人眼只会把它认成
     * 「一张纸」；撒上石块明暗，它立刻变成铺装地面。田块的土色颗粒同理。</p>
     *
     * @param g     画布
     * @param poly  多边形（米）
     * @param ppm   每米像素
     * @param c     颜色（可以带 alpha）
     * @param count 颗粒数
     * @param sizeM 颗粒基准直径（米）
     * @param seed  随机种子
     */
    /**
     * 菜畦：深湿土 + 3~5 条细绿垄 + 一圈草边。
     *
     * <p>菜畦和大田在俯视图上必须是两种东西：大田是「一片黄绿的垄作面」，
     * 菜畦是「一小块被翻过的深色土，上面几行绿」。旧图两者同色同纹理，
     * 于是 60 多块菜畦变成了撒满全镇的绿色碎纸。</p>
     */
    private static void drawGarden(Graphics2D g, MapPlacer.PlacedFeature f, double ppm) {
        Color soil = parseColor(f.roofColor(), new Color(93, 79, 52));
        fillPoly(g, f.polygon(), ppm, soil, new Color(72, 60, 40));
        // 3~5 条垄：条数由 code 派生（同图稳定），垄距 0.9 米左右
        int rows = 3 + Math.floorMod(f.code() == null ? 0 : f.code().hashCode(), 3);
        double spacing = Math.max(0.7, Math.min(1.3, Math.max(f.w(), f.d()) / (rows + 1))) * ppm;
        hatchPoly(g, f.polygon(), ppm, new Color(104, 128, 66, 190), spacing, f.rot());
        hatchPoly(g, f.polygon(), ppm, new Color(140, 160, 92, 80), spacing * 2, f.rot());
        specklePoly(g, f.polygon(), ppm, new Color(0, 0, 0, 22), 12, 0.9,
                f.code() == null ? 17 : f.code().hashCode());
        strokePoly(g, f.polygon(), ppm, new Color(86, 94, 54, 150), 0.45);
    }

    /**
     * 细粒地面噪声：高分辨率底图专用。
     *
     * <p>4096² 底图的地形部分是从 1024² 放大来的（地形栅格是 1 米 1 格），
     * 放大 4 倍后草地会变成一块模糊的绿毯——建筑和田地是矢量画的、清晰，
     * 地面却是糊的，观感立刻「贴图」。这里按输出像素撒一层 ±3% 的明度噪声 + 稀疏植被点，
     * 把放大痕迹打散，同时让地面有真实的颗粒感。</p>
     *
     * @param img 合成图（就地修改）
     * @param t   地形
     */
    public static void groundDetail(BufferedImage img, TerrainData t) {
        int w = img.getWidth();
        int h = img.getHeight();
        if (w < 1600) {
            return;
        }
        int size = t.size;
        for (int y = 0; y < h; y++) {
            int cz = (int) (y * (double) size / h);
            if (cz < 0 || cz >= size) {
                continue;
            }
            for (int x = 0; x < w; x++) {
                int cx = (int) (x * (double) size / w);
                if (cx < 0 || cx >= size) {
                    continue;
                }
                int i = cz * size + cx;
                byte b = t.biome[i];
                if (Biomes.isSea(b)) {
                    continue;
                }
                int rgb = img.getRGB(x, y);
                int r = (rgb >> 16) & 0xFF;
                int g = (rgb >> 8) & 0xFF;
                int bl = rgb & 0xFF;
                // 两层噪声：一层细（每像素），一层粗（4 像素块），后者才是「地表起伏」
                double fine = (hash2(x, y, 91) - 0.5) * 0.055;
                double coarse = (hash2(x >> 2, y >> 2, 37) - 0.5) * 0.075;
                double k = 1 + fine + coarse;
                // 植被点：林/灌/草上偶尔一个略深的绿点，放大后不会是一块纯色
                if ((b == Biomes.FOREST_LIGHT || b == Biomes.FOREST_DENSE
                        || b == Biomes.SCRUB || b == Biomes.MEADOW)
                        && hash2(x >> 1, y >> 1, 53) > 0.86) {
                    k *= 0.93;
                }
                r = (int) Math.max(0, Math.min(255, r * k));
                g = (int) Math.max(0, Math.min(255, g * k));
                bl = (int) Math.max(0, Math.min(255, bl * k));
                img.setRGB(x, y, (r << 16) | (g << 8) | bl);
            }
        }
    }

    /**
     * 环境色（季节 / 天气 / 潮汐 / 湿气）叠加。
     *
     * <p>地图不是一张静态照片：同一座岛在春天是嫩绿、秋天是枯黄，雨天路面发暗、
     * 雾天远景发灰，涨潮时滩涂被淹掉一截。这些不需要重算地形，只需要在成品图上做一次
     * 颜色分级 + 岸边湿润带——但少了它，世界时钟走得再准，地图看起来也是「冻结的」。</p>
     *
     * @param t       地形（判断滩涂/湿地/水体）
     * @param img     合成图（就地修改）
     * @param season  spring / summer / autumn / winter
     * @param weather clear / cloudy / rain / fog / wind
     * @param tide    潮位 0（低潮）~1（高潮）
     * @param wetness 湿度 0~1（雨天/雨后地面变暗）
     */
    public static void applyAmbient(TerrainData t, BufferedImage img, String season,
                                    String weather, double tide, double wetness) {
        int w = img.getWidth();
        int h = img.getHeight();
        int size = t.size;
        double[] mul = seasonPalette(season);
        double desat = "winter".equals(season) ? 0.16 : "autumn".equals(season) ? 0.05 : 0;
        double darken = "rain".equals(weather) ? 0.87
                : "cloudy".equals(weather) ? 0.95 : "fog".equals(weather) ? 0.97 : 1.0;
        double fog = "fog".equals(weather) ? 0.22 : 0;
        double wet = Math.max(0, Math.min(1, wetness)) * 0.20;
        double tideRise = Math.max(0, Math.min(1, tide));
        for (int y = 0; y < h; y++) {
            int cz = (int) (y * (double) size / h);
            if (cz < 0 || cz >= size) {
                continue;
            }
            for (int x = 0; x < w; x++) {
                int cx = (int) (x * (double) size / w);
                if (cx < 0 || cx >= size) {
                    continue;
                }
                int i = cz * size + cx;
                byte b = t.biome[i];
                int rgb = img.getRGB(x, y);
                int r = (rgb >> 16) & 0xFF;
                int g = (rgb >> 8) & 0xFF;
                int bl = rgb & 0xFF;
                double kr = mul[0] * darken;
                double kg = mul[1] * darken;
                double kb = mul[2] * darken;
                // 潮汐：高潮时 BEACH / WETLAND 里靠近海面的那一带被水淹——压暗并偏冷，
                // 看上去就是「滩涂少了一截」。这一条让潮汐在图上真的看得见。
                if ((b == Biomes.BEACH || b == Biomes.WETLAND)
                        && t.height[i] < t.seaLevel + 0.004 + tideRise * 0.012) {
                    kr *= 0.78 - wet * 0.1;
                    kg *= 0.86 - wet * 0.1;
                    kb *= 0.98;
                } else if (!Biomes.isSea(b) && !Biomes.isWater(b)) {
                    // 湿气：所有陆地压暗一点点并略微偏冷（雨后地面不会立刻变干）
                    kr *= 1 - wet * 0.5;
                    kg *= 1 - wet * 0.45;
                    kb *= 1 - wet * 0.25;
                }
                double nr = r * kr;
                double ng = g * kg;
                double nb = bl * kb;
                if (desat > 0) {
                    double gray = nr * 0.299 + ng * 0.587 + nb * 0.114;
                    nr = nr * (1 - desat) + gray * desat;
                    ng = ng * (1 - desat) + gray * desat;
                    nb = nb * (1 - desat) + gray * desat;
                }
                if (fog > 0 && !Biomes.isWater(b)) {
                    // 雾：远景抬黑（真实雾不是白纱，是把暗部提亮、对比度压低）
                    nr = nr * (1 - fog) + 196 * fog;
                    ng = ng * (1 - fog) + 200 * fog;
                    nb = nb * (1 - fog) + 204 * fog;
                }
                img.setRGB(x, y, (clamp255(nr) << 16) | (clamp255(ng) << 8) | clamp255(nb));
            }
        }
    }

    /** 季节色乘子（R/G/B）：春偏嫩、夏偏暖、秋偏金、冬偏冷。 */
    private static double[] seasonPalette(String season) {        if (season == null) {
            return new double[]{1, 1, 1};
        }
        return switch (season) {
            case "spring" -> new double[]{1.02, 1.04, 0.96};
            case "summer" -> new double[]{1.04, 1.01, 0.94};
            case "autumn" -> new double[]{1.07, 0.99, 0.88};
            case "winter" -> new double[]{0.96, 1.00, 1.06};
            default -> new double[]{1, 1, 1};
        };
    }

    private static void specklePoly(Graphics2D g, double[][] poly, double ppm, Color c,
                                    int count, double sizeM, int seed) {
        if (count <= 0 || sizeM <= 0 || poly == null || poly.length < 3) {
            return;
        }
        Path2D.Double clip = pathOf(poly, ppm, 0);
        java.awt.Shape old = g.getClip();
        g.clip(clip);
        g.setColor(c);
        double minX = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE;
        double minZ = Double.MAX_VALUE;
        double maxZ = -Double.MAX_VALUE;
        for (double[] p : poly) {
            minX = Math.min(minX, p[0]);
            maxX = Math.max(maxX, p[0]);
            minZ = Math.min(minZ, p[1]);
            maxZ = Math.max(maxZ, p[1]);
        }
        for (int k = 0; k < count; k++) {
            double x = minX + (maxX - minX) * hash(k * 3 + 1, seed);
            double z = minZ + (maxZ - minZ) * hash(k * 3 + 2, seed);
            double s = Math.max(0.7, sizeM * (0.5 + hash(k * 3 + 3, seed) * 1.2)) * ppm;
            g.fill(new java.awt.geom.Ellipse2D.Double(x * ppm - s / 2, z * ppm - s / 2, s, s));
        }
        g.setClip(old);
    }

    private static void hatchPoly(Graphics2D g, double[][] poly, double ppm, Color c,
                                  double spacingPx, double angDeg) {
        Path2D.Double clip = pathOf(poly, ppm, 0);
        java.awt.Shape old = g.getClip();
        g.clip(clip);
        g.setColor(c);
        g.setStroke(new BasicStroke(0.9f));
        double cx = 0;
        double cz = 0;
        for (double[] p : poly) {
            cx += p[0];
            cz += p[1];
        }
        cx /= poly.length;
        cz /= poly.length;
        double rad = Math.toRadians(angDeg);
        double dx = Math.cos(rad);
        double dz = Math.sin(rad);
        double nx = -dz;
        double nz = dx;
        double halfSpan = 0;
        double longSpan = 0;
        for (double[] p : poly) {
            halfSpan = Math.max(halfSpan, Math.abs((p[0] - cx) * nx + (p[1] - cz) * nz));
            longSpan = Math.max(longSpan, Math.abs((p[0] - cx) * dx + (p[1] - cz) * dz));
        }
        double step = Math.max(2.0, spacingPx) / ppm;
        for (double o = -halfSpan; o <= halfSpan; o += step) {
            double sx = cx + nx * o - dx * longSpan * 1.05;
            double sz = cz + nz * o - dz * longSpan * 1.05;
            double ex = cx + nx * o + dx * longSpan * 1.05;
            double ez = cz + nz * o + dz * longSpan * 1.05;
            g.drawLine((int) Math.round(sx * ppm), (int) Math.round(sz * ppm),
                    (int) Math.round(ex * ppm), (int) Math.round(ez * ppm));
        }
        g.setClip(old);
    }

    /**
     * 屋顶：落影 + 屋面 + 亮/暗两坡 + 屋脊。
     * <p>为什么屋顶值得这么画：1 米 1 像素时一栋房只有 6~10 像素，
     * 一个平涂矩形和一块路面色块在缩略图上几乎没有区别——观感就成了「撒了一地棕色方块」。
     * 而人眼判断「这是个屋顶」靠的是<b>两坡明暗差 + 屋脊 + 落影</b>，
     * 这三样哪怕各只有一两度差异，也足以让整片聚落从「色块堆」变成「房子」。</p>
     */
    private static void drawRoof(Graphics2D g, MapPlacer.PlacedFeature f, double ppm, Color roof) {
        double[][] poly = f.polygon();
        // ① 落影：影子长度 = 建筑高度 / tan(太阳高度角)，方向全图一致。
        // 为什么这比「给屋顶加纹理」重要：1 米 1 像素时屋顶只有 6~10 像素，纹理根本画不进去；
        // 但一栋 7 米高的屋子在 34° 太阳下会甩出 10 米影子——那片影子才是「这里有立体房子」
        // 的唯一线索。旧版固定 2 米偏移，所以整片聚落看着像贴在地上的褐色纸片。
        double[] sd = sunShadowDir();
        double shadowLen = Math.max(1.5, f.height() / Math.tan(Math.toRadians(SUN_ELEVATION_DEG)));
        fillShadow(g, poly, ppm, sd[0] * shadowLen * 0.45, sd[1] * shadowLen * 0.45,
                new Color(0, 0, 0, 52));
        fillShadow(g, poly, ppm, sd[0] * shadowLen * 0.80, sd[1] * shadowLen * 0.80,
                new Color(0, 0, 0, 34));
        fillShadow(g, poly, ppm, sd[0] * shadowLen, sd[1] * shadowLen,
                new Color(0, 0, 0, 20));
        // ①.5 出檐：屋面比屋身放大 0.9 米，先在下面铺一层深色檐口，再把瓦面盖回去。
        // 没有出檐的房子，边缘直接切成草地，2~3 像素/米下就是"贴在图上的一块纸片"；
        // 有了这一圈深色檐影，屋顶才有厚度，整片聚落才像"盖"在地面上。
        if (f.w() > 2.5 && f.d() > 2.5) {
            fillPoly(g, offsetPoly(poly, 0.9), ppm, dim(roof, 0.58), null);
        }
        // ② 屋面
        fillPoly(g, poly, ppm, roof, null);
        if (f.w() < 2.5 || f.d() < 2.5) {
            return;
        }
        boolean gable = "gable".equals(f.roofShape()) || "hip".equals(f.roofShape());
        if (!gable) {
            // 单坡顶（棚/柴房）：沿短边压一条更暗的带子，表示坡向一侧
            fillPolyOffset(g, poly, ppm, 0, dim(roof, 0.86));
            fillPoly(g, poly, ppm, roof, null);
            double rad = Math.toRadians(f.rot());
            double half = f.d() / 2;
            double ox = -Math.sin(rad) * half * 0.55;
            double oz = Math.cos(rad) * half * 0.55;
            double[][] shade = {
                {f.x() - Math.cos(rad) * f.w() / 2 + ox, f.z() - Math.sin(rad) * f.w() / 2 + oz},
                {f.x() + Math.cos(rad) * f.w() / 2 + ox, f.z() + Math.sin(rad) * f.w() / 2 + oz},
                {f.x() + Math.cos(rad) * f.w() / 2 + ox * 2, f.z() + Math.sin(rad) * f.w() / 2 + oz * 2},
                {f.x() - Math.cos(rad) * f.w() / 2 + ox * 2, f.z() - Math.sin(rad) * f.w() / 2 + oz * 2}
            };
            fillPoly(g, shade, ppm, new Color(0, 0, 0, 34), null);
            return;
        }
        double rad = Math.toRadians(f.rot());
        double cx = Math.cos(rad);
        double cz = Math.sin(rad);
        // 脊线方向 = 局部 X（屋脊沿街），两坡分居局部 ±Z
        double halfW = f.w() / 2;
        double halfD = f.d() / 2;
        // 法线：(-cz, cx)；让 dot 与「光来向」(-1,-1)/√2 比较决定哪一坡朝光
        double n1x = -cz;
        double n1z = cx;
        double dot = n1x * -0.7071 + n1z * -0.7071;
        double litSign = dot >= 0 ? 1 : -1;
        double[][] lit = {
            {f.x() - cx * halfW, f.z() - cz * halfW},
            {f.x() + cx * halfW, f.z() + cz * halfW},
            {f.x() + cx * halfW + n1x * litSign * halfD, f.z() + cz * halfW + n1z * litSign * halfD},
            {f.x() - cx * halfW + n1x * litSign * halfD, f.z() - cz * halfW + n1z * litSign * halfD}
        };
        double[][] dark = {
            {f.x() - cx * halfW, f.z() - cz * halfW},
            {f.x() + cx * halfW, f.z() + cz * halfW},
            {f.x() + cx * halfW - n1x * litSign * halfD, f.z() + cz * halfW - n1z * litSign * halfD},
            {f.x() - cx * halfW - n1x * litSign * halfD, f.z() - cz * halfW - n1z * litSign * halfD}
        };
        fillPoly(g, dark, ppm, new Color(0, 0, 0, 46), null);
        fillPoly(g, lit, ppm, new Color(255, 244, 214, 30), null);
        // ③ 屋脊：受光的一线比瓦面亮，不是一道黑缝
        g.setColor(lighter(roof));
        g.setStroke(new BasicStroke((float) Math.max(0.7, 0.85 * ppm)));
        g.drawLine((int) Math.round((f.x() - cx * halfW) * ppm),
                (int) Math.round((f.z() - cz * halfW) * ppm),
                (int) Math.round((f.x() + cx * halfW) * ppm),
                (int) Math.round((f.z() + cz * halfW) * ppm));
    }

    /**
     * 多边形沿径向向外扩 {@code m} 米（用于屋面出檐）。
     *
     * <p>只对凸多边形（建筑脚印都是矩形）有意义：矩形顶点沿对角线外推 m，边实际外扩
     * ≈0.7m，出檐量刚好合适。不用正规的多边形偏置，是因为建筑最多 4 个顶点，
     * 这里要的是"看起来有檐"，不是几何精确。</p>
     *
     * @param poly 多边形（米）
     * @param m    外扩量（米）
     * @return 外扩后的多边形
     */
    private static double[][] offsetPoly(double[][] poly, double m) {
        double cx = 0;
        double cz = 0;
        for (double[] p : poly) {
            cx += p[0];
            cz += p[1];
        }
        cx /= poly.length;
        cz /= poly.length;
        double[][] out = new double[poly.length][2];
        for (int i = 0; i < poly.length; i++) {
            double dx = poly[i][0] - cx;
            double dz = poly[i][1] - cz;
            double d = Math.hypot(dx, dz);
            double f = d < 1e-6 ? 0 : (d + m) / d;
            out[i][0] = cx + dx * f;
            out[i][1] = cz + dz * f;
        }
        return out;
    }

    private static Color dim(Color c, double k) {
        return new Color(Math.max(0, Math.min(255, (int) (c.getRed() * k))),
                Math.max(0, Math.min(255, (int) (c.getGreen() * k))),
                Math.max(0, Math.min(255, (int) (c.getBlue() * k))));
    }

    private static Color lighter(Color c) {
        return new Color(Math.min(255, (int) (c.getRed() + (255 - c.getRed()) * 0.34)),
                Math.min(255, (int) (c.getGreen() + (255 - c.getGreen()) * 0.34)),
                Math.min(255, (int) (c.getBlue() + (255 - c.getBlue()) * 0.30)));
    }

    /** 一组道路按给定宽度描边后求并集（路口只留一条边，不叠色）。 */
    private static java.awt.geom.Area strokeArea(List<MapPlacer.RoadSpec> roads, double ppm,
                                                 java.util.function.ToDoubleFunction<MapPlacer.RoadSpec> width) {
        java.awt.geom.Area area = new java.awt.geom.Area();
        for (MapPlacer.RoadSpec r : roads) {
            List<double[]> pts = r.pathMeters();
            if (pts == null || pts.size() < 2) {
                continue;
            }
            double w = Math.max(0.7, width.applyAsDouble(r));
            java.awt.Shape st = new BasicStroke((float) (w * ppm), BasicStroke.CAP_ROUND,
                    BasicStroke.JOIN_ROUND).createStrokedShape(polylinePath(pts, ppm));
            area.add(new java.awt.geom.Area(st));
        }
        return area;
    }

    /** 折线 → 二次贝塞尔圆角路径（与 {@link #strokePath} 用同一套平滑）。 */
    private static Path2D.Double polylinePath(List<double[]> pts, double ppm) {
        Path2D.Double p = new Path2D.Double();
        p.moveTo(pts.get(0)[0] * ppm, pts.get(0)[1] * ppm);
        for (int i = 1; i < pts.size() - 1; i++) {
            double cx = pts.get(i)[0] * ppm;
            double cz = pts.get(i)[1] * ppm;
            double nx = pts.get(i + 1)[0] * ppm;
            double nz = pts.get(i + 1)[1] * ppm;
            p.quadTo(cx, cz, (cx + nx) / 2, (cz + nz) / 2);
        }
        double[] last = pts.get(pts.size() - 1);
        p.lineTo(last[0] * ppm, last[1] * ppm);
        return p;
    }

    private static void strokePath(Graphics2D g, List<double[]> pts, double ppm, double widthMeters,
                                   Color c, double alpha) {
        if (pts.size() < 2) {
            return;
        }
        Path2D.Double p = new Path2D.Double();
        p.moveTo(pts.get(0)[0] * ppm, pts.get(0)[1] * ppm);
        for (int i = 1; i < pts.size() - 1; i++) {
            double cx = pts.get(i)[0] * ppm;
            double cz = pts.get(i)[1] * ppm;
            double nx = pts.get(i + 1)[0] * ppm;
            double nz = pts.get(i + 1)[1] * ppm;
            p.quadTo(cx, cz, (cx + nx) / 2, (cz + nz) / 2);
        }
        double[] last = pts.get(pts.size() - 1);
        p.lineTo(last[0] * ppm, last[1] * ppm);
        g.setColor(alpha >= 1 ? c : new Color(c.getRed(), c.getGreen(), c.getBlue(),
                (int) Math.round(255 * alpha)));
        g.setStroke(new BasicStroke((float) Math.max(0.7, widthMeters * ppm), BasicStroke.CAP_ROUND,
                BasicStroke.JOIN_ROUND));
        g.draw(p);
    }

    /** 沿路径法向做确定性抖动（同一条路每次得到同一个抖动，便于复现/自检）。 */
    private static List<double[]> jitter(List<double[]> pts, double amp, int seed) {
        List<double[]> out = new java.util.ArrayList<>(pts.size());
        for (int i = 0; i < pts.size(); i++) {
            double[] a = pts.get(Math.max(0, i - 1));
            double[] b = pts.get(Math.min(pts.size() - 1, i + 1));
            double vx = b[0] - a[0];
            double vz = b[1] - a[1];
            double len = Math.hypot(vx, vz);
            if (len < 1e-6) {
                out.add(new double[]{pts.get(i)[0], pts.get(i)[1]});
                continue;
            }
            double nx = -vz / len;
            double nz = vx / len;
            double off = (hash(i, seed) - 0.5) * 2.0 * amp;
            out.add(new double[]{pts.get(i)[0] + nx * off, pts.get(i)[1] + nz * off});
        }
        return out;
    }

    /** 确定性哈希（0~1）。 */
    private static double hash(int i, int seed) {
        long k = i * 374761393L + seed * 668265263L;
        int h = (int) (k ^ (k >>> 29));
        h = (h ^ (h >>> 13)) * 1274126177;
        h = h ^ (h >>> 16);
        return ((h & 0x7FFFFFFF) / (double) 0x7FFFFFFF);
    }

    /** 二维确定性哈希（0~1）：地面细粒噪声按像素坐标取值，不能只用一维序号。 */
    private static double hash2(int x, int y, int seed) {
        long k = x * 374761393L + y * 668265263L + seed * 2654435761L;
        int h = (int) (k ^ (k >>> 29));
        h = (h ^ (h >>> 13)) * 1274126177;
        h = h ^ (h >>> 16);
        return ((h & 0x7FFFFFFF) / (double) 0x7FFFFFFF);
    }

    /** 0~255 取整夹取（颜色分量）。 */
    private static int clamp255(double v) {
        int i = (int) Math.round(v);
        return i < 0 ? 0 : Math.min(255, i);
    }

    /**
     * 路面颜色的十六进制形态（给前端渲染包用）。     * <p>为什么要把内部颜色暴露出来：前端 PixiJS 画的路如果和这张合成 PNG 颜色不一致，
     * 用户会在「小地图预览」和「大地图」之间看到同一段路两种颜色。唯一的做法是让两边
     * 共用<b>同一个函数</b>，而不是各写一份常量表然后慢慢漂移。</p>
     *
     * @param grade   道路等级（见 {@link RoadGrade}）
     * @param surface 路面材质（stone/dirt/gravel/wood）
     * @return #rrggbb
     */
    public static String roadColorHex(int grade, String surface) {
        // 色板原则：所有路面都往"暖灰/土黄"收，不要出现接近纯白的浅灰。
        // 早期 STREET 用的是 (188,182,168)——在草地上一眼就是一条水泥带，像现代公路，
        // 而这是一个渔村石板街。降明度、加一点暖调后，它才像被踩了几百年的条石。
        Color c = switch (RoadGrade.clamp(grade)) {
            case RoadGrade.TRACK -> new Color(176, 158, 124);
            case RoadGrade.VILLAGE -> "gravel".equals(surface)
                    ? new Color(150, 138, 116) : new Color(158, 134, 100);
            case RoadGrade.STREET -> "dirt".equals(surface)
                    ? new Color(170, 146, 112) : new Color(152, 144, 128);
            default -> new Color(170, 160, 142);
        };
        return hex(c);
    }

    /**
     * 路肩颜色（前端铺路时先铺一条更宽的底色，视觉上就是「路两侧被踩秃的土」）。
     *
     * @param grade 道路等级
     * @return #rrggbb
     */
    public static String shoulderColorHex(int grade) {
        Color c = switch (RoadGrade.clamp(grade)) {
            case RoadGrade.TRACK -> new Color(146, 128, 96);
            case RoadGrade.VILLAGE -> new Color(112, 96, 70);
            case RoadGrade.STREET -> new Color(78, 68, 54);
            default -> new Color(64, 56, 44);
        };
        return hex(c);
    }

    /** Color → #rrggbb。 */
    private static String hex(Color c) {
        return String.format("#%02x%02x%02x", c.getRed(), c.getGreen(), c.getBlue());
    }

    private static Path2D.Double pathOf(double[][] poly, double ppm, double off) {
        Path2D.Double p = new Path2D.Double();
        p.moveTo(poly[0][0] * ppm + off, poly[0][1] * ppm + off);
        for (int i = 1; i < poly.length; i++) {
            p.lineTo(poly[i][0] * ppm + off, poly[i][1] * ppm + off);
        }
        p.closePath();
        return p;
    }

    private static Color parseColor(String hex, Color fallback) {
        if (hex == null || hex.isBlank()) {
            return fallback;
        }
        try {
            String h = hex.startsWith("#") ? hex.substring(1) : hex;
            if (h.length() == 6) {
                return new Color(Integer.parseInt(h, 16));
            }
        } catch (RuntimeException ignored) {
            // 配色非法就用兜底色
        }
        return fallback;
    }

    private static Color darker(Color c) {
        return new Color((int) (c.getRed() * 0.62), (int) (c.getGreen() * 0.62), (int) (c.getBlue() * 0.62));
    }
}
