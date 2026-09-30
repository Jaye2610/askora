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

package io.github.jaye.ai.askora.rag.core.rewrite;

/**
 * 查询术语映射工具
 * <p>
 * 纯静态的无状态替换算法实现，被 {@link QueryTermMappingService} 单条规则逐次调用。
 * 与普通 {@code replaceAll} 的区别在于做了「幂等」保护：文本里如果已经是 {@code targetTerm}
 * 原样出现，就不会被再套一层替换，从而避免重复归一化把结果改坏（如「平安保司」误触发成
 * 「平安平安保司」）。
 */
public class QueryTermMappingUtil {

    /**
     * 安全归一化替换：
     * - 只替换 sourceTerm
     * - 如果当前位置本身已经是 targetTerm 起始（例如文本中已经是“平安保司”），则不重复替换
     *
     * @param text       待归一化的原始文本
     * @param sourceTerm 需被替换的原文词（大小写敏感精确匹配）
     * @param targetTerm 替换成的规范词
     * @return 归一化后的文本；入参为空时原样返回
     */
    public static String applyMapping(String text, String sourceTerm, String targetTerm) {
        if (text == null || text.isEmpty() || sourceTerm == null || sourceTerm.isEmpty()) {
            return text;
        }

        StringBuilder sb = new StringBuilder();
        int idx = 0;
        int len = text.length();
        int sourceLen = sourceTerm.length();
        int targetLen = targetTerm.length();

        while (idx < len) {
            int hit = text.indexOf(sourceTerm, idx);
            if (hit < 0) {
                // 后面没有命中，整体拷贝
                sb.append(text, idx, len);
                break;
            }

            // 先把命中之前的文本拷贝过去
            sb.append(text, idx, hit);

            // 判断当前位置是否已经是 targetTerm 的开头
            boolean alreadyTarget =
                    targetTerm != null
                            && hit + targetLen <= len
                            && text.startsWith(targetTerm, hit);

            if (alreadyTarget) {
                // 已经是目标词开头了，直接按原文拷贝 targetTerm，一次性跳过
                sb.append(text, hit, hit + targetLen);
                idx = hit + targetLen;
            } else {
                // 不是目标词开头，正常做归一化替换
                sb.append(targetTerm);
                idx = hit + sourceLen;
            }
        }

        return sb.toString();
    }
}
