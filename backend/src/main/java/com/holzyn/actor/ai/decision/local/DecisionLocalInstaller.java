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
import com.holzyn.actor.ai.decision.adapter.DecisionAdapter;
import com.holzyn.actor.ai.decision.adapter.DecisionAdapters;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 本地决策模型安装器：<b>下载权重 → 拉源码（按需）→ 建虚拟环境装依赖</b>。
 *
 * <p><b>为什么是异步任务而不是同步请求</b>：权重 0.9~4.5GB，同步 HTTP 请求必然超时；
 * 这里用单线程执行器跑安装，前端轮询 {@code status} 拿进度（state.json 落盘，重启可续）。</p>
 *
 * <p><b>下载策略</b>：</p>
 * <ul>
 *   <li>文件清单走 HF Hub 的 {@code /api/models/{repo}/tree/{rev}?recursive=true}（镜像站同构）；</li>
 *   <li>单文件走 {@code /{repo}/resolve/{rev}/{path}}，写 {@code .part} 并用 HTTP Range 续传——
 *       断网/关软件后重来不从头开始；</li>
 *   <li>LFS 文件带 {@code lfs.oid}（sha256），下完校验，不匹配即失败（避免半截权重导致推理乱答）；</li>
 *   <li>镜像站参数化：{@code hf-mirror.com} / {@code huggingface.co} 只差 host，同一套代码。</li>
 * </ul>
 */
@Slf4j
@Component
public class DecisionLocalInstaller {

    /** 下载源：国内镜像 */
    public static final String SOURCE_HF_MIRROR = "hf-mirror";

    /** 下载源：官方站点 */
    public static final String SOURCE_HUGGINGFACE = "huggingface";

    /** 镜像站 host */
    private static final String HOST_MIRROR = "hf-mirror.com";

    /** 官方 host */
    private static final String HOST_HF = "huggingface.co";

    /** 安装日志文件名 */
    private static final String INSTALL_LOG = "install.log";

    /** 进度写盘节流间隔（毫秒） */
    private static final long PROGRESS_FLUSH_MS = 800L;

    /** 单次 HTTP 读超时（秒） */
    private static final int READ_TIMEOUT_SECONDS = 120;

    private final DecisionProperties properties;

    private final DecisionLocalStore store;

    private final DecisionAdapters adapters;

    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "decision-local-install");
        t.setDaemon(true);
        return t;
    });

    private final Map<String, Future<?>> jobs = new ConcurrentHashMap<>();

    private final Set<String> cancelled = ConcurrentHashMap.newKeySet();

    private final HttpClient http = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(30))
            .build();

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
    public DecisionLocalInstaller(DecisionProperties properties,
                                 DecisionLocalStore store,
                                 DecisionAdapters adapters) {
        this.properties = properties;
        this.store = store;
        this.adapters = adapters;
    }

    /**
     * 启动安装（异步）。
     *
     * @param adapterId 适配器 ID
     *
     * @param source 下载源（hf-mirror / huggingface）
     *
     * @return 安装开始时的状态快照
     */
    public DecisionLocalState install(String adapterId, String source) {
        DecisionAdapter adapter = adapters.resolve(adapterId);
        if (!adapter.localDeployable()) {
            throw new IllegalStateException("该模型不支持本地部署：" + adapter.displayName());
        }
        DecisionLocalState existing = store.readState(adapter.id());
        if (existing.busy() || jobs.containsKey(adapter.id())) {
            throw new IllegalStateException("安装任务正在进行中：" + existing.getStatus());
        }
        String src = normalizeSource(source);
        cancelled.remove(adapter.id());

        DecisionLocalState state = new DecisionLocalState();
        state.setAdapterId(adapter.id());
        state.setStatus(DecisionLocalState.STATUS_DOWNLOADING);
        state.setSource(src);
        state.setMessage("准备下载 " + adapter.displayName());
        state.setWeightsDir(store.weightsDir(adapter.id()).toString());
        state.setRepoDir(store.repoDir(adapter.id()).toString());
        state.setVenvDir(store.venvDir(adapter.id()).toString());
        state.setTotalBytes(adapter.approxWeightsBytes());
        store.writeState(state);

        Future<?> future = executor.submit(() -> runInstall(adapter, src));
        jobs.put(adapter.id(), future);
        log.info("[Decision.Local] 安装开始：adapter={} source={} 预计={}MB",
                adapter.id(), src, adapter.approxWeightsBytes() / (1024 * 1024));
        return state.copy();
    }

    /** 取消进行中的安装（下载线程在下一个检查点退出）。 */
    public boolean cancel(String adapterId) {
        DecisionAdapter adapter = adapters.resolve(adapterId);
        Future<?> job = jobs.get(adapter.id());
        if (job == null || job.isDone()) {
            return false;
        }
        cancelled.add(adapter.id());
        job.cancel(true);
        DecisionLocalState state = store.readState(adapter.id());
        state.setStatus(DecisionLocalState.STATUS_INSTALLED.equals(state.getStatus())
                ? state.getStatus() : DecisionLocalState.STATUS_NOT_INSTALLED);
        state.setMessage("已取消安装");
        state.setDownloadedBytes(0);
        state.setPid(0);
        state.setPort(0);
        store.writeState(state);
        log.info("[Decision.Local] 安装已取消：adapter={}", adapter.id());
        return true;
    }

    /** 卸载：删除该适配器全部文件（调用方需先停进程）。 */
    public void uninstall(String adapterId) {
        DecisionAdapter adapter = adapters.resolve(adapterId);
        cancel(adapter.id());
        store.deleteRecursively(store.adapterDir(adapter.id()).toFile().toPath());
        log.info("[Decision.Local] 已卸载：adapter={}", adapter.id());
    }

    /** 是否安装任务进行中。 */
    public boolean installing(String adapterId) {
        Future<?> job = jobs.get(adapterId);
        return job != null && !job.isDone();
    }

    /** 归一化下载源（未识别一律按国内镜像）。 */
    static String normalizeSource(String source) {
        String s = source == null ? "" : source.trim().toLowerCase(Locale.ROOT);
        return SOURCE_HUGGINGFACE.equals(s) ? SOURCE_HUGGINGFACE : SOURCE_HF_MIRROR;
    }

    private String hostOf(String source) {
        return SOURCE_HUGGINGFACE.equals(source) ? HOST_HF : HOST_MIRROR;
    }

    // ------------------------------------------------------------------
    // 安装主流程
    // ------------------------------------------------------------------

    private void runInstall(DecisionAdapter adapter, String source) {
        try {
            Path weights = store.weightsDir(adapter.id());
            downloadWeights(adapter, source, weights);
            if ("repo-script".equalsIgnoreCase(adapter.runtimeKind())) {
                fetchSourceRepo(adapter, source, store.repoDir(adapter.id()));
            }
            provision(adapter);
            DecisionLocalState done = store.readState(adapter.id());
            done.setStatus(DecisionLocalState.STATUS_INSTALLED);
            done.setMessage("安装完成，可启动");
            done.setLastError("");
            done.setInstalledAt(Instant.now().toString());
            done.setTotalBytes(Math.max(done.getTotalBytes(), done.getDownloadedBytes()));
            done.setPythonExe(store.venvPython(adapter.id()).toString());
            store.writeState(done);
            store.appendLog(adapter.id(), INSTALL_LOG, "安装完成 dir=" + store.adapterDir(adapter.id()));
            log.info("[Decision.Local] 安装完成：adapter={} dir={}", adapter.id(), store.adapterDir(adapter.id()));
        } catch (Exception e) {
            if (cancelled.contains(adapter.id())) {
                log.info("[Decision.Local] 安装被取消：adapter={}", adapter.id());
                return;
            }
            DecisionLocalState failed = store.readState(adapter.id());
            failed.setStatus(DecisionLocalState.STATUS_ERROR);
            failed.setMessage("安装失败");
            failed.setLastError(brief(e));
            store.writeState(failed);
            store.appendLog(adapter.id(), INSTALL_LOG, "安装失败：" + e);
            log.warn("[Decision.Local] 安装失败：adapter={} 原因={}", adapter.id(), e.toString());
        } finally {
            jobs.remove(adapter.id());
            cancelled.remove(adapter.id());
        }
    }

    /** 下载权重文件（清单来自 HF tree API）。 */
    private void downloadWeights(DecisionAdapter adapter, String source, Path targetDir) throws Exception {
        String host = hostOf(source);
        List<RemoteFile> files = listFiles(host, adapter.weightsRepo(), adapter.weightsRevision());
        List<RemoteFile> wanted = filterWeights(adapter, files);
        if (wanted.isEmpty()) {
            throw new IOException("仓库未返回可下载的权重文件：" + adapter.weightsRepo());
        }
        long total = wanted.stream().mapToLong(f -> Math.max(f.size, 0)).sum();

        DecisionLocalState state = store.readState(adapter.id());
        state.setStatus(DecisionLocalState.STATUS_DOWNLOADING);
        state.setTotalBytes(total);
        state.setDownloadedBytes(0);
        state.setMessage("正在下载权重（" + wanted.size() + " 个文件）");
        store.writeState(state);
        store.appendLog(adapter.id(), INSTALL_LOG,
                "下载源=" + host + " 文件数=" + wanted.size() + " 总量=" + total + "B");

        long done = 0;
        long lastFlush = 0;
        for (RemoteFile f : wanted) {
            throwIfCancelled(adapter.id());
            Path dest = targetDir.resolve(f.path).normalize();
            if (!dest.startsWith(targetDir)) {
                throw new IOException("非法文件路径：" + f.path);
            }
            Files.createDirectories(dest.getParent());
            if (Files.exists(dest) && f.size > 0 && Files.size(dest) == f.size) {
                done += f.size;
                continue;
            }
            downloadOne(host, adapter, f, dest);
            if (f.sha256 != null && !f.sha256.isBlank()) {
                DecisionLocalState verifying = store.readState(adapter.id());
                verifying.setStatus(DecisionLocalState.STATUS_VERIFYING);
                verifying.setMessage("校验 " + f.path);
                store.writeState(verifying);
                String actual = sha256(dest);
                if (!actual.equalsIgnoreCase(f.sha256)) {
                    Files.deleteIfExists(dest);
                    throw new IOException("文件校验失败（sha256 不匹配）：" + f.path);
                }
                DecisionLocalState back = store.readState(adapter.id());
                back.setStatus(DecisionLocalState.STATUS_DOWNLOADING);
                back.setMessage("正在下载权重");
                store.writeState(back);
            }
            done += Math.max(f.size, Files.size(dest));
            long now = System.currentTimeMillis();
            if (now - lastFlush >= PROGRESS_FLUSH_MS) {
                DecisionLocalState s = store.readState(adapter.id());
                s.setDownloadedBytes(done);
                s.setStatus(DecisionLocalState.STATUS_DOWNLOADING);
                s.setMessage("正在下载权重（" + percent(done, total) + "%）");
                store.writeState(s);
                lastFlush = now;
            }
        }
        DecisionLocalState s = store.readState(adapter.id());
        s.setDownloadedBytes(Math.max(done, 0));
        s.setTotalBytes(Math.max(total, done));
        store.writeState(s);
    }

    /** 按适配器声明的 glob 过滤清单（无匹配时回退为全部文件）。 */
    private List<RemoteFile> filterWeights(DecisionAdapter adapter, List<RemoteFile> files) {
        List<String> patterns = adapter.weightFilePatterns();
        if (patterns == null || patterns.isEmpty()) {
            return files;
        }
        List<RemoteFile> matched = new ArrayList<>();
        for (RemoteFile f : files) {
            for (String p : patterns) {
                if (globMatch(f.path, p)) {
                    matched.add(f);
                    break;
                }
            }
        }
        return matched.isEmpty() ? files : matched;
    }

    /** 极简 glob（{@code *} 与 {@code **} 都按「任意字符」处理，足够匹配 HF 清单）。 */
    static boolean globMatch(String path, String pattern) {
        StringBuilder regex = new StringBuilder();
        for (char c : pattern.toCharArray()) {
            switch (c) {
                case '*' -> regex.append(".*");
                case '?' -> regex.append('.');
                case '.', '(', ')', '+', '|', '^', '$', '@', '%', '[', ']', '{', '}', '\\' -> regex.append('\\').append(c);
                default -> regex.append(c);
            }
        }
        return path.matches(regex.toString());
    }

    /** 拉取清单：{@code GET https://{host}/api/models/{repo}/tree/{rev}?recursive=true}。 */
    private List<RemoteFile> listFiles(String host, String repo, String revision) throws Exception {
        String url = "https://" + host + "/api/models/" + repo + "/tree/" + revision + "?recursive=true";
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(60))
                .header("Accept", "application/json")
                .header("User-Agent", "HolzynActor/1.0")
                .GET()
                .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (resp.statusCode() / 100 != 2) {
            throw new IOException("拉取文件清单失败：HTTP " + resp.statusCode() + " url=" + url);
        }
        List<RemoteFile> out = new ArrayList<>();
        Object parsed = Json.MAPPER.readValue(resp.body(), Object.class);
        if (!(parsed instanceof List<?> list)) {
            throw new IOException("文件清单格式非法：" + url);
        }
        for (Object o : list) {
            if (!(o instanceof Map<?, ?> m)) {
                continue;
            }
            if (!"file".equals(String.valueOf(m.get("type")))) {
                continue;
            }
            RemoteFile f = new RemoteFile();
            f.path = String.valueOf(m.get("path"));
            f.size = toLong(m.get("size"));
            if (m.get("lfs") instanceof Map<?, ?> lfs) {
                f.sha256 = lfs.get("oid") == null ? null : String.valueOf(lfs.get("oid"));
                long lfsSize = toLong(lfs.get("size"));
                if (lfsSize > 0) {
                    f.size = lfsSize;
                }
            }
            if (f.path == null || f.path.isBlank()) {
                continue;
            }
            out.add(f);
        }
        out.sort(Comparator.comparing(f -> f.path));
        return out;
    }

    /** 单文件下载：写 {@code .part}，有残留则 Range 续传。 */
    private void downloadOne(String host, DecisionAdapter adapter, RemoteFile file, Path dest) throws Exception {
        String encodedPath = encodePath(file.path);
        String url = "https://" + host + "/" + adapter.weightsRepo() + "/resolve/"
                + adapter.weightsRevision() + "/" + encodedPath;
        Path part = dest.resolveSibling(dest.getFileName() + ".part");
        long existing = Files.exists(part) ? Files.size(part) : 0;
        if (existing > 0 && file.size > 0 && existing >= file.size) {
            Files.move(part, dest, StandardCopyOption.REPLACE_EXISTING);
            return;
        }
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(READ_TIMEOUT_SECONDS))
                .header("User-Agent", "HolzynActor/1.0")
                .GET();
        if (existing > 0) {
            b.header("Range", "bytes=" + existing + "-");
        }
        HttpResponse<InputStream> resp = http.send(b.build(), HttpResponse.BodyHandlers.ofInputStream());
        int code = resp.statusCode();
        boolean append = existing > 0 && code == 206;
        if (code == 416 && existing >= file.size && file.size > 0) {
            Files.move(part, dest, StandardCopyOption.REPLACE_EXISTING);
            return;
        }
        if (code / 100 != 2) {
            try (InputStream in = resp.body()) {
                in.readAllBytes();
            }
            throw new IOException("下载失败：HTTP " + code + " path=" + file.path + " url=" + url);
        }
        if (!append) {
            existing = 0;
        }
        store.appendLog(adapter.id(), INSTALL_LOG,
                "下载 " + file.path + "（" + (append ? "续传 from " + existing : "从头") + "，"
                        + Math.max(file.size, 0) + "B）");
        try (InputStream in = resp.body();
             OutputStream out = Files.newOutputStream(part, append
                     ? new java.nio.file.OpenOption[]{java.nio.file.StandardOpenOption.CREATE,
                        java.nio.file.StandardOpenOption.APPEND}
                     : new java.nio.file.OpenOption[]{java.nio.file.StandardOpenOption.CREATE,
                        java.nio.file.StandardOpenOption.TRUNCATE_EXISTING})) {
            byte[] buf = new byte[256 * 1024];
            int n;
            long flushed = 0;
            long last = System.currentTimeMillis();
            while ((n = in.read(buf)) > 0) {
                throwIfCancelled(adapter.id());
                out.write(buf, 0, n);
                flushed += n;
                long now = System.currentTimeMillis();
                if (now - last >= PROGRESS_FLUSH_MS) {
                    DecisionLocalState s = store.readState(adapter.id());
                    s.setDownloadedBytes(s.getDownloadedBytes() + flushed);
                    s.setMessage("正在下载权重（" + percent(s.getDownloadedBytes(), s.getTotalBytes()) + "%）");
                    store.writeState(s);
                    flushed = 0;
                    last = now;
                }
            }
            out.flush();
            if (flushed > 0) {
                DecisionLocalState s = store.readState(adapter.id());
                s.setDownloadedBytes(s.getDownloadedBytes() + flushed);
                s.setMessage("正在下载权重（" + percent(s.getDownloadedBytes(), s.getTotalBytes()) + "%）");
                store.writeState(s);
            }
        }
        Files.move(part, dest, StandardCopyOption.REPLACE_EXISTING);
    }

    /**
     * 拉官方源码（repo-script 形态必需：服务脚本与 requirements 不在权重仓库里）。
     * <p>用 GitHub zipball 而不是 git clone：桌面端不保证有 git。</p>
     */
    private void fetchSourceRepo(DecisionAdapter adapter, String source, Path repoDir) throws Exception {
        String repo = adapter.sourceRepo();
        if (repo == null || repo.isBlank()) {
            return;
        }
        if (Files.exists(repoDir.resolve(".ready"))) {
            return;
        }
        String url = "https://github.com/" + repo + "/archive/refs/heads/" + adapter.sourceRevision() + ".zip";
        DecisionLocalState state = store.readState(adapter.id());
        state.setMessage("正在获取源码 " + repo);
        store.writeState(state);
        store.appendLog(adapter.id(), INSTALL_LOG, "获取源码 " + url);
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(READ_TIMEOUT_SECONDS))
                .header("User-Agent", "HolzynActor/1.0")
                .GET()
                .build();
        HttpResponse<InputStream> resp = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
        if (resp.statusCode() / 100 != 2) {
            try (InputStream in = resp.body()) {
                in.readAllBytes();
            }
            throw new IOException("获取源码失败：HTTP " + resp.statusCode() + " " + url);
        }
        Path zip = Files.createTempFile("decision-src-", ".zip");
        try (InputStream in = resp.body()) {
            Files.copy(in, zip, StandardCopyOption.REPLACE_EXISTING);
        }
        try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(zip))) {
            ZipEntry e;
            while ((e = zis.getNextEntry()) != null) {
                throwIfCancelled(adapter.id());
                String name = e.getName().replace('\\', '/');
                int slash = name.indexOf('/');
                if (slash < 0) {
                    continue;
                }
                String rel = name.substring(slash + 1);
                if (rel.isEmpty()) {
                    continue;
                }
                Path dest = repoDir.resolve(rel).normalize();
                if (!dest.startsWith(repoDir)) {
                    continue;
                }
                if (e.isDirectory()) {
                    Files.createDirectories(dest);
                } else {
                    Files.createDirectories(dest.getParent());
                    Files.copy(zis, dest, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        } finally {
            Files.deleteIfExists(zip);
        }
        Files.writeString(repoDir.resolve(".ready"), Instant.now().toString(), StandardCharsets.UTF_8);
    }

    /**
     * 建虚拟环境并安装依赖。
     * <p>不内置便携 Python 时用系统 Python 建 venv；两者产物一致，差别只在解释器来源。</p>
     */
    private void provision(DecisionAdapter adapter) throws Exception {
        DecisionLocalState state = store.readState(adapter.id());
        state.setStatus(DecisionLocalState.STATUS_PROVISIONING);
        state.setMessage("正在准备 Python 环境");
        store.writeState(state);

        String python = findSystemPython(adapter.id());
        Path venv = store.venvDir(adapter.id());
        Path venvPython = venv.resolve("Scripts").resolve("python.exe");
        if (!Files.exists(venvPython)) {
            venvPython = venv.resolve("bin").resolve("python3");
        }
        if (!Files.exists(venvPython)) {
            run(List.of(python, "-m", "venv", venv.toString()), adapter.id(), "创建虚拟环境");
        }
        venvPython = store.venvPython(adapter.id());
        if (!Files.exists(venvPython)) {
            throw new IOException("虚拟环境创建失败：" + venv);
        }
        DecisionLocalState s2 = store.readState(adapter.id());
        s2.setPythonExe(venvPython.toString());
        s2.setMessage("正在升级 pip");
        store.writeState(s2);
        try {
            run(List.of(venvPython.toString(), "-m", "pip", "install", "--upgrade", "pip", "--quiet"),
                    adapter.id(), "升级 pip");
        } catch (Exception e) {
            store.appendLog(adapter.id(), INSTALL_LOG, "pip 升级失败（继续）：" + brief(e));
        }

        List<String> cmd = new ArrayList<>();
        cmd.add(venvPython.toString());
        cmd.add("-m");
        cmd.add("pip");
        cmd.add("install");
        cmd.add("--disable-pip-version-check");
        Path requirement = store.repoDir(adapter.id()).resolve("requirements.txt");
        if ("repo-script".equalsIgnoreCase(adapter.runtimeKind()) && Files.exists(requirement)) {
            cmd.add("-r");
            cmd.add(requirement.toString());
        } else {
            cmd.addAll(adapter.pipPackages());
        }
        DecisionLocalState s3 = store.readState(adapter.id());
        s3.setMessage("正在安装依赖（首次较慢，请耐心等待）");
        store.writeState(s3);
        run(cmd, adapter.id(), "安装 Python 依赖");
    }

    /** 找一个可用的系统 Python（3.9+）。 */
    private String findSystemPython(String adapterId) throws IOException {
        String wanted = properties.getLocal() == null ? "3.12" : properties.getLocal().getPythonVersion();
        List<List<String>> candidates = new ArrayList<>();
        if (wanted != null && wanted.matches("\\d+\\.\\d+")) {
            candidates.add(List.of("python" + wanted));
            candidates.add(List.of("py", "-" + wanted));
        }
        candidates.add(List.of("python3"));
        candidates.add(List.of("python"));
        candidates.add(List.of("py"));
        for (List<String> c : candidates) {
            try {
                String out = runCapture(concat(c, List.of("--version")));
                if (out.toLowerCase(Locale.ROOT).contains("python 3")) {
                    store.appendLog(adapterId, INSTALL_LOG, "使用 Python：" + String.join(" ", c) + " → " + out.trim());
                    return String.join(" ", c).trim();
                }
            } catch (Exception ignored) {
                // 试下一个候选
            }
        }
        throw new IOException("未找到 Python 3（请先安装 Python 3.10+，或改用 API 形态）");
    }

    /** 执行命令并等待结束（输出写安装日志）。 */
    private void run(List<String> cmd, String adapterId, String what) throws Exception {
        store.appendLog(adapterId, INSTALL_LOG, what + "： " + String.join(" ", cmd));
        ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true);
        Path logFile = store.logsDir(adapterId).resolve(INSTALL_LOG);
        pb.redirectOutput(ProcessBuilder.Redirect.appendTo(logFile.toFile()));
        pb.redirectErrorStream(true);
        Process p = pb.start();
        int code = p.waitFor();
        if (code != 0) {
            throw new IOException(what + "失败（退出码 " + code + "，详见 logs/" + INSTALL_LOG + "）");
        }
    }

    private String runCapture(List<String> cmd) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true);
        Process p = pb.start();
        String out;
        try (InputStream in = p.getInputStream()) {
            out = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        p.waitFor();
        return out;
    }

    private void throwIfCancelled(String adapterId) throws InterruptedException {
        if (cancelled.contains(adapterId)) {
            throw new InterruptedException("install cancelled: " + adapterId);
        }
    }

    private static String percent(long done, long total) {
        if (total <= 0) {
            return "?";
        }
        return String.valueOf(Math.min(100, done * 100 / total));
    }

    private static List<String> concat(List<String> a, List<String> b) {
        List<String> out = new ArrayList<>(a);
        out.addAll(b);
        return out;
    }

    private static String encodePath(String path) {
        StringBuilder sb = new StringBuilder();
        for (String seg : path.split("/")) {
            if (sb.length() > 0) {
                sb.append('/');
            }
            sb.append(URLEncoder.encode(seg, StandardCharsets.UTF_8).replace("+", "%20"));
        }
        return sb.toString();
    }

    private static String sha256(Path file) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buf = new byte[1024 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                md.update(buf, 0, n);
            }
        }
        return HexFormat.of().formatHex(md.digest());
    }

    private static long toLong(Object o) {
        if (o instanceof Number n) {
            return n.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(o));
        } catch (Exception e) {
            return 0;
        }
    }

    private static String brief(Throwable e) {
        String m = e.getMessage();
        return m == null || m.isBlank() ? e.getClass().getSimpleName() : m;
    }

    /** 远端文件条目。 */
    private static final class RemoteFile {
        String path;
        long size;
        String sha256;
    }

    /** 安装器内部 JSON 解析器（只读清单，宽松）。 */
    private static final class Json {
        static final tools.jackson.databind.ObjectMapper MAPPER = new tools.jackson.databind.ObjectMapper();

        private Json() {
        }
    }
}
