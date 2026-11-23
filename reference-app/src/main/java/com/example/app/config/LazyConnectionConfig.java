package com.example.app.config;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.LazyConnectionDataSourceProxy;

/**
 * Without the proxy every read-only transaction borrows at begin: Spring's {@code HibernateJpaDialect} takes the
 * connection then to call {@code setReadOnly}. The proxy records the flag and applies it at the first statement.
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
