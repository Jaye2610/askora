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

package io.github.jaye.ai.askora.infra.chat;

import io.github.jaye.ai.askora.framework.convention.GroundingChunk;
import io.github.jaye.ai.askora.framework.convention.SourceRef;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 透传式 StreamCallback 装饰器
 * <p>
 * onContent / onThinking 直接透传 delegate，onComplete / onError 透传后 CAS-once
 * 触发 {@link #onFinish(boolean, Throwable)}，便于 trace、计量等场景在流式终态做收尾
 */
public abstract class ForwardingStreamCallback implements StreamCallback {

    private final StreamCallback delegate;
    private final AtomicBoolean finished = new AtomicBoolean(false);
    private final AtomicBoolean firstContentSeen = new AtomicBoolean(false);

    protected ForwardingStreamCallback(StreamCallback delegate) {
        this.delegate = delegate;
    }

    /**
     * 透传正文增量，首个片段时触发一次 onFirstContent 钩子
     */
    @Override
    public final void onContent(String content) {
        if (firstContentSeen.compareAndSet(false, true)) {
            try {
                onFirstContent();
            } catch (Throwable ex) {
                // 钩子异常不能影响正常推流
            }
        }
        delegate.onContent(content);
    }

    /**
     * 透传思考内容增量
     */
    @Override
    public final void onThinking(String content) {
        delegate.onThinking(content);
    }

    /**
     * 透传回复消息 id
     */
    @Override
    public final void onReplyToMessageId(String messageId) {
        delegate.onReplyToMessageId(messageId);
    }

    /**
     * 透传引用来源列表
     */
    @Override
    public final void onSources(List<SourceRef> sources) {
        delegate.onSources(sources);
    }

    /**
     * 透传联网搜索溯源块列表
     */
    @Override
    public final void onGroundingChunks(List<GroundingChunk> chunks) {
        delegate.onGroundingChunks(chunks);
    }

    /**
     * 流式响应到达「第一个 onContent」时触发一次，常用于记录用户感知首包 TTFT
     * 默认空实现
     */
    protected void onFirstContent() {
    }

    /**
     * 透传完成信号后触发一次收尾
     */
    @Override
    public final void onComplete() {
        try {
            delegate.onComplete();
        } finally {
            finishOnce(true, null);
        }
    }

    /**
     * 透传异常信号后触发一次收尾
     */
    @Override
    public final void onError(Throwable error) {
        try {
            delegate.onError(error);
        } finally {
            finishOnce(false, error);
        }
    }

    /**
     * 外部路径（如 cancel）触发收尾，不再透传 delegate
     */
    protected final void finishExternally(boolean success, Throwable error) {
        finishOnce(success, error);
    }

    /**
     * CAS 保证终态只触发一次 onFinish
     */
    private void finishOnce(boolean success, Throwable error) {
        if (!finished.compareAndSet(false, true)) {
            return;
        }
        onFinish(success, error);
    }

    /**
     * 流式终态收尾，仅会触发一次
     *
     * @param success 是否成功结束
     * @param error   失败时的异常，成功为 null
     */
    protected abstract void onFinish(boolean success, Throwable error);
}
