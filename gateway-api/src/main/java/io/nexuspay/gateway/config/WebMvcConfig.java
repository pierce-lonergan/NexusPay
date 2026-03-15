package io.nexuspay.gateway.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    private final ApiVersionInterceptor apiVersionInterceptor;

    public WebMvcConfig(ApiVersionInterceptor apiVersionInterceptor) {
        this.apiVersionInterceptor = apiVersionInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(apiVersionInterceptor)
                .addPathPatterns("/v1/**");
    }
}
