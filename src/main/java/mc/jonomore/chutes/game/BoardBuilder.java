package mc.jonomore.chutes.game;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BiConsumer;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import org.jspecify.annotations.Nullable;

/**
 * Places a session's regions (rooms and the finish platform) over several ticks, loading their
 * chunks first and holding plugin chunk tickets on them until everything is cleared again. The
 * tickets also keep the non-persistent button entities from being unloaded.
 */
final class BoardBuilder {

    /** One thing to place: its min corner, horizontal extent and the place/clear actions. */
    record Region(Location origin, int sizeX, int sizeZ, Runnable place, Runnable clear) {
    }

    private static final int PLACE_PER_TICK = 2;
    private static final int CLEAR_PER_TICK = 4;
    private static final int REGIONS_LOADING = 6;

    private final Plugin plugin;
    private final World world;
    private final List<Region> regions;
    private final Set<Long> tickets = new HashSet<>();
    private final boolean[] placed;
    private final ArrayDeque<Integer> ready = new ArrayDeque<>();

    private int nextRequest;
    private int loading;
    private int placedCount;
    private boolean buildStopped;
    private @Nullable BukkitTask task;
    private @Nullable ArrayDeque<Integer> clearQueue;
    private @Nullable Runnable onCleared;

    BoardBuilder(Plugin plugin, World world, List<Region> regions) {
        this.plugin = plugin;
        this.world = world;
        this.regions = List.copyOf(regions);
        this.placed = new boolean[regions.size()];
    }

    int total() {
        return regions.size();
    }

    /** Starts building. onProgress gets (placed, total); onDone runs once, when everything is placed. */
    void build(BiConsumer<Integer, Integer> onProgress, Runnable onDone) {
        task = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            if (buildStopped) {
                return;
            }
            while (loading < REGIONS_LOADING && nextRequest < regions.size()) {
                request(nextRequest++);
            }
            int placedNow = 0;
            while (placedNow < PLACE_PER_TICK && !ready.isEmpty()) {
                int index = ready.poll();
                try {
                    regions.get(index).place().run();
                } catch (RuntimeException e) {
                    plugin.getSLF4JLogger().error("Failed to place board region {}", index, e);
                }
                placed[index] = true;
                placedCount++;
                placedNow++;
            }
            if (placedNow > 0) {
                onProgress.accept(placedCount, regions.size());
            }
            if (placedCount == regions.size()) {
                stopTask();
                buildStopped = true;
                onDone.run();
            }
        }, 1L, 1L);
    }

    private void request(int index) {
        loading++;
        List<long[]> chunks = chunksOf(regions.get(index));
        int[] pending = {chunks.size()};
        for (long[] chunk : chunks) {
            int cx = (int) chunk[0];
            int cz = (int) chunk[1];
            // Paper completes this future on the main thread.
            world.getChunkAtAsync(cx, cz).whenComplete((Chunk loaded, @Nullable Throwable error) -> {
                if (buildStopped) {
                    return;
                }
                if (error != null) {
                    plugin.getSLF4JLogger().warn("Chunk {},{} failed to load for the board", cx, cz, error);
                }
                addTicket(cx, cz);
                if (--pending[0] == 0) {
                    loading--;
                    ready.add(index);
                }
            });
        }
    }

    private static List<long[]> chunksOf(Region region) {
        int minX = region.origin().getBlockX() >> 4;
        int minZ = region.origin().getBlockZ() >> 4;
        int maxX = (region.origin().getBlockX() + Math.max(1, region.sizeX()) - 1) >> 4;
        int maxZ = (region.origin().getBlockZ() + Math.max(1, region.sizeZ()) - 1) >> 4;
        List<long[]> chunks = new ArrayList<>();
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                chunks.add(new long[] {x, z});
            }
        }
        return chunks;
    }

    private void addTicket(int cx, int cz) {
        if (tickets.add(Chunk.getChunkKey(cx, cz))) {
            world.addPluginChunkTicket(cx, cz, plugin);
        }
    }

    /**
     * Stops building and clears every placed region, then releases the chunk tickets and runs
     * onCleared once. When sync is true everything (including an earlier async clear still in
     * progress) completes before this returns.
     */
    void clear(boolean sync, Runnable onCleared) {
        buildStopped = true;
        stopTask();
        if (this.onCleared == null) {
            this.onCleared = onCleared;
        }
        ArrayDeque<Integer> queue = clearQueue;
        if (queue == null) {
            queue = new ArrayDeque<>();
            for (int i = 0; i < placed.length; i++) {
                if (placed[i]) {
                    queue.add(i);
                }
            }
            clearQueue = queue;
        }
        if (sync) {
            while (!queue.isEmpty()) {
                clearOne(queue.poll());
            }
            finishClear();
            return;
        }
        ArrayDeque<Integer> pending = queue;
        task = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            for (int i = 0; i < CLEAR_PER_TICK && !pending.isEmpty(); i++) {
                clearOne(pending.poll());
            }
            if (pending.isEmpty()) {
                stopTask();
                finishClear();
            }
        }, 1L, 1L);
    }

    private void clearOne(int index) {
        placed[index] = false;
        try {
            regions.get(index).clear().run();
        } catch (RuntimeException e) {
            plugin.getSLF4JLogger().error("Failed to clear board region {}", index, e);
        }
    }

    private void finishClear() {
        for (long key : tickets) {
            try {
                world.removePluginChunkTicket((int) key, (int) (key >> 32), plugin);
            } catch (RuntimeException e) {
                plugin.getSLF4JLogger().warn("Could not release a chunk ticket", e);
            }
        }
        tickets.clear();
        Runnable callback = onCleared;
        onCleared = null;
        if (callback != null) {
            callback.run();
        }
    }

    private void stopTask() {
        BukkitTask current = task;
        task = null;
        if (current != null) {
            current.cancel();
        }
    }
}
