package ac.grim.grimac.platform.api.player;

import com.github.retrooper.packetevents.protocol.item.ItemStack;

public interface PlatformInventory {
    ItemStack getItemInHand();

    ItemStack getItemInOffHand();

    ItemStack getStack(int bukkitSlot, int vanillaSlot);

    /** May reuse expected when its type and count match the server slot. */
    default ItemStack getStackForSync(int bukkitSlot, int vanillaSlot, ItemStack expected) {
        return getStack(bukkitSlot, vanillaSlot);
    }

    ItemStack getHelmet();

    ItemStack getChestplate();

    ItemStack getLeggings();

    ItemStack getBoots();

    ItemStack[] getContents();

    String getOpenInventoryKey();
}
