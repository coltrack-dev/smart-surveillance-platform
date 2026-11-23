package com.coltrack.websocketservice.security;

import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;

/** Drops queued and future broker frames as soon as selected membership is revoked. */
public class TenantSocketOutboundInterceptor implements ChannelInterceptor {
    private final TenantStompSecurityInterceptor sessions;

    public TenantSocketOutboundInterceptor(TenantStompSecurityInterceptor sessions) {
        this.sessions = sessions;
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor headers = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (headers == null) headers = StompHeaderAccessor.wrap(message);
        if (headers != null && headers.getCommand() == org.springframework.messaging.simp.stomp.StompCommand.ERROR) {
            return message;
        }
        if (headers == null || headers.getSessionId() == null) return null;
        return sessions.isActive(headers.getSessionId()) ? message : null;
    }
}
