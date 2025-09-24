package com.vitra.fabric;

import com.vitra.VitraMod;
import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Fabric mod initializer for Vitra
 * Handles Fabric-specific initialization and hooks
 */
public final class ExampleModFabric implements ModInitializer {
    private static final Logger LOGGER = LoggerFactory.getLogger("Vitra/Fabric");

    @Override
    public void onInitialize() {
        LOGGER.info("Initializing Vitra on Fabric");

        try {
            // Initialize the common Vitra mod
            VitraMod.init();

            // Register Fabric-specific hooks
            registerFabricHooks();

            LOGGER.info("Vitra Fabric initialization complete");

        } catch (Exception e) {
            LOGGER.error("Failed to initialize Vitra on Fabric", e);
            throw e;
        }
    }

    /**
     * Register Fabric-specific hooks and events
     */
    private void registerFabricHooks() {
        // TODO: Register Fabric-specific rendering hooks
        // This would include hooks for:
        // - Chunk rendering pipeline
        // - Entity rendering pipeline
        // - Shader loading events
        // - Configuration GUI integration

        LOGGER.debug("Fabric hooks registered");
    }
}
