# banbu-airouter

面向业务服务的内部 AI 网关。它统一承接模型调用，让业务系统通过固定接口接入 AI，而不用直接耦合具体模型厂商。

当前定位是第一期试点版，重点解决：
- 统一接口
- 场景路由与主备链
- 基础超时、重试、降级
- 基础租户保护
- 结构化输出
- 可回归的本地与真实外部 E2E

## 适用场景

适合把 AI 能力隐含嵌入业务流程的场景，例如：
- 合同摘要
- 客户跟进建议
- 内容润色
- 文本分类与标签提取

最终用户不需要知道背后用了哪个模型，业务系统只调用本服务提供的统一接口。

## 当前能力

- 统一接口：`POST /ai/chat`、`POST /ai/chat/stream`
- 路由能力：`default-model`、`routes`、`chains`
- 主备降级：非流式按主备链自动切换
- 策略控制：`ai.policy`
- 租户限流：`ai.guard`
- 结构化输出：`responseFormat=json` + `responseSchema`
- 基础观测：`ai_call`、`route_failed`、access log、Actuator
- 测试体系：本地 E2E、真实 Kimi E2E、单元测试

## 快速开始

环境要求：
- Java 17+
- Maven 3.9+

构建：
```bash
mvn -DskipTests package
```

启动：
```bash
mvn spring-boot:run
```

健康检查：
```bash
curl -s http://localhost:8081/actuator/health
```

## 最小可用配置

推荐先从这份最小配置开始：

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
      base-url: ${OPENAI_KIMI_BASE_URL:https://api.moonshot.cn}
      api-key: ${OPENAI_KIMI_API_KEY:${OPENAI_COMPAT_API_KEY:}}
    glm-main:
      type: openai-compat
      base-url: ${OPENAI_GLM_BASE_URL:https://open.bigmodel.cn/api/paas}
      api-key: ${OPENAI_GLM_API_KEY:}
```

说明：
- `providers.clients` 的 key 就是“别名”
- 路由使用 `alias:model`
- 新配置优先使用 `chains`
- `routes` 仅保留为兼容简写

## 环境变量

Kimi / Moonshot：
```bash
export OPENAI_KIMI_BASE_URL=https://api.moonshot.cn
export OPENAI_KIMI_API_KEY=sk-***
```

OpenAI 兼容默认值：
```bash
export OPENAI_BASE_URL=https://api.moonshot.cn
export OPENAI_COMPAT_API_KEY=sk-***
```

GLM：
```bash
export OPENAI_GLM_BASE_URL=https://open.bigmodel.cn/api/paas
export OPENAI_GLM_API_KEY=sk-***
```

DashScope：
```bash
export DASHSCOPE_API_KEY=sk-***
```

## 接口

### 非流式

```http
POST /ai/chat
Content-Type: application/json
```

请求示例：
```json
{
  "scene": "CONTRACT_SUMMARY",
  "tenantId": "t001",
  "userId": "u123",
  "messages": [
    { "role": "user", "content": "请总结这份合同的重点" }
  ],
  "responseFormat": "text",
  "stream": false
}
```

响应示例：
```json
{
  "data": "这里是模型返回内容",
  "model": "moonshot-v1-8k",
  "error": ""
}
```

### 流式

```http
POST /ai/chat/stream
Accept: text/event-stream
```

返回：
- `event: message`
- `event: done`

### 结构化输出

请求示例：
```json
{
  "scene": "CONTRACT_SUMMARY",
  "tenantId": "t001",
  "userId": "u123",
  "messages": [
    { "role": "user", "content": "请总结合同并输出摘要、风险等级和标签" }
  ],
  "responseFormat": "json",
  "responseSchema": {
    "type": "object",
    "properties": {
      "summary": { "type": "string" },
      "riskLevel": { "type": "string" },
      "tags": {
        "type": "array",
        "items": { "type": "string" }
      }
    },
    "required": ["summary", "riskLevel", "tags"]
  },
  "stream": false
}
```

## 客户端 SDK (AiGatewayClient)

`AiGatewayClient` 是一个 Spring Boot 客户端 SDK，用于从业务服务侧调用 `ai-service` 网关，统一封装了非流式与流式请求。

### 依赖配置（Maven）

```xml
<dependency>
  <groupId>com.yowits</groupId>
  <artifactId>banbu-airouter-client</artifactId>
  <version>0.0.1-SNAPSHOT</version>
</dependency>
```

### 非流式调用示例

```java
import com.yowits.banbu.ai.client.AiGatewayClient;
import com.yowits.banbu.ai.client.AiGatewayClient.ChatMessage;
import com.yowits.banbu.ai.client.AiGatewayClient.ChatReq;
import com.yowits.banbu.ai.client.AiGatewayClient.ChatResp;

import java.util.List;

public class AiGatewayClientExample {
    private final AiGatewayClient aiGatewayClient;

    public AiGatewayClientExample(AiGatewayClient aiGatewayClient) {
        this.aiGatewayClient = aiGatewayClient;
    }

    public ChatResp call() {
        ChatReq req = new ChatReq(
                "CONTRACT_SUMMARY",
                "t001",
                "u123",
                List.of(new ChatMessage("user", "请总结这份合同的重点")),
                "text",
                false
        );

        ChatResp resp = aiGatewayClient.chat(req);
        System.out.println("model = " + resp.model);
        System.out.println("data = " + resp.data);
        return resp;
    }
}
```

### 流式调用示例

```java
import com.yowits.banbu.ai.client.AiGatewayClient;
import com.yowits.banbu.ai.client.AiGatewayClient.ChatMessage;
import com.yowits.banbu.ai.client.AiGatewayClient.ChatReq;
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.publisher.Flux;

import java.util.List;

public class AiGatewayStreamExample {
    private final AiGatewayClient aiGatewayClient;

    public AiGatewayStreamExample(AiGatewayClient aiGatewayClient) {
        this.aiGatewayClient = aiGatewayClient;
    }

    public Flux<ServerSentEvent<String>> call() {
        ChatReq req = new ChatReq(
                "CONTRACT_SUMMARY",
                "t001",
                "u123",
                List.of(new ChatMessage("user", "请流式输出这份合同的重点")),
                "text",
                true
        );

        return aiGatewayClient.chatStream(req)
                .doOnNext(event -> System.out.println(event.data()));
    }
}
```

### 关键类说明

- `AiGatewayClient`：SDK 入口类，提供 `chat(ChatReq)` 和 `chatStream(ChatReq)` 两个方法，分别对应 `/ai/chat` 与 `/ai/chat/stream`
- `ChatReq`：请求对象，字段包括 `scene`、`tenantId`、`userId`、`messages`、`responseFormat`、`stream`
- `ChatResp`：非流式响应对象，包含 `data` 和 `model`
- `ChatMessage`：对话消息对象，包含 `role` 和 `content`

## 错误响应

所有错误统一为结构化 JSON：

```json
{
  "timestamp": "2026-03-10T11:00:00Z",
  "path": "/ai/chat",
  "status": 429,
  "error": "Too Many Requests",
  "code": "AI_RATE_LIMITED",
  "message": "Tenant rate limit exceeded for tenantId=t001",
  "requestId": "xxx"
}
```

常见错误码：
- `INVALID_REQUEST`
- `AI_RATE_LIMITED`
- `AI_UNAVAILABLE`
- `AI_TIMEOUT`
- `AI_INTERNAL_ERROR`

## 配置规则

优先记这几条：
- `providers.clients` 的 key 就是别名
- `alias:model` 必须引用已存在的别名
- 新配置优先写 `chains`
- `routes` 是兼容字段，不推荐继续扩展依赖
- YAML 示例统一用 kebab-case

启动时会校验：
- `alias:model` 格式
- routes / chains 引用的别名是否存在
- `timeout-ms`、`per-route-max-attempts`、`default-requests-per-minute` 是否大于 0

启动时会打印配置摘要日志：
- 已注册别名
- 默认模型
- routes / chains 摘要
- policy / guard 摘要

## 常见易错点

- Moonshot `base-url` 不要带 `/v1`
- `chains` 没显式写主路由时，服务端仍会自动补主路由到链首
- `ai.guard` 当前是单实例内存限流，只适合试点或单实例部署
- `responseSchema` 是约束提示，不是 provider 原生强约束

## 测试

本地测试：
```bash
mvn test
```

真实 Kimi E2E：
```bash
mvn -P e2e-kimi \
  -Dopenai.base.url=https://api.moonshot.cn \
  -Dopenai.api.key=sk-*** \
  -Dtest=ChatE2EKimiTest test
```

当前测试覆盖重点：
- 路由与主备链
- 流式 / 非流式
- 结构化输出
- 参数校验
- 租户限流
- 真实外部 provider 回归

## 文档导航

- 配置说明：[docs/CONFIG.md](/Users/hand/project/airouter/docs/CONFIG.md)
- 部署说明：[docs/DEPLOY.md](/Users/hand/project/airouter/docs/DEPLOY.md)
