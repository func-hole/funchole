package com.funchole.backend.certificate.generator;

import com.funchole.backend.certificate.CertificateRequest;
import com.funchole.backend.certificate.GeneratedCertificate;

public interface CertificateGenerator {

    GeneratedCertificate generate(CertificateRequest request);
}
