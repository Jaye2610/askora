# Askora AI — 服务器部署方案

> 目标服务器部署场景。本项目的完整运行依赖一套本地/远端中间件，下面给出**资源清单、中间件栈、两种部署路径（单机 Docker / 云服务器）**、以及启动/验证步骤。
> 注意：当前 `application.yaml` 里所有服务都指向 `127.0.0.1`——上服务器部署时需把这些改为部署机地址或内网地址。

---

## 1. 需要哪些资源（服务器最低配）

| 项 | 建议最低 | 说明 |
|:---|:---|:---|
| CPU | 4 核 | 后端 JVM + Milvus/ES 均吃 CPU |
| 内存 | 8 GB（推荐 16GB） | Milvus 默认预留较多内存，RocketMQ 也要堆 |
| 磁盘 | 100 GB SSD | 存储文档/向量索引/消息日志 |
| OS | Ubuntu 22.04 / CentOS 7+ | Docker + docker compose v2 |
| 外网带宽 | 按问答并发 | LLM API 出站为主，SSE 长连接入站 |

**若只想最小跑通（无 Milvus/ES/图谱）**：可用 `storage.type=pg`（pgvector 替代 Milvus）、关掉 ES 与图谱通道，最低 2 核 4GB 也能起步，但检索能力缩水。

---

## 2. 中间件栈与端口约定

| 中间件 | 端口 | 用途 | 是否必须 |
|:---|:---|:---|:---|
| PostgreSQL | 5432 | 业务主库（会话/分块/追踪/配置） | ✅ 必须 |
| Redis | 6379 | 会话缓存/公平排队/热加载配置 | ✅ 必须 |
| RocketMQ | 9876(nameServer)+10911(broker) | 入库事务消息/异步任务 | ✅ 必须 |
| MinIO/S3 | 9000 | 文档/图片对象存储 | ✅ 必须（OSS 可选） |
| Milvus | 19530 | 向量库 | ⚠ 可用 pgvector 替代 |
| Elasticsearch | 9200 | 关键词检索通道 | ⚠ 可选通道 |
| LightRAG/Graph | 9621 | 知识图谱检索 | ⚠ 可选通道 |

另需**外部 LLM API**（`application.yaml`:186-203 已配置 base-url）：本地 Ollama(11434) / 通义千问 / 硅基流动 / AIHub，至少配一个可用。

---

## 3. 路径 A：单机 Docker compose（推荐）

> ⚠ **实测关键坑**：本项目向量检索用 pgvector，**必须用带 pgvector 的 PostgreSQL 镜像**（`pgvector/pgvector:pg16`），普通 `postgres:16` 没有 vector 扩展会导致后端启动失败（报 `relation "t_knowledge_vector" does not exist`）。**且建表不是自动的**，需手动执行 `resources/database/schema_pg.sql` + `init_data_pg.sql`。

步骤：
1. 安装 Docker + docker compose v2。
2. 起基础中间件（**PG 必须用 pgvector 镜像**）：
   ```bash
   docker run -d --name askora-pg -e POSTGRES_PASSWORD=postgres -e POSTGRES_DB=askora -p 5432:5432 pgvector/pgvector:pg16
   docker run -d --name askora-redis -p 6379:6379 redis:7 redis-server --requirepass 123456
   docker run -d --name askora-minio -p 9000:9000 -p 9001:9001 -e MINIO_ROOT_USER=rustfsadmin -e MINIO_ROOT_PASSWORD=rustfsadmin minio/minio server /data --console-address ":9001"
   ```
3. 起 RocketMQ（用项目自带 compose；MinIO 已覆盖对象存储，vector 用 pgvector 时**无需 Milvus**）：
   ```bash
   cd resources/docker
   docker compose -f rocketmq-stack-5.2.0.compose.yaml up -d
   ```
4. **建表 + 初始化数据（手动执行，项目不用 Flyway）**：
   ```bash
   docker exec -i askora-pg psql -U postgres -d askora -v ON_ERROR_STOP=1 < resources/database/schema_pg.sql
   docker exec -i askora-pg psql -U postgres -d askora -v ON_ERROR_STOP=1 < resources/database/init_data_pg.sql
   ```
5. 改 `application.yaml`：把 `127.0.0.1` 全改为容器名 / 宿主机 IP / docker network（含 `rmqbroker` 的 `brokerIP1`）。
6. 后端打包并启动（**JDK17**）：
   ```bash
   # Windows
   set JAVA_HOME=<JDK17路径>&& mvnw.cmd -pl bootstrap -am package -DskipTests
   # 或 git bash
   JAVA_HOME=<JDK17路径> ./mvnw.cmd -pl bootstrap -am package -DskipTests
   java -jar bootstrap/target/bootstrap-0.0.1-SNAPSHOT.jar
   ```
   启动后访问 `http://<host>:9090/api/askora/...`，登录接口为 `POST /api/askora/auth/login`（默认 admin/admin）。
7. 前端：`cd frontend && npm ci && npm run build`（或 `npm run dev`），产物在 `dist/`，用 nginx 托管并反代 `/api` 到后端 9090（dev 模式由 vite 代理 `localhost:9090`）。

---

## 4. 路径 B：云服务器（阿里云/腾讯云等）

1. **规格**：按 §1，至少 4C8G。
2. **安全组放行**：9090（后端，或仅内网+nginx）、5173（dev，可选）、9000、5432、6379、9876、19530（如不对外则走内网/VPC，不建议都暴露公网）。
3. **域名+HTTPS**：nginx 托管前端 dist，`/api` 反代到本机 9090；配 SSL 证书。
4. **对象存储**：可选切 OSS（`storage.type=oss` + AccessKey），避免自建 MinIO。
5. **数据库**：可用云 PostgreSQL/Redis 托管，内网地址填进 `application.yaml`。
6. 其余同 §3 的 4-7。

---

## 5. 关键配置项（上服务器必改）

| 配置 | 现值 | 说明 |
|:---|:---|:---|
| `server.port` | 9090 | 后端端口 |
| `spring.datasource.url` | `127.0.0.1:5432/askora` | 换部署机 DB 地址 |
| `spring.data.redis.host` | 127.0.0.1 | 换 Redis 地址 |
| `rocketmq.name-server` | 127.0.0.1:9876 | 换 MQ 地址 |
| `rag.storage.s3.endpoint` | localhost:9000 | 换 MinIO/内网端点 |
| `rag.storage.kb-bucket/asset-bucket` | `askora-sources/assets` | 桶名全局唯一，生产按需覆盖 |
| 各 LLM `base-url` | 本地Ollama/云API | 至少保证一个可用 |

> 前端 `vite.config.ts` 里 `/api` 代理指向 `http://localhost:9090`；生产走 nginx 反代，无需保留该 proxy。

---

## 6. 验证清单（部署后）

- [ ] 后端健康：`curl http://<host>:9090/api/askora/...` 返回正常（接口前缀 `/api/askora`）。
- [ ] 前端：浏览器访问域名，登录页正常（Askora AI 品牌已生效）。
- [ ] 建知识库 → 传 1 份 PDF → 提问 → 能流式回答 + 出引用来源。
- [ ] 入库走 RocketMQ 事务消息，知识库可删。
- [ ] 并发压测（可选）：k6/`scripts/sse_queue_test.sh` 打 100 并发看公平排队与错误率。
- [ ] Trace 后台能看到本次问答的 span/Token/延迟。

---

## 7. 风险与提示

- **本部署方案是"可执行的指南"，我无法在此环境实际拉通云服务器**——需要你有可访问的服务器/Docker 环境。
- 若你只是想**本地跑通验证效果**，我建议先走 §3 单机 Docker；我可以帮你逐条执行（起中间件、建库、打包、起前后端），前提是本地有 Docker 且能拉镜像。
- `application.yaml` 的 `127.0.0.1` 在容器化部署时要么改主机名要么用 docker network。
