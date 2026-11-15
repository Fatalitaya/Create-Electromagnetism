var device=sd.deviceSD.getDevice(new pos(36,-59,0));
var NativeWorld=Java.loadClass('com.electromagnetism.integration.cee.nativebridge.CeeNativeWorld');
var reading=device==null?'removed':String(sd.ticker.electromagnetism$reading(NativeWorld.branchId(device,'main')));
JSON.stringify({damage:cfg.componentDamage.get(),block:String(level.getBlockState(new pos(36,-59,0))),reading:reading,status:String(sd.ticker.electromagnetism$nativeStatus()),progress:String(sd.ticker.electromagnetism$nativeProgress()),blockers:String(sd.ticker.electromagnetism$inspectInputs().blockers())});
