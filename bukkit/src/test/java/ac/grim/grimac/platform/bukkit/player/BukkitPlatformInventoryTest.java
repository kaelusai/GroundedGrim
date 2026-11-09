package ac.grim.grimac.platform.bukkit.player;

import ac.grim.grimac.platform.api.player.PlatformInventory;
import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.PacketEventsAPI;
import com.github.retrooper.packetevents.manager.server.ServerVersion;
import com.github.retrooper.packetevents.protocol.item.ItemStack;
import com.github.retrooper.packetevents.protocol.item.type.ItemType;
import com.github.retrooper.packetevents.protocol.item.type.ItemTypes;
import com.github.retrooper.packetevents.protocol.nbt.NBTCompound;
import com.github.retrooper.packetevents.protocol.nbt.NBTInt;
import com.github.retrooper.packetevents.resources.ResourceLocation;
import com.github.retrooper.packetevents.settings.PacketEventsSettings;
import io.github.retrooper.packetevents.impl.netty.NettyManagerImpl;
import io.github.retrooper.packetevents.util.SpigotConversionUtil;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.PlayerInventory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.MockedStatic;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BukkitPlatformInventoryTest {
    private Player player;
    private PlayerInventory nativeInventory;
    private BukkitPlatformInventory inventory;

    @BeforeAll
    static void initialize() {
        PacketEventsAPI<?> api = mock(PacketEventsAPI.class, RETURNS_DEEP_STUBS);
        when(api.getServerManager().getVersion()).thenReturn(ServerVersion.V_1_21_11);
        when(api.getSettings()).thenReturn(new PacketEventsSettings());
        when(api.getNettyManager()).thenReturn(new NettyManagerImpl());
        PacketEvents.setAPI(api);
    }

    @BeforeEach
    void setup() throws ReflectiveOperationException {
        when(PacketEvents.getAPI().getServerManager().getVersion()).thenReturn(ServerVersion.V_1_21_11);
        player = mock(Player.class);
        nativeInventory = mock(PlayerInventory.class);
        when(player.getInventory()).thenReturn(nativeInventory);
        inventory = new BukkitPlatformInventory(player);
        var field = BukkitPlatformInventory.class.getDeclaredField("SYNC_TYPES");
        field.setAccessible(true);
        AtomicReferenceArray<?> cache = (AtomicReferenceArray<?>) field.get(null);
        for (int i = 0; i < cache.length(); i++) cache.set(i, null);
    }

    private org.bukkit.inventory.ItemStack live(Material material, int count) {
        org.bukkit.inventory.ItemStack stack = mock(org.bukkit.inventory.ItemStack.class);
        when(stack.getType()).thenReturn(material);
        when(stack.getAmount()).thenReturn(count);
        when(nativeInventory.getItem(0)).thenReturn(stack);
        return stack;
    }

    private ItemStack expected(ItemType type, int count) {
        return ItemStack.builder().type(type).amount(count).build();
    }

    @Test
    void matchingChecksNeedNoConversionEvenOnFirstUse() {
        var live = live(Material.DIAMOND_SWORD, 1);
        ItemStack expected = expected(ItemTypes.DIAMOND_SWORD, 1);
        try (MockedStatic<SpigotConversionUtil> conversion = mockStatic(SpigotConversionUtil.class)) {
            for (int i = 0; i < 1000; i++) assertSame(expected, inventory.getStackForSync(0, 36, expected));
            conversion.verifyNoInteractions();
        }
        verify(live, never()).getItemMeta();
    }

    @ParameterizedTest
    @CsvSource({"STONE, 2", "DIAMOND_SWORD, 1", "APPLE, 1"})
    void mismatchesConvertOnceAndKeepFullServerStack(Material material, int count) {
        var live = live(material, count);
        ItemStack expected = expected(ItemTypes.STONE, 1);
        ItemStack converted = expected(ItemTypes.DIAMOND_SWORD, 1);
        try (MockedStatic<SpigotConversionUtil> conversion = mockStatic(SpigotConversionUtil.class)) {
            conversion.when(() -> SpigotConversionUtil.fromBukkitItemStack(live)).thenReturn(converted);
            assertSame(converted, inventory.getStackForSync(0, 36, expected));
            conversion.verify(() -> SpigotConversionUtil.fromBukkitItemStack(live), times(1));
            conversion.verifyNoMoreInteractions();
        }
        verify(nativeInventory, times(1)).getItem(0);
    }

    @Test
    void syncReuseDoesNotCacheComponentsForNormalReads() {
        var live = live(Material.DIAMOND_SWORD, 1);
        NBTCompound before = new NBTCompound();
        before.setTag("custom", new NBTInt(1));
        NBTCompound after = new NBTCompound();
        after.setTag("custom", new NBTInt(2));
        ItemStack expected = ItemStack.builder().type(ItemTypes.DIAMOND_SWORD).amount(1).nbt(before).build();
        ItemStack converted = ItemStack.builder().type(ItemTypes.DIAMOND_SWORD).amount(1).nbt(after).build();
        try (MockedStatic<SpigotConversionUtil> conversion = mockStatic(SpigotConversionUtil.class)) {
            conversion.when(() -> SpigotConversionUtil.fromBukkitItemStack(live)).thenReturn(converted);
            assertSame(expected, inventory.getStackForSync(0, 36, expected));
            assertSame(converted, inventory.getStack(0, 36));
            assertEquals(new NBTInt(2), inventory.getStack(0, 36).getNBT().getTagOrNull("custom"));
        }
    }

    @Test
    void nullSlotsAreEmptyWithoutConversion() {
        try (MockedStatic<SpigotConversionUtil> conversion = mockStatic(SpigotConversionUtil.class)) {
            assertSame(ItemStack.EMPTY, inventory.getStackForSync(0, 36, expected(ItemTypes.STONE, 1)));
            conversion.verifyNoInteractions();
        }
    }

    @ParameterizedTest
    @CsvSource({"AIR, 1", "CAVE_AIR, 1", "VOID_AIR, 1", "STONE, 0", "STONE, -1"})
    void nativeEmptyStacksAreNormalized(Material material, int amount) {
        live(material, amount);
        try (MockedStatic<SpigotConversionUtil> conversion = mockStatic(SpigotConversionUtil.class)) {
            assertSame(ItemStack.EMPTY, inventory.getStackForSync(0, 36, expected(ItemTypes.STONE, 0)));
            conversion.verifyNoInteractions();
        }
    }

    @Test
    void legacyMaterialsKeepTheFullConversion() {
        var live = live(Material.LEGACY_STONE, 1);
        ItemStack converted = expected(ItemTypes.STONE, 1);
        try (MockedStatic<SpigotConversionUtil> conversion = mockStatic(SpigotConversionUtil.class)) {
            conversion.when(() -> SpigotConversionUtil.fromBukkitItemStack(live)).thenReturn(converted);
            assertSame(converted, inventory.getStackForSync(0, 36, converted));
            conversion.verify(() -> SpigotConversionUtil.fromBukkitItemStack(live));
        }
    }

    @ParameterizedTest
    @CsvSource({"V_1_12_2, 1", "V_1_13, 128", "V_1_20_4, 128"})
    void oldProtocolsKeepConversionWhereItemCountsCanChange(ServerVersion version, int amount) {
        when(PacketEvents.getAPI().getServerManager().getVersion()).thenReturn(version);
        var live = live(Material.STONE, amount);
        ItemStack converted = expected(ItemTypes.STONE, amount);
        try (MockedStatic<SpigotConversionUtil> conversion = mockStatic(SpigotConversionUtil.class)) {
            conversion.when(() -> SpigotConversionUtil.fromBukkitItemStack(live)).thenReturn(converted);
            assertSame(converted, inventory.getStackForSync(0, 36, converted));
            conversion.verify(() -> SpigotConversionUtil.fromBukkitItemStack(live));
        }
    }

    @ParameterizedTest
    @CsvSource({"V_1_13, 64", "V_1_20_4, 64", "V_1_20_5, 128", "V_1_21_11, 128"})
    void normalCountsUseRegistryKeysAcrossModernVersions(ServerVersion version, int amount) {
        when(PacketEvents.getAPI().getServerManager().getVersion()).thenReturn(version);
        live(Material.STONE, amount);
        ItemStack expected = expected(ItemTypes.STONE, amount);
        try (MockedStatic<SpigotConversionUtil> conversion = mockStatic(SpigotConversionUtil.class)) {
            assertSame(expected, inventory.getStackForSync(0, 36, expected));
            conversion.verifyNoInteractions();
        }
    }

    @Test
    void customNamespacesDoNotMatchVanillaMaterials() {
        var live = live(Material.STONE, 1);
        ItemType custom = mock(ItemType.class);
        when(custom.getName()).thenReturn(new ResourceLocation("custom:stone"));
        ItemStack expected = mock(ItemStack.class);
        when(expected.getType()).thenReturn(custom);
        when(expected.getAmount()).thenReturn(1);
        ItemStack converted = expected(ItemTypes.STONE, 1);
        try (MockedStatic<SpigotConversionUtil> conversion = mockStatic(SpigotConversionUtil.class)) {
            conversion.when(() -> SpigotConversionUtil.fromBukkitItemStack(live)).thenReturn(converted);
            assertSame(converted, inventory.getStackForSync(0, 36, expected));
            conversion.verify(() -> SpigotConversionUtil.fromBukkitItemStack(live));
        }
    }

    @Test
    void blockOnlyMaterialsCannotMatchAnItemKey() {
        var live = live(Material.FIRE, 1);
        ItemStack expected = mock(ItemStack.class);
        when(expected.getType()).thenReturn(ItemTypes.FIRE);
        when(expected.getAmount()).thenReturn(1);
        try (MockedStatic<SpigotConversionUtil> conversion = mockStatic(SpigotConversionUtil.class)) {
            conversion.when(() -> SpigotConversionUtil.fromBukkitItemStack(live)).thenReturn(ItemStack.EMPTY);
            assertSame(ItemStack.EMPTY, inventory.getStackForSync(0, 36, expected));
            conversion.verify(() -> SpigotConversionUtil.fromBukkitItemStack(live));
        }
    }

    @Test
    void nonCanonicalTypesCannotMatchByNameAlone() {
        var live = live(Material.STONE, 1);
        ItemType custom = mock(ItemType.class);
        when(custom.getName()).thenReturn(ItemTypes.STONE.getName());
        when(custom.getId(any())).thenReturn(ItemTypes.STONE.getId(ServerVersion.V_1_21_11.toClientVersion()));
        ItemStack expected = mock(ItemStack.class);
        when(expected.getType()).thenReturn(custom);
        when(expected.getAmount()).thenReturn(1);
        ItemStack converted = expected(ItemTypes.STONE, 1);
        try (MockedStatic<SpigotConversionUtil> conversion = mockStatic(SpigotConversionUtil.class)) {
            conversion.when(() -> SpigotConversionUtil.fromBukkitItemStack(live)).thenReturn(converted);
            assertSame(converted, inventory.getStackForSync(0, 36, expected));
            conversion.verify(() -> SpigotConversionUtil.fromBukkitItemStack(live));
        }
    }

    @Test
    void subclassOverridesKeepTheirExistingStackReader() {
        ItemStack converted = expected(ItemTypes.STONE, 1);
        BukkitPlatformInventory custom = new BukkitPlatformInventory(player) {
            @Override
            public ItemStack getStack(int bukkitSlot, int vanillaSlot) {
                return converted;
            }
        };
        assertSame(converted, custom.getStackForSync(0, 36, converted));
        verifyNoInteractions(nativeInventory);
    }

    @Test
    void otherPlatformsDefaultToFullReads() {
        PlatformInventory custom = mock(PlatformInventory.class, CALLS_REAL_METHODS);
        ItemStack converted = expected(ItemTypes.STONE, 1);
        when(custom.getStack(0, 36)).thenReturn(converted);
        assertSame(converted, custom.getStackForSync(0, 36, ItemStack.EMPTY));
        verify(custom).getStack(0, 36);
    }

    @Test
    void concurrentFirstChecksPublishTheSameCanonicalType() {
        live(Material.DIAMOND_SWORD, 1);
        ItemStack expected = expected(ItemTypes.DIAMOND_SWORD, 1);
        CompletableFuture<?>[] workers = new CompletableFuture<?>[8];
        for (int i = 0; i < workers.length; i++) {
            workers[i] = CompletableFuture.runAsync(() -> {
                for (int j = 0; j < 100; j++) assertSame(expected, inventory.getStackForSync(0, 36, expected));
            });
        }
        CompletableFuture.allOf(workers).join();
    }

    @Test
    void canonicalVanillaItemMaterialsNeedNoStackConversion() {
        var protocol = ServerVersion.V_1_21_11.toClientVersion();
        Set<Material> checked = new HashSet<>();
        try (MockedStatic<SpigotConversionUtil> conversion = mockStatic(SpigotConversionUtil.class)) {
            for (ItemType type : ItemTypes.values()) {
                int id = type.getId(protocol);
                if (id < 0 || ItemTypes.getById(protocol, id) != type || type == ItemTypes.AIR) continue;
                Material material = Material.getMaterial(type.getName().getKey().toUpperCase(Locale.ROOT));
                if (material == null || material.isLegacy() || !checked.add(material)) continue;
                live(material, 1);
                ItemStack expected = expected(type, 1);
                assertSame(expected, inventory.getStackForSync(0, 36, expected), type.getName().toString());
            }
            conversion.verifyNoInteractions();
        }
        assertTrue(checked.size() > 1000);
    }
}
