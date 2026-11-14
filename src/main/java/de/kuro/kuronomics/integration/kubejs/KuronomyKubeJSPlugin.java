package de.kuro.kuronomics.integration.kubejs;

import de.kuro.kuronomics.api.event.*;
import dev.latvian.mods.kubejs.event.*;
import dev.latvian.mods.kubejs.plugin.KubeJSPlugin;
import dev.latvian.mods.kubejs.script.BindingRegistry;
import dev.latvian.mods.kubejs.script.ScriptType;
import net.neoforged.bus.api.Event;
import net.neoforged.neoforge.common.NeoForge;

/** Only KubeJS plugin discovery loads this. Always-loaded mod classes must not reference it. */
public final class KuronomyKubeJSPlugin implements KubeJSPlugin {
    private static final EventGroup GROUP = EventGroup.of("KuronomyEvents");
    private static final EventHandler TRANSACTION = GROUP.server("transaction", () -> Notice.class);
    private static final EventHandler PLAYER_PAYDAY = GROUP.server("playerPayday", () -> Notice.class);
    private static final EventHandler COMPANY_PAYDAY = GROUP.server("companyPayday", () -> Notice.class);
    private static final EventHandler COMPANY = GROUP.server("companyChanged", () -> Notice.class);
    private static final EventHandler JOB = GROUP.server("jobChanged", () -> Notice.class);
    private static final EventHandler TASK = GROUP.server("jobTaskCompleted", () -> Notice.class);
    private static final EventHandler CONTRACT = GROUP.server("contractChanged", () -> Notice.class);
    private static final EventHandler PROPERTY = GROUP.server("propertyChanged", () -> Notice.class);
    public static final class Notice implements KubeEvent {
        private final Event data;
        public Notice(Event data) { this.data = data; }
        public Event getData() { return data; }
        public net.minecraft.server.MinecraftServer getServer() { return net.neoforged.neoforge.server.ServerLifecycleHooks.getCurrentServer(); }
    }
    @Override public void registerEvents(EventGroupRegistry registry) { registry.register(GROUP); }
    @Override public void registerBindings(BindingRegistry bindings) {
        if (bindings.type() == ScriptType.SERVER) bindings.add("Kuronomy", new KuronomyScriptApi());
    }
    @Override public void init() {
        listen(EconomyTransactionEvent.class, TRANSACTION); listen(PlayerPaydayCompletedEvent.class, PLAYER_PAYDAY);
        listen(CompanyPaydayCompletedEvent.class, COMPANY_PAYDAY); listen(CompanyChangedEvent.class, COMPANY);
        listen(JobChangedEvent.class, JOB); listen(JobTaskCompletedEvent.class, TASK);
        listen(ContractChangedEvent.class, CONTRACT); listen(PropertyOwnershipChangedEvent.class, PROPERTY);
    }
    private static <E extends Event> void listen(Class<E> type, EventHandler handler) {
        NeoForge.EVENT_BUS.addListener(type, event -> { if (handler.hasListeners()) handler.post(new Notice(event)); });
    }
}
