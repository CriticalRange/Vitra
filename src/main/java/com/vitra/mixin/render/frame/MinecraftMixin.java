package com.vitra.mixin.render.frame;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.systems.TimerQuery;
import net.minecraft.client.GraphicsStatus;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.main.GameConfig;
import com.vitra.core.VitraCore;
import com.vitra.render.jni.VitraNativeRenderer;
import com.vitra.render.texture.SpriteUpdateUtil;
import com.vitra.render.VitraRenderer;
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

    @Shadow @Final public Options options;

    @Inject(method = "<init>", at = @At(value = "RETURN"))
    private void forceGraphicsMode(GameConfig gameConfig, CallbackInfo ci) {
        var graphicsModeOption = this.options.graphicsMode();

        if (graphicsModeOption.get() == GraphicsStatus.FABULOUS) {
            VitraCore.getLogger().info("Fabulous graphics mode not supported with DirectX 11, forcing Fancy");
            graphicsModeOption.set(GraphicsStatus.FANCY);
        }
    }

    /**
     * Redirect RenderSystem.clear() to inject beginFrame() call
     * Based on VulkanMod's beginRender pattern
     *
     * CRITICAL FIX: This ensures DirectX 11's beginFrame() is called before each frame's rendering begins,
     * which sets up the viewport, clears the render target, and prepares for drawing.
     * Without this, the screen appears blank or with incorrect clear color.
     */
    @Redirect(method = "runTick", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/systems/RenderSystem;clear(IZ)V"))
    private void injectBeginFrame(int mask, boolean getError) {
        // CRITICAL DEBUG: Log that redirect is working
        int callCount = 0;
        if (callCount < 3) {
            System.out.println("[JAVA] MinecraftMixin.injectBeginFrame() CALLED! Count=" + callCount);
            callCount++;
        }

        try {
            // Call original RenderSystem.clear() first
            RenderSystem.clear(mask, getError);

            // Then call DirectX 11 beginFrame to set up rendering state
            // This clears the render target with the correct color, sets viewport, and binds default shaders
            System.out.println("[JAVA] Calling VitraNativeRenderer.beginFrame()...");
            VitraNativeRenderer.beginFrame();
            System.out.println("[JAVA] VitraNativeRenderer.beginFrame() returned");

            LOGGER.trace("DirectX 11 beginFrame() called for new frame");

        } catch (Exception e) {
            LOGGER.error("Failed to inject DirectX 11 beginFrame()", e);
            e.printStackTrace();
            // Still call original to prevent total rendering failure
            RenderSystem.clear(mask, getError);
        }
    }

    @Redirect(method = "runTick", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/systems/TimerQuery;getInstance()Ljava/util/Optional;"))
    private Optional<TimerQuery> removeTimer() {
        // DirectX 11 doesn't need OpenGL timer queries
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
        // Wait for DirectX 11 operations to complete
        if (VitraRenderer.getRenderer() != null) {
            VitraRenderer.getRenderer().waitForIdle();
        }
    }

    @Inject(method = "close", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/VirtualScreen;close()V"))
    public void close2(CallbackInfo ci) {
        // Clean up DirectX 11 resources
        if (VitraRenderer.getRenderer() != null) {
            VitraRenderer.getRenderer().cleanup();
        }
    }

    @Inject(method = "resizeDisplay", at = @At("HEAD"))
    public void onResolutionChanged(CallbackInfo ci) {
        // Schedule DirectX 11 swapchain update
        if (VitraRenderer.getRenderer() != null) {
            VitraRenderer.getRenderer().scheduleResize();
        }
    }

    // Fixes crash when minimizing window before setScreen is called
    @Redirect(method = "setScreen", at = @At(value = "FIELD", target = "Lnet/minecraft/client/Minecraft;noRender:Z", opcode = Opcodes.PUTFIELD))
    private void keepVar(Minecraft instance, boolean value) {}
}
