# 跨境金融业财核算与智能决策平台

> 一个面向跨境贸易企业的业财一体化平台，整合数据底座、业财核算、资金风控与 AI 智能决策能力，帮助企业实现订单对账、多币种利润核算、预算管控、付款审批全流程数字化，并内置 AI 顾问基于实时业务数据辅助经营决策。

---

## 目录

- [项目亮点](#项目亮点)
- [技术栈](#技术栈)
- [功能架构](#功能架构)
- [项目结构](#项目结构)
- [快速开始](#快速开始)
- [环境变量配置](#环境变量配置)
- [数据库初始化](#数据库初始化)
- [AI Agent 工具能力](#ai-agent-工具能力)
- [安全与权限模型](#安全与权限模型)
- [测试与评测](#测试与评测)
- [常见问题](#常见问题)
- [许可证](#许可证)

---

## 项目亮点

- **业财一体**：从平台账单导入 → 银行流水对账 → 多维度利润核算 → 付款审批，全链路打通
- **多币种支持**：内置汇率快照机制，支持 USD / EUR / HKD / JPY ↔ CNY 自动折算
- **AI 智能决策**：集成 DeepSeek 大模型 + LangChain4j Agent，支持工具调用、流式输出、会话回溯
- **实时数据问答**：AI 顾问可主动查询实时汇率、订单、利润、预算、付款等 12 类业务工具
- **细粒度权限**：基于 Spring Security + JWT 的无状态认证，RBAC 角色级菜单/接口控制
- **审计可追溯**：AOP 切面自动记录所有 Controller 操作日志，支持全链路审计
- **预算风控**：付款审批与预算扣减采用原子 SQL，防止并发超扣

---

## 技术栈

### 后端
| 组件 | 版本 | 用途 |
|---|---|---|
| Spring Boot | 3.5.16 | 应用框架（**不使用 4.x**，与第三方库不兼容） |
| Java | 21 | 运行时 |
| Spring Security | 6.x | 认证鉴权 |
| MyBatis-Plus | 3.5.7 | ORM |
| MySQL Connector/J | runtime | 数据库驱动 |
| Redis (Lettuce) | - | 缓存 |
| JJWT | 0.12.6 | JWT 签发与解析 |
| LangChain4j | 0.35.0 | 大模型 Agent 框架（**不使用 1.x**，API 不兼容） |
| EasyExcel | 4.0.3 | Excel/CSV 解析 |
| Hutool | 5.8.32 | 工具类 |
| Lombok | - | 简化样板代码 |

### 前端
| 组件 | 版本 | 用途 |
|---|---|---|
| Vue | 3.5.x | 视图框架 |
| Vite | 5.4.x | 构建工具 |
| Pinia | 2.2.x | 状态管理 |
| Vue Router | 4.4.x | 路由 |
| Element Plus | 2.8.x | UI 组件库 |
| ECharts | 5.5.x | 图表 |
| Axios | 1.7.x | HTTP 客户端 |

### 基础设施
- **数据库**：MySQL 8.0+（**必须使用 utf8mb4 字符集**以支持中文）
- **缓存**：Redis 6+
- **大模型**：DeepSeek（`deepseek-chat`）

---

## 功能架构

平台分为四大业务域 + 一套基础底座：

```
┌─────────────────────────────────────────────────────────────┐
│                      前端 Vue3 SPA                          │
│   登录 │ 经营驾驶舱 │ AI 顾问 │ 数据底座 │ 业财核算 │ 资金风控 │
└────────────────────────┬────────────────────────────────────┘
                         │ HTTP / SSE
┌────────────────────────▼────────────────────────────────────┐
│                Spring Boot 后端 (五层架构)                  │
│   Controller → Service → Manager → Mapper → Database        │
├─────────────────────────────────────────────────────────────┤
│  数据底座          │  业财核算         │  资金风控           │
│  · 账单导入清洗    │  · 多维利润核算   │  · 预算计划         │
│  · 银行流水对账    │  · 费用分摊模型   │  · 付款申请         │
│  · 汇率同步        │  · 利润报表       │  · 审批工作台       │
├─────────────────────────────────────────────────────────────┤
│                       AI 智能决策                            │
│  · AI 顾问对话（SSE 流式）                                   │
│  · Agent 工具调用（12 个 @Tool 方法）                        │
│  · 会话上下文持久化（跨设备回溯）                            │
├─────────────────────────────────────────────────────────────┤
│                       系统底座                               │
│  · Spring Security + JWT  · AOP 审计日志                    │
│  · RBAC 角色权限         · 全局异常处理                      │
│  · 线程池隔离（ETL / AI）                                    │
└─────────────────────────────────────────────────────────────┘
```

### 核心功能模块

| 模块 | 路由 | 支持角色 | 说明 |
|---|---|---|---|
| 经营驾驶舱 | `/ai/dashboard` | 全部 | 汇总营收、净利润、预算执行等核心指标 |
| AI 合规顾问 | `/ai/advisor` | 全部 | 流式对话 + 工具调用 + 会话回溯 |
| 账单导入清洗 | `/data/bill-import` | ADMIN/FINANCE/OPERATOR | Excel 上传 → ETL 清洗 → 入库 |
| 银行流水对账 | `/data/bank-reconciliation` | ADMIN/FINANCE/OPERATOR | 平台账单 ↔ 银行流水自动匹配 |
| 多维度利润报表 | `/accounting/profit-report` | ADMIN/FINANCE/OPERATOR | 按店铺/平台/币种多维度核算 |
| 费用分摊模型配置 | `/accounting/model-config` | ADMIN/FINANCE | 按金额/重量配置分摊规则 |
| 付款申请 | `/fund/payment-apply` | ADMIN/FINANCE/OPERATOR | 关联预算扣减 |
| 审批工作台 | `/fund/approval-center` | ADMIN/APPROVER/CASHIER | 多级审批 + 预算原子扣减 |
| 用户与权限管理 | `/system/user` | ADMIN | 用户/角色/菜单维护 |
| 操作审计日志 | `/system/audit` | ADMIN | 全 Controller 操作留痕 |

---

## 项目结构

```
cross-finance/
├── backend/                          # 后端 Spring Boot 工程
│   ├── src/main/java/com/finance/platform/
│   │   ├── accounting/               # 业财核算（利润、分摊）
│   │   ├── ai/                       # AI 顾问（Agent、对话、RAG）
│   │   │   └── agent/AiTools.java    # 12 个 @Tool 工具方法
│   │   ├── common/                   # 公共组件（切面、异常、工具）
│   │   ├── config/                   # 配置（Security/JWT/LangChain/线程池）
│   │   ├── data/                     # 数据底座（订单、汇率、ETL）
│   │   ├── fund/                     # 资金风控（预算、付款、审批）
│   │   ├── system/                   # 系统管理（用户、角色、审计）
│   │   └── PlatformApplication.java  # 启动入口
│   ├── src/main/resources/
│   │   ├── application.yml           # 配置（敏感信息用 ${VAR} 占位）
│   │   ├── sql/                      # 建表 + 初始化脚本
│   │   └── prompt/                   # AI Prompt 模板
│   └── src/test/java/                # 单元测试 + Agent 评测脚本
│
├── frontend/                         # 前端 Vue3 工程
│   ├── src/
│   │   ├── api/                      # 接口封装
│   │   ├── components/               # 通用组件
│   │   ├── router/                   # 路由 + 角色守卫
│   │   ├── store/                    # Pinia 状态
│   │   ├── utils/                    # 工具（request 拦截器、auth）
│   │   └── views/                    # 页面（ai/data/accounting/fund/system）
│   └── vite.config.js
│
├── mock-data/                        # 模拟数据
│   ├── 03_mock_data.sql              # 一键导入全量数据
│   ├── excel/                        # 模拟账单/流水 CSV
│   └── README_mock_data.txt
│
├── .env.example                      # 环境变量模板
└── .gitignore                        # 统一忽略规则
```

---

## 快速开始

### 前置依赖

| 软件 | 最低版本 | 备注 |
|---|---|---|
| JDK | 21 | 必须 |
| Maven | 3.9+ | 或使用项目自带 `mvnw` |
| Node.js | 18+ | 推荐 20 LTS |
| MySQL | 8.0+ | 字符集 utf8mb4 |
| Redis | 6.0+ | 可选（不启用则关闭缓存） |

### 1. 克隆仓库

```bash
git clone https://github.com/rui720/cross-finance.git
cd cross-finance
```

### 2. 配置环境变量

复制 `.env.example` 为 `.env`，填入真实值：

```bash
cp .env.example .env
```

或在 IDE 运行配置的 Environment variables 中填写：

```
MYSQL_PASSWORD=your_db_password
JWT_SECRET=your_jwt_secret_at_least_256_bit
LANGCHAIN_API_KEY=sk-your_deepseek_api_key
```

> 详见 [环境变量配置](#环境变量配置)

### 3. 初始化数据库

```bash
mysql -u root -p < backend/src/main/resources/sql/01_schema.sql
mysql -u root -p cross_finance < backend/src/main/resources/sql/02_init_data.sql
mysql -u root -p cross_finance < backend/src/main/resources/sql/04_ai_chat.sql
mysql -u root -p cross_finance < backend/src/main/resources/sql/05_dept_and_roles.sql

# 可选：导入模拟数据用于功能验证
mysql -u root -p cross_finance < mock-data/03_mock_data.sql
```

> ⚠️ Windows 命令行默认 GBK 编码，导入中文数据前请先执行 `SET NAMES utf8mb4;`

### 4. 启动后端

```bash
cd backend
./mvnw spring-boot:run
# 或 Windows:
# mvnw.cmd spring-boot:run
```

后端启动后访问：`http://localhost:8080/api`

### 5. 启动前端

```bash
cd frontend
npm install
npm run dev
```

前端访问：`http://localhost:5173`

### 6. 登录

使用初始化脚本创建的账号登录（密码请联系管理员或参考内部文档获取，已通过 BCrypt 加密存储）。

---

## 环境变量配置

所有敏感信息均通过环境变量注入，**禁止硬编码到代码或配置文件中**。

| 变量名 | 必填 | 说明 | 示例 |
|---|---|---|---|
| `MYSQL_URL` | 否 | 数据库连接 URL（有默认值） | `jdbc:mysql://localhost:3306/cross_finance?...` |
| `MYSQL_USERNAME` | 否 | 数据库用户名（默认 root） | `root` |
| `MYSQL_PASSWORD` | **是** | 数据库密码 | `your_password` |
| `JWT_SECRET` | **是** | JWT 签名密钥（≥256 bit 随机串） | `openssl rand -base64 48` 生成 |
| `LANGCHAIN_API_KEY` | **是** | DeepSeek API Key | `sk-xxxxxxxx` |

生成 JWT 密钥：

```bash
openssl rand -base64 48
```

DeepSeek API Key 申请：https://platform.deepseek.com/

---

## 数据库初始化

SQL 脚本按编号顺序执行：

| 脚本 | 作用 |
|---|---|
| `01_schema.sql` | 建库建表（utf8mb4） |
| `02_init_data.sql` | 初始化用户、菜单、基础配置 |
| `04_ai_chat.sql` | AI 对话会话表 |
| `05_dept_and_roles.sql` | 部门、角色、用户角色关联 |
| `mock-data/03_mock_data.sql` | 模拟业务数据（可选，用于功能验证） |

### 核心数据表

| 表名 | 说明 |
|---|---|
| `sys_user` | 系统用户（密码 BCrypt 加密） |
| `sys_dept` | 部门 |
| `sys_audit_log` | 操作审计日志 |
| `raw_order` | 原始订单（平台账单 + 银行流水） |
| `exchange_rate_snapshot` | 汇率快照 |
| `cost_allocation_rule` | 费用分摊规则 |
| `profit_report` | 利润报表 |
| `budget_plan` | 预算计划 |
| `payment_apply` | 付款申请单 |
| `ai_session` / `ai_message` | AI 对话会话与消息 |

---

## AI Agent 工具能力

AI 顾问基于 LangChain4j Agent 架构，通过 `@Tool` 注解将业务能力暴露给大模型，支持模型自主决策何时调用哪个工具。

### 内置 12 个工具方法

| 工具 | 说明 |
|---|---|
| `getLatestExchangeRate` | 查询实时汇率或按最新汇率换算金额 |
| `getExchangeRateHistory` | 查询历史汇率走势 |
| `queryOrders` | 多条件查询订单 |
| `queryOrderDetail` | 查询订单详情 |
| `queryProfitReport` | 查询利润报表数值汇总 |
| `analyzeProfit` | 利润归因分析与诊断建议 |
| `queryBudgetWarnings` | 查询预算预警 |
| `queryPaymentApplies` | 查询付款申请列表 |
| `queryPaymentDetail` | 查询付款申请详情 |
| `queryAllocationRules` | 查询费用分摊规则 |
| `queryAuditLogs` | 查询操作审计日志 |
| `queryReconcileStatus` | 查询对账状态 |

### 工具调用流程

```
用户提问
   ↓
构建 SystemMessage + UserMessage
   ↓
ChatLanguageModel.generate(messages, toolSpecs)
   ↓
模型返回 ToolExecutionRequest?
   ↓ 是
执行工具 → 返回 ToolExecutionResult
   ↓
循环直到模型给出最终回答
   ↓
SSE 流式输出最终回答到前端
```

### Agent 评测

项目内置 30 题集成评测脚本，覆盖 5 个维度：

- **工具选择准确率**（13 题）：能否选对工具
- **参数提取准确率**（6 题）：能否从自然语言中提取正确参数
- **不调工具判断**（4 题）：闲聊类问题应直接回答而非调工具
- **边界容错**（4 题）：异常输入的处理
- **多工具协作**（3 题）：需要连续调用多个工具

运行评测：

```bash
cd backend
./mvnw test -Dtest=AgentEvaluationTest -DrunAgentEval=true
```

> 评测默认不启用（避免日常构建消耗 API token），需通过 `-DrunAgentEval=true` 显式开启。

---

## 安全与权限模型

### 认证流程

1. 用户登录 → 后端校验密码（BCrypt） → 签发 JWT
2. 前端将 JWT 存入 localStorage，后续请求通过 `Authorization: Bearer xxx` 头携带
3. `JwtAuthenticationFilter` 解析 JWT，构建 `SecurityContext`
4. 路由守卫 + 接口注解双重校验角色权限

### RBAC 角色

| 角色 | 权限范围 |
|---|---|
| `ADMIN` | 全部菜单 |
| `FINANCE` | 数据底座 + 业财核算 + 资金风控(付款) + AI |
| `APPROVER` | 资金风控(审批) + AI |
| `OPERATOR` | 数据底座 + 业财核算(无分摊配置) + 资金风控(付款) + AI |
| `CASHIER` | 资金风控(审批) |

### HTTP 状态码语义

| 状态码 | 场景 | 前端行为 |
|---|---|---|
| 401 | JWT 过期 / 未登录 | Toast 提示 + 跳转登录页（防并发跳转） |
| 403 | 权限不足 | Toast 提示"权限不足，无法访问" |

### 敏感信息保护

- 所有密钥（MySQL / JWT / DeepSeek API Key）通过环境变量注入
- `.env*`、`application-local.yml`、`*.pem`、`*.key` 等已被 `.gitignore` 排除
- 用户密码 BCrypt 加密存储，前端 `@JsonIgnore` 不序列化
- 编译产物 `target/` 不入库，避免敏感配置副本泄漏

---

## 测试与评测

### 单元测试

```bash
cd backend
./mvnw test
```

覆盖范围：
- `AllocationStrategyTest`：分摊策略
- `CurrencyConvertUtilsTest`：币种换算
- `BudgetControlServiceImplTest`：预算扣减
- `PaymentFlowServiceImplTest`：付款流程
- `ProfitEngineServiceImplTest`：利润核算引擎
- `AiToolsTest`：AI Agent 12 个工具方法

### Agent 评测

```bash
./mvnw test -Dtest=AgentEvaluationTest -DrunAgentEval=true
```

输出分类准确率报告，断言整体准确率 ≥ 80%（优化后实测 100%）。

---

## 常见问题

### Q1: 启动报错 `Spring Boot 4.x 依赖解析失败`
A: 项目强制使用 Spring Boot 3.5.16，请勿升级到 4.x（与 LangChain4j 等第三方库不兼容）。

### Q2: 启动报错 `LangChain4j ChatLanguageModel not found`
A: LangChain4j 1.0+ 将 `ChatLanguageModel` 重命名为 `ChatModel`，本项目锁定 0.35.0 版本。

### Q3: 中文乱码
A: 
- MySQL 建库必须 `DEFAULT CHARACTER SET utf8mb4`
- Windows 命令行导入 SQL 前先执行 `SET NAMES utf8mb4;`
- 后端 `application.yml` 已配置 `characterEncoding=utf-8`

### Q4: JWT 过期后显示"无访问权限"而非跳登录页
A: 已修复。后端 `SecurityConfig` 区分 `AuthenticationEntryPoint`（401）和 `AccessDeniedHandler`（403），前端 401 直接跳转登录页。

### Q5: AI 顾问回答错误或调用工具不准确
A: 参考 `AgentEvaluationTest` 评测结果，若准确率下降，检查 `AiTools.java` 中工具描述是否清晰，避免歧义。

### Q6: 汇率同步定时任务失败
A: 检查 `ExchangeRateSyncTask` 日志，确认外部汇率 API 可达，且 `exchange_rate_snapshot` 表字符集为 utf8mb4。

---

## 许可证

本项目为私人/企业内部项目，未开放开源许可证。如需使用，请联系仓库所有者。
