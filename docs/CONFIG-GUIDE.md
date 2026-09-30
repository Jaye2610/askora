# Askora AI 配置指南

> 主配置文件：`bootstrap/src/main/resources/application.yaml`（后端全部运行时配置都在这里）。
> 前端配置在 `frontend/vite.config.ts`（开发代理端口），以及 `.env`。
> 本文按 yaml 分组逐个解释每个 key 的用途、可选值、如何改。改完需**重启后端**（或部分支持配置热更新）才能生效。

---

## 0. 修改配置的通用方法

1. 用任意编辑器打开 `bootstrap/src/main/resources/application.yaml`。
2. 定位对应 key，改值。
3. **重启后端**（IDEA 里重启 Run 配置，或在终端重新 `java -jar ...`）。
   - 纯本地调试还可用 `--spring.config.location` 指向另一份 yaml 覆盖。
   - 敏感信息（API Key）支持用环境变量，如 `${BAILIAN_API_KEY:}`，不在 yaml 里写死明文。

---

## 1. `server` — HTTP 服务

| key | 默认 | 含义 | 如何改 |
|:---|:---|:---|:---|
| `server.port` | `9090` | 后端 HTTP 端口 | 改数字即可，前端代理需同步（见 §13） |
| `server.servlet.context-path` | `/api/askora` | 所有接口统一前缀 | **一般别改**；改了前端所有 `/api` 调用路径都要跟着改 |

---

## 2. `spring.datasource` — PostgreSQL 数据库

| key | 默认 | 含义 |
|:---|:---|:---|
| `spring.datasource.url` | `jdbc:postgresql://127.0.0.1:5432/askora?client_encoding=UTF8` | 数据库连接串（地址/端口/库名） |
| `spring.datasource.username` | `postgres` | 账号 |
| `spring.datasource.password` | `postgres` | 密码 |
| `spring.datasource.hikari.maximum-pool-size` | `10` | 最大连接数 |
| `spring.datasource.hikari.minimum-idle` | `5` | 最小空闲连接 |

**如何改**：上服务器部署时把 `127.0.0.1` 换成数据库地址，库名/账号密码按实际。改完需 `CREATE DATABASE askora`（若库名变了）并执行建表脚本（见部署文档）。

---

## 3. `spring.data.redis` — Redis

| key | 默认 | 含义 |
|:---|:---|:---|
| `spring.data.redis.host` | `127.0.0.1` | Redis 地址 |
| `spring.data.redis.port` | `6379` | 端口 |
| `spring.data.redis.password` | `123456` | 密码（空则无密码） |

---

## 4. `rocketmq` — RocketMQ 消息队列

| key | 默认 | 含义 |
|:---|:---|:---|
| `rocketmq.name-server` | `127.0.0.1:9876` | NameServer 地址 |
| `rocketmq.producer.group` | `askora-producer${unique-name:}_pg` | 生产者组名（`unique-name` 用于多实例区分） |
| `rocketmq.producer.send-message-timeout` | `2000` | 发送超时(ms) |

---

## 5. `askora` — 平台级开关

| key | 默认 | 可选值 | 含义 |
|:---|:---|:---|:---|
| `askora.engine.type` | `workflow` | `workflow` / `agent` | **执行架构档位**：`workflow`=v1 编排管线；`agent`=v2 ReAct 架构（RAG 管线降级为主 Agent 的一个 Tool）⭐最重要的档位开关 |
| `askora.demo-mode` | `false` | `true/false` | 演示模式 |
| `askora.eval.enabled` | `true` | `true/false` | 是否启用评测接口 |

---

## 6. `rag.storage` — 对象存储（文档/图片）

| key | 默认 | 可选值 | 含义 |
|:---|:---|:---|:---|
| `rag.storage.type` | `s3` | `s3` / `oss` | 用 MinIO/S3 还是阿里云 OSS |
| `rag.storage.kb-bucket` | `askora-sources` | 任意桶名 | 私有知识库文档桶（全局唯一，生产需覆盖） |
| `rag.storage.asset-bucket` | `askora-assets` | 任意桶名 | 公共读资产桶（PDF 抽出的图供前端预览） |
| `rag.storage.s3.endpoint` | `http://localhost:9000` | — | MinIO/S3 地址 |
| `rag.storage.s3.access-key` / `secret-key` | `rustfsadmin` | — | MinIO 账号密码（与 MinIO 启动时一致） |
| `rag.storage.s3.region` | `us-east-1` | — | 区域 |
| `rag.storage.s3.path-style` | `true` | `true/false` | 路径风格寻址（MinIO 用 true） |
| `rag.storage.s3.public-url` | 空 | — | 外网访问基址；留空回退 endpoint |
| `rag.storage.oss.*` | — | — | 阿里云 OSS 相关（用 OSS 时配置，`access-key` 等用 `${OSS_ACCESS_KEY:}` 环境变量） |

---

## 7. `rag.vector` / `rag.keyword` / `rag.graph` — 三种检索后端

### 7.1 向量库
| key | 默认 | 可选值 | 含义 |
|:---|:---|:---|:---|
| `rag.vector.type` | `pg` | `milvus` / `pg` | **pg=pgvector，milvus=Milvus**。用 pg 则无需起 Milvus |

### 7.2 关键词检索（ES）
| key | 默认 | 含义 |
|:---|:---|:---|
| `rag.keyword.type` | `none`（可选`es`） | 关键词检索开关 |
| `rag.keyword.es.uris` / `index` / `analyzer` | — | ES 地址/索引名/分词器 |

### 7.3 知识图谱（LightRAG）
| key | 默认 | 含义 |
|:---|:---|:---|
| `rag.graph.type` | `none`（可选`lightrag`） | 图谱后端总开关 |
| `rag.graph.lightrag.base-url` | `http://127.0.0.1:9621` | LightRAG 服务地址 |
| `rag.graph.lightrag.query-mode` | `hybrid` | 查询模式 |
| `rag.graph.embedding-model` | `qwen-emb-8b` | 图谱嵌入模型 |

> ⚠ 想开图谱问答检索：需同时把 `rag.graph.type=lightrag` **和** `rag.search.channels.graph.enabled=true`（§10）。

---

## 8. `rag.default` — 默认向量集合

| key | 默认 | 含义 |
|:---|:---|:---|
| `rag.default.collection-name` | `rag_default_store` | 默认知识库集合名 |
| `rag.default.dimension` | `1536` | 向量维度（需与嵌入模型输出一致） |
| `rag.default.metric-type` | `COSINE` | 相似度度量 |
| `rag.default.sse-timeout-ms` | `300000` | **SSE 全局超时（毫秒）=5 分钟**，长回答超时可调大 |

---

## 9. 功能开关

| key | 默认 | 含义 |
|:---|:---|:---|
| `rag.query-rewrite.enabled` | `true` | 是否启用查询改写+拆分 |
| `rag.rerank.enabled` | `true` | 是否启用 Rerank 精排（关掉则跳过 Rerank） |
| `rag.citation.enabled` | `true` | 回答行内引用角标 `[N]`；开会增加首字延迟，关掉不影响来源面板 |

---

## 10. `rag.search` — 检索漏斗与通道（召回质量最常用）

### 10.1 三段预算（漏斗）
| key | 默认 | 含义 |
|:---|:---|:---|
| `rag.search.default-top-k` | `10` | ③ 最终进 LLM 的条数（产品 topK） |
| `rag.search.recall-budget` | `20` | ① 每通道召回条数，须 ≥ default-top-k |
| `rag.search.fusion.rerank-candidate-limit` | `40` | ② RRF 融合后送 Rerank 的候选池上限 |

> ⚠ 三数必须满足 `recall ≥ rerank-limit ≥ default-top-k`，否则**启动时报错拒绝启动**。

### 10.2 检索作用域
| key | 默认 | 含义 |
|:---|:---|:---|
| `rag.search.scope.min-intent-score` | `0.4` | 低于此分的意图不参与"收窄"判定 |
| `rag.search.scope.confidence-threshold` | `0.6` | KB 意图最高分低于此 → 退化为全库检索 |
| `rag.search.scope.supplement-ratio` | `0.25` | 定向时给"未命中库"的保底召回比例；0=关补充 |

### 10.3 通道开关
| key | 默认 | 含义 |
|:---|:---|:---|
| `rag.search.channels.timeout-ms` | `15000` | 单通道查询超时 |
| `rag.search.channels.vector.enabled` | `true` | 向量通道 |
| `rag.search.channels.keyword.enabled` | `false` | 关键词通道（需 `.es`） |
| `rag.search.channels.graph.enabled` | `false` | 图谱通道（需 `rag.graph.type=lightrag`） |
| `rag.search.channels.web-search.enabled` | `false` | 联网搜索通道 |
| `rag.search.channels.web-search.count` | `5` | 联网返回条数 |
| `rag.search.channels.web-search.api-key` | `${YDC_API_KEY:}` | You.com 联网 API Key |

### 10.4 融合算法
| key | 默认 | 含义 |
|:---|:---|:---|
| `rag.search.fusion.strategy` | `rrf` | 融合策略 |
| `rag.search.fusion.rrf-k` | `20` | RRF 平滑参数 |
| `rag.search.fusion.channel-weights.*` | 见默认 | 各路权重（vector 1.0 / keyword 1.0 / graph 0.8 / web 0.5） |

---

## 11. `rag.rate-limit` / `rag.memory` / `rag.semaphore` — 稳定性与记忆

| key | 默认 | 含义 |
|:---|:---|:---|
| `rag.rate-limit.global.enabled` | `true` | 全局限流开关 |
| `rag.rate-limit.global.max-concurrent` | `10` | 最大并发 |
| `rag.rate-limit.global.max-wait-seconds` | `15` | 排队最长等待 |
| `rag.rate-limit.global.lease-seconds` | `30` | 许可租期 |
| `rag.rate-limit.global.poll-interval-ms` | `200` | 轮询间隔 |
| `rag.memory.history-keep-turns` | `8` | 保留最近 N 轮原文 |
| `rag.memory.summary-enabled` | `true` | 是否开启历史摘要 |
| `rag.memory.summary-start-turns` | `9` | 超过 N 轮开始摘要 |
| `rag.memory.summary-max-chars` | `400` | 摘要最大长度 |
| `rag.memory.title-max-length` | `30` | 会话标题截断长度 |

---

## 12. `rag.mcp` — MCP 工具

| key | 默认 | 含义 |
|:---|:---|:---|
| `rag.mcp.servers[].name` | `default` | MCP 服务名 |
| `rag.mcp.servers[].url` | `http://localhost:9099` | MCP Server 地址（可加多个） |

---

## 13. `rag.trace` / `image-parse` — 追踪与图片解析

| key | 默认 | 含义 |
|:---|:---|:---|
| `rag.trace.enabled` | `true` | 全链路 Trace 开关 |
| `rag.trace.max-error-length` | `1000` | Trace 错误信息截断长度 |
| `rag.image-parse.description-prompt` | 长文本 | 图片转写指令（可自定义提示词） |
| `rag.image-parse.max-output-tokens` | `4096` | 图片解析最大 token |

---

## 14. `ai` — 模型供应商（**想让问答真正跑起来必须配**）

### 14.1 `ai.providers` — 供应商
| key | 含义 | 是否要 Key |
|:---|:---|:---|
| `ai.providers.ollama.url` | 本地 Ollama（默认 `localhost:11434`） | 否 |
| `ai.providers.bailian.api-key` | 阿里百炼 `${BAILIAN_API_KEY:}` | 是 |
| `ai.providers.aihubmix.api-key` | AIHubMix `${AIHUBMIX_API_KEY:}` | 是 |
| `ai.providers.siliconflow.api-key` | 硅基流动 `${SILICONFLOW_API_KEY:}` | 是 |

> 每个 provider 下的 `endpoints.chat/embedding/rerank` 是对应协议的接口路径，一般不用改。

### 14.2 `ai.selection` — 模型熔断参数
| key | 默认 | 含义 |
|:---|:---|:---|
| `ai.selection.failure-threshold` | `2` | 连续失败多少次判故障 |
| `ai.selection.open-duration-ms` | `30000` | 熔断冷却时长 |

### 14.3 `ai.chat` — 对话模型候选与档位（**核心**）
候选列表 `ai.chat.candidates[]`：每个选项目的 `id`、`provider`、`model`、可选 `supports-thinking`。

档位 `ai.chat.tiers`：
| tier | 默认候选 | timeout-ms |
|:---|:---|:---|
| `fast` | qwen-flash, qwen-plus, qwen3-local | 5000 |
| `standard` | qwen3-max, qwen-plus, qwen3-local, gpt-5.4 | 30000 |
| `deep` | qwen3-max, glm-4.7 | 120000 |

- `ai.chat.default-tier: standard`
- `ai.chat.deep-thinking-tier: deep`

**如何改**：加/删候选时，在 `candidates` 加一条、并在想用的 `tier` 的 `candidates` 数组里引用 `id`。换主力模型 = 改对应 candidate 的 `model`/`provider`。

### 14.4 `ai.embedding` — 嵌入模型
- `default-model`、`candidates[]`（按 `priority` 选，`dimension` 需与 `rag.default.dimension` 一致）。

### 14.5 `ai.rerank` — 重排模型
- `default-model`、`candidates[]`（`rerank-noop` 是空实现，priority 100 兜底，未配真实 rerank 时自动用 noop，不影响主链路）。

### 14.6 `ai.vlm` — 多模态大模型
- `default-model`、`candidates[]`（用于图片解析等）。

---

## 15. `mineru` — MinerU SaaS 文档解析（PDF/Word/PPT）

| key | 默认 | 含义 |
|:---|:---|:---|
| `mineru.api-url` | `https://mineru.net/api/v4` | MinerU 接口地址 |
| `mineru.api-key` | `${MINERU_API_KEY:}` | MinerU API Key（**需购买/申请**） |
| `mineru.poll-interval-seconds` | `5` | 轮询间隔 |
| `mineru.timeout-seconds` | `300` | 总超时 |
| `mineru.enable-table` / `enable-formula` / `ocr` / `language` | — | 表格/公式/OCR/语言开关 |
| `mineru.concurrency-limit` | `5` | 并发上限 |
| `mineru.semaphore-name` / `max-wait-seconds` / `lease-seconds` | — | 并发信号量配置 |

> ⚠ 文档入库需要 MinerU，**没配 Key 时 PDF/Word 将无法正常解析入库**（这也是"本地跑通后上传 PDF 失败"的头号原因）。

---

## 16. `sa-token` — 认证鉴权

| key | 默认 | 含义 |
|:---|:---|:---|
| `sa-token.token-name` | `Authorization` | 请求头携带 token 的名称 |
| `sa-token.timeout` | `2592000`(30天) | token 有效期(秒) | 
| `sa-token.is-concurrent` / `is-share` | `true`/`false` | 是否允许单账号多点登录 |
| `sa-token.token-style` | `simple-uuid` | token 生成风格 |

---

## 17. `milvus` — Milvus 连接（选了 `rag.vector.type=milvus` 时才用）

| key | 默认 | 含义 |
|:---|:---|:---|
| `milvus.uri` | `http://localhost:19530` | Milvus 服务地址 |

---

## 18. 前端相关配置（不在 application.yaml）

| 文件 | key/项 | 含义 | 如何改 |
|:---|:---|:---|:---|
| `frontend/vite.config.ts` | `server.port` | 前端 dev 端口（默认 5173） | 改 `port` |
| `frontend/vite.config.ts` | `server.proxy['/api'].target` | 开发时 `/api` 转发到的后端地址 | 改 `http://localhost:9090` 为实际后端 |
| `frontend/.env` | `VITE_*` | 前端构建期环境变量 | 按需改 |

> 生产环境前端用 nginx 托管 `dist/` 并反代 `/api` 到后端，此时不走 vite 代理。

---

## 19. 上服务器必改清单（快速核对）

1. `server.port` / `context-path`
2. `spring.datasource.url`（数据库地址）
3. `spring.data.redis.host/password`
4. `rocketmq.name-server`
5. `rag.storage.s3.endpoint` 与桶名（或切 `oss`）
6. 至少一个模型供应商的 `api-key`（bailian/aihubmix/siliconflow 之一）
7. `mineru.api-key`
8. （若用）`ai.providers.*` 各 Key、`rag.search.channels.web-search.api-key`
9. `milvus.uri`（若用 milvus）

---

## 20. 常见改动场景 → 改哪个 key

| 我要做什么 | 改哪里 |
|:---|:---|
| 让问答真能跑（配一个模型） | `ai.providers.*.api-key` + 确认 `ai.chat.tiers.*.candidates` 里有该 provider 的模型 |
| 换主力对话模型 | `ai.chat.candidates` 里改对应 `model`/`provider` |
| 调检索返回条数 | `rag.search.default-top-k` / `recall-budget` / `rerank-candidate-limit` |
| 开/关某个检索通道 | `rag.search.channels.<通道>.enabled` |
| 对话太慢/超时 | `rag.default.sse-timeout-ms`、`ai.chat.tiers.<档>.timeout-ms` |
| 记忆太短/太长 | `rag.memory.history-keep-turns` / `summary-*` |
| 并发太高被限流 | `rag.rate-limit.global.max-concurrent` / `max-wait-seconds` |
| 文档上传解析失败 | `mineru.api-key` / `mineru.enable-*` |
| 用 pgvector 还是 Milvus | `rag.vector.type` |
