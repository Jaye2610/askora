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
import io.github.jaye.ai.askora.framework.convention.ChatMessage;
import io.github.jaye.ai.askora.framework.convention.ChatRequest;
import io.github.jaye.ai.askora.ingestion.domain.context.IngestionContext;
import io.github.jaye.ai.askora.ingestion.domain.enums.EnhanceType;
import io.github.jaye.ai.askora.ingestion.domain.enums.IngestionNodeType;
import io.github.jaye.ai.askora.ingestion.domain.pipeline.NodeConfig;
import io.github.jaye.ai.askora.ingestion.domain.result.NodeResult;
import io.github.jaye.ai.askora.ingestion.domain.settings.EnhancerSettings;
import io.github.jaye.ai.askora.ingestion.prompt.EnhancerPromptManager;
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
 * 该节点通过调用大模型对输入的文本进行增强处理，包括不限于上下文增强、关键词提取、问题生成及元数据提取等任务
 */
@Component
public class EnhancerNode implements IngestionNode {

    private final ObjectMapper objectMapper;
    private final LLMService llmService;

    public EnhancerNode(ObjectMapper objectMapper, LLMService llmService) {
        this.objectMapper = objectMapper;
        this.llmService = llmService;
    }

    /**
     * 获取 ENHANCER 节点类型标识
     */
    @Override
    public String getNodeType() {
        return IngestionNodeType.ENHANCER.getValue();
    }

    /**
     * 按配置的任务列表逐项调用大模型增强文本
     * <p>
     * 每个任务指定增强类型（上下文增强/关键词/问题/元数据），输入文本随类型取原始或已增强文本，
     * 没配任务时直接成功返回；任务间通过上下文串联——前一个任务的增强结果可作为后一个的输入
     */
    @Override
    public NodeResult execute(IngestionContext context, NodeConfig config) {
        EnhancerSettings settings = parseSettings(config.getSettings());
        if (settings.getTasks() == null || settings.getTasks().isEmpty()) {
            return NodeResult.ok("未配置增强任务");
        }
        if (context.getMetadata() == null) {
            context.setMetadata(new HashMap<>());
        }

        for (EnhancerSettings.EnhanceTask task : settings.getTasks()) {
            if (task == null || task.getType() == null) {
                continue;
            }
            EnhanceType type = task.getType();
            String input = resolveInputText(context, type);
            if (!StringUtils.hasText(input)) {
                continue;
            }
            String systemPrompt = StringUtils.hasText(task.getSystemPrompt())
                    ? task.getSystemPrompt()
                    : EnhancerPromptManager.systemPrompt(type);
            String userPrompt = buildUserPrompt(task.getUserPromptTemplate(), input, context);

            ChatRequest request = ChatRequest.builder()
                    .messages(List.of(
                            ChatMessage.system(systemPrompt == null ? "" : systemPrompt),
                            ChatMessage.user(userPrompt)
                    ))
                    .build();
            String response = chat(request, settings.getModelId());
            applyTaskResult(context, type, response);
        }

        return NodeResult.ok("增强完成");
    }

    /**
     * 把节点配置里的 JSON settings 反序列化为增强器配置，缺失时给空任务列表兜底
     */
    private EnhancerSettings parseSettings(JsonNode node) {
        if (node == null || node.isNull()) {
            return EnhancerSettings.builder().tasks(List.of()).build();
        }
        return objectMapper.convertValue(node, EnhancerSettings.class);
    }

    /**
     * 按增强类型选输入文本：上下文增强始终用原始文本，其余任务优先用已增强文本（无则退回原始文本）
     */
    private String resolveInputText(IngestionContext context, EnhanceType type) {
        if (type == EnhanceType.CONTEXT_ENHANCE) {
            return context.getRawText();
        }
        if (StringUtils.hasText(context.getEnhancedText())) {
            return context.getEnhancedText();
        }
        return context.getRawText();
    }

    /**
     * 构造用户提示词：无模板时直接用原文；有模板则以 text/content 等变量渲染，让调用方可定制输入格式
     */
    private String buildUserPrompt(String template, String input, IngestionContext context) {
        if (!StringUtils.hasText(template)) {
            return input;
        }
        Map<String, Object> vars = new HashMap<>();
        vars.put("text", input);
        vars.put("content", input);
        vars.put("mimeType", context.getMimeType());
        vars.put("taskId", context.getTaskId());
        vars.put("pipelineId", context.getPipelineId());
        return PromptTemplateRenderer.render(template, vars);
    }

    /**
     * 调用大模型对话，增强任务对时效与成本不敏感，统一走 FAST 档
     */
    private String chat(ChatRequest request, String modelId) {
        return llmService.chat(request, Tier.FAST, modelId);
    }

    /**
     * 按增强类型把模型响应回写到上下文的对应字段，列表类响应用 JSON 解析器抽取
     */
    private void applyTaskResult(IngestionContext context, EnhanceType type, String response) {
        switch (type) {
            case CONTEXT_ENHANCE -> context.setEnhancedText(StringUtils.hasText(response) ? response.trim() : response);
            case KEYWORDS -> context.setKeywords(JsonResponseParser.parseStringList(response));
            case QUESTIONS -> context.setQuestions(JsonResponseParser.parseStringList(response));
            case METADATA -> context.getMetadata().putAll(JsonResponseParser.parseObject(response));
            default -> {
            }
        }
    }
}
