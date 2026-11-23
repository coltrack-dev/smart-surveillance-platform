package com.coltrack.websocketservice.security;

import com.coltrack.identity.model.Role;
import com.coltrack.identity.model.TenantMembership;
import com.coltrack.identity.security.TenantAuthorization;
import com.coltrack.identity.security.TenantPermissions;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import java.time.Instant;
import java.util.HashMap;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TenantStompSecurityInterceptorTest {
    private static final String ISSUER = "https://issuer.example/realms/streaming";
    private static final String SUBJECT = "user-123";

    @Test
    void authenticatedMultiTenantUserMayConnectPerTenantButCannotSubscribeAcrossTenant() {
        UUID tenantA = UUID.randomUUID(), tenantB = UUID.randomUUID(), userId = UUID.randomUUID();
        JwtDecoder decoder = decoder();
        TenantAuthorization authorization = authorization(userId, tenantA, tenantB, new AtomicBoolean(true));
        TenantStompSecurityInterceptor interceptor = new TenantStompSecurityInterceptor(decoder, authorization);
        MessageChannel channel = mock(MessageChannel.class);

        StompHeaderAccessor a = connect(interceptor, channel, tenantA, "socket-a");
        assertDoesNotThrow(() -> interceptor.preSend(subscribe(a, tenantA), channel));
        assertThrows(org.springframework.messaging.MessageDeliveryException.class,
                () -> interceptor.preSend(subscribe(a, tenantB), channel));
        assertThrows(org.springframework.messaging.MessageDeliveryException.class,
                () -> interceptor.preSend(subscribe(a, tenantA, "/topic/streams"), channel));

        StompHeaderAccessor b = connect(interceptor, channel, tenantB, "socket-b");
        assertNotEquals(a.getUser().getName(), b.getUser().getName());
        assertDoesNotThrow(() -> interceptor.preSend(subscribe(b, tenantB), channel));
    }

    @Test
    void activeSubscriptionStopsReceivingFramesAfterMembershipRevocation() {
        UUID tenant = UUID.randomUUID(), userId = UUID.randomUUID();
        AtomicBoolean active = new AtomicBoolean(true);
        TenantStompSecurityInterceptor inbound = new TenantStompSecurityInterceptor(decoder(),
                authorization(userId, tenant, null, active));
        StompHeaderAccessor connected = connect(inbound, mock(MessageChannel.class), tenant, "revoked-socket");
        TenantSocketOutboundInterceptor outbound = new TenantSocketOutboundInterceptor(inbound);
        assertTrue(inbound.isActive("revoked-socket"));
        active.set(false);

        Message<?> event = outboundMessage("revoked-socket");

        assertNull(outbound.preSend(event, mock(MessageChannel.class)));
        assertFalse(inbound.isActive("revoked-socket"));
        assertNotNull(connected.getUser());
    }

    @Test
    void connectRequiresJwtAndValidatedMembershipForSelectedTenant() {
        UUID tenant = UUID.randomUUID();
        JwtDecoder decoder = decoder();
        TenantAuthorization authorization = new TenantAuthorization((principal, selected) -> Optional.empty(),
                (principal, selected, permission) -> false);
        TenantStompSecurityInterceptor interceptor = new TenantStompSecurityInterceptor(decoder, authorization);
        StompHeaderAccessor headers = StompHeaderAccessor.create(StompCommand.CONNECT);
        headers.setSessionId("unauthorized");
        headers.setNativeHeader("X-Tenant-ID", tenant.toString());
        headers.setSessionAttributes(new HashMap<>());
        Message<?> request = MessageBuilder.withPayload(new byte[0]).setHeaders(headers).build();

        assertThrows(org.springframework.messaging.MessageDeliveryException.class,
                () -> interceptor.preSend(request, mock(MessageChannel.class)));
    }

    private static TenantAuthorization authorization(UUID userId, UUID firstTenant, UUID secondTenant,
                                                     AtomicBoolean active) {
        return new TenantAuthorization((principal, selected) -> {
            if (!principal.subject().equals(SUBJECT) || !active.get()) return Optional.empty();
            if (!selected.equals(firstTenant) && !selected.equals(secondTenant)) return Optional.empty();
            return Optional.of(new TenantMembership(selected, userId, "tenant", "Tenant", Role.VIEWER));
        }, (principal, selected, permission) -> TenantPermissions.allows(Role.VIEWER, permission));
    }

    private static JwtDecoder decoder() {
        JwtDecoder decoder = mock(JwtDecoder.class);
        Instant now = Instant.now();
        when(decoder.decode(anyString())).thenReturn(Jwt.withTokenValue("opaque-test")
                .header("alg", "RS256").issuer(ISSUER).subject(SUBJECT)
                .issuedAt(now).expiresAt(now.plusSeconds(120)).build());
        return decoder;
    }

    private static StompHeaderAccessor connect(TenantStompSecurityInterceptor interceptor,
                                               MessageChannel channel, UUID tenantId, String sessionId) {
        StompHeaderAccessor headers = StompHeaderAccessor.create(StompCommand.CONNECT);
        headers.setSessionId(sessionId);
        headers.setNativeHeader("Authorization", "Bearer jwt");
        headers.setNativeHeader("X-Tenant-ID", tenantId.toString());
        headers.setSessionAttributes(new HashMap<>());
        Message<?> message = MessageBuilder.withPayload(new byte[0]).setHeaders(headers).build();
        Message<?> authenticated = interceptor.preSend(message, channel);
        return StompHeaderAccessor.wrap(authenticated);
    }

    private static Message<?> subscribe(StompHeaderAccessor connected, UUID tenantId) {
        return subscribe(connected, tenantId, "/topic/tenants/" + tenantId + "/cameras/status");
    }

    private static Message<?> subscribe(StompHeaderAccessor connected, UUID tenantId, String destination) {
        StompHeaderAccessor headers = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
        headers.setSessionId(connected.getSessionId());
        headers.setUser(connected.getUser());
        headers.setSessionAttributes(connected.getSessionAttributes());
        headers.setDestination(destination.startsWith("/topic/")
                ? destination : "/topic/tenants/" + tenantId + destination);
        return MessageBuilder.withPayload(new byte[0]).setHeaders(headers).build();
    }

    private static Message<?> outboundMessage(String sessionId) {
        StompHeaderAccessor headers = StompHeaderAccessor.create(StompCommand.MESSAGE);
        headers.setSessionId(sessionId);
        return MessageBuilder.withPayload(new byte[0]).setHeaders(headers).build();
    }
}
