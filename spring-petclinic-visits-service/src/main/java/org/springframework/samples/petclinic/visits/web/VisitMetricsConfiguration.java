package org.springframework.samples.petclinic.visits.web;

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
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.context.request.ServletWebRequest;

@Configuration(proxyBeanMethods = false)
class VisitMetricsConfiguration implements WebMvcConfigurer {

    private static final String TIMER_NAME = VisitMetricsConfiguration.class.getName() + ".timer";
    private static final String TIMER_URI = VisitMetricsConfiguration.class.getName() + ".uri";

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new HandlerInterceptor() {
            @Override
            public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
                if (handler instanceof HandlerMethod) {
                    HandlerMethod method = (HandlerMethod) handler;
                    Timed timed = AnnotatedElementUtils.findMergedAnnotation(method.getMethod(), Timed.class);
                    if (timed == null) {
                        timed = AnnotatedElementUtils.findMergedAnnotation(method.getBeanType(), Timed.class);
                    }
                    if (timed != null && !timed.value().isEmpty()) {
                        String uri = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE).toString();
                        request.setAttribute(TIMER_NAME, timed.value());
                        request.setAttribute(TIMER_URI,
                            uri.length() > 1 && uri.endsWith("/") ? uri.substring(0, uri.length() - 1) : uri);
                    }
                }
                return true;
            }
        });
    }

    @Bean
    OncePerRequestFilter visitMetricsFilter(ObjectProvider<MeterRegistry> registries, ErrorAttributes errors) {
        return new OncePerRequestFilter() {
            @Override
            protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                            FilterChain chain) throws ServletException, IOException {
                MeterRegistry registry = registries.getIfAvailable();
                if (registry == null) {
                    chain.doFilter(request, response);
                    return;
                }
                Timer.Sample sample = Timer.start(registry);
                Throwable failure = null;
                try {
                    chain.doFilter(request, response);
                } catch (ServletException | IOException | RuntimeException | Error ex) {
                    failure = ex;
                    throw ex;
                } finally {
                    String name = (String) request.getAttribute(TIMER_NAME);
                    if (name != null) {
                        Throwable exception = errors.getError(new ServletWebRequest(request));
                        if (exception == null) {
                            exception = failure;
                        }
                        if (exception instanceof ServletException && exception.getCause() != null) {
                            exception = exception.getCause();
                        }
                        int status = failure == null ? response.getStatus() : 500;
                        // Keep the Boot 2 HTTP timer contract, including failures before handler invocation.
                        sample.stop(Timer.builder(name)
                            .tag("exception", exception == null ? "None" : exception.getClass().getSimpleName())
                            .tag("method", request.getMethod())
                            .tag("outcome", outcome(status))
                            .tag("status", Integer.toString(status))
                            .tag("uri", (String) request.getAttribute(TIMER_URI))
                            .register(registry));
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
