var commands=srv.getCommands();var stack=srv.createCommandSourceStack();
commands.performPrefixedCommand(stack,'execute in minecraft:the_end run fill 38 70 -1 42 74 4 minecraft:air');
commands.performPrefixedCommand(stack,'execute in minecraft:the_end run setblock 40 69 0 minecraft:stone');
commands.performPrefixedCommand(stack,'execute in minecraft:the_end run setblock 40 70 0 electroenergetics:converter');
'converter placed after End arrival';
