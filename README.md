# Vitra - Multi-Backend Minecraft Optimization Mod

Vitra is a high-performance optimization and multi-backend rendering mod for Minecraft Java Edition. It provides significant performance improvements through advanced rendering techniques and supports multiple graphics APIs including OpenGL, DirectX 12, and Vulkan.

## Features

### Multi-Backend Rendering
- **OpenGL** - Maximum compatibility, default renderer
- **DirectX 12** - Windows-only, modern features and performance
- **Vulkan** - Cross-platform, maximum performance and efficiency
- **Software** - CPU-based fallback renderer

### Performance Optimizations
- **Frustum Culling** - Only render visible chunks and entities
- **Level of Detail (LOD)** - Reduce detail for distant objects
- **Async Mesh Building** - Build chunk meshes on background threads
- **Memory Pooling** - Reduce garbage collection pressure
- **Entity Batching** - Batch similar entities for efficient rendering
- **Chunk Batching** - Combine multiple chunks into single draw calls

### Advanced Features
- Runtime backend switching
- Comprehensive configuration system
- Debug information and statistics
- Shader hot-reloading support
- Compatible with Fabric and NeoForge

## Installation

1. Download the appropriate version for your mod loader:
   - `vitra-fabric-<version>.jar` for Fabric
   - `vitra-neoforge-<version>.jar` for NeoForge

2. Place the mod file in your `mods` directory

3. Launch Minecraft - Vitra will automatically initialize with optimal settings

## Configuration

Vitra creates a configuration file at `config/vitra.properties` with the following options:

```properties
# Rendering Configuration
renderer.type=OPENGL
renderer.vsync=true
renderer.maxFPS=144
renderer.debug=false

# Performance Optimizations
optimization.frustumCulling=true
optimization.asyncMeshBuilding=true
optimization.lodEnabled=true
optimization.lodDistance=64.0
optimization.memoryPooling=true

# Chunk Rendering
chunk.renderDistance=12
chunk.batching=true
chunk.maxPerBatch=16

# Entity Rendering
entity.batching=true
entity.maxPerBatch=32
entity.culling=true
```

## Development

### Building from Source

```bash
# Clone the repository
git clone <repository-url>
cd vitra

# Build for all platforms
./gradlew build

# Build for specific platform
./gradlew fabric:build
./gradlew neoforge:build
```

### Testing

```bash
# Run Minecraft client with Fabric
./gradlew fabric:runClient

# Run Minecraft client with NeoForge
./gradlew neoforge:runClient
```

## Architecture

### Core Components

1. **VitraMod** - Main mod entry point and lifecycle management
2. **VitraCore** - Core optimization and management systems
3. **VitraRenderer** - Multi-backend rendering system
4. **RenderContext** - Backend-agnostic rendering interface

### Module Structure

```
common/src/main/java/com/vitra/
├── core/
│   ├── config/           # Configuration management
│   └── optimization/     # Performance optimization modules
├── render/
│   ├── backend/          # Rendering backend implementations
│   ├── demo/            # Example rendering demonstrations
│   └── shader/          # Shader management system
└── VitraMod.java        # Main mod class
```

### Performance Optimization Modules

- **FrustumCuller** - Visibility culling system
- **LODManager** - Level-of-detail management
- **AsyncMeshBuilder** - Background mesh generation
- **MemoryPool** - Object pooling for garbage collection optimization

## Compatibility

### Minecraft Versions
- Minecraft 1.21.1+
- Java 21+

### Mod Loaders
- Fabric 0.17.2+
- NeoForge 21.8.33+

### Graphics APIs
- OpenGL 3.3+ (all platforms)
- DirectX 12 (Windows 10+)
- Vulkan 1.0+ (with compatible drivers)

## Performance Tips

1. **Choose the Right Backend**
   - Vulkan: Best performance on modern systems
   - DirectX 12: Good performance on Windows
   - OpenGL: Maximum compatibility

2. **Optimize Settings**
   - Enable async mesh building for better frame pacing
   - Adjust LOD distance based on your system performance
   - Use chunk batching for better GPU utilization

3. **Monitor Performance**
   - Enable debug mode to see optimization statistics
   - Use the built-in performance overlay
   - Check logs for performance warnings

## Troubleshooting

### Common Issues

**Vitra fails to initialize**
- Ensure you have Java 21+
- Check that LWJGL natives are properly loaded
- Verify your graphics drivers are up to date

**Poor performance**
- Try different backend types
- Adjust optimization settings in config
- Check system resource usage

**Rendering issues**
- Disable shader packs temporarily
- Try software renderer as fallback
- Check graphics driver compatibility

### Debug Information

Enable debug mode in configuration to get detailed information:
```properties
renderer.debug=true
```

This will show:
- Active renderer backend
- Optimization statistics
- Memory usage information
- Performance metrics

## Contributing

1. Fork the repository
2. Create a feature branch
3. Make your changes with appropriate tests
4. Submit a pull request

### Development Guidelines

- Follow existing code style and conventions
- Add comprehensive comments for complex systems
- Include unit tests for new functionality
- Update documentation for user-facing changes

## License

This project is licensed under the MIT License - see the LICENSE file for details.

## Credits

- Built with [LWJGL](https://lwjgl.org/) for graphics API access
- Uses [BGFX](https://github.com/bkaradzic/bgfx) for multi-backend rendering
- Powered by [Architectury](https://docs.architectury.dev/) for multi-platform support

## Support

- Report issues on [GitHub Issues](https://github.com/your-repo/vitra/issues)
- Join our [Discord](https://discord.gg/your-discord) for community support
- Check the [Wiki](https://github.com/your-repo/vitra/wiki) for detailed documentation