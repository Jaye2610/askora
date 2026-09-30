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

import lombok.Getter;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 流式首包探测桥接器
 * <p>
 * 包装下游回调：首个正文/思考增量到达前先缓冲事件，探测成功后统一提交，避免
 * 探测期间的增量丢失或乱序
 */
public final class ProbeStreamBridge implements StreamCallback {

    private final StreamCallback downstream;
    private final CompletableFuture<ProbeResult> probe = new CompletableFuture<>();
    private final Object lock = new Object();
    private final List<Runnable> buffer = new ArrayList<>();
    private volatile boolean committed;

    ProbeStreamBridge(StreamCallback downstream) {
        this.downstream = downstream;
    }

    /**
     * 首个正文增量到达即判定首包成功，事件缓冲或转发下游
     */
    @Override
    public void onContent(String content) {
        probe.complete(ProbeResult.success());
        bufferOrDispatch(() -> downstream.onContent(content));
    }

    /**
     * 思考增量同样视为首包信号，事件缓冲或转发下游
     */
    @Override
    public void onThinking(String content) {
        probe.complete(ProbeResult.success());
        bufferOrDispatch(() -> downstream.onThinking(content));
    }

    /**
     * 未产生任何增量即完成，探测结果记为 NO_CONTENT
     */
    @Override
    public void onComplete() {
        probe.complete(ProbeResult.noContent());
        bufferOrDispatch(downstream::onComplete);
    }

    /**
     * 流异常即完成探测并记录错误
     */
    @Override
    public void onError(Throwable t) {
        probe.complete(ProbeResult.error(t));
        bufferOrDispatch(() -> downstream.onError(t));
    }

    /**
     * 阻塞等待首包探测结果，SUCCESS 时自动提交缓冲
     */
    ProbeResult awaitFirstPacket(long timeout, TimeUnit unit) throws InterruptedException {
        ProbeResult result;
        try {
            result = probe.get(timeout, unit);
        } catch (TimeoutException e) {
            return ProbeResult.timeout();
        } catch (ExecutionException e) {
            return ProbeResult.error(e.getCause());
        }

        if (result.isSuccess()) {
            commit();
        }
        return result;
    }

    /**
     * 提交缓冲：将探测期间暂存的回调事件按序重放到下游
     */
    private void commit() {
        synchronized (lock) {
            if (committed) {
                return;
            }
            committed = true;
            buffer.forEach(Runnable::run);
        }
    }

    /**
     * 已提交则直接转发下游，否则先入缓冲等待探测结果
     */
    private void bufferOrDispatch(Runnable action) {
        boolean dispatchNow;
        synchronized (lock) {
            dispatchNow = committed;
            if (!dispatchNow) {
                buffer.add(action);
            }
        }
        if (dispatchNow) {
            action.run();
        }
    }

    /**
     * 探测结果
     */
    @Getter
    public static class ProbeResult {

        enum Type {SUCCESS, ERROR, TIMEOUT, NO_CONTENT}

        private final Type type;
        private final Throwable error;

        private ProbeResult(Type type, Throwable error) {
            this.type = type;
            this.error = error;
        }

        /**
         * 探测成功（已收到首个增量）
         */
        static ProbeResult success() {
            return new ProbeResult(Type.SUCCESS, null);
        }

        /**
         * 探测失败（流异常）
         */
        static ProbeResult error(Throwable t) {
            return new ProbeResult(Type.ERROR, t);
        }

        /**
         * 探测超时
         */
        static ProbeResult timeout() {
            return new ProbeResult(Type.TIMEOUT, null);
        }

        /**
         * 流正常结束但未产生任何增量
         */
        static ProbeResult noContent() {
            return new ProbeResult(Type.NO_CONTENT, null);
        }

        /**
         * 是否探测成功
         */
        boolean isSuccess() {
            return type == Type.SUCCESS;
        }
    }
}
