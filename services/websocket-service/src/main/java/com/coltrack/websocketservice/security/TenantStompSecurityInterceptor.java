package com.coltrack.websocketservice.security;

import com.coltrack.identity.security.TenantAuthorization;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Authenticates STOMP CONNECT and limits each socket to one currently authorized tenant. */
public class TenantStompSecurityInterceptor implements ChannelInterceptor {
    static final String CONTEXT_ATTRIBUTE = TenantStompSecurityInterceptor.class.getName() + ".context";
    private static final Pattern TENANT_DESTINATION = Pattern.compile(
            "^/topic/tenants/([0-9a-fA-F-]{36})/(cameras/status|streams)$");

    private final JwtDecoder decoder;
    private final TenantAuthorization authorization;
    private final Map<String, TenantSocketPrincipal> sessions = new ConcurrentHashMap<>();

    public TenantStompSecurityInterceptor(JwtDecoder decoder, TenantAuthorization authorization) {
        this.decoder = decoder;
        this.authorization = authorization;
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor headers = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (headers == null) headers = StompHeaderAccessor.wrap(message);
        if (headers.getCommand() == null) return message;

        Message<?> authorized = switch (headers.getCommand()) {
            case CONNECT, STOMP -> authenticate(headers, message);
            case SUBSCRIBE -> authorizeSubscription(headers, message);
            case UNSUBSCRIBE -> message;
            case DISCONNECT -> disconnect(headers, message);
            default -> throw denied(); // clients may subscribe, but cannot publish or use broker control frames
        };
        // Some inbound STOMP messages arrive without their mutable accessor attached.
        // Rebuild so the authenticated Principal and session context reach the broker.
        return authorized == message && headers == MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class)
                ? message : org.springframework.messaging.support.MessageBuilder.createMessage(
                        message.getPayload(), headers.getMessageHeaders());
    }

    private Message<?> authenticate(StompHeaderAccessor headers, Message<?> message) {
        try {
            String authorizationHeader = headers.getFirstNativeHeader("Authorization");
            if (authorizationHeader == null || !authorizationHeader.startsWith("Bearer ")) throw denied();
            String selectedTenantHeader = headers.getFirstNativeHeader("X-Tenant-ID");
            UUID selectedTenant = UUID.fromString(selectedTenantHeader);
            Jwt jwt = decoder.decode(authorizationHeader.substring("Bearer ".length()));
            TenantSocketPrincipal principal = new TenantSocketPrincipal(
                    jwt.getIssuer().toString(), jwt.getSubject(), selectedTenant);
            authorization.require(principal.identity(), selectedTenant, "camera.read");
            headers.setUser(principal);
            Map<String, Object> attributes = headers.getSessionAttributes();
            if (attributes == null || headers.getSessionId() == null) throw denied();
            attributes.put(CONTEXT_ATTRIBUTE, principal);
            sessions.put(headers.getSessionId(), principal);
            return message;
        } catch (RuntimeException invalidOrUnauthorized) {
            throw denied();
        }
    }

    private Message<?> authorizeSubscription(StompHeaderAccessor headers, Message<?> message) {
        TenantSocketPrincipal principal = context(headers);
        Matcher destination = TENANT_DESTINATION.matcher(headers.getDestination() == null ? "" : headers.getDestination());
        if (!destination.matches() || !principal.tenantId().equals(UUID.fromString(destination.group(1)))) throw denied();
        requireActive(principal);
        return message;
    }

    private Message<?> disconnect(StompHeaderAccessor headers, Message<?> message) {
        if (headers.getSessionId() != null) sessions.remove(headers.getSessionId());
        return message;
    }

    private TenantSocketPrincipal context(StompHeaderAccessor headers) {
        if (headers.getUser() instanceof TenantSocketPrincipal principal) return principal;
        TenantSocketPrincipal sessionPrincipal = headers.getSessionId() == null ? null : sessions.get(headers.getSessionId());
        if (sessionPrincipal != null) return sessionPrincipal;
        Object stored = headers.getSessionAttributes() == null ? null
                : headers.getSessionAttributes().get(CONTEXT_ATTRIBUTE);
        if (stored instanceof TenantSocketPrincipal principal) return principal;
        throw denied();
    }

    boolean isActive(String sessionId) {
        TenantSocketPrincipal principal = sessions.get(sessionId);
        if (principal == null) return false;
        try {
            requireActive(principal);
            return true;
        } catch (RuntimeException revoked) {
            sessions.remove(sessionId, principal);
            return false;
        }
    }

    private void requireActive(TenantSocketPrincipal principal) {
        authorization.require(principal.identity(), principal.tenantId(), "camera.read");
    }

    private static org.springframework.messaging.MessageDeliveryException denied() {
        return new org.springframework.messaging.MessageDeliveryException("WebSocket authentication or tenant authorization failed");
    }
}
