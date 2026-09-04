package com.mcpbridge.executor.config;

import com.mcpbridge.executor.snapshot.NodeLifecycleService;
import com.mcpbridge.executor.snapshot.SnapshotSyncService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.Trigger;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;

import java.time.Duration;
import java.time.Instant;
import java.util.function.Supplier;

/**
 * 后台任务编排。
 *
 * <p>为什么不用 {@code @Scheduled}：心跳周期是 Manager 在注册响应里下发的
 * （{@code heartbeatIntervalSeconds}），运行期可变；而 {@code @Scheduled} 的周期在启动时就固定了。
 * 用 {@link Trigger} 才能让「下一次执行时间」每次重新计算。
 *
 * <p>两个任务共用一个小线程池：它们都是「短平快的 HTTP 调用」，
 * 池子开大只会在 Manager 不可用时堆积任务。任务内部自带 try/catch，绝不让异常打断调度。
 */
@Configuration
public class ExecutorSchedulingConfig implements SchedulingConfigurer {

    private static final Logger log = LoggerFactory.getLogger(ExecutorSchedulingConfig.class);

    private static final Duration MIN_INTERVAL = Duration.ofSeconds(1);

    private final ExecutorProperties properties;
    private final SnapshotSyncService snapshotSyncService;
    private final NodeLifecycleService nodeLifecycleService;

    public ExecutorSchedulingConfig(ExecutorProperties properties,
                                    SnapshotSyncService snapshotSyncService,
                                    NodeLifecycleService nodeLifecycleService) {
        this.properties = properties;
        this.snapshotSyncService = snapshotSyncService;
        this.nodeLifecycleService = nodeLifecycleService;
    }

    @Bean(destroyMethod = "shutdown")
    public ThreadPoolTaskScheduler executorTaskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(3);
        scheduler.setThreadNamePrefix("executor-sync-");
        scheduler.setRemoveOnCancelPolicy(true);
        scheduler.setWaitForTasksToCompleteOnShutdown(false);
        return scheduler;
    }

    @Override
    public void configureTasks(ScheduledTaskRegistrar registrar) {
        registrar.setTaskScheduler(executorTaskScheduler());
        // 快照轮询：EXE-01 要求发布后 ≤30s 生效，默认 10s 留足重试余量
        registrar.addTriggerTask(
                () -> runQuietly("快照轮询", snapshotSyncService::poll),
                periodic(() -> properties.manager().pollInterval()));
        // 心跳：周期由 Manager 下发；注册成功后才会真正执行
        registrar.addTriggerTask(
                () -> runQuietly("节点心跳", nodeLifecycleService::heartbeat),
                periodic(nodeLifecycleService::heartbeatInterval));
        log.info("后台任务已注册：快照轮询={} 心跳={}",
                properties.manager().pollInterval(), nodeLifecycleService.heartbeatInterval());
    }

    private static void runQuietly(String name, Runnable task) {
        try {
            task.run();
        } catch (RuntimeException e) {
            // 调度线程里抛出异常会取消后续执行，必须吞掉并记录
            log.warn("{}任务异常：{}", name, e.getMessage());
        }
    }

    /** 每次执行完重新取周期，因此运行期改配置或 Manager 下发新周期都能立刻生效。 */
    private static Trigger periodic(Supplier<Duration> interval) {
        return context -> {
            Instant base = context.lastCompletion() != null ? context.lastCompletion() : Instant.now();
            Duration next = interval.get();
            if (next == null || next.compareTo(MIN_INTERVAL) < 0) {
                next = MIN_INTERVAL;
            }
            return base.plus(next);
        };
    }
}