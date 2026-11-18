package io.lakio.farfield.mixin.client;

import java.util.Deque;
import net.minecraft.client.multiplayer.ClientLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * The client level's queue of light updates (the server's light, applied a
 * few ticks after its chunk): 1.21.1 had a getter for its emptiness, gone
 * since (ADR-165); the queue is the same in both.
 */
@Mixin(ClientLevel.class)
public interface ClientLevelAccessor {
    @Accessor("lightUpdateQueue")
    Deque<Runnable> farfield$lightUpdateQueue();
}
