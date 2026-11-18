var BasicStack=Java.loadClass('net.minecraft.world.item.ItemStack');
var basicLayout=[];
var basicRows=[{z:10,name:'single',loads:[{x:30,r:24}]},{z:18,name:'series',loads:[{x:30,r:12},{x:34,r:12}]},{z:26,name:'parallel',loads:[{x:30,r:48},{x:34,r:48}]}];
basicRows.forEach(function(row){
 [{x:18,kind:'creative_battery',role:'source'},{x:22,kind:'cut_off_switch',role:'switch'},{x:26,kind:'ammeter',role:'ammeter'},{x:38,kind:'voltmeter',role:'voltmeter'}].forEach(function(item){item.z=row.z;item.row=row.name;basicLayout.push(item);});
 row.loads.forEach(function(item,index){basicLayout.push({x:item.x,z:row.z,row:row.name,kind:'creative_resistor',role:'load'+index,resistance:item.r});});
});
for(var x=16;x<=40;x++)for(var z=8;z<=28;z++)for(var y=-60;y<=-58;y++)if(!basicLevel.isEmptyBlock(new BasicPos(x,y,z)))throw new Error('Occupied build area '+x+','+y+','+z);
var basicFloor=BasicRegistries.BLOCK.get(BasicResource.parse('minecraft:smooth_stone')).defaultBlockState();
for(var x=16;x<=40;x++)for(var z=8;z<=28;z++)basicLevel.setBlockAndUpdate(new BasicPos(x,-60,z),basicFloor);
basicLayout.forEach(function(item){
 var at=new BasicPos(item.x,-59,item.z);var block=BasicRegistries.BLOCK.get(BasicResource.parse('electroenergetics:'+item.kind));var state=block.defaultBlockState();
 basicLevel.setBlockAndUpdate(at.south(),basicFloor);
 basicLevel.setBlockAndUpdate(at,state);
 block.setPlacedBy(basicLevel,at,state,null,new BasicStack(block.asItem()));
});
JSON.stringify({dimension:'minecraft:overworld',deviceY:-59,rows:basicRows,devices:basicLayout});
