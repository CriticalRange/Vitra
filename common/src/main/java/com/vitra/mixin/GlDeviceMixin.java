package com.vitra.mixin;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.opengl.GlDevice;
import com.vitra.render.bgfx.BgfxGpuBuffer;
import com.vitra.VitraMod;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.lwjgl.bgfx.BGFX;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Mixin to replace OpenGL GlDevice initialization with BGFX D3D11
 */
@Mixin(GlDevice.class)
public class GlDeviceMixin {
    private static final Logger LOGGER = LoggerFactory.getLogger("VitraGlDeviceMixin");

    /**
     * Replace GlDevice constructor entirely with BGFX initialization
     */
    @Inject(method = "<init>", at = @At("HEAD"), cancellable = true)
    private void replaceBGFXGlDeviceInit(long window, int debugFlags, boolean synchronous, java.util.function.BiFunction contextFactory, boolean validateState, CallbackInfo ci) {
        LOGGER.info("Replacing GlDevice constructor with BGFX DirectX 11 initialization");

        try {
            // Ensure BGFX is initialized before we proceed
            if (VitraMod.getRenderer() != null && VitraMod.getRenderer().isInitialized()) {
                LOGGER.info("BGFX DirectX 11 already initialized - GlDevice constructor bypassed successfully");
            } else {
                LOGGER.warn("BGFX not yet initialized - allowing GlDevice constructor to proceed but it will fail gracefully");
            }

            // Cancel the OpenGL GlDevice constructor entirely
            // BGFX handles device management internally
            ci.cancel();

        } catch (Exception e) {
            LOGGER.error("Error in BGFX GlDevice replacement", e);
            // Don't cancel on error - let the original constructor run and fail naturally
        }
    }

    /**
     * Replace OpenGL buffer creation with BGFX buffer
     */
    @Inject(method = "createBuffer", at = @At("HEAD"), cancellable = true)
    private static void replaceOpenGLBufferWithBGFX(java.util.function.Supplier<String> nameSupplier, int usage, int size, CallbackInfoReturnable<GpuBuffer> cir) {
        LOGGER.info("Replacing OpenGL buffer creation with BGFX D3D11 buffer - usage: {}, size: {}", usage, size);

        try {
            // Determine buffer type based on usage flags and size
            BgfxGpuBuffer.BufferType bufferType;

            // Usage 136 is uniform buffer usage (from the bytecode analysis)
            // Small sizes (64, 128, 256) are typically uniform buffers
            if (usage == 136 || (size <= 512 && (size == 64 || size == 128 || size == 256 || size == 512))) {
                bufferType = BgfxGpuBuffer.BufferType.UNIFORM_BUFFER;
                LOGGER.debug("Detected uniform buffer: usage={}, size={}", usage, size);
            } else {
                bufferType = BgfxGpuBuffer.BufferType.DYNAMIC_VERTEX_BUFFER;
                LOGGER.debug("Using dynamic vertex buffer: usage={}, size={}", usage, size);
            }

            BgfxGpuBuffer bgfxBuffer = new BgfxGpuBuffer(size, bufferType);
            cir.setReturnValue(bgfxBuffer);
            LOGGER.debug("Successfully created BGFX buffer: {}", bgfxBuffer);

        } catch (Exception e) {
            LOGGER.error("Failed to create BGFX buffer, falling back to OpenGL", e);
            // Don't cancel - let OpenGL buffer creation proceed as fallback
        }
    }
}