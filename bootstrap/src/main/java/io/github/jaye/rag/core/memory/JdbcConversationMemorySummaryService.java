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

package io.github.jaye.ai.askora.rag.core.memory;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import io.github.jaye.ai.askora.framework.convention.ChatMessage;
import io.github.jaye.ai.askora.rag.core.source.CitationMarkup;
import io.github.jaye.ai.askora.framework.convention.ChatRequest;
import io.github.jaye.ai.askora.infra.chat.LLMService;
import io.github.jaye.ai.askora.infra.enums.Tier;
import io.github.jaye.ai.askora.rag.config.MemoryProperties;
import io.github.jaye.ai.askora.rag.core.prompt.AgentPromptResolver;
import io.github.jaye.ai.askora.rag.core.prompt.AgentPromptSlot;
import io.github.jaye.ai.askora.rag.core.prompt.PromptTemplateLoader;
import io.github.jaye.ai.askora.rag.dao.entity.ConversationMessageDO;
import io.github.jaye.ai.askora.rag.dao.entity.ConversationSummaryDO;
import io.github.jaye.ai.askora.rag.service.ConversationGroupService;
import io.github.jaye.ai.askora.rag.service.ConversationMessageService;
import io.github.jaye.ai.askora.rag.service.bo.ConversationSummaryBO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.stream.Collectors;

import static io.github.jaye.ai.askora.rag.constant.RAGConstant.CONTEXT_FORMAT_PATH;

@Slf4j
@Service
@RequiredArgsConstructor
/**
 * 基于 JDBC 的对话记忆摘要压缩服务
 * 核心思路：历史消息按滑动窗口保留最近 N 轮原文，窗口外的旧消息由 LLM 增量合并为一条摘要，
 * 送入模型上下文时"摘要 + 窗口内原文"，从而在有限 token 预算内保留长对话的语义连续性
 */
public class JdbcConversationMemorySummaryService implements ConversationMemorySummaryService {

    private static final String SUMMARY_LOCK_PREFIX = "askora:memory:summary:lock:";

    private final ConversationGroupService conversationGroupService;
    private final ConversationMessageService conversationMessageService;
    private final MemoryProperties memoryProperties;
    private final LLMService llmService;
    private final PromptTemplateLoader promptTemplateLoader;
    private final AgentPromptResolver agentPromptResolver;
    private final RedissonClient redissonClient;
    private final Executor memorySummaryExecutor;

    /**
     * 判断是否需要摘要压缩，满足条件时异步触发
     * 仅在 assistant 回复落地后触发（此时一轮问答已完整），且压缩在线程池异步执行，
     * 不阻塞问答主流程的返回
     */
    @Override
    public void compressIfNeeded(String conversationId, String userId, ChatMessage message) {
        if (!memoryProperties.getSummaryEnabled()) {
            return;
        }
        if (message.getRole() != ChatMessage.Role.ASSISTANT) {
            return;
        }
        CompletableFuture.runAsync(() -> doCompressIfNeeded(conversationId, userId), memorySummaryExecutor)
                .exceptionally(ex -> {
                    log.error("对话记忆摘要异步任务失败 - conversationId: {}, userId: {}",
                            conversationId, userId, ex);
                    return null;
                });
    }

    /**
     * 加载当前对话最新一条摘要，无摘要时返回 null
     */
    @Override
    public ChatMessage loadLatestSummary(String conversationId, String userId) {
        ConversationSummaryDO summary = conversationGroupService.findLatestSummary(conversationId, userId);
        return toChatMessage(summary);
    }

    /**
     * 将摘要包装为系统消息，套用模板标记其为历史概览，与当前轮指令区分开
     */
    @Override
    public ChatMessage decorateIfNeeded(ChatMessage summary) {
        if (summary == null || StrUtil.isBlank(summary.getContent())) {
            return summary;
        }
        String wrapped = promptTemplateLoader.renderSection(
                CONTEXT_FORMAT_PATH, "summary-wrapper",
                Map.of("content", summary.getContent().trim())
        );
        return ChatMessage.system(wrapped);
    }

    /**
     * 执行摘要压缩的核心流程
     * 关键设计：
     * 1. Redisson 分布式锁按"用户+会话"粒度 tryLock，拿不到锁直接放弃——同一会话并发追加时
     *    只允许一个节点压缩，其余请求丢弃即可，摘要最终会由后续触发补齐（幂等兜底）；
     * 2. 滑动窗口边界比较：afterId 是上次摘要已覆盖到的消息位置（摘要窗口尾巴），historyStartId 是当前消息窗口内
     *    最早一条用户消息的位置（消息窗口左边界）。若 afterId >= historyStartId，说明上次摘要已覆盖到窗口内部，
     *    窗口外没有新的可压缩内容，直接返回；否则把 (afterId, summaryCutoffId] 区间的消息送去压缩；
     * 3. summaryCutoffId 取窗口中点而非窗口起点：让摘要覆盖约一半原文窗口，新旧摘要存在重叠区间，
     *    便于 LLM 合并去重，也降低"摘要刚生成就滑出窗口导致信息断层"的概率；
     * 4. 失败仅记日志不重试，由下一轮问答的追加触发再次尝试
     */
    private void doCompressIfNeeded(String conversationId, String userId) {
        long startTime = System.currentTimeMillis();
        int triggerTurns = memoryProperties.getSummaryStartTurns();
        int maxTurns = memoryProperties.getHistoryKeepTurns();
        if (maxTurns <= 0 || triggerTurns <= 0) {
            return;
        }

        String lockKey = SUMMARY_LOCK_PREFIX + buildLockKey(conversationId, userId);
        RLock lock = redissonClient.getLock(lockKey);
        if (!lock.tryLock()) {
            return;
        }
        try {
            long total = conversationGroupService.countUserMessages(conversationId, userId);
            // 轮数未到摘要触发阈值（triggerTurns）时直接返回，避免过早压缩丢上下文
            if (total < triggerTurns) {
                return;
            }

            // 读取最近一次已生成的摘要，作为新摘要生成的上下文基准
            ConversationSummaryDO latestSummary = conversationGroupService.findLatestSummary(conversationId, userId);

            List<ConversationMessageDO> latestUserTurns = conversationGroupService.listLatestUserOnlyMessages(
                    conversationId,
                    userId,
                    maxTurns
            );
            if (latestUserTurns.isEmpty()) {
                return;
            }
            String historyStartId = resolveHistoryStartId(latestUserTurns);
            if (StrUtil.isBlank(historyStartId)) {
                return;
            }

            String afterId = resolveSummaryStartId(conversationId, userId, latestSummary);
            if (afterId != null && Long.parseLong(afterId) >= Long.parseLong(historyStartId)) {
                return;
            }

            // 摘要覆盖约一半原文窗口；只有这段重叠滑出窗口后才再次生成摘要
            String summaryCutoffId = resolveSummaryCutoffId(latestUserTurns);
            if (StrUtil.isBlank(summaryCutoffId)) {
                return;
            }

            List<ConversationMessageDO> toSummarize = conversationGroupService.listMessagesBetweenIds(
                    conversationId,
                    userId,
                    afterId,
                    summaryCutoffId
            );
            if (CollUtil.isEmpty(toSummarize)) {
                return;
            }

            String lastMessageId = resolveLastMessageId(toSummarize);
            if (StrUtil.isBlank(lastMessageId)) {
                return;
            }

            String existingSummary = latestSummary == null ? "" : latestSummary.getContent();
            String summary = summarizeMessages(toSummarize, existingSummary);
            if (StrUtil.isBlank(summary)) {
                return;
            }

            createSummary(conversationId, userId, summary, lastMessageId);
            log.info("摘要成功 - conversationId：{}，userId：{}，消息数：{}，耗时：{}ms",
                    conversationId, userId, toSummarize.size(),
                    System.currentTimeMillis() - startTime);
        } catch (Exception e) {
            log.error("摘要失败 - conversationId：{}，userId：{}", conversationId, userId, e);
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    /**
     *
     * @param messages 需要提取摘要的消息
     * @param existingSummary 已存在的生成的最新的摘要消息
     * @return
     */
    private String summarizeMessages(List<ConversationMessageDO> messages, String existingSummary) {
        List<ChatMessage> histories = toHistoryMessages(messages);
        if (CollUtil.isEmpty(histories)) {
            return existingSummary;
        }

        int summaryMaxChars = memoryProperties.getSummaryMaxChars();
        List<ChatMessage> summaryMessages = new ArrayList<>();
        String summaryPrompt = agentPromptResolver.render(
                AgentPromptSlot.CONVERSATION_SUMMARY,
                Map.of("summary_max_chars", String.valueOf(summaryMaxChars))
        );
        summaryMessages.add(ChatMessage.system(summaryPrompt));

        if (StrUtil.isNotBlank(existingSummary)) {
            summaryMessages.add(ChatMessage.assistant(
                    "历史摘要（仅用于合并去重，不得作为事实新增来源；若与本轮对话冲突，以本轮对话为准）：\n"
                            + existingSummary.trim()
            ));
        }
        summaryMessages.addAll(histories);
        summaryMessages.add(ChatMessage.user(
                "合并以上对话与历史摘要，去重后输出更新摘要。要求：严格≤" + summaryMaxChars + "字符；仅一行。"
        ));

        ChatRequest request = ChatRequest.builder()
                .messages(summaryMessages)
                .temperature(0.3D)
                .topP(0.9D)
                .thinking(false)
                .build();
        try {
            String result = llmService.chat(request, Tier.FAST);
            log.info("对话摘要生成 - resultChars: {}", result.length());

            return result;
        } catch (Exception e) {
            log.error("对话记忆摘要生成失败, conversationId相关消息数: {}", messages.size(), e);
            return existingSummary;
        }
    }

    /**
     * 数据库消息记录转对话消息列表
     * 只保留 user/assistant 角色，助手消息剥离引用标记，降低摘要输入的 token 占用
     */
    private List<ChatMessage> toHistoryMessages(List<ConversationMessageDO> messages) {
        if (CollUtil.isEmpty(messages)) {
            return List.of();
        }
        return messages.stream()
                .filter(item -> item != null
                        && StrUtil.isNotBlank(item.getContent())
                        && StrUtil.isNotBlank(item.getRole()))
                .map(item -> {
                    String role = item.getRole().toLowerCase();
                    if ("user".equals(role)) {
                        return ChatMessage.user(item.getContent());
                    } else if ("assistant".equals(role)) {
                        return ChatMessage.assistant(CitationMarkup.strip(item.getContent()));
                    }
                    return null;
                })
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    /**
     * 摘要记录转系统消息，无内容时返回 null
     */
    private ChatMessage toChatMessage(ConversationSummaryDO record) {
        if (record == null || StrUtil.isBlank(record.getContent())) {
            return null;
        }
        return new ChatMessage(ChatMessage.Role.SYSTEM, record.getContent());
    }

    /**
     * 计算摘要压缩的起始消息ID（afterId），即上次摘要已覆盖到的位置
     * 优先用摘要记录的 lastMessageId；老数据缺失时降级为按更新时间反查
     * 该时间点之前（含）的最大消息ID，兼容历史版本摘要记录
     */
    private String resolveSummaryStartId(String conversationId, String userId, ConversationSummaryDO summary) {
        if (summary == null) {
            return null;
        }
        if (summary.getLastMessageId() != null) {
            return summary.getLastMessageId();
        }

        Date after = summary.getUpdateTime();
        if (after == null) {
            after = summary.getCreateTime();
        }
        return conversationGroupService.findMaxMessageIdAtOrBefore(conversationId, userId, after);
    }

    /**
     * 取当前滑动窗口内最早一条用户消息的ID，即窗口起点
     * 该位置之前（含）的消息属于窗口外，只能靠摘要承载
     */
    private String resolveHistoryStartId(List<ConversationMessageDO> latestUserTurns) {
        if (CollUtil.isEmpty(latestUserTurns)) {
            return null;
        }

        // 倒序列表的最后一个就是最早的
        ConversationMessageDO oldest = latestUserTurns.get(latestUserTurns.size() - 1);
        return oldest == null ? null : oldest.getId();
    }

    /**
     * 计算本轮摘要的截止消息ID，取滑动窗口的中点位置
     * 倒序列表按索引取中点，使摘要多覆盖约半窗原文，与窗口形成重叠区，便于下轮合并去重
     */
    private String resolveSummaryCutoffId(List<ConversationMessageDO> latestUserTurns) {
        if (CollUtil.isEmpty(latestUserTurns)) {
            return null;
        }

        ConversationMessageDO overlapBoundary = latestUserTurns.get((latestUserTurns.size() - 1) / 2);
        return overlapBoundary == null ? null : overlapBoundary.getId();
    }

    /**
     * 从待压缩消息中取最后一条有效消息的ID，作为新摘要的覆盖位置（lastMessageId）
     */
    private String resolveLastMessageId(List<ConversationMessageDO> toSummarize) {
        for (int i = toSummarize.size() - 1; i >= 0; i--) {
            ConversationMessageDO item = toSummarize.get(i);
            if (item != null && item.getId() != null) {
                return item.getId();
            }
        }
        return null;
    }

    /**
     * 落库新生成的摘要，记录其覆盖到的最后一条消息ID供下轮增量压缩定位
     */
    private void createSummary(String conversationId,
                               String userId,
                               String content,
                               String lastMessageId) {
        ConversationSummaryBO summaryRecord = ConversationSummaryBO.builder()
                .conversationId(conversationId)
                .userId(userId)
                .content(content)
                .lastMessageId(lastMessageId)
                .build();
        conversationMessageService.addMessageSummary(summaryRecord);
    }

    /**
     * 拼接分布式锁的 key，按"用户:会话"维度隔离，保证同一会话全局只有一个节点在压缩
     */
    private String buildLockKey(String conversationId, String userId) {
        return userId.trim() + ":" + conversationId.trim();
    }
}
