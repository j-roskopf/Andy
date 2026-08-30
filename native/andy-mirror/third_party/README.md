# Vendored native headers

`vulkan/` and `vk_video/` are a snapshot of
[KhronosGroup/Vulkan-Headers](https://github.com/KhronosGroup/Vulkan-Headers)
`v1.4.321` (Apache-2.0, see `LICENSE.vulkan-headers`). They are used only when
building `libandy-mirror-jni.so` so Linux CI and local builds do not require
the distro `vulkan-headers` package. Runtime still uses the system Vulkan
loader (`libvulkan.so`).
