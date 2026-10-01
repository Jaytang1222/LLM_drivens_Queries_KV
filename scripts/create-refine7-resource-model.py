"""Create an isolated alias with bounded CPU/context; never alter the base model."""
import json
import urllib.request

payload = {
    'model': 'kart-refine7-1p5b-t2',
    'from': 'qwen2.5:1.5b-instruct',
    'parameters': {'num_thread': 2, 'num_ctx': 1024},
    'stream': False,
}
req = urllib.request.Request('http://127.0.0.1:11434/api/create',
                             data=json.dumps(payload).encode(),
                             headers={'Content-Type': 'application/json'})
with urllib.request.urlopen(req, timeout=120) as response:
    print(response.read().decode())
