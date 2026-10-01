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

package io.github.jaye.ai.askora.rag.service.ratelimit;

import cn.dev33.satoken.stp.StpUtil;
import com.alibaba.ttl.TtlRunnable;
import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import io.github.jaye.ai.askora.framework.convention.ChatMessage;
import io.github.jaye.ai.askora.framework.context.UserContext;
import io.github.jaye.ai.askora.rag.service.ratelimit.FairDistributedRateLimiter.AcquireRequest;
import io.github.jaye.ai.askora.framework.web.SseEmitterSender;
import io.github.jaye.ai.askora.rag.config.MemoryProperties;
import io.github.jaye.ai.askora.rag.config.RAGRateLimitProperties;
import io.github.jaye.ai.askora.rag.core.memory.ConversationMemoryService;
import io.github.jaye.ai.askora.rag.dto.CompletionPayload;
import io.github.jaye.ai.askora.rag.dto.MessageDelta;
import io.github.jaye.ai.askora.rag.dto.MetaPayload;
import io.github.jaye.ai.askora.rag.enums.SSEEventType;
import io.github.jaye.ai.askora.rag.service.ConversationGroupService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.logging.log4j.util.Strings;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

/**
 * SSE 全局并发限流入口
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChatQueueLimiter {

    private static final String REJECT_MESSAGE = "系统繁忙，请稍后再试";
    private static final String RESPONSE_TYPE = "response";

    private final FairDistributedRateLimiter chatRateLimiter;
    private final Executor chatEntryExecutor;
    private final RAGRateLimitProperties rateLimitProperties;
    private final ConversationMemoryService memoryService;
    private final ConversationGroupService conversationGroupService;
    private final MemoryProperties memoryProperties;

    /**
     * 对话请求入队：先经全局并发限流，获取 permit 后再执行真正的对话任务
     * <p>
     * 限流总开关关闭时直通提交线程池；排队等待通过 TTL 上下文透传保证异步分支用户上下文不丢，
     * SSE 连接完结/超时/异常时触发取消以释放排队名额，超时则走 reject 流程
     */
    public void enqueue(String question, String conversationId, SseEmitter emitter, Runnable onAcquire) {
        if (!Boolean.TRUE.equals(rateLimitProperties.getGlobalEnabled())) {
            try {
                chatEntryExecutor.execute(onAcquire);
            } catch (RejectedExecutionException ex) {
                log.warn("直通分支线程池拒绝任务，转 reject 流程", ex);
                handleReject(question, conversationId, emitter);
            }
            return;
        }

        chatRateLimiter.acquire(AcquireRequest.builder()
                .maxWaitMillis(TimeUnit.SECONDS.toMillis(rateLimitProperties.getGlobalMaxWaitSeconds()))
                .onAcquired(TtlRunnable.get(onAcquire))
                .onTimeout(TtlRunnable.get(() -> handleReject(question, conversationId, emitter)))
                .onAcquiredExecutor(chatEntryExecutor)
                .cancelBinder(cancel -> {
                    emitter.onCompletion(cancel);
                    emitter.onTimeout(cancel);
                    emitter.onError(e -> cancel.run());
                })
                .build());
    }

    // ==================== Reject 业务 ====================

    /**
     * 拒绝处理：把被拒问答落库为一条 REJECTED 消息，再向前端推送 reject 事件并结束 SSE
     */
    private void handleReject(String question, String conversationId, SseEmitter emitter) {
        RejectedContext context = null;
        try {
            context = recordRejectedConversation(question, conversationId, resolveUserId());
        } catch (Exception ex) {
            // 记录失败不能阻塞 emitter，否则前端永远收不到 DONE
            log.warn("记录 reject 会话失败，仍向前端发送 DONE", ex);
        }
        sendRejectEvents(emitter, context);
    }

    /**
     * 记录被拒会话：问题与拒答消息成对落库；新会话时补标题（优先取 LLM 生成结果，失败则截断问题兜底）
     */
    private RejectedContext recordRejectedConversation(String question, String conversationId, String userId) {
        if (StrUtil.isBlank(question) || StrUtil.isBlank(userId)) {
            return null;
        }

        String actualConversationId;
        boolean isNewConversation;
        if (StrUtil.isBlank(conversationId)) {
            // 入参未带 conversationId：刚生成的雪花 ID 不可能命中已有会话，跳过 existence 查询
            actualConversationId = IdUtil.getSnowflakeNextIdStr();
            isNewConversation = true;
        } else {
            actualConversationId = conversationId;
            isNewConversation = conversationGroupService.findConversation(actualConversationId, userId) == null;
        }

        String questionMessageId = memoryService.append(actualConversationId, userId, ChatMessage.user(question));
        ChatMessage rejectedMessage = ChatMessage.assistant(REJECT_MESSAGE);
        rejectedMessage.setReplyToMessageId(questionMessageId);
        rejectedMessage.setMessageStatus(ChatMessage.MessageStatus.REJECTED);
        String messageId = memoryService.append(actualConversationId, userId, rejectedMessage);

        String title = Strings.EMPTY;
        if (isNewConversation) {
            // append(USER) 内部会触发 conversationService.createOrUpdate（含 LLM 生成标题），此处回查拿到生成结果
            var conversation = conversationGroupService.findConversation(actualConversationId, userId);
            title = conversation != null ? conversation.getTitle() : Strings.EMPTY;
            if (StrUtil.isBlank(title)) {
                title = buildFallbackTitle(question);
            }
        }
        String taskId = IdUtil.getSnowflakeNextIdStr();
        return new RejectedContext(actualConversationId, taskId, messageId, title);
    }

    /**
     * 标题兜底：截取问题前 N 个字符作为会话标题
     */
    private String buildFallbackTitle(String question) {
        if (StrUtil.isBlank(question)) {
            return Strings.EMPTY;
        }
        int maxLen = memoryProperties.getTitleMaxLength() != null ? memoryProperties.getTitleMaxLength() : 30;
        String cleaned = question.trim();
        return cleaned.length() <= maxLen ? cleaned : cleaned.substring(0, maxLen);
    }

    /**
     * 按前端协议依次推送 META、REJECT、FINISH、DONE 事件并完结 SSE 连接
     */
    private void sendRejectEvents(SseEmitter emitter, RejectedContext rejectedContext) {
        SseEmitterSender sender = new SseEmitterSender(emitter);
        if (rejectedContext != null) {
            sender.sendEvent(SSEEventType.META.value(), new MetaPayload(rejectedContext.conversationId, rejectedContext.taskId));
            sender.sendEvent(SSEEventType.REJECT.value(), new MessageDelta(RESPONSE_TYPE, REJECT_MESSAGE));
            sender.sendEvent(SSEEventType.FINISH.value(),
                    new CompletionPayload(String.valueOf(rejectedContext.messageId), rejectedContext.title,
                            null, ChatMessage.MessageStatus.REJECTED));
        }
        sender.sendEvent(SSEEventType.DONE.value(), "[DONE]");
        sender.complete();
    }

    /**
     * 解析用户 ID：优先取用户上下文，缺失时回退 Sa-Token 会话
     */
    private String resolveUserId() {
        String userId = UserContext.getUserId();
        if (StrUtil.isNotBlank(userId)) {
            return userId;
        }
        try {
            return StpUtil.getLoginIdAsString();
        } catch (Exception ignored) {
            return null;
        }
    }

    /**
     * 拒绝上下文：SSE 事件推送所需的会话与消息元数据
     */
    private record RejectedContext(String conversationId, String taskId, String messageId, String title) {
    }
}
