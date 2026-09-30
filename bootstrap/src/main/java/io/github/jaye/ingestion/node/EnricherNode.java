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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.jaye.ai.askora.core.chunk.model.EmbeddedChunk;
import io.github.jaye.ai.askora.framework.convention.ChatMessage;
import io.github.jaye.ai.askora.framework.convention.ChatRequest;
import io.github.jaye.ai.askora.ingestion.domain.context.IngestionContext;
import io.github.jaye.ai.askora.ingestion.domain.enums.ChunkEnrichType;
import io.github.jaye.ai.askora.ingestion.domain.enums.IngestionNodeType;
import io.github.jaye.ai.askora.ingestion.domain.pipeline.NodeConfig;
import io.github.jaye.ai.askora.ingestion.domain.result.NodeResult;
import io.github.jaye.ai.askora.ingestion.domain.settings.EnricherSettings;
import io.github.jaye.ai.askora.ingestion.prompt.EnricherPromptManager;
import io.github.jaye.ai.askora.ingestion.util.JsonResponseParser;
import io.github.jaye.ai.askora.ingestion.util.PromptTemplateRenderer;
import io.github.jaye.ai.askora.infra.chat.LLMService;
import io.github.jaye.ai.askora.infra.enums.Tier;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 文本增强节点
 * 该节点通过调用大模型对文档分片进行信息提取或补充，如提取关键词、生成摘要、补充元数据等
 */
@Component
public class EnricherNode implements IngestionNode {

    private final ObjectMapper objectMapper;
    private final LLMService llmService;

    public EnricherNode(ObjectMapper objectMapper, LLMService llmService) {
        this.objectMapper = objectMapper;
        this.llmService = llmService;
    }

    /**
     * 获取 ENRICHER 节点类型标识
     */
    @Override
    public String getNodeType() {
        return IngestionNodeType.ENRICHER.getValue();
    }

    /**
     * 逐块调用大模型做信息加工，产物写入块的元数据扩展位（extras）
     * <p>
     * 与 EnhancerNode 的职责边界：本节点作用于分块之后的块，加工结果随块入库参与检索；
     * 无块或无任务配置时静默成功，不阻断流水线。块不可变——先收集产物到 extras，
     * 再整块替换回写上下文
     */
    @Override
    public NodeResult execute(IngestionContext context, NodeConfig config) {
        List<EmbeddedChunk> chunks = context.getChunks();
        if (chunks == null || chunks.isEmpty()) {
            return NodeResult.ok("No chunks to enrich");
        }
        EnricherSettings settings = parseSettings(config.getSettings());
        if (settings.getTasks() == null || settings.getTasks().isEmpty()) {
            return NodeResult.ok("No enricher tasks configured");
        }
        boolean attachMetadata = settings.getAttachDocumentMetadata() == null || settings.getAttachDocumentMetadata();
        // 块不可变：加工产物收集到扩展位，最后整块替换，而不是原地改一个自由 Map
        List<EmbeddedChunk> enriched = new java.util.ArrayList<>(chunks.size());
        for (EmbeddedChunk chunk : chunks) {
            if (chunk == null || !StringUtils.hasText(chunk.content())) {
                if (chunk != null) {
                    enriched.add(chunk);
                }
                continue;
            }
            Map<String, Object> extras = new HashMap<>();
            if (attachMetadata && context.getMetadata() != null) {
                extras.putAll(context.getMetadata());
            }
            for (EnricherSettings.ChunkEnrichTask task : settings.getTasks()) {
                if (task == null || task.getType() == null) {
                    continue;
                }
                ChunkEnrichType type = task.getType();
                String systemPrompt = StringUtils.hasText(task.getSystemPrompt())
                        ? task.getSystemPrompt()
                        : EnricherPromptManager.systemPrompt(type);
                String userPrompt = buildUserPrompt(task.getUserPromptTemplate(), chunk, context);
                ChatRequest request = ChatRequest.builder()
                        .messages(List.of(
                                ChatMessage.system(systemPrompt == null ? "" : systemPrompt),
                                ChatMessage.user(userPrompt)
                        ))
                        .build();
                String response = chat(request, settings.getModelId());
                applyResult(extras, type, response);
            }
            enriched.add(extras.isEmpty()
                    ? chunk
                    : new EmbeddedChunk(chunk.chunk().withMetadata(chunk.metadata().withExtras(extras)),
                            chunk.embedding()));
        }
        context.setChunks(enriched);
        return NodeResult.ok("Enricher completed");
    }

    /**
     * 把节点配置里的 JSON settings 反序列化为加工配置，缺失时给空任务列表兜底
     */
    private EnricherSettings parseSettings(JsonNode node) {
        if (node == null || node.isNull()) {
            return EnricherSettings.builder().tasks(List.of()).build();
        }
        return objectMapper.convertValue(node, EnricherSettings.class);
    }

    /**
     * 构造用户提示词：无模板时直接用块原文；有模板则以 text/chunkIndex 等变量渲染
     */
    private String buildUserPrompt(String template, EmbeddedChunk chunk, IngestionContext context) {
        String input = chunk.content();
        if (!StringUtils.hasText(template)) {
            return input;
        }
        Map<String, Object> vars = new HashMap<>();
        vars.put("text", input);
        vars.put("content", input);
        vars.put("chunkIndex", chunk.index());
        vars.put("taskId", context.getTaskId());
        vars.put("pipelineId", context.getPipelineId());
        return PromptTemplateRenderer.render(template, vars);
    }

    /**
     * 按加工类型把模型响应写入扩展位：关键词、摘要以固定键存放，元数据对象直接平铺合并
     */
    private void applyResult(Map<String, Object> extras, ChunkEnrichType type, String response) {
        switch (type) {
            case KEYWORDS -> extras.put("keywords", JsonResponseParser.parseStringList(response));
            case SUMMARY -> extras.put("summary", StringUtils.hasText(response) ? response.trim() : response);
            case METADATA -> extras.putAll(JsonResponseParser.parseObject(response));
            default -> {
            }
        }
    }

    /**
     * 调用大模型对话，加工任务统一走 FAST 档
     */
    private String chat(ChatRequest request, String modelId) {
        return llmService.chat(request, Tier.FAST, modelId);
    }
}
