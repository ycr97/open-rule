#!/usr/bin/env python3
"""Prepare/read exact owned resources; process/container restart is performed explicitly by the operator."""
import argparse
import copy
from pathlib import Path
import runpy
import json
import urllib.request
import urllib.error
import uuid

ROOT=Path(__file__).resolve().parents[1]
FIX=ROOT/'docs/contracts/studio-m2/fixtures'
helpers=runpy.run_path(str(ROOT/'scripts/studio-m2-seed.py'));encode=helpers['encode'];read=helpers['read']
out=ROOT/'output/m2-v1/restart';out.mkdir(parents=True,exist_ok=True)
base='http://127.0.0.1:8094/api/v2';flow='m2_v1_restart_20261028';events=[]
def call(method,path,body=None,status=200):
    req=urllib.request.Request(base+path,data=None if body is None else encode(body).encode(),headers={'Content-Type':'application/json'},method=method)
    try:r=urllib.request.urlopen(req,timeout=15)
    except urllib.error.HTTPError as error:r=error
    raw=r.read().decode();events.append({'method':method,'path':path,'status':r.status,'request':body,'response':raw});assert r.status==status,(r.status,path,raw);return json.loads(raw)
def prepare():
    provider=call('GET','/admin/fact-providers/risk_profile')
    document=read(FIX/'valid/risk-profile.provider.json');document['endpoint']='http://127.0.0.1:19094/v1/buyer/batch'
    call('PUT','/admin/fact-providers/risk_profile',{'expectedRevision':provider['revision'],'document':document})
    # Existing catalog from the HTTP matrix has the original six frozen bindings.
    doc=read(ROOT/'docs/contracts/studio-m1/fixtures/valid/order-admission.definition.json');doc['flowId']=flow
    call('POST','/admin/flows',{'flowId':flow,'flowName':'M2 V1 restart isolation'},201)
    path='/admin/flows/'+flow+'/versions/'
    r=call('PUT',path+'1',{'expectedRevision':'1','document':doc});call('POST',path+'1/publish',{'expectedRevision':r['revision'],'changeNote':'V1 original75'})
    call('POST','/admin/flows/'+flow+'/drafts',{'sourceVersion':'1'},201)
    doc['version']='2';doc['stages'][2]['nodes'][0]['config']['characteristics'][1]['bins'][0]['score']=5
    r=call('PUT',path+'2',{'expectedRevision':'1','document':doc});call('POST',path+'2/publish',{'expectedRevision':r['revision'],'changeNote':'V1 modified35'})
def normalized(result):
    execution=result.get('execution',result)
    norm={k:execution[k] for k in ['flowId','version','revision','definitionChecksum','purpose','status','decision','variables']}
    norm['nodes']=[{k:n.get(k) for k in ['stageId','nodeId','status','hit','outputs','details','failure','skipReason']} for n in execution['nodeResults']]
    if 'factResolution' in result:
        report=result['factResolution'];norm['resolution']={'mode':report['mode'],'status':report['status'],'fields':report['fields'],'calls':[{k:c.get(k) for k in ['providerId','providerRevision','transport','status','attempted','requestedPaths','error']} for c in report['calls']]}
    return norm
def snapshot():
    resources={}
    for path in ['/admin/fact-providers/risk_profile']+['/admin/facts/'+r['document']['factId'] for r in call('GET','/admin/facts?pageSize=100')['items']]+['/admin/flows/'+flow+'/versions/'+v for v in ['1','2']]:
        resources[path]=call('GET',path)
    cases=read(ROOT/'docs/contracts/studio-m1/fixtures/valid/order-admission.cases.json');high=next(c for c in cases if c['id']=='OA-02')['facts']
    executions=[]
    for version,score in [('1','75'),('2','35')]:
        r=call('POST','/flows/'+flow+'/versions/'+version+'/executions',{'requestId':str(uuid.uuid4()),'bizId':'m2-v1-restart','facts':high,'timeoutMillis':3000});assert r['decision']['score']==score;executions.append(normalized(r))
    r=call('POST','/flows/'+flow+'/versions/1/fact-executions',{'requestId':str(uuid.uuid4()),'bizId':'m2-v1-restart','facts':{'order':{'amount':680,'country':'JP'},'buyer':{'id':'buyer_1024'}},'timeoutMillis':3000,'factMode':'HTTP'})
    assert r['execution']['decision']['score']=='15';executions.append(normalized(r))
    return {'resources':resources,'executions':executions}
if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('mode',choices=['prepare','before','after']);args=parser.parse_args()
    try:
        if args.mode=='prepare':prepare()
        else:
            current=snapshot()
            if args.mode=='after':assert current==json.loads((out/'before.json').read_text()),'Resources/definitions/configurations/executions changed across owned restart'
            (out/(args.mode+'.json')).write_text(encode(current))
        print(encode({'status':'PASS','mode':args.mode,'flow':flow,**({'resources':len(current['resources']),'executions':len(current['executions'])} if args.mode!='prepare' else {})}))
    finally:(out/(args.mode+'-http.json')).write_text(encode(events))
