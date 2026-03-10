# 部署指南

本文面向第一期部署，目标是把服务以稳定、可回滚、可观测的方式上线。

## 部署前检查

上线前至少确认：
- `mvn test` 通过
- 目标环境配置了 provider 的密钥和 base-url
- `providers.clients`、`chains`、`policy` 已检查
- 健康检查地址 `/actuator/health` 可用
- 已确认限流策略是否适合当前环境

如果要验证真实外部链路，建议额外跑一次：

```bash
mvn -P e2e-kimi \
  -Dopenai.base.url=https://api.moonshot.cn \
  -Dopenai.api.key=sk-*** \
  -Dtest=ChatE2EKimiTest test
```

## 容器镜像

构建：
```bash
mvn -DskipTests package
```

示例 Dockerfile：
```dockerfile
FROM eclipse-temurin:17-jre

ENV JAVA_OPTS="-Xms512m -Xmx1024m"
WORKDIR /app

COPY target/banbu-airouter-0.0.1-SNAPSHOT.jar app.jar

EXPOSE 8081
ENTRYPOINT ["sh","-c","java $JAVA_OPTS -jar /app/app.jar"]
```

镜像构建：
```bash
docker build -t banbu-airouter:latest .
```

本地容器运行：
```bash
docker run --rm -p 8081:8081 \
  -e OPENAI_BASE_URL=https://api.moonshot.cn \
  -e OPENAI_COMPAT_API_KEY=sk-*** \
  --name airouter \
  banbu-airouter:latest
```

## Kubernetes 部署建议

### Deployment

建议：
- 至少 `2` 个副本
- readiness / liveness 都走 `/actuator/health`
- 配置和密钥分离

示例：
```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: banbu-airouter
spec:
  replicas: 2
  selector:
    matchLabels:
      app: banbu-airouter
  template:
    metadata:
      labels:
        app: banbu-airouter
    spec:
      containers:
        - name: app
          image: your-registry/banbu-airouter:0.0.1
          ports:
            - containerPort: 8081
          env:
            - name: OPENAI_KIMI_BASE_URL
              value: https://api.moonshot.cn
            - name: OPENAI_KIMI_API_KEY
              valueFrom:
                secretKeyRef:
                  name: airouter-secrets
                  key: kimi_key
            - name: OPENAI_GLM_BASE_URL
              value: https://open.bigmodel.cn/api/paas
            - name: OPENAI_GLM_API_KEY
              valueFrom:
                secretKeyRef:
                  name: airouter-secrets
                  key: glm_key
          readinessProbe:
            httpGet:
              path: /actuator/health
              port: 8081
            initialDelaySeconds: 10
            periodSeconds: 10
          livenessProbe:
            httpGet:
              path: /actuator/health
              port: 8081
            initialDelaySeconds: 30
            periodSeconds: 30
          resources:
            requests:
              cpu: "200m"
              memory: "512Mi"
            limits:
              cpu: "1"
              memory: "2Gi"
```

### Service

```yaml
apiVersion: v1
kind: Service
metadata:
  name: banbu-airouter
spec:
  selector:
    app: banbu-airouter
  ports:
    - name: http
      port: 80
      targetPort: 8081
```

### Ingress

```yaml
apiVersion: networking.k8s.io/v1
kind: Ingress
metadata:
  name: banbu-airouter
spec:
  rules:
    - host: ai.your-domain.com
      http:
        paths:
          - path: /
            pathType: Prefix
            backend:
              service:
                name: banbu-airouter
                port:
                  number: 80
```

## 配置注入建议

建议分开管理：
- Secret：`api-key`
- ConfigMap：`chains`、`policy`、`guard`
- 环境变量：各 provider 的 base-url

如果后面场景和租户变多，建议把配置迁到配置中心，而不是持续堆在单个 `application.yml`。

## 扩缩容

示例 HPA：
```yaml
apiVersion: autoscaling/v2
kind: HorizontalPodAutoscaler
metadata:
  name: banbu-airouter
spec:
  scaleTargetRef:
    apiVersion: apps/v1
    kind: Deployment
    name: banbu-airouter
  minReplicas: 2
  maxReplicas: 10
  metrics:
    - type: Resource
      resource:
        name: cpu
        target:
          type: Utilization
          averageUtilization: 60
```

注意：
- 当前 `ai.guard` 是单实例内存限流，副本数增加后不会共享计数
- 如果上线为多实例，请把强限流放到网关层或 Redis

## 灰度发布建议

第一期建议用最简单的方式：
- 主 Deployment
- Canary Deployment
- 由 Ingress / Gateway 做权重分流

如果要更稳，可以按以下顺序灰度：
1. 先灰度低风险 scene
2. 再灰度结构化输出场景
3. 最后灰度高频业务场景

## 观测建议

当前至少应接入：
- `/actuator/health`
- `/actuator/prometheus`
- 应用日志

重点关注：
- `ai_call`
- `route_failed`
- P95 / P99
- 错误率
- fallback 命中率
- 限流命中率

## 回滚建议

出现问题时，优先按这几个层级回滚：
1. 回滚 Deployment 镜像版本
2. 回滚 `chains` / `policy` 配置
3. 临时提高 `timeout-ms` 或关闭高风险 fallback
4. 必要时下线高风险 scene 的 AI 能力

## 上线后检查

上线完成后建议立即检查：
- 健康检查是否正常
- 配置摘要日志是否符合预期
- `providers.clients` 是否全部注册
- 首条 `/ai/chat` 调用是否成功
- 真实 fallback 是否符合配置预期
