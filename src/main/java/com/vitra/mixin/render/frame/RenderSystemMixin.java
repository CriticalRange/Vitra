package com.vitra.mixin.render.frame;

import com.mojang.blaze3d.ProjectionType;
import com.mojang.blaze3d.TracyFrameCapture;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderSystem;
import com.vitra.render.IVitraRenderer;
import com.vitra.render.VRenderSystem;
import com.vitra.render.VitraRenderer;
import com.vitra.VitraMod;
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

/**
 * Vitra unified RenderSystem mixin for Minecraft 26.1
 * Replaces OpenGL matrix operations with renderer-agnostic backend equivalents.
 * 
 * 26.1 API Changes:
 * - initRenderer() now takes GpuDevice instead of (int, boolean)
 * - flipFrame() now takes TracyFrameCapture
 * - setProjectionMatrix() now uses GpuBufferSlice + ProjectionType
 * - getShaderFogStart/End/Color/etc methods REMOVED
 * - New GpuBufferSlice system for uniform data
 */
@Mixin(RenderSystem.class)
public abstract class RenderSystemMixin {
    private static final Logger LOGGER = LoggerFactory.getLogger("Vitra/RenderSystem");

    // Helper to get renderer instance (with null-safety check)
    @Nullable
    private static VitraRenderer getRenderer() {
        IVitraRenderer baseRenderer = VitraMod.getRenderer();
        if (baseRenderer == null) {
            return null;
        }
        if (baseRenderer instanceof VitraRenderer) {
            return (VitraRenderer) baseRenderer;
        }
        return null;
    }

    @Shadow @Final private static Matrix4fStack modelViewStack;

    @Shadow private static @Nullable Thread renderThread;

    @Shadow
    public static boolean isOnRenderThread() { return false; }

    // Note: recordRenderCall removed in 26.1

    /**
     * @author Vitra (adapted from VulkanMod)
     * @reason Replace OpenGL renderer initialization with DirectX backend
     * 
     * 26.1 API Change: Now takes GpuDevice instead of (int debugVerbosity, boolean debugSync)
     * 
     * CRITICAL: This is called BEFORE the window is created!
     * We must create and store VitraGpuDevice immediately for getDevice() to work,
     * but defer actual D3D11 native initialization until the window exists.
     */
    @Overwrite(remap = false)
    public static void initRenderer(GpuDevice gpuDevice) {
        try {
            com.vitra.config.VitraConfig config = com.vitra.VitraMod.getConfig();
            com.vitra.config.RendererType rendererType = (config != null) ? config.getRendererType() : com.vitra.config.RendererType.DIRECTX11;

            LOGGER.info("RenderSystem.initRenderer() called - creating VitraGpuDevice for {} (D3D11 init deferred until window creation)", rendererType);

            // Create our DirectX-backed GpuDevice WITHOUT initializing D3D11 yet
            // Native D3D11 init will happen later when Window.constructor stores the handle
            com.vitra.render.bridge.VitraGpuDevice vitraDevice = new com.vitra.render.bridge.VitraGpuDevice();
            
            // Store the device in RenderSystem via reflection IMMEDIATELY
            // This is critical so getDevice() works before window creation
            try {
                java.lang.reflect.Field deviceField = RenderSystem.class.getDeclaredField("DEVICE");
                deviceField.setAccessible(true);
                deviceField.set(null, vitraDevice);
                LOGGER.info("✓ Stored VitraGpuDevice in RenderSystem.DEVICE (D3D11 init pending)");
            } catch (Exception e) {
                LOGGER.error("Failed to set DEVICE field via reflection", e);
                throw new RuntimeException("Failed to store VitraGpuDevice", e);
            }
            
            // Also initialize dynamicUniforms - required for getDynamicUniforms() to work
            try {
                java.lang.reflect.Field dynamicUniformsField = RenderSystem.class.getDeclaredField("dynamicUniforms");
                dynamicUniformsField.setAccessible(true);
                Object dynamicUniforms = dynamicUniformsField.get(null);
                if (dynamicUniforms == null) {
                    // Create new DynamicUniforms instance
                    Class<?> dynamicUniformsClass = Class.forName("net.minecraft.client.renderer.DynamicUniforms");
                    Object newDynamicUniforms = dynamicUniformsClass.getConstructor().newInstance();
                    dynamicUniformsField.set(null, newDynamicUniforms);
                    LOGGER.info("✓ Initialized DynamicUniforms");
                }
            } catch (Exception e) {
                LOGGER.warn("Failed to initialize dynamicUniforms via reflection: {}", e.getMessage());
            }

            // Set render thread priority
            if (renderThread != null) {
                renderThread.setPriority(Thread.NORM_PRIORITY + 2);
            }
        } catch (Exception e) {
            LOGGER.error("Vitra renderer initialization failed", e);
            throw new RuntimeException("Vitra initialization failed", e);
        }
    }

    /**
     * @author Vitra
     * @reason Replace OpenGL default state setup with DirectX backend
     * 
     * 26.1 API Change: Now takes no parameters (was x, y, width, height)
     */
    @Overwrite(remap = false)
    public static void setupDefaultState() {
        LOGGER.info("RenderSystem.setupDefaultState intercepted - Vitra JNI handles state internally");
        // NO-OP: Vitra JNI handles its own state setup
    }

    /**
     * Note: 26.1 flipFrame now takes TracyFrameCapture and doesn't use glfwSwapBuffers.
     * Frame presentation is handled differently. DirectX presentation hooks need to be
     * placed elsewhere (Window.updateDisplay or similar).
     */

    /**
     * Helper method to extract Matrix4f from the modelViewStack for DirectX uniform upload
     */
    private static Matrix4f getModelViewMatrixCopy() {
        return new Matrix4f(modelViewStack);
    }

    /**
     * Upload transform matrices to DirectX when model view changes
     */
    private static void uploadTransformMatricesToNative() {
        VitraRenderer renderer = getRenderer();
        if (renderer != null) {
            float[] mvpArray = new float[16];
            float[] mvArray = new float[16];
            float[] projArray = new float[16];

            // Read from VRenderSystem's computed matrices
            VRenderSystem.getMVP().byteBuffer().asFloatBuffer().get(0, mvpArray);
            VRenderSystem.getModelViewMatrix().byteBuffer().asFloatBuffer().get(0, mvArray);
            VRenderSystem.getProjectionMatrix().byteBuffer().asFloatBuffer().get(0, projArray);

            renderer.setTransformMatrices(mvpArray, mvArray, projArray);
        }
    }
}
