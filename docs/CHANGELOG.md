# 改动日记

> 本文件记录**结构性改动**的动机、范围与验证方式。
> 业务功能变更请走后台「变更日志」（`biz_change_log`），这里只记工程侧的品牌、设计系统与布局演进。
> 约定：每条记录必须带**验证证据**（跑过的命令 + 输出），未验证的不写入。

---

## 2026-XX-XX · 品牌收口：AskoraLogo 单一来源 + 旧标识清零

### 背景

一次品牌审计发现三件事：

1. **同一个品牌标识有 4 种画法、3 种概念。** 聊天页是「?」问号 + 圆环的矢量图，登录页是 lucide `Sparkles`（满天星），后台侧栏是 lucide `Bot`（机器人），favicon 则是用 `<text font-family="Arial">?</text>` 画的 —— 后者依赖系统字体，跨平台渲染不一致。用户在不同页面看到的是三个不同的 logo。
2. **同一份「?」矢量 path 被内联复制了 3 遍**（`Sidebar.tsx` 两处 + `constants/avatars.ts`），加上 `public/askora-avatar.svg` 共 4 份。改一次要改四五个地方。
3. **旧品牌标识（Ragent）大量残留在文档、包名、Docker 资源与数据库脚本里**，且部分文档描述与代码实际值已经不一致（属文档 bug，不是有意保留的历史）。

### 改动

#### 1. 新建品牌单一来源

`frontend/src/components/brand/AskoraLogo.tsx`

- `<AskoraLogo size variant="mark" | "mark-with-ring" | "wordmark" title? />`
- 问号光标用**纯 path** 绘制，不带 `font-family` —— 这是 favicon 早先跨平台不一致的根因。
- 导出 `ASKORA_MARK_STEM` / `ASKORA_MARK_RING` / `ASKORA_MARK_DOT` / `ASKORA_BRAND_FROM` / `ASKORA_BRAND_TO` / `askoraMarkSvgBody()`，让 React 组件与静态 SVG / data-URI 共用同一份几何。
- `title` 属性决定无障碍语义：不传即 `aria-hidden`（纯装饰），传了则渲染 `<title>` 供屏幕阅读器朗读。

#### 2. 消除重复引用

| 位置 | 之前 | 现在 |
|---|---|---|
| `components/layout/Sidebar.tsx` 品牌块 | 内联 SVG（约 22 行） | `<AskoraLogo size={40} title="Askora AI" />` |
| `components/layout/Sidebar.tsx` 用户头像 | 内联 SVG（同一份拷第 2 遍） | `<AskoraLogo size={32} className="!rounded-full" />` |
| `pages/LoginPage.tsx` 左侧品牌区 | lucide `Sparkles` | `<AskoraLogo size={56} title="Askora AI" />` |
| `pages/LoginPage.tsx` 小屏登录卡 | lucide `Sparkles` | `<AskoraLogo size={44} />` + 补上 "Askora AI" 文字 |
| `pages/admin/AdminLayout.tsx` 侧栏品牌 | lucide `Bot` | `<AskoraLogo size={36} title="Askora AI" />` |
| `constants/avatars.ts` | 第 4 份手写 path | 改用 `askoraMarkSvgBody()` |

#### 3. 静态资源与页面元信息

- `public/favicon.svg` —— 去掉 Arial `<text>`，改为纯 path 版本。
- `public/askora-avatar.svg` —— 与组件几何对齐。
- `public/askora-logo.svg` —— 新建，供 README 引用。
- `index.html` —— `lang="en"` → **`lang="zh-CN"`**（全站 UI 是中文，原来的值是 a11y bug）；补 `theme-color`、`description`、`viewport-fit=cover`。
- `README.md` —— 删除指向 `assets/ragent-ai-banner.png` 的裂图 `<picture>`（`assets/` 目录已被删除，该引用必然 404），改为引用 `frontend/public/askora-logo.svg`，不再引入二进制图片依赖。

#### 4. 旧标识清零

**先核实、再替换。** 以下每一条都先对着代码确认"文档写错了"，才动手：

| 文档原值 | 代码实际值 | 证据 |
|---|---|---|
| context-path `/api/ragent` | `/api/askora` | `bootstrap/src/main/resources/application.yaml:4` |
| 库名 `ragent` | `askora` | `application.yaml:20`（`jdbc:postgresql://127.0.0.1:5432/askora`） |
| 桶名 `ragent-sources` / `ragent-assets` | `askora-sources` / `askora-assets` | `application.yaml:51-52` |
| 生产者组 `ragent-producer...` | `askora-producer...` | `application.yaml:38` |
| 平台开关前缀 `ragent.*` | `askora.*` | `application.yaml:41` |
| 内部锚点 `data-ragent-doc-id` | `data-askora-doc-id` | `CitationContextEnricher.java:48` |

改动文件：

- 前端：`package.json` + `package-lock.json`（`ragent-frontend` → `askora-frontend`，**两处同改**，否则 `npm ci` 报 lock 不一致）、`TESTING.md`（同时修掉 8080 → 9090，原文档端口与 `vite.config.ts`、README 都不符；并删掉泄露的原作者本地路径 `/Users/machen/workspace/nageoffer/ragent/frontend`）
- 文档：`docs/CONFIG-GUIDE.md`、`docs/DEPLOYMENT.md`、`docs/run-chain.md`、`docs/examples/pdf-ingestion-example.md`
- 资源：`resources/database/{schema,init_data}_pg.sql` 头注释、`resources/docker/graphrag/{.env.example,lightrag-neo4j-stack.compose.yaml,README.md}`
- 历史叙述文档中的旧名一并移除：`docs/Agent核心/{1-入门说明-README,5-企业级演进路线,7-踩坑日志,8-变化原因}.md`、`docs/Agent核心/Memory/00-文档索引.md`

#### 5. 运行时标识改名（有升级成本，已写回滚表）

`resources/docker/graphrag/` 三项运行时标识随品牌改名，**它们不是纯文案，重命名有成本**，已在 `resources/docker/graphrag/README.md` 的「从旧标识升级」一节写明影响与回滚方式：

| 标识 | 新值 | 影响 | 回滚 |
|---|---|---|---|
| Neo4j 镜像 | `askora/neo4j-gds:5.26-2.13.11` | 仅本地重新构建一次（该镜像非 Docker Hub 分发） | 改回 `image:` 一行 |
| `NEO4J_PASSWORD` | `askora123456` | **已存在的 Neo4j 数据卷不会随之改密码**，直接改 `.env` 会连不上 | `.env` 改回旧密码，或 `docker compose down -v` 重建卷（丢图数据） |
| `POSTGRES_DATABASE` | `askora` | LightRAG 的 KV/向量/文档状态表原先写在旧库，改名后会去 `askora` 找（与后端一致），**旧库图索引不会自动迁移** | 改回旧库名，或 `ALTER DATABASE <旧库名> RENAME TO askora` |

#### 6. IDE 项目图标重生成

- 删除 `.idea/icon.png`（旧品牌位图，且模型无法在此环境核验其内容），取消 `.gitignore` 中 `!.idea/icon.png` 例外。
- 新建 `.idea/icon.svg` 用 Askora 标识，`.gitignore` 例外改为 `!.idea/icon.svg`。
  **为什么换扩展名**：位图需要图像工具生成，SVG 可以由源码直接产出、可 diff、且与 `AskoraLogo` 几何同源。

#### 7. 顺带修掉的既存死代码

这两处不是本次品牌改动引入的，但它们挡着 `tsc`，一并清掉：

- `components/layout/Sidebar.tsx` —— 解构了 `createSession` 却从未使用（TS6133）。
- `pages/LoginPage.tsx` —— 导入了 `MessagesSquare` 却从未使用（TS6133）。

### 验证证据

```bash
# 1. 类型检查：24 个错误，全部落在 4 个本次未改动的文件（改动前是 26 个）
npx tsc -p tsconfig.app.json --noEmit
#   12  src/pages/admin/knowledge/KnowledgeDocumentsPage.tsx   ← react-hook-form 泛型不匹配
#    9  src/stores/chatStore.ts
#    2  src/components/chat/FeedbackButtons.tsx                ← onOpenAutoFocus 不在 DropdownMenuContentProps
#    1  src/pages/admin/ingestion/IngestionPage.tsx
#   → 本次改动涉及的文件：0 错误

# 2. 打包：通过
npx vite build
#   ✓ built in 16.02s   EXIT=0

# 3. 旧标识清零
grep -ri "ragent\|nageoffer\|moacode" --exclude-dir={node_modules,.git,dist,target}
#   → 仅剩 .workbuddy/ 与 .zcode/ 两处本地产物（见下「已知未处理」）

# 4. 内联字体栈清除（设计系统收敛的前置条件）
grep -rn "fontFamily" frontend/src   # → 0
```

**重要：`npm run build` 在本仓库目前会失败**，因为它等于 `tsc -b && vite build`，而 tsc 有 24 个**既存**错误。这是改动前就存在的状态，不是本次引入。绿灯命令是 `npx vite build`。

### 已知未处理

- **`.workbuddy/**`** 与 **`.zcode/plans/**`** 仍有旧标识：前者是本地 agent 记忆与简历准备笔记（曾在其中以旧名指代本项目），后者是一次历史迁移方案。两者都是**本地产物 / 历史档案**，删除不可逆，**保留**。`.workbuddy/scan_comments.py` 指向的已是失效路径（旧仓库目录已不存在），如需继续用需改 `ROOT` 常量。
- **`.idea/workspace.xml`、`.idea/dataSources/*.xml`** 仍含旧包名与旧库名。这两份是 IDE 生成的状态文件，下次 reimport / 重连数据源会自动刷新，手改意义不大。
- **暗色模式已打通但覆盖面有限**：`.dark` 令牌已补全、切换按钮已接入（见 2.3），但外壳与登录页之外（侧栏内部、聊天气泡、admin 卡片/表格）仍硬编码浅色，完整覆盖属 2.4。
- **`--dsw-*` 等旧变量已删除**（见下节 2.2）。
- **设计令牌层大半是死代码**：`globals.css` 共定义 143 个自定义属性，其中 **80 个零引用**，包括**整组** `--bg-*` / `--text-*` / `--accent-*` / `--border-*` / 状态色 / 圆角 / 间距 / 阴影 / 字号 / 字重 / 行高 / 过渡 / 布局令牌，以及一组 `--trace-*`（9 个）。原因是 TSX 里 279 处硬编码 hex、`admin-*` BEM 规则里也直接写颜色，**令牌层无人消费**。→ 这使 2.4（令牌化）成为「三套合一」真正有意义的前提。
- `styles/globals.css` 仍是单文件 **3174 行 / 80042 字节**，内含 464 条 `admin-*` BEM 规则并行的第二套设计系统。**拆分尝试已失败，原因见下节。**

### 下一步（设计系统与布局）

1. ~~`globals.css` 拆为 6 个文件~~ —— **已验证不可行，见下节**。
2. 三套变量收敛为一套，删掉已确认无引用的 `--dsw-*`。
3. 暗色模式二选一：补全 `.dark` 令牌 + 在 `Header.tsx` / admin 顶栏接上切换按钮，**或**彻底删除 `themeStore` 与 `.dark` 规则。
4. 硬编码色收敛：`frontend/src/**/*.tsx` 现有 279 处 hex、56 处 `bg-white` → 语义令牌 / `bg-background`、`bg-card`。
5. 统一滚动模型（`min-h-screen` 嵌套 + `body{overflow:hidden}` 打架）、`max-w-[840px]` → `--content-max-width`、`Sidebar.tsx` 与 `AdminLayout.tsx` 拆分。

---

## 2026-XX-XX · 阶段 2.1 结论：`globals.css` 无法按 `@layer` 拆分（附一次事故复盘）

### 结论（先看这条）

**在当前 Vite 5 + Tailwind v3 + `postcss.config.cjs` 流水线下，`styles/globals.css` 不能拆成多个文件。** 干净拆分需要先改构建流水线，而不是改 CSS。

### 为什么

Tailwind v3 只会在**同时存在 `@tailwind components` 指令的那个文件**里保留 `@layer components { }` 的内容。而 Vite 对每个 CSS 文件**分别**跑 PostCSS，所以把 `@layer components` 搬到另一个文件后，该层内容会被**整块丢弃**。

实测三种方案（每次都取了编译产物做比对，不是推测）：

| 方案 | 结果 |
|------|------|
| `@import` 放在 `@tailwind` **之后** | ❌ 产物 199.05 kB → **102.52 kB**；`.admin-layout` 命中数 **502 → 0**，`--bg-primary` 归 0。后台彻底失去样式。 |
| 同上 + `postcss.config.cjs` 加 `postcss-import` | ❌ 产物与上一行**逐字节相同**（哈希 `42F271D0…`）—— 说明 Vite 已自行处理 `@import`，显式插件是空操作。 |
| `@import` 放在 `@tailwind` **之前** | ⚠️ 内容完整存活（199 052 字符、`.admin-layout` 502/502），但**层叠顺序被翻转**。 |

第三种为什么不能直接收：实测字符位置对比——

```
基线（原文件）: .admin-layout(18742) → .from-blue-500 工具类(136487) → --bg-primary(163854) → .prose h1(167861)
拆分后　　　　: --bg-primary(602) → .prose h1(4609) → .admin-layout(27823) → 工具类(145568)
```

即：原本「无 layer 的 `.prose` / `.chat-surface` / `.sidebar-scroll` 等规则**排在 utilities 之后、能覆盖工具类**」；拆分后它们跑到 utilities **之前**，「工具类反过来覆盖它们」。这会改变 `MarkdownRenderer` 等处的实际渲染，**属于层叠架构变更，必须过第 6 阶段的浏览器逐页核对**，不能在无人验证时静默落地。

CSS 规范又要求 `@import` 必须位于其它规则之前，因此「`@import` 在顶 + 工具类在最后」这个唯一能保住原顺序的写法，语法上就不成立。**结论：此路不通。**

### 要拆的话，先动这些

任选其一，然后再谈拆文件：

1. 让 `@import` 在 Tailwind 之前被内联（自定义 Vite 插件 / 把 `tailwindcss` 换成显式 `postcss-import` 前置且绕过 Vite 自带的 CSS 处理）。
2. 或者接受层叠翻转，把它当作**有意的架构调整**（工具类归位到最末，是更常规的顺序），并配套第 6 阶段逐页浏览器核对。
3. 或者只提取**层叠上惰性**的部分。目前唯一已证明惰性的是设计令牌（`--x` 自定义属性不参与与工具类的竞争）；但单独抽一个 `tokens.css` 收益有限，等 2.2 真要做令牌收敛时一并处理。

### 事故复盘（必须留档）

拆文件的收尾脚本里，我用**元素下标**去 `slice` 一个「每个元素是一整段多行文本」的数组，却按**行号**算的偏移，导致：

1. `globals.css` 被覆写成 391 字节的残桩；
2. 同时 `unlinkSync` 删掉了 `base/markdown/admin/pages/settings.css` 五个中间文件；
3. 即**源文件被自己删掉**。

恢复过程与结论：

- HEAD 里的 `globals.css` 是 **3173 行**，而工作区的是 **3174 行** —— 说明工作区那份带着**未提交的本地改动**，`git checkout` 不能完整还原。
- 在 `AppData` 下查过 VS Code / Cursor 历史目录与 JetBrains LocalHistory，均未命中内容副本；`node_modules/.vite` 只缓存 deps，不含 CSS。
- 改用**编译产物反推**：拿事故前抓的基线产物与实际构建产物做全量 diff，**逐条列出差异**并回写源码。
- 最终结果：全量 diff 只存在 **3 个自定义属性** 差异（零条其它声明差异），即未提交改动就是以下四项，已逐条回写：

  | # | 丢失内容 | HEAD 里的值 |
  |---|----------|-------------|
  | 1 | 新增 `--accent-violet: #7c3aed;` | 不存在 |
  | 2 | `--gradient-primary` 末段 | `#2563eb`（应为 `#7c3aed`） |
  | 3 | `--gradient-light` 末段 | `#dbeafe`（应为 `#f3e8ff`） |
  | 4 | 第 44 行注释 | `（蓝色系）`（应为 `（Askora 蓝紫品牌）`） |

- **验收判据**：回写后重新构建，产物哈希回到基线值 ——

  ```
  C667E0758B225711D699E6AB51D53C581CBE2116784CA08AB29A204806F14BBE   ✅ 与事故前基线一致
  产物文件名亦回到 index-D83yYFm1.css
  ```

**遗留不确定性（照实说）**：注释不参与编译产物，所以第 4 项之外的**注释级改动无法被反推出来**，只能确认「行为与事故前逐字节一致」。若那片区域还有纯注释改动，需要你自己回想。

### 教训

**改一个有 3000+ 行、且工作区尚未提交的文件之前，先复制一份到仓库外的临时目录。** 这次是靠「先抓基线编译产物 + 全量 diff 反推」才救回来的；那个基线是我为验证层叠而顺手抓的，属于侥幸。

---

## 2026-XX-XX · 阶段 2.2：变量收敛（删掉第三套），并查出令牌层 80/143 是死代码

### 改动

`globals.css` 原本有三套自定义属性：

| 集合 | 位置 | 去向 |
|------|------|------|
| HSL 契约变量（`--background` / `--primary` / `--ring` …） | `:root` 开头 | **保留** —— `tailwind.config.cjs` 靠 `hsl(var(--primary))` 等消费它，是 shadcn/Radix 的接口 |
| 「新设计系统」语义令牌（`--bg-*` / `--text-*` / `--accent-*` …） | 紧随其后 | **保留** —— 它是 2.4 令牌化的目标词汇表 |
| 「旧变量保留（兼容性）」 | 块尾 | **删除** —— 兼容期已结束 |

具体动作：

1. 删除整个「旧变量保留（兼容性）」块，共 **11 个变量**：`--sidebar-bg`、`--sidebar-item-active`、`--sidebar-item-hover`、`--primary-color`、`--primary-light`、`--primary-dark`、`--secondary-color`、`--gradient-primary`、`--gradient-light`、`--dsw-alias-brand-primary-invert`、`--dsw-specific-sidebar-nav-item-active-accent`。
2. `--gradient-primary` 是唯一还有消费者的（`.text-gradient`），按新命名习惯归入强调色组，改名 **`--accent-gradient`**，并同步 `.text-gradient` 的 `background`。

### 删除前的引用核实（两遍，因为第一遍有 bug）

- 第一遍只搜了 `frontend/src` 并排除 `globals.css`：11 个变量全为 0 引用。
- **第一遍不够** —— 变量也可能在 `globals.css` 内部被 `var()` 消费。补搜后抓到 `--gradient-primary` 有 1 处消费者（L221 `.text-gradient`），于是它改为改名而非删除。
- 审计脚本第一版还有个 bug：把 `globals.css` 混进了「使用方」集合，导致每个变量都匹配到自己的定义行，报出「0 个零引用」。修正后得出 80/143。

### 验证证据

拿构建产物与事故前的基线逐条比对（声明集合级 diff，非哈希 —— 因为改名必然改变产物）：

```
仅基线有 12 条 / 仅现有有 2 条
  移除: --sidebar-bg、--sidebar-item-active、--sidebar-item-hover、--primary-color、
        --primary-light、--primary-dark、--secondary-color、--gradient-primary、
        --gradient-light、--dsw-alias-brand-primary-invert、--dsw-specific-…、
        background:var(--gradient-primary)
  新增: --accent-gradient、background:var(--accent-gradient)
```

**除此之外没有任何其它声明差异** —— 即没有误删、没有连带改动。

```
npx vite build                          → ✓ 198.70 kB（原 199.05 kB，减的正是那 11 条声明）
npx tsc -p tsconfig.app.json --noEmit   → 24 errors（与改动前一致，全在未改动的 4 个文件）
```

还原方式：改动前的 `globals.css` 已备份到 `%TEMP%\globals.css.bak-20260928-212021`。

### 查出的更大问题（待 2.4 处理）

`globals.css` 定义 **143** 个自定义属性，**80 个零引用**。零引用清单里包含**整组**「新设计系统」令牌：

- 背景：`--bg-primary` / `--bg-tertiary` / `--bg-hover` / `--bg-active`
- 文字：`--text-secondary` / `--text-muted` / `--text-on-accent`
- 强调色：`--accent-primary` / `--accent-secondary` / `--accent-violet` / `--accent-light` / `--accent-hover`
- 边框：`--border-light` / `--border-focus` / `--border-accent`
- 状态色：`--success` / `--warning` / `--error` / `--info`
- 圆角 / 间距 / 阴影 / 字体 / 字号 / 字重 / 行高 / 过渡 / 布局：共 40 余个
- `--trace-*` 一组 9 个（`--trace-space-32`、`--trace-kpi-*` …）

**为什么没人用**：TSX 里有 **279 处硬编码 hex**、56 处 `bg-white`；`admin-*` 的 464 条 BEM 规则也直接写颜色。令牌层建好了却没人接。

**本次没有删这 80 个**，这是有意决定：它们是 2.4 令牌化的目标词汇表，删了反而要重建。但必须明确 —— 在 2.4 完成之前，「三套变量收敛为一套」只是形式上成立（少了一套死变量），**真正的收敛要等消费方从硬编码改成读令牌**。

---

## 2026-XX-XX · 阶段 2.3（方案 A）：打通暗色模式

### 之前的状况

`.dark` 是空壳：

```css
.dark { color-scheme: light; }   /* 声明自己是浅色，等于没写 */
```

而 `themeStore` 里的 `toggleTheme` **没有任何 UI 调用**，全仓只有 `MarkdownRenderer.tsx` 读 `theme`。即「有开关、有状态、有持久化，就是没按钮，也没样式」。

### 改动

1. **`.dark` 令牌块补全**（取代原来的空壳）：
   - `color-scheme` 改为 `dark`；
   - 覆盖**整组 HSL 契约变量**（`--background` / `--foreground` / `--card` / `--popover` / `--secondary` / `--muted` / `--accent` / `--destructive` / `--border` / `--input` / `--ring` / `--chat-user` / `--chat-assistant` / `--glow`）—— 因为 `tailwind.config.cjs` 靠 `hsl(var(--x))` 消费，漏一个就会在暗底上留下浅色块；
   - 覆盖随主题变化的话义令牌（`--bg-*` / `--text-*` / `--border-*` / `--shadow-*` / `--accent-light`）；
   - **不覆盖**尺寸类令牌（圆角 / 间距 / 字号 / 字重 / 行高）—— 它们与主题无关，重复定义只会制造两份事实。
2. **`.dark .glass`** 收口（毛玻璃在暗底上不能还是 `rgba(255,255,255,.7)`）。
3. **外壳链路改用令牌**（这是让开关不難看的最小集）：
   - `globals.css` 的 `body`：`bg-[#FAFAFA] text-gray-900` → `bg-[var(--bg-secondary)] text-[var(--text-primary)]`（原先 `text-gray-900` 是硬编码，暗底上会是黑字）。
   - `MainLayout.tsx`：外层 `bg-[#FAFAFA]`、内层与 `<main>` 的 `bg-white` → 令牌（3 处）。
   - `Header.tsx`：`bg-white/80` → `bg-[color-mix(in_srgb,var(--bg-primary)_80%,transparent)]`（保留半透明，故不能写成 `bg-[var(--bg-primary)]/80`：Tailwind 无法对 `var()` 应用透明度修饰符）；顺带把 `text-gray-900` / `text-gray-500` / `hover:bg-gray-100` 换成令牌。
   - `ChatPage.tsx`：两处 `bg-white` → 令牌。
4. **切换按钮接上**：
   - `components/layout/Header.tsx`：右侧新增 Sun/Moon 按钮，`aria-label` 与 `title` 随当前主题变化。
   - `pages/admin/AdminLayout.tsx`：顶栏右侧同步新增一个。
   - 持久化本已就绪（`utils/storage.ts` 的 `askora_theme` + `main.tsx` 的 `initialize()`），无需改动。

### 顺带修正：阶段 1 漏掉的第 5 份内联 logo

`AdminLayout.tsx` 用户菜单里的头像还是**内联写死的「?」矢量图**（第 5 份拷贝，阶段 1 未发现），已改为 `<AskoraLogo size={32} className="!rounded-full" />`。

### 验证证据

```bash
npx vite build   → ✓ 200.37 kB（2.2 后为 198.70 kB，增量即上述 .dark 与令牌化）
npx tsc -p tsconfig.app.json --noEmit  → 24 errors，仍全部落在未改动的 4 个文件
```

对构建产物逐项核对（均已在产物中命中）：

| 检查项 | 结果 |
|--------|------|
| `.dark{color-scheme:dark` | ✅ |
| `.dark` 内置 `--background` / `--bg-primary` / `--text-primary` | ✅（各 22 + 5 项均写入） |
| `.dark .glass` | ✅ |
| `body{…background-color:var(--bg-secondary)}` | ✅ |
| `body{…color:var(--text-primary)}` | ✅ |
| `bg-[color-mix(in_srgb,…)]` 生成 | ✅ `background-color:color-mix(in srgb,var(--bg-primary) 80%,transparent)` |
| 产物中 `.dark` 相关规则总数 | 39 |

### 覆盖面（重要，不夸大）

**暗色目前只覆盖外壳与登录页，不是全站。** 量化剩余量：

| 位置 | 硬编码浅色 |
|------|-----------|
| `components/layout/` | 5 处 `bg-white`，其中 `Sidebar.tsx` 单文件就有 **~30 处 hex**（`#FAFAFA` / `#F5F5F5` / `#1F2937` / `#E5E7EB` / `#999999` …） |
| `components/chat/` | 12 处 `bg-white` |
| `pages/` | 33 处 `bg-white` |
| 全仓 TSX | 剩余 **51 处 `bg-white`** + **277 处 hex** |

所以现在切到暗色会看到：**主区域、顶栏、登录页是暗的，侧栏与消息气泡、admin 卡片/表格仍是亮的**。这是一个**半成品状态**，不是完成态。

**为什么不在本轮把侧栏一起改**：侧栏那 30 个 hex 需要先决定语义映射（`#1F2937` → `--text-primary`？`#F5F5F5` → `--bg-tertiary`？`#E5E7EB` → `--border-default`？），而且 `admin-*` 还有 464 条规则同样是硬编码。零敲碎打地改正是计划里警告的「打地鼠」；应当作为 **2.4 的专门批次**做，并配第 6 阶段的逐页浏览器核对。

### 下一步

2.4 令牌化的第一刀应当是 **`Sidebar.tsx` + `components/chat/`**（用户只看这两块的时间最长），完成后暗色才算真正可用。

---

## 2026-XX-XX · 阶段 2.4（第一批）：外壳与聊天链路令牌化

### 方法：只做「light 模式零变化」的替换

2.4 的诱惑是批量把 hex 换成令牌。但 `globals.css` 的语义令牌里，**只有一部分的 light 值与页面里写的 hex 逐字节相同**；剩下的（比如 `#DDD6FE` 紫-200、`#6D28D9` 紫-700、`#F8FAFC`、`#1F2937`）没有对应令牌，强行映射会在 light 模式下**改变颜色** —— 那是设计变更，不是重构，不能在无人核对时静默做。

所以本批只替换两边逐字节相等的 13 项：

| hex | 令牌 | | hex | 令牌 |
|-----|------|---|-----|------|
| `#FFFFFF`（`bg-white`） | `--bg-primary` | | `#1A1A1A` | `--text-primary` |
| `#FAFAFA` | `--bg-secondary` | | `#4A4A4A` | `--text-secondary` |
| `#F5F5F5` | `--bg-tertiary` | | `#999999` | `--text-tertiary` |
| `#EEEEEE` | `--bg-hover` | | `#CCCCCC` | `--text-muted` |
| `#F0F0F0` | `--border-light` | | `#7C3AED` | `--accent-violet` |
| `#E5E5E5` | `--border-default` | | `#EF4444` | `--error` |
| `#D4D4D4` | `--border-focus` | | | |

`bg-white/70 · /80 · /90` 这类透明度形式改为 `bg-[color-mix(in_srgb,var(--bg-primary)_NN%,transparent)]` —— **不能写 `bg-[var(--bg-primary)]/80`**：Tailwind 无法对 `var()` 应用透明度修饰符，会静默失效。

### 范围

| 文件 | 替换 | 剩余 |
|------|------|------|
| `components/layout/Sidebar.tsx` | 24 | 17 |
| `components/chat/WelcomeScreen.tsx` | 19 | 21 |
| `components/chat/ChatInput.tsx` | 18 | 8 |
| `components/chat/FeedbackButtons.tsx` | 13 | 4 |
| `components/chat/RecommendedQuestions.tsx` | 8 | 6 |
| `components/chat/SourcesPanel.tsx` | 7 | 6 |
| `components/chat/QuestionRail.tsx` | 6 | 1 |
| `components/chat/SourcesButton.tsx` | 3 | 4 |
| `components/chat/ThinkingIndicator.tsx` | 2 | 6 |
| `components/chat/RecommendedQuestionsButton.tsx` | 2 | 4 |
| `components/chat/MessageItem.tsx` | 1 | 10 |
| **合计** | **103** | |

**故意排除 `MarkdownRenderer.tsx` 与 `SourceCitation.tsx`**：这两个文件已经写全了 `dark:` 配对（GitHub 风格的 light/dark 双套色板），它们**不是坏的**，令牌化反而会把有意设计的代码色板拉平。

### 验证证据

**light 模式等价性（本次改动最重要的判据）—— 逐个令牌对账，全部逐字节相同：**

```
✅ --bg-secondary #fafafa   ✅ --bg-tertiary #f5f5f5   ✅ --bg-hover #eeeeee
✅ --border-light #f0f0f0   ✅ --border-default #e5e5e5 ✅ --border-focus #d4d4d4
✅ --text-primary #1a1a1a   ✅ --text-secondary #4a4a4a ✅ --text-tertiary #999999
✅ --text-muted #cccccc     ✅ --accent-violet #7c3aed ✅ --error #ef4444
✅ --bg-primary #ffffff
不匹配: 0
```

```
使用到的令牌 13 个；未定义: 无 ✅
畸形替换检查（var(--x)]] / color-mix 空值 等）: 无 ✅
npx tsc -p tsconfig.app.json --noEmit  → 24 errors，仍全在未改动的 4 个文件
npx vite build                        → ✓ 16.20s，产物 199 339 字节
产物中透明度形式已生成: color-mix(in srgb,var(--bg-primary) 70%/80%/90%,transparent) ✅
```

结论：**light 模式的可计算颜色集与改动前完全一致**，dark 模式多了 103 处随主题变化。

### 一个容易误判的点

`git diff` 在本仓对 HEAD 做比较会**误导**：例如 `QuestionRail.tsx` 看起来是 `text-[#3B82F6]` → `text-[var(--accent-violet)]`（蓝变紫），像是错误。实际是：

- HEAD：`#3B82F6`（蓝）
- 工作区（**别人未提交的改动**）：`#7C3AED`（紫）
- 本次：`#7C3AED` → `var(--accent-violet)`（值同为 `#7c3aed`）

即「蓝→紫」是本次之前就存在的未提交改动，本次引用替换逐字节等价。**本仓有大量未提交改动，任何 `git diff HEAD` 的审阅都必须先排除这一层。**

### 剩余量

| | 2.3 时 | 现在 |
|---|---|---|
| `bg-white` | 51 | **35** |
| hex 字面量 | 277 | **233** |

主要集中（按文件，含 `dark:` 已完备的两个）：

```
38 KnowledgeGraphPage.tsx      21 MarkdownRenderer.tsx（已含 dark:）
26 DashboardPage.tsx           17 Sidebar.tsx
24 SimpleLineChart.tsx         12 SourceCitation.tsx（已含 dark:）
23 BizChangeLogPage.tsx        10 AgentAvatar.tsx
21 WelcomeScreen.tsx            9 SourceIcon.tsx
```

### 下一批

剩下分两类，**处理方式不同，不能一把刷**：

1. **`admin-*` 页面**（KnowledgeGraph / Dashboard / SimpleLineChart / BizChangeLog 等）—— 走的是 `.admin-layout .xxx` 的 CSS 类，颜色在 **`globals.css` 里**而不在 TSX。改这里要动那 464 条规则，且必须配浏览器核对。
2. **无对应令牌的颜色**（`#DDD6FE` / `#6D28D9` / `#5B21B6` 这组「深度思考」紫色，`#F8FAFC` / `#E5E7EB` / `#1F2937` 这组 slate 色）—— 需要先决定：是补一组「紫色辅助」令牌（推荐，因为深度思考面板是一块独立语义），还是给它们逐处加 `dark:` 配对。

---

## 2026-XX-XX · 阶段 2.4（第二批）：新增「紫色辅助」令牌组

### 为什么要单独立一组，而不是复用 `--accent-*`

页面里有一整组紫色（`#DDD6FE` / `#EDE9FE` / `#6D28D9` / `#5B21B6` …），用在「深度思考」面板、推荐问题、智能体标识上。而 `--accent-*` 是**蓝色系**（`--accent-primary: #3b82f6`）。

若把它们塞进 `--accent-*`，两种语义就在颜色上无法分辨了 —— “品牌强调”与“深度思考”会变成同一个颜色。所以单独成组：

```css
/* ===== 新设计系统 - 紫色辅助 ===== */
--violet-soft: #f5f3ff;          /* 最浅衬底 */
--violet-surface: #ede9fe;       /* 面板底 */
--violet-surface-strong: #ddd6fe;/* 内层块 / 边框 */
--violet-line: #c4b5fd;          /* 聚焦描边 */
--violet-icon: #8b5cf6;
--violet-text: #6d28d9;
--violet-text-strong: #5b21b6;
--violet-muted: #e6e0f0;         /* 装饰性极浅色调 */
```

`:root` 的值与页面原有 hex **逐字节相同**（所以 light 模式零变化）；`.dark` 里色阶方向反转 —— 衬底转暗、文字转亮：

```css
.dark {
  --violet-soft: #171233;  --violet-surface: #1f1842;  --violet-surface-strong: #2c2258;
  --violet-line: #4c3d8f;  --violet-icon: #a78bfa;    --violet-text: #c4b5fd;
  --violet-text-strong: #ddd6fe;  --violet-muted: #2a2145;
}
```

### 替换范围（42 处 / 9 个文件）

| 文件 | 替换 |
|------|------|
| `chat/MessageItem.tsx` | 10 |
| `chat/WelcomeScreen.tsx` | 10 |
| `chat/ThinkingIndicator.tsx` | 6 |
| `layout/Sidebar.tsx` | 5 |
| `chat/ChatInput.tsx` | 3 |
| `chat/SourceIcon.tsx` | 3 |
| `chat/RecommendedQuestions.tsx` | 2 |
| `chat/RecommendedQuestionsButton.tsx` | 2 |
| `admin/AdminState.tsx` | 1 |

### 验证证据

```
紫色辅助令牌：light 值必须与被替换的 hex 逐字节相同，且 dark 已覆盖
  ✅ --violet-soft            light #f5f3ff  dark #171233
  ✅ --violet-surface         light #ede9fe  dark #1f1842
  ✅ --violet-surface-strong  light #ddd6fe  dark #2c2258
  ✅ --violet-line            light #c4b5fd  dark #4c3d8f
  ✅ --violet-icon            light #8b5cf6  dark #a78bfa
  ✅ --violet-text            light #6d28d9  dark #c4b5fd
  ✅ --violet-text-strong     light #5b21b6  dark #ddd6fe
  ✅ --violet-muted           light #e6e0f0  dark #2a2145
不合格: 0

npx tsc -p tsconfig.app.json --noEmit  → 24 errors，仍全在未改动的 4 个文件
npx vite build                        → ✓ 16.70s，产物 199 410 字节
```

### 顺带排除了一个假警报

核对「TSX 里用到的令牌是否都有定义」时，脚本报了 10 个未定义：

- `--radix-select-trigger-height`、`--radix-select-trigger-width`、`--radix-dropdown-menu-trigger-width` —— Radix 在**运行期**注入的，本来就不应该在 CSS 里定义。**非缺陷。**
- `--chart-primary` / `--chart-success` / `--chart-warning` / `--chart-danger` / `--chart-info` / `--chart-neutral` —— 看着像缺失，实际是 **`SimpleLineChart.tsx` 在组件内用内联 style 对象自己定义的**（`["--chart-primary"]: "#8b5cf6"`）。**非缺陷**，而且图表色板就该跟图表走，不该上提成全局语义令牌。

（脚本只查了 `globals.css`，所以看不见这两种来源。记在这里，免得下次又当成 bug 去“修”。）

### 累计进展

| | 起点 | 2.4a 后 | 2.4b 后 |
|---|---|---|---|
| `bg-white`（行） | 56 | 35 | **35** |
| hex 字面量（行） | 279 | 233 | **206** |
| `var()` 引用（行） | — | — | **132** |
| 累计替换（处） | — | 103 | **145** |

### 下一批

剩余 hex 行数前列：

```
29 KnowledgeGraphPage.tsx   ← 颜色在 globals.css 的 .admin-* 规则里，不在 TSX
24 SimpleLineChart.tsx      ← 图表色板（局部变量），建议不动
21 MarkdownRenderer.tsx     ← 已含完整 dark: 配对，不动
19 DashboardPage.tsx        ← 同上，走 admin-* 规则
18 BizChangeLogPage.tsx     ← 同上
15 WelcomeScreen.tsx        ← slate 组 + 装饰性渐变，需 dark: 配对
14 Sidebar.tsx              ← 同上
12 SourceCitation.tsx       ← 已含 dark: 配对，不动
10 AgentAvatar.tsx
```

所以剩下的是两类：**（1）`admin-*` 页面** —— 必须改 `globals.css` 里那 464 条规则，且**必须配浏览器逐页核对**（没有别的验证手段）；**（2）slate 组 + 装饰渐变** —— 需要决定是再补一组中性色令牌，还是逐处加 `dark:` 配对。




