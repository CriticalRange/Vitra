package com.vitra.mixin.render.frame;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.systems.TimerQuery;
import net.minecraft.client.GraphicsStatus;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.main.GameConfig;
import com.vitra.core.VitraCore;
import com.vitra.render.texture.SpriteUpdateUtil;
import com.vitra.render.VitraRenderer;
import com.vitra.render.IVitraRenderer;
import com.vitra.VitraMod;
import org.jetbrains.annotations.Nullable;
import org.objectweb.asm.Opcodes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.LocalCapture;

import java.util.Optional;

@Mixin(Minecraft.class)
public class MinecraftMixin {
    private static final Logger LOGGER = LoggerFactory.getLogger("Vitra/MinecraftMixin");

    // Helper to get renderer instance (with null-safety check)
    // Returns null for D3D12 (which doesn't need GL compatibility layer)
    @Nullable
    private static VitraRenderer getRenderer() {
        IVitraRenderer baseRenderer = VitraMod.getRenderer();
        if (baseRenderer == null) {
            // Not yet initialized - this is expected during early initialization
            return null;
        }

        // If it's already a VitraRenderer (D3D11), return it directly
        if (baseRenderer instanceof VitraRenderer) {
            return (VitraRenderer) baseRenderer;
        }

        // For D3D12, return null (D3D12 doesn't use GL compatibility layer)
        // D3D12 handles rendering directly without going through GL emulation
        return null;
    }

    @Shadow @Final public Options options;

    /**
     * FIX #1: Pre-frame initialization (VulkanMod pattern)
     * Called BEFORE RenderSystem.clear() to prepare for new frame
     *
     * This prevents race conditions and ensures all per-frame resources are ready
     */
    @Inject(method = "runTick", at = @At("HEAD"))
    private void preFrameOps(boolean bl, CallbackInfo ci) {
        try {
            VitraRenderer renderer = getRenderer();
            if (renderer != null) {
                renderer.preInitFrame();
            }
        } catch (Exception e) {
            LOGGER.error("Failed to execute preInitFrame()", e);
        }
    }

    @Inject(method = "<init>", at = @At(value = "RETURN"))
    private void forceGraphicsMode(GameConfig gameConfig, CallbackInfo ci) {
        var graphicsModeOption = this.options.graphicsMode();

        if (graphicsModeOption.get() == GraphicsStatus.FABULOUS) {
            VitraCore.getLogger().info("Fabulous graphics mode not supported with Vitra renderer, forcing Fancy");
            graphicsModeOption.set(GraphicsStatus.FANCY);
        }
    }

    /**
     * Redirect RenderSystem.clear() to inject beginFrame() call
     * Based on VulkanMod's beginRender pattern
     *
     * CRITICAL FIX: This ensures the renderer's beginFrame() is called before each frame's rendering begins,
     * which sets up the viewport, clears the render target, and prepares for drawing.
     * Without this, the screen appears blank or with incorrect clear color.
     */
    @Redirect(method = "runTick", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/systems/RenderSystem;clear(IZ)V"))
    private void injectBeginFrame(int mask, boolean getError) {
        try {
            // Call original RenderSystem.clear() first
            RenderSystem.clear(mask, getError);

            // Then call renderer beginFrame to set up rendering state (renderer-agnostic)
            // This clears the render target with the correct color, sets viewport, and binds default shaders
            // Only for D3D11 - D3D12 handles frame management internally
            VitraRenderer renderer = getRenderer();
            if (renderer != null) {
                renderer.beginFrame();
            }

        } catch (Exception e) {
            LOGGER.error("Failed to inject renderer beginFrame()", e);
            e.printStackTrace();
            // Still call original to prevent total rendering failure
            RenderSystem.clear(mask, getError);
        }
    }

    @Redirect(method = "runTick", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/systems/TimerQuery;getInstance()Ljava/util/Optional;"))
    private Optional<TimerQuery> removeTimer() {
        // Vitra renderer doesn't need OpenGL timer queries
        return Optional.empty();
    }

    @Inject(method = "runTick", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Minecraft;tick()V"),
            locals = LocalCapture.CAPTURE_FAILHARD)
    private void redirectResourceTick(boolean bl, CallbackInfo ci, Runnable runnable, int i, int j) {
        int n = Math.min(10, i) - 1;
        boolean doUpload = j == n;
        SpriteUpdateUtil.setDoUpload(doUpload);
    }

    @Inject(method = "close", at = @At(value = "HEAD"))
    public void close(CallbackInfo ci) {
        // Wait for DirectX operations to complete
        IVitraRenderer renderer = VitraMod.getRenderer();
        if (renderer != null) {
            renderer.waitForGpuCommands();
        }
    }

    @Inject(method = "close", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/VirtualScreen;close()V"))
    public void close2(CallbackInfo ci) {
        // Clean up DirectX resources
        IVitraRenderer renderer = VitraMod.getRenderer();
        if (renderer != null) {
            renderer.shutdown();
        }
    }

    @Inject(method = "resizeDisplay", at = @At("HEAD"))
    public void onResolutionChanged(CallbackInfo ci) {
        // Get window size and trigger resize on renderer
        IVitraRenderer renderer = VitraMod.getRenderer();
        if (renderer != null) {
            Minecraft mc = (Minecraft)(Object)this;
            renderer.resize(mc.getWindow().getWidth(), mc.getWindow().getHeight());
        }
    }

    // Fixes crash when minimizing window before setScreen is called
    @Redirect(method = "setScreen", at = @At(value = "FIELD", target = "Lnet/minecraft/client/Minecraft;noRender:Z", opcode = Opcodes.PUTFIELD))
    private void keepVar(Minecraft instance, boolean value) {}
}
