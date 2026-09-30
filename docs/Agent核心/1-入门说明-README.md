# 1 · Askora 入门说明（README）

> 本文面向第一次接触 Askora 的读者：它是什么、解决什么问题、整体长什么样、怎么跑起来、代码从哪看起。读完你能对项目建立一个可操作的整体认知，并知道下一步该读哪份文档。

---

## 1.1 项目是什么

**Askora AI** 是一个面向 **Agentic RAG 演进**的生产级 Java AI 应用平台。

一句话概括：**一个能让企业把文档喂进去、用户用自然语言问出来、并且能看得见"它到底怎么回答"的完整智能问答系统。**

它覆盖了：
- **文档入库**：上传 PDF / Word / Excel / Markdown / 图片等 → 解析 → 分块 → 向量化 → 写入存储
- **多路检索**：向量（Milvus/pgvector）＋ 关键词（ES）＋ 知识图谱（LightRAG）＋ 联网搜索，四路并行召回
- **智能问答**：问题理解（改写/拆分/意图识别）→ 检索 → Rerank 精排 → Prompt 组装 → LLM 流式回答
- **可观测性**：全链路 Trace、来源溯源、回答溯源、用户反馈、管理后台

> 这是一套**经过真实场景锤炼**的工程实践。生产落地 RAG/Agent 会踩的坑，这里大多有对应方案，这也是它和"just call LLM"demo 的本质区别。

---

## 1.2 核心能力一览

| 能力 | 说明 | 代码位置（示意） |
|:---|:---|:---|
| 混合检索 | 向量/关键词/图谱/联网 四路并行，去重 → RRF 融合 → Rerank 精排 | `rag/core/retrieval/` |
| 问题理解 | 查询改写、多问句拆分、树形意图识别、知识库路由、歧义澄清 | `rag/core/rewrite/` `rag/core/intent/` `rag/core/guidance/` |
| 模型治理 | 多模型档位路由、三态熔断、故障转移、首包探测 | `infra-ai/.../model/` |
| 会话记忆 | 最近 N 轮消息 + 持久化 LLM 摘要，控 Token 成本 | `rag/core/memory/` |
| 流量保护 | Redis 公平排队 + 分布式并发控制 | `rag/service/ratelimit/` |
| 知识闭环 | 可编排入库 Pipeline、远程刷新、回答溯源、反馈、Trace、后台 | `core/ingest/` `ingestion/` `rag/trace/` |

---

## 1.3 技术栈

| 类别 | 技术 |
|:---|:---|
| 后端 | Java 17 · Spring Boot 3.5 · Maven（多模块）· MyBatis-Plus |
| 前端 | React 18 · TypeScript · Vite · Tailwind CSS · Radix UI · Zustand |
| 存储 | PostgreSQL + pgvector · Redis · Milvus(可选) · Elasticsearch(可选) · MinIO/S3/OSS |
| 队列 | RocketMQ（事务消息） |
| AI/LLM | OpenAI 兼容协议 · 阿里百炼 · 硅基流动 · AIHubMix · 本地 Ollama · MCP SDK |
| 检索 | 向量 · 关键词(BM25) · 知识图谱(LightRAG) · 联网搜索 · RRF + Rerank |

---

## 1.4 模块结构（代码地图）

```
askora/
├─ bootstrap/   主应用（Controller / Service / RAG 编排 / 入库 / 管理后台接口）
│   └─ src/main/java/io/github/jaye/ai/askora/
│       ├─ rag/         【核心】RAG 问答编排：pipeline/intent/retrieval/rewrite/memory/prompt/guidance/mcp
│       ├─ core/        【入库内核】parser(解析)/chunk(分块)/ingest(向量化落库)
│       ├─ knowledge/   知识库领域：KB 管理、文档管理、Chunk 管理、MinerU 同步
│       ├─ ingestion/   可编排入库流水线引擎（v2，重构中）
│       ├─ user/        用户与鉴权（sa-token）
│       ├─ admin/       管理后台（Dashboard）
│       └─ audit/       业务变更审计日志
├─ framework/   通用底座（约定 DTO/上下文/异常/错误码/分布式id/Trace/缓存/幂等/MQ 封装）
├─ infra-ai/    模型接入层（多供应商客户端/档位路由/熔断/首包探测/向量/rerank/vlm/算 token）
├─ mcp-server/  独立 MCP Server（工具注册/参数校验/执行），端口 9099
├─ frontend/    前端（对话界面 + 管理后台）
├─ resources/   docker compose / 数据库初始化 SQL / Lua 脚本
└─ docs/        配置指南 / 部署文档 / 运行链路
```

**一句话记住模块边界**：
- `infra-ai` = "怎么调通各种大模型/向量/重排"（能力供给）
- `framework` = "团队写业务想要的公共件"（干活的公共底座）
- `bootstrap/rag` = "怎么把这些拼成一个能思考的 Agent"（编排大脑）
- `bootstrap/core+knowledge+ingestion` = "文档怎么变成可检索的知识"（知识生产）

---

## 1.5 怎么跑起来

### 需要的基础中间件
| 中间件 | 端口 | 是否必须 |
|:---|:---|:---|
| PostgreSQL（**必须用 pgvector 镜像**） | 5432 | ✅ |
| Redis | 6379 | ✅ |
| RocketMQ | 9876/10911 | ✅ |
| MinIO/S3 | 9000 | ✅（OSS 可选） |
| Milvus | 19530 | ⚠️ 可用 `vector.type=pg` 替代 |
| Elasticsearch / LightRAG | 9200 / 9621 | ⚠️ 可选通道 |
| 任意一个 LLM API（Ollama/百炼/硅基/AIHub） | — | ✅ 至少一个 |

> ⚠️ **关键坑**：PG 必须用 `pgvector/pgvector:pg16` 镜像（普通 `postgres` 没有 vector 扩展，项目会启动失败报 `relation "t_knowledge_vector" does not exist`）。且**建表不是自动的**，需手动执行 `resources/database/schema_pg.sql` + `init_data_pg.sql`（详见 `docs/DEPLOYMENT.md`）。

### 快速启动（本仓库实测通过）
```bash
# 1) 起中间件（PG 用 pgvector 镜像）
docker run -d --name askora-pg -e POSTGRES_PASSWORD=postgres -e POSTGRES_DB=askora -p 5432:5432 pgvector/pgvector:pg16
docker run -d --name askora-redis -p 6379:6379 redis:7 redis-server --requirepass 123456
docker run -d --name askora-minio -p 9000:9000 -p 9001:9001 -e MINIO_ROOT_USER=rustfsadmin -e MINIO_ROOT_PASSWORD=rustfsadmin minio/minio server /data --console-address ":9001"
docker compose -f resources/docker/rocketmq-stack-5.2.0.compose.yaml up -d

# 2) 建表 + 初始化数据
docker exec -i askora-pg psql -U postgres -d askora -v ON_ERROR_STOP=1 < resources/database/schema_pg.sql
docker exec -i askora-pg psql -U postgres -d askora -v ON_ERROR_STOP=1 < resources/database/init_data_pg.sql

# 3) 编译打包（JDK17）
./mvnw -DskipTests package

# 4) 启动后端（默认端口 9090，context-path=/api/askora）
java -jar bootstrap/target/bootstrap-0.0.1-SNAPSHOT.jar
```

启动日志出现如下即成功：
```
Tomcat started on port 9090 (http) with context path '/api/askora'
Started AskoraApplication in 20.1 seconds
```

> 其中 MCP Server 未启动（连不上 9099）只会有一条 WARN，**不影响主应用启动**。

### 启动后端后再启动 MCP Server（可选）
```bash
./mvnw -pl mcp-server -am package -DskipTests
java -jar mcp-server/target/mcp-server-0.0.1-SNAPSHOT.jar   # 端口 9099
```

### 启动前端
```bash
cd frontend && npm install && npm run dev
```

### 默认账号
`application.yaml` 中 RocketMQ/DB 默认 `postgres/postgres`；初始化数据里的默认管理员账号为 `admin/admin`（当前实现为明文比对，见 `user/service/impl/AuthServiceImpl`）。

---

## 1.6 第一个请求长什么样（登录 → 问问题）

```bash
# 1) 登录拿 token
TOKEN=$(curl -s -X POST http://127.0.0.1:9090/api/askora/auth/login \
  -H "Content-Type: application/json" -d '{"username":"admin","password":"admin"}' | grep -o '"token":"[^"]*"' | cut -d'"' -f4)

# 2) 验证系统配置
curl -s http://127.0.0.1:9090/api/askora/rag/settings -H "Authorization: $TOKEN"
```

对 `/rag/chat/stream`（SSE）发起提问即可，前端用 EventSource 消费：
- 事件类型：`meta`（会话/任务标识）→ `message`（流式思考/正文）→ `finish`（来源/标题）→ `done`（结束）
- 字段含义与落库逻辑见 `rag/service/handler/StreamChatEventHandler.java`

---

## 1.7 怎么读这份代码（给新人的阅读路线）

不要从头读到尾，按"入口 → 大脑 → 零件"的顺序：

1. **入口**：`AskoraApplication.java` → `rag/controller/RAGChatController.java`（SSE 流式入口）
2. **大脑（最重要）**：`rag/service/pipeline/StreamChatPipeline.java` —— **一条方法看完整个问答流程**：记忆 → 改写 → 意图 → 歧义引导 → 检索 → Prompt → 流式输出
3. **对话服务**：`rag/service/impl/RAGChatServiceImpl.java`（限流 + Trace 编排）
4. **检索**：`rag/core/retrieval/RetrievalEngine.java` → `MultiChannelRetrievalEngine.java` → `RetrievalScopeResolver.java`
5. **模型层**：`infra-ai/.../model/ModelRoutingExecutor.java` + `ModelHealthStore.java`
6. **入库**：`core/ingest/DefaultIngestionKernel.java`（五阶段内核）

配套文档（推荐顺序）：
- [2-面试准备指南] → 高密度考点
- [3-程序文件详解] → 每个核心类讲清楚
- [4-项目完整流程] → 一次提问/一份文档的完整旅程
- [5-企业级演进路线] → 从 demo 到生产
- [7-踩坑日志] / [8-变化原因] / [9-排查日志] → 为什么这样设计、遇到过什么问题

---

## 1.8 设计上的三个总原则

1. **一切为了"回答可靠"**：问题理解（意图/改写/歧义）大幅减少错误召回；检索后置链（去重/RRF/Rerank）把最相关的证据顶到前面。
2. **一切为了"高可用"**：模型层三态熔断 + 线性故障转移；检索通道级超时降级，最慢的那路不能拖垮整体。
3. **一切为了"可观察"**：每个环节都有 `@RagTraceNode` 埋点，前端能看整条链路的每一步耗时与结果。
