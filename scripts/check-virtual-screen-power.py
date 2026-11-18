#!/usr/bin/env python3
"""Exercise persisted disable/restore transactions with isolated display helpers."""

from pathlib import Path
import json
import os
import subprocess
import tempfile

root = Path(__file__).resolve().parents[1]
with tempfile.TemporaryDirectory(prefix="floatdesk-power-") as temp:
    folder = Path(temp)
    tools = folder / "bin"
    tools.mkdir()
    outputs = folder / "outputs.json"
    initial = [
        {
            "name": "eDP-1",
            "enabled": True,
            "priority": 1,
            "pos": {"x": 0, "y": 0},
            "size": {"width": 800, "height": 600},
        },
        {
            "name": "Virtual-1-1",
            "enabled": True,
            "priority": 2,
            "pos": {"x": 800, "y": 0},
            "size": {"width": 800, "height": 600},
        },
    ]
    outputs.write_text(json.dumps(initial))
    (tools / "xrandr").write_text("""#!/usr/bin/env python3
import os,json
for row in json.load(open(os.environ['FD_POWER_OUTPUTS'])):
 print(row['name']+' connected'+(' primary' if row['priority']==1 and row['enabled'] else '')+(f" 800x600+{row['pos']['x']}+{row['pos']['y']}" if row['enabled'] else ''))
 print('   low 800x600') if False else None
 print('   800x600_low 0.10'+('*' if row['enabled'] else ''))
""")
    (tools / "kscreen-doctor").write_text("""#!/usr/bin/env python3
import os,json,sys
p=os.environ['FD_POWER_OUTPUTS'];rows=json.load(open(p))
assert os.environ.get('QT_IM_MODULE')=='none'
if '-j' in sys.argv:
 for r in rows:r['modes']=[] if os.environ.get('FD_MISSING_MODE') else [{'id':'low','size':r['size'],'refreshRate':.1}]
 print(json.dumps({'outputs':rows}))
else:
 if os.environ.get('FD_REJECT'):sys.exit(1)
 for arg in sys.argv[1:]:
  _,name,action,value,*_=arg.split('.')+['']*2
  row=next(r for r in rows if r['name']==name)
  if action=='disable':
   assert name.startswith('Virtual-')
   row['enabled']=False
  elif action=='enable':row['enabled']=True
  elif action=='position':row['pos']=dict(zip(('x','y'),map(int,value.split(','))))
  elif action=='priority':row['priority']=int(value)
 json.dump(rows,open(p,'w'))
""")
    for p in tools.iterdir():
        p.chmod(0o755)
    env = dict(
        os.environ,
        PATH=str(tools) + ":" + os.environ["PATH"],
        XDG_STATE_HOME=str(folder / "state"),
        FD_POWER_OUTPUTS=str(outputs),
    )
    binary = root / "target/release/floatdesk"

    def run(action, ok=True, **extra):
        result = subprocess.run(
            [str(binary), "virtual-screens", action],
            env=dict(env, **extra),
            capture_output=True,
            text=True,
        )
        assert (result.returncode == 0) == ok, result.stderr

    run("disable")
    saved = folder / "state/floatdesk/virtual-screens.json"
    snapshot = saved.read_bytes()
    assert json.loads(snapshot)["disabled"]
    assert [r["enabled"] for r in json.loads(outputs.read_text())] == [True, False]
    run("disable")
    assert saved.read_bytes() == snapshot
    run("restore", ok=False, FD_MISSING_MODE="1")
    assert saved.read_bytes() == snapshot
    assert not json.loads(outputs.read_text())[1]["enabled"]
    run("restore")
    assert json.loads(outputs.read_text()) == initial
    assert not json.loads(saved.read_bytes())["disabled"]
    run("restore")
    assert json.loads(outputs.read_text()) == initial
    run("disable", ok=False, FD_REJECT="1")
    assert json.loads(saved.read_bytes())["disabled"]
    run("restore")
    no_physical = [dict(initial[0], enabled=False), initial[1]]
    outputs.write_text(json.dumps(no_physical))
    run("disable", ok=False)
    assert json.loads(outputs.read_text()) == no_physical
print(
    "PASS snapshot persistence, idempotence, missing-mode preflight, failure recovery, physical safety and exact restoration"
)
