# 配置指南（多 Provider/多别名/多环境/策略）

本文解释如何在不同环境与租户下，灵活配置多家模型、多别名与主备链，以及策略覆盖顺序。

## Providers（多别名）
- application.yml 片段
```
providers:
  clients:
    kimi-main: { type: openai-compat, base-url: ${OPENAI_KIMI_BASE_URL}, api-key: ${OPENAI_KIMI_API_KEY} }
    kimi-256k: { type: openai-compat, base-url: ${OPENAI_KIMI_BASE_URL}, api-key: ${OPENAI_KIMI_API_KEY} }
    glm-main:  { type: openai-compat, base-url: ${OPENAI_GLM_BASE_URL},  api-key: ${OPENAI_GLM_API_KEY} }
    qwen-main: { type: dashscope,      api-key: ${DASHSCOPE_API_KEY} }
```
- 别名说明：可自由增减；同一 Provider 可配置多个账号/环境（如主/备、不同配额）。

## 路由与主备链
- 主路由（每个 scene 一个首选 alias:model）
```
ai:
  routes:
    CONTRACT_SUMMARY: kimi-main:moonshot-v1-8k
    CUSTOMER_FOLLOWUP: glm-main:glm-4
```
- 主备链（从前到后）
```
ai:
  chains:
    CUSTOMER_FOLLOWUP:
      - glm-main:glm-4
      - kimi-main:moonshot-v1-8k
      - qwen-main:qwen-turbo
```

## 策略（全局/scene/tenant）
- 覆盖顺序：tenant.scenes → tenant.default → global.scenes → global.default
```
ai:
  policy:
    default-policy: { allowFallback: true, timeoutMs: 30000, perRouteMaxAttempts: 1 }
    scenes:
      CONTRACT_SUMMARY: { timeoutMs: 45000 }
    tenants:
      t001:
        defaultPolicy: { timeoutMs: 25000 }
        scenes:
          CUSTOMER_FOLLOWUP: { timeoutMs: 15000, perRouteMaxAttempts: 2 }
```

## 租户保护（限流）
- 一期默认提供单实例内存限流，按 `tenantId` 统计每分钟请求数。
```
ai:
  guard:
    enabled: true
    default-requests-per-minute: 120
    tenants:
      vip-tenant:
        requests-per-minute: 300
      sandbox-tenant:
        requests-per-minute: 30
```
- 说明：
  - 适合第一期试点和单实例部署。
  - 多实例部署建议后续迁移到 Redis / API Gateway 限流，避免实例间计数不一致。

## 多环境配置
- Spring Profile：为 dev/test/prod 分别维护 `application-<env>.yml`（仅差异项）。
- 配置中心（推荐）：Spring Cloud Config + Bus，集中管理 routes/chains/policy/providers，支持在线刷新与灰度。
- 密钥：使用 K8s Secret/env 或 Vault/KMS，不落盘，不提交仓库。

## 请求 Options（OpenAI 兼容）
- 请求体 `options` 支持：`temperature`、`topP`、`maxTokens`（OpenAI 兼容链路生效）。
```
{
  "scene":"CONTRACT_SUMMARY",
  "tenantId":"t001",
  "userId":"u123",
  "messages":[{"role":"user","content":"..."}],
  "responseFormat":"text",
  "options": { "temperature": 0.2, "maxTokens": 512 },
  "stream": false
}
```

## 结构化输出（JSON）
- 优先建议传 `responseFormat: json` 与 `responseSchema`，服务端会注入约束提示并尝试解析为 JSON；解析失败回退文本。

## 错误响应
- 当前接口统一返回结构化错误：
```
{
  "timestamp":"2026-03-10T11:00:00Z",
  "path":"/ai/chat",
  "status":400,
  "error":"Bad Request",
  "code":"INVALID_REQUEST",
  "message":"scene must not be blank",
  "requestId":"..."
}
```
- 常见错误码：
  - `INVALID_REQUEST`
  - `AI_RATE_LIMITED`
  - `AI_UNAVAILABLE`
  - `AI_TIMEOUT`

## 常见配置误区
- base-url 多带 `/v1`（Moonshot 应去掉）；
- routes 写成模型名但没指明 alias；建议写成 `alias:model`；
- chains 未包含主路由：服务端会自动补上主路由到链首。
