package ac.grim.grimac.platform.bukkit.player;

import ac.grim.grimac.platform.api.player.PlatformInventory;
import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.manager.server.ServerVersion;
import com.github.retrooper.packetevents.protocol.item.ItemStack;
import com.github.retrooper.packetevents.protocol.item.type.ItemType;
import com.github.retrooper.packetevents.protocol.item.type.ItemTypes;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.github.retrooper.packetevents.resources.ResourceLocation;
import io.github.retrooper.packetevents.util.SpigotConversionUtil;
import lombok.RequiredArgsConstructor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.concurrent.atomic.AtomicReferenceArray;

@RequiredArgsConstructor
public class BukkitPlatformInventory implements PlatformInventory {

    private static final boolean HAS_OFFHAND = PacketEvents.getAPI().getServerManager().getVersion().isNewerThanOrEquals(ServerVersion.V_1_9);
    private static final AtomicReferenceArray<ItemType> SYNC_TYPES = new AtomicReferenceArray<>(Material.values().length);

    private final @NotNull Player bukkitPlayer;

    @SuppressWarnings("deprecation")
    @Override
    public ItemStack getItemInHand() {
        return SpigotConversionUtil.fromBukkitItemStack(bukkitPlayer.getInventory().getItemInHand());
    }

    @Override
    public ItemStack getItemInOffHand() {
        return HAS_OFFHAND
                ? SpigotConversionUtil.fromBukkitItemStack(bukkitPlayer.getInventory().getItemInOffHand())
                : ItemStack.EMPTY;
    }

    @Override
    public ItemStack getStack(int bukkitSlot, int vanillaSlot) {
        return SpigotConversionUtil.fromBukkitItemStack(bukkitPlayer.getInventory().getItem(bukkitSlot));
    }

    @Override
    public ItemStack getStackForSync(int bukkitSlot, int vanillaSlot, ItemStack expected) {
        ServerVersion version = PacketEvents.getAPI().getServerManager().getVersion();
        if (getClass() != BukkitPlatformInventory.class || version.isOlderThan(ServerVersion.V_1_13)) {
            return getStack(bukkitSlot, vanillaSlot);
        }

        org.bukkit.inventory.ItemStack live = bukkitPlayer.getInventory().getItem(bukkitSlot);
        if (live == null) return ItemStack.EMPTY;

        Material material = live.getType();
        int amount = live.getAmount();
        if (!material.isLegacy()) {
            if (amount <= 0 || material == Material.AIR || material == Material.CAVE_AIR || material == Material.VOID_AIR) {
                return ItemStack.EMPTY;
            }
            // Older item codecs store the count in a signed byte.
            if (expected != null && amount == expected.getAmount()
                    && (amount <= Byte.MAX_VALUE || version.isNewerThanOrEquals(ServerVersion.V_1_20_5))) {
                ItemType type = getSyncType(material, version);
                if (type != ItemTypes.AIR && type == expected.getType()) return expected;
            }
        }
        return SpigotConversionUtil.fromBukkitItemStack(live);
    }

    private static ItemType getSyncType(Material material, ServerVersion version) {
        int ordinal = material.ordinal();
        ItemType cached = SYNC_TYPES.get(ordinal);
        if (cached != null) return cached;

        NamespacedKey key = material.getKey();
        ItemType type = ItemTypes.getByName(key.toString());
        if (type != null) {
            ClientVersion protocol = version.toClientVersion();
            ResourceLocation name = type.getName();
            int id = type.getId(protocol);
            if (id < 0 || ItemTypes.getById(protocol, id) != type
                    || !key.getNamespace().equals(name.getNamespace()) || !key.getKey().equals(name.getKey())) {
                type = null;
            }
        }
        // AIR marks unsupported materials; they always use the full conversion.
        if (type == null) type = ItemTypes.AIR;
        SYNC_TYPES.compareAndSet(ordinal, null, type);
        return type;
    }

    @Override
    public ItemStack getHelmet() {
        return SpigotConversionUtil.fromBukkitItemStack(bukkitPlayer.getInventory().getHelmet());
    }

    @Override
    public ItemStack getChestplate() {
        return SpigotConversionUtil.fromBukkitItemStack(bukkitPlayer.getInventory().getChestplate());
    }

    @Override
    public ItemStack getLeggings() {
        return SpigotConversionUtil.fromBukkitItemStack(bukkitPlayer.getInventory().getLeggings());
    }

    @Override
    public ItemStack getBoots() {
        return SpigotConversionUtil.fromBukkitItemStack(bukkitPlayer.getInventory().getBoots());
    }

    @Override
    public ItemStack[] getContents() {
        org.bukkit.inventory.ItemStack[] bukkitItems = bukkitPlayer.getInventory().getContents();
        ItemStack[] items = new ItemStack[bukkitItems.length];
        for (int i = 0; i < bukkitItems.length; i++) {
            if (bukkitItems[i] == null) continue;
            items[i] = SpigotConversionUtil.fromBukkitItemStack(bukkitItems[i]);
        }
        return items;
    }

    @Override
    public String getOpenInventoryKey() {
        return bukkitPlayer.getOpenInventory().getType().toString();
    }
}
