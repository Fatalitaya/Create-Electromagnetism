sd.ticker.electromagnetism$saveNative();
sd.connect(new node(0,new pos(32,-59,0)),new node(0,new pos(40,-59,0)),wire);
sd.connect(new node(1,new pos(32,-59,0)),new node(0,new pos(40,-58,0)),wire);
sd.connect(new node(0,new pos(32,-59,0)),new node(0,new pos(44,-59,0)),wire);
sd.connect(new node(1,new pos(32,-59,0)),new node(1,new pos(44,-59,0)),wire);
JSON.stringify({blockers:String(sd.ticker.electromagnetism$inspectInputs().blockers()),devices:sd.deviceSD.getDevices().size()});
