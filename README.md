# banbu-airouter（第一期）

基于 Spring Boot + Spring AI 的企业内部 AI 网关微服务。对接 OpenAI 兼容接口（示例：Kimi/Moonshot、GLM）与可插拔 DashScope（千问），统一暴露 /ai/chat 与 /ai/chat/stream，支持场景到模型路由、主备降级链、流式输出、超时/重试/熔断与基础观测。OpenAPI: `/swagger-ui.html`。

## 当前功能清单
- 统一接口：`POST /ai/chat`（非流式）、`POST /ai/chat/stream`（SSE 流式）。
- 多 Provider 并存：Kimi/Moonshot、GLM（OpenAI 兼容）；DashScope（千问）通过 profile 可插拔。
- 多别名配置：`providers.clients.<alias>` 定义多套账号/环境，路由可指向 `alias:model`。
- 路由与降级：`ai.routes` 首选路由，`ai.chains` 主备链（非流式按链路自动切换）。
- 策略引擎：`ai.policy` 支持全局/scene/tenant 三级覆盖（timeoutMs、perRouteMaxAttempts、allowFallback）。
- 租户保护：`ai.guard` 支持租户级基础限流（每分钟请求数）。
- 结构化输出：支持 `responseFormat=json` 与 `responseSchema` 提示；自动剥离围栏并解析 JSON。
- OpenAPI 文档：集成 springdoc（`/swagger-ui.html`）。
- 观测与日志：Actuator、Prometheus（可接入），关键日志 `ai_call`、`route_failed`。
- Client SDK：`client/` 子模块，封装 RestClient/WebClient（支持注册中心）。
- 测试体系：本地 E2E（不联外）、Kimi 外部 E2E（`-P e2e-kimi`），单元与链路降级测试齐备。

## 核心能力
- 统一接口：非流式与 SSE 流式输出
- 场景路由：`ai.routes` 配置 scene → model
- 基础治理：Resilience4j（超时/重试/熔断）
- 观测：Actuator 健康与指标（Prometheus 可接入）

## 快速开始
- 环境：Java 17+、Maven
- Kimi（OpenAI 兼容）环境变量：
  - `export OPENAI_BASE_URL=https://api.moonshot.cn`
  - `export OPENAI_COMPAT_API_KEY=sk-***`
- GLM 环境变量：
  - `export OPENAI_GLM_BASE_URL=https://open.bigmodel.cn/api/paas`
  - `export OPENAI_GLM_API_KEY=sk-***`
- 千问（DashScope）环境变量（启用 dashscope profile 后生效）：
  - `export DASHSCOPE_API_KEY=sk-***`
- 构建：`mvn -DskipTests package`（DashScope：`mvn -Pdashscope -DskipTests package`）
- 启动：`mvn spring-boot:run` 或 `java -jar target/banbu-airouter-0.0.1-SNAPSHOT.jar`
- 健康：`GET http://localhost:8081/actuator/health`

### 使用 Docker 直接运行
- 构建镜像：
  - `docker build -t banbu-airouter:latest .`
- 启动容器（示例：Kimi 环境变量）
  - `docker run --rm -p 8081:8081 \
     -e OPENAI_BASE_URL=https://api.moonshot.cn \
     -e OPENAI_COMPAT_API_KEY=sk-*** \
     --name airouter banbu-airouter:latest`
- 健康检查：
  - `curl -s http://localhost:8081/actuator/health`
- 非流式调用：
  - 见下文“接口说明”中的 cURL 示例（把主机改为 localhost:8081）。

## 配置说明（片段）
application.yml（片段）：
```
ai:
  default-model: kimi-main:moonshot-v1-8k   # alias:model
  routes:
    CONTRACT_SUMMARY: kimi-main:moonshot-v1-8k
    CUSTOMER_FOLLOWUP: glm-main:glm-4
  chains:
    CUSTOMER_FOLLOWUP: ["glm-main:glm-4","kimi-main:moonshot-v1-8k"]
  guard:
    enabled: true
    default-requests-per-minute: 120
    tenants:
      vip-tenant:
        requests-per-minute: 300
spring:
  ai:
    openai:
      base-url: ${OPENAI_BASE_URL:https://api.moonshot.cn}
      api-key: ${OPENAI_COMPAT_API_KEY:}
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
    # 若已引入 DashScope 依赖，可启用：
    # qwen-main:
    #   type: dashscope
    #   api-key: ${DASHSCOPE_API_KEY:}
```
说明：
- Moonshot 模型推荐 `moonshot-v1-8k`；base-url 不要带 `/v1`。
- `chains` 支持主备降级链（从前到后）；显式配置 `providers.clients` 可自由增加多个别名（多套 Kimi/GLM/千问账号或不同环境）。

## DashScope（千问）接入
- 启用 Maven Profile 使用 Spring Snapshot 仓库与 DashScope Starter：
  - `mvn -Pdashscope -DskipTests package`
- 在 application.yml 配置 qwen 别名与路由：
  - providers.clients.qwen-main: `{ type: dashscope, api-key: ${DASHSCOPE_API_KEY} }`
  - ai.routes.XXX: `qwen-main:qwen-turbo`
说明：本服务支持多 alias 并存，你可以并行配置 Kimi/GLM/千问多个账号或环境，通过 scene 路由与主备链灵活切换。

### 联调示例
- 非流式（qwen-main）
```
curl -s -X POST http://localhost:8081/ai/chat \
  -H 'Content-Type: application/json' \
  -d '{
    "scene":"CONTRACT_SUMMARY",
    "tenantId":"t001",
    "userId":"u123",
    "messages":[{"role":"user","content":"用50字总结：千问是什么？"}],
    "responseFormat":"text",
    "stream":false
  }'
```
- Java（RestClient）
```java
var req = new AiGatewayClient.ChatReq(
  "CONTRACT_SUMMARY","t001","u123",
  List.of(new AiGatewayClient.ChatMessage("user","介绍下千问")),
  "text", false
);
var resp = rest.post().uri("http://ai-service/ai/chat").body(req).retrieve().body(ChatResp.class);
```

## 接口说明
- POST `/ai/chat`（非流式）
  - 请求：`scene`、`tenantId`、`userId`、`messages[{role,content}]`、`responseFormat`（text|json）、`stream=false`
  - 响应：`{ data: <string|json>, model: <string> }`
- POST `/ai/chat/stream`（SSE 流式）
  - 同上，`stream=true`，返回 `text/event-stream`，以 `event: message` 分片，末尾 `event: done`
  - 结构化输出：请求中可传 `responseFormat: json` 与 `responseSchema`（JSON Schema 片段），服务会注入约束提示并尽力解析为 JSON（遇到围栏将自动剥离）。

示例（结构化输出）：
```
POST /ai/chat
{
  "scene":"CONTRACT_SUMMARY",
  "tenantId":"t001",
  "userId":"u123",
  "messages":[{"role":"user","content":"请总结这份合同，并给出风险等级和标签"}],
  "responseFormat":"json",
  "responseSchema":{
    "type":"object",
    "properties":{
      "summary":{"type":"string"},
      "riskLevel":{"type":"string"},
      "tags":{"type":"array","items":{"type":"string"}}
    },
    "required":["summary","riskLevel","tags"]
  },
  "stream":false
}
```

错误响应统一为：
```json
{
  "timestamp":"2026-03-10T11:00:00Z",
  "path":"/ai/chat",
  "status":429,
  "error":"Too Many Requests",
  "code":"AI_RATE_LIMITED",
  "message":"Tenant rate limit exceeded for tenantId=t001",
  "requestId":"xxx"
}
```

常见错误码：
- `INVALID_REQUEST`：参数校验失败，或 `/ai/chat` 误传 `stream=true`
- `AI_RATE_LIMITED`：租户超过每分钟请求阈值
- `AI_UNAVAILABLE`：路由不可用或 provider 全部失败
- `AI_TIMEOUT`：AI 调用超时

## Java 调用（Spring 推荐）
- 定义客户端（RestClient 阻塞；WebClient 流式）
```java
@Service
public class AiGatewayClient {
  private final RestClient rest; private final WebClient web;
  public AiGatewayClient(RestClient.Builder r, WebClient.Builder w) {
    this.rest = r.baseUrl("http://ai-service").build(); // 注册中心可用（@LoadBalanced）
    this.web  = w.baseUrl("http://ai-service").build();
  }
  public ChatResp chat(ChatReq req){
    return rest.post().uri("/ai/chat").contentType(MediaType.APPLICATION_JSON)
      .body(req).retrieve().body(ChatResp.class);
  }
  public Flux<ServerSentEvent<String>> chatStream(ChatReq req){
    return web.post().uri("/ai/chat/stream").contentType(MediaType.APPLICATION_JSON)
      .accept(MediaType.TEXT_EVENT_STREAM).bodyValue(req)
      .retrieve().bodyToFlux(new ParameterizedTypeReference<ServerSentEvent<String>>(){});
  }
  public record ChatMessage(String role,String content){}
  public record ChatReq(String scene,String tenantId,String userId,List<ChatMessage> messages,String responseFormat,boolean stream){}
  public static class ChatResp { public Object data; public String model; }
}
```
- 注册中心调用（可选）
```java
@Configuration
class ClientConfig {
  @Bean @LoadBalanced RestClient.Builder lbRest(){ return RestClient.builder(); }
  @Bean @LoadBalanced WebClient.Builder lbWeb(){ return WebClient.builder(); }
}
```
- Feign（非流式）
```java
@EnableFeignClients @EnableDiscoveryClient
@FeignClient(name = "ai-service")
public interface AiFeignClient {
  @PostMapping(value="/ai/chat", consumes=MediaType.APPLICATION_JSON_VALUE)
  AiGatewayClient.ChatResp chat(@RequestBody AiGatewayClient.ChatReq req);
}
```

## 安全与运维
- 切勿提交密钥到仓库；统一使用环境变量或密管（Vault/KMS）。
- 一期建议至少配置 `ai.guard` 做租户级基础限流；单实例模式适合试点，多实例建议后续替换为 Redis/网关侧限流。
- 建议配置限流与配额（按 tenant/scene）；开启访问与调用审计（日志中包含 tenant/scene/model）。
- 观测：Prometheus 指标、OpenTelemetry Trace（可选），按 scene/tenant 维度出图看板。

## 故障排查
- 400 / `INVALID_REQUEST`：流式请求请调用 `/ai/chat/stream`；或校验必填字段。
- 429 / `AI_RATE_LIMITED`：检查 `ai.guard` 的租户限流配置，或由业务侧做退避重试。
- 404 模型：检查 `ai.routes` 的 alias:model 与对应 providers.clients 的 baseUrl/apiKey。
- 503 / `AI_UNAVAILABLE`：查看 `route_failed` 与 `ai_call`，确认主备链是否已耗尽。
- 504 / `AI_TIMEOUT`：适当提高 `timeoutMs` 或缩短上游模型响应内容。

## 测试与验证
- 单元与 E2E 测试：`mvn test`（不依赖外网，使用 Stub/Mock）
- 本地联调：设置环境变量后直接调用上述两个接口

## 常见问题
- 400 且提示“Use /ai/chat/stream”：流式请求请调用 `/ai/chat/stream`
- 404 Not Found 模型：确认 `ai.routes` 的模型名与 `OPENAI_BASE_URL`/Key 匹配（Kimi 推荐 `moonshot-v1-8k`）

## 构建与发布
- Maven 坐标
  - groupId: `com.yowits`
  - artifactId: `banbu-airouter`
  - version: `0.0.1-SNAPSHOT`

- 构建
  - `mvn -DskipTests package`

- 发布到私有仓库（示例：Nexus/Artifactory）
  1) 在 `pom.xml` 添加 distributionManagement（示例）：
  ```xml
  <distributionManagement>
    <repository>
      <id>releases</id>
      <name>Internal Releases</name>
      <url>https://nexus.example.com/repository/maven-releases/</url>
    </repository>
    <snapshotRepository>
      <id>snapshots</id>
      <name>Internal Snapshots</name>
      <url>https://nexus.example.com/repository/maven-snapshots/</url>
    </snapshotRepository>
  </distributionManagement>
  ```
  2) 在 `~/.m2/settings.xml` 配置凭证（ids 与上面保持一致）：
  ```xml
  <servers>
    <server>
      <id>releases</id>
      <username>${env.NEXUS_USER}</username>
      <password>${env.NEXUS_PASS}</password>
    </server>
    <server>
      <id>snapshots</id>
      <username>${env.NEXUS_USER}</username>
      <password>${env.NEXUS_PASS}</password>
    </server>
  </servers>
  ```
  3) 执行发布
  - snapshot：`mvn -DskipTests deploy`
  - release：建议走版本分支并使用 `mvn -Prelease -DskipTests deploy`

## 更多文档
- 部署指南：docs/DEPLOY.md（K8s/Helm、探针/资源/HPA、灰度/金丝雀）
- 配置指南：docs/CONFIG.md（多 Provider/多别名/多环境/策略、结构化输出）

### Client SDK 发布
- 位置：`client/`（独立 Maven 工程，可单独构建发布）
- 坐标：`com.yowits:banbu-airouter-client:0.0.1-SNAPSHOT`
- 构建：
  - `cd client && mvn -DskipTests package`
- 发布：
  - `cd client && mvn -DskipTests deploy`
- 使用（业务侧）：
  - 依赖 `banbu-airouter-client`，注入 `AiGatewayClient`，按 README 的 Spring 示例调用即可（支持 RestClient/WebClient + 注册中心）。
