package com.coltrack.websocketservice.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;
import org.springframework.messaging.simp.config.ChannelRegistration;
import com.coltrack.websocketservice.security.TenantSocketOutboundInterceptor;
import com.coltrack.websocketservice.security.TenantStompSecurityInterceptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.util.StringUtils;

@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig
        implements WebSocketMessageBrokerConfigurer {

    @Value("${app.security.mode:legacy}")
    private String securityMode;

    @Value("${app.websocket.allowed-origins:}")
    private String allowedOrigins;

    @Autowired(required = false)
    private TenantStompSecurityInterceptor tenantInboundInterceptor;

    @Autowired(required = false)
    private TenantSocketOutboundInterceptor tenantOutboundInterceptor;

    @Override
    public void configureMessageBroker(
            MessageBrokerRegistry registry
    ) {

        registry.enableSimpleBroker(
                "/topic"
        );

        registry.setApplicationDestinationPrefixes(
                "/app"
        );
    }

    @Override
    public void registerStompEndpoints(
            StompEndpointRegistry registry
    ) {
        var endpoint = registry.addEndpoint("/ws");
        if ("oidc".equalsIgnoreCase(securityMode)) {
            String[] origins = StringUtils.commaDelimitedListToStringArray(allowedOrigins);
            if (origins.length == 0 || allowedOrigins.contains("*")) {
                throw new IllegalStateException("Streaming WebSocket requires explicit non-wildcard allowed origins");
            }
            endpoint.setAllowedOriginPatterns(origins);
        } else {
            endpoint.setAllowedOriginPatterns("*");
        }
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        if (tenantInboundInterceptor != null) registration.interceptors(tenantInboundInterceptor);
    }

    @Override
    public void configureClientOutboundChannel(ChannelRegistration registration) {
        if (tenantOutboundInterceptor != null) registration.interceptors(tenantOutboundInterceptor);
    }
}
