#!/usr/bin/env python3
"""Check OpenAPI 3.1 document shape, local refs, paths, and semantic fixtures."""
import copy, json, re, hashlib, sys
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]/'docs/contracts/studio-m1'
api=json.loads((ROOT/'openapi.json').read_text())
assert api['openapi']=='3.1.0' and api['info']['title'] and api['paths'] and api['components']['schemas']
def walk(value):
    if isinstance(value,dict):
        if '$ref' in value:
            target=value['$ref']; assert target.startswith('#/'),target
            obj=api
            for token in target[2:].split('/'):
                token=token.replace('~1','/').replace('~0','~')
                assert token in obj,target
                obj=obj[token]
        for child in value.values():walk(child)
    elif isinstance(value,list):
        for child in value:walk(child)
walk(api)
ids=set()
for path,methods in api['paths'].items():
    assert path.startswith('/api/v2/') and methods
    for method,op in methods.items():
        assert method in {'get','post','put'}
        assert op['operationId'] not in ids;ids.add(op['operationId'])
        assert '200' in op['responses'] or '201' in op['responses']
        assert '400' in op['responses'] and '409' in op['responses']
        for name in re.findall(r'\{([^}]+)\}',path):
            assert any(p['name']==name and p['in']=='path' and p['required'] for p in op.get('parameters',[])),(path,name)
assert len(ids)==10,len(ids)
print(f'OpenAPI: {len(api["paths"])} paths, {len(ids)} operations, local refs resolved')

# These cross-document constraints are intentionally outside JSON Schema.
def semantic_errors(d):
    errors=[]; stages=d['stages']; node_ids=set(); stage_ids=set(); prior_nodes=set(); prior_vars=set()
    def refs(c):
        if c is None:return []
        if 'children' in c:return [r for x in c['children'] for r in refs(x)]
        return [c['ref']]
    def check(c,nodes,variables):
        for r in refs(c):
            key=r['pointer'].split('/')[1] if r['pointer'].startswith('/') else ''
            if r['source']=='NODE' and (key not in nodes or r['pointer'] not in (f'/{key}/status',f'/{key}/hit')):errors.append('NODE_REFERENCE')
            if r['source']=='VARIABLE' and key not in variables:errors.append('VARIABLE_REFERENCE')
    for si,s in enumerate(sorted(stages,key=lambda x:x['order'])):
        if s['stageId'] in stage_ids:errors.append('DUP_STAGE')
        stage_ids.add(s['stageId']);check(s['when'],prior_nodes,prior_vars)
        start_nodes=prior_nodes.copy();start_vars=prior_vars.copy();stage_out=set()
        nodes=sorted(s['nodes'],key=lambda x:x['order'])
        for ni,n in enumerate(nodes):
            if n['nodeId'] in node_ids:errors.append('DUP_NODE')
            node_ids.add(n['nodeId'])
            if n['type']=='openrule.terminal':
                if s['executionMode']!='SERIAL' or ni!=len(nodes)-1:errors.append('TERMINAL_POSITION')
            else:
                cfg=n['config']; key=cfg['outputKey']
                if s['executionMode']=='PARALLEL' and key in stage_out:errors.append('PARALLEL_OUTPUT')
                stage_out.add(key)
                for c in ([cfg['condition']] if n['type']=='openrule.operator' else [r['condition'] for r in cfg.get('rules',[])] if 'rules' in cfg else [b['condition'] for ch in cfg['characteristics'] for b in ch['bins']]):
                    check(c,start_nodes if s['executionMode']=='PARALLEL' else prior_nodes,start_vars if s['executionMode']=='PARALLEL' else prior_vars)
                prior_vars.add(key)
            prior_nodes.add(n['nodeId'])
    last=stages[-1]
    if last['when'] is not None or last['executionMode']!='SERIAL' or len(last['nodes'])!=1 or last['nodes'][0]['type']!='openrule.terminal':errors.append('DEFAULT_TERMINAL')
    return errors
base=json.loads((ROOT/'fixtures/valid/order-admission.definition.json').read_text())
assert semantic_errors(base)==[],semantic_errors(base)
for name,code in [('parallel-output','PARALLEL_OUTPUT'),('future-node','NODE_REFERENCE'),('parallel-terminal','TERMINAL_POSITION')]:
    x=json.loads((ROOT/f'fixtures/invalid/{name}.definition.json').read_text())
    assert code in semantic_errors(x),(name,semantic_errors(x))
    print(f'PASS semantic rejected {name}: {code}')
print('Semantic fixtures: 1 valid, 3 invalid')

# Verify legacy raw-byte checksum and exact-decimal canonical vectors.
from pathlib import Path
sys.path.insert(0,str(Path(__file__).resolve().parent))
from studio_m1_checksum import checksum,canonical,read
assert canonical(read('{"a":0.1}')['a'] + read('{"b":0.2}')['b']) == '0.3'
legacy=ROOT/'fixtures/legacy/v1-priority.raw.json'
meta=json.loads(legacy.with_name('v1-priority.meta.json').read_text())
assert hashlib.sha256(legacy.read_bytes()).hexdigest()==meta['legacyChecksumHex']
print('Legacy v1: raw-byte checksum matched')
vectors=json.loads((ROOT/'fixtures/valid/checksum-vectors.json').read_text())
for v in vectors:
    raw=(ROOT/'fixtures/valid'/v['file']).read_text() if 'file' in v else v['rawJson']
    assert checksum(raw)==v['checksum']
    if 'canonical' in v:assert canonical(read(raw))==v['canonical']
assert vectors[4]['checksum']==vectors[5]['checksum']==vectors[6]['checksum']
print(f'Checksum vectors: {len(vectors)} matched')
# OA fixtures are exact input/expected contracts for later Java runtime tests.
cases=json.loads((ROOT/'fixtures/valid/order-admission.cases.json').read_text())
assert len(cases)==19,len(cases)
assert len({c['id'] for c in cases})==len(cases)
for c in cases:
    assert (ROOT/'fixtures/valid'/c['definitionFixture']).is_file()
    assert c['expected']['status'] in ('DECIDED','FAILED')
    assert isinstance(c['facts'],dict)
print(f'OA cases: {len(cases)} input/expected pairs linked')
