package de.kuro.kuronomics.estate.market;

import de.kuro.kuronomics.economy.EconomyService;
import de.kuro.kuronomics.economy.Money;
import de.kuro.kuronomics.economy.tax.MarketplaceTaxService;
import de.kuro.kuronomics.estate.PropertyLockStore;
import de.kuro.kuronomics.estate.PropertyService;
import de.kuro.kuronomics.estate.market.inbox.RealEstateInboxService;
import de.kuro.kuronomics.menu.RealEstateMenu;
import de.kuro.kuronomics.util.PlayerNames;
import java.util.*;
import java.util.function.LongSupplier;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;

public final class RealEstateMarketService {
    public record CreateResult(RealEstateListing listing, boolean created) {}
    public record PurchaseResult(RealEstateListing listing, UUID settlementId, boolean completed, boolean replayed) {}
    private final MinecraftServer server;
    private final RealEstateMarketStore store;
    private final PropertyService properties;
    private final LongSupplier clock;

    public RealEstateMarketService(MinecraftServer server) { this(server, System::currentTimeMillis); }
    public RealEstateMarketService(MinecraftServer server, LongSupplier clock) {
        this.server = Objects.requireNonNull(server); this.clock = Objects.requireNonNull(clock); checkThread();
        store = RealEstateMarketStore.get(server); properties = new PropertyService(server);
    }

    public boolean available() { return properties.integrationAvailable(); }
    public record CatalogPage(List<RealEstateListing> listings, int total, int page) {}
    /** Browsing only checks visible candidates. Full API queries and purchases still check the actual state. */
    public CatalogPage catalogPage(String query, int sort, UUID seller, int requestedPage, int pageSize) {
        checkThread(); if (pageSize < 1 || pageSize > 32) throw new IllegalArgumentException("Invalid catalog page size");
        if (!available()) return new CatalogPage(List.of(), 0, 0);
        String clean = text(query, "Search", 64, false); int checked = 0;
        while (true) {
            var source = RealEstateCatalog.view(server, store, clean, sort, seller);
            int page = Math.max(0, Math.min(requestedPage, Math.max(0, (source.size() - 1) / pageSize)));
            int start = page * pageSize, end = Math.min(source.size(), start + pageSize);
            List<RealEstateListing> visible = new ArrayList<>(); boolean invalid = false;
            for (int i = start; i < end; i++) {
                var listing = revalidate(source.get(i)); checked++;
                if (listing.state() == RealEstateListing.State.ACTIVE) visible.add(listing); else invalid = true;
            }
            if (!invalid || checked >= 32) return new CatalogPage(List.copyOf(visible),
                    invalid ? RealEstateCatalog.view(server, store, clean, sort, seller).size() : source.size(), page);
            // Refill the page if outside edits removed candidates, without scanning the whole catalog.
        }
    }

    public CreateResult createListing(UUID requestId, UUID sellerId, String propertyId,
                                      String name, String location, String description, long price) {
        checkThread(); properties.requireIntegration(); Objects.requireNonNull(requestId); Objects.requireNonNull(sellerId);
        Objects.requireNonNull(propertyId); Money.positive(price);
        String cleanPropertyId = text(propertyId, "Property reference", 512, true);
        String cleanName = text(name, "Listing name", RealEstateListing.NAME_LIMIT, true);
        String cleanLocation = text(location, "Location", RealEstateListing.LOCATION_LIMIT, true);
        String cleanDescription = text(description, "Description", RealEstateListing.DESCRIPTION_LIMIT, false);
        String fingerprint = fingerprint(sellerId, cleanPropertyId, cleanName, cleanLocation, cleanDescription, price);
        RealEstateListing replay = store.byReference(requestId);
        if (replay != null) {
            if (!replay.creationFingerprint().equals(fingerprint))
                throw new IllegalArgumentException("Listing request was already used for different property details.");
            return new CreateResult(replay, false);
        }
        UUID listingId = EconomyService.stableReference("real-estate-listing", requestId.toString());
        RealEstateListing collision = store.listing(listingId);
        if (collision != null) throw new IllegalStateException("Real-estate listing ID collision");
        if (store.activeForProperty(cleanPropertyId) != null) throw new IllegalArgumentException("This property is already listed.");
        PropertyService.PropertyDetails details = properties.requireMarketable(sellerId, cleanPropertyId, listingId);
        int taxBasisPoints = MarketplaceTaxService.configuredBasisPoints(MarketplaceTaxService.SourceType.REAL_ESTATE_SALE);
        long now = clock.getAsLong();
        if (now < 0) throw new IllegalStateException("Invalid server time");
        RealEstateListing listing = new RealEstateListing(listingId, requestId, fingerprint, cleanPropertyId, sellerId,
                cleanName, cleanLocation, cleanDescription, price, details.displayName(), details.dimension(),
                details.width(), details.length(), taxBasisPoints, RealEstateListing.State.ACTIVE, "", 0, now, now);
        properties.lockForMarketplace(sellerId, cleanPropertyId, listingId);
        try { store.add(listing); save(); }
        catch (RuntimeException failure) {
            try { properties.unlockMarketplace(sellerId, cleanPropertyId, listingId); }
            catch (RuntimeException rollback) { failure.addSuppressed(rollback); }
            throw failure;
        }
        new RealEstateInboxService(server).listed(listing);
        RealEstateMenu.refreshListing(server, listing.id());
        return new CreateResult(listing, true);
    }

    public Optional<RealEstateListing> getListing(UUID listingId) {
        checkThread(); RealEstateListing listing = store.listing(Objects.requireNonNull(listingId));
        if (listing == null) return Optional.empty();
        return Optional.of(listing.state() == RealEstateListing.State.ACTIVE ? revalidate(listing) : listing);
    }

    public List<RealEstateListing> activeListings() {
        checkThread(); if (!available()) return List.of();
        List<RealEstateListing> result = new ArrayList<>();
        for (RealEstateListing listing : store.activeListings()) {
            RealEstateListing current = revalidate(listing);
            if (current.state() == RealEstateListing.State.ACTIVE) result.add(current);
        }
        return List.copyOf(result);  
    }

    public List<RealEstateListing> search(String query) {
        checkThread(); String term = text(query, "Search", 64, false).toLowerCase(Locale.ROOT);
        if (term.isEmpty()) return activeListings();
        List<String> terms = Arrays.stream(term.split("\\s+")).filter(value -> !value.isBlank()).distinct().toList();
        return activeListings().stream().filter(listing -> {
            String searchable = searchable(listing); return terms.stream().allMatch(searchable::contains);
        }).toList();
    }

    public List<RealEstateListing> getSellerListings(UUID sellerId) {
        checkThread(); Objects.requireNonNull(sellerId); if (!available()) return List.of();
        List<RealEstateListing> result = new ArrayList<>();
        for (RealEstateListing listing : store.sellerListings(sellerId)) {
            RealEstateListing current = revalidate(listing);
            result.add(current);
        }
        return sorted(result);
    }

    public RealEstateListing updateListing(UUID sellerId, UUID listingId, long expectedRevision,
                                           String name, String location, String description, long price) {
        checkThread(); properties.requireIntegration(); Money.positive(price);
        RealEstateListing listing = requireSellerActive(sellerId, listingId, expectedRevision);
        listing = revalidate(listing);
        if (listing.state() != RealEstateListing.State.ACTIVE)
            throw new IllegalStateException("This listing is no longer active: " + listing.invalidReason());
        RealEstateListing updated = listing.metadata(text(name, "Listing name", RealEstateListing.NAME_LIMIT, true),
                text(location, "Location", RealEstateListing.LOCATION_LIMIT, true),
                text(description, "Description", RealEstateListing.DESCRIPTION_LIMIT, false), price, now());
        store.replace(updated); save();
        if (updated.price() != listing.price()) new RealEstateInboxService(server).priceChanged(updated, listing.price());
        RealEstateMenu.refreshListing(server, updated.id());
        return updated;
    }

    public RealEstateListing cancelListing(UUID sellerId, UUID listingId, long expectedRevision) {
        checkThread(); RealEstateListing listing = requireSellerActive(sellerId, listingId, expectedRevision);
        RealEstateListing cancelled = listing.transition(RealEstateListing.State.CANCELLED, "", now());
        store.replace(cancelled); save(); releaseMatchingLock(cancelled);
        new RealEstateInboxService(server).cancelled(cancelled); RealEstateMenu.refreshListing(server, cancelled.id());
        return cancelled;
    }

    public PurchaseResult purchase(UUID requestId, UUID buyerId, UUID listingId, long expectedRevision) {
        checkThread(); properties.requireIntegration(); Objects.requireNonNull(requestId); Objects.requireNonNull(buyerId); Objects.requireNonNull(listingId);
        RealEstateMarketStore.PurchaseSettlement existing = store.settlement(listingId);
        if (existing != null) {
            if (!existing.buyerId().equals(buyerId)) throw new IllegalStateException("This property is already being purchased or has been sold.");
            boolean complete = advance(existing);
            return new PurchaseResult(requireListing(listingId), existing.id(), complete, true);
        }
        RealEstateListing listing = requireListing(listingId);
        if (properties.awaitingRegistration(listing.propertyId()))
            throw new IllegalStateException("This property needs administrator review with /estate setprice.");
        if (listing.state() != RealEstateListing.State.ACTIVE) throw new IllegalStateException("This listing is no longer active.");
        if (listing.revision() != expectedRevision) throw new IllegalStateException("This listing changed. Review it and try again.");
        if (listing.sellerId().equals(buyerId)) throw new IllegalArgumentException("You cannot buy your own property.");
        listing = revalidate(listing);
        if (listing.state() != RealEstateListing.State.ACTIVE) throw new IllegalStateException("This property is no longer available.");
        if (properties.marketplaceTransferStatus(listing.propertyId(), listing.id(), listing.sellerId(), buyerId)
                != PropertyService.MarketplaceTransferStatus.READY || !properties.isLockedForMarketplace(listing.propertyId(), listing.id(), listing.sellerId()))
            throw new IllegalStateException("The property's ownership or market lock is no longer valid.");
        EconomyService economy = economy(); economy.requireActive(buyerId);
        if (economy.getAvailableBalance(buyerId) < listing.price()) throw new IllegalArgumentException("Your current balance is insufficient.");
        long tax = listing.taxAmount(), net = listing.sellerNetProceeds(), time = now();
        UUID settlementId = EconomyService.stableReference("real-estate-settlement", listing.id().toString());
        RealEstateMarketStore.PurchaseSettlement settlement = new RealEstateMarketStore.PurchaseSettlement(settlementId, requestId,
                listing.id(), listing.sellerId(), buyerId, listing.price(), tax, net,
                RealEstateMarketStore.PurchaseState.CREATED, "", time, time);
        RealEstateListing pending = listing.transition(RealEstateListing.State.SETTLEMENT_PENDING, "", time);
        store.prepareSettlement(settlement); store.replace(pending); save();
        boolean complete = advance(settlement); RealEstateMenu.refreshListing(server, listing.id());
        return new PurchaseResult(requireListing(listingId), settlementId, complete, false);
    }

    /** On startup, fix unfinished writes and changes made outside Kuronomy in WorldGuard. */
    public void recover() {
        checkThread(); if (!available()) return;
        for (RealEstateMarketStore.PurchaseSettlement settlement : List.copyOf(store.settlements())) {
            if (settlement.state() != RealEstateMarketStore.PurchaseState.COMPLETED
                    && settlement.state() != RealEstateMarketStore.PurchaseState.FAILED) {
                try { advance(settlement); }
                catch (RuntimeException pending) { /* Leave the saved state for the next safe recovery attempt. */ }
            }
        }
        for (RealEstateListing listing : List.copyOf(store.activeListings())) revalidate(listing);
        for (PropertyLockStore.Lock lock : properties.marketplaceLocks()) {
            RealEstateListing listing = store.listing(lock.operationId());
            RealEstateMarketStore.PurchaseSettlement settlement = listing == null ? null : store.settlement(listing.id());
            boolean valid = listing != null && listing.propertyId().equals(lock.propertyId()) && listing.sellerId().equals(lock.sellerId())
                    && (listing.state() == RealEstateListing.State.ACTIVE || listing.state() == RealEstateListing.State.SETTLEMENT_PENDING
                    && settlement != null && settlement.state() != RealEstateMarketStore.PurchaseState.FAILED);
            if (!valid && lock.transferTarget() == null)
                properties.unlockMarketplace(lock.sellerId(), lock.propertyId(), lock.operationId());
        }
    }

    public RealEstateListing invalidateListing(UUID listingId, String reason) {
        checkThread(); RealEstateListing listing = Optional.ofNullable(store.listing(Objects.requireNonNull(listingId)))
                .orElseThrow(() -> new IllegalArgumentException("Unknown real-estate listing."));
        if (listing.state() != RealEstateListing.State.ACTIVE) return listing;
        return invalidate(listing, text(reason, "Invalidation reason", RealEstateListing.REASON_LIMIT, true));
    }

    public static long taxAmount(long grossPrice, int taxBasisPoints) {
        return MarketplaceTaxService.calculate(MarketplaceTaxService.SourceType.REAL_ESTATE_SALE,
                grossPrice, taxBasisPoints).tax();
    }
    public static long sellerNetProceeds(long grossPrice, int taxBasisPoints) {
        return MarketplaceTaxService.calculate(MarketplaceTaxService.SourceType.REAL_ESTATE_SALE,
                grossPrice, taxBasisPoints).net();
    }

    private RealEstateListing revalidate(RealEstateListing listing) {
        if (listing.state() != RealEstateListing.State.ACTIVE || !available()) return listing;
        if (properties.awaitingRegistration(listing.propertyId())) return listing;
        try {
            properties.requireMarketable(listing.sellerId(), listing.propertyId(), listing.id());
        } catch (RuntimeException invalid) {
            return invalidate(listing, safeReason(invalid.getMessage()));
        }
        if (properties.lock(listing.propertyId()).isEmpty())
            properties.lockForMarketplace(listing.sellerId(), listing.propertyId(), listing.id());
        return listing;
    }

    private RealEstateListing invalidate(RealEstateListing listing, String reason) {
        RealEstateListing invalid = listing.transition(RealEstateListing.State.INVALIDATED, reason, now());
        store.replace(invalid); save(); releaseMatchingLock(invalid);
        new RealEstateInboxService(server).invalidated(invalid); RealEstateMenu.refreshListing(server, invalid.id());
        return invalid;
    }

    private boolean advance(RealEstateMarketStore.PurchaseSettlement original) {
        RealEstateMarketStore.PurchaseSettlement settlement = store.settlement(original.listingId());
        if (settlement == null || !settlement.id().equals(original.id())) throw new IllegalStateException("Purchase settlement is missing.");
        if (settlement.state() == RealEstateMarketStore.PurchaseState.COMPLETED) return true;
        if (settlement.state() == RealEstateMarketStore.PurchaseState.FAILED)
            throw new IllegalStateException(settlement.failureReason());
        RealEstateListing listing = requireListing(settlement.listingId()); EconomyService economy = economy();
        if (settlement.state() == RealEstateMarketStore.PurchaseState.ROLLBACK_PENDING)
            return finishFailure(settlement, listing);
        if (settlement.state() == RealEstateMarketStore.PurchaseState.CREATED) {
            var status = properties.marketplaceTransferStatus(listing.propertyId(), listing.id(), listing.sellerId(), settlement.buyerId());
            if (status == PropertyService.MarketplaceTransferStatus.UNAVAILABLE) return false;
            if (status != PropertyService.MarketplaceTransferStatus.READY)
                return fail(settlement, listing, "Property could not be validated before payment.");
            try {
                economy.withdraw(settlement.buyerId(), settlement.gross(), debitReference(settlement.id()),
                        "Real Estate purchase: " + listing.listingName()); save();
            } catch (IllegalArgumentException | ArithmeticException failure) {
                return fail(settlement, listing, safeSettlementReason(failure.getMessage()));
            }
            settlement = settlement.progress(RealEstateMarketStore.PurchaseState.PAYMENT_CAPTURED, "", now());
            store.replaceSettlement(settlement); save();
        }
        if (settlement.state() == RealEstateMarketStore.PurchaseState.PAYMENT_CAPTURED) {
            PropertyService.MarketplaceTransferStatus status = properties.marketplaceTransferStatus(listing.propertyId(), listing.id(), listing.sellerId(), settlement.buyerId());
            if (status == PropertyService.MarketplaceTransferStatus.READY) {
                try { properties.transferMarketplaceToBuyer(listing.propertyId(), listing.id(), listing.sellerId(), settlement.buyerId()); }
                catch (RuntimeException transferFailure) {
                    status = properties.marketplaceTransferStatus(listing.propertyId(), listing.id(), listing.sellerId(), settlement.buyerId());
                    if (status == PropertyService.MarketplaceTransferStatus.READY || status == PropertyService.MarketplaceTransferStatus.INVALID)
                        return fail(settlement, listing, safeSettlementReason(transferFailure.getMessage()));
                    if (status == PropertyService.MarketplaceTransferStatus.UNAVAILABLE) {
                        notifyTransferPending(settlement, listing, transferFailure.getMessage()); return false;
                    }
                }
            }
            if (status == PropertyService.MarketplaceTransferStatus.TRANSFERRED) {
                try { properties.transferMarketplaceToBuyer(listing.propertyId(), listing.id(), listing.sellerId(), settlement.buyerId()); }
                catch (RuntimeException partialRecovery) {
                    notifyTransferPending(settlement, listing, partialRecovery.getMessage()); return false;
                }
            }
            status = properties.marketplaceTransferStatus(listing.propertyId(), listing.id(), listing.sellerId(), settlement.buyerId());
            if (status == PropertyService.MarketplaceTransferStatus.INVALID)
                return fail(settlement, listing, "Property ownership or validity changed before transfer.");
            if (status == PropertyService.MarketplaceTransferStatus.UNAVAILABLE) {
                notifyTransferPending(settlement, listing, "Property integration is temporarily unavailable."); return false;
            }
            if (status != PropertyService.MarketplaceTransferStatus.TRANSFERRED)
                throw new IllegalStateException("Property transfer remains pending recovery.");
            settlement = settlement.progress(RealEstateMarketStore.PurchaseState.PROPERTY_TRANSFERRED, "", now());
            store.replaceSettlement(settlement); save();
        }
        if (settlement.state() == RealEstateMarketStore.PurchaseState.PROPERTY_TRANSFERRED) {
            UUID payoutReference = payoutReference(settlement.id());
            if (!economy.isTransactionProcessed(payoutReference))
                MarketplaceTaxService.queuePayout(economy, settlement.sellerId(), payoutReference,
                        new MarketplaceTaxService.Breakdown(MarketplaceTaxService.SourceType.REAL_ESTATE_SALE,
                                settlement.gross(), listing.taxBasisPoints(), settlement.tax(), settlement.net()),
                        payoutDescription(listing));
            save(); settlement = settlement.progress(RealEstateMarketStore.PurchaseState.SELLER_PAYOUT_QUEUED, "", now());
            store.replaceSettlement(settlement); save();
        }
        if (settlement.state() == RealEstateMarketStore.PurchaseState.SELLER_PAYOUT_QUEUED) {
            if (listing.state() != RealEstateListing.State.SOLD) {
                listing = listing.transition(RealEstateListing.State.SOLD, "", now()); store.replace(listing); save();
            }
            RealEstateInboxService inbox = new RealEstateInboxService(server);
            boolean newlyNotified = inbox.sold(listing, settlement.buyerId());
            inbox.purchased(listing, settlement.buyerId());
            if (newlyNotified) {
                var seller = server.getPlayerList().getPlayer(settlement.sellerId());
                if (seller != null) seller.sendSystemMessage(Component.literal("[Real Estate] ").withStyle(ChatFormatting.DARK_AQUA)
                        .append(Component.literal("One of your listings has been purchased.").withStyle(ChatFormatting.GREEN)));
            }
            settlement = settlement.progress(RealEstateMarketStore.PurchaseState.COMPLETED, "", now());
            store.replaceSettlement(settlement); save(); RealEstateMenu.refreshListing(server, listing.id()); return true;
        }
        return settlement.state() == RealEstateMarketStore.PurchaseState.COMPLETED;
    }

    private boolean fail(RealEstateMarketStore.PurchaseSettlement settlement, RealEstateListing listing, String reason) {
        PropertyService.MarketplaceTransferStatus status = properties.marketplaceTransferStatus(listing.propertyId(), listing.id(), listing.sellerId(), settlement.buyerId());
        if (status == PropertyService.MarketplaceTransferStatus.TRANSFERRED || status == PropertyService.MarketplaceTransferStatus.UNAVAILABLE)
            throw new IllegalStateException("Property transfer requires recovery before a refund can be finalized.");
        String clean = safeSettlementReason(reason);
        RealEstateMarketStore.PurchaseSettlement rollback = settlement.progress(
                RealEstateMarketStore.PurchaseState.ROLLBACK_PENDING, clean, now());
        store.replaceSettlement(rollback); save();
        return finishFailure(rollback, listing);
    }

    private boolean finishFailure(RealEstateMarketStore.PurchaseSettlement settlement, RealEstateListing listing) {
        PropertyService.MarketplaceTransferStatus status = properties.marketplaceTransferStatus(
                listing.propertyId(), listing.id(), listing.sellerId(), settlement.buyerId());
        boolean debitCaptured = economy().isTransactionProcessed(debitReference(settlement.id()));
        boolean refundProcessed = economy().isTransactionProcessed(refundReference(settlement.id()));
        if (status == PropertyService.MarketplaceTransferStatus.TRANSFERRED && !refundProcessed) {
            RealEstateMarketStore.PurchaseSettlement transferred = settlement.progress(
                    RealEstateMarketStore.PurchaseState.PROPERTY_TRANSFERRED, "", now());
            store.replaceSettlement(transferred); save();
            return advance(transferred);
        }
        if (status == PropertyService.MarketplaceTransferStatus.TRANSFERRED || status == PropertyService.MarketplaceTransferStatus.UNAVAILABLE)
            throw new IllegalStateException("Property rollback requires recovery before it can be finalized.");
        if (debitCaptured) {
            economy().deposit(settlement.buyerId(), settlement.gross(), refundReference(settlement.id()),
                    "Refund for failed Real Estate purchase: " + listing.listingName()); save();
        }
        properties.abortMarketplaceTransfer(listing.propertyId(), listing.id(), listing.sellerId(), settlement.buyerId());
        String clean = settlement.failureReason(); RealEstateListing failedListing = listing.state() == RealEstateListing.State.INVALIDATED
                ? listing : listing.transition(RealEstateListing.State.INVALIDATED, safeReason(clean), now());
        if (failedListing != listing) store.replace(failedListing);
        RealEstateMarketStore.PurchaseSettlement failed = settlement.progress(RealEstateMarketStore.PurchaseState.FAILED, clean, now());
        store.replaceSettlement(failed); save();
        RealEstateInboxService inbox = new RealEstateInboxService(server);
        String buyerMessage = debitCaptured ? "Purchase failed; payment refunded: " + clean : "Purchase failed before payment: " + clean;
        inbox.transferFailed(failedListing, settlement.buyerId(), buyerMessage);
        inbox.transferFailed(failedListing, settlement.sellerId(), "Property sale failed: " + clean);
        RealEstateMenu.refreshListing(server, listing.id()); return false;
    }

    private void notifyTransferPending(RealEstateMarketStore.PurchaseSettlement settlement, RealEstateListing listing, String reason) {
        new RealEstateInboxService(server).transferFailed(listing, settlement.buyerId(),
                "Payment secured; property transfer is pending recovery: " + safeSettlementReason(reason));
    }

    private void releaseMatchingLock(RealEstateListing listing) {
        properties.lock(listing.propertyId()).filter(lock -> lock.purpose() == PropertyLockStore.Purpose.MARKETPLACE
                && lock.operationId().equals(listing.id()) && lock.sellerId().equals(listing.sellerId()))
                .ifPresent(lock -> properties.unlockMarketplace(listing.sellerId(), listing.propertyId(), listing.id()));
    }

    private RealEstateListing requireSellerActive(UUID sellerId, UUID listingId, long expectedRevision) {
        Objects.requireNonNull(sellerId); Objects.requireNonNull(listingId);
        RealEstateListing listing = Optional.ofNullable(store.listing(listingId))
                .orElseThrow(() -> new IllegalArgumentException("Unknown real-estate listing."));
        if (!listing.sellerId().equals(sellerId)) throw new IllegalArgumentException("You do not own this listing.");
        if (listing.state() != RealEstateListing.State.ACTIVE)
            throw new IllegalStateException("This listing is no longer active.");
        if (listing.revision() != expectedRevision)
            throw new IllegalStateException("This listing changed. Review it and try again.");
        return listing;
    }
    private RealEstateListing requireListing(UUID listingId) {
        return Optional.ofNullable(store.listing(listingId)).orElseThrow(() -> new IllegalArgumentException("Unknown real-estate listing."));
    }
    private EconomyService economy() { return new EconomyService(de.kuro.kuronomics.economy.data.EconomyLedger.get(server)); }
    private static UUID debitReference(UUID settlement) { return EconomyService.stableReference("real-estate-debit", settlement.toString()); }
    private static UUID payoutReference(UUID settlement) { return EconomyService.stableReference("real-estate-payout", settlement.toString()); }
    private static UUID refundReference(UUID settlement) { return EconomyService.stableReference("real-estate-refund", settlement.toString()); }
    private static String payoutDescription(RealEstateListing listing) {
        return "Property Sale " + listing.listingName() + " (gross " + Money.format(listing.price())
                + " - market tax " + Money.format(listing.taxAmount()) + " = net " + Money.format(listing.sellerNetProceeds()) + ")";
    }

    private static List<RealEstateListing> sorted(Collection<RealEstateListing> listings) {
        return listings.stream().sorted(Comparator.comparingLong(RealEstateListing::createdAt).reversed()
                .thenComparing(RealEstateListing::id)).toList();
    }
    private String searchable(RealEstateListing listing) {
        return String.join(" ", listing.listingName(), listing.location(), listing.description(),
                listing.propertyName(), listing.dimension(), PlayerNames.get(server, listing.sellerId())).toLowerCase(Locale.ROOT);
    }
    public static int configuredTaxBasisPoints() {
        return MarketplaceTaxService.configuredBasisPoints(MarketplaceTaxService.SourceType.REAL_ESTATE_SALE);
    }
    private static String fingerprint(UUID seller, String property, String name, String location, String description, long price) {
        return seller + "|" + field(property) + field(name) + field(location) + field(description) + price;
    }
    private static String field(String value) { return value.length() + ":" + value; }
    private static String text(String value, String label, int max, boolean required) {
        if (value == null) value = ""; String cleaned = value.strip();
        if (required && cleaned.isEmpty()) throw new IllegalArgumentException(label + " cannot be empty.");
        if (cleaned.length() > max) throw new IllegalArgumentException(label + " is too long (maximum " + max + " characters).");
        if (cleaned.chars().anyMatch(Character::isISOControl)) throw new IllegalArgumentException(label + " contains unsupported control characters.");
        return cleaned;
    }
    private static String safeReason(String value) {
        String reason = value == null ? "" : value.chars().map(character -> Character.isISOControl(character) ? ' ' : character)
                .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append).toString().strip();
        if (reason.isEmpty()) reason = "Property ownership or validity changed.";
        return reason.length() <= RealEstateListing.REASON_LIMIT ? reason : reason.substring(0, RealEstateListing.REASON_LIMIT);
    }
    private static String safeSettlementReason(String value) {
        String reason = safeReason(value); return reason.length() <= 256 ? reason : reason.substring(0, 256);
    }
    private long now() { long value = clock.getAsLong(); if (value < 0) throw new IllegalStateException("Invalid server time"); return value; }
    private void save() { de.kuro.kuronomics.economy.FinancialPersistence.save(server); }
    private void checkThread() { if (!server.isSameThread()) throw new IllegalStateException("Real-estate market accessed off server thread"); }
}
