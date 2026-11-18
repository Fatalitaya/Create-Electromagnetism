var basicServer=Java.loadClass('net.neoforged.neoforge.server.ServerLifecycleHooks').getCurrentServer();
var basicPlayers=[];
basicServer.getPlayerList().getPlayers().forEach(function(player){basicPlayers.push({name:String(player.getName().getString()),dimension:String(player.serverLevel().dimension().location()),x:player.getX(),y:player.getY(),z:player.getZ()});});
JSON.stringify({players:basicPlayers,overworld:String(basicServer.overworld().dimension().location())});
