package com.example.app.support;

import org.aopalliance.intercept.MethodInterceptor;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Counts the JDBC template's queries the current thread runs while {@link #record} does: the roles piece's
 * per-request read is plain JDBC through Boot's {@link NamedParameterJdbcTemplate}, which Hibernate's statistics
 * don't see, and each query call is one statement. With Hibernate's prepare count beside it, a test counts the
 * statements a request runs through those two (a plain {@code JdbcTemplate} or a raw connection it would not see). A test component (the shared context scans it); outside {@link #record} it only passes
 * the calls on.
 */
@Component
public class StatementCounter implements BeanPostProcessor {

    private static final ThreadLocal<AtomicInteger> RECORDED = new ThreadLocal<>();

    /** How many template queries {@code work} ran on this thread. */
    public static int record(Callable<?> work) throws Exception {
        AtomicInteger queries = new AtomicInteger();
        RECORDED.set(queries);
        try {
            work.call();
        } finally {
            RECORDED.remove();
        }
        return queries.get();
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) {
        if (!(bean instanceof NamedParameterJdbcTemplate)) {
            return bean;
        }
        ProxyFactory proxy = new ProxyFactory(bean);
        proxy.setProxyTargetClass(true);
        proxy.addAdvice((MethodInterceptor) invocation -> {
            AtomicInteger queries = RECORDED.get();
            if (Objects.nonNull(queries) && invocation.getMethod().getName().startsWith("query")) {
                queries.incrementAndGet();
            }
            return invocation.proceed();
        });
        return proxy.getProxy();
    }
}
