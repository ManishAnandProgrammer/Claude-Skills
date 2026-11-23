package com.example.app.config;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.MessageSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.support.ReloadableResourceBundleMessageSource;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import org.springframework.web.servlet.LocaleResolver;
import org.springframework.web.servlet.i18n.AcceptHeaderLocaleResolver;

import java.time.Clock;
import java.util.List;
import java.util.Locale;

@Configuration
class MessageSourceConfig {

    @Bean
    static MessageSource messageSource() {
        return messageSource("classpath:messages/message");
    }

    static MessageSource messageSource(String basename) {
        ReloadableResourceBundleMessageSource messageSource = new ReloadableResourceBundleMessageSource();
        messageSource.setBasename(basename);
        messageSource.setDefaultEncoding("UTF-8");
        // Without it a message used with no arguments skips MessageFormat, and '' or an unfilled {0}
        // reaches the client raw.
        messageSource.setAlwaysUseMessageFormat(true);
        messageSource.setFallbackToSystemLocale(false);
        // Without it a language that has no file of its own (de, pa, hi, ...) fails the lookup, and the error
        // response with it.
        messageSource.setDefaultLocale(Locale.ENGLISH);
        return messageSource;
    }

    /**
     * Boot's validator customisers don't reach a validator defined here. The clock is looked up on use: the
     * validator is created early, before ordinary beans.
     */
    @Bean
    static LocalValidatorFactoryBean validator(MessageSource messageSource, ObjectProvider<Clock> clock) {
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.setValidationMessageSource(messageSource);
        validator.setConfigurationInitializer(configuration -> configuration.clockProvider(clock::getObject));
        return validator;
    }

    @Bean
    static LocaleResolver localeResolver() {
        AcceptHeaderLocaleResolver localeResolver = new AcceptHeaderLocaleResolver();
        localeResolver.setSupportedLocales(List.of(Locale.ENGLISH));
        localeResolver.setDefaultLocale(Locale.ENGLISH);
        return localeResolver;
    }
}
