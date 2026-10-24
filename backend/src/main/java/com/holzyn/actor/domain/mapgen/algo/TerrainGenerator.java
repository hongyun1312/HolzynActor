/*
 * Copyright (c) 2026 HONGYUN
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package com.holzyn.actor.domain.mapgen.algo;

import java.util.ArrayDeque;

/**
 * 地形生成器（S1）——把 {@link TerrainParams} 变成 {@link TerrainData}，全程确定性。
 * <p>七步：</p>
 * <ol>
 *   <li><b>陆形遮罩</b>：多边形样条 + 谐波（海湾）+ 域扭曲 → 有机海岸线（不是椭圆）；</li>
 *   <li><b>陆上起伏</b>：低频 FBM（大地形）+ 脊状噪声（山脊）+ 山峰高斯凸起，再按 {@code reliefHeight}
 *       归一化（保证不同 seed 的山都不会「顶到天花板」）；</li>
 *   <li><b>平原压平</b>：聚落周围压成缓坡平地（村镇与农田的立足处）；</li>
 *   <li><b>海域塑形</b>：<b>到岸距离场</b>驱动的棚架——近岸浅滩 → 远海深沟，这是「海看起来像海」的关键；</li>
 *   <li><b>水力侵蚀</b>：下切河谷、冲出冲积扇（只侵蚀陆地，不啃海底）；</li>
 *   <li><b>水文分析</b>：填洼 → D8 → 汇流 → 河网/湖泊；</li>
 *   <li><b>群系赋值</b>：高度/坡度/离水/温度/湿度 → 地表类型 + 可建造度。</li>
 * </ol>
 */
public final class TerrainGenerator {

    private static final double TAU = Math.PI * 2;

    /** 海底最大深度（归一化高度，相对海平面）。 */
    private static final double MAX_SEA_DEPTH = 0.52;

    private TerrainGenerator() {
    }

    /**
     * 生成地形。
     *
     * @param p 参数
     * @return 地形数据（含统计）
     */
    public static TerrainData generate(TerrainParams p) {
        int size = p.size;
        TerrainData t = new TerrainData(size, p.seaLevel);
        t.metersPerCell = Math.max(0.05, p.metersPerCell);
        t.heightToMeters = p.elevationMeters / Math.max(0.05, p.reliefHeight);
        Noise2D noise = new Noise2D(Rng.derive(p.seed, 101));

        buildMask(t, p, noise);
        buildLandHeight(t, p, noise);
        carveSea(t, p, noise);
        // 陆地侵蚀下限：略高于海面，从源头避免「内陆坑」。
        HydraulicErosion.erode(t, Rng.derive(p.seed, 211), p.erosionDroplets, p.erosionStrength,
                (float) (p.seaLevel + 0.006));
        // 侵蚀会把河谷啃到海平面以下，留下内陆「低于海面的坑」——渲染时那些格子会被判成海，
        // 于是草地上出现一堆蓝色小斑点。这里不能只是硬提到海平面：硬提的结果是
        // 「一块绝对水平的地台 + 一圈断崖」，俯视图上看像谁挖的方形基坑（实测非常扎眼）。
        // 改成软填：把坑做成浅碟，坑壁变成缓坡。
        softenPits(t, p);
        smoothHeight(t);
        HydroFlow.analyze(t, p);
        // M2 尾巴：溪谷真挖。HydroFlow 只是「算出水往哪流、画一条蓝线」，
        // 地形本身没有为河道让过一寸——于是溪流像一条贴在草坡上的蓝色胶带。
        // 这里沿河道折线下切河床、两岸做出谷肩，溪才真的是「在地形里流」。
        carveRiverBeds(t, p);
        smoothHeight(t);
        BiomeAssigner.assign(t, p, noise);
        return t;
    }

    /**
     * 沿河道折线真挖河床（V 形谷）。
     *
     * <p><b>为什么这一步值得单独做</b>：河流在俯视图里最重要的信息不是「水色」，而是
     * <b>两岸的阴影与坡度</b>——真实的溪谷两侧一定是暗的、有坡的，河谷本身就是地形的褶皱。
     * 只画蓝线的河，在山地上会「爬坡」、在平地上会「浮起来」，一眼假。</p>
     *
     * <p>做法：沿折线每米采样，以采样点为中心压出一个宽 2~4 格、深 1.5~3 米的 V 形剖面，
     * 取「原地形」与「河谷剖面」的较小值（只下切、不抬升）。河床不低于海平面太多，
     * 免得内陆出现「低于海面的坑」被误判成海。</p>
     *
     * @param t 地形（就地修改 height/riverMask/riverAlpha）
     * @param p 参数（河流宽度按水量/尺度定）
     */
    private static void carveRiverBeds(TerrainData t, TerrainParams p) {
        if (t.rivers == null || t.rivers.isEmpty()) {
            return;
        }
        int size = t.size;
        float sea = (float) t.seaLevel;
        float[] origin = t.height.clone();
        // 河宽随地图尺度走：1024 格里 2~4 格（2~4 米）是溪，主干再宽一点
        double halfW = Math.max(1.8, Math.min(4.5, size / 380.0 + 1.6));
        double depth = Math.max(0.0035, Math.min(0.012, 0.006 + p.erosionStrength * 0.004));
        for (double[] flat : t.rivers) {
            if (flat == null || flat.length < 4) {
                continue;
            }
            for (int k = 0; k + 3 < flat.length; k += 2) {
                double ax = flat[k];
                double az = flat[k + 1];
                double bx = flat[k + 2];
                double bz = flat[k + 3];
                double len = Math.hypot(bx - ax, bz - az);
                double ux = len < 1e-9 ? 0 : (bx - ax) / len;
                double uz = len < 1e-9 ? 0 : (bz - az) / len;
                for (double s = 0; s <= len; s += 0.8) {
                    double px = ax + ux * s;
                    double pz = az + uz * s;
                    int cx = (int) Math.round(px);
                    int cz = (int) Math.round(pz);
                    if (cx < 1 || cz < 1 || cx >= size - 1 || cz >= size - 1) {
                        continue;
                    }
                    float centerH = origin[cz * size + cx];
                    // 河床不能低于海面：内陆溪流的水面本来就高于海面（水面 = 河床 + 水深），
                    // 把床挖到海面以下会造出「陆地中间的坑」，渲染时被判成海 → 草地上的蓝斑。
                    float bed = Math.max(sea + 0.0008f, centerH - (float) depth);
                    int rad = (int) Math.ceil(halfW) + 1;
                    for (int dz = -rad; dz <= rad; dz++) {
                        for (int dx = -rad; dx <= rad; dx++) {
                            int nx = cx + dx;
                            int nz = cz + dz;
                            if (nx < 1 || nz < 1 || nx >= size - 1 || nz >= size - 1) {
                                continue;
                            }
                            double dist = Math.hypot(dx, dz);
                            if (dist > halfW) {
                                continue;
                            }
                            int i = nz * size + nx;
                            // V 形剖面：中心 = 河床，边缘 = 原地面
                            double f = dist / halfW;
                            float target = (float) (bed + (origin[i] - bed) * f * f);
                            if (target < t.height[i]) {
                                t.height[i] = target;
                            }
                            double a = 1.0 - f * 0.85;
                            if (a > t.riverAlpha[i]) {
                                t.riverAlpha[i] = (float) a;
                            }
                            if (dist <= halfW * 0.62) {
                                t.riverMask[i] = true;
                            }
                        }
                    }
                }
            }
        }
    }

    // ==================== ① 陆形遮罩 ====================

    private static void buildMask(TerrainData t, TerrainParams p, Noise2D noise) {
        int size = t.size;
        double c = size * 0.5;
        double rot = Math.toRadians(p.rotationDeg);
        double cos = Math.cos(rot);
        double sin = Math.sin(rot);
        double r0 = size * p.islandRadiusRatio;
        double edge = Math.max(2.5, size * 0.0045);

        Rng pr = Rng.derive(p.seed, 7);
        double ph1 = pr.nextDouble() * TAU;
        double ph2 = pr.nextDouble() * TAU;
        double ph3 = pr.nextDouble() * TAU;
        double ph4 = pr.nextDouble() * TAU;
        double rough = p.coastRoughness;

        int bays = p.coastRegionDriven ? 0 : Math.max(0, p.bays);
        double[] bayAng = new double[bays];
        double[] bayDepth = new double[bays];
        for (int i = 0; i < bays; i++) {
            bayAng[i] = (TAU * i / Math.max(1, bays)) + pr.nextDouble() * 0.8 - 0.4;
            bayDepth[i] = r0 * (0.10 + 0.12 * pr.nextDouble());
        }

        // 岬角（向海伸出的尖角，与海湾成对出现才自然）
        int capes = p.coastRegionDriven ? 0 : bays;
        double[] capeAng = new double[capes];
        double[] capeLen = new double[capes];
        for (int i = 0; i < capes; i++) {
            capeAng[i] = bayAng[i] + (0.55 + 0.35 * pr.nextDouble());
            capeLen[i] = r0 * (0.10 + 0.14 * pr.nextDouble());
        }

        int islets = pr.nextDouble() < 0.85 ? 1 + pr.nextInt(2) : 0;
        double[] isletAng = new double[islets];
        double[] isletDist = new double[islets];
        double[] isletRad = new double[islets];
        for (int i = 0; i < islets; i++) {
            isletAng[i] = pr.nextDouble() * TAU;
            isletDist[i] = r0 * (1.22 + 0.34 * pr.nextDouble());
            isletRad[i] = size * (0.014 + 0.020 * pr.nextDouble());
        }

        // ---------- 角向碎形起伏（真实海岸线不是三个正弦波的叠加） ----------
        // 旧实现只用 3/5/7/11 次谐波 + 两级域扭曲：远看永远是一个「带波浪边的鸡蛋」，
        // 因为谐波是周期光滑函数，缺的是真实海岸那种**自相似的小尺度凹凸**
        // （大湾里套小湾、岬上再长小岬、碎礁与连岛沙洲）。
        // 这里沿圆周取一条一维 fbm（θ → 单位圆上的噪声坐标），叠加到半径上：
        // 它天然是分形的，而且和域扭曲无关，所以不会把「湾」整体推走。
        int na = 720;
        double[] coastWobble = new double[na];
        // 契约给了湾/岬时，海岸的主形由契约决定，碎形只做"毛边"，振幅要收小，
        // 否则随机岬可能顶在契约湾口上，把「凹面朝西」直接抹掉。
        double wobAmp = r0 * (p.coastRegionDriven ? 0.045 + 0.075 * rough : 0.070 + 0.110 * rough);
        for (int k = 0; k < na; k++) {
            double a = TAU * k / na;
            double n1 = noise.fbm(Math.cos(a) * 3.2 + 7.7, Math.sin(a) * 3.2 - 4.1, 4, 2.0, 0.55);
            double n2 = noise.fbm(Math.cos(a) * 9.4 - 2.3, Math.sin(a) * 9.4 + 6.5, 2, 2.0, 0.5);
            coastWobble[k] = (n1 - 0.5) * 2.0 + (n2 - 0.5) * 0.85;
        }

        for (int z = 0; z < size; z++) {
            for (int x = 0; x < size; x++) {
                double dx = x - c;
                double dz = z - c;
                double lx = (dx * cos + dz * sin) / Math.max(0.3, p.elongation);
                double lz = -dx * sin + dz * cos;
                double th = Math.atan2(lz, lx);
                double worldAng = Math.atan2(dz, dx);

                // 多尺度谐波：大尺度决定「南北长、东西窄」，小尺度给出碎边
                double shape = 1 + rough * (0.20 * Math.sin(3 * th + ph1)
                        + 0.13 * Math.sin(5 * th + ph2)
                        + 0.08 * Math.sin(7 * th + ph3)
                        + 0.05 * Math.sin(11 * th + ph4));

                // 角向碎形：把海岸线从「光滑蛋形」变成「有大小凹凸的真海岸」
                double wa = (worldAng < 0 ? worldAng + TAU : worldAng) / TAU * na;
                int w0 = (int) Math.floor(wa);
                double wf = wa - w0;
                double wob = coastWobble[w0 % na] * (1 - wf) + coastWobble[(w0 + 1) % na] * wf;
                shape += rough * wob * (wobAmp / Math.max(1e-6, r0));

                // 域扭曲（两级：大尺度掰弯 + 小尺度毛边）
                double[] w1 = noise.warp(x, z, size * 0.055 * rough, size * 0.16);
                double[] w2 = noise.warp(x, z, size * 0.014 * rough, size * 0.030);
                double rw = Math.hypot(lx + w1[0] + w2[0], lz + w1[1] + w2[1]);
                double signed = r0 * shape - rw;

                for (int i = 0; i < bays; i++) {
                    double d = angleDiff(th, bayAng[i]);
                    signed -= bayDepth[i] * Math.exp(-(d * d) / (2 * 0.26 * 0.26));
                }
                for (int i = 0; i < capes; i++) {
                    double d = angleDiff(th, capeAng[i]);
                    signed += capeLen[i] * Math.exp(-(d * d) / (2 * 0.16 * 0.16));
                }
                for (int i = 0; i < islets; i++) {
                    double ix = c + Math.cos(isletAng[i]) * isletDist[i];
                    double iz = c + Math.sin(isletAng[i]) * isletDist[i];
                    double id = Math.hypot(x - ix, z - iz);
                    signed = Math.max(signed, isletRad[i] - id);
                }

                // ---- 语义区域驱动海岸（M2）：湾内凹 / 岬外凸 / 滩涂缓推 ----
                // 这一步是「月牙岛」得以成立的关键：旧管线只能靠随机谐波凑出几个小湾，
                // 而世界观写的是「凹面朝西抱海湾」，只有按契约方位做定点内凹才能真的凹进去。
                if (p.coastRegionDriven && p.regions != null && !p.regions.isEmpty()) {
                    // 注意：必须用**世界极坐标**（dx,dz）而不是上面的 (lx,lz)——
                    // (lx,lz) 是「按主轴旋转 + 按狭长度压扁」之后的局部坐标，
                    // 拿它的角度和契约的罗盘方位比会整体偏 15~20°，湾就跑到湾不该在的方向去了。
                    double rf = Math.hypot(dx, dz) / Math.max(1.0, r0);
                    for (TerrainRegion rg : p.regions) {
                        double w = rg.angularWeight(worldAng) * rg.radialWeight(rf);
                        if (w <= 0.002) {
                            continue;
                        }
                        switch (rg.kind()) {
                            case TerrainRegion.BAY -> signed -= r0 * 0.80 * w;
                            case TerrainRegion.CAPE -> {
                                // 岬角不能顶到图外：留 5% 边距，否则「北端海岬」会被画布裁断
                                double capeCap = Math.max(0, size * 0.45 - r0);
                                signed += Math.min(r0 * 0.34, capeCap) * w;
                            }
                            case TerrainRegion.MUDFLAT -> signed += r0 * 0.10 * w;
                            default -> {
                                // 其余区域只影响高程/植被，不动海岸线
                            }
                        }
                    }
                }

                // 画布边界软约束：越靠近图边，越用力把岸线压回海里。
                // 硬裁（rw > 某个值就算海）会在图边留下一圈圆弧形硬边，非常假；
                // 这里用二次项渐进压回，既保证岛不会被画布切断，又保持海岸线有机。
                double edgeR = size * 0.42;
                if (rw > edgeR) {
                    double over = rw - edgeR;
                    signed -= over * over / (size * 0.05);
                }

                t.mask[z * size + x] = (float) smoothstep(-edge, edge, signed);
            }
        }
        debugCoast(t, p, r0);
    }

    /**
     * 打印海岸线自检（8 个方位上的陆地半径 + 区域是否生效）。
     *
     * <p>为什么要在生成器里留一行日志：「湾到底有没有凹进去」光看图很难判断
     * （尤其有浅海棚架时），而口径一错（比如方位角用了旋转后的局部坐标系）就整体偏 20°，
     * 图上看不出来但世界观里的「凹面朝西」已经错了。这里把 8 个方位的陆地半径打出来，
     * 一对照契约方位就能立刻定位。</p>
     *
     * @param t  地形
     * @param p  参数
     * @param r0 岛屿基准半径（格）
     */
    private static void debugCoast(TerrainData t, TerrainParams p, double r0) {
        int size = t.size;
        double c = size * 0.5;
        StringBuilder sb = new StringBuilder();
        String[] names = {"N", "NE", "E", "SE", "S", "SW", "W", "NW"};
        for (int k = 0; k < 8; k++) {
            double ang = Math.toRadians(k * 45.0 - 90.0);
            double last = 0;
            for (double r = 4; r < size * 0.5; r += 2) {
                int x = (int) Math.round(c + Math.cos(ang) * r);
                int z = (int) Math.round(c + Math.sin(ang) * r);
                if (x < 0 || z < 0 || x >= size || z >= size) {
                    break;
                }
                if (t.mask[z * size + x] > 0.5f) {
                    last = r;
                }
            }
            sb.append(names[k]).append('=').append(Math.round(last)).append(' ');
        }
        StringBuilder rg = new StringBuilder();
        if (p.regions != null) {
            for (TerrainRegion r : p.regions) {
                rg.append(r.kind()).append('@').append(Math.round(r.bearingDeg())).append(' ');
            }
        }
        org.slf4j.LoggerFactory.getLogger(TerrainGenerator.class).info(
                "[S1] 海岸自检 r0={} regionDriven={} 半径 {} / 区域 [{}]",
                Math.round(r0), p.coastRegionDriven, sb.toString().trim(), rg.toString().trim());
    }

    // ==================== ② 陆上起伏 + ③ 平原 + 归一化 ====================

    private static void buildLandHeight(TerrainData t, TerrainParams p, Noise2D noise) {
        int size = t.size;
        double cx = size * 0.5;
        double bearing = Math.toRadians(p.ridgeBearingDeg);
        double dirX = Math.sin(bearing);
        double dirZ = -Math.cos(bearing);

        int peakCount = Math.max(0, p.peakCount);
        double[][] peaks = new double[peakCount][4];
        Rng pr = Rng.derive(p.seed, 13);
        for (int k = 0; k < peakCount; k++) {
            double s = peakCount == 1 ? 0 : (k / (double) (peakCount - 1) - 0.5);
            peaks[k][0] = cx + dirX * s * size * 0.46 + pr.nextDouble(-1, 1) * size * 0.04;
            peaks[k][1] = cx + dirZ * s * size * 0.46 + pr.nextDouble(-1, 1) * size * 0.04;
            // 山体要「窄而高」，宽了会变成一坨大土包
            peaks[k][2] = size * (0.105 + 0.045 * pr.nextDouble());
            peaks[k][3] = p.peakHeight * (0.70 + 0.55 * pr.nextDouble());
        }

        double pcx = p.plainCenterX * size;
        double pcz = p.plainCenterZ * size;
        double prad = Math.max(0.05, p.plainRadius) * size;
        double relief = Math.max(0.15, p.reliefHeight);

        double maxAbove = 0;
        for (int z = 0; z < size; z++) {
            for (int x = 0; x < size; x++) {
                int i = z * size + x;
                double base = noise.fbm(x / (size * 0.33), z / (size * 0.33), 6, 2.0, 0.5);
                // ---- 山列：把噪声坐标转到「山脊坐标系」并沿脊拉长 ----
                // 旧实现是等向的 ridged 噪声：出来的是一堆朝向随机的圆包，俯视图上读不出「山」，
                // 只像一片起伏的绿布。真实山地是**成列**的：山脊沿构造线延伸 10~20 公里，
                // 垂直脊向只有几百米宽，于是从空中看是一条条平行的山梁（岭脊 + 侧沟）。
                // 做法：沿脊方向的采样尺度拉到垂直方向的 5~7 倍，再用一条高斯脊带掩住整条带，
                // 山就只会长在「脊线两侧」——这同时保证聚落平原不会被随机山包盖住。
                double along = (x - cx) * dirX + (z - cx) * dirZ;
                double across = -(x - cx) * dirZ + (z - cx) * dirX;
                // 主脊（沿构造线拉长）+ 支脊（等向，负责分叉）。
                // 纯各向异性的 ridged 噪声会拉出一排**平行等距的条纹**（"灯芯绒地形"），
                // 因为脊线之间没有交叉；掺 28% 的等向脊之后，主脊上会长出横向支脊，
                // 山体读起来才是"山梁 + 沟谷"，而不是搓衣板。
                double ridge = 0.72 * noise.ridged(along / (size * 0.42) + 3.7,
                        across / (size * 0.16) - 1.9, 5)
                        + 0.28 * noise.ridged(x / (size * 0.20) - 11.3, z / (size * 0.20) + 4.7, 4);
                double mtn = smoothstep(0.46, 0.78,
                        noise.fbm(x / (size * 0.55) + 5.3, z / (size * 0.55) + 9.1, 3, 2.0, 0.5));
                // 脊线掩膜：山只长在构造线附近的一条带上；再叠低频让山列断成几段（垭口）
                double spineW = size * 0.115;
                double spine = Math.exp(-(across * across) / (2 * spineW * spineW));
                spine *= 0.40 + 0.60 * smoothstep(0.34, 0.62,
                        noise.fbm(along / (size * 0.34) + 17.2, across / (size * 0.30) - 8.8, 2, 2.0, 0.5));
                double mountain = 0.52 * ridge * mtn * (0.28 + 0.92 * spine) * p.reliefStrength;
                double above = 0.016 + 0.30 * base + mountain;

                double peakSum = 0;
                for (double[] pk : peaks) {
                    double dd = Math.hypot(x - pk[0], z - pk[1]);
                    peakSum += pk[3] * Math.exp(-(dd * dd) / (2 * pk[2] * pk[2]));
                }
                above += peakSum;

                // ---- 语义区域驱动高程（M2）：丘陵抬高 / 滩涂压平 / 聚落压成缓坡 ----
                // 用乘子而不是加数：真实地貌是「同一套山形，北坡抬起来、南岸摊下去」，
                // 加法会让高处的山更高、低处的洼倒挂，形状就散了。
                if (p.regions != null && !p.regions.isEmpty()) {
                    double bdx = x - cx;
                    double bdz = z - cx;
                    double cellBearing = Math.atan2(bdz, bdx);
                    double rf = Math.hypot(bdx, bdz) / Math.max(1.0, size * Math.max(0.1, p.islandRadiusRatio));
                    above *= TerrainSemantics.elevationFactor(p.regions, cellBearing, rf);
                }

                // 平原边界：不能用「到中心的距离」直接画圆——那会在镇外留下一圈完美的圆形陡坎
                // （俯视图上就是一个巨大的环形土围子）。这里给半径加两级低频噪声，
                // 让「平地/坡地」的交界变成一条自然的、跟着地形走的等值线。
                double pd = Math.hypot(x - pcx, z - pcz) / prad;
                double edgeWobble = 1.0
                        + 0.42 * (noise.fbm(x / (size * 0.13) + 7.1, z / (size * 0.13) + 2.9, 3, 2.0, 0.5) - 0.5)
                        + 0.16 * (noise.fbm(x / (size * 0.045) - 3.3, z / (size * 0.045) + 5.7, 2, 2.0, 0.5) - 0.5);
                pd *= Math.max(0.45, edgeWobble);
                // 过渡带要够宽：smoothstep 的中间段最陡，如果只在 0.4~1.0 半径里完成压平，
                // 就等于在镇边刻一道 30 米高的环形坎。拉到 0.15~1.7 之后是缓坡入田。
                double plainW = smoothstep(1.70, 0.15, pd) * p.plainFraction;
                // 平原压平：**只削粗糙度，不整体下沉**。
                //
                // 早期做法是 `above = above*(1-0.9*plainW) + 常数*plainW`，等于在镇周围挖一个
                // 圆盘状的洼地——镇子安安静静地待在一个大盆里，盆沿是一圈环形陡坎
                // （实测在俯视图上就是一个巨大的「马蹄形土围子」，非常假）。
                // 真实村镇所在的平地是「大尺度缓坡照旧、小尺度起伏没有」，
                // 所以这里保留低频 base，只把山脊/峰体的粗糙部分削掉。
                double rough = mountain + peakSum;
                double tilt = 0.012 * (noise.fbm(x / (size * 0.62) + 11.7, z / (size * 0.62) + 3.9, 2, 2.0, 0.5) - 0.5);
                above = 0.016 + 0.30 * base + rough * (1 - 0.88 * plainW) + tilt * plainW;

                t.height[i] = (float) above;
                if (t.mask[i] > 0.5) {
                    maxAbove = Math.max(maxAbove, above);
                }
            }
        }

        // 归一化：最高点恒为 reliefHeight，保证不同 seed 观感一致
        double scale = 1.0 / Math.max(1e-6, maxAbove);
        // 同时给陆地一个「最低抬升」：语义区域会把滩涂/聚落压得很低（乘子 0.1~0.6），
        // 归一化后这些地方可能贴着 0（= 正好海平面），于是被后续的地板逻辑当成「坑」，
        // 变成一个平坦大盘子 + 一圈竖壁。这里统一重映射到 [0.06·relief, relief]：
        // 最低处仍有约 2 米的岸上高度，视觉上是「低平的滨海平原」而不是「和海一样高」。
        double lo = 0.06;
        for (int i = 0; i < t.height.length; i++) {
            double norm = Math.min(1.0, t.height[i] * scale);
            t.height[i] = (float) (relief * (lo + (1 - lo) * norm));
        }
    }

    // ==================== ④ 海域塑形（到岸距离场） ====================

    private static void carveSea(TerrainData t, TerrainParams p, Noise2D noise) {
        int size = t.size;
        int n = size * size;
        float sea = (float) p.seaLevel;
        boolean[] land = new boolean[n];
        for (int i = 0; i < n; i++) {
            land[i] = t.mask[i] > 0.5;
        }

        // 双向距离场（水侧算「到陆距离」、陆侧算「到海距离」）。
        // 用 1 / √2 加权的 chamfer 变换（两趟扫描，误差 ≈2%）而不是 8 邻接 BFS：
        // BFS 得到的是**切比雪夫距离**，等距线是方块，于是海底会出现一圈方形光晕、
        // 小岛外面套一个正方形亮框——这是早期实测最扎眼的假点。
        double[] seaDist = chamfer(size, land, false);
        double[] landDist = chamfer(size, land, true);

        double shelf = Math.max(6, p.coastShelf);
        for (int z = 0; z < size; z++) {
            for (int x = 0; x < size; x++) {
                int i = z * size + x;
                if (land[i]) {
                    // 岸坡：海岸不该是"一圈台地边缘"。
                    // 原实现把所有陆格抬到海面以上 0.012（≈4.4 米），等于给全岛围了一圈 4 米高的墙；
                    // 而陆形高度场在遮罩边缘（mask≈0.5）本身就还有内陆地形的**一半**高（≈35 米），
                    // 两者叠加的结果就是"海拔 30~50 米的悬崖海岸线"：坡度场沿整条岸线饱和，
                    // 沙滩长不出来，从空中看陆地与海是硬切、还镶了一圈深褐色的崖。
                    //
                    // 这里按"离海距离"把陆地抬高量压到 0：贴海 0.9 米，90 米外才恢复到原地形。
                    // 平均岸坡 ≈ 35/90 ≈ 0.39（≈21°），是缓坡入海的真实海滩/砾滩。
                    double above = Math.max(0.006, t.height[i]);
                    double dc = landDist[i];
                    double taper = sstep(0.0, 90.0, dc);
                    t.height[i] = (float) (sea + 0.006 + (above - 0.006) * taper);
                    continue;
                }
                double d = Math.max(0, seaDist[i]);
                // 棚架宽度本身也要有起伏，否则会出现一圈均匀的「光晕」
                double shelfW = Math.max(8, shelf * (0.72 + 0.56
                        * noise.fbm(x / (size * 0.13), z / (size * 0.13), 3, 2.0, 0.5)));
                // 用 1-exp(-k) 而不是 min(1, k)：前者是渐近的，棚架边缘不会留下一道硬边
                double kk = d / shelfW;
                double curve = 1.0 - StrictMath.exp(-Math.pow(kk, 1.7));
                double warp = 0.86 + 0.28 * noise.fbm(x / (size * 0.10), z / (size * 0.10), 3, 2.0, 0.5);
                t.height[i] = (float) (sea - 0.002 - MAX_SEA_DEPTH * curve * warp);
            }
        }
    }

    /**
     * chamfer 距离变换（3-4 近似欧氏距离）。
     *
     * @param size    边长
     * @param land    陆地掩码
     * @param onLand  true=在陆地上算"到最近海格"的距离；false=在水里算"到最近陆格"的距离
     * @return 每格到目标集合的距离（格，近似欧氏）
     */
    private static double[] chamfer(int size, boolean[] land, boolean onLand) {
        final double S = 1.4142135623730951;
        double[] d = new double[size * size];
        double init = 1e9;
        for (int i = 0; i < d.length; i++) {
            // 目标是"另一侧"：陆上量到海，水里量到陆
            boolean isTarget = onLand ? !land[i] : land[i];
            d[i] = isTarget ? 0 : init;
        }
        for (int z = 0; z < size; z++) {
            for (int x = 0; x < size; x++) {
                int i = z * size + x;
                double v = d[i];
                if (x > 0) {
                    v = Math.min(v, d[i - 1] + 1);
                }
                if (z > 0) {
                    v = Math.min(v, d[i - size] + 1);
                    if (x > 0) {
                        v = Math.min(v, d[i - size - 1] + S);
                    }
                    if (x < size - 1) {
                        v = Math.min(v, d[i - size + 1] + S);
                    }
                }
                d[i] = v;
            }
        }
        for (int z = size - 1; z >= 0; z--) {
            for (int x = size - 1; x >= 0; x--) {
                int i = z * size + x;
                double v = d[i];
                if (x < size - 1) {
                    v = Math.min(v, d[i + 1] + 1);
                }
                if (z < size - 1) {
                    v = Math.min(v, d[i + size] + 1);
                    if (x < size - 1) {
                        v = Math.min(v, d[i + size + 1] + S);
                    }
                    if (x > 0) {
                        v = Math.min(v, d[i + size - 1] + S);
                    }
                }
                d[i] = v;
            }
        }
        // 孤岛/无目标时给一个封顶值，避免 1e9 参与 pow()
        for (int i = 0; i < d.length; i++) {
            if (d[i] > 1e6) {
                d[i] = size * 2.0;
            }
        }
        return d;
    }

    /**
     * 软填「低于海面的内陆坑」——把侵蚀挖出的深坑变成浅碟。
     *
     * <p>这些坑本来会被当成海（渲染成蓝斑），旧做法是硬抬到海平面 + 4 毫米，于是得到
     * 一块绝对水平的地台，四周围着一圈断崖（1 米/格 的高度跃变 × 96 米量程 ≈ 60° 以上）。
     * 现在按「到坑沿的距离」把坑底线性抬起来：坑心仍然接近海面（会成为池塘/湿地），
     * 坑壁则展开成一片缓坡，与周围地形自然接上。</p>
     *
     * @param t 地形
     * @param p 参数
     */
    private static void softenPits(TerrainData t, TerrainParams p) {
        int size = t.size;
        int n = size * size;
        float floor = (float) (p.seaLevel + 0.004);
        boolean[] pit = new boolean[n];
        int cnt = 0;
        for (int i = 0; i < n; i++) {
            if (t.mask[i] > 0.5f && t.height[i] < p.seaLevel) {
                pit[i] = true;
                cnt++;
            }
        }
        if (cnt == 0) {
            return;
        }
        if (cnt < 60) {
            // 小坑：直接填平看不出台阶（几十个格子而已）
            for (int i = 0; i < n; i++) {
                if (pit[i]) {
                    t.height[i] = floor;
                }
            }
            return;
        }
        final double big = 1e9;
        final double s2 = 1.4142135623730951;
        double[] d = new double[n];
        double[] ref = new double[n];
        for (int i = 0; i < n; i++) {
            if (pit[i]) {
                d[i] = big;
                ref[i] = floor;
            } else {
                d[i] = 0;
                ref[i] = t.height[i];
            }
        }
        // 3-4 chamfer 两趟扫描，同时把「最近的非坑格高度」传播过来（近似 Voronoi）
        for (int z = 0; z < size; z++) {
            for (int x = 0; x < size; x++) {
                int i = z * size + x;
                if (d[i] == 0) {
                    continue;
                }
                if (x > 0) {
                    relax(d, ref, i, i - 1, 1);
                }
                if (z > 0) {
                    relax(d, ref, i, i - size, 1);
                    if (x > 0) {
                        relax(d, ref, i, i - size - 1, s2);
                    }
                    if (x < size - 1) {
                        relax(d, ref, i, i - size + 1, s2);
                    }
                }
            }
        }
        for (int z = size - 1; z >= 0; z--) {
            for (int x = size - 1; x >= 0; x--) {
                int i = z * size + x;
                if (d[i] == 0) {
                    continue;
                }
                if (x < size - 1) {
                    relax(d, ref, i, i + 1, 1);
                }
                if (z < size - 1) {
                    relax(d, ref, i, i + size, 1);
                    if (x < size - 1) {
                        relax(d, ref, i, i + size + 1, s2);
                    }
                    if (x > 0) {
                        relax(d, ref, i, i + size - 1, s2);
                    }
                }
            }
        }
        // 坑壁展开宽度：约 5.5% 图幅（1 公里图 ≈ 56 米），坑心到坑沿因此是一条缓坡
        double ramp = Math.max(16, size * 0.055);
        for (int i = 0; i < n; i++) {
            if (!pit[i]) {
                continue;
            }
            // 坑沿（dist≈0）要接上周围地形的高度，坑心（dist 大）落到海面附近：
            // 所以权重是 1 - dist/ramp，不能反着写。写反了会得到一个"中间隆起、四周一圈沟"
            // 的环状伪影（俯视图上像一个巨大的陨石坑圈）。
            double w = 1.0 - Math.min(1.0, (d[i] >= big ? ramp : d[i]) / ramp);
            t.height[i] = (float) (floor + Math.max(0, ref[i] - floor) * w);
        }
    }

    /** chamfer 松弛：邻居更近则继承它的距离与参考高度。 */
    private static void relax(double[] d, double[] ref, int i, int a, double cost) {
        double nd = d[a] + cost;
        if (nd < d[i]) {
            d[i] = nd;
            ref[i] = ref[a];
        }
    }

    /** 平滑阶跃：v 从 a 到 b 过渡到 1。 */
    private static double sstep(double a, double b, double v) {
        double k = (v - a) / (b - a);
        k = k < 0 ? 0 : (k > 1 ? 1 : k);
        return k * k * (3 - 2 * k);
    }

    // ==================== 工具 ====================

    /**
     * 高度场平滑（可分离盒式模糊，半径 3）——得到一个「去噪版」高度，用于坡度/日照计算。
     * <p>1 米分辨率的侵蚀高度场噪点很多，直接用会算出满地陡坡，把整座岛判成岩石。</p>
     *
     * @param t 地形
     */
    private static void smoothHeight(TerrainData t) {
        int size = t.size;
        int r = 3;
        float[] src = t.height;
        float[] tmp = new float[src.length];
        float[] dst = t.smooth;
        for (int z = 0; z < size; z++) {
            int row = z * size;
            double sum = 0;
            int cnt = 0;
            for (int x = -r; x <= r; x++) {
                int xx = Math.max(0, Math.min(size - 1, x));
                sum += src[row + xx];
                cnt++;
            }
            for (int x = 0; x < size; x++) {
                tmp[row + x] = (float) (sum / cnt);
                int add = Math.min(size - 1, x + r + 1);
                int sub = Math.max(0, x - r);
                sum += src[row + add] - src[row + sub];
            }
        }
        for (int x = 0; x < size; x++) {
            double sum = 0;
            int cnt = 0;
            for (int z = -r; z <= r; z++) {
                int zz = Math.max(0, Math.min(size - 1, z));
                sum += tmp[zz * size + x];
                cnt++;
            }
            for (int z = 0; z < size; z++) {
                dst[z * size + x] = (float) (sum / cnt);
                int add = Math.min(size - 1, z + r + 1);
                int sub = Math.max(0, z - r);
                sum += tmp[add * size + x] - tmp[sub * size + x];
            }
        }
    }

    private static double angleDiff(double a, double b) {
        double d = a - b;
        while (d > Math.PI) {
            d -= TAU;
        }
        while (d < -Math.PI) {
            d += TAU;
        }
        return d;
    }

    private static double smoothstep(double e0, double e1, double x) {
        if (e0 == e1) {
            return x < e0 ? 0 : 1;
        }
        double tt = (x - e0) / (e1 - e0);
        tt = tt < 0 ? 0 : (tt > 1 ? 1 : tt);
        return tt * tt * (3 - 2 * tt);
    }
}
