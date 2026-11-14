package com.example.app.config;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.LazyConnectionDataSourceProxy;

/**
 * Wraps the pool so a transaction borrows its connection at the first statement. Without it, every
 * read-only transaction (the class-level default on services) borrows at begin: Spring's
 * {@code HibernateJpaDialect} takes the connection then to call {@code setReadOnly}. The proxy
 * records the flag and applies it when the real connection is borrowed, so PostgreSQL still runs
 * the transaction read-only. Code that must hold a real connection without running a statement
 * borrows from the unwrapped {@link HikariDataSource}.
 */
@Configuration(proxyBeanMethods = false)
class LazyConnectionConfig {

    @Bean
    static BeanPostProcessor lazyConnectionDataSource() {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) {
                if (bean instanceof HikariDataSource pool) {
                    return new LazyConnectionDataSourceProxy(pool);
                }
                return bean;
            }
        };
    }
}
