# Askora AI 关键运行链路

> 本文梳理一次用户提问（"问一个问题"）从入口到流式应答的完整核心链路，帮助快速读懂 Askora 的问答主流程。
> 所有路径均相对仓库根目录，类名 / 方法名与源码一致。逻辑比图示复杂，这里只展示主线与关键支线。

```
用户提问
   │
   ▼
RAGChatController.chat()                ← 入口：SSE + 幂等提交
   │
   ▼
RAGChatServiceImpl.streamChat()         ← 生成 taskId，排队 + 链路追踪
   │
   ▼
StreamChatPipeline.execute()            ← 阶段编排（记忆/重写/意图/检索/生成）
   │
   ├─ loadMemory      会话记忆：最近 N 轮 + 持久化摘要
   ├─ rewriteQuery    查询词映射 + LLM 重写与拆分
   ├─ resolveIntents  树形意图分类（KB / MCP / SYSTEM）
   ├─ handleGuidance  意图歧义 → 澄清提问（短路返回）
   ├─ handleSystemOnly 纯系统意图 → 免检索应答（短路返回）
   ├─ retrieve        多路检索 + 后处理（去重/融合/重排/富化）
   ├─ handleEmptyRetrieval 检索为空 → 兜底话术（短路返回）
   └─ streamRagResponse 组装证据 → LLM 流式生成 → SSE 输出
```

---

## 1. 入口：Controller

**`bootstrap/.../rag/controller/RAGChatController.java`**
- `GET /rag/v3/chat`，产物为 SSE（`text/event-stream`）。
- 标注 `@IdempotentSubmit`，对同一用户做单飞（防重复提交）。
- 创建 `SseEmitter`（超时取自 `RAGDefaultProperties.getSseTimeoutMs()`），调用 `ragChatService.streamChat(question, conversationId, deepThinking, emitter)`。
- `GET /rag/v3/stop` 调用 `RAGChatService.stopTask(taskId)` 中止任务。

## 2. 外层编排：RAGChatServiceImpl

**`bootstrap/.../rag/service/impl/RAGChatServiceImpl.java`** — `streamChat()`：
1. conversationId 为空则雪花生成；生成 `taskId`。
2. 通过 `StreamCallbackFactory.createChatEventHandler(emitter, conversationId, taskId)` 创建 SSE 回调。
3. 用 `ChatQueueLimiter.enqueue(...)` 将整个任务入队（Redis 公平排队限流），外层套 `StreamChatTraceRunner.run(...)` 埋 Trace / 首包耗时。
4. 队列内构建 `StreamChatContext`（问题、会话、deepThinking、userId、回调），交给 `chatPipeline.execute(ctx)`。

## 3. 编排 Pipeline：StreamChatPipeline

**`bootstrap/.../rag/service/pipeline/StreamChatPipeline.java`** — `execute(ctx)` 按序执行，部分阶段可短路：

- **a) loadMemory**：`ConversationMemoryService.load()` 载入摘要与历史，`append()` 落用户消息，回传 `replyToMessageId`。
- **b) rewriteQuery**：`QueryRewriteService.rewriteWithSplit(question, history)` → `RewriteResult`。
- **c) resolveIntents**：`IntentResolver.resolve(rewriteResult)` → `List<SubQuestionIntent>`。
- **d) handleGuidance**：`IntentGuidanceService.detectAmbiguity(...)` 检测到类别歧义时，通过回调输出澄清提示并 `onComplete()` 短路。
- **e) handleSystemOnly**：子意图全部为 SYSTEM 时，走免检索的系统应答。
- **f) retrieve**：`RetrievalEngine.retrieve(subIntents)` → `RetrievalContext`。
- **g) handleEmptyRetrieval**：检索为空输出"未检索到…"并短路。
- **h) streamRagResponse**：合并意图、组装来源与 Grounding，调 LLM 流式生成（见第 6 / 7 节）。

## 4. 提问理解（检索前）

- **查询词映射**：`QueryTermMappingService.normalize()`（`core/rewrite/`）按 DB/Redis 缓存的同义/别名规则把提问对齐到规范术语。
- **重写 + 拆分**：`MultiQuestionRewriteService implements QueryRewriteService`（`core/rewrite/`）
  - 先 `normalize()`，再按配置走规则拆分或 LLM 调用（FAST 档）重写并拆成 `sub_questions`；失败回退规范化后的原问题。
- **树形意图识别**：`IntentResolver.resolve()`（`core/intent/`）对每个子问题并行调 `DefaultIntentClassifier.classifyTargets()`，按 `INTENT_MIN_SCORE` 过滤、`MAX_INTENT_COUNT` 截断，并保留每子问题最高分意图；`mergeIntentGroup()` 区分 KB 与 MCP，`isSystemOnly()` 判断。
  - `DefaultIntentClassifier`：从 `IntentNodeRegistry` 载入意图树（Redis/DB），把所有叶子 `IntentNode`（含 id/path/description/examples、KB/MCP/SYSTEM 类型）拼进一次 LLM 调用，解析出 `[{id, score}]` 降序返回。
  - `IntentNode` 叶子携带 `collectionNames`（Milvus）、`mcpToolId`、`promptTemplate`、`topK` 与类型。
- **澄清**：`IntentGuidanceService`（`core/guidance/`）在类别分数接近时判定歧义并生成澄清提问。

## 5. 多路检索

**`RetrievalEngine.retrieve(subIntents)`**（`core/retrieval/RetrievalEngine.java`）：
- 先算共享 `RetrievalBudget`（召回放大量、Rerank 候选上限、最终 `contextTopK`）。
- 每个子问题并行 `buildSubQuestionContext`，把意图拆成 **KB** 与 **MCP** 两类：
  - **KB 路径** → `MultiChannelRetrievalEngine.retrieveKnowledgeChannels(intent, budget)` → `KbResult`（分组上下文 + chunks）。
  - **MCP 路径** → `executeMcpAndMerge`：对每个 MCP 意图先 `McpParameterExtractor.extractParameters()` 提参，再 `McpToolExecutor.execute()` 执行，结果格式化为 MCP 上下文。
- 把各子问题的 KB / MCP 上下文合并成统一 `RetrievalContext`（含 `intentChunks` 与 `eligibleIntentIds`）。

**`MultiChannelRetrievalEngine`**（`core/retrieval/MultiChannelRetrievalEngine.java`）：
- `buildSearchContext()`：`RetrievalScopeResolver.resolve()` 判定检索范围（定向命中知识库，或全局所有已激活知识库）。
- `executeSearchChannels()`：并行执行所有已启用 `SearchChannel`（按枚举序排列，channel 级超时，失败降级为空）。
- `executePostProcessors()`：按 `getOrder()` 升序执行已启用后处理器。

**通道**（`core/retrieval/channel/`）：`VectorSearchChannel`（向量，含定向补充检索）、`KeywordSearchChannel`（ES BM25）、`GraphSearchChannel`（LightRAG 知识图谱）、`WebSearchChannel`（联网搜索）。

**后处理链**（`core/retrieval/postprocessor/`）：
1. `DeduplicationPostProcessor`（order 1）：跨通道按 key 去重，保留首个。
2. `FusionPostProcessor`（order 5）：RRF 加权融合，截断到 `rerankCandidateLimit`。
3. `RerankPostProcessor`（order 10）：`RerankService.rerank()` 交叉注意力重排，取最终 top-K。
4. `MetadataEnrichmentPostProcessor`（order 20）：回查知识元数据补齐 docId/chunkIndex/docName。

## 6. 会话记忆

- **`DefaultConversationMemoryService.load()`**（`core/memory/`）：并行载入摘要与历史，把摘要消息（经 `decorateIfNeeded` 包装）前置到历史列表。
- **`JdbcConversationMemoryStore.loadHistory()`**：滑窗取最近 `historyKeepTurns`（默认 **8** 轮）。
- **`JdbcConversationMemorySummaryService.compressIfNeeded()`**：轮次超过 `summaryStartTurns`（默认 **9**）时用 LLM 把更早消息压缩成单条摘要入库，下次载入时作为开头历史消息注入。
- 两者组合：摘要覆盖最旧内容，`loadHistory` 保留最近 N 轮，共同构成完整上下文，控制 Token 成本。

## 7. 生成（LLM 调用）

**`StreamChatPipeline.streamLLMResponse()/streamSystemResponse()`**：
- `RAGPromptService.buildStructuredMessages()`（`core/prompt/`）组消息：
  1. 系统提示（KB/MCP/MIXED 模板由 `plan()` 选定；KB 且开启引用时追加引用规则）；
  2. 完整 `history`（已含前置的摘要消息）；
  3. 单条用户消息 = 证据主体（kb-evidence + mcp-evidence）+ 问题。
- `LLMService.streamChat(chatRequest, callback)`（infra-ai 模块）流式调用；RAG 模式温度 0 / 深度思考按 `deepThinking`，MCP 模式放宽温度。

## 8. 证据、溯源与 SSE 输出

**`StreamChatEventHandler`**（`service/handler/`，实现 `StreamCallback`，由 `StreamCallbackFactory` 创建）：
- 构造时推送 `META` 事件（`MetaPayload` 含 conversationId / taskId），并向 `StreamTaskManager` 注册任务。
- `onThinking/onContent`：缓冲文本，按 `messageChunkSize` 分块发 `MESSAGE` 增量（think / response 类型）。
- `onSources/onGroundingChunks`：暂存，用于结束事件。
- `onComplete`：组装助手 `ChatMessage`（含来源、Grounding、replyToMessageId），经 `memoryService.append` 持久化，发 `FINISH`（`CompletionPayload`，含 `SourceRef`）和 `DONE`（`[DONE]`），注销任务并完成 emitter；`onError/取消`发 `CANCEL`。

**来源与引用**：
- `SourcesAssembler.assemble(intentChunks)`（`core/source/`）：按 docId 分组、每文档取最高分、按分排序、回查文档，产出 `List<SourceRef>`（index/docId/docName/sourceType/url/excerpt），经回调推送，同时作完成载荷、来源面板与内联引用序号。
- `CitationContextEnricher.enrich()`：开启引用时把 KB 上下文里内部 `data-askora-doc-id` 锚点替换为可见的 `ref` 序号。
- `GroundingChunksAssembler.assemble()`：产出至多 8 个 `GroundingChunk`（docName + 全文，按文档去重），随助手消息存储用于 grounding / 推荐问题（不进入 LLM prompt）。

**推荐问题（非主链路，按需触发）**：
- `RecommendedQuestionController.generate()`（`POST /conversations/messages/{messageId}/recommended-questions`）
- `RecommendedQuestionServiceImpl.generate()` → 载入助手消息 + 用户问题，剥离引用标记，调 `RecommendedQuestionGenerator`（独立 FAST 档 LLM）生成并回写缓存。

---

## 建议跟读顺序（断点）

1. `RAGChatController.chat()` — 入口
2. `RAGChatServiceImpl.streamChat()` — 编排与排队
3. `StreamChatPipeline.execute()` — 阶段主干
4. `RetrievalEngine.retrieve()` + `MultiChannelRetrievalEngine` — 多路检索
5. `RAGPromptService.buildStructuredMessages()` — 证据 / 历史组装
6. `StreamChatEventHandler` — SSE 输出与结束事件

从一次真实提问跟着断点走完 1→6，即可看清 Askora 的核心问答闭环。
