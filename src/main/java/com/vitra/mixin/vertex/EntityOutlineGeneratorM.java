package com.vitra.mixin.vertex;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.OutlineBufferSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

// @Mixin(OutlineBufferSource.class) // DISABLED: BufferBuilder constructor changed in 1.21.1
public class EntityOutlineGeneratorM {

    // @Redirect(method = "getColoredOutlineVertexConsumer", at = @At(value = "NEW", target = "Lcom/mojang/blaze3d/vertex/BufferBuilder;")) // DISABLED
    private BufferBuilder redirectBufferBuilder(VertexFormat vertexFormat) {
        // BufferBuilder constructor changed in 1.21.1 - it now needs ByteBufferBuilder
        // For now, return null and let the game create its own buffer builder
        // This mixin can be updated later when we understand the new constructor better
        return null;
    }
}