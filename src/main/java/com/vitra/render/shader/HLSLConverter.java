package com.vitra.render.shader;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Complete GLSL to HLSL shader converter for Minecraft shaders.
 * Handles vertex and pixel shaders with full syntax translation.
 */
public class HLSLConverter {
    private static final Logger LOGGER = LoggerFactory.getLogger(HLSLConverter.class);

    private String vshConverted;
    private String fshConverted;
    private final List<UniformInfo> uniforms = new ArrayList<>();
    private final List<SamplerInfo> samplers = new ArrayList<>();
    private final Map<String, String> varyings = new LinkedHashMap<>();

    // GLSL version patterns
    private static final Pattern VERSION_PATTERN = Pattern.compile("#version\\s+(\\d+)\\s*(\\w*)");

    // GLSL uniform patterns
    private static final Pattern UNIFORM_PATTERN = Pattern.compile("uniform\\s+(\\w+)\\s+(\\w+)\\s*;");

    // GLSL attribute/in patterns
    private static final Pattern ATTRIBUTE_PATTERN = Pattern.compile("(in|attribute)\\s+(\\w+)\\s+(\\w+)\\s*;");

    // GLSL varying/out patterns
    private static final Pattern VARYING_OUT_PATTERN = Pattern.compile("(out|varying)\\s+(\\w+)\\s+(\\w+)\\s*;");
    private static final Pattern VARYING_IN_PATTERN = Pattern.compile("in\\s+(\\w+)\\s+(\\w+)\\s*;");

    // GLSL sampler patterns
    private static final Pattern SAMPLER_PATTERN = Pattern.compile("uniform\\s+(sampler\\w+)\\s+(\\w+)\\s*;");

    // GLSL function patterns
    private static final Pattern MAIN_FUNCTION_PATTERN = Pattern.compile("void\\s+main\\s*\\(\\s*\\)");

    // GLSL texture function patterns
    private static final Pattern TEXTURE_PATTERN = Pattern.compile("texture\\s*\\(");

    // GLSL built-in variable patterns
    private static final Pattern GL_POSITION_PATTERN = Pattern.compile("\\bgl_Position\\b");
    private static final Pattern GL_FRAGCOLOR_PATTERN = Pattern.compile("\\bgl_FragColor\\b");
    private static final Pattern GL_FRAGDATA_PATTERN = Pattern.compile("\\bgl_FragData\\[0\\]\\b");

    public static class UniformInfo {
        public final String type;
        public final String name;
        public final int size;
        public final int slot;

        public UniformInfo(String type, String name, int slot) {
            this.type = type;
            this.name = name;
            this.size = getUniformSize(type);
            this.slot = slot;
        }

        private static int getUniformSize(String type) {
            return switch (type) {
                case "mat4" -> 64;  // 4x4 matrix = 16 floats = 64 bytes
                case "mat3" -> 48;  // 3x3 matrix = 9 floats (+padding) = 48 bytes
                case "vec4" -> 16;  // 4 floats = 16 bytes
                case "vec3" -> 16;  // 3 floats (+padding) = 16 bytes
                case "vec2" -> 8;   // 2 floats = 8 bytes
                case "float" -> 4;   // 1 float = 4 bytes
                case "int" -> 4;     // 1 int = 4 bytes
                default -> 16;       // default to vec4 size
            };
        }
    }

    public static class SamplerInfo {
        public final String type;
        public final String name;
        public final int slot;

        public SamplerInfo(String type, String name, int slot) {
            this.type = type;
            this.name = name;
            this.slot = slot;
        }
    }

    /**
     * Process GLSL vertex and fragment shaders and convert them to HLSL.
     */
    public void process(String vshSource, String fshSource) {
        LOGGER.info("Converting GLSL shaders to HLSL...");

        // Extract uniforms and samplers from both shaders
        extractUniforms(vshSource);
        extractUniforms(fshSource);
        extractSamplers(vshSource);
        extractSamplers(fshSource);

        // Convert vertex shader
        this.vshConverted = convertVertexShader(vshSource);

        // Convert fragment shader
        this.fshConverted = convertPixelShader(fshSource);

        LOGGER.info("Shader conversion completed. Uniforms: {}, Samplers: {}", uniforms.size(), samplers.size());
    }

    /**
     * Convert GLSL vertex shader to HLSL.
     */
    private String convertVertexShader(String glslSource) {
        StringBuilder hlsl = new StringBuilder();

        // Add HLSL header
        hlsl.append("// Converted from GLSL to HLSL\n");
        hlsl.append("// DirectX 11 Vertex Shader\n\n");

        // Add constant buffer declarations
        if (!uniforms.isEmpty()) {
            hlsl.append(generateConstantBuffers());
            hlsl.append("\n");
        }

        // Add sampler declarations
        if (!samplers.isEmpty()) {
            hlsl.append(generateSamplerDeclarations());
            hlsl.append("\n");
        }

        // Parse input structure from GLSL attributes
        hlsl.append(generateVertexInput(glslSource));
        hlsl.append("\n");

        // Parse output structure from GLSL varyings
        hlsl.append(generateVertexOutput(glslSource));
        hlsl.append("\n");

        // Convert main function
        hlsl.append(convertVertexMain(glslSource));

        return hlsl.toString();
    }

    /**
     * Convert GLSL fragment shader to HLSL.
     */
    private String convertPixelShader(String glslSource) {
        StringBuilder hlsl = new StringBuilder();

        // Add HLSL header
        hlsl.append("// Converted from GLSL to HLSL\n");
        hlsl.append("// DirectX 11 Pixel Shader\n\n");

        // Add constant buffer declarations
        if (!uniforms.isEmpty()) {
            hlsl.append(generateConstantBuffers());
            hlsl.append("\n");
        }

        // Add sampler declarations
        if (!samplers.isEmpty()) {
            hlsl.append(generateSamplerDeclarations());
            hlsl.append("\n");
        }

        // Parse input structure from GLSL varyings
        hlsl.append(generatePixelInput(glslSource));
        hlsl.append("\n");

        // Parse output structure
        hlsl.append("struct PS_OUTPUT {\n");
        hlsl.append("    float4 color : SV_Target0;\n");
        hlsl.append("};\n\n");

        // Convert main function
        hlsl.append(convertPixelMain(glslSource));

        return hlsl.toString();
    }

    /**
     * Generate HLSL constant buffer declarations.
     */
    private String generateConstantBuffers() {
        StringBuilder sb = new StringBuilder();

        // Group uniforms by slot (we'll use b0 for now, can be expanded)
        sb.append("cbuffer MinecraftUniforms : register(b0) {\n");

        for (UniformInfo uniform : uniforms) {
            String hlslType = convertGLSLTypeToHLSL(uniform.type);
            sb.append("    ").append(hlslType).append(" ").append(uniform.name).append(";\n");
        }

        sb.append("};\n");

        return sb.toString();
    }

    /**
     * Generate HLSL sampler and texture declarations.
     */
    private String generateSamplerDeclarations() {
        StringBuilder sb = new StringBuilder();

        for (int i = 0; i < samplers.size(); i++) {
            SamplerInfo sampler = samplers.get(i);
            String hlslType = convertSamplerTypeToHLSL(sampler.type);

            // Texture declaration
            sb.append(hlslType).append(" ").append(sampler.name).append(" : register(t").append(i).append(");\n");

            // Sampler state declaration
            sb.append("SamplerState ").append(sampler.name).append("Sampler : register(s").append(i).append(");\n");
        }

        return sb.toString();
    }

    /**
     * Generate vertex shader input structure.
     */
    private String generateVertexInput(String glslSource) {
        StringBuilder sb = new StringBuilder();
        sb.append("struct VS_INPUT {\n");

        Matcher matcher = ATTRIBUTE_PATTERN.matcher(glslSource);
        int semanticIndex = 0;

        while (matcher.find()) {
            String type = matcher.group(2);
            String name = matcher.group(3);
            String hlslType = convertGLSLTypeToHLSL(type);
            String semantic = getVertexSemantic(name, semanticIndex++);

            sb.append("    ").append(hlslType).append(" ").append(name).append(" : ").append(semantic).append(";\n");
        }

        if (semanticIndex == 0) {
            // Default Minecraft vertex format
            sb.append("    float3 Position : POSITION0;\n");
            sb.append("    float4 Color : COLOR0;\n");
            sb.append("    float2 UV0 : TEXCOORD0;\n");
            sb.append("    int2 UV1 : TEXCOORD1;\n");
            sb.append("    int2 UV2 : TEXCOORD2;\n");
            sb.append("    float3 Normal : NORMAL0;\n");
        }

        sb.append("};\n");
        return sb.toString();
    }

    /**
     * Generate vertex shader output structure.
     */
    private String generateVertexOutput(String glslSource) {
        StringBuilder sb = new StringBuilder();
        sb.append("struct VS_OUTPUT {\n");
        sb.append("    float4 position : SV_Position;\n");

        Matcher matcher = VARYING_OUT_PATTERN.matcher(glslSource);
        int texcoordIndex = 0;

        while (matcher.find()) {
            String type = matcher.group(2);
            String name = matcher.group(3);
            String hlslType = convertGLSLTypeToHLSL(type);

            varyings.put(name, hlslType);
            sb.append("    ").append(hlslType).append(" ").append(name).append(" : TEXCOORD").append(texcoordIndex++).append(";\n");
        }

        sb.append("};\n");
        return sb.toString();
    }

    /**
     * Generate pixel shader input structure.
     */
    private String generatePixelInput(String glslSource) {
        StringBuilder sb = new StringBuilder();
        sb.append("struct PS_INPUT {\n");
        sb.append("    float4 position : SV_Position;\n");

        int texcoordIndex = 0;
        for (Map.Entry<String, String> varying : varyings.entrySet()) {
            sb.append("    ").append(varying.getValue()).append(" ").append(varying.getKey())
              .append(" : TEXCOORD").append(texcoordIndex++).append(";\n");
        }

        sb.append("};\n");
        return sb.toString();
    }

    /**
     * Convert GLSL vertex shader main function to HLSL.
     */
    private String convertVertexMain(String glslSource) {
        StringBuilder sb = new StringBuilder();
        sb.append("VS_OUTPUT main(VS_INPUT input) {\n");
        sb.append("    VS_OUTPUT output;\n\n");

        // Extract main function body
        String mainBody = extractMainFunctionBody(glslSource);

        // Convert GLSL code to HLSL
        mainBody = convertGLSLToHLSL(mainBody, true);

        // Replace gl_Position with output.position
        mainBody = mainBody.replaceAll("\\bgl_Position\\b", "output.position");

        sb.append(mainBody);
        sb.append("\n    return output;\n");
        sb.append("}\n");

        return sb.toString();
    }

    /**
     * Convert GLSL fragment shader main function to HLSL.
     */
    private String convertPixelMain(String glslSource) {
        StringBuilder sb = new StringBuilder();
        sb.append("PS_OUTPUT main(PS_INPUT input) {\n");
        sb.append("    PS_OUTPUT output;\n\n");

        // Extract main function body
        String mainBody = extractMainFunctionBody(glslSource);

        // Convert GLSL code to HLSL
        mainBody = convertGLSLToHLSL(mainBody, false);

        // Replace gl_FragColor/gl_FragData[0] with output.color
        mainBody = mainBody.replaceAll("\\bgl_FragColor\\b", "output.color");
        mainBody = mainBody.replaceAll("\\bgl_FragData\\[0\\]\\b", "output.color");

        sb.append(mainBody);
        sb.append("\n    return output;\n");
        sb.append("}\n");

        return sb.toString();
    }

    /**
     * Extract main function body from GLSL source.
     */
    private String extractMainFunctionBody(String glslSource) {
        Matcher matcher = MAIN_FUNCTION_PATTERN.matcher(glslSource);

        if (!matcher.find()) {
            LOGGER.warn("Could not find main() function in shader");
            return "";
        }

        int start = matcher.end();
        int braceCount = 0;
        int bodyStart = -1;
        int bodyEnd = -1;

        for (int i = start; i < glslSource.length(); i++) {
            char c = glslSource.charAt(i);

            if (c == '{') {
                if (braceCount == 0) {
                    bodyStart = i + 1;
                }
                braceCount++;
            } else if (c == '}') {
                braceCount--;
                if (braceCount == 0) {
                    bodyEnd = i;
                    break;
                }
            }
        }

        if (bodyStart == -1 || bodyEnd == -1) {
            LOGGER.warn("Could not extract main() function body");
            return "";
        }

        return glslSource.substring(bodyStart, bodyEnd).trim();
    }

    /**
     * Convert GLSL syntax to HLSL syntax.
     */
    private String convertGLSLToHLSL(String glsl, boolean isVertexShader) {
        String hlsl = glsl;

        // Remove #version directive
        hlsl = hlsl.replaceAll("#version\\s+\\d+.*\n", "");

        // Convert texture functions
        // texture() -> .Sample()
        hlsl = hlsl.replaceAll("texture\\s*\\(\\s*(\\w+)\\s*,\\s*([^)]+)\\)",
                               "$1.Sample($1Sampler, $2)");

        // Convert GLSL built-in functions to HLSL
        hlsl = convertBuiltInFunctions(hlsl);

        // Convert type constructors
        hlsl = convertTypeConstructors(hlsl);

        // Handle input references
        if (isVertexShader) {
            hlsl = hlsl.replaceAll("\\b(Position|Color|UV0|UV1|UV2|Normal)\\b", "input.$1");
        } else {
            // Replace varying inputs with input.variableName
            for (String varying : varyings.keySet()) {
                hlsl = hlsl.replaceAll("\\b" + varying + "\\b", "input." + varying);
            }
        }

        return hlsl;
    }

    /**
     * Convert GLSL built-in functions to HLSL equivalents.
     */
    private String convertBuiltInFunctions(String glsl) {
        String hlsl = glsl;

        // Mathematical functions
        hlsl = hlsl.replaceAll("\\bmix\\s*\\(", "lerp(");
        hlsl = hlsl.replaceAll("\\bfract\\s*\\(", "frac(");
        hlsl = hlsl.replaceAll("\\bmod\\s*\\(", "fmod(");

        // Vector functions remain mostly the same
        // dot, cross, normalize, length, etc. are identical in HLSL

        return hlsl;
    }

    /**
     * Convert GLSL type constructors to HLSL.
     */
    private String convertTypeConstructors(String glsl) {
        // GLSL and HLSL use similar syntax for type constructors
        // vec4() -> float4() is the main difference
        String hlsl = glsl;

        hlsl = hlsl.replaceAll("\\bvec4\\s*\\(", "float4(");
        hlsl = hlsl.replaceAll("\\bvec3\\s*\\(", "float3(");
        hlsl = hlsl.replaceAll("\\bvec2\\s*\\(", "float2(");
        hlsl = hlsl.replaceAll("\\bivec4\\s*\\(", "int4(");
        hlsl = hlsl.replaceAll("\\bivec3\\s*\\(", "int3(");
        hlsl = hlsl.replaceAll("\\bivec2\\s*\\(", "int2(");
        hlsl = hlsl.replaceAll("\\bmat4\\s*\\(", "float4x4(");
        hlsl = hlsl.replaceAll("\\bmat3\\s*\\(", "float3x3(");
        hlsl = hlsl.replaceAll("\\bmat2\\s*\\(", "float2x2(");

        return hlsl;
    }

    /**
     * Extract uniform declarations from GLSL source.
     */
    private void extractUniforms(String glslSource) {
        Matcher matcher = UNIFORM_PATTERN.matcher(glslSource);
        int slot = 0;

        while (matcher.find()) {
            String type = matcher.group(1);
            String name = matcher.group(2);

            // Skip samplers (they're handled separately)
            if (type.startsWith("sampler")) {
                continue;
            }

            // Check if uniform already exists
            boolean exists = uniforms.stream().anyMatch(u -> u.name.equals(name));
            if (!exists) {
                uniforms.add(new UniformInfo(type, name, slot++));
            }
        }
    }

    /**
     * Extract sampler declarations from GLSL source.
     */
    private void extractSamplers(String glslSource) {
        Matcher matcher = SAMPLER_PATTERN.matcher(glslSource);
        int slot = 0;

        while (matcher.find()) {
            String type = matcher.group(1);
            String name = matcher.group(2);

            // Check if sampler already exists
            boolean exists = samplers.stream().anyMatch(s -> s.name.equals(name));
            if (!exists) {
                samplers.add(new SamplerInfo(type, name, slot++));
            }
        }
    }

    /**
     * Convert GLSL type to HLSL type.
     */
    private String convertGLSLTypeToHLSL(String glslType) {
        return switch (glslType) {
            case "vec2" -> "float2";
            case "vec3" -> "float3";
            case "vec4" -> "float4";
            case "ivec2" -> "int2";
            case "ivec3" -> "int3";
            case "ivec4" -> "int4";
            case "mat2" -> "float2x2";
            case "mat3" -> "float3x3";
            case "mat4" -> "float4x4";
            default -> glslType;  // float, int, etc. are the same
        };
    }

    /**
     * Convert GLSL sampler type to HLSL texture type.
     */
    private String convertSamplerTypeToHLSL(String samplerType) {
        return switch (samplerType) {
            case "sampler2D" -> "Texture2D";
            case "sampler3D" -> "Texture3D";
            case "samplerCube" -> "TextureCube";
            case "sampler2DArray" -> "Texture2DArray";
            default -> "Texture2D";  // default to 2D texture
        };
    }

    /**
     * Get appropriate HLSL semantic for vertex attribute.
     */
    private String getVertexSemantic(String name, int index) {
        // Map common vertex attribute names to semantics
        String lowerName = name.toLowerCase();

        if (lowerName.contains("position") || lowerName.contains("pos")) {
            return "POSITION" + index;
        } else if (lowerName.contains("color") || lowerName.contains("col")) {
            return "COLOR" + index;
        } else if (lowerName.contains("texcoord") || lowerName.contains("uv")) {
            return "TEXCOORD" + index;
        } else if (lowerName.contains("normal") || lowerName.contains("norm")) {
            return "NORMAL" + index;
        } else if (lowerName.contains("tangent") || lowerName.contains("tan")) {
            return "TANGENT" + index;
        } else {
            return "TEXCOORD" + index;  // default
        }
    }

    // Getters
    public String getVshConverted() {
        return vshConverted;
    }

    public String getFshConverted() {
        return fshConverted;
    }

    public List<UniformInfo> getUniforms() {
        return Collections.unmodifiableList(uniforms);
    }

    public List<SamplerInfo> getSamplers() {
        return Collections.unmodifiableList(samplers);
    }
}
