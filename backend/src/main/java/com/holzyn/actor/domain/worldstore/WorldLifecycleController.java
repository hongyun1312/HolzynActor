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

package com.holzyn.actor.domain.worldstore;

import com.holzyn.actor.common.BizException;
import com.holzyn.actor.common.R;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 存档生命周期 API（V1.1.2-beta.1，唯一入口）。
 *
 * <p>类级 {@link GlobalContext}：这些接口的语义永远是"对着目录库"，与当前挂载哪个世界无关。
 * 不标的话，一旦有世界挂载，{@code GET /api/worlds} 会去读世界库的 {@code actor_project}
 * ——那里只有自己那一行，列表就"少了一个世界"，而用户会以为是存档丢了。</p>
 *
 * <p>为什么只有读 + 生命周期动作，没有业务接口：世界内业务（时钟/角色/记忆…）挂在各自的
 * {@code /api/worlds/{id}/…} 下，走世界库；本类只管"存档这个对象本身"。</p>
 *
 * <p>导入 / 导出（M6）也在本类：它们与列表/打开/关闭一样是"对着存档"的动作，
 * 拆到第二个控制器就会出现"两个入口都能改索引"的局面（AGENTS §8.1.3）。真正的实现
 * （zip 打包、脱敏、uid 冲突改写）在 {@link WorldArchiveService}。</p>
 *
 * <p>所属模块：domain/worldstore（世界存档存储）</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/worlds")
@GlobalContext
@RequiredArgsConstructor
public class WorldLifecycleController {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final WorldRegistryService registry;

    private final WorldLifecycleService lifecycle;

    private final WorldPaths paths;

    /**
     * 导入/导出实现（M6）。
     *
     * <p>字段注入而非进构造器：本类是既有控制器，构造器签名不动（AGENTS §8.1.2）；
     * {@code required=false} 让只读单测不必装配整个归档服务。</p>
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private WorldArchiveService archive;

    /**
     * 当前用户（新建世界时写入目录库 {@code actor_project.user_id}）。
     *
     * <p>字段注入而非进构造器：本类是既有控制器，构造器签名不动（AGENTS §8.1.2）；
     * {@code required=false} 让只读单测（list/open/close）不必装配账户体系。</p>
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.holzyn.actor.domain.account.CurrentUserProvider currentUserProvider;

    /**
     * 世界初始化状态（新建/挂载/卸载时刷新目录库完成度快照）。
     *
     * <p>为什么在这里刷：画廊卡片读的是<b>目录库</b>快照，而完成度只能在
     * <b>世界库挂载时</b>算出来（要看地点/角色/关系/时钟表）。世界生命周期是
     * 唯一"挂载状态发生变化的时刻"，在这里刷一次，画廊在不打开任何世界时也能
     * 显示正确的「已初始化 / 未初始化 / 降级」角标。</p>
     *
     * <p>字段注入（构造器签名不动，AGENTS §8.1.2）；{@code required=false} 让
     * 只读单测不必装配整个世界库。</p>
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.holzyn.actor.domain.project.service.WorldInitStatusService initStatusService;

    /**
     * 存档列表（不挂载任何世界库）。
     *
     * @return 卡片列表（无存档返回空数组，不返回 null）
     */
    @GetMapping
    public R<List<WorldSummaryVO>> list() {
        return R.ok(registry.list());
    }

    /**
     * 当前挂载的世界。
     *
     * @return 卡片；未挂载时 {@code data = null}（前端据此判断是否跳存档列表）
     */
    @GetMapping("/current")
    public R<WorldSummaryVO> current() {
        return R.ok(registry.current().orElse(null));
    }

    /**
     * 存档详情。
     *
     * @param id 目录库主键
     *
     * @return 卡片
     */
    @GetMapping("/{id}")
    public R<WorldSummaryVO> detail(@PathVariable("id") long id) {
        return R.ok(registry.requireById(id));
    }

    /**
     * 打开（挂载）世界。
     *
     * @param id 目录库主键
     *
     * @return 打开结果（含耗时与镜像刷新统计）
     */
    @PostMapping("/{id}/open")
    public R<Map<String, Object>> open(@PathVariable("id") long id) {
        WorldLifecycleService.OpenOutcome outcome = lifecycle.open(id);
        // 注意：这里**不能**刷完成度快照。open 返回时本次请求没有绑定世界上下文，
        // 实时校验读到的还是目录库（世界表不存在），写下去会把刚打开的世界误置为 NOT_STARTED。
        // 快照由世界上下文内的「读状态」接口（/init-status）与单步执行自动校正。
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("worldId", outcome.worldId());
        data.put("worldUid", outcome.worldUid());
        data.put("newlyOpened", outcome.newlyOpened());
        data.put("mirrorTables", outcome.mirrorTables());
        data.put("mirrorRows", outcome.mirrorRows());
        data.put("elapsedMs", outcome.elapsedMs());
        return R.ok(data);
    }

    /**
     * 关闭（卸载）世界。
     *
     * @param id 目录库主键
     *
     * @return 关闭结果（含耗时、摘要与锁文件残留）
     */
    @PostMapping("/{id}/close")
    public R<Map<String, Object>> close(@PathVariable("id") long id) {
        // 不在关闭口刷快照：与 open 同理，controller 层不保证手上有世界上下文；
        // 世界运行期间每次读状态/执行步骤都会把快照写准，关闭不再重复这一步。
        WorldLifecycleService.CloseOutcome outcome = lifecycle.close(id);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("worldId", outcome.worldId());
        data.put("worldUid", outcome.worldUid());
        data.put("elapsedMs", outcome.elapsedMs());
        data.put("gameSecond", outcome.gameSecond());
        data.put("characterCount", outcome.characterCount());
        data.put("sizeBytes", outcome.sizeBytes());
        data.put("lockResidual", outcome.lockResidual());
        return R.ok(data);
    }

    /**
     * 读取世界清单（存档自描述，排查"这个存档是哪个版本建的"时直接看）。
     *
     * @param id 目录库主键
     *
     * @return 清单 JSON；文件缺失抛 404
     */
    @GetMapping("/{id}/manifest")
    public R<JsonNode> manifest(@PathVariable("id") long id) {
        WorldSummaryVO summary = registry.requireById(id);
        Path file = paths.manifestFile(summary.worldUid());
        if (!Files.isRegularFile(file)) {
            throw new BizException(404, "世界清单不存在：" + file);
        }
        try {
            JsonNode node = MAPPER.readTree(Files.readString(file, StandardCharsets.UTF_8));
            return R.ok(node);
        } catch (IOException | RuntimeException e) {
            throw new BizException(409, "世界清单不可解析：" + file + "（" + e.getMessage() + "）");
        }
    }

    /**
     * 导出存档（{@code .holzyn} v3 = 世界目录 zip，边打边下载）。
     *
     * <p>为什么是流式而不是"先落地再下载"：世界目录里有几 GB 的地图图块时，先写临时文件等于
     * 把磁盘占用翻倍；流式只多一份被脱敏的库副本（导出期间存在，结束即删）。代价是失败时
     * 客户端会收到一个不完整的 zip——所以准入校验（未挂载、目录/库存在）放在开流之前。</p>
     *
     * @param id 目录库主键
     *
     * @return 附件下载响应（{@code Content-Disposition: attachment}）
     */
    @GetMapping("/{id}/export")
    public ResponseEntity<StreamingResponseBody> export(@PathVariable("id") long id) {
        WorldArchiveService service = requireArchive();
        String fileName = service.fileName(id);
        String encoded;
        try {
            encoded = URLEncoder.encode(fileName, StandardCharsets.UTF_8).replace("+", "%20");
        } catch (RuntimeException e) {
            encoded = "world-" + id + WorldArchiveService.PACKAGE_SUFFIX;
        }
        StreamingResponseBody body = out -> service.export(id, out);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + encoded)
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(body);
    }

    /**
     * 导入存档包（{@code .holzyn} v3；multipart：file）。
     *
     * <p>导入 = 新世界：uid 冲突时自动换新 uid，世界库里的 {@code project_id} 全部改写成本机新 id，
     * 已有存档不受影响。返回新世界 id，前端据此刷新存档列表（不自动打开——用户可能只想先看看）。</p>
     *
     * @param file 存档包
     *
     * @return 导入结果（worldId / worldUid / name / uidRewritten / 世界时间 / 角色数）
     */
    @PostMapping("/import")
    public R<WorldArchiveService.ImportResult> importPackage(@RequestParam("file") MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BizException(400, "请选择要导入的 .holzyn 文件");
        }
        WorldArchiveService service = requireArchive();
        Long userId = currentUserProvider == null ? 1L : currentUserProvider.currentUserId();
        try (InputStream in = file.getInputStream()) {
            WorldArchiveService.ImportResult result =
                    service.importPackage(in, file.getOriginalFilename(), userId);
            return R.ok(result);
        } catch (BizException e) {
            throw e;
        } catch (IOException e) {
            throw new BizException(400, "读取上传的存档包失败：" + e.getMessage());
        }
    }

    /**
     * 刷新目录库的世界初始化完成度快照（画廊角标的数据来源）。
     *
     * <p>失败一律吞掉只告警：快照是展示缓存，写不进去不能让"打开世界"失败。</p>
     *
     * @param worldId 世界/项目 ID（两者同值）
     */
    private void refreshInitSnapshot(long worldId) {
        if (initStatusService == null) {
            return;
        }
        try {
            initStatusService.refreshRegistry(worldId);
        } catch (Exception e) {
            log.warn("[世界初始化快照] 刷新失败（画廊角标可能为旧值）: 世界={} : {}", worldId, e.getMessage());
        }
    }

    /** 归档服务必存在（只读单测不装配它；真正调导入/导出时它就是 Spring 注入的那个） */    private WorldArchiveService requireArchive() {
        if (archive == null) {
            throw new BizException(503, "存档归档服务未就绪（WorldArchiveService 未注入）");
        }
        return archive;
    }

    /**
     * 新建世界（建目录 + 清单 + 目录库索引行；无世界挂载时顺手自动挂载）。
     *
     * <p>自动挂载的语义：存档列表页"新建世界"之后要立刻进世界初始化，那一串接口全是世界内接口；
     * 若这里不挂载，前端就得自己再发一次 {@code /open}——多一次往返，也多一处"忘了 open 就 409"的坑。</p>
     *
     * <p>已经有世界挂载时**不**自动挂载（不替用户关掉当前世界，那会丢它未保存的状态）；
     * 返回 {@code opened=false}，前端据此提示"先返回存档列表再进新世界"。</p>
     *
     * @param body 请求体：{@code {"name": "世界名"}}（可空，空则"未命名世界"）
     *
     * @return 新建结果（worldId / worldUid / opened / open 详情）
     */
    @PostMapping
    public R<Map<String, Object>> create(@RequestBody(required = false) Map<String, Object> body) {
        Object rawName = body == null ? null : body.get("name");
        String name = rawName == null ? null : String.valueOf(rawName);
        Long userId = currentUserProvider == null ? 1L : currentUserProvider.currentUserId();
        WorldLifecycleService.CreateOutcome created = lifecycle.create(name, userId);
        // 概要与封面：索引里已有列，但创建时一次给全比"建完再 PUT 一次"少一次往返
        String summary = text(body, "summary");
        String coverUrl = text(body, "coverUrl");
        if (coverUrl == null) {
            coverUrl = text(body, "cover_url");
        }
        if (summary != null || coverUrl != null) {
            lifecycle.updateMeta(created.worldId(), null, summary, coverUrl);
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("worldId", created.worldId());
        data.put("worldUid", created.worldUid());
        data.put("name", created.name());
        // id 是 worldId 的兼容别名：新建世界页（ManualAddPanel / WorldgenPanel）沿用
        // "POST 回来就是一个项目对象、读 p.id" 的老写法，这里不改前端也能继续工作
        data.put("id", created.worldId());
        data.put("elapsedMs", created.elapsedMs());
        boolean opened = false;
        if (registry.current().isEmpty()) {
            WorldLifecycleService.OpenOutcome outcome = lifecycle.open(created.worldId());
            opened = true;
            Map<String, Object> open = new LinkedHashMap<>();
            open.put("newlyOpened", outcome.newlyOpened());
            open.put("mirrorTables", outcome.mirrorTables());
            open.put("mirrorRows", outcome.mirrorRows());
            open.put("elapsedMs", outcome.elapsedMs());
            data.put("open", open);
        }
        data.put("opened", opened);
        // 新世界：完成度快照先落一个"未初始化"（挂载成功则算得更准，失败也无妨）
        refreshInitSnapshot(created.worldId());
        return R.ok(data);
    }

    /**
     * 改名 / 封面 / 概要（只写目录库）。
     *
     * @param id   目录库主键
     * @param body 请求体：{@code {"name": "...", "summary": "...", "coverUrl": "..."}}（都可空）
     *
     * @return 更新后的存档卡片
     */
    @PutMapping("/{id}")
    public R<WorldSummaryVO> updateMeta(@PathVariable("id") long id,
                                        @RequestBody(required = false) Map<String, Object> body) {
        String name = text(body, "name");
        String summary = text(body, "summary");
        String coverUrl = text(body, "coverUrl");
        if (coverUrl == null) {
            coverUrl = text(body, "cover_url");
        }
        return R.ok(lifecycle.updateMeta(id, name, summary, coverUrl));
    }

    /**
     * 上传 / 替换世界封面（multipart：file；PNG / JPG，≤8MB）。
     *
     * <p>封面文件落在世界目录的上传区（{@code worlds/<uid>/uploads/cover.png|jpg}），
     * 而 .holzyn 导出/导入会整目录搬运 {@code uploads/}，所以封面随包往返；
     * 索引里只存相对路径，导入重编号不会指错世界。</p>
     *
     * @param id   目录库主键
     * @param file 封面图片
     *
     * @return 更新后的存档卡片（{@code coverUrl} = 相对文件名）
     */
    @PostMapping("/{id}/cover")
    public R<WorldSummaryVO> uploadCover(@PathVariable("id") long id,
                                         @RequestParam("file") MultipartFile file) {
        return R.ok(lifecycle.uploadCover(id, file));
    }

    /**
     * 读取世界封面（原样返回图片字节，供画廊卡片 / 详情页直接作为 &lt;img src&gt;）。
     *
     * <p>{@code Cache-Control: no-store}：封面会被"更换封面"原地替换成同名文件，
     * 允许缓存就会出现"换了封面但卡片还是旧图"，而这张图通常只有几十 KB，不缓存代价可接受。</p>
     *
     * @param id 目录库主键
     *
     * @return 图片响应；没有封面时 404（前端据此回落到预备背面）
     */
    @GetMapping("/{id}/cover")
    public ResponseEntity<Resource> cover(@PathVariable("id") long id) {
        WorldSummaryVO summary = registry.requireById(id);
        Path file = lifecycle.coverFile(summary.worldUid());
        if (file == null) {
            throw new BizException(404, "这个世界还没有封面");
        }
        String name = file.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
        MediaType type = name.endsWith(".png") ? MediaType.IMAGE_PNG : MediaType.IMAGE_JPEG;
        return ResponseEntity.ok()
                .contentType(type)
                .cacheControl(CacheControl.noStore())
                .body(new FileSystemResource(file));
    }

    /**
     * 移除世界封面（删文件 + 清空索引，卡片回落到预备背面）。
     *
     * @param id 目录库主键
     *
     * @return 更新后的存档卡片（{@code coverUrl} = null）
     */
    @DeleteMapping("/{id}/cover")
    public R<WorldSummaryVO> removeCover(@PathVariable("id") long id) {
        return R.ok(lifecycle.removeCover(id));
    }

    /** 取请求体里的字符串字段（空串视作"不改"） */
    private static String text(Map<String, Object> body, String key) {
        if (body == null) {
            return null;
        }
        Object value = body.get(key);
        if (value == null) {
            return null;
        }
        String s = String.valueOf(value).trim();
        return s.isEmpty() ? null : s;
    }

    /**
     * 归档删除世界（世界目录整体移进 {@code archive/deleted-worlds/}，索引行软删）。
     *
     * @param id 目录库主键
     *
     * @return 归档结果（archivedTo 为相对数据目录的路径）
     */
    @DeleteMapping("/{id}")
    public R<Map<String, Object>> delete(@PathVariable("id") long id) {
        WorldLifecycleService.DeleteOutcome outcome = lifecycle.delete(id);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("worldId", outcome.worldId());
        data.put("worldUid", outcome.worldUid());
        data.put("archivedTo", outcome.archivedTo());
        data.put("elapsedMs", outcome.elapsedMs());
        return R.ok(data);
    }
}
