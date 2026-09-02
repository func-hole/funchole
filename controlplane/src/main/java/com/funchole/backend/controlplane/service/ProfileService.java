package com.funchole.backend.controlplane.service;

import java.util.UUID;

import org.springframework.data.crossstore.ChangeSetPersister.NotFoundException;
import org.springframework.stereotype.Service;

import com.funchole.backend.controlplane.entity.AppUser;
import com.funchole.backend.controlplane.repository.AppUserRepository;

@Service
public class ProfileService {
    private final AppUserRepository appUserRepository;
    
    public ProfileService(AppUserRepository appUserRepository) {
        this.appUserRepository = appUserRepository;
    }
    
    public AppUser loadUserById(UUID id) throws NotFoundException {
        return appUserRepository.findById(id)
                .orElseThrow(() -> new NotFoundException());
    }
}
