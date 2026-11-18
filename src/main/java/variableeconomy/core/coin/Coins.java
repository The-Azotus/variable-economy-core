package variableeconomy.core.coin;

import variableeconomy.core.CoreModule;
import variableeconomy.core.bank.Denomination;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The mod's coins, and the Bank Card.
 * <p>
 * Coins can never be listed in the market: selling one would create money from nothing. That includes Create:
 * Numismatics' coins, should a pack have that mod: selling them would turn its money into bits.
 */
public final class Coins {
    private static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(CoreModule.MOD_ID);
    private static final DeferredRegister<RecipeSerializer<?>> SERIALIZERS =
            DeferredRegister.create(Registries.RECIPE_SERIALIZER, CoreModule.MOD_ID);

    private static final Map<Coin, DeferredItem<CoinItem>> ITEMS_BY_COIN = new EnumMap<>(Coin.class);

    static {
        for (Coin coin : Coin.values()) {
            ITEMS_BY_COIN.put(coin, ITEMS.register(coin.id(), () -> new CoinItem(coin, new Item.Properties())));
        }
    }

    public static final DeferredItem<BankCardItem> BANK_CARD = ITEMS.register("bank_card", () -> new BankCardItem(new Item.Properties()));

    public static final DeferredHolder<RecipeSerializer<?>, CoinRecipe.Serializer> COIN_RECIPE =
            SERIALIZERS.register("coin_exchange", CoinRecipe.Serializer::new);

    /**
     * Numismatics' coins, by ID so they're known without Numismatics' classes. Item tags can't be used: the catalog
     * loads before tags are bound.
     */
    private static final Set<ResourceLocation> NUMISMATICS_COINS = Set.of("spur", "bevel", "sprocket", "cog", "crown", "sun")
            .stream().map(path -> ResourceLocation.fromNamespaceAndPath("numismatics", path))
            .collect(Collectors.toUnmodifiableSet());

    /** See {@link #denominations()}. */
    private static volatile List<Denomination> denominations;

    private Coins() {
    }

    public static void register(IEventBus modBus) {
        ITEMS.register(modBus);
        SERIALIZERS.register(modBus);
        modBus.addListener((BuildCreativeModeTabContentsEvent event) -> {
            if (event.getTabKey() == CreativeModeTabs.INGREDIENTS) {
                ITEMS_BY_COIN.values().forEach(event::accept);
            }
            if (event.getTabKey() == CreativeModeTabs.TOOLS_AND_UTILITIES) {
                event.accept(BANK_CARD);
            }
        });
    }

    public static CoinItem item(Coin coin) {
        return ITEMS_BY_COIN.get(coin).get();
    }

    public static Map<Coin, DeferredItem<CoinItem>> items() {
        return Collections.unmodifiableMap(ITEMS_BY_COIN);
    }

    /** The coins as the bank pays them out, largest first: Omega down to Bit. Built on first use, once the items exist. */
    public static List<Denomination> denominations() {
        List<Denomination> cached = denominations;
        if (cached == null) {
            List<Denomination> coins = new ArrayList<>();
            for (Coin coin : Coin.values()) {
                coins.add(0, new Denomination(item(coin), coin.value()));
            }
            cached = List.copyOf(coins);
            denominations = cached;
        }
        return cached;
    }

    /** Whether {@code item} is money: one of our coins, or one of Numismatics' should a pack have that mod. */
    public static boolean isCoin(Item item) {
        return item instanceof CoinItem || isCoinId(BuiltInRegistries.ITEM.getKey(item));
    }

    /** Whether {@code id} names one of our coins or one of Numismatics' (by ID, so it works without that mod). */
    public static boolean isCoinId(ResourceLocation id) {
        return NUMISMATICS_COINS.contains(id)
                || BuiltInRegistries.ITEM.getOptional(id).map(item -> item instanceof CoinItem).orElse(false);
    }
}
