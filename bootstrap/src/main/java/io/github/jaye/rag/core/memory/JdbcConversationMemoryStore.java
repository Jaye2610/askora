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
import io.github.jaye.ai.askora.rag.config.MemoryProperties;
import io.github.jaye.ai.askora.rag.core.source.CitationMarkup;
import io.github.jaye.ai.askora.rag.controller.vo.ConversationMessageVO;
import io.github.jaye.ai.askora.framework.convention.ChatMessage;
import io.github.jaye.ai.askora.rag.enums.ConversationMessageOrder;
import io.github.jaye.ai.askora.rag.service.ConversationMessageService;
import io.github.jaye.ai.askora.rag.service.ConversationService;
import io.github.jaye.ai.askora.rag.service.bo.ConversationCreateBO;
import io.github.jaye.ai.askora.rag.service.bo.ConversationMessageBO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
/**
 * 基于 JDBC 的对话记忆存储实现
 * 历史消息直接查库（不做缓存），按滑动窗口只取最近 N 轮对话进入模型上下文
 */
public class JdbcConversationMemoryStore implements ConversationMemoryStore {

    private final ConversationService conversationService;
    private final ConversationMessageService conversationMessageService;
    private final MemoryProperties memoryProperties;

    public JdbcConversationMemoryStore(ConversationService conversationService,
                                       ConversationMessageService conversationMessageService,
                                       MemoryProperties memoryProperties) {
        this.conversationService = conversationService;
        this.conversationMessageService = conversationMessageService;
        this.memoryProperties = memoryProperties;
    }

    /**
     * 按滑动窗口加载最近的历史消息
     * 只取最近 historyKeepTurns 轮（一轮含 user/assistant 两条），超出窗口的旧消息
     * 已由摘要压缩服务收敛为摘要，无需再送入模型上下文
     */
    @Override
    public List<ChatMessage> loadHistory(String conversationId, String userId) {
        int maxMessages = resolveMaxHistoryMessages();
        List<ConversationMessageVO> dbMessages = conversationMessageService.listMessages(
                conversationId,
                userId,
                maxMessages,
                ConversationMessageOrder.DESC
        );
        if (CollUtil.isEmpty(dbMessages)) {
            return List.of();
        }

        List<ChatMessage> result = dbMessages.stream()
                .map(this::toChatMessage)
                .filter(this::isHistoryMessage)
                .collect(Collectors.toList());

        return normalizeHistory(result);
    }

    /**
     * 追加消息到对话历史
     * 用户消息落地时顺带创建或更新会话元信息，便于会话列表展示首句问题和最近活跃时间
     */
    @Override
    public String append(String conversationId, String userId, ChatMessage message) {
        ConversationMessageBO conversationMessage = ConversationMessageBO.builder()
                .conversationId(conversationId)
                .userId(userId)
                .role(message.getRole().name().toLowerCase())
                .content(message.getContent())
                .thinkingContent(message.getThinkingContent())
                .thinkingDuration(message.getThinkingDuration())
                .sources(message.getSources())
                .retrievedChunks(message.getRetrievedChunks())
                .replyToMessageId(message.getReplyToMessageId())
                .messageStatus(message.getMessageStatus() == null ? null : message.getMessageStatus().name())
                .build();
        String messageId = conversationMessageService.addMessage(conversationMessage);

        if (message.getRole() == ChatMessage.Role.USER) {
            ConversationCreateBO conversation = ConversationCreateBO.builder()
                    .conversationId(conversationId)
                    .userId(userId)
                    .question(message.getContent())
                    .lastTime(new Date())
                    .build();
            conversationService.createOrUpdate(conversation);
        }
        return messageId;
    }

    @Override
    public void refreshCache(String conversationId, String userId) {
        // JDBC 直读模式，无需刷新缓存
    }

    /**
     * 数据库记录转对话消息
     * 助手消息需剥离引用标记，避免历史回放时污染上下文
     */
    private ChatMessage toChatMessage(ConversationMessageVO record) {
        if (record == null || StrUtil.isBlank(record.getContent())) {
            return null;
        }
        ChatMessage.Role role = ChatMessage.Role.fromString(record.getRole());
        String content = role == ChatMessage.Role.ASSISTANT
                ? CitationMarkup.strip(record.getContent())
                : record.getContent();
        return new ChatMessage(
                role,
                content
        );
    }

    /**
     * 规整历史消息，剔除开头的孤儿 assistant 消息
     * 窗口截断可能使首轮 user 消息滑出，留下无问独答的助手消息会误导模型
     */
    private List<ChatMessage> normalizeHistory(List<ChatMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return List.of();
        }
        int start = 0;
        while (start < messages.size() && messages.get(start).getRole() == ChatMessage.Role.ASSISTANT) {
            start++;
        }
        if (start >= messages.size()) {
            return List.of();
        }
        return messages.subList(start, messages.size());
    }

    /**
     * 判断消息是否可进入对话记忆，只保留有内容的 user/assistant 消息
     */
    private boolean isHistoryMessage(ChatMessage message) {
        return message != null
                && (message.getRole() == ChatMessage.Role.USER || message.getRole() == ChatMessage.Role.ASSISTANT)
                && StrUtil.isNotBlank(message.getContent());
    }

    /**
     * 将保留轮数换算为消息条数（一轮 = 一问一答两条）
     */
    private int resolveMaxHistoryMessages() {
        int maxTurns = memoryProperties.getHistoryKeepTurns();
        return maxTurns * 2;
    }
}
