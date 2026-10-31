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

package com.holzyn.actor.domain.mapnav.algo;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * 导航栅格的二进制编解码（{@code <mapId>/nav.bin}）与前端缩略位图编码。
 *
 * <p><b>为什么是自定义二进制而不是 PNG/JSON</b>：JSON 存 100 万格是几十 MB，PNG 是有损
 * （1 位水陆塞进 RGB 再读回来，边缘会被抗锯齿算法抹掉）。这份数据是<b>算法输入</b>，
 * 一格的错就等于人能走进湖里，所以用定长小端二进制，读回来必须逐格相等。</p>
 *
 * <p><b>为什么还要一个 {@link #encodeMask} 的缩略</b>：前端拖拽建筑时要「立刻」把非法位置标红，
 * 又不可能为了吸附去下载 3 MB 的栅格。128² 的水域位图 base64 只有 ~3 KB，够画红绿，
 * 真正的判定仍在服务端（设计文档 §4.7 规则 1~7）。</p>
 */
public final class NavGridCodec {

    /** 头部字节数：magic4 + version4 + size4 + seaLevel8 + metersPerCell8 + seed8。 */
    private static final int HEADER_BYTES = 36;

    private NavGridCodec() {
    }

    /**
     * 编码为 {@code nav.bin} 字节。
     *
     * @param grid 导航栅格
     * @return 字节（1024² 约 3.2 MB）
     */
    public static byte[] encode(NavGrid grid) {
        int n = grid.size * grid.size;
        int waterBytes = (n + 7) / 8;
        ByteArrayOutputStream out = new ByteArrayOutputStream(HEADER_BYTES + waterBytes * 2 + n + n * 2);
        try (DataOutputStream d = new DataOutputStream(new BufferedOutputStream(out, 1 << 16))) {
            d.write("HNAV".getBytes(StandardCharsets.US_ASCII));
            d.writeInt(NavGrid.VERSION);
            d.writeInt(grid.size);
            d.writeDouble(grid.seaLevel);
            d.writeDouble(grid.metersPerCell);
            d.writeLong(grid.seed);
            // 水：1 bit/格（1=水）
            writeBits(d, grid.water, n);
            // 林：1 bit/格（1=林下）
            writeBits(d, grid.forest, n);
            d.write(grid.slope, 0, n);
            for (int i = 0; i < n; i++) {
                d.writeShort(grid.elevation[i]);
            }
        } catch (IOException e) {
            // ByteArrayOutputStream 不会抛，包一层是为了让签名不变
            throw new IllegalStateException("编码导航栅格失败", e);
        }
        return out.toByteArray();
    }

    /** 写 1 位布尔场（小端位序：第 i 格对应第 i>>3 字节的第 i&7 位）。 */
    private static void writeBits(DataOutputStream d, boolean[] src, int n) throws IOException {
        int acc = 0;
        int bit = 0;
        for (int i = 0; i < n; i++) {
            if (src[i]) {
                acc |= (1 << bit);
            }
            if (++bit == 8) {
                d.writeByte(acc);
                acc = 0;
                bit = 0;
            }
        }
        if (bit > 0) {
            d.writeByte(acc);
        }
    }

    /** 读 1 位布尔场。 */
    private static void readBits(DataInputStream d, boolean[] dst, int n) throws IOException {
        byte[] buf = new byte[(n + 7) / 8];
        d.readFully(buf);
        for (int i = 0; i < n; i++) {
            dst[i] = ((buf[i >> 3] >> (i & 7)) & 1) == 1;
        }
    }

    /**
     * 解码 {@code nav.bin}。
     *
     * @param in 输入流（调用方负责关闭）
     * @return 栅格；格式不符返回 null（调用方据此重建，而不是抛异常中断地图页）
     */
    public static NavGrid decode(InputStream in) {
        try (DataInputStream d = new DataInputStream(new BufferedInputStream(in, 1 << 16))) {
            byte[] magic = new byte[4];
            d.readFully(magic);
            if (!"HNAV".equals(new String(magic, StandardCharsets.US_ASCII))) {
                return null;
            }
            int version = d.readInt();
            if (version != NavGrid.VERSION) {
                return null;
            }
            int size = d.readInt();
            if (size <= 0 || size > 8192) {
                return null;
            }
            double seaLevel = d.readDouble();
            double mpc = d.readDouble();
            long seed = d.readLong();
            int n = size * size;
            boolean[] water = new boolean[n];
            readBits(d, water, n);
            boolean[] forest = new boolean[n];
            readBits(d, forest, n);
            byte[] slope = new byte[n];
            d.readFully(slope);
            short[] elev = new short[n];
            for (int i = 0; i < n; i++) {
                elev[i] = d.readShort();
            }
            return new NavGrid(size, seaLevel, mpc, seed, water, slope, elev, forest);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /**
     * 解码字节数组（测试与内存场景用）。
     *
     * @param bytes 字节
     * @return 栅格；损坏返回 null
     */
    public static NavGrid decode(byte[] bytes) {
        if (bytes == null || bytes.length < HEADER_BYTES) {
            return null;
        }
        return decode(new java.io.ByteArrayInputStream(bytes));
    }

    /**
     * 把一份 1 位布尔场（水/可建）压成「缩略位图」：按块多数投票降采样 → 打包 bit → base64。
     *
     * @param src      源场（长度 size*size）
     * @param size     源边长
     * @param target   目标边长（如 128；不整除时按最近的源格取样）
     * @param keepTrue true=把「真」记为 1（水用 true，可建用 false）
     * @return base64 字符串
     */
    public static String encodeMask(boolean[] src, int size, int target, boolean keepTrue) {
        if (src == null || src.length != size * size || target <= 0) {
            return "";
        }
        int per = Math.max(1, size / target);
        int outSize = (size + per - 1) / per;
        byte[] packed = new byte[(outSize * outSize + 7) / 8];
        for (int bz = 0; bz < outSize; bz++) {
            for (int bx = 0; bx < outSize; bx++) {
                int hit = 0;
                int total = 0;
                for (int z = bz * per; z < Math.min(size, (bz + 1) * per); z++) {
                    for (int x = bx * per; x < Math.min(size, (bx + 1) * per); x++) {
                        total++;
                        if (src[z * size + x] == keepTrue) {
                            hit++;
                        }
                    }
                }
                boolean on = total > 0 && hit * 2 >= total;
                if (on) {
                    int i = bz * outSize + bx;
                    packed[i >> 3] |= (byte) (1 << (i & 7));
                }
            }
        }
        return Base64.getEncoder().encodeToString(packed);
    }

    /**
     * 缩略位图边长（供前端画网格时换算比例）。
     *
     * @param size   源边长
     * @param target 目标边长
     * @return 实际输出边长
     */
    public static int maskSize(int size, int target) {
        int per = Math.max(1, size / Math.max(1, target));
        return (size + per - 1) / per;
    }

    /**
     * 便捷写法：把栅格的水面压成缩略位图。
     *
     * @param grid   栅格
     * @param target 目标边长
     * @return base64
     */
    public static String encodeWater(NavGrid grid, int target) {
        return encodeMask(grid.water, grid.size, target, true);
    }
}
