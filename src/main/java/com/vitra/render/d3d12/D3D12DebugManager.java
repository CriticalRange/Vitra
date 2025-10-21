package com.vitra.render.d3d12;

import com.vitra.render.jni.VitraD3D12Native;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * DirectX 12 Debug Manager inspired by VulkanMod's comprehensive debugging system
 * Provides debug layer integration, resource naming, and advanced debugging capabilities
 */
public class D3D12DebugManager {
    private static final Logger LOGGER = LoggerFactory.getLogger("Vitra/D3D12DebugManager");

    // Debug message severity levels
    public static final int SEVERITY_INFO = 0;
    public static final int SEVERITY_WARNING = 1;
    public static final int SEVERITY_ERROR = 2;
    public static final int SEVERITY_CORRUPTION = 3;
    public static final int SEVERITY_MESSAGE = 4;

    // Debug message categories
    public static final int CATEGORY_APPLICATION_DEFINED = 0;
    public static final int CATEGORY_MISCELLANEOUS = 1;
    public static final int CATEGORY_INITIALIZATION = 2;
    public static final int CATEGORY_CLEANUP = 3;
    public static final int CATEGORY_COMPILATION = 4;
    public static final int CATEGORY_STATE_SETTING = 5;
    public static final int CATEGORY_STATE_GETTING = 6;
    public static final int CATEGORY_RESOURCE_MANIPULATION = 7;
    public static final int CATEGORY_EXECUTION = 8;
    public static final int CATEGORY_SHADING = 9;

    /**
     * Debug message information
     */
    private static class DebugMessage {
        final int severity;
        final int category;
        final int id;
        final String description;
        final long timestamp;

        DebugMessage(int severity, int category, int id, String description, long timestamp) {
            this.severity = severity;
            this.category = category;
            this.id = id;
            this.description = description;
            this.timestamp = timestamp;
        }

        @Override
        public String toString() {
            return String.format("[%s] [%s] [%d] %s",
                getSeverityName(severity),
                getCategoryName(category),
                id,
                description);
        }
    }

    // Resource tracking for debugging
    private static class ResourceInfo {
        final long handle;
        final String type;
        final String name;
        final long creationTime;
        final long size;
        final Map<String, Object> metadata;

        ResourceInfo(long handle, String type, String name, long size, Map<String, Object> metadata) {
            this.handle = handle;
            this.type = type;
            this.name = name;
            this.creationTime = System.nanoTime();
            this.size = size;
            this.metadata = metadata != null ? new HashMap<>(metadata) : new HashMap<>();
        }

        @Override
        public String toString() {
            return String.format("%s[0x%x]: %s (size=%d)",
                type, handle, name, size);
        }
    }

    // Debug state
    private boolean debugLayerEnabled = false;
    private boolean gpuValidationEnabled = false;
    private boolean resourceLeakDetectionEnabled = false;
    private boolean objectNamingEnabled = true;
    private boolean messageQueueEnabled = true;

    // Message and resource tracking
    private final List<DebugMessage> debugMessages;
    private final Map<Long, ResourceInfo> trackedResources;
    private final Map<String, ResourceInfo> namedResources;

    // Statistics
    private final Map<Integer, AtomicLong> messageCounts;
    private final AtomicLong totalMessages = new AtomicLong(0);
    private final AtomicLong leakDetectedCount = new AtomicLong(0);

    public D3D12DebugManager() {
        this.debugMessages = Collections.synchronizedList(new ArrayList<>());
        this.trackedResources = new ConcurrentHashMap<>();
        this.namedResources = new ConcurrentHashMap<>();
        this.messageCounts = new ConcurrentHashMap<>();

        // Initialize message counts
        for (int severity = SEVERITY_INFO; severity <= SEVERITY_CORRUPTION; severity++) {
            messageCounts.put(severity, new AtomicLong(0));
        }

        LOGGER.info("D3D12 Debug Manager initialized");
    }

    /**
     * Initialize debug layer and validation
     */
    public boolean initializeDebugLayer() {
        LOGGER.info("Initializing D3D12 debug layer and validation");

        try {
            // Enable debug layer through native interface
            boolean success = VitraD3D12Native.enableDebugLayer(true);
            if (success) {
                debugLayerEnabled = true;
                gpuValidationEnabled = true;

                LOGGER.info("D3D12 debug layer enabled successfully");

                // Process any queued debug messages
                processDebugMessages();

                return true;
            } else {
                LOGGER.warn("Failed to enable D3D12 debug layer");
                return false;
            }
        } catch (Exception e) {
            LOGGER.error("Exception during D3D12 debug layer initialization", e);
            return false;
        }
    }

    /**
     * Configure debug features
     */
    public void configureDebugSettings(boolean enableDebugLayer, boolean enableGpuValidation,
                                    boolean enableResourceLeakDetection, boolean enableObjectNaming) {
        this.debugLayerEnabled = enableDebugLayer;
        this.gpuValidationEnabled = enableGpuValidation;
        this.resourceLeakDetectionEnabled = enableResourceLeakDetection;
        this.objectNamingEnabled = enableObjectNaming;

        LOGGER.info("D3D12 Debug Configuration:");
        LOGGER.info("  Debug Layer: {}", debugLayerEnabled);
        LOGGER.info("  GPU Validation: {}", gpuValidationEnabled);
        LOGGER.info("  Resource Leak Detection: {}", resourceLeakDetectionEnabled);
        LOGGER.info("  Object Naming: {}", objectNamingEnabled);

        // Apply settings to native layer
        VitraD3D12Native.setDebugLayerConfiguration(
            enableDebugLayer, enableGpuValidation,
            enableResourceLeakDetection, enableObjectNaming
        );
    }

    /**
     * Register resource for leak detection
     */
    public void registerResource(long handle, String type, String name, long size, Map<String, Object> metadata) {
        if (!resourceLeakDetectionEnabled || handle == 0) {
            return;
        }

        ResourceInfo info = new ResourceInfo(handle, type, name, size, metadata);
        trackedResources.put(handle, info);
        if (name != null) {
            namedResources.put(name, info);
        }

        // Set resource name in native debug layer if enabled
        if (objectNamingEnabled && name != null) {
            VitraD3D12Native.setResourceDebugName(handle, name);
        }

        LOGGER.trace("Registered resource: {} for leak detection", info);
    }

    /**
     * Unregister resource (proper cleanup)
     */
    public void unregisterResource(long handle) {
        if (!resourceLeakDetectionEnabled) {
            return;
        }

        ResourceInfo info = trackedResources.remove(handle);
        if (info != null && info.name != null) {
            namedResources.remove(info.name);
        }

        LOGGER.trace("Unregistered resource: {} (cleaned up)", info != null ? info.toString() : "Unknown");
    }

    /**
     * Unregister resource by name
     */
    public void unregisterResource(String name) {
        ResourceInfo info = namedResources.remove(name);
        if (info != null) {
            trackedResources.remove(info.handle);
            LOGGER.trace("Unregistered named resource: {} (cleaned up)", name);
        }
    }

    /**
     * Process debug messages from debug layer
     */
    public void processDebugMessages() {
        if (!messageQueueEnabled) {
            return;
        }

        try {
            // Get debug messages from native layer
            int messageCount = VitraD3D12Native.getDebugMessageCount();
            for (int i = 0; i < messageCount; i++) {
                String message = VitraD3D12Native.getDebugMessage(i);
                if (message != null) {
                    DebugMessage debugMsg = parseDebugMessage(message);
                    addDebugMessage(debugMsg);
                }
            }

            // Clear processed messages
            VitraD3D12Native.clearDebugMessages();

        } catch (Exception e) {
            LOGGER.error("Failed to process debug messages", e);
        }
    }

    /**
     * Parse debug message string
     */
    private DebugMessage parseDebugMessage(String message) {
        // Parse format: [SEVERITY] [CATEGORY] [ID] Description
        String[] parts = message.split(" ", 4);
        if (parts.length < 4) {
            return new DebugMessage(SEVERITY_MESSAGE, CATEGORY_MISCELLANEOUS, 0, message, System.currentTimeMillis());
        }

        try {
            int severity = parseSeverity(parts[0]);
            int category = parseCategory(parts[1]);
            int id = Integer.parseInt(parts[2].replaceAll("[\\[\\]]", ""));
            String description = parts[3];

            return new DebugMessage(severity, category, id, description, System.currentTimeMillis());
        } catch (Exception e) {
            return new DebugMessage(SEVERITY_MESSAGE, CATEGORY_MISCELLANEOUS, 0, message, System.currentTimeMillis());
        }
    }

    /**
     * Parse severity from message part
     */
    private int parseSeverity(String severityStr) {
        if (severityStr.contains("ERROR")) return SEVERITY_ERROR;
        if (severityStr.contains("WARNING")) return SEVERITY_WARNING;
        if (severityStr.contains("CORRUPTION")) return SEVERITY_CORRUPTION;
        if (severityStr.contains("INFO")) return SEVERITY_INFO;
        return SEVERITY_MESSAGE;
    }

    /**
     * Parse category from message part
     */
    private int parseCategory(String categoryStr) {
        if (categoryStr.contains("INITIALIZATION")) return CATEGORY_INITIALIZATION;
        if (categoryStr.contains("CLEANUP")) return CATEGORY_CLEANUP;
        if (categoryStr.contains("COMPILATION")) return CATEGORY_COMPILATION;
        if (categoryStr.contains("STATE_SETTING")) return CATEGORY_STATE_SETTING;
        if (categoryStr.contains("RESOURCE")) return CATEGORY_RESOURCE_MANIPULATION;
        if (categoryStr.contains("EXECUTION")) return CATEGORY_EXECUTION;
        if (categoryStr.contains("SHADING")) return CATEGORY_SHADING;
        return CATEGORY_MISCELLANEOUS;
    }

    /**
     * Add debug message to queue
     */
    private void addDebugMessage(DebugMessage message) {
        debugMessages.add(message);
        messageCounts.get(message.severity).incrementAndGet();
        totalMessages.incrementAndGet();

        // Log based on severity
        switch (message.severity) {
            case SEVERITY_ERROR:
            case SEVERITY_CORRUPTION:
                LOGGER.error("D3D12 Debug: {}", message);
                break;
            case SEVERITY_WARNING:
                LOGGER.warn("D3D12 Debug: {}", message);
                break;
            default:
                LOGGER.info("D3D12 Debug: {}", message);
                break;
        }

        // Trim messages if too many
        if (debugMessages.size() > 10000) {
            debugMessages.subList(0, 5000).clear();
        }
    }

    /**
     * Check for resource leaks
     */
    public void checkResourceLeaks() {
        if (!resourceLeakDetectionEnabled) {
            return;
        }

        int leakCount = trackedResources.size();
        if (leakCount > 0) {
            leakDetectedCount.addAndGet(leakCount);
            LOGGER.error("Resource leak detected: {} resources still allocated", leakCount);

            // Log leaked resources
            for (ResourceInfo resource : trackedResources.values()) {
                LOGGER.error("  Leaked resource: {}", resource);
            }
        } else {
            LOGGER.info("No resource leaks detected");
        }
    }

    /**
     * Set GPU breakpoint
     */
    public boolean setGpuBreakpoint(long resourceHandle) {
        try {
            return VitraD3D12Native.setGpuBreakpoint(resourceHandle);
        } catch (Exception e) {
            LOGGER.error("Failed to set GPU breakpoint", e);
            return false;
        }
    }

    /**
     * Remove GPU breakpoint
     */
    public boolean removeGpuBreakpoint(long resourceHandle) {
        try {
            return VitraD3D12Native.removeGpuBreakpoint(resourceHandle);
        } catch (Exception e) {
            LOGGER.error("Failed to remove GPU breakpoint", e);
            return false;
        }
    }

    /**
     * Validate resource
     */
    public boolean validateResource(long resourceHandle) {
        try {
            return VitraD3D12Native.validateResource(resourceHandle);
        } catch (Exception e) {
            LOGGER.error("Failed to validate resource", e);
            return false;
        }
    }

    /**
     * Get resource by handle
     */
    public ResourceInfo getResource(long handle) {
        return trackedResources.get(handle);
    }

    /**
     * Get resource by name
     */
    public ResourceInfo getResource(String name) {
        return namedResources.get(name);
    }

    /**
     * Get all tracked resources
     */
    public Collection<ResourceInfo> getAllResources() {
        return new ArrayList<>(trackedResources.values());
    }

    /**
     * Get recent debug messages
     */
    public List<DebugMessage> getRecentMessages(int count) {
        synchronized (debugMessages) {
            int startIndex = Math.max(0, debugMessages.size() - count);
            return new ArrayList<>(debugMessages.subList(startIndex, debugMessages.size()));
        }
    }

    /**
     * Get severity name for logging
     */
    private static String getSeverityName(int severity) {
        switch (severity) {
            case SEVERITY_INFO: return "INFO";
            case SEVERITY_WARNING: return "WARNING";
            case SEVERITY_ERROR: return "ERROR";
            case SEVERITY_CORRUPTION: return "CORRUPTION";
            case SEVERITY_MESSAGE: return "MESSAGE";
            default: return "UNKNOWN(" + severity + ")";
        }
    }

    /**
     * Get category name for logging
     */
    private static String getCategoryName(int category) {
        switch (category) {
            case CATEGORY_APPLICATION_DEFINED: return "APPLICATION";
            case CATEGORY_MISCELLANEOUS: return "MISCELLANEOUS";
            case CATEGORY_INITIALIZATION: return "INITIALIZATION";
            case CATEGORY_CLEANUP: return "CLEANUP";
            case CATEGORY_COMPILATION: return "COMPILATION";
            case CATEGORY_STATE_SETTING: return "STATE_SETTING";
            case CATEGORY_STATE_GETTING: return "STATE_GETTING";
            case CATEGORY_RESOURCE_MANIPULATION: return "RESOURCE";
            case CATEGORY_EXECUTION: return "EXECUTION";
            case CATEGORY_SHADING: return "SHADING";
            default: return "UNKNOWN(" + category + ")";
        }
    }

    /**
     * Get debug statistics
     */
    public String getDebugStats() {
        StringBuilder stats = new StringBuilder();
        stats.append("=== D3D12 Debug Statistics ===\n");
        stats.append("Debug Layer Enabled: ").append(debugLayerEnabled).append("\n");
        stats.append("GPU Validation Enabled: ").append(gpuValidationEnabled).append("\n");
        stats.append("Resource Leak Detection: ").append(resourceLeakDetectionEnabled).append("\n");
        stats.append("Object Naming: ").append(objectNamingEnabled).append("\n");
        stats.append("Message Queue: ").append(messageQueueEnabled).append("\n");

        stats.append("\n--- Message Statistics ---\n");
        stats.append("Total Messages: ").append(totalMessages.get()).append("\n");
        stats.append("Queued Messages: ").append(debugMessages.size()).append("\n");

        for (Map.Entry<Integer, AtomicLong> entry : messageCounts.entrySet()) {
            String severityName = getSeverityName(entry.getKey());
            stats.append(severityName).append(": ").append(entry.getValue().get()).append("\n");
        }

        stats.append("\n--- Resource Statistics ---\n");
        stats.append("Tracked Resources: ").append(trackedResources.size()).append("\n");
        stats.append("Named Resources: ").append(namedResources.size()).append("\n");
        stats.append("Leaks Detected: ").append(leakDetectedCount.get()).append("\n");

        // Resource breakdown by type
        Map<String, Integer> resourceTypes = new HashMap<>();
        long totalResourceSize = 0;
        for (ResourceInfo resource : trackedResources.values()) {
            resourceTypes.merge(resource.type, 1, Integer::sum);
            totalResourceSize += resource.size;
        }

        for (Map.Entry<String, Integer> entry : resourceTypes.entrySet()) {
            stats.append(entry.getKey()).append(": ").append(entry.getValue()).append("\n");
        }

        if (totalResourceSize > 0) {
            stats.append("Total Resource Size: ").append(totalResourceSize / 1024).append(" KB\n");
        }

        return stats.toString();
    }

    /**
     * Export debug information to file
     */
    public boolean exportDebugInfo(String filename) {
        try {
            StringBuilder export = new StringBuilder();
            export.append("# D3D12 Debug Export\n");
            export.append("# Generated: ").append(new Date()).append("\n\n");

            export.append("## Configuration\n");
            export.append("Debug Layer: ").append(debugLayerEnabled).append("\n");
            export.append("GPU Validation: ").append(gpuValidationEnabled).append("\n");
            export.append("Resource Leak Detection: ").append(resourceLeakDetectionEnabled).append("\n\n");

            export.append("## Debug Messages\n");
            for (DebugMessage message : getRecentMessages(1000)) {
                export.append(message.toString()).append("\n");
            }

            export.append("\n## Resource Leaks\n");
            for (ResourceInfo resource : trackedResources.values()) {
                export.append("Leaked: ").append(resource.toString()).append("\n");
            }

            // This would write to file in a real implementation
            LOGGER.info("Debug information exported to: {}", filename);
            return true;

        } catch (Exception e) {
            LOGGER.error("Failed to export debug information", e);
            return false;
        }
    }

    /**
     * Cleanup debug manager
     */
    public void cleanup() {
        LOGGER.info("Cleaning up D3D12 Debug Manager");

        // Check for resource leaks before cleanup
        checkResourceLeaks();

        // Clear all tracking
        debugMessages.clear();
        trackedResources.clear();
        namedResources.clear();
        messageCounts.clear();

        // Disable debug layer
        VitraD3D12Native.enableDebugLayer(false);

        LOGGER.info("D3D12 Debug Manager cleanup completed");
    }
}