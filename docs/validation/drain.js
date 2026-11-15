// Validation-only catch-up: process queued work without introducing more game-time inputs.
// Do not use this synchronous loop as a normal game tick implementation.
var drained=0;
for(var i=0;i<256;i++){
    var progress=sd.ticker.electromagnetism$nativeProgress().get();
    if(String(progress.pauseReason())!=='' || progress.backlogSeconds()<0.000001)break;
    sd.ticker.endTick();sd.ticker.electromagnetism$saveNative();drained++;
}
JSON.stringify({drained:drained,progress:String(sd.ticker.electromagnetism$nativeProgress())});
