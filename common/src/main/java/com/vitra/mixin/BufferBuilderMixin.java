package com.vitra.mixin;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.MeshData;
import com.vitra.render.bgfx.BgfxMeshDataHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Mixin to intercept MeshData creation from BufferBuilder and handle BGFX buffer upload
 */
@Mixin(BufferBuilder.class)
public class BufferBuilderMixin {
    private static final Logger LOGGER = LoggerFactory.getLogger("BufferBuilderMixin");

    /**
     * Intercept MeshData build to handle BGFX buffer upload
     */
    @Inject(method = "build", at = @At("RETURN"))
    private void onBuild(CallbackInfoReturnable<MeshData> cir) {
        MeshData meshData = cir.getReturnValue();
        if (meshData != null) {
            LOGGER.debug("*** INTERCEPTED MeshData creation from BufferBuilder.build()");
            BgfxMeshDataHandler.handleMeshData(meshData);
        }
    }

    /**
     * Intercept MeshData buildOrThrow to handle BGFX buffer upload
     */
    @Inject(method = "buildOrThrow", at = @At("RETURN"))
    private void onBuildOrThrow(CallbackInfoReturnable<MeshData> cir) {
        MeshData meshData = cir.getReturnValue();
        if (meshData != null) {
            LOGGER.debug("*** INTERCEPTED MeshData creation from BufferBuilder.buildOrThrow()");
            BgfxMeshDataHandler.handleMeshData(meshData);
        }
    }
}