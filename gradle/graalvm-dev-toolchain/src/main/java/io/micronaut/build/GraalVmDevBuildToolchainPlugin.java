package io.micronaut.build;

import org.gradle.api.Plugin;
import org.gradle.api.initialization.Settings;
import org.gradle.jvm.toolchain.JavaToolchainResolverRegistry;

import javax.inject.Inject;

public abstract class GraalVmDevBuildToolchainPlugin implements Plugin<Settings> {
    @Inject
    protected abstract JavaToolchainResolverRegistry getToolchainResolverRegistry();

    @Override
    public void apply(Settings settings) {
        settings.getPluginManager().apply("jvm-toolchain-management");
        if (settings.getProviders().gradleProperty("pyronautGraalVmDevTag").isPresent()) {
            System.setProperty("org.gradle.java.installations.auto-detect", "false");
            System.setProperty("org.gradle.java.installations.auto-download", "true");
        }
        getToolchainResolverRegistry().register(GraalVmDevBuildToolchainResolver.class);
    }
}
