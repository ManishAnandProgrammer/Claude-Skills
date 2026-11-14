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

    /** The application's settings over any bundle, so a test can render its own messages the same way. */
    static MessageSource messageSource(String basename) {
        ReloadableResourceBundleMessageSource messageSource = new ReloadableResourceBundleMessageSource();
        messageSource.setBasename(basename);
        // The files are UTF-8; read as the default ISO-8859-1, every non-ASCII character would be garbled.
        messageSource.setDefaultEncoding("UTF-8");
        // Without it a message used with no arguments skips MessageFormat, and '' or an unfilled {0}
        // reaches the client raw.
        messageSource.setAlwaysUseMessageFormat(true);
        messageSource.setFallbackToSystemLocale(false);
        // Any language without its own file (de, pa, hi, ...) is answered in English; without it the
        // lookup fails and the error response itself breaks.
        messageSource.setDefaultLocale(Locale.ENGLISH);
        return messageSource;
    }

    /**
     * {@code @PastOrPresent}, {@code @FutureOrPresent} and their kind read "now" from the application's
     * {@link Clock}, not the system clock, so a test with a fixed clock fixes them too. Boot's own
     * validator customisers don't reach a validator defined here. The clock is looked up on use: the
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
