package com.funchole.backend.certificate.store;

import com.funchole.backend.certificate.CertificateBundle;
import com.funchole.backend.certificate.CertificateReference;

public interface CertificateStore extends CertificateLoader {

    void save(CertificateReference reference, CertificateBundle bundle);
}
