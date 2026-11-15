var player=srv.getPlayerList().getPlayers().get(0);player.getAbilities().flying=true;player.onUpdateAbilities();
srv.getCommands().performPrefixedCommand(srv.createCommandSourceStack(),'execute in minecraft:the_end run tp @p 34.5 70 3.5 180 18');
srv.getCommands().performPrefixedCommand(srv.createCommandSourceStack(),'item replace entity @p armor.head with create:goggles');
'validation camera equipped';
