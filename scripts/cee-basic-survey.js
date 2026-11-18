var basicLevel=basicServer.overworld();
var BasicPos=Java.loadClass('net.minecraft.core.BlockPos');
var basicObstacles=[];
for(var x=16;x<=44;x++)for(var z=8;z<=28;z++)for(var y=-60;y<=-56;y++){
 var at=new BasicPos(x,y,z);if(!basicLevel.isEmptyBlock(at)&&basicObstacles.length<20)basicObstacles.push({x:x,y:y,z:z,state:String(basicLevel.getBlockState(at))});
}
var basicData=Java.loadClass('com.george_vi.electroenergetics.simulation.infrastructure.InfrastructureSavedData').load(basicLevel);
JSON.stringify({obstacles:basicObstacles,status:String(basicData.ticker.electromagnetism$nativeStatus()),progress:String(basicData.ticker.electromagnetism$nativeProgress())});
