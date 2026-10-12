package com.holzyn.actor.domain.worldgen.service;

import com.holzyn.actor.common.BizException;
import com.holzyn.actor.domain.worldgen.model.WorkflowDefinition;
import com.holzyn.actor.domain.worldgen.model.WorkflowNode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * WorldGenDefinitionParser（M1 引擎骨架：工作流定义解析）单元测试。
 * <p>职责：验证——节点/边/变量三段 JSON 解析、拓扑排序执行顺序、占位符引用提取
 * （{{前序维度输出变量}}）、非法定义（重复节点 ID/非法节点类型/悬空边/环）被 400 拒绝。</p>
 * <p>说明：不依赖数据库（纯解析逻辑）。</p>
 */
class WorldGenDefinitionParserTest {

    private final WorldGenDefinitionParser parser = new WorldGenDefinitionParser();

    /** 构造 3 节点线性链定义（agent→script→agent，含上游输出引用） */
    private String[] linearDefinition() {
        String nodes = """
                [
                  {"id":"agent_a","label":"维度A","nodeType":"agent","agentType":"wb-a",
                   "firstMessage":"【创意】{{premise}}\\n【前序】{{agent_b_output}}","outputVariable":"agent_a_output"},
                  {"id":"script_x","label":"脚本X","nodeType":"script","script_name":"render","script_args":"--in world/a.json"},
                  {"id":"agent_b","label":"维度B","nodeType":"agent","agentType":"wb-b",
                   "firstMessage":"【前序】{{agent_a_output}}","outputVariable":"agent_b_output"}
                ]
                """;
        String edges = """
                [ {"source":"__start__","target":"agent_a"},
                  {"source":"agent_a","target":"script_x"},
                  {"source":"script_x","target":"agent_b"},
                  {"source":"agent_b","target":"__end__"} ]
                """;
        String variables = """
                [ {"key":"premise","name":"题材创意","type":"textarea","default":"","required":true},
                  {"key":"language","name":"输出语言","type":"text","default":"中文","required":false} ]
                """;
        return new String[]{nodes, edges, variables};
    }

    @Test
    void parseValidLinearDefinition() {
        String[] d = linearDefinition();
        WorkflowDefinition def = parser.parse("test_v1", "测试工作流", 1, d[0], d[1], d[2]);

        assertEquals("test_v1", def.getCode());
        assertEquals("测试工作流", def.getName());
        assertEquals(3, def.getNodes().size());
        assertEquals(2, def.getVariables().size());
        // 拓扑顺序：agent_a → script_x → agent_b
        assertEquals(List.of("agent_a", "script_x", "agent_b"), def.getExecutionOrder());
        // 执行序号
        assertEquals(1, def.orderOf("agent_a"));
        assertEquals(3, def.orderOf("agent_b"));
        // 占位符引用提取：agent_b 引用 agent_a_output；agent_a 的 {{agent_b_output}} 非上游（自身输出），不计
        WorkflowNode agentB = def.node("agent_b");
        assertEquals(List.of("agent_a_output"), agentB.getReferencedOutputVariables());
        WorkflowNode agentA = def.node("agent_a");
        assertTrue(agentA.getReferencedOutputVariables().isEmpty(),
                "agent_a 引用的 agent_b_output 是自身输出，不应作为上游引用");
    }

    @Test
    void parseWithoutEdgesUsesDefinitionOrder() {
        String nodes = """
                [ {"id":"n1","nodeType":"agent","firstMessage":"{{premise}}"},
                  {"id":"n2","nodeType":"script","script_name":"s"} ]
                """;
        WorkflowDefinition def = parser.parse("no_edges", "无边工作流", 1, nodes, "", "");
        assertEquals(List.of("n1", "n2"), def.getExecutionOrder());
    }

    @Test
    void duplicateNodeIdRejected() {
        String nodes = """
                [ {"id":"n1","nodeType":"agent"}, {"id":"n1","nodeType":"script"} ]
                """;
        BizException e = assertThrows(BizException.class,
                () -> parser.parse("dup", "重复节点", 1, nodes, "", ""));
        assertTrue(e.getMessage().contains("重复"));
    }

    @Test
    void invalidNodeTypeRejected() {
        String nodes = """
                [ {"id":"n1","nodeType":"approval"} ]
                """;
        BizException e = assertThrows(BizException.class,
                () -> parser.parse("bad_type", "非法类型", 1, nodes, "", ""));
        assertTrue(e.getMessage().contains("类型非法"));
    }

    @Test
    void danglingEdgeRejected() {
        String[] d = linearDefinition();
        String edges = """
                [ {"source":"__start__","target":"agent_a"},
                  {"source":"agent_a","target":"ghost_node"} ]
                """;
        BizException e = assertThrows(BizException.class,
                () -> parser.parse("dangling", "悬空边", 1, d[0], edges, d[2]));
        assertTrue(e.getMessage().contains("不存在的节点"));
    }

    @Test
    void cyclicGraphRejected() {
        String nodes = """
                [ {"id":"a","nodeType":"agent"}, {"id":"b","nodeType":"script"} ]
                """;
        String edges = """
                [ {"source":"a","target":"b"}, {"source":"b","target":"a"} ]
                """;
        BizException e = assertThrows(BizException.class,
                () -> parser.parse("cycle", "环", 1, nodes, edges, ""));
        assertTrue(e.getMessage().contains("环"));
    }

    @Test
    void emptyNodesRejected() {
        BizException e = assertThrows(BizException.class,
                () -> parser.parse("empty", "空节点", 1, "[]", "", ""));
        assertTrue(e.getMessage().contains("未定义任何节点"));
    }
}
