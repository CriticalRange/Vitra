package com.vitra.neoforge;

import com.vitra.VitraMod;
import net.neoforged.fml.common.Mod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * NeoForge mod initializer for Vitra
 * Handles NeoForge-specific initialization and hooks
 */
@Mod(VitraMod.MOD_ID)
public final class ExampleModNeoForge {
    private static final Logger LOGGER = LoggerFactory.getLogger("Vitra/NeoForge");

    public ExampleModNeoForge() {
        LOGGER.info("Initializing Vitra on NeoForge");

        try {
            // Initialize the common Vitra mod
            VitraMod.init();

            // Register NeoForge-specific hooks
            registerNeoForgeHooks();

            LOGGER.info("Vitra NeoForge initialization complete");

        } catch (Exception e) {
            LOGGER.error("Failed to initialize Vitra on NeoForge", e);
            throw e;
        }
    }

    /**
     * Register NeoForge-specific hooks and events
     */
    private void registerNeoForgeHooks() {
        // TODO: Register NeoForge-specific rendering hooks
        // This would include hooks for:
        // - Chunk rendering pipeline
        // - Entity rendering pipeline
        // - Shader loading events
        // - Configuration GUI integration

        LOGGER.debug("NeoForge hooks registered");
    }
}
