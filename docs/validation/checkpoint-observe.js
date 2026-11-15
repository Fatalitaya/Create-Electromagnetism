var checkpoint=sd.ticker.electromagnetism$saveNative();
var ids=[];sd.deviceSD.getDevices().forEach(function(d){ids.push({type:String(d.type.id()),position:String(d.pos),identity:String(d.electromagnetism$nativeIdentity())});});
JSON.stringify({devices:ids,acceptedTime:checkpoint.getDouble('AcceptedTime'),snapshotBytes:checkpoint.getByteArray('State').length,historyBytes:checkpoint.getByteArray('InputHistory').length,blockers:String(sd.ticker.electromagnetism$inspectInputs().blockers())});
