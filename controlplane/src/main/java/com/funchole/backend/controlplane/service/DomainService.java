package com.funchole.backend.controlplane.service;

import com.funchole.backend.controlplane.constant.DomainStatus;
import com.funchole.backend.controlplane.dto.DomainCreateRequest;
import com.funchole.backend.controlplane.entity.AppDomain;
import com.funchole.backend.controlplane.entity.AppUser;
import com.funchole.backend.controlplane.repository.AppDomainRepository;
import com.funchole.backend.core.base.exception.ResourceNotFoundException;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.xbill.DNS.Lookup;
import org.xbill.DNS.Name;
import org.xbill.DNS.Record;
import org.xbill.DNS.SimpleResolver;
import org.xbill.DNS.TXTRecord;
import org.xbill.DNS.TextParseException;
import org.xbill.DNS.Type;

@Service
public class DomainService {
    private static final Logger logger = LoggerFactory.getLogger(DomainService.class);

    private final AppDomainRepository domainRepository;

    public DomainService(AppDomainRepository domainRepository) {
        this.domainRepository = domainRepository;
    }

    public AppDomain createDomain(AppUser appUser, DomainCreateRequest request) {
        AppDomain appDomain = AppDomain.create(
                appUser,
                request.domainName(),
                generateVerificationCode(),
                DomainStatus.PENDING
        );

        return domainRepository.save(appDomain);
    }

    public Page<AppDomain> listDomains(UUID appUserId, int page, int size) {
        Pageable pageable = PageRequest.of(
                Math.max(page - 1, 0),
                Math.max(size, 1),
                Sort.by(Sort.Direction.DESC, AppDomain::getCreatedAt)
        );
        return domainRepository.findAllByAppUserId(appUserId, pageable);
    }

    public AppDomain getDomainById(UUID appUserId, UUID domainId) {
        return domainRepository.findByIdAndAppUser_Id(domainId, appUserId)
                .orElseThrow(() -> new ResourceNotFoundException("Domain not found: " + domainId));
    }

    @Transactional
    public AppDomain initiateDomainVerification(UUID appUserId, UUID domainId) {
        AppDomain appDomain = getDomainById(appUserId, domainId);

        if (appDomain.getStatus() == DomainStatus.VERIFIED) {
            return appDomain;
        }

        String recordName = buildVerificationRecordName(appDomain);
        if (hasMatchingTxtRecord(recordName, appDomain.getVerificationCode())) {
            appDomain.markVerified();
            return domainRepository.save(appDomain);
        }

        return appDomain;
    }

    private String generateVerificationCode() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private String buildVerificationRecordName(AppDomain appDomain) {
        return "funchole-" + appDomain.getId() + "." + appDomain.getDomainName();
    }

    private boolean hasMatchingTxtRecord(String recordName, String expectedValue) {
        try {
            Lookup lookup = new Lookup(toAbsoluteName(recordName), Type.TXT);
            lookup.setResolver(buildResolver());
            lookup.setCache(null);

            Record[] records = lookup.run();
            if (records == null || records.length == 0) {
                logger.info("No TXT record found for '{}'", recordName);
                logger.info("TXT lookup result for '{}': {}", recordName, lookup.getErrorString());
                return false;
            }

            for (Record record : records) {
                if (!(record instanceof TXTRecord txtRecord)) {
                    continue;
                }

                String normalizedValue = normalizeTxtValue(txtRecord.getStrings());
                logger.info("TXT record for '{}': {}", recordName, normalizedValue);
                if (expectedValue.equals(normalizedValue)) {
                    return true;
                }
            }

            return false;
        } catch (TextParseException exception) {
            logger.warn("Invalid TXT lookup name '{}': {}", recordName, exception.getMessage());
            return false;
        } catch (Exception exception) {
            logger.warn("Failed to resolve TXT records for '{}': {}", recordName, exception.getMessage());
            return false;
        }
    }

    private Name toAbsoluteName(String recordName) throws TextParseException {
        return Name.fromString(recordName.endsWith(".") ? recordName : recordName + ".");
    }

    private SimpleResolver buildResolver() throws Exception {
        SimpleResolver resolver = new SimpleResolver();
        resolver.setTimeout(Duration.ofSeconds(3));
        return resolver;
    }

    private String normalizeTxtValue(List<String> values) {
        return values.stream()
                .map(String::trim)
                .reduce("", String::concat);
    }
}
