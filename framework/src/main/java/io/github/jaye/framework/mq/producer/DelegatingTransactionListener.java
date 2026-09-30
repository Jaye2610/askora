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

package io.github.jaye.ai.askora.framework.mq.producer;

import io.github.jaye.ai.askora.framework.mq.MessageWrapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQTransactionListener;
import org.apache.rocketmq.spring.core.RocketMQLocalTransactionListener;
import org.apache.rocketmq.spring.core.RocketMQLocalTransactionState;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.Message;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Consumer;

/**
 * 通用的 RocketMQ 事务消息监听器
 */
@Slf4j
@RocketMQTransactionListener
public class DelegatingTransactionListener implements RocketMQLocalTransactionListener {

    static final String HEADER_TX_ID = "TRANSACTION_CONTEXT_ID";
    static final String HEADER_TOPIC = "TRANSACTION_TOPIC";

    /**
     * 本地事务执行逻辑，per-message，仅当前实例有效
     */
    private final ConcurrentMap<String, Consumer<Object>> localTransactionMap = new ConcurrentHashMap<>();

    /**
     * 事务回查逻辑，per-topic，所有实例共享（Spring Bean 注册）
     */
    private final ConcurrentMap<String, TransactionChecker> checkerMap = new ConcurrentHashMap<>();

    @Autowired
    private PlatformTransactionManager transactionManager;

    /**
     * 注册本地事务逻辑，供 Broker 确认 half 消息后回调执行
     */
    public void registerLocalTransaction(String txId, Consumer<Object> localTransaction) {
        localTransactionMap.put(txId, localTransaction);
    }

    /**
     * 注销本地事务逻辑，half 消息发送失败或非 SEND_OK 时调用，防止内存泄漏
     */
    void unregisterLocalTransaction(String txId) {
        localTransactionMap.remove(txId);
    }

    /**
     * 按 topic 注册事务回查器，回查请求可能落在任意实例，因此 checker 须以 Spring Bean 形式全局共享
     */
    public void registerChecker(String topic, TransactionChecker checker) {
        checkerMap.put(topic, checker);
    }

    /**
     * 执行本地事务（half 消息发送成功后被 RocketMQ 回调）
     * <p>
     * 从本地注册表中取出 txId 对应的业务逻辑并包裹在 Spring 事务中执行：
     * 成功返回 COMMIT 让 Broker 投递消息，异常返回 ROLLBACK 丢弃消息，
     * 从而保证「本地事务与消息发送」的最终一致性。
     */
    @Override
    public RocketMQLocalTransactionState executeLocalTransaction(Message message, Object arg) {
        String txId = (String) message.getHeaders().get(HEADER_TX_ID);
        Consumer<Object> localTransaction = txId != null ? localTransactionMap.remove(txId) : null;
        if (localTransaction == null) {
            log.error("[事务消息] 未找到本地事务逻辑, txId={}", txId);
            return RocketMQLocalTransactionState.ROLLBACK;
        }
        try {
            new TransactionTemplate(transactionManager).executeWithoutResult(status -> localTransaction.accept(arg));
            return RocketMQLocalTransactionState.COMMIT;
        } catch (Exception e) {
            log.error("[事务消息] 本地事务执行失败, txId={}", txId, e);
            return RocketMQLocalTransactionState.ROLLBACK;
        }
    }

    /**
     * 事务回查（Broker 长时间未收到二次确认时调用）
     * <p>
     * 回查请求可能被路由到任意实例，本地注册表查不到 txId，
     * 因此委托给按 topic 注册的 {@link TransactionChecker}，
     * 由其基于消息载荷查询 DB 判断本地事务是否已提交；
     * checker 缺失或执行异常分别返回 ROLLBACK / UNKNOWN，交由 Broker 稍后再次回查。
     */
    @Override
    public RocketMQLocalTransactionState checkLocalTransaction(Message message) {
        String topic = (String) message.getHeaders().get(HEADER_TOPIC);
        TransactionChecker checker = topic != null ? checkerMap.get(topic) : null;
        if (checker == null) {
            log.warn("[事务消息] 回查时未找到 topic={} 对应的 checker, 默认 ROLLBACK", topic);
            return RocketMQLocalTransactionState.ROLLBACK;
        }
        try {
            MessageWrapper<?> wrapper = (MessageWrapper<?>) message.getPayload();
            boolean committed = checker.check(wrapper);
            RocketMQLocalTransactionState state = committed
                    ? RocketMQLocalTransactionState.COMMIT
                    : RocketMQLocalTransactionState.ROLLBACK;
            log.info("[事务消息] 回查结果: topic={}, state={}", topic, state);
            return state;
        } catch (Exception e) {
            log.error("[事务消息] 回查异常, topic={}", topic, e);
            return RocketMQLocalTransactionState.UNKNOWN;
        }
    }
}
