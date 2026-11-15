// Copied validation world only: explicitly acknowledge the previously uncaptured interval.
// Retained boundaries must have drained before this command. This excludes missing time.
sd.ticker.electromagnetism$resumeMissingInputs();
JSON.stringify({status:String(sd.ticker.electromagnetism$nativeStatus()),progress:String(sd.ticker.electromagnetism$nativeProgress())});
