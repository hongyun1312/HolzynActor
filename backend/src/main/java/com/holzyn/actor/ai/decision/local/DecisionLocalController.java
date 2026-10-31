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

import com.holzyn.actor.ai.decision.DecisionCallClient;
import com.holzyn.actor.ai.decision.DecisionEndpoint;
import com.holzyn.actor.ai.decision.DecisionProperties;
import com.holzyn.actor.ai.decision.adapter.DecisionAdapter;
import com.holzyn.actor.ai.decision.adapter.DecisionAdapters;
import com.holzyn.actor.common.R;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 决策模型本地部署与运行形态管理接口（{@code /api/settings/decision-models/**}）。
 *
 * <p><b>职责边界</b>：只负责「装/卸/启/停 + 选择运行形态」；真正的推理调用走
 * {@link DecisionCallClient}，两者通过 {@link DecisionLocalStore.Selection}（selection.json）
 * 与 {@link DecisionLocalRuntime#activeEndpoint()} 解耦。</p>
 *
 * <p><b>为什么下载要两个源</b>：HF 官方站在国内经常连不通，用户需要一个「点一下就成」的路径；
 * 两个按钮 = 同一份清单两套 host，不做自动测速（多一次探测就多一次卡顿）。</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/settings/decision-models")
public class DecisionLocalController {

    private final DecisionLocalInstaller installer;

    private final DecisionLocalRuntime runtime;

    private final DecisionLocalStore store;

    private final DecisionAdapters adapters;

    private final DecisionProperties properties;

    private final DecisionCallClient callClient;

    /**
     * 构造。
     *
     * @param installer 安装器
     *
     * @param runtime 进程托管
     *
     * @param store 目录与状态
     *
     * @param adapters 适配器注册表
     *
     * @param properties 配置
     *
     * @param callClient 决策模型调用客户端（只读用于展示生效端点）
     */
    @Autowired
    public DecisionLocalController(DecisionLocalInstaller installer,
                                  DecisionLocalRuntime runtime,
                                  DecisionLocalStore store,
                                  DecisionAdapters adapters,
                                  DecisionProperties properties,
                                  DecisionCallClient callClient) {
        this.installer = installer;
        this.runtime = runtime;
        this.store = store;
        this.adapters = adapters;
        this.properties = properties;
        this.callClient = callClient;
    }

    /**
     * 目录 + 当前状态总览（设置页一次拉全）。
     *
     * @return {@code {adapters:[...], selection:{...}, local:{...}, remote:{...}}}
     */
    @GetMapping("/catalog")
    public R<Map<String, Object>> catalog() {
        List<Map<String, Object>> list = new ArrayList<>();
        for (DecisionAdapter adapter : adapters.all()) {
            DecisionLocalState state = runtime.status(adapter.id());
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", adapter.id());
            item.put("name", adapter.displayName());
            item.put("vendor", adapter.vendor());
            item.put("description", adapter.description());
            item.put("protocol", adapter.protocol().id());
            item.put("modelId", adapter.defaultModelId());
            item.put("localDeployable", adapter.localDeployable());
            item.put("runtimeKind", adapter.runtimeKind());
            item.put("weightsRepo", adapter.weightsRepo());
            item.put("weightsRevision", adapter.weightsRevision());
            item.put("approxWeightsBytes", adapter.approxWeightsBytes());
            item.put("defaultLocalPort", adapter.defaultLocalPort());
            item.put("license", adapter.licenseName());
            item.put("docsUrl", adapter.docsUrl());
            item.put("defaultBaseUrl", adapter.defaultBaseUrl());
            item.put("state", stateToMap(state));
            item.put("installed", state.installed());
            item.put("running", runtime.isRunning(adapter.id()));
            list.add(item);
        }
        DecisionLocalStore.Selection selection = store.readSelection();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("adapters", list);
        out.put("selection", selection.toMap());
        Map<String, Object> local = new LinkedHashMap<>();
        local.put("enabled", properties.getLocal() != null && properties.getLocal().isEnabled());
        local.put("dir", store.root().toString());
        local.put("rootSizeBytes", store.dirSize(store.root()));
        local.put("pythonVersion", properties.getLocal() == null ? "" : properties.getLocal().getPythonVersion());
        local.put("autoStart", properties.getLocal() != null && properties.getLocal().isAutoStart());
        out.put("local", local);
        Map<String, Object> remote = new LinkedHashMap<>();
        remote.put("configuredBaseUrl", properties.getBaseUrl());
        remote.put("configuredAdapter", properties.getAdapter());
        remote.put("configuredModelId", properties.getModelId());
        remote.put("enabled", properties.isEnabled());
        out.put("remote", remote);
        out.put("mode", effectiveMode());
        out.put("adapter", effectiveAdapterId());
        out.put("endpoint", endpointToMap());
        return R.ok(out);
    }

    /**
     * 单个适配器的安装/运行状态（前端轮询用）。
     *
     * @param adapter 适配器 ID（空=默认）
     *
     * @return 状态
     */
    @GetMapping("/status")
    public R<Map<String, Object>> status(@RequestParam(required = false) String adapter) {
        DecisionAdapter a = adapters.resolve(adapter);
        return R.ok(stateToMap(runtime.status(a.id())));
    }

    /**
     * 启动安装（下载 + 部署，异步）。
     *
     * @param body {@code {adapter, source}}
     *
     * @return 初始状态
     */
    @PostMapping("/install")
    public R<Map<String, Object>> install(@RequestBody Map<String, Object> body) {
        String adapter = str(body.get("adapter"));
        String source = str(body.get("source"));
        try {
            DecisionLocalState state = installer.install(adapter, source);
            return R.ok(stateToMap(state));
        } catch (Exception e) {
            return R.fail(400, e.getMessage());
        }
    }

    /**
     * 取消安装。
     *
     * @param body {@code {adapter}}
     *
     * @return {@code {cancelled:true|false}}
     */
    @PostMapping("/cancel")
    public R<Map<String, Object>> cancel(@RequestBody(required = false) Map<String, Object> body) {
        String adapter = adapters.resolve(body == null ? null : str(body.get("adapter"))).id();
        boolean ok = installer.cancel(adapter);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("cancelled", ok);
        return R.ok(out);
    }

    /**
     * 启动本地模型进程。
     *
     * @param body {@code {adapter, port}}
     *
     * @return 启动后状态
     */
    @PostMapping("/start")
    public R<Map<String, Object>> start(@RequestBody(required = false) Map<String, Object> body) {
        String adapter = adapters.resolve(body == null ? null : str(body.get("adapter"))).id();
        int port = body == null ? 0 : toInt(body.get("port"), 0);
        try {
            return R.ok(stateToMap(runtime.start(adapter, port)));
        } catch (Exception e) {
            return R.fail(400, e.getMessage());
        }
    }

    /**
     * 停止本地模型进程。
     *
     * @return {@code {stopped:true}}
     */
    @PostMapping("/stop")
    public R<Map<String, Object>> stop() {
        String running = runtime.runningAdapterId();
        runtime.stop();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("stopped", true);
        out.put("adapter", running);
        return R.ok(out);
    }

    /**
     * 卸载（先停进程再删文件）。
     *
     * @param body {@code {adapter}}
     *
     * @return {@code {uninstalled:true}}
     */
    @PostMapping("/uninstall")
    public R<Map<String, Object>> uninstall(@RequestBody(required = false) Map<String, Object> body) {
        String adapter = adapters.resolve(body == null ? null : str(body.get("adapter"))).id();
        if (runtime.isRunning(adapter)) {
            runtime.stop();
        }
        installer.uninstall(adapter);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("uninstalled", true);
        return R.ok(out);
    }

    /**
     * 保存运行形态与适配器选择（切到本地时顺带启动，切到 API 时停进程）。
     *
     * @param body {@code {mode, adapter, baseUrl, localPort}}
     *
     * @return 生效后的选择与端点
     */
    @PostMapping("/selection")
    public R<Map<String, Object>> selection(@RequestBody Map<String, Object> body) {
        String mode = str(body.get("mode"));
        String adapterId = str(body.get("adapter"));
        DecisionAdapter adapter = adapters.resolve(adapterId.isBlank() ? null : adapterId);
        String selectedMode = "local".equalsIgnoreCase(mode) ? "local" : "api";
        int port = toInt(body.get("localPort"), 0);

        DecisionLocalStore.Selection selection = new DecisionLocalStore.Selection();
        selection.setMode(selectedMode);
        selection.setAdapter(adapter.id());
        selection.setBaseUrl(str(body.get("baseUrl")));
        selection.setLocalPort(port);
        selection.setUpdatedAt(java.time.Instant.now().toString());

        if ("local".equals(selectedMode)) {
            DecisionLocalState state = store.readState(adapter.id());
            if (!state.installed()) {
                return R.fail(400, "本地模型尚未安装：" + adapter.displayName()
                        + "（请先点下载按钮完成安装）");
            }
            try {
                runtime.start(adapter.id(), port);
            } catch (Exception e) {
                return R.fail(400, "启动本地模型失败：" + e.getMessage());
            }
        } else {
            runtime.stop();
        }
        store.writeSelection(selection);
        log.info("[Decision.Local] 切换运行形态：mode={} adapter={}", selectedMode, adapter.id());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("selection", selection.toMap());
        out.put("mode", effectiveMode());
        out.put("adapter", effectiveAdapterId());
        out.put("endpoint", endpointToMap());
        return R.ok(out);
    }

    /**
     * 手动指定自定义启动命令（排障逃生门：官方脚本改名/参数变化时不必改代码）。
     *
     * @param body {@code {adapter, command}}
     *
     * @return 保存后的状态
     */
    @PostMapping("/custom-command")
    public R<Map<String, Object>> customCommand(@RequestBody Map<String, Object> body) {
        String adapter = adapters.resolve(str(body.get("adapter"))).id();
        DecisionLocalState state = store.readState(adapter);
        state.setCustomCommand(str(body.get("command")));
        store.writeState(state);
        return R.ok(stateToMap(state));
    }

    // ------------------------------------------------------------------

    private String effectiveMode() {
        String mode = store.readSelection().getMode();
        if (mode != null && !mode.isBlank()) {
            return mode;
        }
        return callClient == null ? properties.getMode() : callClient.mode();
    }

    private String effectiveAdapterId() {
        String id = store.readSelection().getAdapter();
        if (id != null && !id.isBlank()) {
            return id;
        }
        return callClient == null ? properties.getAdapter() : callClient.adapterId();
    }

    private Map<String, Object> endpointToMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        Optional<DecisionEndpoint> ep = runtime.activeEndpoint();
        if (ep.isPresent()) {
            DecisionEndpoint e = ep.get();
            m.put("adapter", e.adapterId());
            m.put("baseUrl", e.baseUrl());
            m.put("evaluateUrl", e.evaluateUrl());
            m.put("local", e.local());
            m.put("ready", true);
            return m;
        }
        m.put("ready", false);
        m.put("adapter", effectiveAdapterId());
        return m;
    }

    private Map<String, Object> stateToMap(DecisionLocalState s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("adapter", s.getAdapterId());
        m.put("status", s.getStatus());
        m.put("message", s.getMessage());
        m.put("downloadedBytes", s.getDownloadedBytes());
        m.put("totalBytes", s.getTotalBytes());
        m.put("progressPercent", s.progressPercent());
        m.put("source", s.getSource());
        m.put("weightsDir", s.getWeightsDir());
        m.put("repoDir", s.getRepoDir());
        m.put("venvDir", s.getVenvDir());
        m.put("pythonExe", s.getPythonExe());
        m.put("port", s.getPort());
        m.put("pid", s.getPid());
        m.put("lastError", s.getLastError());
        m.put("installedAt", s.getInstalledAt());
        m.put("updatedAt", s.getUpdatedAt());
        m.put("customCommand", s.getCustomCommand());
        return m;
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o).trim();
    }

    private static int toInt(Object o, int fallback) {
        if (o instanceof Number n) {
            return n.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(o).trim());
        } catch (Exception e) {
            return fallback;
        }
    }
}
