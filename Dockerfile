# syntax=docker/dockerfile:1

FROM eclipse-temurin:17-jdk AS build
WORKDIR /workspace

# 复制源码并构建（如有私库请在构建环境配置 settings.xml）
COPY . .
RUN ./mvnw -v >/dev/null 2>&1 || true
RUN mvn -q -DskipTests package

FROM eclipse-temurin:17-jre
ENV JAVA_OPTS="" \
    SERVER_PORT=8081 \
    OPENAI_BASE_URL="" \
    OPENAI_COMPAT_API_KEY=""

WORKDIR /app
COPY --from=build /workspace/target/banbu-airouter-0.0.1-SNAPSHOT.jar /app/app.jar
EXPOSE 8081
ENTRYPOINT ["sh","-c","java $JAVA_OPTS -jar /app/app.jar"]

