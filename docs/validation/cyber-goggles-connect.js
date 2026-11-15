var node=Java.loadClass('com.george_vi.electroenergetics.foundation.nodes.InWorldNode');var wire=Java.loadClass('com.george_vi.electroenergetics.CEEWireTypes').COPPER.get();
var source=sd.deviceSD.getDevice(new pos(32,70,0));source.voltage=24;source.acFrequency=0;
[0,1].forEach(function(pin){sd.connect(new node(pin,new pos(32,70,0)),new node(pin,new pos(36,70,0)),wire);});
JSON.stringify({status:String(sd.ticker.electromagnetism$nativeStatus()),progress:String(sd.ticker.electromagnetism$nativeProgress())});
