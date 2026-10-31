package com.holzyn.actor.domain.worldstore;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 镜像表清单与生成器产物的一致性（V1.1.2-beta.1 M2）。
 *
 * <p>{@link MirrorTables} 是运行时硬编码清单（避免多一个"启动读 JSON 失败"的失败点），
 * 而 {@code db/worldsplit-tables.json} 是 schema 拆分与分类测试的唯一依据。两者一旦分叉，
 * 症状是"测试全绿但世界启动后少刷了一张表"——镜像不全会让挂载后的查询静默返回空。</p>
 *
 * <p>所属模块：test/domain/worldstore</p>
 */
class MirrorTablesContractTest {

    private static Set<String> fromJson() throws IOException {
        var res = new ClassPathResource("db/worldsplit-tables.json");
        assertTrue(res.exists(), "缺少分类清单 db/worldsplit-tables.json");
        String text;
        try (var in = res.getInputStream()) {
            text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        JsonNode mirror = new ObjectMapper().readTree(text).get("mirror");
        Set<String> out = new TreeSet<>();
        for (JsonNode n : mirror) {
            out.add(n.asText());
        }
        return out;
    }

    @Test
    @DisplayName("清单一致：MirrorTables.ALL 与 worldsplit-tables.json 的 mirror 逐项相等")
    void mirrorListMatchesGeneratedManifest() throws IOException {
        Set<String> json = fromJson();
        Set<String> code = new TreeSet<>(MirrorTables.ALL);
        assertEquals(12, code.size(), "镜像表应为 12 张");
        assertEquals(json, code, "运行时清单与生成器产物不一致（多=" + diff(code, json) + " 少=" + diff(json, code) + "）");
    }

    @Test
    @DisplayName("actor_project 是『只镜像自己那一行』的特例，必须包含在清单里")
    void ownRowTableIsPartOfMirrorSet() {
        assertTrue(MirrorTables.SET.contains(MirrorTables.OWN_ROW_TABLE));
        assertTrue(MirrorTables.isMirror("actor_project"));
        assertTrue(MirrorTables.isMirror("ACTOR_PROJECT"), "判定应大小写不敏感");
        assertTrue(MirrorTables.isMirror("actor_prompt_template"));
        assertEquals(false, MirrorTables.isMirror("actor_profile"), "世界自有表不应被当成镜像");
        assertEquals(false, MirrorTables.isMirror(null));
    }

    private static <T> Set<T> diff(Set<T> a, Set<T> b) {
        Set<T> out = new LinkedHashSet<>(a);
        out.removeAll(b);
        return out;
    }
}
