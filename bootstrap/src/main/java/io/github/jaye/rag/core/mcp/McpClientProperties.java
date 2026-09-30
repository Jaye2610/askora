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

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * MCP 客户端配置属性，对应 {@code rag.mcp.*} 配置项
 * <p>
 * 用于声明接入的远程 MCP Server 列表，启动时由 {@link McpClientAutoConfiguration} 读取，
 * 依次连接每个 Server、拉取其工具并注册为可被大模型调用的工具（tool calling）
 */
@Data
@ConfigurationProperties(prefix = "rag.mcp")
public class McpClientProperties {

    /**
     * MCP Server 列表：按序连接并注册其暴露的工具
     */
    private List<ServerConfig> servers = new ArrayList<>();

    @Data
    public static class ServerConfig {

        /**
         * 服务名称：用于工具 id 命名区分与日志标识
         */
        private String name;

        /**
         * 服务地址（MCP HTTP 端点基址，自动补齐 /mcp 路径）
         */
        private String url;
    }
}
