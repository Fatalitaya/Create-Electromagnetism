var device=sd.deviceSD.getDevice(new pos(36,70,0));var NativeWorld=Java.loadClass('com.electromagnetism.integration.cee.nativebridge.CeeNativeWorld');
JSON.stringify({status:String(sd.ticker.electromagnetism$nativeStatus()),progress:String(sd.ticker.electromagnetism$nativeProgress()),reading:String(sd.ticker.electromagnetism$reading(NativeWorld.branchId(device,'main')))});
