# 部署指南（K8s/Helm）

本指南覆盖容器构建、K8s 部署（含探针/资源/HPA/灰度）、以及 Helm 参数建议。

## 容器镜像
- 本地构建：`mvn -DskipTests package`，产物：`target/banbu-airouter-0.0.1-SNAPSHOT.jar`
- Dockerfile（示例）
```
FROM eclipse-temurin:17-jre
ENV JAVA_OPTS="-Xms512m -Xmx1024m"
WORKDIR /app
COPY target/banbu-airouter-0.0.1-SNAPSHOT.jar app.jar
EXPOSE 8081
ENTRYPOINT ["sh","-c","java $JAVA_OPTS -jar /app/app.jar"]
```

## Kubernetes 清单（示例）
- Deployment（含探针/资源/环境变量）
```
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
        - name: OPENAI_BASE_URL
          value: https://api.moonshot.cn
        - name: OPENAI_COMPAT_API_KEY
          valueFrom:
            secretKeyRef:
              name: airouter-secrets
              key: openai_key
        - name: OPENAI_GLM_BASE_URL
          value: https://open.bigmodel.cn/api/paas
        - name: OPENAI_GLM_API_KEY
          valueFrom:
            secretKeyRef:
              name: airouter-secrets
              key: glm_key
        - name: DASHSCOPE_API_KEY
          valueFrom:
            secretKeyRef:
              name: airouter-secrets
              key: qwen_key
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
            memory: "2048Mi"
```
- Service
```
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
- Ingress（示例，按集群网关调整）
```
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

## HPA（水平扩缩容）
```
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

## 灰度/金丝雀（思路）
- 双 Deployment：`banbu-airouter` 与 `banbu-airouter-canary`，Service 选择器打双标签，通过 Ingress/网关权重或 Header 路由。
- 或使用 Service Mesh（Istio/Linkerd）按权重 or Header 分流。
- 对应将 `ai.routes/ai.chains/ai.policy` 配置为可灰度参数（建议接入配置中心以便在线切换）。

## Helm 建议
- values.yaml 关键项：
  - image.repository/tag/pullPolicy
  - env（OPENAI_BASE_URL、OPENAI_COMPAT_API_KEY、OPENAI_GLM_*、DASHSCOPE_API_KEY）
  - resources、probes、hpa、ingress、service
  - config.extraApplicationYaml（将 routes/chains/policy/providers 放入 ConfigMap 注入）
- Chart 模板：Deployment/Service/Ingress/HPA/ConfigMap/Secret

## 观测与日志
- 打开 `/actuator/prometheus`，在 Service/Pod 上添加 Prometheus scrape 注解。
- 建议接入 OpenTelemetry（OTLP）输出 Trace；日志中包含 `ai_call` 与 `route_failed`，便于故障定位。

