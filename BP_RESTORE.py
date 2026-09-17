from pathlib import Path
import json, shutil
root=Path(__file__).resolve().parent
items=json.loads((root/'BP_MANIFEST.json').read_text(encoding='utf-8'))
for x in items:
    src=root/x['payload']; dst=root/x['path']
    dst.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(src,dst)
print(f"Ripristinati {len(items)} file nelle cartelle originali.")
