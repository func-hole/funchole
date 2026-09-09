package com.funchole.backend.invocation;

import java.util.Optional;
import java.util.UUID;

public interface InvocationRegistry {

    Invocation create(CreateInvocationRequest request);

    Optional<Invocation> findById(UUID invocationId);
}
