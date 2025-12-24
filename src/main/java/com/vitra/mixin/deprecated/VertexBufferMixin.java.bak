package com.vitra.mixin.render.vertex;

import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexBuffer;
import net.minecraft.client.renderer.ShaderInstance;
import com.vitra.render.VBO;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(VertexBuffer.class)
public class VertexBufferMixin {

    private VBO vbo;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void constructor(VertexBuffer.Usage usage, CallbackInfo ci) {
        vbo = new VBO(usage);
    }

    @Redirect(method = "<init>", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/platform/GlStateManager;_glGenBuffers()I"))
    private int doNothing() {
        return 0;
    }

    @Redirect(method = "<init>", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/platform/GlStateManager;_glGenVertexArrays()I"))
    private int doNothing2() {
        return 0;
    }

    /**
     * @author
     */
    @Overwrite
    public void bind() {}

    /**
     * @author
     */
    @Overwrite
    public static void unbind() {}

    /**
     * @author
     */
    @Overwrite
    public void upload(MeshData meshData) {
        org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger("VertexBufferMixin");
        LOGGER.info("[VERTEX_BUFFER] upload() called with {} vertices", meshData.drawState().vertexCount());
        vbo.upload(meshData);
    }

    /**
     * @author
     */
    @Overwrite
    public void uploadIndexBuffer(ByteBufferBuilder.Result result) {
        org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger("VertexBufferMixin");
        LOGGER.info("[VERTEX_BUFFER] uploadIndexBuffer() called");
        vbo.uploadIndexBuffer(result.byteBuffer());
    }

    /**
     * @author
     */
    @Overwrite
    public void drawWithShader(Matrix4f viewMatrix, Matrix4f projectionMatrix, ShaderInstance shader) {
        org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger("VertexBufferMixin");
        LOGGER.info("[VERTEX_BUFFER] drawWithShader() called with shader: {}", shader.getName());
        vbo.drawWithShader(viewMatrix, projectionMatrix, shader);
    }

    /**
     * @author
     */
    @Overwrite
    public void draw() {
        org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger("VertexBufferMixin");
        LOGGER.info("[VERTEX_BUFFER] draw() called, delegating to VBO");
        vbo.draw();
    }

    /**
     * @author
     */
    @Overwrite
    public void close() {
        org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger("VertexBufferMixin");
        LOGGER.info("[VERTEX_BUFFER] close() called");
        vbo.close();
    }
}
