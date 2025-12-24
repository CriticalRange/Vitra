package com.vitra.render.shader;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * Utility class for loading shader resources and configurations.
 * Based on VulkanMod's shader loading approach.
 */
public class ShaderLoadUtil {
    private static final Logger LOGGER = LoggerFactory.getLogger(ShaderLoadUtil.class);

    /**
     * Load shader JSON configuration from resources.
     *
     * @param path Shader path (e.g., "core" for core shaders)
     * @param name Shader name
     * @return JsonObject containing shader configuration, or null if not found
     */
    public static JsonObject getJsonConfig(String path, String name) {
        try {
            Identifier resourceLocation = Identifier.parse("vitra:shaders/" + path + "/" + name + ".json");
            // Note: This requires a ResourceProvider instance, which we'll get from the mixin context
            // For now, we'll return null and handle this in the ShaderInstanceM mixin
            LOGGER.debug("Attempting to load shader config: {}", resourceLocation);
            return null;
        } catch (Exception e) {
            LOGGER.debug("Could not load shader config for {}/{}: {}", path, name, e.getMessage());
            return null;
        }
    }

    /**
     * Load shader JSON configuration from ResourceProvider.
     *
     * @param resourceProvider The resource provider
     * @param path Shader path
     * @param name Shader name
     * @return JsonObject containing shader configuration, or null if not found
     */
    public static JsonObject getJsonConfig(ResourceProvider resourceProvider, String path, String name) {
        try {
            Identifier resourceLocation = Identifier.parse("vitra:shaders/" + path + "/" + name + ".json");
            Resource resource = resourceProvider.getResource(resourceLocation).orElse(null);

            if (resource == null) {
                LOGGER.debug("Shader config not found: {}", resourceLocation);
                return null;
            }

            try (InputStream inputStream = resource.open();
                 InputStreamReader reader = new InputStreamReader(inputStream, StandardCharsets.UTF_8)) {
                return JsonParser.parseReader(reader).getAsJsonObject();
            }
        } catch (Exception e) {
            LOGGER.debug("Could not load shader config for {}/{}: {}", path, name, e.getMessage());
            return null;
        }
    }

    /**
     * Load shader source from ResourceProvider.
     *
     * @param resourceProvider The resource provider
     * @param resourceLocation The shader resource location
     * @return Shader source code as string
     */
    public static String loadShaderSource(ResourceProvider resourceProvider, Identifier resourceLocation) {
        // Try classpath loading first (VulkanMod approach) - works in dev and production
        String classpathPath = "/assets/" + resourceLocation.getNamespace() + "/" + resourceLocation.getPath();

        try (InputStream stream = ShaderLoadUtil.class.getResourceAsStream(classpathPath)) {
            if (stream != null) {
                LOGGER.debug("Loaded shader from classpath: {}", classpathPath);
                return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            }
        } catch (Exception e) {
            LOGGER.debug("Failed to load from classpath: {}", classpathPath, e);
        }

        // Fallback to ResourceProvider (for resource packs)
        try {
            Resource resource = resourceProvider.getResourceOrThrow(resourceLocation);
            try (InputStream inputStream = resource.open()) {
                LOGGER.debug("Loaded shader from ResourceProvider: {}", resourceLocation);
                return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
            }
        } catch (Exception e) {
            LOGGER.error("Failed to load shader source: {}", resourceLocation, e);
            throw new RuntimeException("Failed to load shader: " + resourceLocation, e);
        }
    }

    /**
     * Load shader source with fallback.
     *
     * @param resourceProvider The resource provider
     * @param primaryLocation Primary resource location to try
     * @param fallbackLocation Fallback resource location if primary fails
     * @return Shader source code as string
     */
    public static String loadShaderSourceWithFallback(ResourceProvider resourceProvider,
                                                      Identifier primaryLocation,
                                                      Identifier fallbackLocation) {
        try {
            return loadShaderSource(resourceProvider, primaryLocation);
        } catch (Exception e) {
            LOGGER.warn("Failed to load shader from {}, trying fallback: {}", primaryLocation, fallbackLocation);
            return loadShaderSource(resourceProvider, fallbackLocation);
        }
    }

    /**
     * Parse shader path from shader name.
     * Handles both simple names (e.g., "position") and namespaced names (e.g., "minecraft:position").
     *
     * @param name Shader name
     * @param extension File extension (.vsh, .fsh, etc.)
     * @return Full shader path
     */
    public static String getShaderPath(String name, String extension) {
        if (name.contains(":")) {
            Identifier location = Identifier.tryParse(name);
            return location.getNamespace() + ":shaders/core/" + location.getPath() + extension;
        } else {
            return "minecraft:shaders/core/" + name + extension;
        }
    }

    /**
     * Check if a shader resource exists.
     *
     * @param resourceProvider The resource provider
     * @param resourceLocation The resource location to check
     * @return true if resource exists
     */
    public static boolean shaderExists(ResourceProvider resourceProvider, Identifier resourceLocation) {
        try {
            return resourceProvider.getResource(resourceLocation).isPresent();
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Load HLSL compiled shader bytecode.
     *
     * @param resourceProvider The resource provider
     * @param shaderName Shader name
     * @param shaderType Shader type (vertex or pixel)
     * @return Compiled shader bytecode
     */
    public static byte[] loadCompiledShader(ResourceProvider resourceProvider, String shaderName, String shaderType) {
        try {
            String extension = shaderType.equals("vertex") ? ".vso" : ".pso";
            Identifier resourceLocation = Identifier.parse("vitra:shaders/compiled/" + shaderName + extension);

            Resource resource = resourceProvider.getResource(resourceLocation).orElse(null);
            if (resource == null) {
                LOGGER.debug("Compiled shader not found: {}", resourceLocation);
                return null;
            }

            try (InputStream inputStream = resource.open()) {
                return inputStream.readAllBytes();
            }
        } catch (Exception e) {
            LOGGER.debug("Could not load compiled shader {}/{}: {}", shaderName, shaderType, e.getMessage());
            return null;
        }
    }
}
