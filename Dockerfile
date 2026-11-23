# ---- 构建阶段 ----
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /src
COPY pom.xml .
# 先只拷 pom 跑一次依赖解析，利用镜像层缓存
RUN mvn -q -B dependency:go-offline
COPY src ./src
RUN mvn -q -B -DskipTests package

# ---- 运行阶段 ----
FROM eclipse-temurin:17-jre
WORKDIR /app
ENV TZ=Asia/Shanghai \
    JAVA_OPTS="-XX:MaxRAMPercentage=65 -XX:+UseG1GC -Dfile.encoding=UTF-8"
COPY --from=build /src/target/ltc-rag.jar /app/app.jar
EXPOSE 8080
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
