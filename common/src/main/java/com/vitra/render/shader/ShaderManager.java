package com.vitra.render.shader;

import com.vitra.core.config.RendererType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Shader management system for Vitra
 * Handles loading, compilation, and management of shaders for different backends
 */
public class ShaderManager {
    private static final Logger LOGGER = LoggerFactory.getLogger(ShaderManager.class);

    private final Map<String, ShaderProgram> loadedShaders;
    private final Map<RendererType, ShaderCompiler> compilers;
    private RendererType currentRenderer;
    private boolean initialized = false;

    public ShaderManager() {
        this.loadedShaders = new ConcurrentHashMap<>();
        this.compilers = new HashMap<>();
    }

    /**
     * Initialize the shader manager
     * @param rendererType The current renderer backend
     */
    public void initialize(RendererType rendererType) {
        if (initialized) {
            LOGGER.warn("ShaderManager already initialized");
            return;
        }

        this.currentRenderer = rendererType;

        // Initialize compilers for different backends
        compilers.put(RendererType.DIRECTX11, new HLSLCompiler());

        initialized = true;
        LOGGER.info("ShaderManager initialized for {}", rendererType.getDisplayName());
    }

    /**
     * Shutdown the shader manager
     */
    public void shutdown() {
        if (!initialized) return;

        LOGGER.info("Shutting down ShaderManager...");

        // Cleanup all loaded shaders
        loadedShaders.values().forEach(ShaderProgram::dispose);
        loadedShaders.clear();

        // Cleanup compilers
        compilers.values().forEach(ShaderCompiler::cleanup);
        compilers.clear();

        initialized = false;
        LOGGER.info("ShaderManager shutdown complete");
    }

    /**
     * Load a shader program from resources
     * @param name Shader program name
     * @param vertexShaderPath Path to vertex shader
     * @param fragmentShaderPath Path to fragment shader
     * @return Loaded shader program or null if failed
     */
    public ShaderProgram loadShader(String name, String vertexShaderPath, String fragmentShaderPath) {
        if (!initialized) {
            LOGGER.error("ShaderManager not initialized");
            return null;
        }

        // Check if already loaded
        if (loadedShaders.containsKey(name)) {
            return loadedShaders.get(name);
        }

        try {
            // Load shader source code
            String vertexSource = loadShaderSource(vertexShaderPath);
            String fragmentSource = loadShaderSource(fragmentShaderPath);

            if (vertexSource == null || fragmentSource == null) {
                LOGGER.error("Failed to load shader sources for {}", name);
                return null;
            }

            // Get appropriate compiler
            ShaderCompiler compiler = compilers.get(currentRenderer);
            if (compiler == null) {
                LOGGER.error("No compiler available for renderer: {}", currentRenderer);
                return null;
            }

            // Compile shader program
            ShaderProgram program = compiler.compile(name, vertexSource, fragmentSource);
            if (program != null) {
                loadedShaders.put(name, program);
                LOGGER.info("Successfully loaded shader: {}", name);
                return program;
            } else {
                LOGGER.error("Failed to compile shader: {}", name);
                return null;
            }

        } catch (Exception e) {
            LOGGER.error("Error loading shader: " + name, e);
            return null;
        }
    }

    /**
     * Get a loaded shader program
     * @param name Shader program name
     * @return Shader program or null if not found
     */
    public ShaderProgram getShader(String name) {
        return loadedShaders.get(name);
    }

    /**
     * Load default shaders for basic rendering
     */
    public void loadDefaultShaders() {
        // Load basic vertex/fragment shader for simple rendering
        ShaderProgram basicShader = createBasicShader();
        if (basicShader != null) {
            loadedShaders.put("basic", basicShader);
            LOGGER.info("Loaded basic shader");
        }

        // Load chunk rendering shader
        ShaderProgram chunkShader = createChunkShader();
        if (chunkShader != null) {
            loadedShaders.put("chunk", chunkShader);
            LOGGER.info("Loaded chunk shader");
        }
    }

    /**
     * Create a basic shader program for simple rendering
     */
    private ShaderProgram createBasicShader() {
        String vertexShader =
            "#version 330 core\n" +
            "layout (location = 0) in vec3 position;\n" +
            "layout (location = 1) in vec4 color;\n" +
            "uniform mat4 mvpMatrix;\n" +
            "out vec4 vertexColor;\n" +
            "void main() {\n" +
            "    gl_Position = mvpMatrix * vec4(position, 1.0);\n" +
            "    vertexColor = color;\n" +
            "}";

        String fragmentShader =
            "#version 330 core\n" +
            "in vec4 vertexColor;\n" +
            "out vec4 fragColor;\n" +
            "void main() {\n" +
            "    fragColor = vertexColor;\n" +
            "}";

        ShaderCompiler compiler = compilers.get(currentRenderer);
        return compiler != null ? compiler.compile("basic", vertexShader, fragmentShader) : null;
    }

    /**
     * Create a chunk rendering shader
     */
    private ShaderProgram createChunkShader() {
        String vertexShader =
            "#version 330 core\n" +
            "layout (location = 0) in vec3 position;\n" +
            "layout (location = 1) in vec3 normal;\n" +
            "layout (location = 2) in vec2 texCoord;\n" +
            "uniform mat4 mvpMatrix;\n" +
            "uniform mat4 modelMatrix;\n" +
            "out vec3 worldPos;\n" +
            "out vec3 worldNormal;\n" +
            "out vec2 uv;\n" +
            "void main() {\n" +
            "    vec4 worldPosition = modelMatrix * vec4(position, 1.0);\n" +
            "    worldPos = worldPosition.xyz;\n" +
            "    worldNormal = normalize((modelMatrix * vec4(normal, 0.0)).xyz);\n" +
            "    uv = texCoord;\n" +
            "    gl_Position = mvpMatrix * worldPosition;\n" +
            "}";

        String fragmentShader =
            "#version 330 core\n" +
            "in vec3 worldPos;\n" +
            "in vec3 worldNormal;\n" +
            "in vec2 uv;\n" +
            "uniform sampler2D diffuseTexture;\n" +
            "uniform vec3 lightDirection;\n" +
            "out vec4 fragColor;\n" +
            "void main() {\n" +
            "    vec4 texColor = texture(diffuseTexture, uv);\n" +
            "    float lightFactor = max(0.2, dot(worldNormal, -lightDirection));\n" +
            "    fragColor = vec4(texColor.rgb * lightFactor, texColor.a);\n" +
            "}";

        ShaderCompiler compiler = compilers.get(currentRenderer);
        return compiler != null ? compiler.compile("chunk", vertexShader, fragmentShader) : null;
    }

    /**
     * Load shader source code from resources
     */
    private String loadShaderSource(String resourcePath) {
        try (InputStream inputStream = getClass().getResourceAsStream(resourcePath)) {
            if (inputStream == null) {
                LOGGER.warn("Shader resource not found: {}", resourcePath);
                return null;
            }

            StringBuilder source = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    source.append(line).append('\n');
                }
            }
            return source.toString();

        } catch (IOException e) {
            LOGGER.error("Failed to load shader source: " + resourcePath, e);
            return null;
        }
    }

    public boolean isInitialized() {
        return initialized;
    }

    public RendererType getCurrentRenderer() {
        return currentRenderer;
    }

    public int getLoadedShaderCount() {
        return loadedShaders.size();
    }
}