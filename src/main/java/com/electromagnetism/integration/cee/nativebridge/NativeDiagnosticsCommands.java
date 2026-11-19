package com.electromagnetism.integration.cee.nativebridge;

import com.george_vi.electroenergetics.simulation.infrastructure.InfrastructureSavedData;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/** Administrative operations affect only the command source's dimension. */
public final class NativeDiagnosticsCommands {
    private NativeDiagnosticsCommands() {}
    public static void register(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("electromagnetism").requires(source->source.hasPermission(2))
                .then(Commands.literal("native")
                        .then(Commands.literal("coverage").executes(context->{
                            var source=context.getSource();var sd=InfrastructureSavedData.load(source.getLevel());
                            var inventory=((CeeSimulationAccess)sd.ticker).electromagnetism$inspectInputs();
                            var captured=inventory.inputs().stream().map(input->input.id()).collect(java.util.stream.Collectors.toSet());
                            for(var device:sd.deviceSD.getDevices()) {
                                var identity=device instanceof CeeDeviceIdentity owner?owner.electromagnetism$nativeIdentity():null;
                                String state=captured.contains(identity)?"adapted":"blocked";
                                source.sendSuccess(()->Component.literal(device.type.id()+" at "+device.pos+" id="+identity+" "+state),false);
                            }
                            for(var reason:inventory.blockers())source.sendFailure(Component.literal(reason));
                            source.sendSuccess(()->Component.literal("Captured="+inventory.inputs().size()+"; blockers="+inventory.blockers().size()
                                    +"; runtime capture does not establish in-game validation"),false);
                            return inventory.blockers().isEmpty()?1:0;
                        }))
                        .then(Commands.literal("status").executes(context->{
                            var source=context.getSource();var access=(CeeSimulationAccess)InfrastructureSavedData.load(source.getLevel()).ticker;
                            var status=access.electromagnetism$nativeStatus();
                            source.sendSuccess(()->Component.literal(status.status()+": "+status.reason()),false);
                            access.electromagnetism$nativeProgress().ifPresent(p->source.sendSuccess(()->Component.literal(
                                    "Tick "+p.tick()+" "+p.domains()),false));return 1;
                        }))
                        .then(Commands.literal("resume_missing_inputs").executes(context->{
                            context.getSource().sendSuccess(()->Component.literal("Electrical inputs are retried on the next world tick."),false);
                            return 1;
                        }))));
    }
}
