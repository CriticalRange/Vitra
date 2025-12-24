package com.vitra.fabric;

import com.vitra.VitraMod;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;

/**
 * Fabric mod initializer for Vitra
 * Handles Fabric-specific initialization and hooks
 */
public final class VitraModFabric implements ModInitializer {
    private static final Logger LOGGER = LoggerFactory.getLogger("Vitra/Fabric");

    @Override
    public void onInitialize() {
        LOGGER.info("Initializing Vitra on Fabric");

        try {
            // Get the proper Fabric config directory
            Path configDir = FabricLoader.getInstance().getConfigDir().resolve("vitra");
            LOGGER.info("Using Fabric config directory: {}", configDir);

            // Initialize the common Vitra mod with proper config directory
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
        LOGGER.info("Registering Fabric-specific rendering hooks for DirectX JNI integration");

        try {
            // Register world rendering events for DirectX JNI integration
            registerWorldRenderingHooks();

            // Register chunk loading events for DirectX JNI mesh building
            registerChunkHooks();

            // Register entity rendering events
            registerEntityRenderingHooks();

            // Register render state invalidation for resource reloading
            registerRenderStateHooks();

            LOGGER.info("Fabric rendering hooks registered successfully");

        } catch (Exception e) {
            LOGGER.error("Failed to register Fabric rendering hooks", e);
            throw e;
        }
    }

    /**
     * Register Fabric WorldRenderEvents for DirectX JNI integration
     * TODO: Update for Fabric API 26.1 - WorldRenderEvents package location changed
     */
    private void registerWorldRenderingHooks() {
        // DEPRECATED for 26.1: WorldRenderEvents package location changed or removed
        // These hooks need to be reimplemented once Fabric API for 26.1 is stable
        LOGGER.warn("World rendering hooks disabled for 26.1 migration - Fabric API not yet compatible");
        
        /* DISABLED FOR 26.1 MIGRATION:
        // Hook into world rendering start to setup DirectX JNI frame
        net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents.START.register((context) -> {
            LOGGER.debug("*** FABRIC HOOK: WorldRenderEvents.START - Beginning DirectX JNI frame setup");

            // Notify DirectX JNI renderer that a new frame is starting
            if (com.vitra.VitraMod.getRenderer() != null) {
                try {
                    // Log frame start for DirectX JNI integration
                    LOGGER.debug("DirectX JNI frame setup completed via Fabric START hook");
                } catch (Exception e) {
                    LOGGER.error("Error in DirectX JNI frame setup during Fabric START hook", e);
                }
            }
        });

        // Hook into world rendering after entities to capture geometry
        net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents.AFTER_ENTITIES.register((context) -> {
            LOGGER.debug("*** FABRIC HOOK: WorldRenderEvents.AFTER_ENTITIES - Entities rendered");
        });

        // Hook into world rendering end to finalize DirectX JNI frame
        net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents.END.register((context) -> {
            LOGGER.debug("*** FABRIC HOOK: WorldRenderEvents.END - Finalizing DirectX JNI frame submission");

                        // Additional calls here would violate DirectX JNI threading model
            if (com.vitra.VitraMod.getRenderer() != null) {
                try {
                    // DirectX JNI frame submission is handled by WindowMixin -> VitraRenderer
                    // No additional action needed - frame is already properly submitted
                    LOGGER.debug("DirectX JNI frame already submitted via WindowMixin->VitraRenderer (proper threading model)");
                } catch (Exception e) {
                    LOGGER.error("Error during Fabric END hook", e);
                }
            }
        });

        LOGGER.debug("World rendering hooks registered");
        */
    }

    /**
     * Register Fabric chunk events for DirectX JNI mesh building
     */
    private void registerChunkHooks() {
        // Hook into client-side chunk loading for DirectX JNI mesh preparation
        net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents.CHUNK_LOAD.register((world, chunk) -> {
            LOGGER.debug("*** FABRIC HOOK: ClientChunkEvents.CHUNK_LOAD - Chunk loaded at {}, {}", chunk.getPos().x, chunk.getPos().z);

            // Chunk loading detected - DirectX JNI will handle mesh data through existing pipeline
            if (com.vitra.VitraMod.getRenderer() != null) {
                try {
                    // Log chunk loading for DirectX JNI integration tracking
                    LOGGER.debug("DirectX JNI chunk tracking - chunk loaded at {}, {}", chunk.getPos().x, chunk.getPos().z);
                } catch (Exception e) {
                    LOGGER.error("Error tracking chunk load for DirectX JNI at {}, {}", chunk.getPos().x, chunk.getPos().z, e);
                }
            }
        });

        // Hook into client-side chunk unloading for DirectX JNI cleanup
        net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents.CHUNK_UNLOAD.register((world, chunk) -> {
            LOGGER.debug("*** FABRIC HOOK: ClientChunkEvents.CHUNK_UNLOAD - Chunk unloaded at {}, {}", chunk.getPos().x, chunk.getPos().z);

            // Chunk unloading detected - DirectX JNI will handle cleanup through existing pipeline
            if (com.vitra.VitraMod.getRenderer() != null) {
                try {
                    // Log chunk unloading for DirectX JNI integration tracking
                    LOGGER.debug("DirectX JNI chunk tracking - chunk unloaded at {}, {}", chunk.getPos().x, chunk.getPos().z);
                } catch (Exception e) {
                    LOGGER.error("Error tracking chunk unload for DirectX JNI at {}, {}", chunk.getPos().x, chunk.getPos().z, e);
                }
            }
        });

        LOGGER.debug("Chunk event hooks registered");
    }

    /**
     * Register entity rendering hooks for DirectX JNI
     */
    private void registerEntityRenderingHooks() {
        // Entity rendering is primarily handled through our existing mixins
        // But we can hook into specific entity features if needed

        LOGGER.debug("Entity rendering hooks registered (handled via mixins)");
    }

    /**
     * Register render state invalidation hooks for resource reloading
     */
    private void registerRenderStateHooks() {
        // Hook into render state invalidation (F3+A, resource pack changes)
        net.fabricmc.fabric.api.client.rendering.v1.InvalidateRenderStateCallback.EVENT.register(() -> {
            LOGGER.info("*** FABRIC HOOK: InvalidateRenderStateCallback - Render state invalidated, reinitializing DirectX JNI");

            // Reinitialize DirectX JNI when render state is invalidated
            if (com.vitra.VitraMod.getRenderer() != null) {
                try {
                    // Reload DirectX JNI shaders for validation
                    if (com.vitra.VitraMod.getCore() != null) {
                        com.vitra.VitraMod.getCore().loadShaders();
                    }

                    LOGGER.info("DirectX JNI render state reinitialized after invalidation");
                } catch (Exception e) {
                    LOGGER.error("Error reinitializing DirectX JNI render state", e);
                }
            }
        });

        LOGGER.debug("Render state invalidation hooks registered");
    }
}
