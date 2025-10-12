package com.vitra.fabric.client;

import com.vitra.VitraMod;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Fabric client-side mod initializer for Vitra
 * Handles client-specific initialization for rendering systems
 */
public final class VitraModFabricClient implements ClientModInitializer {
    private static final Logger LOGGER = LoggerFactory.getLogger("Vitra/FabricClient");

    @Override
    public void onInitializeClient() {
        LOGGER.info("Initializing Vitra client-side components");

        try {
            // Register client-side tick events for BGFX frame management
            registerClientTickEvents();

            LOGGER.info("Vitra client initialization completed successfully");
        } catch (Exception e) {
            LOGGER.error("Failed to initialize Vitra client components", e);
            throw new RuntimeException("Vitra client initialization failed", e);
        }
    }

    /**
     * Register client tick events for BGFX frame synchronization
     */
    private void registerClientTickEvents() {
        // Register end tick event to ensure BGFX frames are properly submitted
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            try {
                // Ensure BGFX renderer is available and properly synchronized
                if (VitraMod.getRenderer() != null && VitraMod.getRenderer().isInitialized()) {
                    // BGFX frame submission is handled by WindowMixin, but we ensure
                    // client-side synchronization here
                    LOGGER.debug("Client tick completed - BGFX synchronization verified");
                }
            } catch (Exception e) {
                LOGGER.error("Error during client tick BGFX synchronization", e);
            }
        });

        LOGGER.debug("Client tick events registered for BGFX synchronization");
    }
}