package variableeconomy.core.coin;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.ShapelessRecipe;

/**
 * A shapeless recipe changing 8 coins for the next one up, or one back into 8. Written exactly like
 * {@code minecraft:crafting_shapeless}, with this recipe's type instead.
 * <p>
 * Since 0.6.0 it behaves exactly like {@code minecraft:crafting_shapeless} (coins are always craftable). It is kept
 * so the coin recipes keep their type, {@code variable_economy:coin_exchange}, and packs that change or remove them
 * keep working.
 */
public final class CoinRecipe extends ShapelessRecipe {
    CoinRecipe(ShapelessRecipe base) {
        super(base.getGroup(), base.category(), base.getResultItem(RegistryAccess.EMPTY), base.getIngredients());
    }

    @Override
    public RecipeSerializer<?> getSerializer() {
        return Coins.COIN_RECIPE.get();
    }

    public static final class Serializer implements RecipeSerializer<CoinRecipe> {
        private static final MapCodec<CoinRecipe> CODEC = RecipeSerializer.SHAPELESS_RECIPE.codec()
                .xmap(CoinRecipe::new, recipe -> recipe);
        private static final StreamCodec<RegistryFriendlyByteBuf, CoinRecipe> STREAM_CODEC =
                RecipeSerializer.SHAPELESS_RECIPE.streamCodec().map(CoinRecipe::new, recipe -> recipe);

        @Override
        public MapCodec<CoinRecipe> codec() {
            return CODEC;
        }

        @Override
        public StreamCodec<RegistryFriendlyByteBuf, CoinRecipe> streamCodec() {
            return STREAM_CODEC;
        }
    }
}
