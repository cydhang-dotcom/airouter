# 仓库贡献指南

## 项目结构与模块
- `src/main/java/com/yowits/banbu/ai/`：核心服务代码（控制器、服务、路由、配置）。
- `src/test/java/com/yowits/banbu/ai/`：单元与端到端测试。
- `docs/`：中文文档（部署、配置）。
- `client/`：可选 Java SDK 子模块（业务侧依赖）。

## 构建、测试与本地运行
- 构建：`mvn -DskipTests package`
- 运行：`mvn spring-boot:run`
- 测试（不依赖外网）：`mvn test`
- Kimi 外部端到端测试（需密钥）：
  - `mvn -P e2e-kimi -Dopenai.base.url=https://api.moonshot.cn -Dopenai.api.key=sk-*** -Dtest=ChatE2EKimiTest test`

## 代码风格与命名
- Java 17；包名 `com.yowits.banbu.ai`；类 PascalCase，方法/变量 camelCase。
- 控制器只做入参校验与响应组装；业务逻辑在 Service；配置集中在 `config/`。
- 严禁硬编码密钥/URL；统一使用系统属性或环境变量注入。

## 测试规范
- 单元测试：就近、命名 `*Test.java`，避免真实外部调用（Stub/Mock）。
- 端到端：`SpringBootTest` 随机端口；外部调用通过 profile 显式开启。
- 覆盖重点：路由与主备链、结构化输出（JSON Schema）、SSE 流式、策略超时/重试。

## 提交与合并
- 提交信息建议“类型(范围): 简要说明”，如：`feat(router): 支持主备链路`。
- PR 需包含：变更说明、关联问题、测试结果；涉及接口/配置变更需更新文档。

## 安全与配置
- 不提交密钥；提供 `.env.example`；运行时通过环境变量/密钥管理系统注入。
- Provider 用多别名（`providers.clients`），路由用 `alias:model`，策略在 `ai.policy`。

## 观测与治理
- 关键日志：`ai_call`、`route_failed`（含 tenant/scene/alias/model/耗时/状态）。
- 指标（Prometheus）与 Trace（OTel）按需接入；关注 P95/P99、错误率、降级比。
