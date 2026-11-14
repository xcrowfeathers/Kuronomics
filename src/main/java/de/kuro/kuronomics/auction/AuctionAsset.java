package de.kuro.kuronomics.auction;

import net.minecraft.world.item.ItemStack;

/** The auction house holds the asset. New asset types shouldn't need changes to the auction itself. */
public sealed interface AuctionAsset permits AuctionAsset.ItemAsset, AuctionAsset.PropertyAsset {
    Type type();

    enum Type { ITEM, PROPERTY }

    record ItemAsset(ItemStack stack) implements AuctionAsset {
        public ItemAsset {
            if (stack == null || stack.isEmpty()) throw new IllegalArgumentException("Auction item cannot be empty");
            stack = stack.copy();
        }
        @Override public ItemStack stack() { return stack.copy(); }
        @Override public Type type() { return Type.ITEM; }
    }

    /** The property ID stays the same when its WorldGuard region is renamed. */
    record PropertyAsset(String propertyId, String dimension, String regionKey) implements AuctionAsset {
        public PropertyAsset(String dimension, String regionKey) { this(dimension + "|" + regionKey, dimension, regionKey); }
        public PropertyAsset {
            if (propertyId == null || propertyId.isBlank() || dimension == null || dimension.isBlank() || regionKey == null || regionKey.isBlank())
                throw new IllegalArgumentException("Property auction identity cannot be blank");
        }
        public String displayName() { return regionKey; }
        @Override public Type type() { return Type.PROPERTY; }
    }
}
