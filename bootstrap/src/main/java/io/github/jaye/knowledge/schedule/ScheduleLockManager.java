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

package io.github.jaye.ai.askora.knowledge.schedule;

import cn.hutool.core.thread.ThreadFactoryBuilder;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import io.github.jaye.ai.askora.knowledge.config.KnowledgeScheduleProperties;
import io.github.jaye.ai.askora.knowledge.dao.entity.KnowledgeDocumentScheduleDO;
import io.github.jaye.ai.askora.knowledge.dao.mapper.KnowledgeDocumentScheduleMapper;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.util.Date;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 定时刷新任务分布式锁管理器
 * <p>
 * 基于"锁持有者 + 锁到期时间"两列实现数据库乐观锁，配合心跳续约防止长任务执行中锁过期被其他实例抢占
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ScheduleLockManager {

    private final KnowledgeDocumentScheduleMapper scheduleMapper;
    private final KnowledgeScheduleProperties scheduleProperties;

    private final String instancePrefix = resolveInstancePrefix();
    private final ScheduledExecutorService heartbeatExecutor = Executors.newSingleThreadScheduledExecutor(
            ThreadFactoryBuilder.create()
                    .setNamePrefix("kb_schedule_lock_heartbeat_")
                    .setDaemon(true)
                    .build()
    );

    /**
     * 尝试获取指定任务的锁
     * <p>
     * 条件更新保证只有锁已过期或未持有者才能抢锁成功，返回 null 表示被其他实例持有
     */
    public ScheduleLockLease tryAcquire(String scheduleId, Date now) {
        ScheduleLockLease lease = new ScheduleLockLease(scheduleId, nextLockToken());
        int updated = scheduleMapper.update(
                Wrappers.lambdaUpdate(KnowledgeDocumentScheduleDO.class)
                        .set(KnowledgeDocumentScheduleDO::getLockOwner, lease.lockToken())
                        .set(KnowledgeDocumentScheduleDO::getLockUntil, computeLockUntil())
                        .eq(KnowledgeDocumentScheduleDO::getId, scheduleId)
                        .and(w -> w.isNull(KnowledgeDocumentScheduleDO::getLockUntil)
                                .or()
                                .lt(KnowledgeDocumentScheduleDO::getLockUntil, now))
        );
        return updated > 0 ? lease : null;
    }

    /**
     * 持有令牌续约锁，延长到期时间
     */
    public boolean renew(ScheduleLockLease lease) {
        if (lease == null) {
            return false;
        }
        return scheduleMapper.update(
                Wrappers.lambdaUpdate(KnowledgeDocumentScheduleDO.class)
                        .set(KnowledgeDocumentScheduleDO::getLockUntil, computeLockUntil())
                        .eq(KnowledgeDocumentScheduleDO::getId, lease.scheduleId())
                        .eq(KnowledgeDocumentScheduleDO::getLockOwner, lease.lockToken())
        ) > 0;
    }

    /**
     * 释放锁，仅当令牌匹配时才清空持有者与到期时间
     */
    public boolean release(ScheduleLockLease lease) {
        if (lease == null) {
            return false;
        }
        return scheduleMapper.update(
                Wrappers.lambdaUpdate(KnowledgeDocumentScheduleDO.class)
                        .set(KnowledgeDocumentScheduleDO::getLockOwner, null)
                        .set(KnowledgeDocumentScheduleDO::getLockUntil, null)
                        .eq(KnowledgeDocumentScheduleDO::getId, lease.scheduleId())
                        .eq(KnowledgeDocumentScheduleDO::getLockOwner, lease.lockToken())
        ) > 0;
    }

    /**
     * 为租约启动后台心跳定时器，周期性续约锁
     */
    public ScheduleLockHeartbeat startHeartbeat(ScheduleLockLease lease) {
        long now = System.currentTimeMillis();
        ScheduleLockHeartbeat heartbeat = new ScheduleLockHeartbeat(lease, now, effectiveLockMillis());
        long intervalMillis = computeHeartbeatIntervalMillis();
        ScheduledFuture<?> future = heartbeatExecutor.scheduleWithFixedDelay(
                () -> doHeartbeat(heartbeat),
                intervalMillis,
                intervalMillis,
                TimeUnit.MILLISECONDS
        );
        heartbeat.bind(future);
        return heartbeat;
    }

    /**
     * 计算锁的到期时间（当前时间 + 有效锁时长）
     */
    public Date computeLockUntil() {
        return new Date(System.currentTimeMillis() + effectiveLockMillis());
    }

    /**
     * 执行一次心跳续约，续约失败超过锁 TTL 的安全窗口后标记锁丢失
     */
    private void doHeartbeat(ScheduleLockHeartbeat heartbeat) {
        if (heartbeat.isClosed() || heartbeat.isLost()) {
            return;
        }
        try {
            if (renew(heartbeat.lease())) {
                heartbeat.markRenewed();
                return;
            }
            heartbeat.markLost();
            log.warn("定时刷新锁已丢失: scheduleId={}, lockToken={}",
                    heartbeat.lease().scheduleId(), heartbeat.lease().lockToken());
        } catch (Exception e) {
            if (heartbeat.isExpiredWithoutConfirmation()) {
                heartbeat.markLost();
                log.warn("定时刷新锁续约失败且已超过安全窗口: scheduleId={}, lockToken={}",
                        heartbeat.lease().scheduleId(), heartbeat.lease().lockToken(), e);
            } else {
                log.warn("定时刷新锁续约失败，将继续重试: scheduleId={}, lockToken={}",
                        heartbeat.lease().scheduleId(), heartbeat.lease().lockToken(), e);
            }
        }
    }

    /**
     * 计算心跳间隔：锁时长的三分之一，并限制在 5~60 秒之间
     */
    private long computeHeartbeatIntervalMillis() {
        long effectiveLockSeconds = effectiveLockSeconds();
        long intervalSeconds = Math.max(5, Math.min(effectiveLockSeconds / 3, 60));
        return intervalSeconds * 1000;
    }

    /**
     * 有效锁时长（毫秒）
     */
    private long effectiveLockMillis() {
        return effectiveLockSeconds() * 1000;
    }

    /**
     * 有效锁时长（秒），下限 60 秒防止配置过短
     */
    private long effectiveLockSeconds() {
        return Math.max(scheduleProperties.getLockSeconds(), 60L);
    }

    /**
     * 生成锁令牌：实例前缀 + 随机 UUID
     */
    private String nextLockToken() {
        return instancePrefix + ":" + UUID.randomUUID();
    }

    /**
     * 解析实例前缀（主机名 + 随机 UUID），用于标识锁持有实例
     */
    private static String resolveInstancePrefix() {
        String host;
        try {
            host = InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            host = "unknown";
        }
        return "kb-schedule-" + host + "-" + UUID.randomUUID();
    }

    /**
     * 应用关闭时停止心跳线程池
     */
    @PreDestroy
    public void shutdown() {
        heartbeatExecutor.shutdownNow();
    }

    /**
     * 锁心跳句柄：跟踪租约的续约状态，锁丢失或手动关闭后停止定时任务
     */
    public static final class ScheduleLockHeartbeat implements AutoCloseable {

        private final ScheduleLockLease lease;
        private final long lockTtlMillis;
        private final AtomicBoolean lost = new AtomicBoolean(false);
        private final AtomicBoolean closed = new AtomicBoolean(false);
        private final AtomicLong lastConfirmedAt = new AtomicLong();
        private volatile ScheduledFuture<?> future;

        private ScheduleLockHeartbeat(ScheduleLockLease lease, long startAt, long lockTtlMillis) {
            this.lease = lease;
            this.lockTtlMillis = lockTtlMillis;
            this.lastConfirmedAt.set(startAt);
        }

        /**
         * 绑定底层心跳定时任务，供后续取消
         */
        private void bind(ScheduledFuture<?> future) {
            this.future = future;
        }

        /**
         * 获取当前租约
         */
        public ScheduleLockLease lease() {
            return lease;
        }

        /**
         * 锁是否已丢失（被其他实例抢占或续约超时）
         */
        public boolean isLost() {
            return lost.get();
        }

        /**
         * 心跳是否已关闭
         */
        private boolean isClosed() {
            return closed.get();
        }

        /**
         * 记录本次续约成功的时间
         */
        private void markRenewed() {
            lastConfirmedAt.set(System.currentTimeMillis());
        }

        /**
         * 距上次确认续约是否已超过锁 TTL（超过即视为无确认过期）
         */
        private boolean isExpiredWithoutConfirmation() {
            return System.currentTimeMillis() - lastConfirmedAt.get() >= lockTtlMillis;
        }

        /**
         * 标记锁丢失并取消心跳定时任务（仅首次生效）
         */
        private void markLost() {
            if (lost.compareAndSet(false, true)) {
                ScheduledFuture<?> scheduledFuture = future;
                if (scheduledFuture != null) {
                    scheduledFuture.cancel(false);
                }
            }
        }

        /**
         * 关闭心跳并取消定时任务（仅首次生效）
         */
        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                ScheduledFuture<?> scheduledFuture = future;
                if (scheduledFuture != null) {
                    scheduledFuture.cancel(false);
                }
            }
        }
    }
}
