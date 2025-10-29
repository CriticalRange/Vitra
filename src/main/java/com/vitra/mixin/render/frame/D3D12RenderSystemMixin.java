package com.vitra.mixin.render.frame;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexSorting;
import com.vitra.core.VitraCore;
import com.vitra.render.VitraRenderer;
import com.vitra.render.d3d12.*;
import com.vitra.render.jni.VitraD3D12Native;
import com.vitra.render.jni.VitraD3D12Renderer;
import org.joml.Matrix4f;
import org.joml.Matrix4fStack;
import org.joml.Vector3f;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static com.mojang.blaze3d.systems.RenderSystem.*;

/**
 * D3D12-specific RenderSystem mixin inspired by VulkanMod's render system integration
 * Provides DirectX 12 backend integration with comprehensive shader and state management
 */
@Mixin(RenderSystem.class)
public abstract class D3D12RenderSystemMixin {
    private static final Logger LOGGER = LoggerFactory.getLogger("Vitra/D3D12RenderSystem");

    // D3D12 system components (following VulkanMod's modular approach)
    private static D3D12AdapterSelector adapterSelector;
    private static D3D12MemoryManager memoryManager;
    private static D3D12TextureManager textureManager;
    private static D3D12ShaderCompiler shaderCompiler;
    private static D3D12RootSignatureManager rootSignatureManager;
    private static D3D12CommandManager commandManager;
    private static D3D12DescriptorHeapManager descriptorHeapManager;
    private static D3D12PipelineManager pipelineManager;

    // Current rendering state
    private static boolean d3d12Initialized = false;
    private static long currentPipeline = 0;
    private static Matrix4f lastProjectionMatrix = new Matrix4f();
    private static Matrix4f lastModelViewMatrix = new Matrix4f();

    @Shadow private static Matrix4f projectionMatrix;
    @Shadow private static Matrix4f savedProjectionMatrix;
    @Shadow @Final private static Matrix4fStack modelViewStack;
    @Shadow private static Matrix4f modelViewMatrix;
    @Shadow private static Matrix4f textureMatrix;
    @Shadow @Final private static float[] shaderColor;
    @Shadow @Final private static Vector3f[] shaderLightDirections;
    @Shadow @Final private static float[] shaderFogColor;
    @Shadow private static @Nullable Thread renderThread;
    @Shadow public static VertexSorting vertexSorting;
    @Shadow private static VertexSorting savedVertexSorting;

    @Shadow public static void assertOnRenderThread() {}

    /**
     * Initialize D3D12 rendering systems following VulkanMod's comprehensive approach
     */
    private static void initializeD3D12Systems() {
        if (d3d12Initialized) {
            return;
        }

        LOGGER.info("Initializing D3D12 rendering systems...");

        try {
            VitraCore core = VitraCore.getInstance();
            if (core == null) {
                LOGGER.error("VitraCore not available, cannot initialize D3D12");
                return;
            }

            // Initialize adapter selection (VulkanMod pattern: intelligent GPU selection)
            adapterSelector = new D3D12AdapterSelector(core.getConfig());
            D3D12AdapterSelector.D3D12Adapter selectedAdapter = adapterSelector.selectBestAdapter();
            if (selectedAdapter == null) {
                LOGGER.error("No suitable D3D12 adapter found");
                return;
            }

            // Create D3D12 device with selected adapter
            boolean debugMode = core.getConfig().isDebugMode();
            boolean deviceCreated = VitraD3D12Native.createDeviceWithAdapter(
                selectedAdapter.index, debugMode);
            if (!deviceCreated) {
                LOGGER.error("Failed to create D3D12 device");
                return;
            }

            // Initialize memory manager (VulkanMod pattern: VMA-style management)
            memoryManager = new D3D12MemoryManager();
            memoryManager.enableMemoryBudgeting(true);

            // Initialize descriptor heap manager (VulkanMod pattern: descriptor set management)
            descriptorHeapManager = new D3D12DescriptorHeapManager();

            // Initialize command manager (VulkanMod pattern: frame-based command buffer recycling)
            commandManager = new D3D12CommandManager();

            // Initialize shader compiler (VulkanMod pattern: SPIR-V compilation equivalent)
            shaderCompiler = new D3D12ShaderCompiler();
            shaderCompiler.setDebugMode(debugMode);
            shaderCompiler.setShaderModel(D3D12ShaderCompiler.SHADER_MODEL_6_5);
            shaderCompiler.preloadMinecraftShaders();

            // Initialize root signature manager (VulkanMod pattern: pipeline layout management)
            rootSignatureManager = new D3D12RootSignatureManager();

            // Initialize pipeline manager (VulkanMod pattern: pipeline state object caching)
            pipelineManager = new D3D12PipelineManager();

            // Initialize texture manager (VulkanMod pattern: texture lifecycle management)
            textureManager = new D3D12TextureManager(memoryManager, core.getConfig());

            d3d12Initialized = true;
            LOGGER.info("D3D12 rendering systems initialized successfully");
            LOGGER.info(adapterSelector.getAdapterStats());

        } catch (Exception e) {
            LOGGER.error("Failed to initialize D3D12 rendering systems", e);
        }
    }

    /**
     * @author Vitra (inspired by VulkanMod)
     * @reason Initialize D3D12 renderer with comprehensive system setup
     */
    @Overwrite(remap = false)
    public static void initRenderer(int debugVerbosity, boolean debugSync) {
        try {
            LOGGER.info("D3D12 RenderSystem.initRenderer() called");

            // Initialize D3D12 systems
            initializeD3D12Systems();

            if (!d3d12Initialized) {
                LOGGER.warn("D3D12 systems not initialized, falling back to DirectX");
                return;
            }

            // Initialize window and swap chain through native D3D12
            long windowHandle = getWindowHandle();
            if (windowHandle != 0) {
                VitraD3D12Renderer renderer = new VitraD3D12Renderer();
                boolean success = renderer.initializeWithWindowHandle(windowHandle);

                if (success) {
                    LOGGER.info("D3D12 renderer initialized successfully");

                    // Set up initial render state
                    setupD3D12InitialState();
                } else {
                    LOGGER.error("D3D12 renderer initialization failed");
                }
            }

            // Follow VulkanMod pattern: set render thread priority
            if (renderThread != null) {
                renderThread.setPriority(Thread.NORM_PRIORITY + 2);
            }

        } catch (Exception e) {
            LOGGER.error("D3D12 renderer initialization failed", e);
        }
    }

    /**
     * Set up initial D3D12 rendering state
     */
    private static void setupD3D12InitialState() {
        LOGGER.debug("Setting up D3D12 initial state");

        // Set up default viewport and scissors
        VitraD3D12Native.setViewport(0, 0, 800, 600, 0, 1);

        // Set up default rasterizer state
        VitraD3D12Native.setRasterizerState(
            D3D12PipelineManager.FILL_MODE_SOLID,
            D3D12PipelineManager.CULL_MODE_BACK,
            false, 0, 0.0f, 0.0f, true, false, false, 0, false
        );

        // Set up default depth stencil state
        VitraD3D12Native.setDepthStencilState(
            true, 1, D3D12PipelineManager.COMPARISON_LESS,
            false, (byte) 0, (byte) 0, 1, 1, 1, D3D12PipelineManager.COMPARISON_ALWAYS,
            1, 1, 1, D3D12PipelineManager.COMPARISON_ALWAYS
        );

        // Set up default blend state
        VitraD3D12Native.setBlendState(
            true, false,
            D3D12PipelineManager.BLEND_SRC_ALPHA, D3D12PipelineManager.BLEND_INV_SRC_ALPHA,
            D3D12PipelineManager.BLEND_OP_ADD,
            D3D12PipelineManager.BLEND_SRC_ALPHA, D3D12PipelineManager.BLEND_INV_SRC_ALPHA,
            D3D12PipelineManager.BLEND_OP_ADD,
            0xFFFFFFFF
        );

        LOGGER.debug("D3D12 initial state setup completed");
    }

    /**
     * Get window handle (would be provided by WindowMixin)
     */
    private static long getWindowHandle() {
        // This would be implemented to get the stored window handle
        // For now, return 0 as placeholder
        return 0;
    }

    /**
     * @author Vitra (inspired by VulkanMod)
     * @reason Replace OpenGL matrix operations with D3D12 equivalents
     */
    @Overwrite(remap = false)
    public static void setProjectionMatrix(Matrix4f projectionMatrix, VertexSorting vertexSorting) {
        if (!d3d12Initialized) {
            return;
        }

        // Store matrix for comparison
        D3D12RenderSystemMixin.projectionMatrix.set(projectionMatrix);
        savedVertexSorting = vertexSorting;

        // Only update if matrix actually changed
        if (!lastProjectionMatrix.equals(projectionMatrix)) {
            lastProjectionMatrix.set(projectionMatrix);

            // Upload to D3D12 constant buffer
            VitraD3D12Native.setProjectionMatrix(projectionMatrix.m00(), projectionMatrix.m01(),
                                                   projectionMatrix.m02(), projectionMatrix.m03(),
                                                   projectionMatrix.m10(), projectionMatrix.m11(),
                                                   projectionMatrix.m12(), projectionMatrix.m13(),
                                                   projectionMatrix.m20(), projectionMatrix.m21(),
                                                   projectionMatrix.m22(), projectionMatrix.m23(),
                                                   projectionMatrix.m30(), projectionMatrix.m31(),
                                                   projectionMatrix.m32(), projectionMatrix.m33());

            LOGGER.trace("Updated D3D12 projection matrix");
        }
    }

    /**
     * @author Vitra (inspired by VulkanMod)
     * @reason Replace OpenGL model-view matrix operations with D3D12 equivalents
     */
    @Overwrite(remap = false)
    public static void applyModelViewMatrix() {
        if (!d3d12Initialized) {
            return;
        }

        Matrix4f currentModelView = modelViewStack;
        D3D12RenderSystemMixin.modelViewMatrix.set(currentModelView);

        // Only update if matrix actually changed
        if (!lastModelViewMatrix.equals(currentModelView)) {
            lastModelViewMatrix.set(currentModelView);

            // Upload to D3D12 constant buffer
            VitraD3D12Native.setModelViewMatrix(currentModelView.m00(), currentModelView.m01(),
                                                currentModelView.m02(), currentModelView.m03(),
                                                currentModelView.m10(), currentModelView.m11(),
                                                currentModelView.m12(), currentModelView.m13(),
                                                currentModelView.m20(), currentModelView.m21(),
                                                currentModelView.m22(), currentModelView.m23(),
                                                currentModelView.m30(), currentModelView.m31(),
                                                currentModelView.m32(), currentModelView.m33());

            LOGGER.trace("Updated D3D12 model-view matrix");
        }
    }

    /**
     * @author Vitra (inspired by VulkanMod)
     * @reason Replace OpenGL texture matrix operations with D3D12 equivalents
     */
    @Overwrite(remap = false)
    public static void setTextureMatrix(Matrix4f textureMatrix) {
        if (!d3d12Initialized) {
            return;
        }

        D3D12RenderSystemMixin.textureMatrix.set(textureMatrix);

        // Upload to D3D12 constant buffer
        VitraD3D12Native.setTextureMatrix(textureMatrix.m00(), textureMatrix.m01(),
                                      textureMatrix.m02(), textureMatrix.m03(),
                                      textureMatrix.m10(), textureMatrix.m11(),
                                      textureMatrix.m12(), textureMatrix.m13(),
                                      textureMatrix.m20(), textureMatrix.m21(),
                                      textureMatrix.m22(), textureMatrix.m23(),
                                      textureMatrix.m30(), textureMatrix.m31(),
                                      textureMatrix.m32(), textureMatrix.m33());

        LOGGER.trace("Updated D3D12 texture matrix");
    }

    /**
     * @author Vitra (inspired by VulkanMod)
     * @reason Replace OpenGL shader light setup with D3D12 equivalents
     */
    @Overwrite(remap = false)
    public static void setShaderLights(Vector3f dir0, Vector3f dir1) {
        if (!d3d12Initialized) {
            return;
        }

        shaderLightDirections[0] = dir0;
        shaderLightDirections[1] = dir1;

        // Upload to D3D12 constant buffer
        VitraD3D12Native.setShaderLightDirection(0, dir0.x(), dir0.y(), dir0.z());
        VitraD3D12Native.setShaderLightDirection(1, dir1.x(), dir1.y(), dir1.z());

        LOGGER.trace("Updated D3D12 shader light directions");
    }

    /**
     * @author Vitra (inspired by VulkanMod)
     * @reason Replace OpenGL shader color setup with D3D12 equivalents
     */
    @Overwrite(remap = false)
    private static void _setShaderColor(float r, float g, float b, float a) {
        if (!d3d12Initialized) {
            return;
        }

        shaderColor[0] = r;
        shaderColor[1] = g;
        shaderColor[2] = b;
        shaderColor[3] = a;

        // Upload to D3D12 constant buffer
        VitraD3D12Native.setShaderColor(r, g, b, a);

        LOGGER.trace("Updated D3D12 shader color: ({}, {}, {}, {})", r, g, b, a);
    }

    /**
     * @author Vitra (inspired by VulkanMod)
     * @reason Replace OpenGL shader fog setup with D3D12 equivalents
     */
    @Overwrite(remap = false)
    public static void setShaderFogColor(float r, float g, float b) {
        if (!d3d12Initialized) {
            return;
        }

        shaderFogColor[0] = r;
        shaderFogColor[1] = g;
        shaderFogColor[2] = b;

        // Upload to D3D12 constant buffer
        VitraD3D12Native.setShaderFogColor(r, g, b);

        LOGGER.trace("Updated D3D12 shader fog color: ({}, {}, {})", r, g, b);
    }

    /**
     * @author Vitra (inspired by VulkanMod)
     * @reason Replace OpenGL frame end with D3D12 Present()
     */
    @Redirect(method = "flipFrame", at = @At(value = "INVOKE", target = "Lorg/lwjgl/glfw/GLFW;glfwSwapBuffers(J)V"), remap = false)
    private static void endFrame(long window) {
        if (d3d12Initialized) {
            try {
                // Present the frame through D3D12
                VitraD3D12Native.presentFrame();

                // Begin new frame
                if (commandManager != null) {
                    commandManager.beginFrame();
                }

                // Clean up resources
                if (memoryManager != null) {
                    memoryManager.beginFrame();
                }

                if (textureManager != null) {
                    textureManager.cleanup();
                }

                LOGGER.trace("D3D12 frame presented and new frame begun");
            } catch (Exception e) {
                LOGGER.error("Failed to present D3D12 frame", e);
            }
        }
    }

    /**
     * @author Vitra (inspired by VulkanMod)
     * @reason Replace OpenGL max texture size query with D3D12 equivalent
     */
    @Overwrite(remap = false)
    public static int maxSupportedTextureSize() {
        if (d3d12Initialized && textureManager != null) {
            return textureManager.getMaxTextureSize();
        }
        return VitraD3D12Native.getMaxTextureSize();
    }

    /**
     * Get D3D12 system statistics for debugging
     */
    private static String getD3D12Stats() {
        if (!d3d12Initialized) {
            return "D3D12 not initialized";
        }

        StringBuilder stats = new StringBuilder();
        stats.append("=== D3D12 System Statistics ===\n");

        if (adapterSelector != null) {
            stats.append(adapterSelector.getAdapterStats()).append("\n");
        }

        if (memoryManager != null) {
            stats.append(memoryManager.getStats()).append("\n");
        }

        if (commandManager != null) {
            stats.append(commandManager.getStats()).append("\n");
        }

        if (shaderCompiler != null) {
            stats.append(shaderCompiler.getCacheStats()).append("\n");
        }

        if (pipelineManager != null) {
            stats.append(pipelineManager.getStats()).append("\n");
        }

        if (textureManager != null) {
            stats.append(textureManager.getStats()).append("\n");
        }

        return stats.toString();
    }

    /**
     * Cleanup D3D12 systems
     */
    private static void cleanupD3D12Systems() {
        if (!d3d12Initialized) {
            return;
        }

        LOGGER.info("Cleaning up D3D12 systems...");

        try {
            if (pipelineManager != null) {
                pipelineManager.cleanup();
            }

            if (textureManager != null) {
                textureManager.cleanup();
            }

            if (commandManager != null) {
                commandManager.cleanup();
            }

            if (rootSignatureManager != null) {
                rootSignatureManager.clearCache();
            }

            if (shaderCompiler != null) {
                shaderCompiler.release();
            }

            if (descriptorHeapManager != null) {
                descriptorHeapManager.cleanup();
            }

            if (memoryManager != null) {
                memoryManager.release();
            }

            d3d12Initialized = false;
            LOGGER.info("D3D12 systems cleanup completed");

        } catch (Exception e) {
            LOGGER.error("Failed to cleanup D3D12 systems", e);
        }
    }
}