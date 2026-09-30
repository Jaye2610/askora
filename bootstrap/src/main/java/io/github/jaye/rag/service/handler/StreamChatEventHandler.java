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

package io.github.jaye.ai.askora.rag.service.handler;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import io.github.jaye.ai.askora.rag.dao.entity.ConversationDO;
import io.github.jaye.ai.askora.rag.dto.CompletionPayload;
import io.github.jaye.ai.askora.rag.dto.MessageDelta;
import io.github.jaye.ai.askora.rag.dto.MetaPayload;
import io.github.jaye.ai.askora.rag.enums.SSEEventType;
import io.github.jaye.ai.askora.framework.context.UserContext;
import io.github.jaye.ai.askora.framework.convention.ChatMessage;
import io.github.jaye.ai.askora.framework.convention.GroundingChunk;
import io.github.jaye.ai.askora.framework.convention.SourceRef;
import io.github.jaye.ai.askora.framework.web.SseEmitterSender;
import io.github.jaye.ai.askora.infra.chat.StreamCallback;
import io.github.jaye.ai.askora.infra.config.AIModelProperties;
import io.github.jaye.ai.askora.rag.core.memory.ConversationMemoryService;
import lombok.extern.slf4j.Slf4j;
import io.github.jaye.ai.askora.rag.service.ConversationGroupService;

import java.util.List;
import java.util.Optional;

/**
 * 流式聊天事件处理器（{@link StreamCallback} 实现，RAG 主链路的高频入口）
 * <p>
 * 职责是把 LLM 流式输出的各个回调翻译成对前端的 SSE 事件序列，同时承担「回答落库」这一业务动作：
 * <ul>
 *   <li>会话开始时推送 META（回传 conversationId、taskId）并把任务注册进 {@link StreamTaskManager}，为跨实例取消做准备</li>
 *   <li>流式过程中把「思考」与「回复」增量分别缓冲，再按 messageChunkSize（字符数）分批推送 MESSAGE 事件，驱动前端流式渲染</li>
 *   <li>来源（sources）与 grounding 片段不随 MESSAGE 实时下发，而是先暂存，等生成结束后随 FINISH 一并下发，
 *       并随 assistant 消息一起落库（供后续推荐追问等场景复用）</li>
 *   <li>生成完成（onComplete）时把累积的回答（含思考、来源、grounding）持久化为一条 assistant 消息，
 *       再推送 FINISH 与 DONE 收尾；被取消或出错时则以 INTERRUPTED 状态兜底落库并同样收尾</li>
 * </ul>
 * 每个回调入口都会先做 {@link StreamTaskManager#isCancelled} 检查：一旦任务被取消，立即停止一切推送与落库动作。
 */
@Slf4j
public class StreamChatEventHandler implements StreamCallback {

    private static final String TYPE_THINK = "think";
    private static final String TYPE_RESPONSE = "response";

    private final int messageChunkSize;
    private final SseEmitterSender sender;
    private final String conversationId;
    private final ConversationMemoryService memoryService;
    private final ConversationGroupService conversationGroupService;
    private final String taskId;
    private final String userId;
    private final StreamTaskManager taskManager;
    private final boolean sendTitleOnComplete;
    private final StringBuilder answer = new StringBuilder();
    private final StringBuilder thinking = new StringBuilder();
    private long thinkingStartMs;
    private int thinkingDurationSeconds;
    private List<SourceRef> sources;
    private List<GroundingChunk> groundingChunks;
    private String replyToMessageId;

    /**
     * 使用参数对象构造（推荐）
     * <p>
     * 从参数对象中取出与本次流式会话相关的依赖与标识（会话/任务/用户），
     * 读取模型配置算出消息分批大小，并判断本次是否需要补发会话标题（新建会话才需要）；
     * 构造末尾随即推送 META 事件并注册任务，让前端第一时间拿到可用的会话标识。
     *
     * @param params 构建参数
     */
    public StreamChatEventHandler(StreamChatHandlerParams params) {
        this.sender = new SseEmitterSender(params.getEmitter());
        this.conversationId = params.getConversationId();
        this.taskId = params.getTaskId();
        this.memoryService = params.getMemoryService();
        this.conversationGroupService = params.getConversationGroupService();
        this.taskManager = params.getTaskManager();
        this.userId = UserContext.getUserId();

        // 计算配置：每条 MESSAGE 事件承载的字符数，控制前端流式渲染的刷新粒度
        this.messageChunkSize = resolveMessageChunkSize(params.getModelProperties());
        // 是否补发标题：仅当会话尚不存在或尚未生成标题时才需要
        this.sendTitleOnComplete = shouldSendTitle();

        // 初始化：先推送 META 让前端建立会话上下文，再把本任务注册进流式任务管理器（供跨实例取消）
        initialize();
    }

    /**
     * 初始化：发送元数据事件（META，回传 conversationId/taskId）并注册任务
     * <p>
     * 注册时登记了一个「取消兜底回调」：若任务被取消时已累积了部分回答，能据此先把已有内容落库再收尾。
     */
    private void initialize() {
        sender.sendEvent(SSEEventType.META.value(), new MetaPayload(conversationId, taskId));
        taskManager.register(taskId, sender, this::buildCompletionPayloadOnCancel);
    }

    /**
     * 解析消息块大小
     *
     * @return 每条 MESSAGE 事件推送的字符数，至少 1，缺省 5
     */
    private int resolveMessageChunkSize(AIModelProperties modelProperties) {
        return Math.max(1, Optional.ofNullable(modelProperties.getStream())
                .map(AIModelProperties.Stream::getMessageChunkSize)
                .orElse(5));
    }

    /**
     * 判断是否需要发送标题
     *
     * @return 会话为新会话（尚不存在）或标题为空时为 true
     */
    private boolean shouldSendTitle() {
        ConversationDO existingConversation = conversationGroupService.findConversation(
                conversationId,
                userId
        );
        return existingConversation == null || StrUtil.isBlank(existingConversation.getTitle());
    }

    /**
     * 构造取消时的完成载荷（如果有内容则先落库）
     * <p>
     * 用户中断/取消时会走到这里：把已经流式累积的回答封装成一条被标记为 {@code INTERRUPTED} 的
     * assistant 消息先落库（越界不让你丢掉已生成内容），再连同消息 ID、标题、来源组装成
     * 完成载荷，供推送 CANCEL 事件时使用。
     */
    private CompletionPayload buildCompletionPayloadOnCancel() {
        String content = answer.toString();
        String messageId = null;
        if (StrUtil.isNotBlank(content)) {
            try {
                String thinkingContent = thinking.isEmpty() ? null : thinking.toString();
                ChatMessage message = ChatMessage.assistant(content, thinkingContent, resolveThinkingDuration());
                message.setSources(sources);
                message.setRetrievedChunks(groundingChunks);
                message.setReplyToMessageId(replyToMessageId);
                message.setMessageStatus(ChatMessage.MessageStatus.INTERRUPTED);
                messageId = memoryService.append(conversationId, userId, message);
            } catch (Exception e) {
                log.error("取消时持久化消息失败，conversationId：{}", conversationId, e);
            }
        }
        String title = resolveTitleForEvent();
        String messageIdText = StrUtil.isBlank(messageId) ? null : messageId;
        return new CompletionPayload(messageIdText, title, sources, ChatMessage.MessageStatus.INTERRUPTED);
    }

    /**
     * 记录本次回答所回复的源消息 ID（用于界面上的「回复某条消息」关系）
     */
    @Override
    public void onReplyToMessageId(String messageId) {
        this.replyToMessageId = messageId;
    }

    /**
     * 收到检索来源（sources）回调
     * <p>
     * 来源不随 MESSAGE 实时下发，而是暂存到这里，等到生成结束后随 FINISH 事件一并推给前端，
     * 同时作为本回答的引用一并落库。
     */
    @Override
    public void onSources(List<SourceRef> sources) {
        if (taskManager.isCancelled(taskId)) {
            return;
        }
        if (CollUtil.isEmpty(sources)) {
            return;
        }
        // 暂存来源 随完成事件（finish）一并下发并落库
        this.sources = sources;
    }

    /**
     * 收到检索 grounding 片段（原文支撑片段）回调
     * <p>
     * 同样暂存不实时推送，待 assistant 消息落库时一并保存，
     * 供后续「推荐追问」「引用溯源」等场景复用这些支撑证据。
     */
    @Override
    public void onGroundingChunks(List<GroundingChunk> chunks) {
        if (taskManager.isCancelled(taskId)) {
            return;
        }
        if (CollUtil.isEmpty(chunks)) {
            return;
        }
        // 暂存 grounding 片段 随 assistant 消息一并落库 供后续推荐追问生成 grounding
        this.groundingChunks = chunks;
    }

    /**
     * 收到回答正文增量（response chunk）
     * <p>
     * 先把增量缓冲到本回答的 StringBuilder（毕竟落库时要用到完整回答），
     * 再按 messageChunkSize 拆分推 MESSAGE 事件驱动前端流式渲染。
     */
    @Override
    public void onContent(String chunk) {
        if (taskManager.isCancelled(taskId)) {
            return;
        }
        if (StrUtil.isBlank(chunk)) {
            return;
        }
        if (thinkingStartMs > 0 && thinkingDurationSeconds == 0) {
            // 首次收到正文时结算思考耗时（思考段结束即正文段开始），用于落库/展示
            thinkingDurationSeconds = Math.max(1, Math.round((System.currentTimeMillis() - thinkingStartMs) / 1000.0f));
        }
        answer.append(chunk);
        sendChunked(TYPE_RESPONSE, chunk);
    }

    /**
     * 收到思考过程增量（think chunk）
     * <p>
     * 记录思考开始时间用于统计思考耗时，缓冲思考内容并向前端推送思考流。
     */
    @Override
    public void onThinking(String chunk) {
        if (taskManager.isCancelled(taskId)) {
            return;
        }
        if (StrUtil.isBlank(chunk)) {
            return;
        }
        if (thinkingStartMs == 0) {
            // 思考段首个增量到达时启动计时
            thinkingStartMs = System.currentTimeMillis();
        }
        thinking.append(chunk);
        sendChunked(TYPE_THINK, chunk);
    }

    /**
     * 生成完成回调：持久化 assistant 消息并收尾
     * <p>
     * 1) 把完整回答（含思考、来源、grounding、回复指向）落库为一条 NORMAL 状态的 assistant 消息；
     * 2) 推送 FINISH（带消息 ID、可能补发的标题、来源）；
     * 3) 推送 DONE 结束流；
     * 4) 从任务管理器注销并关闭 SSE。
     */
    @Override
    public void onComplete() {
        if (taskManager.isCancelled(taskId)) {
            return;
        }
        String messageId = null;
        try {
            String thinkingContent = thinking.isEmpty() ? null : thinking.toString();
            ChatMessage message = ChatMessage.assistant(answer.toString(), thinkingContent, resolveThinkingDuration());
            message.setSources(sources);
            message.setRetrievedChunks(groundingChunks);
            message.setReplyToMessageId(replyToMessageId);
            message.setMessageStatus(ChatMessage.MessageStatus.NORMAL);
            // 完整回答落库，返回消息 ID 用于前端关联该条回答
            messageId = memoryService.append(conversationId, userId, message);
        } catch (Exception e) {
            log.error("对话完成时持久化消息失败，conversationId：{}", conversationId, e);
        }
        String title = resolveTitleForEvent();
        String messageIdText = StrUtil.isBlank(messageId) ? null : messageId;
        sender.sendEvent(SSEEventType.FINISH.value(),
                new CompletionPayload(messageIdText, title, sources, ChatMessage.MessageStatus.NORMAL));
        sender.sendEvent(SSEEventType.DONE.value(), "[DONE]");
        taskManager.unregister(taskId);
        sender.complete();
    }

    /**
     * 出错回调：注销任务并以失败事件收尾（前端据此展示错误态）
     */
    @Override
    public void onError(Throwable t) {
        if (taskManager.isCancelled(taskId)) {
            return;
        }
        taskManager.unregister(taskId);
        sender.fail(t);
    }

    /**
     * 按 messageChunkSize 把增量内容切成若干段，逐段推送 MESSAGE 事件
     * <p>
     * 以 Unicode 码点为单位切分，避免把多字节字符拦腰截断成乱码；
     * 末尾不足一块的剩余内容也要补推一条，保证前端能收到全部字符。
     */
    private void sendChunked(String type, String content) {
        int length = content.length();
        int idx = 0;
        int count = 0;
        StringBuilder buffer = new StringBuilder();
        while (idx < length) {
            int codePoint = content.codePointAt(idx);
            buffer.appendCodePoint(codePoint);
            idx += Character.charCount(codePoint);
            count++;
            if (count >= messageChunkSize) {
                sender.sendEvent(SSEEventType.MESSAGE.value(), new MessageDelta(type, buffer.toString()));
                buffer.setLength(0);
                count = 0;
            }
        }
        if (!buffer.isEmpty()) {
            sender.sendEvent(SSEEventType.MESSAGE.value(), new MessageDelta(type, buffer.toString()));
        }
    }

    /**
     * 返回思考耗时（秒）
     *
     * @return 有思考段时返回耗时秒数，否则返回 null
     */
    private Integer resolveThinkingDuration() {
        return thinkingDurationSeconds > 0 ? thinkingDurationSeconds : null;
    }

    /**
     * 结算完成事件里携带的标题
     * <p>
     * 仅对「新会话需要补发标题」的场景生效：若会话标题已由标题生成器写好则下发该标题，
     * 否则兜底为「新对话」；老会话/已有标题的会话返回 null（无需再发）。
     */
    private String resolveTitleForEvent() {
        if (!sendTitleOnComplete) {
            return null;
        }
        ConversationDO conversation = conversationGroupService.findConversation(conversationId, userId);
        if (conversation != null && StrUtil.isNotBlank(conversation.getTitle())) {
            return conversation.getTitle();
        }
        return "新对话";
    }
}
