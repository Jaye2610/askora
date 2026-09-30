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

package io.github.jaye.ai.askora.ingestion.strategy.fetcher;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.jaye.ai.askora.framework.exception.ServiceException;
import io.github.jaye.ai.askora.ingestion.domain.context.DocumentSource;
import io.github.jaye.ai.askora.ingestion.domain.enums.SourceType;
import io.github.jaye.ai.askora.ingestion.util.HttpClientHelper;
import io.github.jaye.ai.askora.core.parser.mime.MimeTypeDetector;
import lombok.RequiredArgsConstructor;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * 飞书文档抓取器
 * 负责从飞书平台获取文档内容，支持 docx 类型的在线文档和二进制文件
 */
@Component
@RequiredArgsConstructor
public class FeishuFetcher implements DocumentFetcher {

    private static final String TOKEN_URL = "https://open.feishu.cn/open-apis/auth/v3/tenant_access_token/internal/";

    @Qualifier("syncHttpClient")
    private final OkHttpClient okHttpClient;
    private final HttpClientHelper httpClientHelper;

    /**
     * 获取支持的源类型
     */
    @Override
    public SourceType supportedType() {
        return SourceType.FEISHU;
    }

    /**
     * 从飞书获取文档内容
     * <p>
     * 在线 docx 文档走开放接口取纯文本；其余链接（飞书云盘文件）按普通 HTTP 下载，
     * Content-Type 缺失时按字节兜底探测 MIME
     */
    @Override
    public FetchResult fetch(DocumentSource source) {
        String location = source.getLocation();
        if (!StringUtils.hasText(location)) {
            throw new ServiceException("飞书文档地址不能为空");
        }

        String accessToken = resolveAccessToken(source.getCredentials());
        Map<String, String> headers = new HashMap<>();
        if (StringUtils.hasText(accessToken)) {
            headers.put("Authorization", "Bearer " + accessToken);
        }

        if (isDocxUrl(location)) {
            String docToken = extractDocToken(location);
            String apiUrl = "https://open.feishu.cn/open-apis/docx/v1/documents/" + docToken + "/raw_content";
            HttpClientHelper.HttpFetchResponse resp = httpClientHelper.get(apiUrl, headers);
            String content = extractDocxContent(resp.body());
            if (!StringUtils.hasText(content)) {
                content = new String(resp.body(), StandardCharsets.UTF_8);
            }
            String fileName = StringUtils.hasText(source.getFileName()) ? source.getFileName() : docToken + ".txt";
            return new FetchResult(content.getBytes(StandardCharsets.UTF_8), "text/plain", fileName);
        }

        HttpClientHelper.HttpFetchResponse resp = httpClientHelper.get(location, headers);
        String fileName = StringUtils.hasText(source.getFileName()) ? source.getFileName() : resp.fileName();
        String contentType = resp.contentType();
        if (!StringUtils.hasText(contentType)) {
            contentType = MimeTypeDetector.detect(resp.body(), fileName);
        }
        return new FetchResult(resp.body(), contentType, fileName);
    }

    /**
     * 判断链接是否指向飞书在线文档（docx/docs 路径）
     */
    private boolean isDocxUrl(String location) {
        return location.contains("/docx/") || location.contains("/docs/");
    }

    /**
     * 从链接里解析出文档令牌（docx/docs 路径段的下一段，去掉查询参数）
     */
    private String extractDocToken(String location) {
        String[] parts = location.split("/");
        for (int i = 0; i < parts.length; i++) {
            if ("docx".equalsIgnoreCase(parts[i]) || "docs".equalsIgnoreCase(parts[i])) {
                if (i + 1 < parts.length) {
                    String token = parts[i + 1];
                    int queryIndex = token.indexOf('?');
                    return queryIndex > 0 ? token.substring(0, queryIndex) : token;
                }
            }
        }
        throw new ServiceException("无法从飞书链接解析文档令牌: " + location);
    }

    /**
     * 解析访问令牌：优先直接取凭证里的现成令牌，缺失时用 app_id/app_secret 现换一个
     */
    private String resolveAccessToken(Map<String, String> credentials) {
        if (credentials == null) {
            return null;
        }
        String token = credentials.get("tenantAccessToken");
        if (!StringUtils.hasText(token)) {
            token = credentials.get("accessToken");
        }
        if (StringUtils.hasText(token)) {
            return token;
        }
        String appId = credentials.get("app_id");
        String appSecret = credentials.get("app_secret");
        if (!StringUtils.hasText(appId) || !StringUtils.hasText(appSecret)) {
            return null;
        }
        return requestTenantAccessToken(appId, appSecret);
    }

    /**
     * 用应用凭证向飞书开放平台换取 tenant_access_token
     */
    private String requestTenantAccessToken(String appId, String appSecret) {
        try {
            JsonObject payload = new JsonObject();
            payload.addProperty("app_id", appId);
            payload.addProperty("app_secret", appSecret);

            Request request = new Request.Builder()
                    .url(TOKEN_URL)
                    .post(RequestBody.create(payload.toString(), MediaType.parse("application/json")))
                    .build();
            try (Response response = okHttpClient.newCall(request).execute()) {
                if (!response.isSuccessful() || response.body() == null) {
                    throw new ServiceException("飞书令牌请求失败: " + response.code());
                }
                String raw = response.body().string();
                JsonObject json = JsonParser.parseString(raw).getAsJsonObject();
                if (json.has("tenant_access_token")) {
                    return json.get("tenant_access_token").getAsString();
                }
                return null;
            }
        } catch (Exception e) {
            throw new ServiceException("飞书令牌请求失败: " + e.getMessage());
        }
    }

    /**
     * 从 raw_content 接口的 JSON 响应里取 data.content 字段，解析失败返回 null 由调用方兜底
     */
    private String extractDocxContent(byte[] bytes) {
        try {
            JsonObject root = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
            if (root.has("data")) {
                JsonObject data = root.getAsJsonObject("data");
                if (data.has("content")) {
                    return data.get("content").getAsString();
                }
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }
}
