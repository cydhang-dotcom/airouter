FROM eclipse-temurin:17-jre
WORKDIR /app

ENV JAVA_OPTS="" \
    SERVER_PORT=8081 \
    OPENAI_BASE_URL="" \
    OPENAI_COMPAT_API_KEY=""

COPY target/banbu-airouter-0.0.1-SNAPSHOT.jar /app/app.jar
EXPOSE 8081

ENTRYPOINT ["sh","-c","java $JAVA_OPTS -jar /app/app.jar"]
