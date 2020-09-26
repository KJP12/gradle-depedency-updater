# Updater.gradle
An updater script written in Gradle to update projects quickly and efficiently.

## Features
- Maven dependency lookup
- Minecraft support
    - Fabric support
        - Loader and Yarn Mappings fetching
        - Automatic remapping and moving
- Can be used in a submodule (allows for quick pulling)
- Dependency and Metadata reference

## Usage

### Properties
In order to setup the script, you need to setup the [`updater.properties`](./blob/master/example.properties) so the script can effectively update your project. You can see a brief summary of the properties below, along with what they do.

```properties

# Usage of metadata
minecraft_required=$minecraft.required
minecraft_version=$minecraft.target
yarn_mappings=$mappings
loader_version=$loader

# Usage of repositories
fabric_loader_version=@fabric,net.fabricmc,fabric-loader
systemProp.loom_version=@fabric,fabric-loom,fabric-loom.gradle.plugin
```

#### Repositories
Repositories are properties which keys start with `@`. They may be referenced for dependency resolution.
```properties
@fabric=https://maven.fabricmc.net/
@maven=https://repo.maven.apache.org/maven2/
@default=@maven
```

#### Metadata
Metadata are properties which keys start with `$`. They may be referenced by dependencies and template properties.
```properties
$minecraft.target=1.16.3
$minecraft.snapshot=1.17
$modding.api=fabric
$properties=gradle.properties
$recursive=true
```

##### Special Metadata
- `minecraft.target` The version of Minecraft you are targeting. Used for fetching the Fabric loader and Yarn mappings.
- `minecraft.snapshot` The version of Minecraft you are using during the snapshots. Used for `minecraft.required` to build up a proper required string.
- `minecraft.required` The semantic-versioning string. Usually mirrors `minecraft.target`, unless it is a snapshot version, in which case, it is expected to be similar to `1.16-alpha.20.14.a`, using `minecraft.snapshot` in place of `1.16`. Intended for templating in `fabric.mod.json` with [dependency resolution](https://fabricmc.net/wiki/documentation:fabric_mod_json_spec#optional_fields_dependency_resolution). Can only be referenced, not set.
- `modding.api` The modding API you're working with. The only accepted value at this time is `fabric`.
- `properties` The gradle.properties file. Defaults to `gradle.properties`.
- `recurisve` If it should recursively act on submodules.
- `mappings` The mappings returned for the current modding API and Minecraft version. Can only be referenced, not set.
- `loader` The loader returned for the current modding API and Minecraft version. Can only be referenced, not set.