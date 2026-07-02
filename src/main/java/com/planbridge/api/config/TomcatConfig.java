package com.planbridge.api.config;

import org.apache.coyote.http11.AbstractHttp11Protocol;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.filter.CharacterEncodingFilter;

@Configuration
public class TomcatConfig {

    @Bean
    public WebServerFactoryCustomizer<TomcatServletWebServerFactory> tomcatCustomizer() {
        return factory -> factory.addConnectorCustomizers(connector -> {
            connector.setURIEncoding("UTF-8");
            if (connector.getProtocolHandler() instanceof AbstractHttp11Protocol<?> protocol) {
                // 한글 등 멀티바이트 문자가 URL query string에 raw로 포함될 때 거부하지 않도록
                // 일반적인 특수문자 허용 (한글은 percent-encoding 권장)
                protocol.setRelaxedQueryChars("|{}[]^`<>\\\"");
                protocol.setRelaxedPathChars("|{}[]^`<>\\\"");
            }
        });
    }

    @Bean
    public CharacterEncodingFilter characterEncodingFilter() {
        CharacterEncodingFilter filter = new CharacterEncodingFilter();
        filter.setEncoding("UTF-8");
        filter.setForceEncoding(true);
        return filter;
    }
}
