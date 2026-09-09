"""Download a pinned public trace and parse its JSON literals without executing JavaScript."""
from pathlib import Path
import urllib.request, hashlib, json
revision = "da212e984c777d31ee7d888f82637288aa4c61d3"
url = f"https://raw.githubusercontent.com/automerge/automerge-perf/{revision}/edit-by-index/editing-trace.js"
data = urllib.request.urlopen(url, timeout=60).read()
assert hashlib.sha256(data).hexdigest() == "23116070719243e2950c310006dd50b3d80744be7542a86c62e7d5c2ed7326d0", "Trace checksum mismatch"
source = data.decode()
decoder = json.JSONDecoder()
edits, _ = decoder.raw_decode(source.split("const edits = ", 1)[1])
final_text, _ = decoder.raw_decode(source.split("const finalText = ", 1)[1])
assert len(edits) == 259778 and len(final_text) == 104852
out = Path(__file__).resolve().parents[1] / ".tools/trace"
out.mkdir(parents=True, exist_ok=True)
(out / "trace.json").write_text(json.dumps({"edits": edits, "finalText": final_text}), encoding="utf-8")
print(f"Verified {len(edits)} edits and {len(final_text)} final characters")
