// Requires pair-source.js globals in the isolated world.
sd.ticker.electromagnetism$saveNative();source.voltage=0;cfg.componentDamage.set(false);cfg.wiresBreak.set(false);
put(36,-59,'electroenergetics:electrical_panel');put(40,-59,'electroenergetics:electrical_panel');
var registry=Java.loadClass('com.george_vi.electroenergetics.CEERegistries').PANEL_ATTACHMENT_TYPE;

var facing=Java.loadClass('net.minecraft.core.Direction').NORTH;
function attach(x,type){
    var position=new pos(x,-59,0);
    var panel=sd.deviceSD.getDevice(position);
    var attachmentType=registry.get(rl.parse('electroenergetics:'+type));
    var slot=attachmentType.mode.possibleSlots[0];
    var part=attachmentType.createNew(position,attachmentType.mode.getNodesFor(position,slot),level,slot,facing,level.registryAccess());
    part.electromagnetism$initializePart();panel.attachments[slot.ordinal()]=part;
    level.getBlockEntity(position).setAttachments(panel.attachments);panel.update();
    return part;
}
var panelGauge=attach(36,'voltmeter');var panelBreaker=attach(40,'miniature_circuit_breaker');
panelBreaker.setAmperage=1;panelBreaker.isClosed=true;
JSON.stringify({gauge:String(panelGauge),breaker:String(panelBreaker),blockers:String(sd.ticker.electromagnetism$inspectInputs().blockers())});
