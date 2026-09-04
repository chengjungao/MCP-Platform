# 数据面镜像。与 manager 同一套约定：jar 由外部构建产出，镜像只管运行时。
FROM eclipse-temurin:23-jre

RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

RUN groupadd --system spring && useradd --system --gid spring --home /app spring

WORKDIR /app
COPY mcp-executor/target/mcp-executor.jar /app/app.jar
RUN chown -R spring:spring /app
USER spring

# 数据面是转发型负载：堆小、并发高，用 ZGC 压低停顿；
# 响应体按 mcp.executor.upstream.max-response-bytes 走堆外缓冲，不要盲目调大 -Xmx
ENV JAVA_OPTS="-XX:MaxRAMPercentage=70 -XX:+UseZGC" \
    EXECUTOR_PORT=9090

EXPOSE 9090

# /healthz 只在「从未成功同步过快照」时返回 503；
# Redis 退化或 Manager 短暂不可达只会降级为 DEGRADED，不摘节点——
# 误摘会把局部抖动放大成全集群容量骤降
HEALTHCHECK --interval=10s --timeout=5s --start-period=40s --retries=6 \
    CMD curl -fsS "http://localhost:${EXECUTOR_PORT}/healthz" || exit 1

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]