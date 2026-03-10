# 配置指南

本文只说明运行时配置，不重复接口说明。

## 设计原则

当前配置遵循 4 条规则：
- provider 配置和业务场景配置分离
- 路由引用统一使用别名
- 新配置优先使用 `chains`
- 配置示例统一使用 kebab-case

## 1. Provider 配置

`providers.clients` 用来定义模型客户端。key 就是别名。

```yaml
providers:
  clients:
    kimi-main:
      type: openai-compat
      base-url: ${OPENAI_KIMI_BASE_URL}
      api-key: ${OPENAI_KIMI_API_KEY}
    kimi-256k:
      type: openai-compat
      base-url: ${OPENAI_KIMI_BASE_URL}
      api-key: ${OPENAI_KIMI_API_KEY}
    glm-main:
      type: openai-compat
      base-url: ${OPENAI_GLM_BASE_URL}
      api-key: ${OPENAI_GLM_API_KEY}
    qwen-main:
      type: dashscope
      api-key: ${DASHSCOPE_API_KEY}
```

说明：
- `type=openai-compat` 适用于 Kimi、GLM 等 OpenAI 兼容接口
- `type=dashscope` 依赖 dashscope profile 和对应 starter
- 同一家 provider 可以配多个别名，用于主备、不同额度、不同环境

## 2. 路由配置

### 推荐方式：`chains`

```yaml
ai:
  default-model: kimi-main:moonshot-v1-8k
  chains:
    CONTRACT_SUMMARY:
      - kimi-main:moonshot-v1-8k
      - glm-main:glm-4
    CUSTOMER_FOLLOWUP:
      - glm-main:glm-4
      - kimi-main:moonshot-v1-8k
```

含义：
- 第一项是首选
- 后续项是 fallback

### 兼容方式：`routes`

```yaml
ai:
  routes:
    CONTRACT_SUMMARY: kimi-main:moonshot-v1-8k
    CUSTOMER_FOLLOWUP: glm-main:glm-4
```

说明：
- `routes` 只表示主路由
- 如果某个场景没在 `chains` 里显式写主路由，服务端会自动把主路由补到链首
- 新配置不建议继续只依赖 `routes`

## 3. 策略配置

`ai.policy` 控制 fallback、超时和单路由尝试次数。

覆盖顺序：
- `tenant.scenes`
- `tenant.default-policy`
- `global.scenes`
- `global.default-policy`

```yaml
ai:
  policy:
    default-policy:
      allow-fallback: true
      timeout-ms: 30000
      per-route-max-attempts: 1
    scenes:
      CONTRACT_SUMMARY:
        timeout-ms: 45000
    tenants:
      t001:
        default-policy:
          timeout-ms: 25000
        scenes:
          CUSTOMER_FOLLOWUP:
            timeout-ms: 15000
            per-route-max-attempts: 2
```

字段说明：
- `allow-fallback`：是否允许切备线
- `timeout-ms`：最终请求超时
- `per-route-max-attempts`：单路由最大尝试次数

## 4. 租户保护

`ai.guard` 提供第一期的基础租户限流。

```yaml
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

说明：
- 当前是单实例内存限流
- 适合试点和单实例部署
- 多实例建议后续迁移到 Redis 或网关侧限流

## 5. 一期推荐最小配置

```yaml
ai:
  default-model: kimi-main:moonshot-v1-8k
  chains:
    CONTRACT_SUMMARY:
      - kimi-main:moonshot-v1-8k
      - glm-main:glm-4
  policy:
    default-policy:
      allow-fallback: true
      timeout-ms: 30000
      per-route-max-attempts: 1
  guard:
    enabled: true
    default-requests-per-minute: 120

providers:
  clients:
    kimi-main:
      type: openai-compat
      base-url: ${OPENAI_KIMI_BASE_URL}
      api-key: ${OPENAI_KIMI_API_KEY}
    glm-main:
      type: openai-compat
      base-url: ${OPENAI_GLM_BASE_URL}
      api-key: ${OPENAI_GLM_API_KEY}
```

## 6. 启动期校验

应用启动时会校验：
- `providers.clients` 不为空
- `alias:model` 格式合法
- routes / chains 引用的别名存在
- `timeout-ms > 0`
- `per-route-max-attempts >= 1`
- `default-requests-per-minute > 0`

如果校验失败，应用会直接启动失败，而不是把错误留到运行期。

## 7. 启动摘要日志

启动后会输出配置摘要日志，包含：
- 已注册别名列表
- 默认模型
- routes / chains 摘要
- policy 默认值和已覆盖场景
- guard 默认值和租户覆盖

这部分日志主要用于排障和上线自检。

## 8. 常见错误码

接口统一返回结构化错误：

```json
{
  "timestamp": "2026-03-10T11:00:00Z",
  "path": "/ai/chat",
  "status": 400,
  "error": "Bad Request",
  "code": "INVALID_REQUEST",
  "message": "scene must not be blank",
  "requestId": "..."
}
```

常见错误码：
- `INVALID_REQUEST`
- `AI_RATE_LIMITED`
- `AI_UNAVAILABLE`
- `AI_TIMEOUT`
- `AI_INTERNAL_ERROR`

## 9. 常见易错点

- Moonshot `base-url` 不要带 `/v1`
- 不要把 provider 名和别名混用，路由引用的是别名
- 新配置优先写 `chains`
- `ai.guard` 只适合单实例试点
- `responseSchema` 是结构化提示，不等于强制结构化协议
