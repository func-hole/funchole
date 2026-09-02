package com.funchole.backend.controlplane.security;

import com.funchole.backend.controlplane.entity.AppUser;
import com.funchole.backend.controlplane.repository.AppUserRepository;

import java.util.UUID;

import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

@Service
public class AppUserDetailsService implements UserDetailsService {

    private final AppUserRepository appUserRepository;

    public AppUserDetailsService(AppUserRepository appUserRepository) {
        this.appUserRepository = appUserRepository;
    }

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        AppUser appUser = appUserRepository.findByUsername(username)
                .orElseThrow(() -> new UsernameNotFoundException("App user not found: " + username));

        return new AppUserPrincipal(appUser);
    }

    public AppUserPrincipal loadUserById(UUID id) throws UsernameNotFoundException {
        AppUser appUser = appUserRepository.findById(id)
                .orElseThrow(() -> new UsernameNotFoundException("App user not found: " + id));

        return new AppUserPrincipal(appUser);
    }
}
