# 控制面镜像。
#
# 刻意不在镜像内跑 Maven：多模块 reactor 构建需要父 pom 与 mcp-common 同时进上下文，
# 缓存层极易失效，每次改一行都要重新下全部依赖。约定由 build.cmd / mvn package 先产出
# 可执行 jar，镜像只负责运行时——构建快、可复现，也避免把源码与 ~/.m2 带进交付物。
FROM eclipse-temurin:23-jre

# curl 只用于容器健康检查（Spring Boot 的 /actuator/health 在 DOWN 时返回 503）
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

# 以非 root 运行：控制面持有 JWT 签名密钥与 Auth-B 加密主密钥，
# 容器逃逸时不应顺带拿到宿主 root
RUN groupadd --system spring && useradd --system --gid spring --home /app spring

WORKDIR /app
COPY mcp-manager/target/mcp-manager.jar /app/app.jar
RUN chown -R spring:spring /app
USER spring

ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+UseZGC -Djava.security.egd=file:/dev/./urandom" \
    MANAGER_PORT=8080

EXPOSE 8080

HEALTHCHECK --interval=15s --timeout=5s --start-period=60s --retries=5 \
    CMD curl -fsS "http://localhost:${MANAGER_PORT}/actuator/health" || exit 1

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]