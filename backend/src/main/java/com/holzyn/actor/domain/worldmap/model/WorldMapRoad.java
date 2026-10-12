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

package com.holzyn.actor.domain.worldmap.model;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 世界地图生成 · 道路（折线路径 + 宽度；坐标 0..scene_size 地图坐标）。
 */
@Data
public class WorldMapRoad {

    /** 道路名（「村中主道」） */
    private String name;

    /** 折线路径 [[x,z],…]（至少 2 点） */
    private List<double[]> path = new ArrayList<>();

    /** 道路宽度（格=米） */
    private double width = 3.0;
}
