level.setBlockAndUpdate(new pos(40,69,0),blocks.get(rl.parse('minecraft:stone')).defaultBlockState());
level.setBlockAndUpdate(new pos(40,70,0),blocks.get(rl.parse('electroenergetics:converter')).defaultBlockState());
var commands=srv.getCommands();var stack=srv.createCommandSourceStack();
commands.performPrefixedCommand(stack,'execute in minecraft:the_end run tp @p 40.5 70 3.5 180 18');
commands.performPrefixedCommand(stack,'item replace entity @p armor.head with create:goggles');
'camera and goggles set in copied fixture';
