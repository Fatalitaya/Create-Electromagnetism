var BasicScroll=Java.loadClass('com.simibubi.create.foundation.blockEntity.behaviour.scrollValue.ScrollValueBehaviour');
var BasicNode=Java.loadClass('com.george_vi.electroenergetics.foundation.nodes.InWorldNode');
var BasicWire=Java.loadClass('com.george_vi.electroenergetics.simulation.infrastructure.WireData');
var BasicWireTypes=Java.loadClass('com.george_vi.electroenergetics.CEEWireTypes');
basicLayout.forEach(function(item){
 var at=new BasicPos(item.x,-59,item.z);var entity=basicLevel.getBlockEntity(at);var device=basicData.deviceSD.getDevice(at);
 if(!device)throw new Error('Missing device '+item.row+'/'+item.role);
 if(item.role==='source'){entity.getBehaviour(BasicScroll.TYPE).setValue(24000);device.voltage=24;device.acFrequency=0;device.phaseOffset=0;entity.setChanged();entity.sendData();}
 if(item.role.indexOf('load')===0){entity.setResistance(item.resistance);entity.setChanged();entity.sendData();}
});
var basicLinks=[];
function basicLink(row,first,pin,second,otherPin){
 var a=new BasicPos(first,-59,row.z),b=new BasicPos(second,-59,row.z);
 var length=Math.sqrt(a.distSqr(b))+1;
 var wire=BasicWire.ofLength(BasicWireTypes.STANDARD.get(),length);
 var result=basicData.connect(new BasicNode(pin,a),new BasicNode(otherPin,b),wire);
 basicLinks.push({row:row.name,a:[first,pin],b:[second,otherPin],result:String(result),ohms:wire.getResistance()});
}
basicRows.forEach(function(row){
 basicLink(row,18,0,22,0);basicLink(row,22,1,26,0);basicLink(row,26,1,30,0);
 if(row.name==='series'){basicLink(row,30,1,34,0);basicLink(row,34,1,18,1);basicLink(row,30,0,38,0);basicLink(row,34,1,38,1);}
 else {basicLink(row,30,1,18,1);basicLink(row,30,0,38,0);basicLink(row,30,1,38,1);if(row.name==='parallel'){basicLink(row,30,0,34,0);basicLink(row,30,1,34,1);}}
});
basicData.deviceSD.setDirty();basicData.setDirty();
JSON.stringify({links:basicLinks,settings:basicLayout.filter(function(item){return item.role==='source'||item.role.indexOf('load')===0;}).map(function(item){var device=basicData.deviceSD.getDevice(new BasicPos(item.x,-59,item.z));return {row:item.row,role:item.role,value:item.role==='source'?device.voltage:device.properties.resistance};})});
