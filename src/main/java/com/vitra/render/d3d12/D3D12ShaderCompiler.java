package com.vitra.render.d3d12;

import com.vitra.render.jni.VitraD3D12Native;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * DirectX 12 Shader Compiler inspired by VulkanMod's SPIR-V compilation
 * Provides HLSL shader compilation with modern DirectX Shader Compiler support
 */
public class D3D12ShaderCompiler {
    private static final Logger LOGGER = LoggerFactory.getLogger("Vitra/D3D12ShaderCompiler");

    // Shader types
    public static final int SHADER_TYPE_VERTEX = 0;
    public static final int SHADER_TYPE_PIXEL = 1;
    public static final int SHADER_TYPE_GEOMETRY = 2;
    public static final int SHADER_TYPE_HULL = 3;
    public static final int SHADER_TYPE_DOMAIN = 4;
    public static final int SHADER_TYPE_COMPUTE = 5;
    public static final int SHADER_TYPE_MESH = 6;
    public static final int SHADER_TYPE_AMPLIFICATION = 7;
    public static final int SHADER_TYPE_RAYTRACING = 8;

    // HLSL shader models
    public static final String SHADER_MODEL_5_0 = "5_0";
    public static final String SHADER_MODEL_5_1 = "5_1";
    public static final String SHADER_MODEL_6_0 = "6_0";
    public static final String SHADER_MODEL_6_1 = "6_1";
    public static final String SHADER_MODEL_6_2 = "6_2";
    public static final String SHADER_MODEL_6_3 = "6_3";
    public static final String SHADER_MODEL_6_4 = "6_4";
    public static final String SHADER_MODEL_6_5 = "6_5";
    public static final String SHADER_MODEL_6_6 = "6_6";

    // Compiler flags
    public static final int COMPILE_FLAG_NONE = 0x0;
    public static final int COMPILE_FLAG_DEBUG = 0x1;
    public static final int COMPILE_FLAG_SKIP_OPTIMIZATION = 0x2;
    public static final int COMPILE_FLAG_SKIP_VALIDATION = 0x4;
    public static final int COMPILE_FLAG_ENABLE_STRICTNESS = 0x8;
    public static final int COMPILE_FLAG_IEEE_STRICTNESS = 0x10;
    public static final int COMPILE_FLAG_OPTIMIZATION_LEVEL_0 = 0x20;
    public static final int COMPILE_FLAG_OPTIMIZATION_LEVEL_1 = 0x40;
    public static final int COMPILE_FLAG_OPTIMIZATION_LEVEL_2 = 0x60;
    public static final int COMPILE_FLAG_OPTIMIZATION_LEVEL_3 = 0x80;
    public static final int COMPILE_FLAG_PREFER_FLOW_CONTROL = 0x100;
    public static final int COMPILE_FLAG_PREFER_FLOW_CONTROL_EXPENSIVE = 0x200;
    public static final int COMPILE_FLAG_AVOID_FLOW_CONTROL = 0x400;
    public static final int COMPILE_FLAG_PART_PRECISION = 0x800;

    /**
     * Compiled shader information
     */
    public static class CompiledShader {
        private final long handle;
        private final byte[] bytecode;
        private final String entryPoint;
        private final String target;
        private final int shaderType;
        private final String compileLog;
        private final boolean compiledSuccessfully;
        private final long compilationTime;
        private final String debugName;

        public CompiledShader(long handle, byte[] bytecode, String entryPoint, String target,
                           int shaderType, String compileLog, boolean compiledSuccessfully,
                           long compilationTime, String debugName) {
            this.handle = handle;
            this.bytecode = bytecode != null ? Arrays.copyOf(bytecode, bytecode.length) : null;
            this.entryPoint = entryPoint;
            this.target = target;
            this.shaderType = shaderType;
            this.compileLog = compileLog;
            this.compiledSuccessfully = compiledSuccessfully;
            this.compilationTime = compilationTime;
            this.debugName = debugName;
        }

        public long getHandle() { return handle; }
        public byte[] getBytecode() { return bytecode != null ? Arrays.copyOf(bytecode, bytecode.length) : null; }
        public String getEntryPoint() { return entryPoint; }
        public String getTarget() { return target; }
        public int getShaderType() { return shaderType; }
        public String getCompileLog() { return compileLog; }
        public boolean isCompiledSuccessfully() { return compiledSuccessfully; }
        public long getCompilationTime() { return compilationTime; }
        public String getDebugName() { return debugName; }

        public int getBytecodeSize() {
            return bytecode != null ? bytecode.length : 0;
        }
    }

    // Compilation cache
    private final Map<String, CompiledShader> shaderCache;
    private final Map<String, String> preprocessorDefinitions;
    private final Set<String> includePaths;
    private boolean debugMode;
    private String currentShaderModel;

    public D3D12ShaderCompiler() {
        this.shaderCache = new HashMap<>();
        this.preprocessorDefinitions = new HashMap<>();
        this.includePaths = new HashSet<>();
        this.debugMode = false;
        this.currentShaderModel = SHADER_MODEL_6_0;

        // Add common preprocessor definitions
        addPreprocessorDefinition("DIRECTX12", "1");
        addPreprocessorDefinition("DX12", "1");
        addPreprocessorDefinition("HLSL", "1");
    }

    /**
     * Set debug mode
     */
    public void setDebugMode(boolean enabled) {
        this.debugMode = enabled;
    }

    /**
     * Set shader model
     */
    public void setShaderModel(String shaderModel) {
        this.currentShaderModel = shaderModel;
    }

    /**
     * Add preprocessor definition
     */
    public void addPreprocessorDefinition(String name, String value) {
        preprocessorDefinitions.put(name, value);
    }

    /**
     * Remove preprocessor definition
     */
    public void removePreprocessorDefinition(String name) {
        preprocessorDefinitions.remove(name);
    }

    /**
     * Add include path
     */
    public void addIncludePath(String path) {
        includePaths.add(path);
    }

    /**
     * Remove include path
     */
    public void removeIncludePath(String path) {
        includePaths.remove(path);
    }

    /**
     * Compile HLSL shader from source
     */
    public CompiledShader compileShader(String source, String entryPoint, int shaderType) {
        return compileShader(source, entryPoint, shaderType, null);
    }

    /**
     * Compile HLSL shader from source with debug name
     */
    public CompiledShader compileShader(String source, String entryPoint, int shaderType, String debugName) {
        String target = getShaderTarget(shaderType);
        return compileShader(source, entryPoint, target, shaderType, debugName);
    }

    /**
     * Compile HLSL shader from source with specific target
     */
    public CompiledShader compileShader(String source, String entryPoint, String target, int shaderType, String debugName) {
        // Generate cache key
        String cacheKey = generateCacheKey(source, entryPoint, target, shaderType);

        // Check cache first
        CompiledShader cached = shaderCache.get(cacheKey);
        if (cached != null) {
            LOGGER.debug("Using cached shader: {}", debugName);
            return cached;
        }

        LOGGER.info("Compiling {} shader: {}, entry={}, target={}, model={}",
            shaderTypeName(shaderType), debugName, entryPoint, target, currentShaderModel);

        long startTime = System.nanoTime();

        // Prepare shader source with preprocessor definitions
        String preparedSource = prepareShaderSource(source);

        // Compile shader using native method
        byte[] bytecode = compileHLSLToBytecode(preparedSource, entryPoint, target);
        String compileLog = getCompileLog();
        boolean compiledSuccessfully = (bytecode != null && bytecode.length > 0);

        long compilationTime = System.nanoTime() - startTime;
        long handle = 0;

        if (compiledSuccessfully) {
            // Create shader handle
            handle = createShaderHandle(bytecode, shaderType);

            if (handle == 0) {
                compiledSuccessfully = false;
                compileLog += "\nFailed to create shader handle";
                bytecode = null;
            }
        }

        // Create compiled shader object
        CompiledShader compiledShader = new CompiledShader(handle, bytecode, entryPoint, target,
                                                       shaderType, compileLog, compiledSuccessfully,
                                                       compilationTime, debugName);

        // Cache the result (even failed compilations to avoid repeated attempts)
        shaderCache.put(cacheKey, compiledShader);

        if (compiledSuccessfully) {
            LOGGER.info("Successfully compiled {} shader: {}, bytecode={}, time={}ms",
                shaderTypeName(shaderType), debugName, bytecode.length, compilationTime / 1_000_000);

            // Set debug name if provided
            if (debugName != null && handle != 0) {
                VitraD3D12Native.setResourceDebugName(handle, debugName);
            }
        } else {
            LOGGER.error("Failed to compile {} shader: {}, errors:\n{}",
                shaderTypeName(shaderType), debugName, compileLog);
        }

        return compiledShader;
    }

    /**
     * Preload common Minecraft shaders
     */
    public void preloadMinecraftShaders() {
        LOGGER.info("Preloading common Minecraft shaders");

        // Define common Minecraft shaders
        String[] vertexShaders = {
            "pos_tex_color", "rendertype_cutout", "rendertype_cutout_mipped",
            "rendertype_solid", "rendertype_translucent", "rendertype_entity_cutout",
            "rendertype_entity_smooth_cutout", "rendertype_beacon_beam",
            "rendertype_crystal", "rendertype_end_portal", "rendertype_entity_solid",
            "rendertype_entity_translucent", "rendertype_entity_decal",
            "rendertype_energy_swirl", "rendertype_leash", "rendertype_lightning",
            "rendertype_entity_no_outline", "rendertype_entity_shadow"
        };

        String[] fragmentShaders = {
            "rendertype_cutout", "rendertype_cutout_mipped", "rendertype_solid",
            "rendertype_translucent", "rendertype_entity_cutout", "rendertype_entity_smooth_cutout",
            "rendertype_beacon_beam", "rendertype_crystal", "rendertype_end_portal",
            "rendertype_entity_solid", "rendertype_entity_translucent", "rendertype_entity_decal",
            "rendertype_energy_swirl", "rendertype_leash", "rendertype_lightning",
            "rendertype_blit_screen", "rendertype_blit_to_noise"
        };

        // These would be loaded from actual shader files in a real implementation
        // For now, we'll just log what would be compiled
        LOGGER.info("Would compile {} vertex shaders and {} fragment shaders",
            vertexShaders.length, fragmentShaders.length);

        // Precompilation would go here in a real implementation
    }

    /**
     * Get shader target for shader type
     */
    private String getShaderTarget(int shaderType) {
        String prefix;
        switch (shaderType) {
            case SHADER_TYPE_VERTEX:
                prefix = "vs_";
                break;
            case SHADER_TYPE_PIXEL:
                prefix = "ps_";
                break;
            case SHADER_TYPE_GEOMETRY:
                prefix = "gs_";
                break;
            case SHADER_TYPE_HULL:
                prefix = "hs_";
                break;
            case SHADER_TYPE_DOMAIN:
                prefix = "ds_";
                break;
            case SHADER_TYPE_COMPUTE:
                prefix = "cs_";
                break;
            case SHADER_TYPE_MESH:
                prefix = "ms_";
                break;
            case SHADER_TYPE_AMPLIFICATION:
                prefix = "as_";
                break;
            case SHADER_TYPE_RAYTRACING:
                prefix = "lib_"; // Raytracing uses library targets
                break;
            default:
                throw new IllegalArgumentException("Unknown shader type: " + shaderType);
        }

        return prefix + currentShaderModel;
    }

    /**
     * Prepare shader source with preprocessor definitions
     */
    private String prepareShaderSource(String source) {
        StringBuilder prepared = new StringBuilder();

        // Add preprocessor definitions
        for (Map.Entry<String, String> entry : preprocessorDefinitions.entrySet()) {
            prepared.append("#define ").append(entry.getKey()).append(" ").append(entry.getValue()).append("\n");
        }

        // Add common includes
        prepared.append("#include <d3d12.h>\n");
        prepared.append("#include <dxgi.h>\n");

        // Add include paths
        for (String includePath : includePaths) {
            prepared.append("#include \"").append(includePath).append("\"\n");
        }

        // Add original source
        prepared.append("\n");
        prepared.append(source);

        return prepared.toString();
    }

    /**
     * Generate cache key for shader compilation
     */
    private String generateCacheKey(String source, String entryPoint, String target, int shaderType) {
        // Create a hash based on source, entry point, target, and definitions
        StringBuilder keyBuilder = new StringBuilder();
        keyBuilder.append(source).append("|");
        keyBuilder.append(entryPoint).append("|");
        keyBuilder.append(target).append("|");
        keyBuilder.append(shaderType).append("|");

        // Include preprocessor definitions in key
        List<String> sortedDefinitions = new ArrayList<>(preprocessorDefinitions.keySet());
        Collections.sort(sortedDefinitions);
        for (String def : sortedDefinitions) {
            keyBuilder.append(def).append("=").append(preprocessorDefinitions.get(def)).append(";");
        }

        return keyBuilder.toString();
    }

    /**
     * Compile HLSL to bytecode using native method
     */
    private byte[] compileHLSLToBytecode(String source, String entryPoint, String target) {
        // Convert string to bytes for native method
        byte[] sourceBytes = source.getBytes(StandardCharsets.UTF_8);
        return compileHLSLShaderNative(sourceBytes, entryPoint, target, getCompilerFlags());
    }

    /**
     * Get compiler flags based on current settings
     */
    private int getCompilerFlags() {
        int flags = COMPILE_FLAG_ENABLE_STRICTNESS;

        if (debugMode) {
            flags |= COMPILE_FLAG_DEBUG | COMPILE_FLAG_SKIP_OPTIMIZATION;
        } else {
            flags |= COMPILE_FLAG_OPTIMIZATION_LEVEL_3; // Full optimization
        }

        return flags;
    }

    /**
     * Create shader handle from bytecode
     */
    private long createShaderHandle(byte[] bytecode, int shaderType) {
        return VitraD3D12Native.createShader(bytecode, shaderType);
    }

    /**
     * Get compilation log from native compiler
     */
    private String getCompileLog() {
        // This would need to be implemented in native code
        return "Compilation log not available";
    }

    /**
     * Get shader type name for logging
     */
    private static String shaderTypeName(int shaderType) {
        switch (shaderType) {
            case SHADER_TYPE_VERTEX: return "Vertex";
            case SHADER_TYPE_PIXEL: return "Pixel";
            case SHADER_TYPE_GEOMETRY: return "Geometry";
            case SHADER_TYPE_HULL: return "Hull";
            case SHADER_TYPE_DOMAIN: return "Domain";
            case SHADER_TYPE_COMPUTE: return "Compute";
            case SHADER_TYPE_MESH: return "Mesh";
            case SHADER_TYPE_AMPLIFICATION: return "Amplification";
            case SHADER_TYPE_RAYTRACING: return "Raytracing";
            default: return "Unknown(" + shaderType + ")";
        }
    }

    /**
     * Clear shader cache
     */
    public void clearCache() {
        // Release shader handles before clearing cache
        for (CompiledShader shader : shaderCache.values()) {
            if (shader.getHandle() != 0) {
                VitraD3D12Native.releaseManagedResource(shader.getHandle());
            }
        }

        shaderCache.clear();
        LOGGER.info("Cleared shader cache");
    }

    /**
     * Get cache statistics
     */
    public String getCacheStats() {
        int totalShaders = shaderCache.size();
        int successfulShaders = (int) shaderCache.values().stream()
                .filter(CompiledShader::isCompiledSuccessfully)
                .count();
        int failedShaders = totalShaders - successfulShaders;

        long totalBytecode = shaderCache.values().stream()
                .filter(CompiledShader::isCompiledSuccessfully)
                .mapToLong(CompiledShader::getBytecodeSize)
                .sum();

        double averageCompilationTime = shaderCache.values().stream()
                .filter(CompiledShader::isCompiledSuccessfully)
                .mapToLong(CompiledShader::getCompilationTime)
                .average()
                .orElse(0.0);

        StringBuilder stats = new StringBuilder();
        stats.append("D3D12 Shader Compiler Statistics:\n");
        stats.append("  Total Shaders: ").append(totalShaders).append("\n");
        stats.append("  Successful: ").append(successfulShaders).append("\n");
        stats.append("  Failed: ").append(failedShaders).append("\n");
        stats.append("  Total Bytecode: ").append(totalBytecode / 1024).append(" KB\n");
        stats.append("  Average Compilation Time: ").append(String.format("%.2f", averageCompilationTime / 1_000_000)).append(" ms\n");
        stats.append("  Shader Model: ").append(currentShaderModel).append("\n");
        stats.append("  Debug Mode: ").append(debugMode).append("\n");

        return stats.toString();
    }

    /**
     * Release all compiled shaders
     */
    public void release() {
        clearCache();
        LOGGER.info("Shader compiler released");
    }

    /**
     * Native method stubs - would need to be implemented in native D3D12 code
     */
    private static native byte[] compileHLSLShaderNative(byte[] source, String entryPoint, String target, int flags);
}