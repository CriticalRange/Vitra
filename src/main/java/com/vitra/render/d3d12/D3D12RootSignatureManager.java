package com.vitra.render.d3d12;

import com.vitra.render.jni.VitraD3D12Native;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * DirectX 12 Root Signature Manager inspired by VulkanMod's descriptor set management
 * Provides root signature creation and parameter management
 */
public class D3D12RootSignatureManager {
    private static final Logger LOGGER = LoggerFactory.getLogger("Vitra/D3D12RootSignatureManager");

    // Root parameter types
    public static final int ROOT_PARAMETER_TYPE_DESCRIPTOR_TABLE = 0;
    public static final int ROOT_PARAMETER_TYPE_32BIT_CONSTANTS = 1;
    public static final int ROOT_PARAMETER_TYPE_CBV = 2;
    public static final int ROOT_PARAMETER_TYPE_SRV = 3;
    public static final int ROOT_PARAMETER_TYPE_UAV = 4;
    public static final int ROOT_PARAMETER_TYPE_DESCRIPTOR_TABLE_UAV_RANGE = 5;

    // Descriptor range types
    public static final int DESCRIPTOR_RANGE_TYPE_SRV = 0;
    public static final int DESCRIPTOR_RANGE_TYPE_UAV = 1;
    public static final int DESCRIPTOR_RANGE_TYPE_CBV = 2;
    public static final int DESCRIPTOR_RANGE_TYPE_SAMPLER = 3;

    // Root signature flags
    public static final int ROOT_SIGNATURE_FLAG_NONE = 0x0;
    public static final int ROOT_SIGNATURE_FLAG_ALLOW_INPUT_ASSEMBLER_INPUT_LAYOUT = 0x1;
    public static final int ROOT_SIGNATURE_FLAG_DENY_VERTEX_SHADER_ROOT_ACCESS = 0x2;
    public static final int ROOT_SIGNATURE_FLAG_DENY_HULL_SHADER_ROOT_ACCESS = 0x4;
    public static final int ROOT_SIGNATURE_FLAG_DENY_DOMAIN_SHADER_ROOT_ACCESS = 0x8;
    public static final int ROOT_SIGNATURE_FLAG_DENY_GEOMETRY_SHADER_ROOT_ACCESS = 0x10;
    public static final int ROOT_SIGNATURE_FLAG_DENY_PIXEL_SHADER_ROOT_ACCESS = 0x20;
    public static final int ROOT_SIGNATURE_FLAG_ALLOW_STREAM_OUTPUT = 0x40;
    public static final int ROOT_SIGNATURE_FLAG_LOCAL_ROOT_SIGNATURE = 0x80;
    public static final int ROOT_SIGNATURE_FLAG_DENY_AMPLIFICATION_SHADER_ROOT_ACCESS = 0x100;
    public static final int ROOT_SIGNATURE_FLAG_DENY_MESH_SHADER_ROOT_ACCESS = 0x200;

    // Shader visibility
    public static final int SHADER_VISIBILITY_ALL = 0;
    public static final int SHADER_VISIBILITY_VERTEX = 1;
    public static final int SHADER_VISIBILITY_HULL = 2;
    public static final int SHADER_VISIBILITY_DOMAIN = 3;
    public static final int SHADER_VISIBILITY_GEOMETRY = 4;
    public static final int SHADER_VISIBILITY_PIXEL = 5;
    public static final int SHADER_VISIBILITY_AMPLIFICATION = 6;
    public static final int SHADER_VISIBILITY_MESH = 7;

    /**
     * Root parameter definition
     */
    public static class RootParameter {
        public final int parameterType;
        public final int shaderVisibility;
        public final Object data; // Can be RootDescriptorTable, RootConstants, or RootDescriptor

        public RootParameter(int parameterType, int shaderVisibility, Object data) {
            this.parameterType = parameterType;
            this.shaderVisibility = shaderVisibility;
            this.data = data;
        }
    }

    /**
     * Descriptor table range
     */
    public static class DescriptorRange {
        public final int rangeType;
        public final int numDescriptors;
        public final int baseShaderRegister;
        public final int registerSpace;
        public final int offsetInDescriptorsFromTableStart;

        public DescriptorRange(int rangeType, int numDescriptors, int baseShaderRegister,
                             int registerSpace, int offsetInDescriptorsFromTableStart) {
            this.rangeType = rangeType;
            this.numDescriptors = numDescriptors;
            this.baseShaderRegister = baseShaderRegister;
            this.registerSpace = registerSpace;
            this.offsetInDescriptorsFromTableStart = offsetInDescriptorsFromTableStart;
        }
    }

    /**
     * Descriptor table
     */
    public static class DescriptorTable {
        public final List<DescriptorRange> descriptorRanges;

        public DescriptorTable(List<DescriptorRange> descriptorRanges) {
            this.descriptorRanges = new ArrayList<>(descriptorRanges);
        }
    }

    /**
     * 32-bit constants
     */
    public static class RootConstants {
        public final int num32BitValues;
        public final int shaderRegister;
        public final int registerSpace;

        public RootConstants(int num32BitValues, int shaderRegister, int registerSpace) {
            this.num32BitValues = num32BitValues;
            this.shaderRegister = shaderRegister;
            this.registerSpace = registerSpace;
        }
    }

    /**
     * Root descriptor (CBV/SRV/UAV)
     */
    public static class RootDescriptor {
        public final int parameterType; // CBV, SRV, or UAV
        public final int shaderRegister;
        public final int registerSpace;

        public RootDescriptor(int parameterType, int shaderRegister, int registerSpace) {
            this.parameterType = parameterType;
            this.shaderRegister = shaderRegister;
            this.registerSpace = registerSpace;
        }
    }

    /**
     * Root signature definition
     */
    public static class RootSignatureDefinition {
        public final List<RootParameter> parameters;
        public final int flags;
        public final String name;

        public RootSignatureDefinition(String name, List<RootParameter> parameters, int flags) {
            this.name = name;
            this.parameters = new ArrayList<>(parameters);
            this.flags = flags;
        }
    }

    /**
     * Compiled root signature
     */
    public static class RootSignature {
        private final long handle;
        private final RootSignatureDefinition definition;
        private final long creationTime;
        private final boolean valid;

        public RootSignature(long handle, RootSignatureDefinition definition, long creationTime, boolean valid) {
            this.handle = handle;
            this.definition = definition;
            this.creationTime = creationTime;
            this.valid = valid;
        }

        public long getHandle() { return handle; }
        public RootSignatureDefinition getDefinition() { return definition; }
        public long getCreationTime() { return creationTime; }
        public boolean isValid() { return valid; }

        public String getName() { return definition.name; }
        public List<RootParameter> getParameters() { return new ArrayList<>(definition.parameters); }
        public int getFlags() { return definition.flags; }
    }

    // Root signature cache
    private final Map<String, RootSignature> rootSignatureCache;
    private long nextRootSignatureId = 1;

    public D3D12RootSignatureManager() {
        this.rootSignatureCache = new ConcurrentHashMap<>();
        createCommonRootSignatures();
    }

    /**
     * Create common root signatures used by Minecraft
     */
    private void createCommonRootSignatures() {
        LOGGER.info("Creating common D3D12 root signatures");

        // Create standard Minecraft root signature with common parameters
        createMinecraftStandardRootSignature();

        // Create simple root signature for basic rendering
        createSimpleRootSignature();

        // Create complex root signature for advanced shaders
        createComplexRootSignature();

        LOGGER.info("Created {} common root signatures", rootSignatureCache.size());
    }

    /**
     * Create standard Minecraft root signature
     */
    private void createMinecraftStandardRootSignature() {
        List<RootParameter> parameters = new ArrayList<>();

        // Parameter 0: Camera matrices (CBV, b0, space 0)
        parameters.add(new RootParameter(
            ROOT_PARAMETER_TYPE_CBV,
            SHADER_VISIBILITY_ALL,
            new RootDescriptor(ROOT_PARAMETER_TYPE_CBV, 0, 0)
        ));

        // Parameter 1: Lighting data (CBV, b1, space 0)
        parameters.add(new RootParameter(
            ROOT_PARAMETER_TYPE_CBV,
            SHADER_VISIBILITY_ALL,
            new RootDescriptor(ROOT_PARAMETER_TYPE_CBV, 1, 0)
        ));

        // Parameter 2: Textures (Descriptor table with 16 textures, t0-t15, space 0)
        List<DescriptorRange> textureRanges = new ArrayList<>();
        textureRanges.add(new DescriptorRange(
            DESCRIPTOR_RANGE_TYPE_SRV, 16, 0, 0, 0
        ));
        parameters.add(new RootParameter(
            ROOT_PARAMETER_TYPE_DESCRIPTOR_TABLE,
            SHADER_VISIBILITY_ALL,
            new DescriptorTable(textureRanges)
        ));

        // Parameter 3: Samplers (Descriptor table with 8 samplers, s0-s7, space 0)
        List<DescriptorRange> samplerRanges = new ArrayList<>();
        samplerRanges.add(new DescriptorRange(
            DESCRIPTOR_RANGE_TYPE_SAMPLER, 8, 0, 0, 0
        ));
        parameters.add(new RootParameter(
            ROOT_PARAMETER_TYPE_DESCRIPTOR_TABLE,
            SHADER_VISIBILITY_ALL,
            new DescriptorTable(samplerRanges)
        ));

        // Parameter 4: Material data (32 constants, 8 registers, space 0)
        parameters.add(new RootParameter(
            ROOT_PARAMETER_TYPE_32BIT_CONSTANTS,
            SHADER_VISIBILITY_ALL,
            new RootConstants(32, 2, 0)
        ));

        int flags = ROOT_SIGNATURE_FLAG_ALLOW_INPUT_ASSEMBLER_INPUT_LAYOUT;

        createRootSignature("MinecraftStandard", parameters, flags);
    }

    /**
     * Create simple root signature
     */
    private void createSimpleRootSignature() {
        List<RootParameter> parameters = new ArrayList<>();

        // Parameter 0: MVP matrix (CBV, b0, space 0)
        parameters.add(new RootParameter(
            ROOT_PARAMETER_TYPE_CBV,
            SHADER_VISIBILITY_ALL,
            new RootDescriptor(ROOT_PARAMETER_TYPE_CBV, 0, 0)
        ));

        // Parameter 1: Single texture (SRV, t0, space 0)
        parameters.add(new RootParameter(
            ROOT_PARAMETER_TYPE_SRV,
            SHADER_VISIBILITY_ALL,
            new RootDescriptor(ROOT_PARAMETER_TYPE_SRV, 0, 0)
        ));

        // Parameter 2: Single sampler (s0, space 0) - would be static sampler
        List<DescriptorRange> samplerRanges = new ArrayList<>();
        samplerRanges.add(new DescriptorRange(
            DESCRIPTOR_RANGE_TYPE_SAMPLER, 1, 0, 0, 0
        ));
        parameters.add(new RootParameter(
            ROOT_PARAMETER_TYPE_DESCRIPTOR_TABLE,
            SHADER_VISIBILITY_ALL,
            new DescriptorTable(samplerRanges)
        ));

        int flags = ROOT_SIGNATURE_FLAG_ALLOW_INPUT_ASSEMBLER_INPUT_LAYOUT;

        createRootSignature("Simple", parameters, flags);
    }

    /**
     * Create complex root signature for advanced shaders
     */
    private void createComplexRootSignature() {
        List<RootParameter> parameters = new ArrayList<>();

        // Parameter 0: Frame constants (CBV, b0, space 0)
        parameters.add(new RootParameter(
            ROOT_PARAMETER_TYPE_CBV,
            SHADER_VISIBILITY_ALL,
            new RootDescriptor(ROOT_PARAMETER_TYPE_CBV, 0, 0)
        ));

        // Parameter 1: Object constants (CBV, b1, space 0)
        parameters.add(new RootParameter(
            ROOT_PARAMETER_TYPE_CBV,
            SHADER_VISIBILITY_ALL,
            new RootDescriptor(ROOT_PARAMETER_TYPE_CBV, 1, 0)
        ));

        // Parameter 2: Textures (Descriptor table with 32 textures, t0-t31, space 0)
        List<DescriptorRange> textureRanges = new ArrayList<>();
        textureRanges.add(new DescriptorRange(
            DESCRIPTOR_RANGE_TYPE_SRV, 32, 0, 0, 0
        ));
        parameters.add(new RootParameter(
            ROOT_PARAMETER_TYPE_DESCRIPTOR_TABLE,
            SHADER_VISIBILITY_ALL,
            new DescriptorTable(textureRanges)
        ));

        // Parameter 3: UAVs (Descriptor table with 8 UAVs, u0-u7, space 0)
        List<DescriptorRange> uavRanges = new ArrayList<>();
        uavRanges.add(new DescriptorRange(
            DESCRIPTOR_RANGE_TYPE_UAV, 8, 0, 0, 0
        ));
        parameters.add(new RootParameter(
            ROOT_PARAMETER_TYPE_DESCRIPTOR_TABLE,
            SHADER_VISIBILITY_ALL,
            new DescriptorTable(uavRanges)
        ));

        // Parameter 4: Vertex shader constants (32 constants, 8 registers, space 0)
        parameters.add(new RootParameter(
            ROOT_PARAMETER_TYPE_32BIT_CONSTANTS,
            SHADER_VISIBILITY_VERTEX,
            new RootConstants(32, 2, 0)
        ));

        // Parameter 5: Pixel shader constants (16 constants, 4 registers, space 0)
        parameters.add(new RootParameter(
            ROOT_PARAMETER_TYPE_32BIT_CONSTANTS,
            SHADER_VISIBILITY_PIXEL,
            new RootConstants(16, 3, 0)
        ));

        // Parameter 6: Samplers (Descriptor table with 16 samplers, s0-s15, space 0)
        List<DescriptorRange> samplerRanges = new ArrayList<>();
        samplerRanges.add(new DescriptorRange(
            DESCRIPTOR_RANGE_TYPE_SAMPLER, 16, 0, 0, 0
        ));
        parameters.add(new RootParameter(
            ROOT_PARAMETER_TYPE_DESCRIPTOR_TABLE,
            SHADER_VISIBILITY_ALL,
            new DescriptorTable(samplerRanges)
        ));

        int flags = ROOT_SIGNATURE_FLAG_ALLOW_INPUT_ASSEMBLER_INPUT_LAYOUT;

        createRootSignature("Complex", parameters, flags);
    }

    /**
     * Create root signature from definition
     */
    public RootSignature createRootSignature(String name, List<RootParameter> parameters, int flags) {
        RootSignatureDefinition definition = new RootSignatureDefinition(name, parameters, flags);

        // Check cache
        RootSignature cached = rootSignatureCache.get(name);
        if (cached != null) {
            LOGGER.debug("Using cached root signature: {}", name);
            return cached;
        }

        LOGGER.info("Creating root signature: {} with {} parameters", name, parameters.size());

        long startTime = System.nanoTime();

        // Serialize root signature definition to native format
        byte[] serializedDefinition = serializeRootSignatureDefinition(definition);

        // Create root signature handle
        long handle = VitraD3D12Native.createRootSignature(serializedDefinition, definition.name);

        long creationTime = System.nanoTime() - startTime;
        boolean valid = (handle != 0);

        RootSignature rootSignature = new RootSignature(handle, definition, creationTime, valid);

        if (valid) {
            rootSignatureCache.put(name, rootSignature);
            LOGGER.info("Successfully created root signature: {}, handle=0x{}, time={}ms",
                name, Long.toHexString(handle), creationTime / 1_000_000);
        } else {
            LOGGER.error("Failed to create root signature: {}", name);
        }

        return rootSignature;
    }

    /**
     * Get root signature by name
     */
    public RootSignature getRootSignature(String name) {
        return rootSignatureCache.get(name);
    }

    /**
     * Remove root signature
     */
    public void removeRootSignature(String name) {
        RootSignature rootSignature = rootSignatureCache.remove(name);
        if (rootSignature != null && rootSignature.getHandle() != 0) {
            VitraD3D12Native.releaseManagedResource(rootSignature.getHandle());
            LOGGER.info("Released root signature: {}", name);
        }
    }

    /**
     * Clear all root signatures
     */
    public void clearCache() {
        // Release all root signatures
        for (RootSignature rootSignature : rootSignatureCache.values()) {
            if (rootSignature.getHandle() != 0) {
                VitraD3D12Native.releaseManagedResource(rootSignature.getHandle());
            }
        }

        rootSignatureCache.clear();
        LOGGER.info("Cleared root signature cache");
    }

    /**
     * Get cache statistics
     */
    public String getStats() {
        int totalRootSignatures = rootSignatureCache.size();
        int validRootSignatures = (int) rootSignatureCache.values().stream()
                .filter(RootSignature::isValid)
                .count();

        long totalCreationTime = rootSignatureCache.values().stream()
                .mapToLong(RootSignature::getCreationTime)
                .sum();

        double averageCreationTime = rootSignatureCache.isEmpty() ? 0.0 :
                (double) totalCreationTime / rootSignatureCache.size() / 1_000_000;

        StringBuilder stats = new StringBuilder();
        stats.append("D3D12 Root Signature Manager Statistics:\n");
        stats.append("  Total Root Signatures: ").append(totalRootSignatures).append("\n");
        stats.append("  Valid Root Signatures: ").append(validRootSignatures).append("\n");
        stats.append("  Average Creation Time: ").append(String.format("%.2f", averageCreationTime)).append(" ms\n");

        stats.append("\n--- Root Signatures ---\n");
        for (Map.Entry<String, RootSignature> entry : rootSignatureCache.entrySet()) {
            RootSignature rs = entry.getValue();
            stats.append("  ").append(entry.getKey()).append(": ")
                 .append(rs.isValid() ? "Valid" : "Invalid")
                 .append(", handle=0x").append(Long.toHexString(rs.getHandle()))
                 .append(", params=").append(rs.getParameters().size())
                 .append("\n");
        }

        return stats.toString();
    }

    /**
     * Serialize root signature definition to byte array
     */
    private byte[] serializeRootSignatureDefinition(RootSignatureDefinition definition) {
        // This would need to be implemented to serialize the definition
        // to a format that the native code can understand
        // For now, return a placeholder
        return definition.name.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    /**
     * Get parameter type name for logging
     */
    private static String parameterTypeName(int parameterType) {
        switch (parameterType) {
            case ROOT_PARAMETER_TYPE_DESCRIPTOR_TABLE: return "DescriptorTable";
            case ROOT_PARAMETER_TYPE_32BIT_CONSTANTS: return "32bitConstants";
            case ROOT_PARAMETER_TYPE_CBV: return "CBV";
            case ROOT_PARAMETER_TYPE_SRV: return "SRV";
            case ROOT_PARAMETER_TYPE_UAV: return "UAV";
            default: return "Unknown(" + parameterType + ")";
        }
    }

    /**
     * Get shader visibility name for logging
     */
    private static String shaderVisibilityName(int visibility) {
        switch (visibility) {
            case SHADER_VISIBILITY_ALL: return "All";
            case SHADER_VISIBILITY_VERTEX: return "Vertex";
            case SHADER_VISIBILITY_HULL: return "Hull";
            case SHADER_VISIBILITY_DOMAIN: return "Domain";
            case SHADER_VISIBILITY_GEOMETRY: return "Geometry";
            case SHADER_VISIBILITY_PIXEL: return "Pixel";
            case SHADER_VISIBILITY_AMPLIFICATION: return "Amplification";
            case SHADER_VISIBILITY_MESH: return "Mesh";
            default: return "Unknown(" + visibility + ")";
        }
    }
}