package com.vitra.core;

import com.vitra.core.config.VitraConfig;
import com.vitra.core.optimization.OptimizationManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Paths;

/**
 * Core optimization and management system for Vitra
 * Handles performance optimizations, memory management, and system coordination
 */
public class VitraCore {
    private static final Logger LOGGER = LoggerFactory.getLogger(VitraCore.class);

    private VitraConfig config;
    private OptimizationManager optimizationManager;
    private boolean initialized = false;

    /**
     * Initialize the Vitra core systems
     */
    public void initialize() {
        if (initialized) {
            LOGGER.warn("VitraCore already initialized");
            return;
        }

        LOGGER.info("Initializing Vitra core systems...");

        try {
            // Load configuration
            config = new VitraConfig(Paths.get("config"));
            LOGGER.info("Configuration loaded");

            // Initialize optimization manager
            optimizationManager = new OptimizationManager(config);
            optimizationManager.initialize();
            LOGGER.info("Optimization manager initialized");

            initialized = true;
            LOGGER.info("Vitra core initialization complete");

        } catch (Exception e) {
            LOGGER.error("Failed to initialize Vitra core", e);
            throw new RuntimeException("Vitra core initialization failed", e);
        }
    }

    /**
     * Shutdown the Vitra core systems
     */
    public void shutdown() {
        if (!initialized) return;

        LOGGER.info("Shutting down Vitra core...");

        if (optimizationManager != null) {
            optimizationManager.shutdown();
        }

        if (config != null) {
            config.saveConfig();
        }

        initialized = false;
        LOGGER.info("Vitra core shutdown complete");
    }

    public VitraConfig getConfig() {
        return config;
    }

    public OptimizationManager getOptimizationManager() {
        return optimizationManager;
    }

    public boolean isInitialized() {
        return initialized;
    }
}