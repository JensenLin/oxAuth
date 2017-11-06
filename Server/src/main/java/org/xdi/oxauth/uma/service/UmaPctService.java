package org.xdi.oxauth.uma.service;

import com.unboundid.ldap.sdk.Filter;
import com.unboundid.ldap.sdk.LDAPException;
import com.unboundid.util.StaticUtils;
import org.apache.commons.lang.StringUtils;
import org.gluu.site.ldap.persistence.BatchOperation;
import org.gluu.site.ldap.persistence.LdapEntryManager;
import org.slf4j.Logger;
import org.xdi.ldap.model.SearchScope;
import org.xdi.ldap.model.SimpleBranch;
import org.xdi.oxauth.model.config.StaticConfiguration;
import org.xdi.oxauth.model.configuration.AppConfiguration;
import org.xdi.oxauth.model.jwt.Jwt;
import org.xdi.oxauth.model.jwt.JwtClaims;
import org.xdi.oxauth.model.uma.persistence.UmaPermission;
import org.xdi.oxauth.service.CleanerTimer;
import org.xdi.oxauth.uma.authorization.UmaPCT;
import org.xdi.util.INumGenerator;

import javax.ejb.Stateless;
import javax.inject.Inject;
import javax.inject.Named;
import java.util.Date;
import java.util.List;
import java.util.UUID;

/**
 * @author yuriyz on 05/31/2017.
 */
@Stateless
@Named
public class UmaPctService {

    public static final int DEFAULT_PCT_LIFETIME = 3600;

    @Inject
    private Logger log;

    @Inject
    private LdapEntryManager ldapEntryManager;

    @Inject
    private StaticConfiguration staticConfiguration;

    @Inject
    private AppConfiguration appConfiguration;

    public UmaPCT updateClaims(UmaPCT pct, Jwt idToken, String clientId, List<UmaPermission> permissions) {
        try {
            String ticketPctCode = permissions.get(0).getAttributes().get("pct");
            UmaPCT ticketPct = StringUtils.isNotBlank(ticketPctCode) ? getByCode(ticketPctCode) : null;

            boolean hasPct = pct != null;

            if (!hasPct) {
                if (ticketPct != null) {
                    pct = ticketPct;
                } else {
                    pct = createPctAndPersist(clientId);
                }
            }

            // copy claims from pctTicket into normal pct
            JwtClaims pctClaims = pct.getClaims();
            if (ticketPct != null && hasPct) {
                JwtClaims ticketClaims = ticketPct.getClaims();
                for (String key : ticketClaims.keys()) {
                    pctClaims.setClaimObject(key, ticketClaims.getClaim(key), false);
                }
                pct = ticketPct;
            }

            if (idToken != null && idToken.getClaims() != null) {
                for (String key : idToken.getClaims().keys()) {
                    pctClaims.setClaimObject(key, idToken.getClaims().getClaim(key), false);
                }
            }

            pct.setClaims(pctClaims);
            log.trace("PCT code: " + pct.getCode() + ", claims: " + pct.getClaimValuesAsJson());

            return ldapEntryManager.merge(pct);
        } catch (Exception e) {
            log.error("Failed to update PCT claims. " + e.getMessage(), e);
        }

        return pct;
    }

    public UmaPCT getByCode(String pctCode) {
        try {
            final Filter filter = Filter.create(String.format("&(oxAuthTokenCode=%s)", pctCode));
            final List<UmaPCT> entries = ldapEntryManager.findEntries(branchBaseDn(), UmaPCT.class, filter);
            if (entries != null && !entries.isEmpty()) {
                return entries.get(0);
            } else {
                log.error("Failed to find PCT by code: " + pctCode);
            }
        } catch (Exception e) {
            log.error(e.getMessage(), e);
        }
        return null;
    }

    public UmaPCT createPct(String clientId) {
        String code = UUID.randomUUID().toString() + "_" + INumGenerator.generate(8);

        UmaPCT pct = new UmaPCT(pctLifetime());
        pct.setCode(code);
        pct.setDn(dn(pct.getCode()));
        pct.setClientId(clientId);
        return pct;
    }

    public UmaPCT createPctAndPersist(String clientId) {
        UmaPCT pct = createPct(clientId);
        persist(pct);
        return pct;
    }

    public int pctLifetime() {
        int lifeTime = appConfiguration.getUmaPctLifetime();
        if (lifeTime <= 0) {
            lifeTime = DEFAULT_PCT_LIFETIME;
        }
        return lifeTime;
    }

    public void persist(UmaPCT pct) {
        try {
            prepareBranch();

            pct.setDn(dn(pct.getCode()));
            ldapEntryManager.persist(pct);
        } catch (Exception e) {
            log.error("Failed to persist PCT, code: " + pct.getCode() + ". " + e.getMessage(), e);
        }
    }

    public void remove(UmaPCT umaPCT) {
        ldapEntryManager.remove(umaPCT);
    }

    public void remove(String pctCode) {
        remove(getByCode(pctCode));
    }

    public void remove(List<UmaPCT> pctList) {
        for (UmaPCT pct : pctList) {
            remove(pct);
        }
    }

    private void prepareBranch() {
        if (!ldapEntryManager.contains(SimpleBranch.class, branchBaseDn())) {
            addBranch();
        }
    }

    public void addBranch() {
        SimpleBranch branch = new SimpleBranch();
        branch.setOrganizationalUnitName("pct");
        branch.setDn(branchBaseDn());

        ldapEntryManager.persist(branch);
    }

    public String dn(String pctCode) {
        if (StringUtils.isBlank(pctCode)) {
            throw new IllegalArgumentException("PCT code is null or blank.");
        }
        return String.format("oxAuthTokenCode=%s,%s", pctCode, branchBaseDn());
    }

    public String branchBaseDn() {
        final String umaBaseDn = staticConfiguration.getBaseDn().getUmaBase(); // "ou=uma,o=@!1111,o=gluu"
        return String.format("ou=pct,%s", umaBaseDn);
    }

    public void cleanup(final Date now) {
        prepareBranch();
        BatchOperation<UmaPCT> batchService = new BatchOperation<UmaPCT>(ldapEntryManager) {
            @Override
            protected List<UmaPCT> getChunkOrNull(int chunkSize) {
                return ldapEntryManager.findEntries(branchBaseDn(), UmaPCT.class, getFilter(), SearchScope.SUB, null, this, 0, chunkSize, chunkSize);
            }

            @Override
            protected void performAction(List<UmaPCT> entries) {
                for (UmaPCT p : entries) {
                    try {
                        remove(p);
                    } catch (Exception e) {
                        log.error("Failed to remove entry", e);
                    }
                }
            }

            private Filter getFilter() {
                try {
                    return Filter.create(String.format("(oxAuthExpiration<=%s)", StaticUtils.encodeGeneralizedTime(now)));
                } catch (LDAPException e) {
                    log.trace(e.getMessage(), e);
                    return Filter.createPresenceFilter("oxAuthExpiration");
                }
            }
        };
        batchService.iterateAllByChunks(CleanerTimer.BATCH_SIZE);
    }

    public void merge(UmaPCT pct) {
        try {
            ldapEntryManager.merge(pct);
        } catch (Exception e) {
            log.error("Failed to merge PCT, code: " + pct.getCode() + ". " + e.getMessage(), e);
        }
    }
}
