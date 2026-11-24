package com.coltrack.identityservice.service;

import com.coltrack.identity.model.User;
import com.coltrack.identity.security.AuthenticatedPrincipal;
import com.coltrack.identityservice.model.QuotaDtos.OrganizationQuotas;
import com.coltrack.identityservice.repository.IdentityRepository;
import com.coltrack.identityservice.repository.QuotaPlanRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class OrganizationQuotaService {
    private final IdentityRepository identities;
    private final QuotaPlanRepository quotas;
    private final String defaultPlanCode;

    public OrganizationQuotaService(IdentityRepository identities, QuotaPlanRepository quotas,
                                    @Value("${identity.quotas.default-plan:FREE}") String defaultPlanCode) {
        this.identities = identities;
        this.quotas = quotas;
        this.defaultPlanCode = defaultPlanCode;
    }

    @Transactional(readOnly = true)
    public OrganizationQuotas get(AuthenticatedPrincipal principal, UUID organizationId) {
        User user = authenticatedUser(principal);
        if (identities.membership(user.id(), organizationId).isEmpty()) {
            throw new ApiProblem(404, "organization or resource not found");
        }
        return quotas.findEffective(organizationId, defaultPlanCode)
                .orElseThrow(() -> new ApiProblem(503, "quota configuration unavailable"));
    }

    private User authenticatedUser(AuthenticatedPrincipal principal) {
        if (principal == null || principal.issuer() == null || principal.subject() == null) {
            throw new ApiProblem(401, "authentication required");
        }
        User user = identities.findUser(principal.issuer(), principal.subject())
                .orElseThrow(() -> new ApiProblem(404, "organization or resource not found"));
        if (!user.enabled()) throw new ApiProblem(403, "account disabled");
        return user;
    }
}
