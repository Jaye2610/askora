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

import io.github.jaye.ai.askora.framework.exception.ServiceException;
import io.github.jaye.ai.askora.ingestion.domain.context.DocumentSource;
import io.github.jaye.ai.askora.ingestion.domain.enums.SourceType;
import io.github.jaye.ai.askora.ingestion.util.HttpClientHelper;
import io.github.jaye.ai.askora.core.parser.mime.MimeTypeDetector;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.HashMap;
import java.util.Map;

/**
 * HTTP 链接文档获取器
 * 用于从指定的 HTTP/HTTPS 链接地址获取文档内容
 */
@Component
@RequiredArgsConstructor
public class HttpUrlFetcher implements DocumentFetcher {

    private final HttpClientHelper httpClientHelper;

    /**
     * 获取支持的源类型
     */
    @Override
    public SourceType supportedType() {
        return SourceType.URL;
    }

    /**
     * 从 HTTP/HTTPS 链接下载文档
     * <p>
     * Content-Type 缺失时按响应体字节兜底探测 MIME，保证后续解析节点拿到可用类型
     */
    @Override
    public FetchResult fetch(DocumentSource source) {
        String location = source.getLocation();
        if (!StringUtils.hasText(location)) {
            throw new ServiceException("链接地址不能为空");
        }

        Map<String, String> headers = buildHeaders(source.getCredentials());
        HttpClientHelper.HttpFetchResponse resp = httpClientHelper.get(location, headers);
        String fileName = StringUtils.hasText(source.getFileName()) ? source.getFileName() : resp.fileName();
        String contentType = normalizeContentType(resp.contentType());
        if (!StringUtils.hasText(contentType)) {
            contentType = MimeTypeDetector.detect(resp.body(), fileName);
        }
        return new FetchResult(resp.body(), contentType, fileName);
    }

    /**
     * 把凭证键值对转成请求头：token 键约定映射为 Bearer 认证头，其余原样透传
     */
    private Map<String, String> buildHeaders(Map<String, String> credentials) {
        if (credentials == null || credentials.isEmpty()) {
            return Map.of();
        }
        Map<String, String> headers = new HashMap<>();
        credentials.forEach((k, v) -> {
            if (!StringUtils.hasText(k) || v == null) {
                return;
            }
            if ("token".equalsIgnoreCase(k)) {
                headers.put("Authorization", "Bearer " + v);
            } else {
                headers.put(k, v);
            }
        });
        return headers;
    }

    /**
     * 去掉 Content-Type 里的字符集参数（如 text/html; charset=utf-8），只留媒体类型部分
     */
    private String normalizeContentType(String contentType) {
        if (!StringUtils.hasText(contentType)) {
            return null;
        }
        int idx = contentType.indexOf(';');
        return idx > 0 ? contentType.substring(0, idx).trim() : contentType.trim();
    }
}
