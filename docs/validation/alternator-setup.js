// Copied save only: open-circuit DC alternator, creative drive at 16 rpm, one permanent stator.
var cmds=srv.getCommands();var stack=srv.createCommandSourceStack();
function command(s){return cmds.performPrefixedCommand(stack,'execute in minecraft:the_end run '+s);}
command('fill 30 70 -2 40 74 4 minecraft:air');
command('fill 30 69 -2 40 69 4 minecraft:stone');
command('setblock 32 70 0 create:creative_motor[facing=east]');
command('setblock 33 70 0 electroenergetics:alternator_rotor[axis=x]');
command('setblock 34 70 0 electroenergetics:alternator_brushes[facing=east]');
command('setblock 33 70 1 electroenergetics:stator[facing=north]');
var Stator=Java.loadClass('com.george_vi.electroenergetics.content.rotor.StatorBlock');var Axis=Java.loadClass('net.minecraft.core.Direction$Axis');var sp=new pos(33,70,1);var st=level.getBlockState(sp);if(Stator.getRotorAxis(st)!==Axis.X)level.setBlockAndUpdate(sp,st.cycle(Stator.ROLL));
JSON.stringify({brush:String(level.getBlockState(new pos(34,70,0))),stator:String(level.getBlockState(sp)),axis:String(Stator.getRotorAxis(level.getBlockState(sp)))});
