package com.mcpbridge.executor.config;

import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * 本节点身份。
 *
 * <p>nodeKey 是 Manager 侧 {@code (clusterId, nodeKey)} 唯一键的一部分，因此必须稳定且唯一：
 * 同一容器重启后应当报同一个 key（否则节点表会无限增长），不同容器必须不同。
 * 「主机名 + 端口」在容器环境下恰好满足两者：主机名就是容器 id。
 */
@Component
public class NodeIdentity {

    private final String nodeKey;
    private final String hostName;

    public NodeIdentity(ExecutorProperties properties) {
        this.hostName = resolveHostName();
        String configured = properties.node().key();
        this.nodeKey = configured != null && !configured.isBlank()
                ? configured.trim()
                : hostName + ":" + properties.node().port();
    }

    public String nodeKey() {
        return nodeKey;
    }

    public String hostName() {
        return hostName;
    }

    private static String resolveHostName() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException | RuntimeException e) {
            // 容器里偶发解析失败；退回 HOSTNAME 环境变量，再退回随机后缀，保证 nodeKey 一定唯一
            String fromEnv = System.getenv("HOSTNAME");
            return fromEnv != null && !fromEnv.isBlank()
                    ? fromEnv
                    : "node-" + Long.toHexString(System.nanoTime());
        }
    }
}