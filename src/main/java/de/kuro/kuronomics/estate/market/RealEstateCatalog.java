package de.kuro.kuronomics.estate.market;

import java.util.*;
import net.minecraft.server.MinecraftServer;
import de.kuro.kuronomics.util.PlayerNames;

/** Cache small catalog views, not ownership or regions. Trades still check the live data. */
final class RealEstateCatalog {
    private record Key(String query, int sort, UUID seller) {}
    private static final Map<MinecraftServer, RealEstateCatalog> caches = new WeakHashMap<>();
    private List<RealEstateListing> source;
    private int refreshed;
    private final LinkedHashMap<Key, List<RealEstateListing>> views = new LinkedHashMap<>(16, .75f, true);
    static List<RealEstateListing> view(MinecraftServer server, RealEstateMarketStore store, String query, int sort, UUID seller) {
        var cache = caches.computeIfAbsent(server, ignored -> new RealEstateCatalog());
        var source = store.activeListings(); int tick = server.getTickCount();
        if (cache.source != source || tick - cache.refreshed >= 40) {
            cache.source = source; cache.views.clear(); cache.refreshed = tick;
        }
        Key key = new Key(query.strip().toLowerCase(Locale.ROOT), sort, seller);
        var existing = cache.views.get(key); if (existing != null) return existing;
        String[] terms = key.query().isEmpty() ? new String[0] : key.query().split("\\s+");
        Comparator<RealEstateListing> newest = Comparator.comparingLong(RealEstateListing::createdAt).reversed().thenComparing(RealEstateListing::id);
        Comparator<RealEstateListing> order = switch (sort) {
            case 1 -> Comparator.comparingLong(RealEstateListing::price).thenComparing(newest);
            case 2 -> Comparator.comparingLong(RealEstateListing::price).reversed().thenComparing(newest);
            case 3 -> Comparator.comparingLong(RealEstateListing::area).thenComparing(newest);
            case 4 -> Comparator.comparingLong(RealEstateListing::area).reversed().thenComparing(newest);
            default -> newest;
        };
        var stream = source.stream().filter(value -> seller == null || value.sellerId().equals(seller));
        if (terms.length != 0) stream = stream.filter(value -> {
            String text = String.join(" ", value.listingName(), value.location(), value.description(), value.propertyName(), value.dimension(),
                    PlayerNames.get(server, value.sellerId())).toLowerCase(Locale.ROOT);
            for (String term : terms) if (!text.contains(term)) return false; return true;
        });
        List<RealEstateListing> result = (sort == 0 ? stream : stream.sorted(order)).toList();
        if (cache.views.size() >= 32) cache.views.remove(cache.views.keySet().iterator().next());
        cache.views.put(key, result); return result;
    }
}
