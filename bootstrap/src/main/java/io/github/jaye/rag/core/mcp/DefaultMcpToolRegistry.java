/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.github.jaye.ai.askora.rag.core.mcp;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * MCP 工具注册表默认实现
 * <p>
 * 业务语义：维护当前会话可用的全部 MCP 工具（toolId -> 执行器）的内存注册表。
 * 启动时通过 {@code autoDiscoveredExecutors} 自动收集 Spring 容器里所有 {@link McpToolExecutor}
 * 并完成注册，此后 LLM 的 Function Calling 按 toolId 从中取执行器、拿到统一的 Tool 定义清单，
 * 是「模型能力入口」与「MCP 远端工具」之间的桥接层。
 * 同名 toolId 重复注册时后注册者覆盖前者。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DefaultMcpToolRegistry implements McpToolRegistry {

    /**
     * 工具执行器存储
     * key: toolId, value: executor
     */
    private final Map<String, McpToolExecutor> executorMap = new HashMap<>();

    /**
     * Spring 容器中的所有 McpToolExecutor Bean（自动注入）
     */
    private final List<McpToolExecutor> autoDiscoveredExecutors;

    /**
     * 启动时自动注册所有发现的执行器
     */
    @PostConstruct
    public void init() {
        if (CollUtil.isEmpty(autoDiscoveredExecutors)) {
            log.info("MCP 工具注册跳过, 未发现任何工具执行器");
        }

        for (McpToolExecutor executor : autoDiscoveredExecutors) {
            register(executor);
        }
        log.info("MCP 工具自动注册完成, 共注册 {} 个工具", autoDiscoveredExecutors.size());
    }

    /**
     * 注册一个工具执行器到注册表
     * <p>
     * 先做非空与 toolId 校验，再按其 toolId 入表；若该 toolId 已被占用则覆盖并告警，
     * 通常意味着不同来源（自动发现 / 手动注册）定义了同名的工具。
     */
    @Override
    public void register(McpToolExecutor executor) {
        if (executor == null || executor.getToolDefinition() == null) {
            log.warn("尝试注册空的执行器，已忽略");
            return;
        }

        String toolId = executor.getToolId();
        if (StrUtil.isBlank(toolId)) {
            log.warn("工具 ID 为空，已忽略");
            return;
        }

        McpToolExecutor existing = executorMap.put(toolId, executor);
        if (existing != null) {
            log.warn("工具 {} 已存在，已覆盖", toolId);
        } else {
            log.info("MCP 工具注册成功, toolId: {}", toolId);
        }
    }

    /**
     * 从注册表中注销指定工具（如该 MCP Server 断连时清理其暴露的工具）
     */
    @Override
    public void unregister(String toolId) {
        McpToolExecutor removed = executorMap.remove(toolId);
        if (removed != null) {
            log.info("MCP 工具注销成功, toolId: {}", toolId);
        }
    }

    /**
     * 按 toolId 取工具执行器，未注册时返回空
     */
    @Override
    public Optional<McpToolExecutor> getExecutor(String toolId) {
        return Optional.ofNullable(executorMap.get(toolId));
    }

    /**
     * 返回全部已注册工具的 Tool 定义清单，供 LLM Function Calling 声明可用函数
     */
    @Override
    public List<Tool> listAllTools() {
        return executorMap.values().stream()
                .map(McpToolExecutor::getToolDefinition)
                .toList();
    }

    /**
     * 返回全部已注册的执行器清单
     */
    @Override
    public List<McpToolExecutor> listAllExecutors() {
        return new ArrayList<>(executorMap.values());
    }

    /**
     * 判断某工具是否已注册
     */
    @Override
    public boolean contains(String toolId) {
        return executorMap.containsKey(toolId);
    }

    /**
     * 当前已注册工具的数量
     */
    @Override
    public int size() {
        return executorMap.size();
    }
}
