package ac.grim.grimac.utils.chunks;

import com.github.retrooper.packetevents.protocol.world.chunk.palette.DataPalette;
import com.github.retrooper.packetevents.protocol.world.chunk.palette.GlobalPalette;
import com.github.retrooper.packetevents.protocol.world.chunk.storage.BaseStorage;
import com.github.retrooper.packetevents.protocol.world.chunk.storage.BitStorage;
import com.github.retrooper.packetevents.protocol.world.chunk.storage.LegacyFlexibleStorage;

public final class PaletteStorage {
    private PaletteStorage() {
    }

    public static void ensureCapacity(DataPalette data) {
        BaseStorage storage = data.storage;
        if (storage == null || data.palette instanceof GlobalPalette) return;
        int bits = data.palette.getBits();
        if (storage.getBitsPerEntry() >= bits) return;

        // PE can decode a full-size list palette backed by fewer storage bits.
        int size = data.paletteType.getStorageSize();
        BaseStorage expanded = storage instanceof LegacyFlexibleStorage
                ? new LegacyFlexibleStorage(bits, size)
                : new BitStorage(bits, size);
        for (int index = 0; index < size; index++) {
            expanded.set(index, storage.get(index));
        }
        data.storage = expanded;
    }
}
