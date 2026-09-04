package com.funchole.backend.controlplane.config;

import com.funchole.backend.certificate.CertificateProvider;
import com.funchole.backend.certificate.generator.CertificateGenerator;
import com.funchole.backend.certificate.generator.SelfSignedCertificateGenerator;
import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class CertificateConfig {

    @Bean
    CertificateGenerator certificateGenerator(CertificateProperties properties) {
        if (properties.provider() == CertificateProvider.SELF_SIGNED) {
            return new SelfSignedCertificateGenerator(Duration.ofDays(properties.selfSignedValidityDays()));
        }

        throw new IllegalStateException("Certificate provider is not implemented yet: " + properties.provider());
    }
}
