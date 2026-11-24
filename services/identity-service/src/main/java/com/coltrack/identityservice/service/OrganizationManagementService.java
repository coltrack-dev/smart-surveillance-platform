package com.coltrack.identityservice.service;

import com.coltrack.identity.model.Role;
import com.coltrack.identity.model.User;
import com.coltrack.identity.security.AuthenticatedPrincipal;
import com.coltrack.identityservice.model.OrganizationDtos.*;
import com.coltrack.identityservice.repository.IdentityRepository;
import com.coltrack.identityservice.repository.QuotaPlanRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
public class OrganizationManagementService {
    private static final Pattern SLUG = Pattern.compile("[a-z0-9]+(?:-[a-z0-9]+)*");
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Duration INVITATION_TTL = Duration.ofDays(7);
    private final JdbcTemplate jdbc;
    private final IdentityRepository identities;
    private final boolean devLinkEnabled;
    private final String publicBaseUrl;
    private final String defaultQuotaPlan;

    private QuotaPlanRepository quotaPlans;

    @Autowired
    public OrganizationManagementService(JdbcTemplate jdbc, IdentityRepository identities,
                                         org.springframework.core.env.Environment environment,
                                         QuotaPlanRepository quotaPlans) {
        this(jdbc, identities, environment);
        this.quotaPlans = quotaPlans;
    }

    public OrganizationManagementService(JdbcTemplate jdbc, IdentityRepository identities,
                                         org.springframework.core.env.Environment environment) {
        this.jdbc = jdbc;
        this.identities = identities;
        this.devLinkEnabled = environment.getProperty("identity.invitations.development-link-enabled", Boolean.class, false);
        this.publicBaseUrl = environment.getProperty("identity.invitations.public-base-url", "http://localhost:5173");
        this.defaultQuotaPlan = environment.getProperty("identity.quotas.default-plan", "FREE")
                .trim().toUpperCase(Locale.ROOT);
        if (devLinkEnabled && !isLoopbackDevelopmentUrl(publicBaseUrl)) {
            throw new IllegalStateException("Development invitation links must use a loopback HTTP origin");
        }
    }

    @Transactional
    public Organization create(AuthenticatedPrincipal principal, CreateOrganization request) {
        String name = required(request == null ? null : request.name(), "name", 120);
        String slug = required(request.slug(), "slug", 63).toLowerCase(Locale.ROOT);
        if (!SLUG.matcher(slug).matches()) throw invalid("slug is invalid");
        User user = user(principal);
        UUID tenantId = UUID.randomUUID();
        try {
            jdbc.update("INSERT INTO identity.tenants(id,slug,display_name) VALUES (?,?,?)", tenantId, slug, name);
            if (quotaPlans != null) quotaPlans.assignDefault(tenantId, defaultQuotaPlan);
            jdbc.update("INSERT INTO identity.tenant_memberships(tenant_id,user_id,role_code,status) VALUES (?,?, 'OWNER','ACTIVE')",
                    tenantId, user.id());
        } catch (DuplicateKeyException ex) {
            throw conflict("organization slug already exists");
        }
        return organization(tenantId);
    }

    public List<Organization> list(AuthenticatedPrincipal principal) {
        User user = user(principal);
        return jdbc.query("""
                SELECT t.id,t.slug,t.display_name,t.created_at FROM identity.tenants t
                JOIN identity.tenant_memberships m ON m.tenant_id=t.id
                WHERE m.user_id=? AND m.status='ACTIVE' AND t.status='ACTIVE'
                ORDER BY t.display_name,t.id
                """, (rs, n) -> mapOrganization(rs), user.id());
    }

    public Organization get(AuthenticatedPrincipal principal, UUID tenantId) {
        activeMember(principal, tenantId);
        return organization(tenantId);
    }

    @Transactional
    public Organization update(AuthenticatedPrincipal principal, UUID tenantId, UpdateOrganization request) {
        lockTenant(tenantId);
        if (actorRole(principal, tenantId) != Role.OWNER) throw forbidden();
        String name = required(request == null ? null : request.name(), "name", 120);
        jdbc.update("UPDATE identity.tenants SET display_name=?,updated_at=now() WHERE id=?", name, tenantId);
        return organization(tenantId);
    }

    public List<Member> members(AuthenticatedPrincipal principal, UUID tenantId) {
        activeMember(principal, tenantId);
        return jdbc.query("""
                SELECT u.id,u.email,u.display_name,m.role_code,m.created_at
                FROM identity.tenant_memberships m JOIN identity.users u ON u.id=m.user_id
                WHERE m.tenant_id=? AND m.status='ACTIVE' ORDER BY m.created_at,u.id
                """, (rs, n) -> new Member(rs.getObject("id", UUID.class), rs.getString("email"),
                rs.getString("display_name"), rs.getString("role_code"), instant(rs, "created_at")), tenantId);
    }

    @Transactional
    public Member changeRole(AuthenticatedPrincipal actor, UUID tenantId, UUID targetId, ChangeRole request) {
        lockTenant(tenantId);
        Role actorRole = actorRole(actor, tenantId);
        Role targetRole = currentRole(tenantId, targetId);
        Role newRole = parseRole(request == null ? null : request.role());
        requireCanManage(actorRole, targetRole, newRole);
        if (targetRole == Role.OWNER && newRole != Role.OWNER) ensureAnotherOwner(tenantId, targetId);
        jdbc.update("UPDATE identity.tenant_memberships SET role_code=?,updated_at=now() WHERE tenant_id=? AND user_id=? AND status='ACTIVE'",
                newRole.name(), tenantId, targetId);
        return member(tenantId, targetId);
    }

    @Transactional
    public void remove(AuthenticatedPrincipal actor, UUID tenantId, UUID targetId) {
        lockTenant(tenantId);
        Role actorRole = actorRole(actor, tenantId);
        Role targetRole = currentRole(tenantId, targetId);
        requireCanManage(actorRole, targetRole, null);
        if (targetRole == Role.OWNER) ensureAnotherOwner(tenantId, targetId);
        jdbc.update("UPDATE identity.tenant_memberships SET status='REVOKED',updated_at=now() WHERE tenant_id=? AND user_id=? AND status='ACTIVE'", tenantId, targetId);
    }

    @Transactional
    public void leave(AuthenticatedPrincipal actor, UUID tenantId) {
        lockTenant(tenantId);
        User user = user(actor);
        Role role = currentRole(tenantId, user.id());
        if (role == Role.OWNER) ensureAnotherOwner(tenantId, user.id());
        int changed = jdbc.update("UPDATE identity.tenant_memberships SET status='REVOKED',updated_at=now() WHERE tenant_id=? AND user_id=? AND status='ACTIVE'", tenantId, user.id());
        if (changed == 0) throw inaccessible();
    }

    @Transactional
    public Invitation invite(AuthenticatedPrincipal actor, UUID tenantId, CreateInvitation request) {
        lockTenant(tenantId);
        Role actorRole = actorRole(actor, tenantId);
        String email = normalizedEmail(request == null ? null : request.email());
        Role role = parseRole(request == null ? null : request.role());
        requireCanInvite(actorRole, role);
        byte[] tokenBytes = new byte[32]; RANDOM.nextBytes(tokenBytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(tokenBytes);
        byte[] hash = sha256(tokenBytes);
        UUID invitationId = UUID.randomUUID();
        User creator = user(actor);
        Instant expiry = Instant.now().plus(INVITATION_TTL);
        try {
            jdbc.update("""
                    INSERT INTO identity.tenant_invitations(id,tenant_id,invited_email_normalized,role_code,token_hash,status,expires_at,created_by_user_id)
                    VALUES (?,?,?,?,?,'PENDING',?,?)
                    """, invitationId, tenantId, email, role.name(), hash, Timestamp.from(expiry), creator.id());
        } catch (DuplicateKeyException ex) { throw conflict("pending invitation already exists"); }
        String url = devLinkEnabled ? publicBaseUrl.replaceAll("/$", "") + "/invite#token=" + token : null;
        return new Invitation(invitationId, tenantId, email, role.name(), "PENDING", expiry, Instant.now(), url);
    }

    @Transactional
    public List<Invitation> invitations(AuthenticatedPrincipal actor, UUID tenantId) {
        Role role = actorRole(actor, tenantId);
        if (role != Role.OWNER && role != Role.ADMIN) throw forbidden();
        jdbc.update("UPDATE identity.tenant_invitations SET status='EXPIRED',updated_at=now() WHERE tenant_id=? AND status='PENDING' AND expires_at<=now()", tenantId);
        return jdbc.query("""
                SELECT id,tenant_id,invited_email_normalized,role_code,status,expires_at,created_at
                FROM identity.tenant_invitations WHERE tenant_id=? AND status='PENDING' AND expires_at>now()
                ORDER BY created_at DESC
                """, (rs, n) -> mapInvitation(rs, null), tenantId);
    }

    @Transactional
    public void revokeInvitation(AuthenticatedPrincipal actor, UUID tenantId, UUID invitationId) {
        lockTenant(tenantId);
        Role actorRole = actorRole(actor, tenantId);
        if (actorRole != Role.OWNER && actorRole != Role.ADMIN) throw forbidden();
        String roleFilter = actorRole == Role.ADMIN ? " AND role_code IN ('OPERATOR','VIEWER')" : "";
        int count = jdbc.update("UPDATE identity.tenant_invitations SET status='REVOKED',updated_at=now() WHERE id=? AND tenant_id=? AND status='PENDING'" + roleFilter, invitationId, tenantId);
        if (count == 0) throw inaccessible();
    }

    @Transactional(noRollbackFor = ApiProblem.class)
    public AcceptedMembership accept(AuthenticatedPrincipal principal, AcceptInvitation request) {
        if (principal == null || !principal.emailVerified() || principal.email() == null) throw forbidden();
        String token = required(request == null ? null : request.token(), "token", 128);
        byte[] raw;
        try { raw = Base64.getUrlDecoder().decode(token); } catch (IllegalArgumentException ex) { throw inaccessible(); }
        if (raw.length != 32) throw inaccessible();
        byte[] hash = sha256(raw);
        List<UUID> tenantIds = jdbc.query("SELECT tenant_id FROM identity.tenant_invitations WHERE token_hash=?",
                (rs,n) -> rs.getObject(1, UUID.class), hash);
        if (tenantIds.isEmpty()) throw inaccessible();
        UUID invitationTenantId = tenantIds.getFirst();
        lockTenant(invitationTenantId);
        List<InvitationRow> rows = jdbc.query("""
                SELECT id,tenant_id,invited_email_normalized,role_code,status,expires_at
                FROM identity.tenant_invitations WHERE token_hash=? FOR UPDATE
                """, (rs,n) -> new InvitationRow(rs.getObject("id", UUID.class), rs.getObject("tenant_id", UUID.class),
                rs.getString("invited_email_normalized"), rs.getString("role_code"), rs.getString("status"), instant(rs,"expires_at")), hash);
        if (rows.isEmpty()) throw inaccessible();
        InvitationRow row = rows.getFirst();
        if (!row.tenantId().equals(invitationTenantId)) throw inaccessible();
        if (!"PENDING".equals(row.status()) || !Instant.now().isBefore(row.expiresAt())) {
            if ("PENDING".equals(row.status())) jdbc.update("UPDATE identity.tenant_invitations SET status='EXPIRED',updated_at=now() WHERE id=?", row.id());
            throw gone("invitation is expired or already consumed");
        }
        String email = normalizedEmail(principal.email());
        if (!email.equals(row.email())) throw forbidden();
        User user = user(principal);
        List<String> existing = jdbc.query("SELECT status FROM identity.tenant_memberships WHERE tenant_id=? AND user_id=? FOR UPDATE",
                (rs,n) -> rs.getString(1), row.tenantId(), user.id());
        if (!existing.isEmpty() && "ACTIVE".equals(existing.getFirst())) throw conflict("user is already an active member");
        if (existing.isEmpty()) {
            jdbc.update("INSERT INTO identity.tenant_memberships(tenant_id,user_id,role_code,status) VALUES (?,?,?,'ACTIVE')",
                    row.tenantId(), user.id(), row.role());
        } else {
            jdbc.update("UPDATE identity.tenant_memberships SET role_code=?,status='ACTIVE',updated_at=now() WHERE tenant_id=? AND user_id=?",
                    row.role(), row.tenantId(), user.id());
        }
        jdbc.update("UPDATE identity.tenant_invitations SET status='ACCEPTED',accepted_by_user_id=?,accepted_at=now(),updated_at=now() WHERE id=? AND status='PENDING'",
                user.id(), row.id());
        return new AcceptedMembership(row.tenantId(), user.id(), row.role());
    }

    private User user(AuthenticatedPrincipal principal) {
        if (principal == null || principal.issuer() == null || principal.subject() == null) throw forbidden();
        return identities.findUser(principal.issuer(), principal.subject()).map(existing -> {
            if (!existing.enabled()) throw forbidden();
            return existing;
        }).orElseGet(() -> {
            User discovered = identities.discover(principal.issuer(), principal.subject(), principal.email(),
                    principal.emailVerified(), principal.displayName());
            if (!discovered.enabled()) throw forbidden();
            return discovered;
        });
    }
    private Organization organization(UUID id) {
        return jdbc.query("SELECT id,slug,display_name,created_at FROM identity.tenants WHERE id=? AND status='ACTIVE'",
                (rs,n) -> mapOrganization(rs), id).stream().findFirst().orElseThrow(OrganizationManagementService::inaccessible);
    }
    private Organization mapOrganization(ResultSet rs) throws SQLException {
        return new Organization(rs.getObject("id", UUID.class),rs.getString("slug"),rs.getString("display_name"),instant(rs,"created_at"));
    }
    private Member member(UUID tenant, UUID user) {
        return jdbc.query("SELECT u.id,u.email,u.display_name,m.role_code,m.created_at FROM identity.tenant_memberships m JOIN identity.users u ON u.id=m.user_id WHERE m.tenant_id=? AND m.user_id=? AND m.status='ACTIVE'",
                (rs,n) -> new Member(rs.getObject("id",UUID.class),rs.getString("email"),rs.getString("display_name"),rs.getString("role_code"),instant(rs,"created_at")),tenant,user)
                .stream().findFirst().orElseThrow(OrganizationManagementService::inaccessible);
    }
    private Invitation mapInvitation(ResultSet rs, String url) throws SQLException {
        return new Invitation(rs.getObject("id",UUID.class),rs.getObject("tenant_id",UUID.class),rs.getString("invited_email_normalized"),rs.getString("role_code"),rs.getString("status"),instant(rs,"expires_at"),instant(rs,"created_at"),url);
    }
    private void activeMember(AuthenticatedPrincipal principal, UUID tenant) { actorRole(principal, tenant); }
    private Role actorRole(AuthenticatedPrincipal principal, UUID tenant) {
        lockTenantIfTransactional(tenant);
        User actor = user(principal);
        List<String> roles = jdbc.query("SELECT m.role_code FROM identity.tenant_memberships m JOIN identity.tenants t ON t.id=m.tenant_id WHERE m.tenant_id=? AND m.user_id=? AND m.status='ACTIVE' AND t.status='ACTIVE'",
                (rs,n) -> rs.getString(1), tenant, actor.id());
        if (roles.isEmpty()) throw inaccessible();
        return Role.valueOf(roles.getFirst());
    }
    private Role currentRole(UUID tenant, UUID target) {
        List<String> roles = jdbc.query("SELECT role_code FROM identity.tenant_memberships WHERE tenant_id=? AND user_id=? AND status='ACTIVE' FOR UPDATE",
                (rs,n) -> rs.getString(1), tenant,target);
        if (roles.isEmpty()) throw inaccessible(); return Role.valueOf(roles.getFirst());
    }
    private void lockTenant(UUID tenant) {
        if (jdbc.query("SELECT id FROM identity.tenants WHERE id=? AND status='ACTIVE' FOR UPDATE", (rs,n)->rs.getObject(1),tenant).isEmpty()) throw inaccessible();
    }
    private void lockTenantIfTransactional(UUID tenant) {
        if (org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()) lockTenant(tenant);
    }
    private void ensureAnotherOwner(UUID tenant, UUID excluded) {
        Integer count = jdbc.queryForObject("SELECT count(*) FROM identity.tenant_memberships WHERE tenant_id=? AND status='ACTIVE' AND role_code='OWNER' AND user_id<>?", Integer.class,tenant,excluded);
        if (count == null || count == 0) throw conflict("the last active OWNER cannot be removed or demoted");
    }
    static void requireCanManage(Role actor, Role target, Role next) {
        if (actor == Role.OWNER) return;
        if (actor == Role.ADMIN && (target == Role.OPERATOR || target == Role.VIEWER)
                && (next == null || next == Role.OPERATOR || next == Role.VIEWER)) return;
        throw forbidden();
    }
    static void requireCanInvite(Role actor, Role invited) {
        if (actor == Role.OWNER || actor == Role.ADMIN && invited != Role.OWNER && invited != Role.ADMIN) return;
        throw forbidden();
    }
    private static Role parseRole(String value) {
        try {
            return Role.valueOf(required(value,"role",16).toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) { throw invalid("role is invalid"); }
    }
    private static String normalizedEmail(String value) {
        String email = required(value,"email",320).toLowerCase(Locale.ROOT);
        if (!email.matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+")) throw invalid("email is invalid");
        return email;
    }
    private static String required(String value,String field,int max) {
        if (value == null || value.isBlank() || value.trim().length()>max) throw invalid(field+" is required and must be at most "+max+" characters");
        return value.trim();
    }
    private static byte[] sha256(byte[] bytes) {
        try { return MessageDigest.getInstance("SHA-256").digest(bytes); } catch (Exception ex) { throw new IllegalStateException(ex); }
    }
    private static boolean isLoopbackDevelopmentUrl(String value) { return value.matches("http://(localhost|127\\.0\\.0\\.1)(:[0-9]{1,5})?"); }
    private static Instant instant(ResultSet rs,String field) throws SQLException { return rs.getTimestamp(field).toInstant(); }
    private static RuntimeException invalid(String message) { return new ApiProblem(400,message); }
    private static RuntimeException forbidden() { return new ApiProblem(403,"insufficient permission"); }
    private static RuntimeException inaccessible() { return new ApiProblem(404,"organization or resource not found"); }
    private static RuntimeException conflict(String message) { return new ApiProblem(409,message); }
    private static RuntimeException gone(String message) { return new ApiProblem(410,message); }
    private record InvitationRow(UUID id,UUID tenantId,String email,String role,String status,Instant expiresAt) {}
}
