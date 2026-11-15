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
                        .then(Commands.literal("status").executes(context->{
                            var source=context.getSource();var access=(CeeSimulationAccess)InfrastructureSavedData.load(source.getLevel()).ticker;
                            var status=access.electromagnetism$nativeStatus();
                            source.sendSuccess(()->Component.literal(status.status()+": "+status.reason()),false);
                            access.electromagnetism$nativeProgress().ifPresent(p->source.sendSuccess(()->Component.literal(
                                    "Accepted="+p.acceptedTime()+" s; target="+p.targetTime()+" s; backlog="+p.backlogSeconds()
                                            +" s; excluded="+p.excludedSeconds()+" s; queue="+p.queuedBoundaries()+"/256; bytes="+p.encodedBytes()
                                            +"/16777216; pause="+p.pauseReason()+"; missing=["+p.missingStart()+", "+p.missingEnd()
                                            +"]; pending events="+p.pendingEvents()),false));return 1;
                        }))
                        .then(Commands.literal("resume_missing_inputs").executes(context->{
                            var source=context.getSource();var access=(CeeSimulationAccess)InfrastructureSavedData.load(source.getLevel()).ticker;
                            try {access.electromagnetism$resumeMissingInputs();}
                            catch(IllegalStateException failure) {source.sendFailure(Component.literal(failure.getMessage()));return 0;}
                            source.sendSuccess(()->Component.literal("Missing-input interval closed explicitly; retained history will resume. Missing time will not be simulated."),true);
                            return 1;
                        }))));
    }
}
