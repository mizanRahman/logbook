package org.zalando.logbook.spring;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.autoconfigure.security.SecurityFilterAutoConfiguration;
import org.springframework.boot.autoconfigure.web.WebMvcAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.Ordered;
import org.zalando.logbook.BodyFilter;
import org.zalando.logbook.BodyFilters;
import org.zalando.logbook.Conditions;
import org.zalando.logbook.DefaultHttpLogFormatter;
import org.zalando.logbook.DefaultHttpLogWriter;
import org.zalando.logbook.DefaultHttpLogWriter.Level;
import org.zalando.logbook.HeaderFilter;
import org.zalando.logbook.HeaderFilters;
import org.zalando.logbook.HttpLogFormatter;
import org.zalando.logbook.HttpLogWriter;
import org.zalando.logbook.JsonHttpLogFormatter;
import org.zalando.logbook.Logbook;
import org.zalando.logbook.QueryFilter;
import org.zalando.logbook.QueryFilters;
import org.zalando.logbook.RawHttpRequest;
import org.zalando.logbook.RawRequestFilter;
import org.zalando.logbook.RawRequestFilters;
import org.zalando.logbook.RawResponseFilter;
import org.zalando.logbook.RawResponseFilters;
import org.zalando.logbook.RequestFilter;
import org.zalando.logbook.ResponseFilter;
import org.zalando.logbook.httpclient.LogbookHttpRequestInterceptor;
import org.zalando.logbook.httpclient.LogbookHttpResponseInterceptor;
import org.zalando.logbook.servlet.LogbookFilter;

import javax.servlet.Filter;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

import static java.util.stream.Collectors.toList;
import static javax.servlet.DispatcherType.ASYNC;
import static javax.servlet.DispatcherType.ERROR;
import static javax.servlet.DispatcherType.REQUEST;

@Configuration
@ConditionalOnClass(Logbook.class)
@EnableConfigurationProperties(LogbookProperties.class)
@AutoConfigureAfter({
        JacksonAutoConfiguration.class,
        SecurityFilterAutoConfiguration.class,
        WebMvcAutoConfiguration.class,
})
@Import(SecurityLogbookAutoConfiguration.class)
public class LogbookAutoConfiguration {

    public static final String AUTHORIZED = "authorizedLogbookFilter";

    @Autowired
    // IDEA doesn't support @EnableConfigurationProperties
    @SuppressWarnings("SpringJavaAutowiringInspection")
    private LogbookProperties properties;

    @Bean
    @ConditionalOnWebApplication
    @ConditionalOnProperty(name = "logbook.filter.enabled", havingValue = "true", matchIfMissing = true)
    @ConditionalOnMissingBean(name = AUTHORIZED)
    public FilterRegistrationBean authorizedLogbookFilter(final Logbook logbook) {
        final Filter filter = new LogbookFilter(logbook);
        final FilterRegistrationBean registration = new FilterRegistrationBean(filter);
        registration.setName(AUTHORIZED);
        registration.setDispatcherTypes(REQUEST, ASYNC, ERROR);
        registration.setOrder(Ordered.LOWEST_PRECEDENCE);
        return registration;
    }

    @Bean
    @ConditionalOnMissingBean(LogbookHttpRequestInterceptor.class)
    public LogbookHttpRequestInterceptor logbookHttpRequestInterceptor(final Logbook logbook) {
        return new LogbookHttpRequestInterceptor(logbook);
    }

    @Bean
    @ConditionalOnMissingBean(LogbookHttpResponseInterceptor.class)
    public LogbookHttpResponseInterceptor logbookHttpResponseInterceptor() {
        return new LogbookHttpResponseInterceptor();
    }

    @Bean
    @ConditionalOnMissingBean(Logbook.class)
    public Logbook logbook(
            final Predicate<RawHttpRequest> condition,
            final List<RawRequestFilter> rawRequestFilters,
            final List<RawResponseFilter> rawResponseFilters,
            final List<HeaderFilter> headerFilters,
            final List<QueryFilter> queryFilters,
            final List<BodyFilter> bodyFilters,
            final List<RequestFilter> requestFilters,
            final List<ResponseFilter> responseFilters,
            @SuppressWarnings("SpringJavaAutowiringInspection") final HttpLogFormatter formatter,
            final HttpLogWriter writer) {
        return Logbook.builder()
                .condition(mergeWithExcludes(condition))
                .rawRequestFilters(rawRequestFilters)
                .rawResponseFilters(rawResponseFilters)
                .headerFilters(headerFilters)
                .queryFilters(queryFilters)
                .bodyFilters(bodyFilters)
                .requestFilters(requestFilters)
                .responseFilters(responseFilters)
                .formatter(formatter)
                .writer(writer)
                .build();
    }

    private Predicate<RawHttpRequest> mergeWithExcludes(final Predicate<RawHttpRequest> predicate) {
        return properties.getExclude().stream()
                .map(Conditions::<RawHttpRequest>requestTo)
                .map(Predicate::negate)
                .reduce(predicate, Predicate::and);
    }

    @Bean
    @ConditionalOnMissingBean(name = "requestCondition")
    public Predicate<RawHttpRequest> requestCondition() {
        return $ -> true;
    }

    @Bean
    @ConditionalOnMissingBean(RawRequestFilter.class)
    public RawRequestFilter rawRequestFilter() {
        return RawRequestFilters.defaultValue();
    }

    @Bean
    @ConditionalOnMissingBean(RawResponseFilter.class)
    public RawResponseFilter rawResponseFilter() {
        return RawResponseFilters.defaultValue();
    }

    @Bean
    @ConditionalOnMissingBean(QueryFilter.class)
    public QueryFilter queryFilter() {
        final List<String> parameters = properties.getObfuscate().getParameters();
        return parameters.isEmpty() ?
                QueryFilters.defaultValue() :
                parameters.stream()
                        .map(parameter -> QueryFilters.replaceQuery(parameter, "XXX"))
                        .collect(toList()).stream()
                        .reduce(QueryFilter::merge)
                        .orElseGet(QueryFilter::none);
    }

    @Bean
    @ConditionalOnMissingBean(HeaderFilter.class)
    public HeaderFilter headerFilter() {
        final List<String> headers = properties.getObfuscate().getHeaders();
        return headers.isEmpty() ?
                HeaderFilters.defaultValue() :
                headers.stream()
                        .map(header -> HeaderFilters.replaceHeaders(header::equalsIgnoreCase, "XXX"))
                        .collect(toList()).stream()
                        .reduce(HeaderFilter::merge)
                        .orElseGet(HeaderFilter::none);
    }

    @Bean
    @ConditionalOnMissingBean(BodyFilter.class)
    public BodyFilter bodyFilter() {
        return BodyFilters.defaultValue();
    }

    @Bean
    @ConditionalOnMissingBean(RequestFilter.class)
    public RequestFilter requestFilter() {
        return RequestFilter.none();
    }

    @Bean
    @ConditionalOnMissingBean(ResponseFilter.class)
    public ResponseFilter responseFilter() {
        return ResponseFilter.none();
    }

    @Bean
    @ConditionalOnMissingBean(HttpLogFormatter.class)
    @ConditionalOnProperty(name = "logbook.format.style", havingValue = "http")
    public HttpLogFormatter httpFormatter() {
        return new DefaultHttpLogFormatter();
    }

    @Bean
    @ConditionalOnBean(ObjectMapper.class)
    @ConditionalOnMissingBean(HttpLogFormatter.class)
    public HttpLogFormatter jsonFormatter(
            @SuppressWarnings("SpringJavaAutowiringInspection") final ObjectMapper mapper) {
        return new JsonHttpLogFormatter(mapper);
    }

    @Bean
    @ConditionalOnMissingBean(HttpLogWriter.class)
    public HttpLogWriter writer(final Logger httpLogger) {
        final Level level = properties.getWrite().getLevel();

        return level == null ?
                new DefaultHttpLogWriter(httpLogger) :
                new DefaultHttpLogWriter(httpLogger, level);
    }

    @Bean
    @ConditionalOnMissingBean(name = "httpLogger")
    public Logger httpLogger() {
        final String category = properties.getWrite().getCategory();
        return LoggerFactory.getLogger(Optional.ofNullable(category).orElseGet(Logbook.class::getName));
    }

}
