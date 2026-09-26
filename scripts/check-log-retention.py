"""Real Loki compactor expiration on disposable storage and an old synthetic record."""
import json
import time
import os
from pathlib import Path
from urllib.request import Request,urlopen
from urllib.parse import urlencode
from urllib.error import HTTPError,URLError

def request(path,body=None,tenant='retention-fixture'):
    data=None if body is None else json.dumps(body).encode()
    with urlopen(Request('http://loki:3100'+path,data=data,headers={'X-Scope-OrgID':tenant,'Content-Type':'application/json'}),timeout=10) as res:
        raw=res.read();return json.loads(raw) if raw and raw.startswith(b'{') else raw

deadline=time.monotonic()+75
while True:
    try:request('/ready');break
    except (HTTPError,URLError):
        assert time.monotonic()<deadline,'Loki not ready';time.sleep(1)
if os.environ.get('LOG_CHECK_PHASE')=='delete':
    # First phase has stopped Loki cleanly so the TSDB index was shipped.
    deadline=time.monotonic()+150
    while list(Path('/data/chunks/retention-fixture').rglob('*')):
        files=[p for p in Path('/data/chunks/retention-fixture').rglob('*') if p.is_file()]
        if not files:break
        assert time.monotonic()<deadline,'Expired physical chunk still exists';time.sleep(2)
    assert any(p.is_file() for p in Path('/data/chunks/live-fixture').rglob('*')),'Current log chunk must be preserved'
    print('PASS actual Loki compactor: expired physical chunks removed; current tenant chunks retained')
    raise SystemExit(0)
stamp=time.time_ns()-48*3600*1_000_000_000
query='/loki/api/v1/query_range?'+urlencode(dict(query='{app="external"}',start=str(stamp-1_000_000_000),end=str(stamp+1_000_000_000),limit=10))
request('/loki/api/v1/push',{'streams':[{'stream':{'app':'external','level':'INFO'},'values':[[str(stamp),'retention fixture old log']]}]})
fresh=time.time_ns()
request('/loki/api/v1/push',{'streams':[{'stream':{'app':'external','level':'INFO'},'values':[[str(fresh),'retention fixture current log']]}]},tenant='live-fixture')
current_query='/loki/api/v1/query_range?'+urlencode(dict(query='{app="external"}',start=str(fresh-1_000_000_000),end=str(fresh+1_000_000_000),limit=10))
assert request(current_query,tenant='live-fixture')['data']['result'],'Current fixture must be queryable'
assert not request(current_query,tenant='other-fixture')['data']['result'],'Tenant must be isolated'
request('/flush',{})
deadline=time.monotonic()+15
while not any(p.is_file() for p in Path('/data/chunks/retention-fixture').rglob('*')):
    assert time.monotonic()<deadline,'Expired test chunk was not flushed to disk';time.sleep(.5)
print('PASS synthetic old/current logs accepted, current query + tenant isolation, old physical chunk present. Restart with LOG_CHECK_PHASE=delete.')
