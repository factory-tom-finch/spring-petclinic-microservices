package org.springframework.samples.petclinic.customers.web;

import java.io.IOException;

import io.micrometer.core.annotation.Timed;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.web.servlet.error.ErrorAttributes;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.PathMatchConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration(proxyBeanMethods = false)
class CustomersWebConfiguration implements WebMvcConfigurer {

    private static final String TIMER_NAME = CustomersWebConfiguration.class.getName() + ".timer";

    private static final String TIMER_URI = CustomersWebConfiguration.class.getName() + ".uri";

    @Override
    @SuppressWarnings("deprecation")
    public void configurePathMatch(PathMatchConfigurer configurer) {
        // Keep the pre-Spring 6 optional slash behavior, including POST and PUT.
        configurer.setUseTrailingSlashMatch(true);
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new HandlerInterceptor() {
            @Override
            public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
                if (handler instanceof HandlerMethod) {
                    HandlerMethod method = (HandlerMethod) handler;
                    Timed timed = method.getMethodAnnotation(Timed.class);
                    if (timed == null) {
                        timed = AnnotatedElementUtils.findMergedAnnotation(method.getBeanType(), Timed.class);
                    }
                    if (timed != null) {
                        request.setAttribute(TIMER_NAME, timed.value());
                        request.setAttribute(TIMER_URI, request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE));
                    }
                }
                return true;
            }
        });
    }

    @Bean
    OncePerRequestFilter customHttpTimers(ObjectProvider<MeterRegistry> registries, ErrorAttributes errors) {
        // Boot 3 no longer applies MVC @Timed annotations to complete HTTP requests.
        return new OncePerRequestFilter() {
            @Override
            protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                throws ServletException, IOException {
                MeterRegistry registry = registries.getIfAvailable();
                if (registry == null) {
                    chain.doFilter(request, response);
                    return;
                }
                Timer.Sample sample = Timer.start(registry);
                Throwable escaped = null;
                try {
                    chain.doFilter(request, response);
                } catch (ServletException | IOException | RuntimeException | Error exception) {
                    escaped = exception;
                    throw exception;
                } finally {
                    String name = (String) request.getAttribute(TIMER_NAME);
                    if (name != null) {
                        Throwable error = errors.getError(new ServletWebRequest(request));
                        if (error == null) {
                            error = escaped;
                        }
                        if (error instanceof ServletException && error.getCause() != null) {
                            error = error.getCause();
                        }
                        int status = escaped == null ? response.getStatus() : 500;
                        sample.stop(Timer.builder(name).tags(
                            "exception", error == null ? "None" : error.getClass().getSimpleName(),
                            "method", request.getMethod(),
                            "outcome", outcome(status),
                            "status", Integer.toString(status),
                            "uri", (String) request.getAttribute(TIMER_URI)
                        ).register(registry));
                    }
                }
            }
        };
    }

    private static String outcome(int status) {
        switch (status / 100) {
            case 1:
                return "INFORMATIONAL";
            case 2:
                return "SUCCESS";
            case 3:
                return "REDIRECTION";
            case 4:
                return "CLIENT_ERROR";
            case 5:
                return "SERVER_ERROR";
            default:
                return "UNKNOWN";
        }
    }
}
