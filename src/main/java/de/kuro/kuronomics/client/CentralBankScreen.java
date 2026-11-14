package de.kuro.kuronomics.client;

import de.kuro.kuronomics.economy.Money;
import de.kuro.kuronomics.menu.CentralBankMenu;
import de.kuro.kuronomics.network.*;
import java.util.*;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.PlayerFaceRenderer;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.neoforged.neoforge.network.PacketDistributor;

public final class CentralBankScreen extends AbstractContainerScreen<CentralBankMenu> {
    private static final ScreenBackground BACKGROUND=new ScreenBackground("central_bank",300,210);
    private static final int TEXT=0xFF39444C,MUTED=0xFF667078,GREEN=0xFF34733B,RED=0xFFAA3030;
    private Button previous,next,back,period;
    public CentralBankScreen(CentralBankMenu menu,Inventory inventory,Component title){super(menu,inventory,title);imageWidth=300;imageHeight=210;inventoryLabelY=10000;}
    private Button button(String text,int x,int width,Runnable action){return addRenderableWidget(Button.builder(Component.literal(text),b->action.run()).bounds(leftPos+x,topPos+182,width,20).build(builder->new TerminalButton(builder,TerminalButton.BANK)));}
    private void send(String action){PacketDistributor.sendToServer(new BankAction(menu.containerId,action,UUID.randomUUID()));}
    @Override protected void init(){
        super.init();topPos=Math.max(0,(height-imageHeight-14)/2);
        previous=button("<",16,24,()->send("previous"));next=button(">",44,24,()->send("next"));
        period=button("Today",16,112,()->send("period"));back=button("Refresh",136,70,()->send(menu.state.category().isEmpty()?"refresh":"main"));button("Close",214,70,()->send("close"));
        updateControls();
    }
    @Override protected void containerTick(){super.containerTick();updateControls();}
    private void updateControls(){
        if(previous==null)return;var s=menu.state;boolean details=!s.category().isEmpty();
        previous.visible=next.visible=details;previous.active=s.page()>0;next.active=(s.page()+1)*5<s.total();
        period.setX(leftPos+(details?77:16));period.setWidth(details?51:112);
        period.setMessage(Component.literal(s.window()?(details?s.historyDays()+" days":"Last "+s.historyDays()+" days"):"Today"));
        back.setMessage(Component.literal(details?"Back":"Refresh"));
    }
    private String clip(String text,int width){return font.width(text)<=width?text:font.plainSubstrByWidth(text,Math.max(0,width-font.width("…")))+"…";}
    private void text(GuiGraphics g,String value,int x,int y,int color){g.drawString(font,value,x,y,color,false);}
    private void right(GuiGraphics g,String value,int x,int y,int color){text(g,value,x-font.width(value),y,color);}
    private int amountColor(String value){return value.startsWith("-")?RED:value.startsWith("+")?GREEN:MUTED;}
    private BankSnapshot metricSnapshot;
    private List<BankSnapshot.Metric> visibleMetrics=List.of();
    private List<BankSnapshot.Metric> metrics(){
        if(metricSnapshot!=menu.state){metricSnapshot=menu.state;visibleMetrics=menu.state.metrics().stream().filter(m->!m.id().equals("tax_refunds")||m.payments()>0).toList();}
        return visibleMetrics;
    }
    private int metricAt(double x,double y){
        if(x<14||x>=286||y<81)return -1;
        int index=(int)((y-81)/12);return index<metrics().size()?index:-1;
    }
    private void centered(GuiGraphics g,String value,int center,int y,int color){text(g,value,center-font.width(value)/2,y,color);}
    private String categoryLabel(){return menu.state.metrics().stream().filter(m->m.id().equals(menu.state.category())).map(BankSnapshot.Metric::label).findFirst().orElse("History");}
    @Override protected void renderBg(GuiGraphics g,float tick,int x,int y){BACKGROUND.draw(g,leftPos,topPos);}
    @Override protected void renderLabels(GuiGraphics g,int mouseX,int mouseY){
        var s=menu.state;
        centered(g,"Central Bank"+(s.window()&&s.category().isEmpty()?" · Last "+s.historyDays()+" days":""),150,12,TEXT);
        if(s.partial())text(g,"*",278,12,0xFFA66B15);
        text(g,"STATE TREASURY",16,31,MUTED);String position=clip(s.position(),150);right(g,position,284,31,s.rank()>=3?0xFFB86D10:0xFF326AA5);
        centered(g,Money.format(s.balance()),150,47,s.balance()<0?RED:GREEN);
        text(g,"Today · net change",16,65,MUTED);right(g,s.dailyNet(),276,65,s.dailyNet().contains("-")?RED:GREEN);
        if(s.category().isEmpty()) {
            var metrics=metrics();
            for(int i=0;i<metrics.size();i++) {
                var m=metrics.get(i);int y=82+i*12;boolean hovered=metricAt(mouseX-leftPos,mouseY-topPos)==i;
                if(hovered)g.fill(14,y-1,286,y+11,0xFFF5F4F0);
                text(g,m.label(),20,y+1,TEXT);right(g,m.net(),276,y+1,amountColor(m.net()));
                if(hovered)text(g,"›",278,y+1,MUTED);
            }
        } else {
            text(g,clip(categoryLabel()+" · "+(s.window()?"Last "+s.historyDays()+" days":"Today"),210),16,76,TEXT);
            right(g,(s.page()+1)+"/"+Math.max(1,(s.total()+4)/5),284,76,MUTED);
            if(s.history().isEmpty())g.drawCenteredString(font,"No activity in this period",150,120,MUTED);
            for(int i=0;i<s.history().size();i++) {
                var row=s.history().get(i);int y=86+i*18;badge(g,row,17,y);
                String value=row.net();int valueWidth=font.width(value);text(g,clip(row.name(),Math.max(30,240-valueWidth)),38,y,TEXT);right(g,value,282,y,amountColor(value));
                String detail=row.incoming().equals("$0,00")?"Received from bank · "+row.payments()+" payments"
                        :row.outgoing().equals("$0,00")?"Paid to bank · "+row.payments()+" payments":"In "+row.incoming()+"  ·  Out "+row.outgoing();
                text(g,clip(detail,244),38,y+9,MUTED);
            }
        }
    }
    private void badge(GuiGraphics g,BankSnapshot.Row row,int x,int y){
        if(row.kind().equals("PLAYER")) {
            var info=minecraft.getConnection()==null?null:minecraft.getConnection().getPlayerInfo(row.owner());
            PlayerFaceRenderer.draw(g,info==null?DefaultPlayerSkin.get(row.owner()):info.getSkin(),x,y,16);
        } else {
            int color=row.archived()?0xFF7A8187:row.kind().equals("COMPANY")?0xFF426B89:0xFF956F33;
            g.fill(x,y,x+16,y+16,color);String letter=row.archived()?"…":row.kind().equals("COMPANY")?"C":"B";
            g.drawCenteredString(font,letter,x+8,y+4,0xFFFFFFFF);
        }
    }
    @Override public boolean mouseClicked(double x,double y,int button){
        if(button==0 && menu.state.category().isEmpty()) {
            int index=metricAt(x-leftPos,y-topPos);if(index>=0){send("cat_"+metrics().get(index).id());return true;}
        }
        return super.mouseClicked(x,y,button);
    }
    @Override public void render(GuiGraphics g,int x,int y,float tick){
        renderBackground(g,x,y,tick);super.render(g,x,y,tick);renderTooltip(g,x,y);var s=menu.state;
        if(x>=leftPos+16&&x<leftPos+284&&y>=topPos+31&&y<topPos+41&&s.limited())g.renderTooltip(font,Component.literal("Daily remaining · Pay: "+Money.format(s.payRemaining())+" · Debit: "+Money.format(s.debitRemaining())),x,y);
        if(s.category().isEmpty()) {
            var metrics=metrics();for(int i=0;i<metrics.size();i++)if(x>=leftPos+14&&x<leftPos+286&&y>=topPos+81+i*12&&y<topPos+93+i*12){var m=metrics.get(i);g.renderTooltip(font,List.of(Component.literal(m.label()+" · "+m.payments()+" payments"),Component.literal("Bank income: "+m.incoming()),Component.literal("Bank expenses: "+m.outgoing()),Component.literal("Click to view totals by account")),Optional.empty(),x,y);}
        } else for(int i=0;i<s.history().size();i++)if(x>=leftPos+14&&x<leftPos+286&&y>=topPos+85+i*18&&y<topPos+103+i*18){var row=s.history().get(i);g.renderTooltip(font,List.of(Component.literal(row.name()+" · "+row.payments()+" payments"),Component.literal("Paid to bank: "+row.incoming()),Component.literal("Received from bank: "+row.outgoing()),Component.literal("Net to bank: "+row.net())),Optional.empty(),x,y);}
        if(s.partial() && x>=leftPos+270&&x<leftPos+288&&y>=topPos+8&&y<topPos+25)g.renderTooltip(font,Component.literal("Partial history: earlier activity was recorded before this categorized view existed."),x,y);
        if(!s.status().isEmpty())text(g,clip(s.status(),width-16),Math.max(8,leftPos+8),Math.min(topPos+imageHeight+4,height-font.lineHeight-2),0xFFFFAAAA);
    }
}
