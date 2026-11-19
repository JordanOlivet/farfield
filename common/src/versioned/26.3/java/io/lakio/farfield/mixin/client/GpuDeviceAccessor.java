package io.lakio.farfield.mixin.client;

import com.mojang.renderpearl.backend.api.GpuDeviceBackend;
import com.mojang.renderpearl.frontend.FrontendGpuDevice;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Since 26.3 the GPU device is an interface; its front over the backend is Renderpearl's {@code FrontendGpuDevice} (ADR-171). */
@Mixin(FrontendGpuDevice.class)
public interface GpuDeviceAccessor {
    @Accessor("backend")
    GpuDeviceBackend farfield$backend();
}
