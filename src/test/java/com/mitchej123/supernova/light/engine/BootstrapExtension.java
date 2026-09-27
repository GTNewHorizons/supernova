package com.mitchej123.supernova.light.engine;

import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

/** Registered via junit-platform.properties autodetection. */
public final class BootstrapExtension implements BeforeAllCallback {

    @Override
    public void beforeAll(ExtensionContext context) {
        MCBootstrap.init();
    }
}
