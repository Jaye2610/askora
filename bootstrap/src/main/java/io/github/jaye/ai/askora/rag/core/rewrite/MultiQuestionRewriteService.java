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

package io.github.jaye.ai.askora.rag.core.rewrite;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.jaye.ai.askora.infra.util.LLMResponseCleaner;
import io.github.jaye.ai.askora.rag.config.RAGConfigProperties;
import io.github.jaye.ai.askora.framework.convention.ChatMessage;
import io.github.jaye.ai.askora.framework.convention.ChatRequest;
import io.github.jaye.ai.askora.framework.trace.RagTraceNode;
import io.github.jaye.ai.askora.infra.chat.LLMService;
import io.github.jaye.ai.askora.infra.enums.Tier;
import io.github.jaye.ai.askora.rag.core.prompt.PromptTemplateLoader;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import static io.github.jaye.ai.askora.rag.constant.RAGConstant.QUERY_REWRITE_AND_SPLIT_PROMPT_PATH;

/**
 * 查询预处理：改写 + 拆分多问句
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MultiQuestionRewriteService implements QueryRewriteService {

    private final LLMService llmService;
    private final RAGConfigProperties ragConfigProperties;
    private final QueryTermMappingService queryTermMappingService;
    private final PromptTemplateLoader promptTemplateLoader;

    /**
     * 独立改写入口：不做多问句拆分，只取改写后的主问题
     * <p>
     * 复用与拆分链路相同的 LLM 改写，失败同样回退到归一化问题
     *
     * @return 改写后的主问题（失败时回退归一化后的原问题）
     */
    @Override
    @RagTraceNode(name = "query-rewrite", type = "REWRITE")
    public String rewrite(String userQuestion) {
        return rewriteAndSplit(userQuestion).rewrittenQuestion();
    }

    /**
     * 改写 + 拆分：无会话历史版本，脱胎于 rewriteAndSplit 的统一逻辑
     */
    @Override
    public RewriteResult rewriteWithSplit(String userQuestion) {
        return rewriteAndSplit(userQuestion);
    }

    /**
     * 查询预处理主入口：改写 + 拆分多问句，支持携带会话历史
     * <p>
     * 业务走向二选一：
     * <ul>
     *     <li>开关关闭（非 LLM 场景）：只做词典归一化 + 规则拆分，不烧 LLM Token</li>
     *     <li>开关打开：先归一化，再让 LLM 改写并拆分；LLM 失败回退到「归一化问题 + 规则拆分」兜底</li>
     * </ul>
     *
     * @param history 会话历史，仅当开关打开时传给 LLM 作为上下文
     */
    @Override
    @RagTraceNode(name = "query-rewrite-and-split", type = "REWRITE")
    public RewriteResult rewriteWithSplit(String userQuestion, List<ChatMessage> history) {
        // 开关关闭：不走 LLM，直接做词典归一化 + 规则拆分
        if (!ragConfigProperties.getQueryRewriteEnabled()) {
            String normalized = queryTermMappingService.normalize(userQuestion);
            List<String> subs = ruleBasedSplit(normalized);
            return new RewriteResult(normalized, subs);
        }

        // 归一化先行：同一问题无论措辞如何，先落到统一词形上，再交给 LLM 改写
        String normalizedQuestion = queryTermMappingService.normalize(userQuestion);

        return callLLMRewriteAndSplit(normalizedQuestion, userQuestion, history);
    }

    /**
     * 改写 + 拆分的统一骨架：开关关闭走规则，开关打开走 LLM（不带历史）
     * <p>
     * 无历史版本与带历史版本共享同一 LLM 逻辑，仅是历史参数传空
     */
    private RewriteResult rewriteAndSplit(String userQuestion) {
        // 开关关闭：直接做规则归一化 + 规则拆分
        if (!ragConfigProperties.getQueryRewriteEnabled()) {
            String normalized = queryTermMappingService.normalize(userQuestion);
            List<String> subs = ruleBasedSplit(normalized);
            return new RewriteResult(normalized, subs);
        }

        String normalizedQuestion = queryTermMappingService.normalize(userQuestion);

        return callLLMRewriteAndSplit(normalizedQuestion, userQuestion, List.of());

        // 兜底：使用归一化结果 + 规则拆分
    }

    /**
     * 调用 LLM 完成「改写 + 拆分多问句」，并对失败做归一化兜底
     * <p>
     * 兜底策略（快档调用，候选内已做传输容错，不再跨档升级）：
     * <ul>
     *     <li>LLM 调用抛异常：回退到「归一化问题 + 单子问题」</li>
     *     <li>返回内容解析不出合法 JSON / 缺 rewrite 字段：同样回退归一化问题</li>
     *     <li>解析出 rewrite 但缺 sub_questions：以 rewrite 自身作为唯一子问题</li>
     * </ul>
     *
     * @param normalizedQuestion 词典归一化后的问题，用作 LLM 输入与兜底输出
     * @param originalQuestion   原始用户问题，仅供日志记录
     * @param history            会话历史，非空时拼接给 LLM 作为上下文
     */
    private RewriteResult callLLMRewriteAndSplit(String normalizedQuestion,
                                                 String originalQuestion,
                                                 List<ChatMessage> history) {
        String systemPrompt = promptTemplateLoader.load(QUERY_REWRITE_AND_SPLIT_PROMPT_PATH);
        ChatRequest req = buildRewriteRequest(systemPrompt, normalizedQuestion, history);

        // 快速档调用；解析失败或调用失败均用归一化问题兜底（档位内多候选已提供传输容错，不再跨档升级）
        RewriteResult fallback = new RewriteResult(normalizedQuestion, List.of(normalizedQuestion));
        RewriteResult result;
        try {
            RewriteResult parsed = parseRewriteAndSplit(llmService.chat(req, Tier.FAST));
            result = parsed != null ? parsed : fallback;
        } catch (Exception e) {
            log.warn("查询改写 LLM 调用失败，使用归一化问题兜底", e);
            result = fallback;
        }

        log.info("""
                RAG用户问题查询改写+拆分：
                原始问题：{}
                归一化后：{}
                改写结果：{}
                子问题：{}
                """, originalQuestion, normalizedQuestion, result.rewrittenQuestion(), result.subQuestions());
        return result;
    }

    /**
     * 组装 LLM 请求：系统提示词 + 最近会话 + 当前问题
     * <p>
     * 低温度（0.1）/低 topP（0.3）是为了让改写输出尽量稳定严谨，不做开放式生成；
     * 轻量任务关闭思考链以省 Token
     */
    private ChatRequest buildRewriteRequest(String systemPrompt,
                                            String question,
                                            List<ChatMessage> history) {
        List<ChatMessage> messages = new ArrayList<>();
        if (StrUtil.isNotBlank(systemPrompt)) {
            messages.add(ChatMessage.system(systemPrompt));
        }

        // 只保留最近 1-2 轮的 User 和 Assistant 消息
        // 过滤掉 System 摘要，避免 Token 浪费
        if (CollUtil.isNotEmpty(history)) {
            List<ChatMessage> recentHistory = history.stream()
                    .filter(msg -> msg.getRole() == ChatMessage.Role.USER
                            || msg.getRole() == ChatMessage.Role.ASSISTANT)
                    .skip(Math.max(0, history.size() - 4))  // 最多保留最近 4 条消息（2 轮对话）
                    .toList();
            messages.addAll(recentHistory);
        }

        messages.add(ChatMessage.user(question));

        return ChatRequest.builder()
                .messages(messages)
                .temperature(0.1D)
                .topP(0.3D)
                .thinking(false)
                .build();
    }


    /**
     * 解析 LLM 返回的 {@code {"rewrite": "...", "sub_questions": [...]}} JSON
     * <p>
     * 任一环节异常或缺 rewrite 字段即返回 null（由调用方走归一化兜底）；
     * 有 rewrite 但无有效子问题时，把 rewrite 本身作为唯一子问题，保证后续链路至少拿到一个问题
     */
    private RewriteResult parseRewriteAndSplit(String raw) {
        try {
            // 移除可能存在的 Markdown 代码块标记
            String cleaned = LLMResponseCleaner.stripMarkdownCodeFence(raw);

            JsonElement root = JsonParser.parseString(cleaned);
            if (!root.isJsonObject()) {
                return null;
            }
            JsonObject obj = root.getAsJsonObject();
            String rewrite = obj.has("rewrite") ? obj.get("rewrite").getAsString().trim() : "";
            List<String> subs = new ArrayList<>();
            if (obj.has("sub_questions") && obj.get("sub_questions").isJsonArray()) {
                JsonArray arr = obj.getAsJsonArray("sub_questions");
                for (JsonElement el : arr) {
                    if (el.isJsonPrimitive() && el.getAsJsonPrimitive().isString()) {
                        String s = el.getAsString().trim();
                        if (StrUtil.isNotBlank(s)) {
                            subs.add(s);
                        }
                    }
                }
            }
            if (StrUtil.isBlank(rewrite)) {
                return null;
            }
            if (CollUtil.isEmpty(subs)) {
                subs = List.of(rewrite);
            }
            return new RewriteResult(rewrite, subs);
        } catch (Exception e) {
            log.warn("解析改写+拆分结果失败，raw={}", raw, e);
            return null;
        }
    }

    /**
     * 规则兜底拆分：LLM 不可用（开关关闭 / 调用失败）时，按常见分隔符把多问句切成子问题
     * <p>
     * 切完每个子问题统一补齐问号（半角补全角），保证下游每个子问题形态完整；
     * 切不出任何子问题时回退到整句单问题
     */
    private List<String> ruleBasedSplit(String question) {
        // 兜底：按常见分隔符拆分
        List<String> parts = Arrays.stream(question.split("[?？。；;\\n]+"))
                .map(String::trim)
                .filter(StrUtil::isNotBlank)
                .collect(Collectors.toList());

        if (CollUtil.isEmpty(parts)) {
            return List.of(question);
        }
        return parts.stream()
                .map(s -> s.endsWith("？") || s.endsWith("?") ? s : s + "？")
                .toList();
    }
}
