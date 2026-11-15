var node=Java.loadClass('com.george_vi.electroenergetics.foundation.nodes.InWorldNode');var wire=Java.loadClass('com.george_vi.electroenergetics.CEEWireTypes').COPPER.get();
[0,1].forEach(function(pin){sd.connect(new node(pin,new pos(34,70,0)),new node(pin,new pos(38,70,0)),wire);});
JSON.stringify({blockers:String(sd.ticker.electromagnetism$inspectInputs().blockers())});
