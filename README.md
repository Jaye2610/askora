<p align="center">
  <img src="frontend/public/askora-logo.svg" alt="Askora AI" width="96" height="96">
</p>

<p align="center">
  <strong>Askora AI — 面向 Agentic RAG 演进的企业级智能问答平台</strong><br/>
  <em>Ask 提问 · Ora 智慧之源 · 从文档入库到智能问答的全链路生产级工程实践</em>
</p>

---

## 📌 项目简介

Askora AI 是一个面向 **Agentic RAG 演进**的生产级 Java AI 应用平台，覆盖从文档入库、多路检索、智能问答到可观测性的完整链路。

- **混合检索**：向量(Milvus/pgvector) / 关键词(ES) / 知识图谱(LightRAG) / 联网搜索 四路并行召回，支持去重、RRF 融合与 Rerank 精排。
- **问题理解**：查询词映射、问题重写与拆分、树形意图识别、多知识库路由与歧义澄清。
- **模型治理**：多模型档位路由、首包探测、三态熔断降级，保障 LLM 服务高可用。
- **会话记忆**：最近 N 轮消息 + 持久化 LLM 摘要，控制 Token 成本并保留关键上下文。
- **流量保护**：Redis 公平排队 + 分布式并发控制，避免突发请求压垮模型服务。
- **知识闭环**：可编排入库 Pipeline、远程刷新、回答溯源、用户反馈、Trace 和管理后台。

> 生产落地智能体会踩的坑，这里都有对应方案 —— 一套经过真实场景锤炼的工程实践。

---

## 🧰 技术栈

| 类别 | 技术 |
|:---|:---|
| 后端 | Java 17 · Spring Boot 3 · Maven（多模块）|
| 前端 | React 18 · TypeScript · Vite · Tailwind CSS · Radix UI · Zustand |
| 存储 | PostgreSQL + pgvector · Redis · Milvus(可选) · Elasticsearch(可选) · MinIO/S3/OSS |
| 队列 | RocketMQ（事务消息）|
| AI/LLM | OpenAI 兼容协议 · 阿里百炼 · 硅基流动 · AIHubMix · 本地 Ollama · MCP SDK |
| 检索 | 向量 · 关键词(BM25) · 知识图谱(LightRAG) · 联网搜索 · RRF + Rerank |

## 📁 模块结构

```
├─ bootstrap/      后端主应用（Controller / Service / RAG 编排 / 管理后台接口）
├─ framework/      通用底座（缓存/幂等/鉴权/错误码/分布式 id/Trace/Web）
├─ infra-ai/       模型接入层（多供应商客户端 / 档位路由 / 熔断 / 首包探测 / 向量）
├─ mcp-server/     独立 MCP Server 模块（工具注册 / 参数校验 / 执行）
├─ frontend/       前端（对话界面 + 管理后台）
├─ resources/      docker compose 编排 / 数据库初始化 SQL / Lua 脚本
└─ docs/           配置指南 / 部署文档 / 运行链路
```

---

## 🚀 快速启动

### 前置依赖

- **JDK 17**（后端）
- **Node.js 18+**（前端，推荐 20+）
- **Docker**（中间件：PostgreSQL+pgvector / Redis，也可手动装）

### 方式一：用 Docker 起中间件 + 本地跑前后端（开发推荐）

**1. 起中间件**

PostgreSQL（**必须带 pgvector**）：
```bash
docker run -d --name askora-pg -e POSTGRES_PASSWORD=postgres -e POSTGRES_DB=askora -p 5432:5432 pgvector/pgvector:pg16
```
Redis：
```bash
docker run -d --name askora-redis -p 6379:6379 redis:7 redis-server --requirepass 123456
```
（可选）MinIO / RocketMQ：用 `resources/docker/` 提供的 compose 或对应镜像启动。RocketMQ 单独起见 `resources/docker/rocketmq-stack-5.2.0.compose.yaml`。

**2. 初始化数据库**（项目不自动建表，需手动执行）：
```bash
docker exec -i askora-pg psql -U postgres -d askora -v ON_ERROR_STOP=1 < resources/database/schema_pg.sql
docker exec -i askora-pg psql -U postgres -d askora -v ON_ERROR_STOP=1 < resources/database/init_data_pg.sql
```
> 默认账号 admin / admin。

**3. 启动后端**（仓库根目录）：
```bash
# Windows
set JAVA_HOME=<你的JDK17路径>
./mvnw -pl bootstrap -am package -DskipTests
java -jar bootstrap/target/bootstrap-0.0.1-SNAPSHOT.jar
```
后端默认 `http://127.0.0.1:9090`，接口前缀 `/api/askora`。登录：`POST /api/askora/auth/login`。

**4. 启动前端**：
```bash
cd frontend
npm install
npm run dev
```
前端开发服务器默认 `http://127.0.0.1:5173`，已配置 `/api` 代理到后端 9090。

### 方式二：仅前端（需后端 + 中间件已就绪）
前端可单独起，但登录/问答依赖后端与中间件，开发请按方式一起齐。

---

## ⚙️ 配置说明

全部后端运行配置集中在：`bootstrap/src/main/resources/application.yaml`

| 配置分组 | 用途 |
|:---|:---|
| `server` | 后端端口 / 接口前缀 |
| `spring.datasource` | PostgreSQL 连接 |
| `spring.data.redis` | Redis 连接 |
| `rocketmq` | RocketMQ NameServer / 生产组 |
| `rag.storage` | 对象存储（MinIO / OSS，含桶名）|
| `rag.vector / keyword / graph` | 三种检索后端开关与地址 |
| `rag.default` | 默认向量集合 / SSE 超时 |
| `rag.search` | 检索漏斗预算 / 通道开关 / RRF 融合权重 |
| `rag.rate-limit / memory / semaphore` | 限流 / 记忆 / 并发信号量 |
| `ai.providers / chat / embedding / rerank / vlm` | 模型供应商、档位、候选（核心）|
| `mineru` | MinerU 文档解析（需 API Key）|
| `sa-token` | 认证鉴权 |

**让问答真正跑起来必须配**：至少一个模型供应商的 API Key（`bailian` / `siliconflow` / `aihubmix` 之一），以及 `mineru.api-key`（文档解析）。

📄 每个 key 的用途 / 可选值 / 修改方法：**[docs/CONFIG-GUIDE.md](docs/CONFIG-GUIDE.md)**

---

## 🗂️ 部署

- 生产部署完整步骤（中间件 / 建库 / 打包 / 启动 / nginx）：**[docs/DEPLOYMENT.md](docs/DEPLOYMENT.md)**
- 关键提醒：PostgreSQL 必须用 **pgvector** 镜像；建表需手动执行 `schema_pg.sql`；生产环境数据库等端口不对公网开放，网页走 nginx + 80 端口，安全组放行 80。

---

## 🧭 运行链路（一次提问）

```
用户提问
  → RAGChatController.chat()         入口：SSE + 幂等提交
  → RAGChatServiceImpl.streamChat()  排队 + 链路追踪
  → StreamChatPipeline.execute()     记忆 → 重写 → 意图 → 检索 → 生成
       ├─ 歧义引导 / 系统直答 / 空检索  三种短路
       └─ 多路检索 + 后处理 → LLM 流式生成 → SSE 输出
```

📄 完整链路（含断点跟读顺序）：**[docs/run-chain.md](docs/run-chain.md)**

---

## 🤝 文档导航

| 文档 | 内容 |
|:---|:---|
| `docs/CONFIG-GUIDE.md` | 所有配置 key 的用途 / 修改方法 |
| `docs/DEPLOYMENT.md` | 单机 Docker / 云服务器部署 |
| `docs/run-chain.md` | 问答主链路代码阅读指南 |
| `docs/agent-harness-refactor.md` | Agent Harness 骨架演进方案 |

---

## 📄 协议

[Apache License 2.0](LICENSE)
