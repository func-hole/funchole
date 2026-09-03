package com.funchole.backend.controlplane.service;

import com.funchole.backend.controlplane.constant.DomainStatus;
import com.funchole.backend.controlplane.dto.DomainCreateRequest;
import com.funchole.backend.controlplane.entity.AppDomain;
import com.funchole.backend.controlplane.entity.AppUser;
import com.funchole.backend.controlplane.repository.AppDomainRepository;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

@Service
public class DomainService {
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

    private String generateVerificationCode() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
