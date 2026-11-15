var sd=Java.loadClass('com.george_vi.electroenergetics.simulation.infrastructure.InfrastructureSavedData').load(level);
var node=Java.loadClass('com.george_vi.electroenergetics.foundation.nodes.InWorldNode');
var wire=Java.loadClass('com.george_vi.electroenergetics.CEEWireTypes').COPPER.get();
var source=sd.deviceSD.getDevice(new pos(32,-59,0)); source.voltage=10000;source.acFrequency=0;
[0,1].forEach(function(pin){sd.connect(new node(pin,new pos(32,-59,0)),new node(pin,new pos(36,-59,0)),wire);});
JSON.stringify({devices:sd.deviceSD.getDevices().size(),blockers:String(sd.ticker.electromagnetism$inspectInputs().blockers())});
