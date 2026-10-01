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

import io.github.jaye.ai.askora.framework.errorcode.BaseErrorCode;
import io.github.jaye.ai.askora.framework.exception.RemoteException;
import io.github.jaye.ai.askora.infra.enums.ModelCapability;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.function.Function;

/**
 * 模型路由执行器
 * 负责在多个模型候选者之间进行调度执行，并提供故障转移（Fallback）和健康检查机制
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ModelRoutingExecutor {

    private final ModelHealthStore healthStore;

    /**
     * 按候选顺序执行一次模型调用，并对健康状态做门控与故障转移
     * <p>
     * 业务语义：针对同一能力（如 CHAT/EMBEDDING），配置通常按优先级排列了多个候选模型。
     * 这里按顺序尝试，直到拿到一个成功响应：
     * <ul>
     *   <li>优先经 {@link ModelHealthStore#allowCall} 做熔断门控，被熔断（OPEN/HALF_OPEN 占用）的候选直接跳过；</li>
     *   <li>调用成功立即标记健康并返回结果；</li>
     *   <li>调用失败标记该候选不健康，并降级到下一个候选继续尝试（即线性故障转移）；</li>
     *   <li>若所有候选都失败（含不可达/被拒绝），抛 {@link RemoteException} 通知上层，可触发全局降级或提示。</li>
     * </ul>
     *
     * @param capability     本次调用所需的能力类型（用于区分 CHAT/EMBEDDING/RERANK 各自的候选池与日志标签）
     * @param targets        按优先级排列的候选模型列表（第 0 个为主用，越靠后越是备份）
     * @param clientResolver 由候选 target 解析出可用的 provider 客户端；解析为空表示该提供方未配置/不可用
     * @param caller         真正执行一次模型调用的回调，返回模型结果
     * @return 第一个调用成功的模型结果
     * @throws RemoteException 候选列表为空，或全部候选均调用失败时抛出
     */
    public <C, T> T executeWithFallback(
            ModelCapability capability,
            List<ModelTarget> targets,
            Function<ModelTarget, C> clientResolver,
            ModelCaller<C, T> caller) {
        String label = capability.getDisplayName();
        if (targets == null || targets.isEmpty()) {
            // 没有任何候选可用（配置缺失），直接抛出，避免空转
            throw new RemoteException("No " + label + " model candidates available");
        }

        Throwable last = null;
        for (ModelTarget target : targets) {
            // 解析出该候选对应的 provider 客户端，拿不到说明该提供方当前不可用，跳过本候选
            C client = clientResolver.apply(target);
            if (client == null) {
                log.warn("{} provider client missing: provider={}, modelId={}", label, target.candidate().getProvider(), target.id());
                continue;
            }
            // 熔断门控：当前候选处于熔断期或半开探测被占用则拒绝放行，等下一候选
            if (healthStore.allowCall(target.id()) == null) {
                continue;
            }

            try {
                // 调用成功：回写健康状态（关闭熔断），直接返回该结果作为最终输出
                T response = caller.call(client, target);
                healthStore.markSuccess(target.id());
                return response;
            } catch (Exception e) {
                // 调用失败：记录最后一次异常、标记候选不健康，再降级到下一个候选
                last = e;
                healthStore.markFailure(target.id());
                log.warn("{} model failed, fallback to next. modelId={}, provider={}", label, target.id(), target.candidate().getProvider(), e);
            }
        }

        // 走到这里说明全部候选均失败，抛出远程异常通知上层统一处理
        throw new RemoteException(
                "All " + label + " model candidates failed: " + (last == null ? "unknown" : last.getMessage()),
                last,
                BaseErrorCode.REMOTE_ERROR
        );
    }
}
