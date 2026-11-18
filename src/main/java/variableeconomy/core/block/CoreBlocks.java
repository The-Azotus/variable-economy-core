package variableeconomy.core.block;

import variableeconomy.core.CoreModule;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

/** The Bank block and its item. */
public final class CoreBlocks {
    private static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(CoreModule.MOD_ID);
    private static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(CoreModule.MOD_ID);

    public static final DeferredBlock<BankBlock> BANK = BLOCKS.register("bank",
            () -> new BankBlock(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.STONE)
                    .strength(3.5f)
                    .sound(SoundType.STONE)));
    public static final DeferredItem<BlockItem> BANK_ITEM = ITEMS.registerSimpleBlockItem(BANK);

    private CoreBlocks() {
    }

    public static void register(IEventBus modBus) {
        BLOCKS.register(modBus);
        ITEMS.register(modBus);
        modBus.addListener((BuildCreativeModeTabContentsEvent event) -> {
            if (event.getTabKey() == CreativeModeTabs.FUNCTIONAL_BLOCKS) {
                event.accept(BANK_ITEM);
            }
        });
    }
}
