package com.funchole.backend.controlplane.service;

import java.util.UUID;

import org.springframework.data.crossstore.ChangeSetPersister.NotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import com.funchole.backend.controlplane.dto.ProfileRequest;
import com.funchole.backend.controlplane.entity.AppUser;
import com.funchole.backend.controlplane.repository.AppUserRepository;

@Service
public class ProfileService {
    private final AppUserRepository appUserRepository;
    private final PasswordEncoder passwordEncoder;
    
    public ProfileService(AppUserRepository appUserRepository, PasswordEncoder passwordEncoder) {
        this.appUserRepository = appUserRepository;
        this.passwordEncoder = passwordEncoder;
    }
    
    public AppUser loadUserById(UUID id) throws NotFoundException {
        return appUserRepository.findById(id)
                .orElseThrow(() -> new NotFoundException());
    }

    public AppUser updateUser(AppUser appUser, ProfileRequest profileRequest) throws NotFoundException {

        if (profileRequest.fullName() != null) {
            appUser.setFullName(profileRequest.fullName());
        }
        
        if (profileRequest.password() != null) {
            appUser.setPasswordHash(passwordEncoder.encode(profileRequest.password()));
        }

        if (appUser.isPasswordChangeRequired()) {
            appUser.setPasswordChangeRequired(false);
        }
        
        return appUserRepository.save(appUser);
    }
}
