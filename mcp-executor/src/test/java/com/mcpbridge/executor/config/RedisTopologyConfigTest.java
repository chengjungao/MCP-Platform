package com.mcpbridge.executor.config;

import com.mcpbridge.executor.config.ExecutorProperties.Redis.Mode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.yaml.snakeyaml.Yaml;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Redis 接入形态（BR-6）：single / cluster 的可配置切换。
 *
 * <p>盯三件事：①两种形态的 yml 绑定与默认值；②地址归一化（协议前缀、空白、TLS）；
 * ③配置不自洽时<b>快速失败</b>——「连不上」可以降级成内存模式，「配错了」不行，
 * 否则运维会以为自己配的是三节点集群，实际每个节点各跑各的内存态。
 */
class RedisTopologyConfigTest {

    /** 只注册 Properties，不注册 SharedStateConfig——后者会真的去建连，单测不该依赖 Redis。 */
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(PropertiesOnly.class);

    @Test
    @DisplayName("缺省是单节点形态：address 有默认值，nodes 为空")
    void defaultsToSingleMode() {
        runner.withPropertyValues("mcp.executor.redis.enabled=true").run(ctx -> {
            ExecutorProperties.Redis redis = ctx.getBean(ExecutorProperties.class).redis();

            assertThat(redis.mode()).isEqualTo(Mode.SINGLE);
            assertThat(redis.address()).isEqualTo("redis://localhost:6379");
            assertThat(redis.nodes()).isNullOrEmpty();
            assertThat(SharedStateConfig.endpoints(redis)).containsExactly("redis://localhost:6379");
        });
    }

    @Test
    @DisplayName("cluster 模式绑定多个节点；address 的默认值还在，但不参与接入")
    void bindsClusterNodes() {
        runner.withPropertyValues(
                "mcp.executor.redis.mode=cluster",
                "mcp.executor.redis.nodes=redis://h1:6379,redis://h2:6379,redis://h3:6379").run(ctx -> {
            ExecutorProperties.Redis redis = ctx.getBean(ExecutorProperties.class).redis();

            assertThat(redis.mode()).isEqualTo(Mode.CLUSTER);
            assertThat(SharedStateConfig.endpoints(redis)).containsExactly(
                    "redis://h1:6379", "redis://h2:6379", "redis://h3:6379");
        });
    }

    @Test
    @DisplayName("mode 大小写不敏感，节点地址可省略协议前缀")
    void modeIsCaseInsensitiveAndSchemeIsOptional() {
        runner.withPropertyValues(
                "mcp.executor.redis.mode=CLUSTER",
                "mcp.executor.redis.nodes=h1:6379,h2:6379").run(ctx -> {
            ExecutorProperties.Redis redis = ctx.getBean(ExecutorProperties.class).redis();

            assertThat(redis.mode()).isEqualTo(Mode.CLUSTER);
            assertThat(SharedStateConfig.endpoints(redis))
                    .containsExactly("redis://h1:6379", "redis://h2:6379");
        });
    }

    @Test
    @DisplayName("地址归一化：补 redis://、保留 rediss://、空白按未配置处理")
    void normalizesAddresses() {
        assertThat(SharedStateConfig.normalizeAddress("  h1:6379  ")).isEqualTo("redis://h1:6379");
        // TLS 前缀必须原样保留：静默降级成明文是安全事故，不是便利
        assertThat(SharedStateConfig.normalizeAddress("rediss://h1:6379")).isEqualTo("rediss://h1:6379");
        assertThat(SharedStateConfig.normalizeAddress("")).isNull();
        assertThat(SharedStateConfig.normalizeAddress("   ")).isNull();
        assertThat(SharedStateConfig.normalizeAddress(null)).isNull();
    }

    @Test
    @DisplayName("cluster 模式没给 nodes：启动即失败，且错误信息给出该改哪个 key")
    void clusterWithoutNodesFailsFast() {
        runner.withPropertyValues("mcp.executor.redis.mode=cluster").run(ctx -> {
            ExecutorProperties.Redis redis = ctx.getBean(ExecutorProperties.class).redis();

            assertThatThrownBy(() -> SharedStateConfig.endpoints(redis))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("mcp.executor.redis.nodes")
                    .hasMessageContaining("mcp.executor.redis.mode=single");
        });
    }

    @Test
    @DisplayName("EXECUTOR_REDIS_NODES= 的空串注入按「没配」处理，不当作一个空节点")
    void blankNodesAreTreatedAsMissing() {
        runner.withPropertyValues(
                "mcp.executor.redis.mode=cluster",
                "mcp.executor.redis.nodes=").run(ctx -> {
            ExecutorProperties.Redis redis = ctx.getBean(ExecutorProperties.class).redis();

            assertThatThrownBy(() -> SharedStateConfig.endpoints(redis))
                    .isInstanceOf(IllegalStateException.class);
        });
    }

    @Test
    @DisplayName("single 模式没给 address：同样快速失败，不给一个连不上的默认地址")
    void singleWithoutAddressFailsFast() {
        // 直接构造而不是走绑定：这里要验的是 SharedStateConfig 的判定，不是 yml 绑定的空值语义
        ExecutorProperties.Redis redis = new ExecutorProperties.Redis(
                true, Mode.SINGLE, "   ", null, null, 0, "mcp",
                Duration.ofMinutes(30), Duration.ofSeconds(5), Duration.ofSeconds(30));

        assertThatThrownBy(() -> SharedStateConfig.endpoints(redis))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("mcp.executor.redis.address")
                .hasMessageContaining("mode=single");
    }

    @Test
    @DisplayName("application.yml 里的 redis 键与配置类组件一一对应（拼错会静默不绑定，不报错）")
    void applicationYmlRedisKeysMatchProperties() {
        Map<String, Object> yml = new Yaml().load(
                Objects.requireNonNull(getClass().getClassLoader().getResourceAsStream("application.yml")));
        Map<String, Object> redis = section(yml, "mcp", "executor", "redis");

        assertThat(redis.keySet()).containsExactlyInAnyOrder(
                "enabled", "mode", "address", "nodes", "password", "database", "key-prefix",
                "token-ttl", "lock-wait", "lock-lease");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> section(Map<String, Object> root, String... path) {
        Map<String, Object> current = root;
        for (String key : path) {
            current = (Map<String, Object>) current.get(key);
        }
        return current;
    }

    @EnableConfigurationProperties(ExecutorProperties.class)
    static class PropertiesOnly {
    }
}
