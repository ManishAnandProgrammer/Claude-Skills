package com.example.app.support;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.aopalliance.intercept.MethodInterceptor;
import org.hibernate.Session;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.orm.jpa.EntityManagerFactoryUtils;
// sign-in:begin
import org.springframework.security.authentication.password.CompromisedPasswordChecker;
// sign-in:end
import org.springframework.transaction.TransactionExecution;
import org.springframework.transaction.TransactionExecutionListener;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
// media:begin
import software.amazon.awssdk.services.s3.S3Client;
// media:end

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Transaction boundaries every integration test checks, so every endpoint and job the tests reach is
 * covered, a project's generated ones included. {@link IntegrationTest} imports it and fails a test that
 * broke one of them:
 * <ul>
 *   <li>a read-only transaction ends with no pending change. Spring never flushes one: a change made in it
 *       (a write method without its {@code @Transactional}, an entity changed inside a read) is dropped at
 *       the commit, without an error;</li>
 *   <li>a GET request opens no write transaction (a read changes nothing, and a read-only transaction
 *       tells PostgreSQL so);</li>
 *   <li>no remote call (the object store, an HTTP API) runs while a transaction is open: the connection
 *       would be held for the call's whole duration, and a slow service would drain the pool.</li>
 * </ul>
 * A project's own remote client (another HTTP API) is added to {@link #isRemoteClient}.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TransactionGuards {

    private static final List<String> VIOLATIONS = new CopyOnWriteArrayList<>();

    /** The broken checks since the last call, which clears them. */
    public static List<String> takeViolations() {
        List<String> taken = new ArrayList<>(VIOLATIONS);
        VIOLATIONS.removeAll(taken);
        return taken;
    }

    @Bean
    TransactionExecutionListener transactionBoundaryChecks(EntityManagerFactory entityManagerFactory) {
        return new TransactionExecutionListener() {
            @Override
            public void afterBegin(TransactionExecution transaction, Throwable beginFailure) {
                if (Objects.nonNull(beginFailure)) {
                    return;
                }
                if (transaction.isReadOnly()) {
                    keepWhatItLoads(entityManagerFactory);
                } else if (isGetRequest()) {
                    VIOLATIONS.add("a GET request opened a write transaction: " + transaction.getTransactionName());
                }
            }

            @Override
            public void beforeCommit(TransactionExecution transaction) {
                if (transaction.isReadOnly() && hasPendingChanges(entityManagerFactory)) {
                    VIOLATIONS.add("a read-only transaction ended with changes it drops: " + transaction.getTransactionName());
                }
            }
        };
    }

    @Bean
    static BeanPostProcessor remoteCallChecks() {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) {
                if (!isRemoteClient(bean)) {
                    return bean;
                }
                ProxyFactory proxy = new ProxyFactory(bean);
                proxy.addAdvice((MethodInterceptor) call -> {
                    if (TransactionSynchronizationManager.isActualTransactionActive()) {
                        VIOLATIONS.add("%s.%s called inside a transaction".formatted(beanName, call.getMethod().getName()));
                    }
                    return call.proceed();
                });
                return proxy.getProxy();
            }
        };
    }

    /** The clients that leave the process. Signing an upload URL is local, so the S3 presigner isn't one. */
    private static boolean isRemoteClient(Object bean) {
        boolean remote = false;
        // sign-in:begin
        remote = remote || bean instanceof CompromisedPasswordChecker;
        // sign-in:end
        // media:begin
        remote = remote || bean instanceof S3Client;
        // media:end
        return remote;
    }

    /**
     * Spring opens a read-only transaction's session read-only, and Hibernate then keeps no copy of what it
     * loaded: a change can't be found later. In tests the copies are kept, so it can. The session still
     * never flushes on its own, and PostgreSQL still runs the transaction read-only.
     */
    private static void keepWhatItLoads(EntityManagerFactory entityManagerFactory) {
        EntityManager entityManager = EntityManagerFactoryUtils.getTransactionalEntityManager(entityManagerFactory);
        if (Objects.nonNull(entityManager)) {
            entityManager.unwrap(Session.class).setDefaultReadOnly(false);
        }
    }

    private static boolean hasPendingChanges(EntityManagerFactory entityManagerFactory) {
        EntityManager entityManager = EntityManagerFactoryUtils.getTransactionalEntityManager(entityManagerFactory);
        return Objects.nonNull(entityManager) && entityManager.unwrap(Session.class).isDirty();
    }

    private static boolean isGetRequest() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        return attributes instanceof ServletRequestAttributes request && "GET".equals(request.getRequest().getMethod());
    }
}
