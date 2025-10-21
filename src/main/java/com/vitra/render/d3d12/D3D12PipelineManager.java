package com.vitra.render.d3d12;

import com.vitra.render.jni.VitraD3D12Native;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * DirectX 12 Pipeline Manager inspired by VulkanMod's pipeline system
 * Provides graphics pipeline creation and caching with state tracking
 */
public class D3D12PipelineManager {
    private static final Logger LOGGER = LoggerFactory.getLogger("Vitra/D3D12PipelineManager");

    // Primitive topologies
    public static final int PRIMITIVE_TOPOLOGY_UNDEFINED = 0;
    public static final int PRIMITIVE_TOPOLOGY_POINTLIST = 1;
    public static final int PRIMITIVE_TOPOLOGY_LINELIST = 2;
    public static final int PRIMITIVE_TOPOLOGY_LINESTRIP = 3;
    public static final int PRIMITIVE_TOPOLOGY_TRIANGLELIST = 4;
    public static final int PRIMITIVE_TOPOLOGY_TRIANGLESTRIP = 5;

    // Fill modes
    public static final int FILL_MODE_SOLID = 0;
    public static final int FILL_MODE_WIREFRAME = 1;

    // Cull modes
    public static final int CULL_MODE_NONE = 1;
    public static final int CULL_MODE_FRONT = 2;
    public static final int CULL_MODE_BACK = 3;

    // Comparison functions
    public static final int COMPARISON_NEVER = 1;
    public static final int COMPARISON_LESS = 2;
    public static final int COMPARISON_EQUAL = 3;
    public static final int COMPARISON_LESS_EQUAL = 4;
    public static final int COMPARISON_GREATER = 5;
    public static final int COMPARISON_NOT_EQUAL = 6;
    public static final int COMPARISON_GREATER_EQUAL = 7;
    public static final int COMPARISON_ALWAYS = 8;

    // Blend operations
    public static final int BLEND_OP_ADD = 1;
    public static final int BLEND_OP_SUBTRACT = 2;
    public static final int BLEND_OP_REV_SUBTRACT = 3;
    public static final int BLEND_OP_MIN = 4;
    public static final int BLEND_OP_MAX = 5;

    // Blend factors
    public static final int BLEND_ZERO = 0;
    public static final int BLEND_ONE = 1;
    public static final int BLEND_SRC_COLOR = 2;
    public static final int BLEND_INV_SRC_COLOR = 3;
    public static final int BLEND_SRC_ALPHA = 4;
    public static final int BLEND_INV_SRC_ALPHA = 5;
    public static final int BLEND_DEST_ALPHA = 6;
    public static final int BLEND_INV_DEST_ALPHA = 7;
    public static final int BLEND_DEST_COLOR = 8;
    public static final int BLEND_INV_DEST_COLOR = 9;

    /**
     * Input element description
     */
    public static class InputElement {
        public final String semanticName;
        public final int semanticIndex;
        public final int format;
        public final int inputSlot;
        public final int alignedByteOffset;
        public final int inputSlotClass;
        public final int instanceDataStepRate;

        public InputElement(String semanticName, int semanticIndex, int format, int inputSlot,
                         int alignedByteOffset, int inputSlotClass, int instanceDataStepRate) {
            this.semanticName = semanticName;
            this.semanticIndex = semanticIndex;
            this.format = format;
            this.inputSlot = inputSlot;
            this.alignedByteOffset = alignedByteOffset;
            this.inputSlotClass = inputSlotClass;
            this.instanceDataStepRate = instanceDataStepRate;
        }
    }

    /**
     * Render target blend description
     */
    public static class RenderTargetBlend {
        public final boolean blendEnable;
        public final boolean logicOpEnable;
        public final int srcBlend;
        public final int destBlend;
        public final int blendOp;
        public final int srcBlendAlpha;
        public final int destBlendAlpha;
        public final int blendOpAlpha;
        public final int logicOp;
        public final boolean renderTargetWriteMask;

        public RenderTargetBlend(boolean blendEnable, boolean logicOpEnable, int srcBlend,
                            int destBlend, int blendOp, int srcBlendAlpha, int destBlendAlpha,
                            int blendOpAlpha, int logicOp, boolean renderTargetWriteMask) {
            this.blendEnable = blendEnable;
            this.logicOpEnable = logicOpEnable;
            this.srcBlend = srcBlend;
            this.destBlend = destBlend;
            this.blendOp = blendOp;
            this.srcBlendAlpha = srcBlendAlpha;
            this.destBlendAlpha = destBlendAlpha;
            this.blendOpAlpha = blendOpAlpha;
            this.logicOp = logicOp;
            this.renderTargetWriteMask = renderTargetWriteMask;
        }
    }

    /**
     * Rasterizer description
     */
    public static class RasterizerDesc {
        public final int fillMode;
        public final int cullMode;
        public final boolean frontCounterClockwise;
        public final int depthBias;
        public final float depthBiasClamp;
        public final float slopeScaledDepthBias;
        public final boolean depthClipEnable;
        public final boolean multisampleEnable;
        public final boolean antialiasedLineEnable;
        public final int forcedSampleCount;
        public final boolean conservativeRaster;

        public RasterizerDesc(int fillMode, int cullMode, boolean frontCounterClockwise,
                            int depthBias, float depthBiasClamp, float slopeScaledDepthBias,
                            boolean depthClipEnable, boolean multisampleEnable,
                            boolean antialiasedLineEnable, int forcedSampleCount,
                            boolean conservativeRaster) {
            this.fillMode = fillMode;
            this.cullMode = cullMode;
            this.frontCounterClockwise = frontCounterClockwise;
            this.depthBias = depthBias;
            this.depthBiasClamp = depthBiasClamp;
            this.slopeScaledDepthBias = slopeScaledDepthBias;
            this.depthClipEnable = depthClipEnable;
            this.multisampleEnable = multisampleEnable;
            this.antialiasedLineEnable = antialiasedLineEnable;
            this.forcedSampleCount = forcedSampleCount;
            this.conservativeRaster = conservativeRaster;
        }
    }

    /**
     * Depth stencil description
     */
    public static class DepthStencilDesc {
        public final boolean depthEnable;
        public final int depthWriteMask;
        public final int depthFunc;
        public final boolean stencilEnable;
        public final byte stencilReadMask;
        public final byte stencilWriteMask;
        public final int frontFaceFailOp;
        public final int frontFaceDepthFailOp;
        public final int frontFacePassOp;
        public final int frontFaceFunc;
        public final int backFaceFailOp;
        public final int backFaceDepthFailOp;
        public final int backFacePassOp;
        public final int backFaceFunc;

        public DepthStencilDesc(boolean depthEnable, int depthWriteMask, int depthFunc,
                            boolean stencilEnable, byte stencilReadMask, byte stencilWriteMask,
                            int frontFaceFailOp, int frontFaceDepthFailOp, int frontFacePassOp,
                            int frontFaceFunc, int backFaceFailOp, int backFaceDepthFailOp,
                            int backFacePassOp, int backFaceFunc) {
            this.depthEnable = depthEnable;
            this.depthWriteMask = depthWriteMask;
            this.depthFunc = depthFunc;
            this.stencilEnable = stencilEnable;
            this.stencilReadMask = stencilReadMask;
            this.stencilWriteMask = stencilWriteMask;
            this.frontFaceFailOp = frontFaceFailOp;
            this.frontFaceDepthFailOp = frontFaceDepthFailOp;
            this.frontFacePassOp = frontFacePassOp;
            this.frontFaceFunc = frontFaceFunc;
            this.backFaceFailOp = backFaceFailOp;
            this.backFaceDepthFailOp = backFaceDepthFailOp;
            this.backFacePassOp = backFacePassOp;
            this.backFaceFunc = backFaceFunc;
        }
    }

    /**
     * Sample description
     */
    public static class SampleDesc {
        public final int count;
        public final int quality;

        public SampleDesc(int count, int quality) {
            this.count = count;
            this.quality = quality;
        }
    }

    /**
     * Pipeline state definition
     */
    public static class PipelineStateDesc {
        public final D3D12RootSignatureManager.RootSignature rootSignature;
        public final long vertexShader;
        public final long pixelShader;
        public final long geometryShader;
        public final long hullShader;
        public final long domainShader;
        public final List<InputElement> inputElements;
        public final int primitiveTopologyType;
        public final RasterizerDesc rasterizer;
        public final DepthStencilDesc depthStencil;
        public final List<RenderTargetBlend> renderTargetBlends;
        public final int sampleMask;
        public final SampleDesc sampleDesc;
        public final int numRenderTargets;
        public final int[] rtvFormats;
        public final int dsvFormat;
        public final String debugName;

        public PipelineStateDesc(D3D12RootSignatureManager.RootSignature rootSignature,
                               long vertexShader, long pixelShader, long geometryShader,
                               long hullShader, long domainShader, List<InputElement> inputElements,
                               int primitiveTopologyType, RasterizerDesc rasterizer,
                               DepthStencilDesc depthStencil, List<RenderTargetBlend> renderTargetBlends,
                               int sampleMask, SampleDesc sampleDesc, int numRenderTargets,
                               int[] rtvFormats, int dsvFormat, String debugName) {
            this.rootSignature = rootSignature;
            this.vertexShader = vertexShader;
            this.pixelShader = pixelShader;
            this.geometryShader = geometryShader;
            this.hullShader = hullShader;
            this.domainShader = domainShader;
            this.inputElements = new ArrayList<>(inputElements);
            this.primitiveTopologyType = primitiveTopologyType;
            this.rasterizer = rasterizer;
            this.depthStencil = depthStencil;
            this.renderTargetBlends = new ArrayList<>(renderTargetBlends);
            this.sampleMask = sampleMask;
            this.sampleDesc = sampleDesc;
            this.numRenderTargets = numRenderTargets;
            this.rtvFormats = rtvFormats != null ? Arrays.copyOf(rtvFormats, rtvFormats.length) : null;
            this.dsvFormat = dsvFormat;
            this.debugName = debugName;
        }
    }

    /**
     * Pipeline state object
     */
    public static class PipelineState {
        private final long handle;
        private final PipelineStateDesc desc;
        private final long creationTime;
        private final boolean valid;
        private volatile int bindingCount = 0;

        public PipelineState(long handle, PipelineStateDesc desc, long creationTime, boolean valid) {
            this.handle = handle;
            this.desc = desc;
            this.creationTime = creationTime;
            this.valid = valid;
        }

        public long getHandle() { return handle; }
        public PipelineStateDesc getDesc() { return desc; }
        public long getCreationTime() { return creationTime; }
        public boolean isValid() { return valid; }
        public int getBindingCount() { return bindingCount; }
        public void incrementBindingCount() { bindingCount++; }

        public String getDebugName() { return desc.debugName; }
    }

    // Pipeline cache
    private final Map<String, PipelineState> pipelineCache;
    private final Map<Long, String> handleToName;
    private long nextPipelineId = 1;

    // Common pipeline configurations
    private final Map<String, PipelineStateDesc> commonPipelineConfigs;

    public D3D12PipelineManager() {
        this.pipelineCache = new ConcurrentHashMap<>();
        this.handleToName = new ConcurrentHashMap<>();
        this.commonPipelineConfigs = new HashMap<>();

        createCommonPipelineConfigs();
        LOGGER.info("D3D12 Pipeline Manager initialized with {} common configurations", commonPipelineConfigs.size());
    }

    /**
     * Create common pipeline configurations for Minecraft
     */
    private void createCommonPipelineConfigs() {
        // Create standard Minecraft 2D pipeline
        createMinecraft2DPipeline();

        // Create standard Minecraft 3D pipeline
        createMinecraft3DPipeline();

        // Create sky rendering pipeline
        createSkyPipeline();

        // Create translucent pipeline
        createTranslucentPipeline();

        // Create entity rendering pipeline
        createEntityPipeline();

        // Create GUI rendering pipeline
        createGUIPipeline();

        LOGGER.info("Created {} common pipeline configurations", commonPipelineConfigs.size());
    }

    /**
     * Create standard Minecraft 2D pipeline
     */
    private void createMinecraft2DPipeline() {
        List<InputElement> inputElements = Arrays.asList(
            new InputElement("POSITION", 0, DXGI_FORMAT_R32G32B32_FLOAT, 0, 0, 0, 0),
            new InputElement("TEXCOORD", 0, DXGI_FORMAT_R32G32_FLOAT, 0, 12, 0, 0),
            new InputElement("COLOR", 0, DXGI_FORMAT_R8G8B8A8_UNORM, 0, 20, 0, 0)
        );

        RasterizerDesc rasterizer = new RasterizerDesc(
            FILL_MODE_SOLID, CULL_MODE_BACK, false,
            0, 0.0f, 0.0f, true, false, false, 0, false
        );

        DepthStencilDesc depthStencil = new DepthStencilDesc(
            true, 1, COMPARISON_LESS,
            false, (byte) 0, (byte) 0, 1, 1, 1, COMPARISON_ALWAYS,
            1, 1, 1, COMPARISON_ALWAYS
        );

        List<RenderTargetBlend> renderTargetBlends = Arrays.asList(
            new RenderTargetBlend(
                true, false,
                BLEND_SRC_ALPHA, BLEND_INV_SRC_ALPHA, BLEND_OP_ADD,
                BLEND_ONE, BLEND_INV_SRC_ALPHA, BLEND_OP_ADD,
                0, true
            )
        );

        PipelineStateDesc desc = new PipelineStateDesc(
            null, 0, 0, 0, 0, 0,
            inputElements, PRIMITIVE_TOPOLOGY_TRIANGLELIST,
            rasterizer, depthStencil, renderTargetBlends,
            0xFFFFFFFF, new SampleDesc(1, 0), 1,
            new int[]{DXGI_FORMAT_R8G8B8A8_UNORM}, DXGI_FORMAT_D32_FLOAT,
            "Minecraft2D"
        );

        commonPipelineConfigs.put("Minecraft2D", desc);
    }

    /**
     * Create standard Minecraft 3D pipeline
     */
    private void createMinecraft3DPipeline() {
        List<InputElement> inputElements = Arrays.asList(
            new InputElement("POSITION", 0, DXGI_FORMAT_R32G32B32_FLOAT, 0, 0, 0, 0),
            new InputElement("TEXCOORD", 0, DXGI_FORMAT_R32G32_FLOAT, 0, 12, 0, 0),
            new InputElement("COLOR", 0, DXGI_FORMAT_R8G8B8A8_UNORM, 0, 20, 0, 0),
            new InputElement("NORMAL", 0, DXGI_FORMAT_R32G32B32_FLOAT, 0, 24, 0, 0)
        );

        RasterizerDesc rasterizer = new RasterizerDesc(
            FILL_MODE_SOLID, CULL_MODE_BACK, false,
            0, 0.0f, 0.0f, true, false, false, 0, false
        );

        DepthStencilDesc depthStencil = new DepthStencilDesc(
            true, 1, COMPARISON_LESS,
            false, (byte) 0, (byte) 0, 1, 1, 1, COMPARISON_ALWAYS,
            1, 1, 1, COMPARISON_ALWAYS
        );

        List<RenderTargetBlend> renderTargetBlends = Arrays.asList(
            new RenderTargetBlend(
                false, false,
                BLEND_ONE, BLEND_ZERO, BLEND_OP_ADD,
                BLEND_ONE, BLEND_ZERO, BLEND_OP_ADD,
                0, true
            )
        );

        PipelineStateDesc desc = new PipelineStateDesc(
            null, 0, 0, 0, 0, 0,
            inputElements, PRIMITIVE_TOPOLOGY_TRIANGLELIST,
            rasterizer, depthStencil, renderTargetBlends,
            0xFFFFFFFF, new SampleDesc(1, 0), 1,
            new int[]{DXGI_FORMAT_R8G8B8A8_UNORM}, DXGI_FORMAT_D32_FLOAT,
            "Minecraft3D"
        );

        commonPipelineConfigs.put("Minecraft3D", desc);
    }

    // Helper format constants
    private static final int DXGI_FORMAT_R32G32B32_FLOAT = 41;
    private static final int DXGI_FORMAT_R32G32_FLOAT = 16;
    private static final int DXGI_FORMAT_R8G8B8A8_UNORM = 87;
    private static final int DXGI_FORMAT_D32_FLOAT = 126;

    /**
     * Create sky rendering pipeline
     */
    private void createSkyPipeline() {
        List<InputElement> inputElements = Arrays.asList(
            new InputElement("POSITION", 0, DXGI_FORMAT_R32G32B32_FLOAT, 0, 0, 0, 0),
            new InputElement("TEXCOORD", 0, DXGI_FORMAT_R32G32_FLOAT, 0, 12, 0, 0)
        );

        RasterizerDesc rasterizer = new RasterizerDesc(
            FILL_MODE_SOLID, CULL_MODE_NONE, false,
            0, 0.0f, 0.0f, true, false, false, 0, false
        );

        DepthStencilDesc depthStencil = new DepthStencilDesc(
            true, 1, COMPARISON_LESS_EQUAL,
            false, (byte) 0, (byte) 0, 1, 1, 1, COMPARISON_ALWAYS,
            1, 1, 1, COMPARISON_ALWAYS
        );

        List<RenderTargetBlend> renderTargetBlends = Arrays.asList(
            new RenderTargetBlend(
                false, false,
                BLEND_ONE, BLEND_ZERO, BLEND_OP_ADD,
                BLEND_ONE, BLEND_ZERO, BLEND_OP_ADD,
                0, true
            )
        );

        PipelineStateDesc desc = new PipelineStateDesc(
            null, 0, 0, 0, 0, 0,
            inputElements, PRIMITIVE_TOPOLOGY_TRIANGLELIST,
            rasterizer, depthStencil, renderTargetBlends,
            0xFFFFFFFF, new SampleDesc(1, 0), 1,
            new int[]{DXGI_FORMAT_R8G8B8A8_UNORM}, DXGI_FORMAT_D32_FLOAT,
            "Sky"
        );

        commonPipelineConfigs.put("Sky", desc);
    }

    /**
     * Create translucent rendering pipeline
     */
    private void createTranslucentPipeline() {
        List<InputElement> inputElements = Arrays.asList(
            new InputElement("POSITION", 0, DXGI_FORMAT_R32G32B32_FLOAT, 0, 0, 0, 0),
            new InputElement("TEXCOORD", 0, DXGI_FORMAT_R32G32_FLOAT, 0, 12, 0, 0),
            new InputElement("COLOR", 0, DXGI_FORMAT_R8G8B8A8_UNORM, 0, 20, 0, 0)
        );

        RasterizerDesc rasterizer = new RasterizerDesc(
            FILL_MODE_SOLID, CULL_MODE_BACK, false,
            0, 0.0f, 0.0f, true, false, false, 0, false
        );

        DepthStencilDesc depthStencil = new DepthStencilDesc(
            true, 1, COMPARISON_LESS,
            false, (byte) 0, (byte) 0, 1, 1, 1, COMPARISON_ALWAYS,
            1, 1, 1, COMPARISON_ALWAYS
        );

        List<RenderTargetBlend> renderTargetBlends = Arrays.asList(
            new RenderTargetBlend(
                true, false,
                BLEND_SRC_ALPHA, BLEND_INV_SRC_ALPHA, BLEND_OP_ADD,
                BLEND_SRC_ALPHA, BLEND_INV_SRC_ALPHA, BLEND_OP_ADD,
                0, true
            )
        );

        PipelineStateDesc desc = new PipelineStateDesc(
            null, 0, 0, 0, 0, 0,
            inputElements, PRIMITIVE_TOPOLOGY_TRIANGLELIST,
            rasterizer, depthStencil, renderTargetBlends,
            0xFFFFFFFF, new SampleDesc(1, 0), 1,
            new int[]{DXGI_FORMAT_R8G8B8A8_UNORM}, DXGI_FORMAT_D32_FLOAT,
            "Translucent"
        );

        commonPipelineConfigs.put("Translucent", desc);
    }

    /**
     * Create entity rendering pipeline
     */
    private void createEntityPipeline() {
        List<InputElement> inputElements = Arrays.asList(
            new InputElement("POSITION", 0, DXGI_FORMAT_R32G32B32_FLOAT, 0, 0, 0, 0),
            new InputElement("TEXCOORD", 0, DXGI_FORMAT_R32G32_FLOAT, 0, 12, 0, 0),
            new InputElement("COLOR", 0, DXGI_FORMAT_R8G8B8A8_UNORM, 0, 20, 0, 0),
            new InputElement("NORMAL", 0, DXGI_FORMAT_R32G32B32_FLOAT, 0, 24, 0, 0)
        );

        RasterizerDesc rasterizer = new RasterizerDesc(
            FILL_MODE_SOLID, CULL_MODE_BACK, false,
            0, 0.0f, 0.0f, true, false, false, 0, false
        );

        DepthStencilDesc depthStencil = new DepthStencilDesc(
            true, 1, COMPARISON_LESS,
            false, (byte) 0, (byte) 0, 1, 1, 1, COMPARISON_ALWAYS,
            1, 1, 1, COMPARISON_ALWAYS
        );

        List<RenderTargetBlend> renderTargetBlends = Arrays.asList(
            new RenderTargetBlend(
                true, false,
                BLEND_SRC_ALPHA, BLEND_INV_SRC_ALPHA, BLEND_OP_ADD,
                BLEND_SRC_ALPHA, BLEND_INV_SRC_ALPHA, BLEND_OP_ADD,
                0, true
            )
        );

        PipelineStateDesc desc = new PipelineStateDesc(
            null, 0, 0, 0, 0, 0,
            inputElements, PRIMITIVE_TOPOLOGY_TRIANGLELIST,
            rasterizer, depthStencil, renderTargetBlends,
            0xFFFFFFFF, new SampleDesc(1, 0), 1,
            new int[]{DXGI_FORMAT_R8G8B8A8_UNORM}, DXGI_FORMAT_D32_FLOAT,
            "Entity"
        );

        commonPipelineConfigs.put("Entity", desc);
    }

    /**
     * Create GUI rendering pipeline
     */
    private void createGUIPipeline() {
        List<InputElement> inputElements = Arrays.asList(
            new InputElement("POSITION", 0, DXGI_FORMAT_R32G32_FLOAT, 0, 0, 0, 0),
            new InputElement("TEXCOORD", 0, DXGI_FORMAT_R32G32_FLOAT, 0, 8, 0, 0),
            new InputElement("COLOR", 0, DXGI_FORMAT_R8G8B8A8_UNORM, 0, 16, 0, 0)
        );

        RasterizerDesc rasterizer = new RasterizerDesc(
            FILL_MODE_SOLID, CULL_MODE_NONE, false,
            0, 0.0f, 0.0f, false, false, false, 0, false
        );

        DepthStencilDesc depthStencil = new DepthStencilDesc(
            false, 1, COMPARISON_ALWAYS,
            false, (byte) 0, (byte) 0, 1, 1, 1, COMPARISON_ALWAYS,
            1, 1, 1, COMPARISON_ALWAYS
        );

        List<RenderTargetBlend> renderTargetBlends = Arrays.asList(
            new RenderTargetBlend(
                true, false,
                BLEND_SRC_ALPHA, BLEND_INV_SRC_ALPHA, BLEND_OP_ADD,
                BLEND_SRC_ALPHA, BLEND_INV_SRC_ALPHA, BLEND_OP_ADD,
                0, true
            )
        );

        PipelineStateDesc desc = new PipelineStateDesc(
            null, 0, 0, 0, 0, 0,
            inputElements, PRIMITIVE_TOPOLOGY_TRIANGLELIST,
            rasterizer, depthStencil, renderTargetBlends,
            0xFFFFFFFF, new SampleDesc(1, 0), 1,
            new int[]{DXGI_FORMAT_R8G8B8A8_UNORM}, DXGI_FORMAT_UNKNOWN,
            "GUI"
        );

        commonPipelineConfigs.put("GUI", desc);
    }

    private static final int DXGI_FORMAT_UNKNOWN = 0;

    /**
     * Get common pipeline configuration
     */
    public PipelineStateDesc getCommonPipelineConfig(String name) {
        return commonPipelineConfigs.get(name);
    }

    /**
     * Create pipeline state from description
     */
    public PipelineState createPipeline(PipelineStateDesc desc) {
        if (desc == null) {
            return null;
        }

        // Check cache first
        String cacheKey = generatePipelineCacheKey(desc);
        PipelineState cached = pipelineCache.get(cacheKey);
        if (cached != null) {
            cached.incrementBindingCount();
            LOGGER.debug("Using cached pipeline: {}", desc.debugName);
            return cached;
        }

        LOGGER.info("Creating pipeline state: {}", desc.debugName);

        long startTime = System.nanoTime();

        // Serialize pipeline description for native code
        byte[] serializedDesc = serializePipelineStateDesc(desc);

        // Create pipeline state handle
        long handle = VitraD3D12Native.createGraphicsPipelineState(serializedDesc, desc.debugName);

        long creationTime = System.nanoTime() - startTime;
        boolean valid = (handle != 0);

        PipelineState pipeline = new PipelineState(handle, desc, creationTime, valid);

        if (valid) {
            pipelineCache.put(cacheKey, pipeline);
            handleToName.put(handle, desc.debugName);
            LOGGER.info("Successfully created pipeline: {}, handle=0x{}, time={}ms",
                desc.debugName, Long.toHexString(handle), creationTime / 1_000_000);
        } else {
            LOGGER.error("Failed to create pipeline: {}", desc.debugName);
        }

        return pipeline;
    }

    /**
     * Create pipeline from common configuration
     */
    public PipelineState createPipelineFromConfig(String configName, D3D12RootSignatureManager.RootSignature rootSignature,
                                             long vertexShader, long pixelShader) {
        PipelineStateDesc baseDesc = commonPipelineConfigs.get(configName);
        if (baseDesc == null) {
            LOGGER.error("Unknown pipeline configuration: {}", configName);
            return null;
        }

        // Create new description with specified shaders and root signature
        String debugName = configName + "_" + System.currentTimeMillis();
        PipelineStateDesc desc = new PipelineStateDesc(
            rootSignature, vertexShader, pixelShader, baseDesc.geometryShader,
            baseDesc.hullShader, baseDesc.domainShader, baseDesc.inputElements,
            baseDesc.primitiveTopologyType, baseDesc.rasterizer, baseDesc.depthStencil,
            baseDesc.renderTargetBlends, baseDesc.sampleMask, baseDesc.sampleDesc,
            baseDesc.numRenderTargets, baseDesc.rtvFormats, baseDesc.dsvFormat,
            debugName
        );

        return createPipeline(desc);
    }

    /**
     * Get pipeline by handle
     */
    public PipelineState getPipeline(long handle) {
        String name = handleToName.get(handle);
        return name != null ? pipelineCache.get(name) : null;
    }

    /**
     * Get pipeline by name
     */
    public PipelineState getPipeline(String name) {
        return pipelineCache.get(name);
    }

    /**
     * Remove pipeline from cache
     */
    public void removePipeline(String name) {
        PipelineState pipeline = pipelineCache.remove(name);
        if (pipeline != null && pipeline.getHandle() != 0) {
            handleToName.remove(pipeline.getHandle());
            VitraD3D12Native.releaseManagedResource(pipeline.getHandle());
            LOGGER.info("Released pipeline: {}", name);
        }
    }

    /**
     * Clear all pipelines
     */
    public void clearCache() {
        // Release all pipeline handles
        for (PipelineState pipeline : pipelineCache.values()) {
            if (pipeline.getHandle() != 0) {
                VitraD3D12Native.releaseManagedResource(pipeline.getHandle());
            }
        }

        pipelineCache.clear();
        handleToName.clear();
        LOGGER.info("Cleared pipeline cache");
    }

    /**
     * Generate cache key for pipeline description
     */
    private String generatePipelineCacheKey(PipelineStateDesc desc) {
        StringBuilder keyBuilder = new StringBuilder();
        keyBuilder.append(desc.debugName).append("|");
        keyBuilder.append(desc.primitiveTopologyType).append("|");
        keyBuilder.append(desc.rasterizer.cullMode).append("|");
        keyBuilder.append(desc.depthStencil.depthEnable).append("|");
        keyBuilder.append(desc.depthStencil.depthFunc).append("|");
        keyBuilder.append(desc.renderTargetBlends.size()).append("|");
        keyBuilder.append(desc.numRenderTargets).append("|");
        keyBuilder.append(desc.sampleDesc.count).append("|");
        keyBuilder.append(desc.sampleMask).append("|");

        if (desc.rtvFormats != null) {
            for (int format : desc.rtvFormats) {
                keyBuilder.append(format).append(",");
            }
        }

        return keyBuilder.toString();
    }

    /**
     * Serialize pipeline description for native code
     */
    private byte[] serializePipelineStateDesc(PipelineStateDesc desc) {
        // This would need to be implemented to serialize the pipeline description
        // to a format that the native code can understand
        // For now, return a placeholder
        return (desc.debugName != null ? desc.debugName : "").getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    /**
     * Get statistics
     */
    public String getStats() {
        int totalPipelines = pipelineCache.size();
        int validPipelines = (int) pipelineCache.values().stream()
                .filter(PipelineState::isValid)
                .count();

        long totalCreationTime = pipelineCache.values().stream()
                .filter(PipelineState::isValid)
                .mapToLong(PipelineState::getCreationTime)
                .sum();

        double averageCreationTime = pipelineCache.isEmpty() ? 0.0 :
                (double) totalCreationTime / validPipelines / 1_000_000;

        StringBuilder stats = new StringBuilder();
        stats.append("D3D12 Pipeline Manager Statistics:\n");
        stats.append("  Total Pipelines: ").append(totalPipelines).append("\n");
        stats.append("  Valid Pipelines: ").append(validPipelines).append("\n");
        stats.append("  Average Creation Time: ").append(String.format("%.2f", averageCreationTime)).append(" ms\n");
        stats.append("  Common Configurations: ").append(commonPipelineConfigs.size()).append("\n");

        stats.append("\n--- Common Configurations ---\n");
        for (String name : commonPipelineConfigs.keySet()) {
            stats.append("  ").append(name).append("\n");
        }

        return stats.toString();
    }

    /**
     * Cleanup resources
     */
    public void cleanup() {
        clearCache();
        LOGGER.info("D3D12 Pipeline Manager cleanup completed");
    }
}