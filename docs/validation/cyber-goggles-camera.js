// Copied validation world only. Keep the camera above the platform.
var p=srv.getPlayerList().getPlayers().get(0);p.getAbilities().flying=true;p.onUpdateAbilities();
srv.getCommands().performPrefixedCommand(srv.createCommandSourceStack(),'execute in minecraft:the_end run tp @p 40.5 70 3.5 180 18');
'camera positioned';
