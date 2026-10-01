"""Register a digest-verified official GGUF through local Ollama APIs.
Does not change the daemon proxy, existing models, or service configuration.
"""
import hashlib,json,subprocess,sys,urllib.request
from pathlib import Path
root=Path(sys.argv[1]); manifest=json.loads((root/'manifest.json').read_text(encoding='utf-8-sig'))
payload={'model':'kart-qwen05-refine7','stream':False,'files':{}}
for layer in manifest['layers']:
    digest=layer['digest'];path=root/digest.replace(':','-')
    h=hashlib.sha256()
    with path.open('rb') as f:
        for block in iter(lambda:f.read(1024*1024),b''):h.update(block)
    assert h.hexdigest()==digest.split(':')[1]
    kind=layer['mediaType'].rsplit('.',1)[-1]
    if kind=='model':
        subprocess.run(['curl','--noproxy','*','-fsS','--max-time','180','-X','POST','--data-binary','@'+str(path),'http://127.0.0.1:11434/api/blobs/'+digest],check=True)
        payload['files']['model.gguf']=digest
    elif kind in ('template','system','license'):payload[kind]=path.read_text()
opener=urllib.request.build_opener(urllib.request.ProxyHandler({}))
req=urllib.request.Request('http://127.0.0.1:11434/api/create',data=json.dumps(payload).encode(),headers={'Content-Type':'application/json'})
print(opener.open(req,timeout=180).read().decode())
