package com.vitra.mixin;

import com.vitra.config.RendererType;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.List;
import java.util.Properties;
import java.util.Set;

/**
 * Mixin plugin for Vitra mod.
 * Provides dynamic mixin configuration and conditional loading.
 *
 * Conditionally disables OpenGL compatibility mixins (GL11Mixin, GL14Mixin, etc.)
 * when DirectX 12 is selected, as D3D12 renders directly without the GL compatibility layer.
 */
public class MixinPlugin implements IMixinConfigPlugin {

    private String rendererType = "DIRECTX12"; // Default

    @Override
    public void onLoad(String mixinPackage) {
        // Load config early to determine renderer type BEFORE mixins are applied
        // Mixins are applied at startup, before VitraConfig is initialized
        try {
            File configFile = new File("config/vitra.properties");
            if (configFile.exists()) {
                Properties properties = new Properties();
                try (FileInputStream fis = new FileInputStream(configFile)) {
                    properties.load(fis);
                    rendererType = properties.getProperty("renderer.type", "DIRECTX12");
                    System.out.println("[Vitra/MixinPlugin] Loaded renderer type from config: " + rendererType);
                }
            } else {
                System.out.println("[Vitra/MixinPlugin] Config file not found, using default renderer: " + rendererType);
            }
        } catch (IOException e) {
            System.err.println("[Vitra/MixinPlugin] Failed to load config, using default renderer: " + e.getMessage());
        }
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        // Disable OpenGL compatibility mixins for DirectX 12
        // D3D12 renders directly via RenderSystemMixin, BufferUploaderMixin, etc.
        // GL compatibility layer is only needed for D3D11
        if (mixinClassName.contains("compatibility.gl.GL")) {
            // Disable GL compat mixins for D3D12 (use instance variable loaded in onLoad())
            if (this.rendererType.contains("DIRECTX12") || this.rendererType.contains("D3D12")) {
                System.out.println("[Vitra/MixinPlugin] Disabling GL compatibility mixin for D3D12: " + mixinClassName);
                return false;
            }
        }

        // Allow all other mixins
        return true;
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
        // Called to inform the plugin of its targets
    }

    @Override
    public List<String> getMixins() {
        // Return null to use the mixins defined in the JSON config
        return null;
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
        // Called before mixin is applied
    }

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
        // Called after mixin is applied
    }
}
