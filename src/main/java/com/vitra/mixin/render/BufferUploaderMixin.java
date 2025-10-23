package com.vitra.mixin.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ShaderInstance;
import com.vitra.render.VBO;
import com.vitra.render.VitraRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

@Mixin(BufferUploader.class)
public abstract class BufferUploaderMixin {

    // Helper to get renderer instance (with null-safety check)
    private static VitraRenderer getRenderer() {
        VitraRenderer renderer = VitraRenderer.getInstance();
        if (renderer == null) {
            throw new IllegalStateException("VitraRenderer not initialized yet. Ensure renderer is initialized before OpenGL calls.");
        }
        return renderer;
    }

    /**
     * @author
     */
    @Overwrite
    public static void reset() {}

    /**
     * @author
     */
    @Overwrite
    public static void drawWithShader(MeshData meshData) {
        RenderSystem.assertOnRenderThread();

        MeshData.DrawState parameters = meshData.drawState();
        if (parameters.vertexCount() > 0) {
            ShaderInstance shaderInstance = RenderSystem.getShader();

            // Prevent drawing if formats don't match to avoid disturbing visual bugs
            if (shaderInstance.getVertexFormat() != parameters.format()) {
                meshData.close();
                return;
            }

            // Set primitive topology (renderer-agnostic)
            getRenderer().setPrimitiveTopology(parameters.mode().asGLMode);

            // Update shader uniforms
            shaderInstance.setDefaultUniforms(VertexFormat.Mode.QUADS, RenderSystem.getModelViewMatrix(),
                                              RenderSystem.getProjectionMatrix(), Minecraft.getInstance().getWindow());
            shaderInstance.apply();

            // Upload and draw using temporary VBO (immediate mode)
            VBO tempVbo = new VBO(com.mojang.blaze3d.vertex.VertexBuffer.Usage.DYNAMIC);
            tempVbo.upload(meshData);
            tempVbo.draw();
            tempVbo.close();
        }

        meshData.close();
    }

    /**
     * @author
     */
    @Overwrite
    public static void draw(MeshData meshData) {
        MeshData.DrawState parameters = meshData.drawState();

        if (parameters.vertexCount() > 0) {
            // Set primitive topology (renderer-agnostic)
            getRenderer().setPrimitiveTopology(parameters.mode().asGLMode);

            // Upload and draw using temporary VBO (immediate mode)
            VBO tempVbo = new VBO(com.mojang.blaze3d.vertex.VertexBuffer.Usage.DYNAMIC);
            tempVbo.upload(meshData);
            tempVbo.draw();
            tempVbo.close();
        }

        meshData.close();
    }

}
