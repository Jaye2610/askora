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

package io.github.jaye.ai.askora.ingestion.node;

import io.github.jaye.ai.askora.framework.exception.ClientException;
import io.github.jaye.ai.askora.ingestion.domain.context.DocumentSource;
import io.github.jaye.ai.askora.ingestion.domain.context.IngestionContext;
import io.github.jaye.ai.askora.ingestion.domain.enums.IngestionNodeType;
import io.github.jaye.ai.askora.ingestion.domain.enums.SourceType;
import io.github.jaye.ai.askora.ingestion.domain.pipeline.NodeConfig;
import io.github.jaye.ai.askora.ingestion.domain.result.NodeResult;
import io.github.jaye.ai.askora.ingestion.strategy.fetcher.DocumentFetcher;
import io.github.jaye.ai.askora.ingestion.strategy.fetcher.FetchResult;
import io.github.jaye.ai.askora.core.parser.mime.MimeTypeDetector;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 文档获取节点 (Fetcher Node)
 * 数据摄取负责从多元化的存储介质（如 Local FS、HTTP/HTTPS、OSS 等）中检索并载入文档原始字节流
 * 核心逻辑采用策略模式 (Strategy Pattern) 实现，根据 {@link SourceType} 动态路由至具体的 {@link DocumentFetcher}
 * 具备幂等性检查机制：若上下文中已预置原始字节，则自动跳过获取流程，避免重复 I/O
 */
@Component
public class FetcherNode implements IngestionNode {

    private final Map<SourceType, DocumentFetcher> fetchers;

    /**
     * 收集所有拉取器实现，按支持的来源类型建立策略表，执行时按 SourceType 路由
     */
    public FetcherNode(List<DocumentFetcher> fetchers) {
        this.fetchers = fetchers.stream()
                .collect(Collectors.toMap(DocumentFetcher::supportedType, Function.identity()));
    }

    /**
     * 获取 FETCHER 节点类型标识
     */
    @Override
    public String getNodeType() {
        return IngestionNodeType.FETCHER.getValue();
    }

    /**
     * 从配置的文档来源拉取原始字节流
     * <p>
     * 幂等设计：上下文已有原始字节时只补齐缺失的 MIME 探测并直接跳过，避免重复 I/O；
     * 拉取器按来源类型从策略表路由取得，拉取结果（字节、MIME、文件名）回写上下文供后续节点使用
     */
    @Override
    public NodeResult execute(IngestionContext context, NodeConfig config) {
        if (context.getRawBytes() != null && context.getRawBytes().length > 0) {
            if (!StringUtils.hasText(context.getMimeType())) {
                String fileName = context.getSource() == null ? null : context.getSource().getFileName();
                context.setMimeType(MimeTypeDetector.detect(context.getRawBytes(), fileName));
            }
            return NodeResult.ok("已跳过获取器：原始字节已存在");
        }

        DocumentSource source = context.getSource();
        if (source == null || source.getType() == null) {
            return NodeResult.fail(new ClientException("文档来源不能为空"));
        }

        DocumentFetcher fetcher = fetchers.get(source.getType());
        if (fetcher == null) {
            return NodeResult.fail(new ClientException("不支持的来源类型: " + source.getType()));
        }

        FetchResult result = fetcher.fetch(source);
        context.setRawBytes(result.content());
        if (StringUtils.hasText(result.mimeType())) {
            context.setMimeType(result.mimeType());
        }
        if (StringUtils.hasText(result.fileName())) {
            source.setFileName(result.fileName());
        }
        return NodeResult.ok("已获取 " + (result.content() == null ? 0 : result.content().length) + " 字节");
    }
}
