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

package com.holzyn.actor.ai.decision.local;

import com.holzyn.actor.ai.decision.DecisionProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 本地决策模型的目录与状态存储（<b>唯一落盘入口</b>）。
 *
 * <p><b>目录约定</b>（{@code <模型根>/}，模型根 = 配置 {@code local.dir} 或数据目录下
 * {@code decision-models}）：</p>
 * <pre>
 * decision-models/
 *   runtime/python/          # 便携 Python 运行时（可选，缺省用系统 Python 建 venv）
 *   downloads/               # 下载中的临时文件（Range 续传用 .part）
 *   adapters/&lt;id&gt;/
 *     state.json             # 安装/运行状态（本类读写）
 *     weights/               # 权重与分词器（HF 快照子集）
 *     repo/                  # 官方源码（repo-script 形态）
 *     venv/                  # 虚拟环境
 *     logs/install.log       # 安装日志
 *     logs/serve.log         # 服务日志
 *   selection.json           # 用户选择（api/local + 适配器），见 {@link Selection}
 * </pre>
 *
 * <p><b>为什么不放安装包内</b>：桌面安装包默认不含模型本体，且可能装在只读目录
 * （Program Files）——所有 GB 级产物一律落在用户数据目录。</p>
 */
@Slf4j
@Component
public class DecisionLocalStore {

    /** 模型根目录下的子目录名 */
    private static final String SUBDIR = "decision-models";

    /** 状态文件名 */
    private static final String STATE_FILE = "state.json";

    /** 用户选择文件名 */
    private static final String SELECTION_FILE = "selection.json";

    private final String dataDir;

    private final DecisionProperties properties;

    /**
     * 构造（数据目录与项目其它组件同源：{@code holzyn.actor.data-dir}）。
     *
     * @param dataDir 数据目录
     *
     * @param properties 决策模型配置（取 local.dir）
     */
    @Autowired
    public DecisionLocalStore(@org.springframework.beans.factory.annotation.Value("${holzyn.actor.data-dir:./data}") String dataDir,
                              DecisionProperties properties) {
        this.dataDir = dataDir == null || dataDir.isBlank() ? "./data" : dataDir;
        this.properties = properties;
    }

    /** 模型根目录（不存在时按需创建）。 */
    public Path root() {
        String configured = properties == null || properties.getLocal() == null
                ? "" : properties.getLocal().getDir();
        Path root = configured == null || configured.isBlank()
                ? Path.of(dataDir, SUBDIR)
                : Path.of(configured);
        ensureDir(root);
        return root.toAbsolutePath().normalize();
    }

    /** 下载临时目录。 */
    public Path downloadsDir() {
        return ensureDir(root().resolve("downloads"));
    }

    /** 指定适配器的目录（{@code adapters/<id>}）。 */
    public Path adapterDir(String adapterId) {
        return ensureDir(root().resolve("adapters").resolve(safeName(adapterId)));
    }

    /** 权重目录。 */
    public Path weightsDir(String adapterId) {
        return ensureDir(adapterDir(adapterId).resolve("weights"));
    }

    /** 源码目录（repo-script 形态）。 */
    public Path repoDir(String adapterId) {
        return ensureDir(adapterDir(adapterId).resolve("repo"));
    }

    /** 虚拟环境目录。 */
    public Path venvDir(String adapterId) {
        return ensureDir(adapterDir(adapterId).resolve("venv"));
    }

    /** 日志目录。 */
    public Path logsDir(String adapterId) {
        return ensureDir(adapterDir(adapterId).resolve("logs"));
    }

    /** 虚拟环境 Python 可执行文件（Windows=Scripts/python.exe，其它=bin/python3）。 */
    public Path venvPython(String adapterId) {
        Path venv = venvDir(adapterId);
        Path win = venv.resolve("Scripts").resolve("python.exe");
        if (Files.exists(win)) {
            return win;
        }
        Path nix = venv.resolve("bin").resolve("python3");
        if (Files.exists(nix)) {
            return nix;
        }
        String os = System.getProperty("os.name", "");
        return os.toLowerCase(java.util.Locale.ROOT).contains("win") ? win : nix;
    }

    /** 读取状态（不存在返回默认 not-installed）。 */
    public DecisionLocalState readState(String adapterId) {
        Path f = adapterDir(adapterId).resolve(STATE_FILE);
        if (!Files.exists(f)) {
            DecisionLocalState s = new DecisionLocalState();
            s.setAdapterId(adapterId);
            return s;
        }
        try {
            DecisionLocalState s = JsonStore.MAPPER.readValue(Files.readString(f, StandardCharsets.UTF_8),
                    DecisionLocalState.class);
            if (s == null) {
                s = new DecisionLocalState();
                s.setAdapterId(adapterId);
            }
            s.setAdapterId(adapterId);
            return s;
        } catch (Exception e) {
            log.warn("[Decision.Local] 状态文件损坏，按未安装处理：adapter={} 原因={}", adapterId, e.getMessage());
            DecisionLocalState s = new DecisionLocalState();
            s.setAdapterId(adapterId);
            return s;
        }
    }

    /** 写入状态（原子替换；失败仅告警，不阻断安装流程）。 */
    public void writeState(DecisionLocalState state) {
        if (state == null || state.getAdapterId() == null || state.getAdapterId().isBlank()) {
            return;
        }
        state.setUpdatedAt(Instant.now().toString());
        Path f = adapterDir(state.getAdapterId()).resolve(STATE_FILE);
        try {
            Files.writeString(f, JsonStore.MAPPER.writeValueAsString(state), StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.warn("[Decision.Local] 状态写入失败：adapter={} 原因={}", state.getAdapterId(), e.getMessage());
        }
    }

    /** 读取用户选择（不存在返回默认 api + 空适配器）。 */
    public Selection readSelection() {
        Path f = root().resolve(SELECTION_FILE);
        if (!Files.exists(f)) {
            return new Selection();
        }
        try {
            Selection s = JsonStore.MAPPER.readValue(Files.readString(f, StandardCharsets.UTF_8), Selection.class);
            return s == null ? new Selection() : s;
        } catch (Exception e) {
            return new Selection();
        }
    }

    /** 写入用户选择。 */
    public void writeSelection(Selection selection) {
        try {
            Files.writeString(root().resolve(SELECTION_FILE),
                    JsonStore.MAPPER.writeValueAsString(selection), StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("[Decision.Local] 选择写入失败：原因={}", e.getMessage());
        }
    }

    /** 追加安装/服务日志（时间戳前缀，最大保留由调用方决定）。 */
    public void appendLog(String adapterId, String file, String line) {
        try {
            Path f = logsDir(adapterId).resolve(file);
            Files.writeString(f, "[" + Instant.now() + "] " + line + System.lineSeparator(),
                    StandardCharsets.UTF_8,
                    java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        } catch (Exception e) {
            log.debug("[Decision.Local] 日志写入失败：{}", e.getMessage());
        }
    }

    /** 目录大小（字节；不存在返回 0）。 */
    public long dirSize(Path dir) {
        if (dir == null || !Files.exists(dir)) {
            return 0;
        }
        final long[] sum = {0};
        try (var stream = Files.walk(dir)) {
            stream.filter(Files::isRegularFile).forEach(p -> {
                try {
                    sum[0] += Files.size(p);
                } catch (IOException ignored) {
                    // 单文件不可读时跳过（统计用途）
                }
            });
        } catch (IOException ignored) {
            return sum[0];
        }
        return sum[0];
    }

    /** 递归删除目录（不存在返回 false）。 */
    public boolean deleteRecursively(Path dir) {
        if (dir == null || !Files.exists(dir)) {
            return false;
        }
        try (var stream = Files.walk(dir)) {
            stream.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // 被占用时留待下次清理
                }
            });
            return true;
        } catch (IOException e) {
            log.warn("[Decision.Local] 删除目录失败：{} 原因={}", dir, e.getMessage());
            return false;
        }
    }

    /** 校验适配器 ID 作为目录名安全（只允许字母数字与连字符/下划线）。 */
    static String safeName(String adapterId) {
        String id = adapterId == null ? "" : adapterId.trim();
        if (id.isEmpty() || !id.matches("[A-Za-z0-9._-]+")) {
            throw new IllegalArgumentException("非法适配器 ID：" + adapterId);
        }
        return id;
    }

    private static Path ensureDir(Path p) {
        try {
            Files.createDirectories(p);
        } catch (IOException e) {
            throw new IllegalStateException("无法创建目录：" + p + "（" + e.getMessage() + "）", e);
        }
        return p;
    }

    /**
     * 用户选择（{@code selection.json}）：运行形态 + 适配器 + 可选覆盖端点。
     * <p>为什么不用 DB：本地部署是「这台机器」的属性（换机不跟随），文件更贴合语义；
     * 且与模型目录同源，卸载模型目录=一起清空，不会留下悬空配置。</p>
     */
    @lombok.Getter
    @lombok.Setter
    public static class Selection {
        /** 运行形态：api / local */
        private String mode = "api";
        /** 适配器 ID */
        private String adapter = "";
        /** api 形态端点覆盖（空=用 yml 配置） */
        private String baseUrl = "";
        /** 本地端口（0=适配器默认） */
        private int localPort;
        /** 更新时间 */
        private String updatedAt = "";

        /** 转为 Map（前端展示）。 */
        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("mode", mode);
            m.put("adapter", adapter);
            m.put("baseUrl", baseUrl);
            m.put("localPort", localPort);
            m.put("updatedAt", updatedAt);
            return m;
        }
    }

    /** 统一 Jackson 配置（本地文件读写，宽松：容忍未知字段）。 */
    static final class JsonStore {
        static final tools.jackson.databind.ObjectMapper MAPPER =
                new tools.jackson.databind.ObjectMapper();

        private JsonStore() {
        }
    }
}
