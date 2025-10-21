package com.vitra.render.d3d12;

import com.vitra.config.VitraConfig;
import com.vitra.render.jni.VitraD3D12Native;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * DirectX 12 adapter selection system inspired by VulkanMod's device selection
 * Provides intelligent GPU selection with fallback support
 */
public class D3D12AdapterSelector {
    private static final Logger LOGGER = LoggerFactory.getLogger("Vitra/D3D12AdapterSelector");

    public static class D3D12Adapter {
        public final int index;
        public final String name;
        public final long dedicatedVideoMemory;
        public final long dedicatedSystemMemory;
        public final long sharedSystemMemory;
        public final boolean supportsRayTracing;
        public final boolean supportsVariableRateShading;
        public final boolean supportsMeshShaders;
        public final boolean isDiscrete;
        public final boolean isIntegrated;
        public final boolean isSoftware;

        public D3D12Adapter(int index, String name, long dedicatedVideoMemory, long dedicatedSystemMemory,
                           long sharedSystemMemory, boolean supportsRayTracing, boolean supportsVariableRateShading,
                           boolean supportsMeshShaders, int adapterType) {
            this.index = index;
            this.name = name;
            this.dedicatedVideoMemory = dedicatedVideoMemory;
            this.dedicatedSystemMemory = dedicatedSystemMemory;
            this.sharedSystemMemory = sharedSystemMemory;
            this.supportsRayTracing = supportsRayTracing;
            this.supportsVariableRateShading = supportsVariableRateShading;
            this.supportsMeshShaders = supportsMeshShaders;
            this.isDiscrete = adapterType == 1; // DXGI_ADAPTER_FLAG_DISCRETE
            this.isIntegrated = adapterType == 2; // DXGI_ADAPTER_FLAG_INTEGRATED
            this.isSoftware = adapterType == 4; // DXGI_ADAPTER_FLAG_SOFTWARE
        }

        public long getTotalMemory() {
            return dedicatedVideoMemory + dedicatedSystemMemory;
        }

        @Override
        public String toString() {
            return String.format("Adapter[%d] %s (%s) - VRAM: %dMB, RT: %s, VRS: %s, MS: %s",
                index, name, isDiscrete ? "Discrete" : isIntegrated ? "Integrated" : "Software",
                dedicatedVideoMemory / (1024 * 1024), supportsRayTracing, supportsVariableRateShading, supportsMeshShaders);
        }
    }

    private final VitraConfig config;
    private final List<D3D12Adapter> availableAdapters;
    private D3D12Adapter selectedAdapter;

    public D3D12AdapterSelector(VitraConfig config) {
        this.config = config;
        this.availableAdapters = new ArrayList<>();
        this.selectedAdapter = null;

        enumerateAdapters();
    }

    /**
     * Enumerate all available DirectX 12 adapters
     */
    private void enumerateAdapters() {
        LOGGER.info("Enumerating DirectX 12 adapters...");

        int adapterCount = VitraD3D12Native.getAdapterCount();
        LOGGER.debug("Found {} adapters", adapterCount);

        for (int i = 0; i < adapterCount; i++) {
            String adapterInfo = VitraD3D12Native.getAdapterInfo(i);
            if (adapterInfo != null && !adapterInfo.isEmpty()) {
                D3D12Adapter adapter = parseAdapterInfo(i, adapterInfo);
                if (adapter != null) {
                    availableAdapters.add(adapter);
                    LOGGER.info("  {}", adapter);
                }
            }
        }

        LOGGER.info("Total suitable adapters: {}", availableAdapters.size());
    }

    /**
     * Parse adapter information from native string
     */
    private D3D12Adapter parseAdapterInfo(int index, String adapterInfo) {
        try {
            // Format: "Name|DedicatedVideoMemory|DedicatedSystemMemory|SharedSystemMemory|RT|VRS|MS|Type"
            String[] parts = adapterInfo.split("\\|");
            if (parts.length >= 8) {
                String name = parts[0];
                long dedicatedVideoMemory = Long.parseLong(parts[1]);
                long dedicatedSystemMemory = Long.parseLong(parts[2]);
                long sharedSystemMemory = Long.parseLong(parts[3]);
                boolean supportsRayTracing = Boolean.parseBoolean(parts[4]);
                boolean supportsVariableRateShading = Boolean.parseBoolean(parts[5]);
                boolean supportsMeshShaders = Boolean.parseBoolean(parts[6]);
                int adapterType = Integer.parseInt(parts[7]);

                return new D3D12Adapter(index, name, dedicatedVideoMemory, dedicatedSystemMemory,
                                       sharedSystemMemory, supportsRayTracing, supportsVariableRateShading,
                                       supportsMeshShaders, adapterType);
            }
        } catch (Exception e) {
            LOGGER.warn("Failed to parse adapter info for index {}: {}", index, adapterInfo, e);
        }
        return null;
    }

    /**
     * Select the best adapter based on VulkanMod's selection logic
     */
    public D3D12Adapter selectBestAdapter() {
        if (availableAdapters.isEmpty()) {
            LOGGER.error("No suitable DirectX 12 adapters found");
            return null;
        }

        // Check for user-specified adapter
        int preferredAdapterIndex = config.getPreferredAdapter();
        if (preferredAdapterIndex >= 0 && preferredAdapterIndex < availableAdapters.size()) {
            D3D12Adapter preferredAdapter = availableAdapters.get(preferredAdapterIndex);
            LOGGER.info("Using user-specified adapter: {}", preferredAdapter);
            selectedAdapter = preferredAdapter;
            return selectedAdapter;
        }

        // Apply VulkanMod's selection logic
        D3D12Adapter selected = autoPickAdapter();

        if (selected != null) {
            LOGGER.info("Auto-selected adapter: {}", selected);
        } else {
            LOGGER.warn("No suitable adapter found, falling back to first available");
            selected = availableAdapters.get(0);
        }

        selectedAdapter = selected;

        // Update config with selected adapter index
        config.setPreferredAdapter(selected.index);

        return selectedAdapter;
    }

    /**
     * Auto-pick the best adapter following VulkanMod's logic
     */
    private D3D12Adapter autoPickAdapter() {
        // Separate adapters by type
        List<D3D12Adapter> discreteGPUs = new ArrayList<>();
        List<D3D12Adapter> integratedGPUs = new ArrayList<>();
        List<D3D12Adapter> otherAdapters = new ArrayList<>();

        for (D3D12Adapter adapter : availableAdapters) {
            if (adapter.isSoftware) {
                continue; // Skip software adapters unless no other option
            }

            if (adapter.isDiscrete) {
                discreteGPUs.add(adapter);
            } else if (adapter.isIntegrated) {
                integratedGPUs.add(adapter);
            } else {
                otherAdapters.add(adapter);
            }
        }

        // Priority 1: Best discrete GPU (most VRAM, RT support preferred)
        if (!discreteGPUs.isEmpty()) {
            D3D12Adapter best = selectBestFromList(discreteGPUs);
            LOGGER.debug("Selected best discrete GPU: {}", best);
            return best;
        }

        // Priority 2: Best integrated GPU
        if (!integratedGPUs.isEmpty()) {
            D3D12Adapter best = selectBestFromList(integratedGPUs);
            LOGGER.debug("Selected best integrated GPU: {}", best);
            return best;
        }

        // Priority 3: Any other hardware adapter
        if (!otherAdapters.isEmpty()) {
            D3D12Adapter best = selectBestFromList(otherAdapters);
            LOGGER.debug("Selected best other adapter: {}", best);
            return best;
        }

        // Fallback: Software adapter if available
        for (D3D12Adapter adapter : availableAdapters) {
            if (adapter.isSoftware) {
                LOGGER.warn("Using software adapter as fallback: {}", adapter);
                return adapter;
            }
        }

        return null;
    }

    /**
     * Select best adapter from a list based on VulkanMod's criteria
     */
    private D3D12Adapter selectBestFromList(List<D3D12Adapter> adapters) {
        // Sort by multiple criteria
        return adapters.stream()
                .max(Comparator
                        .comparing((D3D12Adapter a) -> a.supportsRayTracing ? 1000 : 0)
                        .thenComparing((D3D12Adapter a) -> a.supportsVariableRateShading ? 500 : 0)
                        .thenComparing((D3D12Adapter a) -> a.supportsMeshShaders ? 250 : 0)
                        .thenComparing(D3D12Adapter::getTotalMemory))
                .orElse(null);
    }

    /**
     * Get all available adapters
     */
    public List<D3D12Adapter> getAvailableAdapters() {
        return new ArrayList<>(availableAdapters);
    }

    /**
     * Get the currently selected adapter
     */
    public D3D12Adapter getSelectedAdapter() {
        return selectedAdapter;
    }

    /**
     * Check if ray tracing is supported on any adapter
     */
    public boolean isRayTracingSupported() {
        return availableAdapters.stream().anyMatch(a -> a.supportsRayTracing);
    }

    /**
     * Check if variable rate shading is supported on any adapter
     */
    public boolean isVariableRateShadingSupported() {
        return availableAdapters.stream().anyMatch(a -> a.supportsVariableRateShading);
    }

    /**
     * Check if mesh shaders are supported on any adapter
     */
    public boolean isMeshShadersSupported() {
        return availableAdapters.stream().anyMatch(a -> a.supportsMeshShaders);
    }

    /**
     * Get adapter statistics
     */
    public String getAdapterStats() {
        StringBuilder stats = new StringBuilder();
        stats.append("=== DirectX 12 Adapter Statistics ===\n");
        stats.append("Total Adapters: ").append(availableAdapters.size()).append("\n");
        stats.append("Selected Adapter: ").append(selectedAdapter != null ? selectedAdapter.toString() : "None").append("\n");

        stats.append("\n--- Ray Tracing Support ---\n");
        stats.append("Available: ").append(isRayTracingSupported()).append("\n");
        stats.append("Adapters with RT: ").append(availableAdapters.stream().filter(a -> a.supportsRayTracing).count()).append("\n");

        stats.append("\n--- Variable Rate Shading Support ---\n");
        stats.append("Available: ").append(isVariableRateShadingSupported()).append("\n");
        stats.append("Adapters with VRS: ").append(availableAdapters.stream().filter(a -> a.supportsVariableRateShading).count()).append("\n");

        stats.append("\n--- Mesh Shader Support ---\n");
        stats.append("Available: ").append(isMeshShadersSupported()).append("\n");
        stats.append("Adapters with MS: ").append(availableAdapters.stream().filter(a -> a.supportsMeshShaders).count()).append("\n");

        stats.append("\n--- Memory Summary ---\n");
        long totalVRAM = availableAdapters.stream().mapToLong(a -> a.dedicatedVideoMemory).sum();
        stats.append("Total VRAM: ").append(totalVRAM / (1024 * 1024)).append(" MB\n");

        if (selectedAdapter != null) {
            stats.append("\n--- Selected Adapter Details ---\n");
            stats.append("Name: ").append(selectedAdapter.name).append("\n");
            stats.append("VRAM: ").append(selectedAdapter.dedicatedVideoMemory / (1024 * 1024)).append(" MB\n");
            stats.append("Type: ").append(selectedAdapter.isDiscrete ? "Discrete" : selectedAdapter.isIntegrated ? "Integrated" : "Software").append("\n");
            stats.append("Ray Tracing: ").append(selectedAdapter.supportsRayTracing).append("\n");
            stats.append("Variable Rate Shading: ").append(selectedAdapter.supportsVariableRateShading).append("\n");
            stats.append("Mesh Shaders: ").append(selectedAdapter.supportsMeshShaders).append("\n");
        }

        return stats.toString();
    }
}