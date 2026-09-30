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

package io.github.jaye.ai.askora.infra.rerank;

import cn.hutool.core.collection.CollUtil;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.github.jaye.ai.askora.framework.convention.RetrievedChunk;
import io.github.jaye.ai.askora.infra.config.AIModelProperties;
import io.github.jaye.ai.askora.infra.enums.ModelCapability;
import io.github.jaye.ai.askora.infra.enums.ModelProvider;
import io.github.jaye.ai.askora.infra.http.HttpMediaTypes;
import io.github.jaye.ai.askora.infra.http.HttpResponseHelper;
import io.github.jaye.ai.askora.infra.http.ModelClientErrorType;
import io.github.jaye.ai.askora.infra.http.ModelClientException;
import io.github.jaye.ai.askora.infra.http.ModelUrlResolver;
import io.github.jaye.ai.askora.infra.model.ModelTarget;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 百炼（阿里云）重排客户端
 * 调用百炼 rerank 端点，按候选文档与 query 的相关度精排并返回前 topN 条
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class BaiLianRerankClient implements RerankClient {

    @Qualifier("syncHttpClient")
    private final OkHttpClient httpClient;

    /**
     * 返回百炼提供商标识
     */
    @Override
    public String provider() {
        return ModelProvider.BAI_LIAN.getId();
    }

    /**
     * 对候选文档重排并返回前 topN 条
     * 先按 chunk id 去重；若去重后数量不超过 topN 则直接返回，省去一次远程调用
     */
    @Override
    public List<RetrievedChunk> rerank(String query, List<RetrievedChunk> candidates, int topN, ModelTarget target) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }

        List<RetrievedChunk> dedup = new ArrayList<>(candidates.size());
        Set<String> seen = new HashSet<>();
        for (RetrievedChunk rc : candidates) {
            if (seen.add(rc.getId())) {
                dedup.add(rc);
            }
        }

        if (topN <= 0 || dedup.size() <= topN) {
            return dedup;
        }

        return doRerank(query, dedup, topN, target);
    }

    /**
     * 调用百炼 rerank 接口执行重排
     * 响应结果按 index 映射回原候选并覆盖相关性分数；返回不足 topN 时用剩余候选按原顺序补齐
     */
    private List<RetrievedChunk> doRerank(String query, List<RetrievedChunk> candidates, int topN, ModelTarget target) {
        AIModelProperties.ProviderConfig provider = HttpResponseHelper.requireProvider(target, provider());

        if (candidates == null || candidates.isEmpty() || topN <= 0) {
            return List.of();
        }

        JsonObject reqBody = new JsonObject();
        reqBody.addProperty("model", HttpResponseHelper.requireModel(target, provider()));

        JsonObject input = new JsonObject();
        input.addProperty("query", query);

        JsonArray documentsArray = new JsonArray();
        for (RetrievedChunk each : candidates) {
            documentsArray.add(each.getText() == null ? "" : each.getText());
        }
        input.add("documents", documentsArray);

        JsonObject parameters = new JsonObject();
        parameters.addProperty("top_n", topN);
        parameters.addProperty("return_documents", true);

        reqBody.add("input", input);
        reqBody.add("parameters", parameters);

        Request request = new Request.Builder()
                .url(ModelUrlResolver.resolveUrl(provider, target.candidate(), ModelCapability.RERANK))
                .post(RequestBody.create(reqBody.toString(), HttpMediaTypes.JSON))
                .addHeader("Authorization", "Bearer " + provider.getApiKey())
                .build();

        JsonObject respJson;
        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                String body = HttpResponseHelper.readBody(response.body());
                log.warn("{} rerank 请求失败: status={}, body={}", provider(), response.code(), body);
                throw new ModelClientException(
                        provider() + " rerank 请求失败: HTTP " + response.code(),
                        ModelClientErrorType.fromHttpStatus(response.code()),
                        response.code()
                );
            }
            respJson = HttpResponseHelper.parseJson(response.body(), provider());
        } catch (IOException e) {
            throw new ModelClientException(provider() + " rerank 请求失败: " + e.getMessage(), ModelClientErrorType.NETWORK_ERROR, null, e);
        }

        JsonObject output = requireOutput(respJson);

        JsonArray results = output.getAsJsonArray("results");
        if (CollUtil.isEmpty(results)) {
            throw new ModelClientException(provider() + " rerank results 为空", ModelClientErrorType.INVALID_RESPONSE, null);
        }

        List<RetrievedChunk> reranked = new ArrayList<>();
        Set<String> addedIds = new HashSet<>();

        for (JsonElement elem : results) {
            if (!elem.isJsonObject()) {
                continue;
            }
            JsonObject item = elem.getAsJsonObject();

            if (!item.has("index")) {
                continue;
            }
            int idx = item.get("index").getAsInt();

            if (idx < 0 || idx >= candidates.size()) {
                continue;
            }

            RetrievedChunk src = candidates.get(idx);

            Float score = null;
            if (item.has("relevance_score") && !item.get("relevance_score").isJsonNull()) {
                score = item.get("relevance_score").getAsFloat();
            }

            // 整体拷贝仅覆盖分数：逐字段白名单在 RetrievedChunk 新增字段时会静默漏拷（collectionName 曾因此丢失、意图归属整体失效）
            RetrievedChunk hit = score != null
                    ? src.toBuilder()
                    .score(score)
                    .build()
                    : src;
            reranked.add(hit);
            addedIds.add(src.getId());

            if (reranked.size() >= topN) {
                break;
            }
        }

        if (reranked.size() < topN) {
            for (RetrievedChunk c : candidates) {
                if (addedIds.add(c.getId())) {
                    reranked.add(c);
                }
                if (reranked.size() >= topN) {
                    break;
                }
            }
        }

        return reranked;
    }

    /**
     * 校验并提取响应中的 output.results 节点，缺失时抛出异常
     */
    private JsonObject requireOutput(JsonObject respJson) {
        if (respJson == null || !respJson.has("output")) {
            throw new ModelClientException(provider() + " rerank 响应缺少 output", ModelClientErrorType.INVALID_RESPONSE, null);
        }
        JsonObject output = respJson.getAsJsonObject("output");
        if (output == null || !output.has("results")) {
            throw new ModelClientException(provider() + " rerank 响应缺少 results", ModelClientErrorType.INVALID_RESPONSE, null);
        }
        return output;
    }
}
