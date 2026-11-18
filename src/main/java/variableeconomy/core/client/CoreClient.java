package variableeconomy.core.client;

import variableeconomy.core.CoreModule;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.ModContainer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;
import net.neoforged.neoforge.common.NeoForge;
import variableeconomy.core.menu.BankMenu;

/** The bank's client side: its screen. Loaded only on the client. */
@Mod(value = CoreModule.MOD_ID, dist = Dist.CLIENT)
public final class CoreClient {
    /** The mod's client entry point, constructed on the client only. */
    public CoreClient(IEventBus modBus, ModContainer container) {
        modBus.addListener((RegisterMenuScreensEvent event) -> event.register(BankMenu.TYPE.get(), BankScreen::new));
        NeoForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingOut event) -> ClientBankState.clear());
    }
}
