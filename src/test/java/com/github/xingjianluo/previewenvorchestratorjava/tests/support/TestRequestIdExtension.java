package com.github.xingjianluo.previewenvorchestratorjava.tests.support;

import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.slf4j.MDC;

/**
 * Puts a deterministic request id into the MDC for every test so lifecycle
 * decision logs always carry a request identifier in unit-test output.
 */
public class TestRequestIdExtension implements BeforeEachCallback, AfterEachCallback {

    @Override
    public void beforeEach(ExtensionContext context) {
        String id = "test-" + context.getRequiredTestMethod().getName();
        MDC.put("requestId", id);
    }

    @Override
    public void afterEach(ExtensionContext context) {
        MDC.remove("requestId");
    }
}
