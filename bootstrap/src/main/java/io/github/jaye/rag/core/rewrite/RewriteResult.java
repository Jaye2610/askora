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

import java.util.List;

/**
 * 查询重写结果
 * <p>
 * 由 {@link QueryRewriteService} 产出：rewrittenQuestion 为结合上下文补全后的规范化问题，
 * subQuestions 为拆分出的多个子问题，供后续意图识别与多路检索逐条使用。
 */
public record RewriteResult(String rewrittenQuestion, List<String> subQuestions) {

}
