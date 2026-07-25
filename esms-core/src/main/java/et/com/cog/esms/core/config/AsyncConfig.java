package et.com.cog.esms.core.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.aop.interceptor.AsyncUncaughtExceptionHandler;
import org.springframework.aop.interceptor.SimpleAsyncUncaughtExceptionHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.lang.reflect.Method;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * Explicit executors for @Async work.
 *
 * Previously nothing was configured, so Spring fell back to
 * SimpleAsyncTaskExecutor, which starts a brand new thread for every single
 * task and never bounds them.
 *
 * Defining the default here is not optional once auditExecutor exists: with a
 * single Executor bean on the context and no declared default, Spring resolves
 * that lone bean as the default for every unqualified @Async method - which
 * would have quietly moved report generation onto the audit log's
 * single-thread executor and serialised exports behind audit writes.
 */
@Slf4j
@Configuration
public class AsyncConfig implements AsyncConfigurer {

    /** Default for unqualified @Async work, e.g. report export generation. */
    @Override
    public Executor getAsyncExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("esms-async-");
        executor.initialize();
        return executor;
    }

    /**
     * Audit writes, deliberately single-threaded.
     *
     * The hash chain requires appends to happen one at a time; concurrent
     * appenders read the same previous row and fork the chain. One thread
     * gives that ordering within this instance for free (the advisory lock in
     * AuditLogRepository covers multiple instances).
     *
     * CallerRunsPolicy rather than the default AbortPolicy: if the queue fills,
     * the calling request thread performs the write itself and is slowed down.
     * Dropping an audit entry to keep the request fast is the wrong trade for
     * a security log.
     */
    @Bean("auditExecutor")
    public Executor auditExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(1000);
        executor.setThreadNamePrefix("esms-audit-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(20);
        executor.initialize();
        return executor;
    }

    /** Async failures were previously invisible; surface them. */
    @Override
    public AsyncUncaughtExceptionHandler getAsyncUncaughtExceptionHandler() {
        return new SimpleAsyncUncaughtExceptionHandler() {
            @Override
            public void handleUncaughtException(Throwable ex, Method method, Object... params) {
                log.error("Uncaught exception in @Async method {}", method.getName(), ex);
            }
        };
    }
}
