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

package io.github.jaye.ai.askora.infra.model;

import io.github.jaye.ai.askora.infra.config.AIModelProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 模型健康状态存储器
 * 用于管理和跟踪各个 AI 模型的健康状况，实现断路器模式
 * <p>
 * 每个模型拥有 CLOSED（正常）/ OPEN（熔断打开）/ HALF_OPEN（半开试探）三态：
 * <ul>
 *   <li>CLOSED：正常工作，连续失败达到阈值后转为 OPEN，并冷却 openDurationMs；</li>
 *   <li>OPEN：熔断窗口内拒绝一切调用，窗口结束后的第一个请求转入 HALF_OPEN 进行探活；</li>
 *   <li>HALF_OPEN：仅放行一个探测请求（halfOpenInFlight 互斥），成功则恢复 CLOSED，失败则回到 OPEN。</li>
 * </ul>
 * 状态存储于并发安全的 {@link ConcurrentHashMap} 中，按 targetId 独立隔离，避免其它模型被牵连熔断。
 */
@Component
@RequiredArgsConstructor
public class ModelHealthStore {

    private final AIModelProperties properties;

    private final Map<String, ModelHealth> healthById = new ConcurrentHashMap<>();

    private final AtomicLong probeTokenSeq = new AtomicLong();

    /**
     * 模型调用许可，halfOpenToken 为 0 时不持有半开探测名额
     */
    public record CallPermit(String modelId, long halfOpenToken) {
    }

    /**
     * 判断某个模型当前是否不可用（熔断期禁止调用的简化查询）
     *
     * @param id 模型 targetId
     * @return true 表示正处于熔断冷却期，或半开探测名额已被占用，不被放行调用
     */
    public boolean isUnavailable(String id) {
        ModelHealth health = healthById.get(id);
        if (health == null) {
            return false;
        }
        // OPEN 且未过冷却窗口：熔断生效中
        if (health.state == State.OPEN && health.openUntil > System.currentTimeMillis()) {
            return true;
        }
        // HALF_OPEN 且侦察请求在途：只允许一个探活请求，其余视为不可用
        return health.state == State.HALF_OPEN && health.halfOpenInFlight;
    }

    /**
     * 申请一次模型调用许可，返回 null 表示熔断拒绝放行
     * <p>
     * 在并发安全的前提下完成三态迁移：
     * <ul>
     *   <li>CLOSED：直接放行（halfOpenToken=0，普通许可）；</li>
     *   <li>OPEN 且已过冷却窗口：转入 HALF_OPEN 并占据探活名额，作为第一个探测请求放行；</li>
     *   <li>OPEN 仍在冷却 / HALF_OPEN 探活已被占用：返回 null 拒绝本次调用。</li>
     * </ul>
     *
     * @param id 模型 targetId
     * @return 调用许可；null 表示该模型当前被熔断，不应发起调用
     */
    public CallPermit allowCall(String id) {
        if (id == null) {
            return null;
        }
        long now = System.currentTimeMillis();
        AtomicReference<CallPermit> granted = new AtomicReference<>();
        healthById.compute(id, (k, v) -> {
            if (v == null) {
                v = new ModelHealth();
            }
            if (v.state == State.OPEN) {
                // 熔断窗口未过：继续拒绝
                if (v.openUntil > now) {
                    return v;
                }
                // 冷却结束：首个请求抢占探活名额，转入半开试探
                v.state = State.HALF_OPEN;
                v.halfOpenInFlight = true;
                v.halfOpenToken = probeTokenSeq.incrementAndGet();
                granted.set(new CallPermit(id, v.halfOpenToken));
                return v;
            }
            if (v.state == State.HALF_OPEN) {
                // 已有探测请求在途：拒绝其余并发请求，避免探活被并发冲垮
                if (v.halfOpenInFlight) {
                    return v;
                }
                // 探活名额可抢占（通常是上一次探测已释放）：再次接管
                v.halfOpenInFlight = true;
                v.halfOpenToken = probeTokenSeq.incrementAndGet();
                granted.set(new CallPermit(id, v.halfOpenToken));
                return v;
            }
            // CLOSED 正常态：无熔断限制，直接放行
            granted.set(new CallPermit(id, 0L));
            return v;
        });
        return granted.get();
    }

    /**
     * 记录一次调用成功，使模型恢复健康（关闭熔断、清零连续失败计数）
     */
    public void markSuccess(String id) {
        if (id == null) {
            return;
        }
        healthById.compute(id, (k, v) -> {
            if (v == null) {
                return new ModelHealth();
            }
            // 半开探测成功：回到 CLOSED，复位所有熔断状态
            v.state = State.CLOSED;
            v.consecutiveFailures = 0;
            v.openUntil = 0L;
            v.halfOpenInFlight = false;
            return v;
        });
    }

    /**
     * 记录一次调用失败并按策略推进熔断状态
     * <ul>
     *   <li>半开探测失败：立即转回 OPEN，重新进入冷却窗口，等待下一次探活；</li>
     *   <li>正常态连续失败：累计计数，达到 failureThreshold 阈值后转 OPEN，同样进入冷却。</li>
     * </ul>
     */
    public void markFailure(String id) {
        if (id == null) {
            return;
        }
        long now = System.currentTimeMillis();
        healthById.compute(id, (k, v) -> {
            if (v == null) {
                v = new ModelHealth();
            }
            if (v.state == State.HALF_OPEN) {
                // 半开探测失败：模型仍有病，回退到 OPEN 再冷却一个窗口
                v.state = State.OPEN;
                v.openUntil = now + properties.getSelection().getOpenDurationMs();
                v.consecutiveFailures = 0;
                v.halfOpenInFlight = false;
                return v;
            }
            // CLOSED 下的失败：累加连续失败次数
            v.consecutiveFailures++;
            // 连续失败达到阈值：打开熔断，进入冷却窗口
            if (v.consecutiveFailures >= properties.getSelection().getFailureThreshold()) {
                v.state = State.OPEN;
                v.openUntil = now + properties.getSelection().getOpenDurationMs();
                v.consecutiveFailures = 0;
            }
            return v;
        });
    }

    /**
     * 仅释放当前凭证持有的半开探测名额
     */
    public void releaseHalfOpenPermit(CallPermit permit) {
        if (permit == null || permit.halfOpenToken() <= 0L) {
            return;
        }
        healthById.computeIfPresent(permit.modelId(), (k, v) -> {
            if (v.state == State.HALF_OPEN && v.halfOpenInFlight && v.halfOpenToken == permit.halfOpenToken()) {
                v.halfOpenInFlight = false;
            }
            return v;
        });
    }

    private static class ModelHealth {
        private int consecutiveFailures;
        private long openUntil;
        private boolean halfOpenInFlight;
        private long halfOpenToken;
        private State state;

        private ModelHealth() {
            this.consecutiveFailures = 0;
            this.openUntil = 0L;
            this.halfOpenInFlight = false;
            this.halfOpenToken = 0L;
            this.state = State.CLOSED;
        }
    }

    private enum State {
        CLOSED,
        OPEN,
        HALF_OPEN
    }
}
