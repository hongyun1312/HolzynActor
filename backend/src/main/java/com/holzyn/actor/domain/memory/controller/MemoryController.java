package com.holzyn.actor.domain.memory.controller;

import com.holzyn.actor.common.R;
import com.holzyn.actor.domain.memory.vo.MemoryVO;
import com.holzyn.actor.common.PageResult;
import com.holzyn.actor.domain.memory.service.MemoryService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 长期记忆控制器（P4-1 记忆 API，A-C7 P2）。
 * <p>职责：提供记忆列表（项目级+角色级）、删除（软删）与手动触发抽取接口；
 * 归属以当前登录用户为准（服务层校验）；统一返回 R&lt;T&gt;。</p>
 * <p>所属模块：controller/memory（记忆子域）</p>
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class MemoryController {

    /** 长期记忆服务 */
    private final MemoryService memoryService;

    /**
     * 记忆列表（归属校验）。
     *
     * @param projectId   项目 ID
     * @param characterId 角色 ID（可空：空=项目全部记忆；非空=该角色记忆）
     * @param page        页码（从 1 起，默认 1）
     * @param size        每页条数（默认 20）
     * @return 分页记忆 VO
     */
    @GetMapping("/projects/{projectId}/memories")
    public R<PageResult<MemoryVO>> list(@PathVariable("projectId") Long projectId,
                                        @RequestParam(value = "characterId", required = false) Long characterId,
                                        @RequestParam(value = "page", defaultValue = "1") int page,
                                        @RequestParam(value = "size", defaultValue = "20") int size) {
        return R.ok(memoryService.list(projectId, characterId, page, size));
    }

    /**
     * 删除单条记忆（软删，归属校验）。
     *
     * @param id 记忆主键
     * @return 删除确认
     */
    @DeleteMapping("/memories/{id}")
    public R<Map<String, Object>> delete(@PathVariable("id") Long id) {
        memoryService.delete(id);
        return R.ok(Map.of("id", id, "deleted", true));
    }

    /**
     * 手动触发抽取（调试/补抽）：对指定角色参与的最近会话抽取一次新事实。
     *
     * @param id 角色主键
     * @return 本次新增事实条数
     */
    @PostMapping("/characters/{id}/memories/extract")
    public R<Map<String, Object>> extract(@PathVariable("id") Long id) {
        int added = memoryService.extractForCharacter(id);
        return R.ok(Map.of("characterId", id, "added", added));
    }
}
