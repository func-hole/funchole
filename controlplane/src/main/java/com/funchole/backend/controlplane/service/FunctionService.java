package com.funchole.backend.controlplane.service;

import com.funchole.backend.controlplane.dto.FunctionCreateRequest;
import com.funchole.backend.controlplane.dto.FunctionUpdateRequest;
import com.funchole.backend.controlplane.entity.AppUser;
import com.funchole.backend.controlplane.entity.Function;
import com.funchole.backend.controlplane.repository.FunctionRepository;
import com.funchole.backend.core.base.exception.ResourceNotFoundException;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class FunctionService {
    private static final String DEFAULT_RUNTIME = "NODE";

    private final FunctionRepository functionRepository;

    public FunctionService(FunctionRepository functionRepository) {
        this.functionRepository = functionRepository;
    }

    public Page<Function> listFunctions(UUID appUserId, int page, int size) {
        Pageable pageable = PageRequest.of(
                Math.max(page - 1, 0),
                Math.max(size, 1),
                Sort.by(Sort.Direction.DESC, Function::getCreatedAt)
        );
        return functionRepository.findAllByAppUser_IdAndDeletedAtIsNull(appUserId, pageable);
    }

    public Function getFunctionById(UUID appUserId, UUID functionId) {
        return functionRepository.findByIdAndAppUser_IdAndDeletedAtIsNull(functionId, appUserId)
                .orElseThrow(() -> new ResourceNotFoundException("Function not found: " + functionId));
    }

    @Transactional
    public Function createFunction(AppUser appUser, FunctionCreateRequest request) {
        if (functionRepository.existsByFunctionKey(request.functionKey())) {
            throw new IllegalArgumentException("Function key already in use: " + request.functionKey());
        }

        Function function = Function.create(
                appUser,
                request.functionKey(),
                request.name(),
                request.description(),
                resolveRuntime(request.runtime())
        );

        return functionRepository.save(function);
    }

    @Transactional
    public Function updateFunction(UUID appUserId, UUID functionId, FunctionUpdateRequest request) {
        Function function = getFunctionById(appUserId, functionId);

        function.update(
                request.name(),
                request.description(),
                resolveRuntime(request.runtime())
        );

        return functionRepository.save(function);
    }

    @Transactional
    public void deleteFunction(UUID appUserId, UUID functionId) {
        Function function = getFunctionById(appUserId, functionId);
        function.softDelete();
        functionRepository.save(function);
    }

    private String resolveRuntime(String runtime) {
        return runtime != null ? runtime : DEFAULT_RUNTIME;
    }
}
