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

import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Tool;

import java.util.Map;

/**
 * MCP 工具执行器接口
 * <p>
 * 一个执行器封装一个可被 LLM Function Calling 调用的具体工具：既提供其 Schema 定义
 * （{@link #getToolDefinition()}，用于注入到模型），也负责真正执行一次调用并返回标准化结果
 * （{@link #execute}）。典型实现有两类：本地封装的自研工具，以及通过 SDK 客户端转发到远端
 * MCP Server 的工具（{@link McpClientToolExecutor}）。
 */
public interface McpToolExecutor {

    /**
     * 获取工具的 Schema 定义
     * <p>
     * 该定义会进入注册表汇总后透传给 LLM，作为 Function Calling 声明一个可用函数的依据。
     *
     * @return 工具元信息（使用官方 SDK 的 Tool）
     */
    Tool getToolDefinition();

    /**
     * 执行一次工具调用并返回标准化结果
     *
     * @param parameters 模型根据 Schema 填写的调用参数（key 为参数名）
     * @return 工具调用结果（使用官方 SDK 的 CallToolResult，可携带 isError 标记供模型判断成败）
     */
    CallToolResult execute(Map<String, Object> parameters);

    /**
     * 工具 ID：直接取 Schema 定义里的工具名
     */
    default String getToolId() {
        return getToolDefinition().name();
    }
}
