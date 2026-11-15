var srv=Java.loadClass('net.neoforged.neoforge.server.ServerLifecycleHooks').getCurrentServer();
var level=srv.overworld();var pos=Java.loadClass('net.minecraft.core.BlockPos');
var sd=Java.loadClass('com.george_vi.electroenergetics.simulation.infrastructure.InfrastructureSavedData').load(level);
var gaugePanel=sd.deviceSD.getDevice(new pos(36,-59,0));var breakerPanel=sd.deviceSD.getDevice(new pos(40,-59,0));
JSON.stringify({sourceId:String(sd.deviceSD.getDevice(new pos(32,-59,0)).electromagnetism$nativeIdentity()),gaugePanelId:String(gaugePanel.electromagnetism$nativeIdentity()),breakerPanelId:String(breakerPanel.electromagnetism$nativeIdentity()),gaugeValue:gaugePanel.attachments[1].value,breakerClosed:breakerPanel.attachments[7].isClosed,blockers:String(sd.ticker.electromagnetism$inspectInputs().blockers()),status:String(sd.ticker.electromagnetism$nativeStatus()),progress:String(sd.ticker.electromagnetism$nativeProgress())});
