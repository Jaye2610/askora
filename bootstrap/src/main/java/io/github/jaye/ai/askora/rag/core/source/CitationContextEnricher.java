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

package io.github.jaye.ai.askora.rag.core.source;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import io.github.jaye.ai.askora.framework.convention.SourceRef;
import io.github.jaye.ai.askora.rag.config.RAGConfigProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 为已格式化的知识库上下文注入请求级引用编号
 * <p>
 * 上下文格式化阶段只写入内部 {@code data-askora-doc-id}，来源装配完成后再依据
 * {@link SourceRef#getIndex()} 替换为模型可见的 {@code ref}。这样 Prompt、SSE、落库和前端
 * 始终复用同一份来源编号，同时不把内部文档 ID 暴露给模型
 * <p>
 * 引用开关关闭时按「无来源」处理：只抹掉内部锚点、不注入编号。无论开关如何，
 * 上下文都必须先过这一道，内部 docId 才不会漏进模型可见文本
 */
@Component
@RequiredArgsConstructor
public class CitationContextEnricher {

    private static final Pattern CONTENT_TAG = Pattern.compile(
            "(?m)^<content([^>]*) data-askora-doc-id=\"([^\"]*)\">$");

    private final RAGConfigProperties ragConfigProperties;

    /**
     * 为格式化好的知识库上下文注入模型可见的引用编号
     * <p>
     * 逐行扫描内部 {@code data-askora-doc-id} 锚点，找到对应 {@link SourceRef} 的引用编号后，
     * 在 content 标签上补写 {@code ref="n"}；找不到编号（如引用开关关闭或来源缺失）则只保留
     * 原始标签、不注入编号。返回的上下文即可安全交给模型。
     *
     * @param kbContext 已格式化的知识库上下文（含内部 docId 锚点）
     * @param sources   本次回答的来源编号表
     * @return 注入引用编号后的上下文
     */
    public String enrich(String kbContext, List<SourceRef> sources) {
        if (StrUtil.isBlank(kbContext)) {
            return StrUtil.emptyIfNull(kbContext);
        }

        // 引用开关关闭时按「无来源」处理：不建立编号映射，只负责抹掉内部锚点
        Map<String, Integer> indexByDocId = Boolean.TRUE.equals(ragConfigProperties.getCitationEnabled())
                ? indexByDocId(sources)
                : Map.of();
        Matcher matcher = CONTENT_TAG.matcher(kbContext);
        StringBuilder result = new StringBuilder(kbContext.length());
        while (matcher.find()) {
            String attributes = matcher.group(1);
            String docId = matcher.group(2);
            Integer index = indexByDocId.get(docId);
            String replacement = index == null
                    ? "<content" + attributes + ">"
                    : "<content" + attributes + " ref=\"" + index + "\">";
            matcher.appendReplacement(result, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    /**
     * 把来源列表整理成 docId -> 引用编号 的查找表，同一 docId 取最先出现的编号
     */
    private Map<String, Integer> indexByDocId(List<SourceRef> sources) {
        if (CollUtil.isEmpty(sources)) {
            return Map.of();
        }
        Map<String, Integer> result = new LinkedHashMap<>();
        for (SourceRef source : sources) {
            if (source == null
                    || StrUtil.isBlank(source.getDocId())
                    || source.getIndex() == null) {
                continue;
            }
            // putIfAbsent：同一文档出现多条来源编号时保留首个，其余丢弃
            result.putIfAbsent(source.getDocId(), source.getIndex());
        }
        return result;
    }
}
