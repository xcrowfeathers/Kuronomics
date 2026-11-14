package de.kuro.kuronomics.client;

import de.kuro.kuronomics.menu.JobMenu;
import de.kuro.kuronomics.network.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.neoforged.neoforge.network.PacketDistributor;

/** Positions use the texture's 340x230 pixel grid. */
public final class JobScreen extends AbstractContainerScreen<JobMenu> {
    private static final int W = 340, H = 230, TEXT = 0xFF293044, MUTED = 0xFF65728C;
    private static final ScreenBackground CATALOG = new ScreenBackground("job_terminal", W, H);
    private static final ScreenBackground DETAILS = new ScreenBackground("job_details", W, H);
    private static final ScreenBackground APPLY = new ScreenBackground("job_application", W, H);
    private static final ScreenBackground EDITOR = new ScreenBackground("job_terminal_admin", W, H);
    private static final ScreenBackground APPLICATIONS = new ScreenBackground("job_applications_admin", W, H);
    private static final ScreenBackground APPLICATION_DETAILS = new ScreenBackground("job_application_details", W, H);
    private final List<Button> rowButtons = new ArrayList<>(), rowUp = new ArrayList<>(), rowDown = new ArrayList<>();
    private Button previous, next, back, add, action, cancel, save, remove, toggle, textUp, textDown;
    private MultiLineEditBox applicationInput, descriptionInput;
    private String oldView = "", oldSelection = "";
    private int textScroll;
    public JobScreen(JobMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title); imageWidth = W; imageHeight = H; inventoryLabelY = 10_000;
    }
    private Button button(String label, int x, int y, int width, Runnable callback) {
        return addRenderableWidget(Button.builder(Component.literal(label), ignored -> callback.run())
                .bounds(leftPos + x, topPos + y, width, 20).build(builder -> new TerminalButton(builder, TerminalButton.JOB)));
    }
    private void send(String command, String id, UUID application, String text) {
        var s = menu.state;
        PacketDistributor.sendToServer(new JobAction(menu.containerId, command, id, application, UUID.randomUUID(), text,
                s.terminalRevision(), s.registryRevision(), s.applicationRevision()));
    }
    private void move(String action, int row) {
        if (row < menu.state.offers().size()) send(action, menu.state.offers().get(row).id(), JobAction.NONE, "");
    }
    private void send(String command) { send(command, menu.state.selectedJob(), menu.state.selectedApplication(), ""); }
    @Override protected void init() {
        super.init(); rowButtons.clear(); rowUp.clear(); rowDown.clear(); oldView = ""; oldSelection = "";
        for (int i = 0; i < 4; i++) {
            final int row = i;
            rowUp.add(button("↑", 205, 46 + i * 35, 24, () -> move("up", row)));
            rowDown.add(button("↓", 232, 46 + i * 35, 24, () -> move("down", row)));
            rowButtons.add(button("Details", 259, 46 + i * 35, 69, () -> {
                var s = menu.state;
                if (s.view().equals("applications")) {
                    if (row < s.applications().size()) send("details", "", s.applications().get(row).id(), "");
                } else if (row < s.offers().size()) send(s.view().equals("add") ? "add" : "details", s.offers().get(row).id(), JobAction.NONE, "");
            }));
        }
        previous = button("‹", 17, 192, 28, () -> send("previous"));
        next = button("›", 50, 192, 28, () -> send("next"));
        back = button("Back", 17, 192, 60, () -> send("back"));
        add = button("Add Job", 245, 192, 83, () -> send("add_screen"));
        action = button("Start Job", 214, 192, 114, () -> {
            String view = menu.state.view();
            send(view.equals("details") ? menu.state.currentJobId().equals(menu.state.selectedJob()) ? "leave"
                    : menu.state.offers().getFirst().availability().equals("OPEN") ? "start" : "apply_dialog"
                    : view.equals("apply") ? "submit" : view.equals("application_details") ? "accept" : "toggle",
                    menu.state.selectedJob(), menu.state.selectedApplication(), applicationInput == null ? "" : applicationInput.getValue());
        });
        cancel = button("Reject", 96, 192, 80, () -> send("reject"));
        save = button("Save Description", 170, 192, 158, () -> send("description", menu.state.selectedJob(), JobAction.NONE, descriptionInput.getValue()));
        remove = button("Remove", 87, 192, 74, () -> send("remove"));
        toggle = button("Toggle Apply", 240, 163, 88, () -> send("toggle"));
        
        
        textUp = button("▲", 300, 56, 28, () -> textScroll = Math.max(0, textScroll - 3));
        textDown = button("▼", 300, 138, 28, () -> textScroll += 3);
        applicationInput = new MultiLineEditBox(font, leftPos + 18, topPos + 76, 309, 76,
                Component.literal("Write your application"), Component.literal("Text")) {
            @Override public boolean mouseClicked(double x, double y, int button) { return visible && active && super.mouseClicked(x, y, button); }
        };
        applicationInput.setCharacterLimit(2000); addRenderableWidget(applicationInput);
        descriptionInput = new MultiLineEditBox(font, leftPos + 18, topPos + 76, 309, 76,
                Component.literal("Write a job description"), Component.literal("Description")) {
            @Override public boolean mouseClicked(double x, double y, int button) { return visible && active && super.mouseClicked(x, y, button); }
        };
        descriptionInput.setCharacterLimit(1000); addRenderableWidget(descriptionInput);
        updateVisibility();
    }
    @Override protected void containerTick() {
        super.containerTick(); updateVisibility();
    }
    private void updateVisibility() {
        if (applicationInput == null || descriptionInput == null) return;
        var s = menu.state; String view = s.view();
        if (!oldView.equals(view) || !oldSelection.equals(s.selectedJob())) {
            setFocused(null);
            oldView = view; oldSelection = s.selectedJob(); textScroll = 0;
            applicationInput.setValue("");
            descriptionInput.setValue(view.equals("edit_details") && !s.offers().isEmpty() ? s.offers().getFirst().description() : "");
        }
        boolean listing = view.equals("catalog") || view.equals("editor") || view.equals("add") || view.equals("applications");
        for (int i = 0; i < rowButtons.size(); i++) {
            Button b = rowButtons.get(i); b.visible = listing && i < (view.equals("applications") ? s.applications().size() : s.offers().size());
            b.setMessage(Component.literal(view.equals("add") ? "Add" : view.equals("editor") ? "Edit" : "Details"));
            rowUp.get(i).visible = rowDown.get(i).visible = view.equals("editor") && i < s.offers().size();
            rowUp.get(i).active = s.page() * 4 + i > 0;
            rowDown.get(i).active = s.page() * 4 + i + 1 < s.total();
        }
        previous.visible = next.visible = listing; previous.active = s.page() > 0; next.active = (s.page() + 1) * 4 < s.total();
        back.visible = !view.equals("catalog") && !view.equals("editor") && !view.equals("applications");
        back.setX(leftPos + (view.equals("add") ? 250 : 17)); back.setWidth(view.equals("add") ? 78 : 60);
        back.setMessage(Component.literal(view.equals("apply") ? "Cancel" : "Back"));
        add.visible = view.equals("editor");
        action.visible = view.equals("details") || view.equals("apply") || view.equals("application_details");
        if (action.visible) action.setMessage(Component.literal(view.equals("apply") ? "Submit" : view.equals("application_details") ? "Accept"
                : s.currentJobId().equals(s.selectedJob()) ? "Quit Job"
                : !s.offers().isEmpty() && s.offers().getFirst().availability().equals("APPOINTMENT_REQUIRED") ? "Appointment Only"
                : !s.offers().isEmpty() && s.offers().getFirst().availability().equals("OPEN") ? "Start Job" : "Apply for Job"));
        if (view.equals("details") && !s.offers().isEmpty()) {
            var o = s.offers().getFirst();
            action.active = s.currentJobId().equals(o.id()) || (s.currentJobId().equals("unemployed") && !o.availability().equals("APPOINTMENT_REQUIRED")
                    && (!o.availability().equals("OPEN") || o.slots() == Integer.MAX_VALUE || o.occupied() < o.slots()));
        } else action.active = true;
        cancel.visible = view.equals("application_details"); save.visible = remove.visible = toggle.visible = view.equals("edit_details");
        applicationInput.visible = view.equals("apply"); descriptionInput.visible = view.equals("edit_details");
        textUp.visible = textDown.visible = view.equals("application_details") || view.equals("details");
    }
    @Override protected void renderBg(GuiGraphics g, float tick, int mouseX, int mouseY) {
        ScreenBackground background = switch (menu.state.view()) {
            case "details", "edit_details" -> DETAILS;
            case "apply" -> APPLY;
            case "editor", "add" -> EDITOR;
            case "applications" -> APPLICATIONS;
            case "application_details" -> APPLICATION_DETAILS;
            default -> CATALOG;
        };
        background.draw(g, leftPos, topPos);
    }
    @Override protected void renderLabels(GuiGraphics g, int mouseX, int mouseY) {
        var s = menu.state; String view = s.view();
        String heading = switch (view) {
            case "details" -> "Job Details"; case "edit_details" -> "Edit Job Offer";
            case "apply" -> "Write your application"; case "editor" -> "Job Terminal Editor";
            case "add" -> "Add Existing Job"; case "applications" -> "Pending Applications";
            case "application_details" -> "Application Details"; default -> "Job Terminal";
        };
        g.drawString(font, heading, 18, 12, TEXT, false);
        if (view.equals("catalog")) g.drawString(font, "Current Job: " + clip(s.currentJob(), 210), 18, 23, TEXT, false);
        if (view.equals("catalog") || view.equals("editor") || view.equals("add")) {
            for (int i = 0; i < s.offers().size(); i++) {
                var o = s.offers().get(i); int y = 46 + i * 35;
                g.drawString(font, clip(o.name(), view.equals("editor") ? 180 : 230), 18, y, TEXT, false);
                g.drawString(font, clip(salary(o), view.equals("editor") ? 180 : 230), 18, y + 10, MUTED, false);
                g.drawString(font, clip(slots(o), 108), 18, y + 20, MUTED, false);
                g.drawString(font, availability(o), 132, y + 20, 0xFF236A4A, false);
                g.drawString(font, mode(o), 248, y + 20, MUTED, false);
            }
        }
        if (view.equals("details") || view.equals("edit_details") || view.equals("apply")) {
            if (!s.offers().isEmpty()) {
                var o = s.offers().getFirst();
                g.drawString(font, clip(o.name(), 300), 18, 37, TEXT, false);
                g.drawString(font, clip(salary(o), 305), 18, 51, MUTED, false);
                g.drawString(font, clip(slots(o), 108), 18, 63, MUTED, false);
                String mode=mode(o);
                int modeX=292-font.width(mode);
                String availability=availability(o);
                float scale=Math.min(1f,(modeX-140f)/Math.max(1,font.width(availability)));
                g.pose().pushPose();g.pose().translate(132,63+font.lineHeight*(1-scale)/2f,0);g.pose().scale(scale,scale,1);
                g.drawString(font,availability,0,0,MUTED,false);g.pose().popPose();
                g.drawString(font, mode, modeX, 63, MUTED, false);
                if (view.equals("details")) drawWrapped(g, o.description().isBlank() ? "No description" : o.description(), 18, 80, 270, 8, textScroll);
                if (view.equals("edit_details")) g.drawString(font, "Availability: " + o.availability().replace('_', ' '), 18, 151, MUTED, false);
                if (view.equals("details") && s.currentJobId().equals(o.id())) g.drawString(font, "This is your current job", 18, 174, 0xFF236A4A, false);
                else if (view.equals("details") && !s.currentJobId().equals("unemployed")) g.drawString(font, "Quit your current job first: /jobs quit", 18, 174, MUTED, false);
            }
        }
        if (view.equals("applications")) for (int i = 0; i < s.applications().size(); i++) {
            var a = s.applications().get(i); int y = 47 + i * 35;
            g.drawString(font, clip(a.applicantName(), 220), 18, y, TEXT, false);
            g.drawString(font, clip(a.jobName() + "  •  " + DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(a.submittedAt())), 225), 18, y + 12, MUTED, false);
        }
        if (view.equals("application_details") && !s.applications().isEmpty()) {
            var a = s.applications().getFirst();
            g.drawString(font, clip(a.applicantName(), 300), 18, 36, TEXT, false);
            g.drawString(font, clip(a.jobName(), 300), 18, 48, MUTED, false);
            drawWrapped(g, a.text(), 18, 66, 270, 10, textScroll);
        }
        if (!s.status().isBlank()) g.drawString(font, clip(s.status(), 310), 18, 216, 0xFFAD3546, false);
        if (view.equals("catalog") || view.equals("editor") || view.equals("add") || view.equals("applications"))
            g.drawString(font, "Page " + (s.page() + 1) + " / " + Math.max(1, (s.total() + 3) / 4), 85, 198, MUTED, false);
    }
    private String clip(String value, int width) { return font.plainSubstrByWidth(value, width); }
    private String availability(JobSnapshot.OfferView o) {
        return o.availability().equals("APPOINTMENT_REQUIRED") ? "Appointment Only" : o.availability().equals("OPEN") ? "Open" : "Application Required";
    }
    private String salary(JobSnapshot.OfferView o) {
        if (o.mode().equals("STATIC")) return "Salary: " + money(o.base());
        return o.max() == Long.MAX_VALUE ? "Salary: Task earnings" : "Salary: Up to " + money(o.max());
    }
    private String mode(JobSnapshot.OfferView o) { return o.mode().equals("STATIC") ? "Static" : "Task Based"; }
    private String money(long cents) {
        var format = java.text.NumberFormat.getNumberInstance(Locale.US);
        format.setMinimumFractionDigits(cents % 100 == 0 ? 0 : 2);
        format.setMaximumFractionDigits(2);
        return "$" + format.format(java.math.BigDecimal.valueOf(cents, 2));
    }
    private String slots(JobSnapshot.OfferView o) { return o.slots() == Integer.MAX_VALUE ? "Slots: Unlimited" : "Slots: " + o.occupied() + " / " + o.slots(); }
    private void drawWrapped(GuiGraphics g, String value, int x, int y, int width, int visibleLines, int scroll) {
        List<net.minecraft.util.FormattedCharSequence> lines = new ArrayList<>();
        for (String paragraph : value.split("\\n", -1)) {
            var wrapped = font.split(Component.literal(paragraph.isEmpty() ? " " : paragraph), width);
            lines.addAll(wrapped);
        }
        int start = Math.min(scroll, Math.max(0, lines.size() - visibleLines));
        for (int i = 0; i < visibleLines && start + i < lines.size(); i++) g.drawString(font, lines.get(start + i), x, y + i * 10, TEXT, false);
    }
    @Override public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if ((applicationInput != null && getFocused() == applicationInput && applicationInput.visible
                || descriptionInput != null && getFocused() == descriptionInput && descriptionInput.visible)
                && minecraft != null && minecraft.options.keyInventory.matches(keyCode, scanCode)) return true;
        return super.keyPressed(keyCode, scanCode, modifiers);
    }
    @Override public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        renderBackground(g, mouseX, mouseY, partialTick); super.render(g, mouseX, mouseY, partialTick); renderTooltip(g, mouseX, mouseY);
    }
}
