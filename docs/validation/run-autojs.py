import json,sys,urllib.request
from pathlib import Path
code=Path(sys.argv[1]).read_text(encoding="utf-8")
body={"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"eval_js","arguments":{"side":sys.argv[2] if len(sys.argv)>2 else "server","code":code,"timeout_ms":20000}}}
req=urllib.request.Request("http://127.0.0.1:25585/mcp",data=json.dumps(body).encode(),headers={"Content-Type":"application/json"})
r=json.load(urllib.request.urlopen(req,timeout=30))
with open("build/autojs-transcript.jsonl","a",encoding="utf-8") as f: f.write(json.dumps({"request":body,"response":r})+"\n")
print(json.dumps(r,ensure_ascii=False))
result=r.get("result",{})
if result.get("isError") or "error" in r: sys.exit(1)
for content in result.get("content",[]):
    if content.get("type")=="text":
        try: value=json.loads(content["text"])
        except (ValueError,KeyError): continue
        if isinstance(value,dict) and value.get("ok") is False: sys.exit(1)
