# Getting Started with Vitra

This guide will help you set up, build, and run the Vitra optimization mod for Minecraft.

## Prerequisites

### System Requirements
- **Java**: JDK 21 or higher
- **Minecraft**: Java Edition 1.21.8+
- **Memory**: 4GB+ RAM recommended
- **Graphics**: OpenGL 3.3+ compatible GPU

### Development Environment
- **IDE**: IntelliJ IDEA (recommended) or Eclipse
- **Git**: For version control
- **Gradle**: 8.0+ (included with wrapper)

### Graphics API Support
- **OpenGL**: All platforms (minimum requirement)
- **DirectX 12**: Windows 10+ with compatible GPU
- **Vulkan**: Compatible drivers required

## Quick Start

### 1. Clone the Repository
```bash
git clone <repository-url>
cd vitra
```

### 2. Build the Mod
```bash
# Build for all platforms
./gradlew build

# Windows
gradlew.bat build
```

### 3. Run Development Environment
```bash
# Launch Minecraft with Fabric
./gradlew fabric:runClient

# Launch Minecraft with NeoForge
./gradlew neoforge:runClient
```

## Project Structure

```
vitra/
├── common/              # Shared code between platforms
│   └── src/main/java/
│       └── com/vitra/
│           ├── core/        # Core systems and optimizations
│           ├── render/      # Rendering abstraction layer
│           └── VitraMod.java
├── fabric/              # Fabric-specific code
├── neoforge/            # NeoForge-specific code
├── build.gradle         # Root build configuration
├── gradle.properties    # Project properties and versions
└── settings.gradle      # Multi-project setup
```

## Building and Testing

### Build Commands

```bash
# Clean build
./gradlew clean build

# Build specific platform
./gradlew fabric:build
./gradlew neoforge:build

# Run tests
./gradlew test

# Generate IDE files
./gradlew genEclipseRuns    # For Eclipse
./gradlew genIntellijRuns   # For IntelliJ IDEA
```

### Testing in Development

1. **Run Client**: Use `./gradlew fabric:runClient` or `./gradlew neoforge:runClient`
2. **Check Logs**: Monitor console output for Vitra initialization messages
3. **Test Rendering**: Create a new world and verify rendering works correctly
4. **Performance Testing**: Enable debug mode and monitor optimization statistics

### Configuration During Development

Create a test configuration file at `run/config/vitra.properties`:

```properties
# Enable debug mode for development
renderer.debug=true

# Use OpenGL for compatibility during testing
renderer.type=OPENGL

# Enable all optimizations for testing
optimization.frustumCulling=true
optimization.asyncMeshBuilding=true
optimization.lodEnabled=true
optimization.memoryPooling=true
```

## IDE Setup

### IntelliJ IDEA

1. **Import Project**:
   - Open IntelliJ IDEA
   - Select "Open or Import"
   - Choose the `build.gradle` file
   - Import as Gradle project

2. **Configure JDK**:
   - File → Project Structure → Project
   - Set Project SDK to Java 21+

3. **Generate Run Configurations**:
   ```bash
   ./gradlew genIntellijRuns
   ```

4. **Run Configurations**:
   - `fabric_runClient` - Run Minecraft with Fabric
   - `neoforge_runClient` - Run Minecraft with NeoForge

### Eclipse

1. **Import Project**:
   - File → Import → Existing Gradle Project
   - Select the project root directory

2. **Generate Launch Configurations**:
   ```bash
   ./gradlew genEclipseRuns
   ```

## Development Workflow

### 1. Making Changes

1. **Modify Code**: Edit files in the `common/` directory for shared functionality
2. **Platform-Specific Changes**: Edit `fabric/` or `neoforge/` for loader-specific code
3. **Configuration Updates**: Modify configuration classes in `core/config/`

### 2. Testing Changes

```bash
# Incremental build and test
./gradlew fabric:runClient

# Full rebuild if needed
./gradlew clean build fabric:runClient
```

### 3. Debugging

Enable debug logging by adding to your test configuration:
```properties
renderer.debug=true
```

Common debug information includes:
- Backend initialization status
- Optimization statistics
- Memory usage information
- Performance metrics

### 4. Performance Testing

1. **Enable Optimization Statistics**:
   ```java
   OptimizationManager.OptimizationStats stats =
       VitraMod.getCore().getOptimizationManager().getStats();
   System.out.println(stats);
   ```

2. **Monitor Frame Times**: Use F3 debug screen to monitor performance
3. **Test Different Backends**: Switch between OpenGL/DirectX/Vulkan
4. **Load Test**: Test with large worlds and many entities

## Common Development Tasks

### Adding a New Optimization

1. **Create Optimization Class**:
   ```java
   public class MyOptimization {
       public void initialize() { /* setup */ }
       public void update(float deltaTime) { /* per-frame update */ }
       public void shutdown() { /* cleanup */ }
   }
   ```

2. **Register with OptimizationManager**:
   ```java
   // In OptimizationManager.initialize()
   if (config.isMyOptimizationEnabled()) {
       myOptimization = new MyOptimization();
       myOptimization.initialize();
   }
   ```

3. **Add Configuration Option**:
   ```java
   // In VitraConfig.java
   private boolean myOptimizationEnabled = true;

   public boolean isMyOptimizationEnabled() {
       return myOptimizationEnabled;
   }
   ```

### Adding a Render Demo

1. **Create Demo Class**:
   ```java
   public class MyRenderDemo {
       public boolean initialize() { /* setup rendering resources */ }
       public void render() { /* draw the demo */ }
       public void cleanup() { /* free resources */ }
   }
   ```

2. **Integration Point**: Add to `VitraRenderer` or create separate demo runner

### Extending Backend Support

1. **Implement RenderContext**:
   ```java
   public class MyBackendContext implements RenderContext {
       // Implement all required methods
   }
   ```

2. **Add to Backend Factory**: Update `VitraRenderer.createRenderContext()`

3. **Update Configuration**: Add new renderer type to `RendererType` enum

## Troubleshooting

### Build Issues

**Problem**: `Could not find LWJGL natives`
**Solution**: Ensure internet connection for dependency download, or check proxy settings

**Problem**: `Java version incompatible`
**Solution**: Update to Java 21+ and set JAVA_HOME correctly

**Problem**: `Gradle wrapper not found`
**Solution**: Run `gradle wrapper` to generate wrapper files

### Runtime Issues

**Problem**: `Vitra failed to initialize`
**Solution**:
- Check Java version compatibility
- Verify graphics drivers are updated
- Try different renderer backend

**Problem**: `BGFX initialization failed`
**Solution**:
- Ensure graphics drivers support required OpenGL version
- Try software renderer as fallback
- Check for conflicting mods

**Problem**: `Shader compilation errors`
**Solution**:
- Verify shader source code syntax
- Check for backend-specific limitations
- Enable debug mode for detailed error messages

### Performance Issues

**Problem**: `Lower FPS than expected`
**Solution**:
- Verify optimizations are enabled in config
- Check CPU/GPU usage with profiler
- Try different backends for comparison

**Problem**: `Memory leaks`
**Solution**:
- Enable memory pooling
- Check for proper resource cleanup
- Monitor GC activity

## Next Steps

1. **Explore the Codebase**: Read through the architecture documentation
2. **Run Examples**: Test the built-in rendering demos
3. **Experiment with Backends**: Try different graphics APIs
4. **Contribute**: Submit improvements and bug fixes
5. **Join Community**: Participate in discussions and development

For more detailed information, see:
- [Architecture Documentation](ARCHITECTURE.md)
- [API Reference](API.md)
- [Contributing Guidelines](../CONTRIBUTING.md)