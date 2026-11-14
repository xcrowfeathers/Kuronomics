package de.kuro.kuronomics.registry;

import de.kuro.kuronomics.Kuronomics;
import de.kuro.kuronomics.LegacyCompatibility;
import de.kuro.kuronomics.block.*;
import de.kuro.kuronomics.item.DocumentItem;
import de.kuro.kuronomics.item.WalletItem;
import de.kuro.kuronomics.menu.MachineMenu;
import de.kuro.kuronomics.menu.AuctionMenu;
import de.kuro.kuronomics.menu.ServerShopEditorMenu;
import de.kuro.kuronomics.menu.ServerShopTerminalMenu;
import de.kuro.kuronomics.menu.WalletMenu;
import de.kuro.kuronomics.menu.RealEstateMenu;
import de.kuro.kuronomics.menu.JobMenu;
import java.util.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.*;
import net.neoforged.neoforge.common.extensions.IMenuTypeExtension;

public final class ModContent {
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(Kuronomics.MODID);
    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(Kuronomics.MODID);
    public static final DeferredRegister<BlockEntityType<?>> ENTITIES = DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, Kuronomics.MODID);
    public static final DeferredRegister<MenuType<?>> MENUS = DeferredRegister.create(Registries.MENU, Kuronomics.MODID);
    public static final DeferredRegister<SoundEvent> SOUNDS = DeferredRegister.create(Registries.SOUND_EVENT, Kuronomics.MODID);
    private static final Map<String, DeferredHolder<SoundEvent, SoundEvent>> SOUND_EVENTS = new HashMap<>();
    public static final DeferredRegister<CreativeModeTab> TABS = DeferredRegister.create(Registries.CREATIVE_MODE_TAB, Kuronomics.MODID);
    public static final Map<Integer, DeferredItem<Item>> CASH = new LinkedHashMap<>();
    private static volatile Map<Item, Integer> cashValues;
    public static final Map<MachineKind, DeferredBlock<MachineBlock>> MACHINES = new EnumMap<>(MachineKind.class);
    public static final Map<MachineKind, DeferredHolder<BlockEntityType<?>, BlockEntityType<MachineBlockEntity>>> MACHINE_ENTITIES = new EnumMap<>(MachineKind.class);
    /** This is the global-shop terminal. shop_block_server is still the old stocked shop block. */
    public static final DeferredBlock<ServerShopBlock> SERVER_SHOP_TERMINAL_BLOCK = BLOCKS.register("server_shop_terminal", ServerShopBlock::new);
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<ServerShopBlockEntity>> SERVER_SHOP_TERMINAL_ENTITY = ENTITIES.register(
            "server_shop_terminal", () -> BlockEntityType.Builder.of(ServerShopBlockEntity::new, SERVER_SHOP_TERMINAL_BLOCK.get()).build(null));
    public static final DeferredBlock<AuctionTerminalBlock> AUCTION_TERMINAL_BLOCK = BLOCKS.register("auction_terminal", AuctionTerminalBlock::new);
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<AuctionTerminalBlockEntity>> AUCTION_TERMINAL_ENTITY = ENTITIES.register(
            "auction_terminal", () -> BlockEntityType.Builder.of(AuctionTerminalBlockEntity::new, AUCTION_TERMINAL_BLOCK.get()).build(null));
    public static final DeferredBlock<RealEstateTerminalBlock> REAL_ESTATE_TERMINAL_BLOCK = BLOCKS.register("real_estate_terminal", RealEstateTerminalBlock::new);
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<RealEstateTerminalBlockEntity>> REAL_ESTATE_TERMINAL_ENTITY = ENTITIES.register(
            "real_estate_terminal", () -> BlockEntityType.Builder.of(RealEstateTerminalBlockEntity::new, REAL_ESTATE_TERMINAL_BLOCK.get()).build(null));
    public static final DeferredBlock<JobTerminalBlock> JOB_TERMINAL_BLOCK = BLOCKS.register("job_terminal", JobTerminalBlock::new);
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<JobTerminalBlockEntity>> JOB_TERMINAL_ENTITY = ENTITIES.register(
            "job_terminal", () -> BlockEntityType.Builder.of(JobTerminalBlockEntity::new, JOB_TERMINAL_BLOCK.get()).build(null));
    public static final List<DeferredItem<DocumentItem>> DOCUMENTS = new ArrayList<>();
    public static final DeferredItem<Item> CHECK = ITEMS.registerSimpleItem("check_item", new Item.Properties().stacksTo(1));
    public static final DeferredItem<WalletItem> WALLET = ITEMS.register("wallet", () -> new WalletItem(new Item.Properties().stacksTo(1)));
    public static final DeferredHolder<MenuType<?>, MenuType<MachineMenu>> MACHINE_MENU = MENUS.register("machine", () -> IMenuTypeExtension.create(MachineMenu::new));
    public static final DeferredHolder<MenuType<?>, MenuType<WalletMenu>> WALLET_MENU = MENUS.register("wallet", () -> IMenuTypeExtension.create(WalletMenu::new));
    public static final DeferredHolder<MenuType<?>, MenuType<de.kuro.kuronomics.menu.DocumentMenu>> DOCUMENT_MENU = MENUS.register("document", () -> IMenuTypeExtension.create(de.kuro.kuronomics.menu.DocumentMenu::new));
    public static final DeferredHolder<MenuType<?>, MenuType<ServerShopTerminalMenu>> SERVER_SHOP_TERMINAL_MENU = MENUS.register("server_shop_terminal", () -> IMenuTypeExtension.create(ServerShopTerminalMenu::new));
    public static final DeferredHolder<MenuType<?>, MenuType<ServerShopEditorMenu>> SERVER_SHOP_EDITOR_MENU = MENUS.register("server_shop_editor", () -> IMenuTypeExtension.create(ServerShopEditorMenu::new));
    public static final DeferredHolder<MenuType<?>, MenuType<AuctionMenu>> AUCTION_MENU = MENUS.register("auction", () -> IMenuTypeExtension.create(AuctionMenu::new));
    public static final DeferredHolder<MenuType<?>, MenuType<RealEstateMenu>> REAL_ESTATE_MENU = MENUS.register("real_estate", () -> IMenuTypeExtension.create(RealEstateMenu::new));
    public static final DeferredHolder<MenuType<?>, MenuType<JobMenu>> JOB_MENU = MENUS.register("job_terminal", () -> IMenuTypeExtension.create(JobMenu::new));
    public static final DeferredHolder<MenuType<?>, MenuType<de.kuro.kuronomics.menu.MarketMenu>> MARKET_MENU = MENUS.register("market", () -> IMenuTypeExtension.create(de.kuro.kuronomics.menu.MarketMenu::new));
    public static final DeferredHolder<MenuType<?>, MenuType<de.kuro.kuronomics.menu.CentralBankMenu>> CENTRAL_BANK_MENU = MENUS.register("central_bank", () -> IMenuTypeExtension.create(de.kuro.kuronomics.menu.CentralBankMenu::new));
    static {
        for (int value : new int[]{1, 5, 10, 50, 100, 500}) CASH.put(value, ITEMS.registerSimpleItem(value == 1 ? "dollar_coin" : "dollar_" + value));
        String[] ids = {"id1", "id2", "id_3", "id_4", "drivers_license"};
        for (int i = 0; i < ids.length; i++) { final int index = i; DOCUMENTS.add(ITEMS.register(ids[i], () -> new DocumentItem(new Item.Properties().stacksTo(1), index))); }
        for (MachineKind kind : MachineKind.values()) {
            var block = BLOCKS.register(kind.id, () -> new MachineBlock(kind)); MACHINES.put(kind, block);
            ITEMS.registerSimpleBlockItem(kind.id, block);
            MACHINE_ENTITIES.put(kind, ENTITIES.register(kind.id, () -> BlockEntityType.Builder.of((pos, state) -> new MachineBlockEntity(kind, pos, state), block.get()).build(null)));
        }
        ITEMS.registerSimpleBlockItem("server_shop_terminal", SERVER_SHOP_TERMINAL_BLOCK);
        ITEMS.registerSimpleBlockItem("auction_terminal", AUCTION_TERMINAL_BLOCK);
        ITEMS.registerSimpleBlockItem("real_estate_terminal", REAL_ESTATE_TERMINAL_BLOCK);
        ITEMS.registerSimpleBlockItem("job_terminal", JOB_TERMINAL_BLOCK);
        for (String sound : new String[]{"atm_print", "atm_boot", "error", "cashreg", "id_accept", "id_incorrect"})
            SOUND_EVENTS.put(sound, SOUNDS.register(sound, () -> SoundEvent.createVariableRangeEvent(ResourceLocation.fromNamespaceAndPath(Kuronomics.MODID, sound))));
        TABS.register("kuronomics", () -> CreativeModeTab.builder().title(net.minecraft.network.chat.Component.literal("Kuronomics"))
                .icon(() -> new ItemStack(CASH.get(100).get())).displayItems((parameters, output) -> ITEMS.getEntries().forEach(item -> output.accept(item.get()))).build());
        Set<String> itemIds = new HashSet<>(List.of("check_item", "wallet", "dollar_coin", "dollar_5", "dollar_10",
                "dollar_50", "dollar_100", "dollar_500", "id1", "id2", "id_3", "id_4", "drivers_license", "server_shop_terminal", "real_estate_terminal", "job_terminal"));
        for (MachineKind kind : MachineKind.values()) itemIds.add(kind.id);
        itemIds.forEach(path -> alias(ITEMS, path));
        for (MachineKind kind : MachineKind.values()) {
            alias(BLOCKS, kind.id); alias(ENTITIES, kind.id);
        }
        alias(BLOCKS, "server_shop_terminal"); alias(ENTITIES, "server_shop_terminal");
        alias(BLOCKS, "real_estate_terminal"); alias(ENTITIES, "real_estate_terminal");
        alias(BLOCKS, "job_terminal"); alias(ENTITIES, "job_terminal");
        for (String path : List.of("machine", "wallet", "document", "server_shop_terminal", "server_shop_editor", "auction", "real_estate", "job_terminal")) alias(MENUS, path);
        SOUND_EVENTS.keySet().forEach(path -> alias(SOUNDS, path));
        TABS.addAlias(LegacyCompatibility.id(LegacyCompatibility.MOD_ID),
                ResourceLocation.fromNamespaceAndPath(Kuronomics.MODID, "kuronomics"));
    }
    private ModContent() {}
    private static <T> void alias(DeferredRegister<T> register, String path) {
        register.addAlias(LegacyCompatibility.id(path), ResourceLocation.fromNamespaceAndPath(Kuronomics.MODID, path));
    }
    public static SoundEvent sound(String name) { return SOUND_EVENTS.get(name).get(); }
    public static net.minecraft.world.level.block.Block block(MachineKind kind) {
        return MACHINES.get(kind).get();
    }
    public static void play(net.minecraft.world.level.Level level, net.minecraft.core.BlockPos pos, String name) {
        level.playSound(null, pos, sound(name), net.minecraft.sounds.SoundSource.BLOCKS, .25f, 1f);
    }
    public static void register(IEventBus bus) { BLOCKS.register(bus); ITEMS.register(bus); ENTITIES.register(bus); MENUS.register(bus); SOUNDS.register(bus); TABS.register(bus); }
    public static int cashValue(ItemStack stack) {
        if (stack.isEmpty()) return 0;
        Map<Item, Integer> values = cashValues;
        if (values == null) {
            synchronized (ModContent.class) {
                values = cashValues;
                if (values == null) {
                    Map<Item, Integer> registered = new HashMap<>();
                    CASH.forEach((value, item) -> registered.put(item.get(), value));
                    cashValues = values = Map.copyOf(registered);
                }
            }
        }
        return values.getOrDefault(stack.getItem(), 0);
    }
}
