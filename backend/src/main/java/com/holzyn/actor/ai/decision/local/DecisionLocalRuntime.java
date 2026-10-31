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

import com.holzyn.actor.ai.decision.DecisionEndpoint;
import com.holzyn.actor.ai.decision.DecisionProperties;
import com.holzyn.actor.ai.decision.adapter.DecisionAdapter;
import com.holzyn.actor.ai.decision.adapter.DecisionAdapters;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 本地决策模型进程托管：<b>启动 / 停止 / 健康检查 / 端点解析</b>。
 *
 * <p><b>为什么同时只允许一个进程</b>：三家模型各自 1.3~4.5GB 权重 + torch 运行时，
 * 同时常驻在 8GB 内存的用户机上必然 OOM；切换模型时先停旧进程再起新进程（热换）。</p>
 *
 * <p><b>为什么健康检查先探端口</b>：不同实现的健康路径不一致（Laya 有 {@code /health}，
 * 部分适配器的服务没有），以「端口可连接 + 进程未退出」为准，再尽力探一次 HTTP 只作日志依据。</p>
 */
@Slf4j
@Component
public class DecisionLocalRuntime {

    /** 启动等待窗口（秒）：首次加载 torch 权重可能较慢 */
    private static final int START_TIMEOUT_SECONDS = 180;

    /** 端口探测间隔（毫秒） */
    private static final long POLL_INTERVAL_MS = 500L;

    private final DecisionProperties properties;

    private final DecisionLocalStore store;

    private final DecisionAdapters adapters;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .build();

    private final Object lock = new Object();

    /** 当前托管的进程（null=未运行） */
    private volatile Process process;

    /** 当前运行的适配器 ID（空=未运行） */
    private volatile String runningAdapterId = "";

    /** 当前监听端口（0=未运行） */
    private volatile int runningPort;

    /**
     * 构造。
     *
     * @param properties 决策模型配置
     *
     * @param store 目录与状态存储
     *
     * @param adapters 适配器注册表
     */
    @Autowired
    public DecisionLocalRuntime(DecisionProperties properties,
                               DecisionLocalStore store,
                               DecisionAdapters adapters) {
        this.properties = properties;
        this.store = store;
        this.adapters = adapters;
    }

    /**
     * 启动指定适配器的本地进程（已在运行同一适配器时直接复用）。
     *
     * @param adapterId 适配器 ID
     *
     * @param portOverride 端口覆盖（0=适配器默认）
     *
     * @return 启动后的状态
     */
    public DecisionLocalState start(String adapterId, int portOverride) {
        DecisionAdapter adapter = adapters.resolve(adapterId);
        synchronized (lock) {
            if (process != null && process.isAlive()) {
                if (adapter.id().equals(runningAdapterId)) {
                    return currentState(adapter);
                }
                stopLocked();
            }
            DecisionLocalState state = store.readState(adapter.id());
            if (!state.installed()) {
                throw new IllegalStateException("尚未安装，无法启动：" + adapter.displayName());
            }
            int port = portOverride > 0 ? portOverride : adapter.defaultLocalPort();
            if (portInUse(port)) {
                throw new IllegalStateException("端口已被占用：" + port + "（请在设置页改用其它端口）");
            }
            List<String> command = buildCommand(adapter, port);
            state.setStatus(DecisionLocalState.STATUS_STARTING);
            state.setMessage("正在启动 " + adapter.displayName());
            state.setPort(port);
            state.setLastError("");
            store.writeState(state);

            try {
                ProcessBuilder pb = new ProcessBuilder(command);
                Map<String, String> env = pb.environment();
                env.put("PYTHONUNBUFFERED", "1");
                env.put("PYTHONIOENCODING", "utf-8");
                env.putAll(adapter.serveEnvironment(port));
                java.nio.file.Path workDir = "repo-script".equalsIgnoreCase(adapter.runtimeKind())
                        ? store.repoDir(adapter.id()) : store.adapterDir(adapter.id());
                pb.directory(workDir.toFile());
                pb.redirectErrorStream(true);
                pb.redirectOutput(ProcessBuilder.Redirect.appendTo(
                        store.logsDir(adapter.id()).resolve("serve.log").toFile()));
                process = pb.start();
                runningAdapterId = adapter.id();
                runningPort = port;
                store.appendLog(adapter.id(), "install.log", "启动进程 pid=" + process.pid()
                        + " 命令=" + String.join(" ", command));

                DecisionLocalState started = store.readState(adapter.id());
                started.setPid(process.pid());
                started.setPort(port);
                store.writeState(started);

                if (!waitUntilReady(adapter, port)) {
                    String tail = tailServeLog(adapter.id());
                    stopLocked();
                    DecisionLocalState failed = store.readState(adapter.id());
                    failed.setStatus(DecisionLocalState.STATUS_ERROR);
                    failed.setMessage("启动超时，请查看日志");
                    failed.setLastError("进程未在 " + START_TIMEOUT_SECONDS + " 秒内监听端口 " + port
                            + (tail.isBlank() ? "" : "；日志尾部：" + tail));
                    failed.setPid(0);
                    store.writeState(failed);
                    throw new IllegalStateException(failed.getLastError());
                }
                DecisionLocalState running = store.readState(adapter.id());
                running.setStatus(DecisionLocalState.STATUS_RUNNING);
                running.setMessage("运行中（端口 " + port + "）");
                running.setLastError("");
                store.writeState(running);
                log.info("[Decision.Local] 已启动：adapter={} port={} pid={}", adapter.id(), port, process.pid());
                return running.copy();
            } catch (IllegalStateException e) {
                throw e;
            } catch (Exception e) {
                stopLocked();
                DecisionLocalState failed = store.readState(adapter.id());
                failed.setStatus(DecisionLocalState.STATUS_ERROR);
                failed.setMessage("启动失败");
                failed.setLastError(brief(e));
                failed.setPid(0);
                store.writeState(failed);
                log.warn("[Decision.Local] 启动失败：adapter={} 原因={}", adapter.id(), e.toString());
                throw new IllegalStateException("启动失败：" + brief(e), e);
            }
        }
    }

    /** 停止当前进程（幂等）。 */
    public void stop() {
        synchronized (lock) {
            stopLocked();
        }
    }

    /** 当前状态快照。 */
    public DecisionLocalState status(String adapterId) {
        DecisionAdapter adapter = adapters.resolve(adapterId);
        return currentState(adapter).copy();
    }

    /** 当前运行中的适配器 ID（空=未运行）。 */
    public String runningAdapterId() {
        Process p = process;
        return p != null && p.isAlive() ? runningAdapterId : "";
    }

    /** 是否正在运行指定适配器。 */
    public boolean isRunning(String adapterId) {
        Process p = process;
        return p != null && p.isAlive() && adapterId.equals(runningAdapterId);
    }

    /**
     * 本地端点（供调用链解析）。
     *
     * @return 运行中时返回端点，否则 empty
     */
    public Optional<DecisionEndpoint> activeEndpoint() {
        Process p = process;
        if (p == null || !p.isAlive()) {
            return Optional.empty();
        }
        DecisionAdapter adapter = adapters.resolve(runningAdapterId);
        DecisionAdapter.LocalEndpoint ep = adapter.localEndpoint(runningPort);
        return Optional.of(new DecisionEndpoint(adapter.id(), ep.baseUrl(), ep.evaluatePath(),
                adapter.defaultModelId(), true));
    }

    /** 应用就绪后按用户选择自动拉起（失败只记日志，不阻断启动）。 */
    @EventListener(ApplicationReadyEvent.class)
    public void autoStartOnReady() {
        if (properties.getLocal() == null || !properties.getLocal().isAutoStart()) {
            return;
        }
        DecisionLocalStore.Selection selection = store.readSelection();
        if (!"local".equalsIgnoreCase(selection.getMode())) {
            return;
        }
        String adapterId = selection.getAdapter() == null || selection.getAdapter().isBlank()
                ? adapters.defaultAdapter().id() : selection.getAdapter();
        DecisionAdapter adapter = adapters.resolve(adapterId);
        DecisionLocalState state = store.readState(adapter.id());
        if (!state.installed()) {
            log.info("[Decision.Local] 选择为本地形态但未安装，跳过自动启动：adapter={}", adapter.id());
            return;
        }
        Thread t = new Thread(() -> {
            try {
                start(adapter.id(), selection.getLocalPort());
            } catch (Exception e) {
                log.warn("[Decision.Local] 自动启动失败：adapter={} 原因={}", adapter.id(), e.getMessage());
            }
        }, "decision-local-autostart");
        t.setDaemon(true);
        t.start();
    }

    /** 容器关闭 / 应用退出时清理子进程，避免孤儿进程占端口。 */
    @PreDestroy
    public void shutdown() {
        synchronized (lock) {
            stopLocked();
        }
    }

    // ------------------------------------------------------------------

    private DecisionLocalState currentState(DecisionAdapter adapter) {
        DecisionLocalState state = store.readState(adapter.id());
        Process p = process;
        if (p != null && p.isAlive() && adapter.id().equals(runningAdapterId)) {
            state.setStatus(DecisionLocalState.STATUS_RUNNING);
            state.setPid(p.pid());
            state.setPort(runningPort);
        } else if (DecisionLocalState.STATUS_RUNNING.equals(state.getStatus())
                || DecisionLocalState.STATUS_STARTING.equals(state.getStatus())) {
            state.setStatus(DecisionLocalState.STATUS_STOPPED);
            state.setPid(0);
        }
        return state;
    }

    private void stopLocked() {
        Process p = process;
        String adapterId = runningAdapterId;
        process = null;
        runningAdapterId = "";
        runningPort = 0;
        if (p != null && p.isAlive()) {
            p.descendants().forEach(ProcessHandle::destroy);
            p.destroy();
            try {
                if (!p.waitFor(12, java.util.concurrent.TimeUnit.SECONDS)) {
                    p.descendants().forEach(ProcessHandle::destroyForcibly);
                    p.destroyForcibly();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                p.destroyForcibly();
            }
            log.info("[Decision.Local] 已停止进程：adapter={} pid={}", adapterId, p.pid());
        }
        if (!adapterId.isBlank()) {
            DecisionLocalState state = store.readState(adapterId);
            if (!DecisionLocalState.STATUS_NOT_INSTALLED.equals(state.getStatus())) {
                state.setStatus(DecisionLocalState.STATUS_STOPPED);
                state.setMessage("已停止");
                state.setPid(0);
                state.setPort(0);
                store.writeState(state);
            }
        }
    }

    private List<String> buildCommand(DecisionAdapter adapter, int port) {
        DecisionLocalState state = store.readState(adapter.id());
        if (state.getCustomCommand() != null && !state.getCustomCommand().isBlank()) {
            List<String> custom = new ArrayList<>();
            for (String s : state.getCustomCommand().split("\\s+")) {
                if (!s.isBlank()) {
                    custom.add(s);
                }
            }
            if (!custom.isEmpty()) {
                store.appendLog(adapter.id(), "install.log", "使用自定义命令：" + state.getCustomCommand());
                return custom;
            }
        }
        // pip 形态：虚拟环境里的 console script 才是真正的入口（python -m 包名多数发行版不可用）
        String script = resolveConsoleScript(adapter);
        if (!script.isEmpty()) {
            List<String> cmd = new ArrayList<>();
            cmd.add(script);
            cmd.addAll(adapter.consoleArgs(store.weightsDir(adapter.id()).toString(), port));
            store.appendLog(adapter.id(), "install.log", "使用控制台脚本启动：" + cmd);
            return cmd;
        }
        String python = fileExists(state.getPythonExe()) ? state.getPythonExe()
                : store.venvPython(adapter.id()).toString();
        List<String> cmd = adapter.serveCommand(python, store.repoDir(adapter.id()).toString(),
                store.weightsDir(adapter.id()).toString(), port);
        if (cmd == null || cmd.isEmpty()) {
            throw new IllegalStateException("适配器未提供启动命令：" + adapter.id());
        }
        return cmd;
    }

    /**
     * 在虚拟环境里找适配器声明的控制台脚本。
     *
     * @param adapter 适配器
     *
     * @return 脚本绝对路径；空=没找到（回退到 python -m / 官方脚本）
     */
    private String resolveConsoleScript(DecisionAdapter adapter) {
        String name = adapter.consoleScript();
        if (name == null || name.isBlank()) {
            return "";
        }
        java.nio.file.Path venv = store.venvDir(adapter.id());
        boolean win = System.getProperty("os.name", "").toLowerCase().contains("win");
        List<java.nio.file.Path> candidates = new ArrayList<>();
        candidates.add(venv.resolve(win ? "Scripts" : "bin").resolve(name + (win ? ".exe" : "")));
        candidates.add(venv.resolve("Scripts").resolve(name + ".exe"));
        candidates.add(venv.resolve("bin").resolve(name));
        for (java.nio.file.Path p : candidates) {
            if (Files.isRegularFile(p)) {
                return p.toString();
            }
        }
        return "";
    }

    /** 等端口可连接（并确认进程没有提前退出）。 */
    private boolean waitUntilReady(DecisionAdapter adapter, int port) throws InterruptedException {
        long deadline = System.currentTimeMillis() + START_TIMEOUT_SECONDS * 1000L;
        while (System.currentTimeMillis() < deadline) {
            Process p = process;
            if (p == null || !p.isAlive()) {
                return false;
            }
            if (portOpen(port)) {
                probeHealth(adapter, port);
                return true;
            }
            Thread.sleep(POLL_INTERVAL_MS);
        }
        return false;
    }

    /** 尽力探一次健康路径，仅作日志依据（失败不影响就绪判定）。 */
    private void probeHealth(DecisionAdapter adapter, int port) {
        try {
            String path = adapter.protocol().healthPath();
            if (path == null || path.isBlank()) {
                return;
            }
            String base = adapter.localEndpoint(port).baseUrl();
            String url = DecisionEndpoint.trimSlash(base) + (path.startsWith("/") ? path : "/" + path);
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            store.appendLog(adapter.id(), "install.log", "健康检查 " + url + " → HTTP " + resp.statusCode());
        } catch (Exception e) {
            store.appendLog(adapter.id(), "install.log", "健康检查未通过（不阻断）：" + brief(e));
        }
    }

    private String tailServeLog(String adapterId) {
        try {
            java.nio.file.Path f = store.logsDir(adapterId).resolve("serve.log");
            if (!java.nio.file.Files.exists(f)) {
                return "";
            }
            List<String> lines = java.nio.file.Files.readAllLines(f, StandardCharsets.UTF_8);
            int from = Math.max(0, lines.size() - 5);
            return String.join(" | ", lines.subList(from, lines.size()));
        } catch (Exception e) {
            return "";
        }
    }

    private static boolean portInUse(int port) {
        return portOpenOn("127.0.0.1", port);
    }

    private static boolean portOpen(int port) {
        return portOpenOn("127.0.0.1", port);
    }

    private static boolean portOpenOn(String host, int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), 400);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static String brief(Throwable e) {
        String m = e.getMessage();
        return m == null || m.isBlank() ? e.getClass().getSimpleName() : m;
    }

    private static boolean fileExists(String path) {
        return path != null && !path.isBlank() && java.nio.file.Files.exists(java.nio.file.Path.of(path));
    }
}
