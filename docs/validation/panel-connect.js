[panelGauge,panelBreaker].forEach(function(part){[0,1].forEach(function(pin){sd.connect(new node(pin,new pos(32,-59,0)),part.nodes[pin],wire);});});
source.voltage=20000;JSON.stringify({damage:cfg.componentDamage.get(),volts:source.voltage,blockers:String(sd.ticker.electromagnetism$inspectInputs().blockers())});
