package com.coltrack.identityservice.service;

import com.coltrack.identity.model.TenantMembership;
import com.coltrack.identity.model.User;
import com.coltrack.identity.security.AuthenticatedPrincipal;
import com.coltrack.identity.security.MembershipResolver;
import com.coltrack.identityservice.repository.IdentityRepository;
import org.springframework.stereotype.Service;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class IdentityDirectory implements MembershipResolver {
    private final IdentityRepository repository;

    public IdentityDirectory(IdentityRepository repository) {
        this.repository = repository;
    }

    public User discover(AuthenticatedPrincipal principal) {
        return repository.discover(principal.issuer(), principal.subject(), principal.email(),
                principal.emailVerified(), principal.displayName());
    }

    public List<TenantMembership> memberships(AuthenticatedPrincipal principal) {
        User user = discover(principal);
        return repository.memberships(user.id());
    }

    @Override
    public Optional<TenantMembership> findMembership(AuthenticatedPrincipal principal, UUID tenantId) {
        return repository.findUser(principal.issuer(), principal.subject())
                .filter(User::enabled)
                .flatMap(user -> repository.membership(user.id(), tenantId));
    }
}
