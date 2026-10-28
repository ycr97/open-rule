#!/usr/bin/env python3
"""Real connect timeout: same Studio jar on Linux; drop SYN in an owned probe namespace only."""
import json
from pathlib import Path
import runpy
import subprocess
import urllib.request
import urllib.error

ROOT=Path(__file__).resolve().parents[1];FIX=ROOT/'docs/contracts/studio-m2/fixtures'
helpers=runpy.run_path(str(ROOT/'scripts/studio-m2-seed.py'));read=helpers['read'];encode=helpers['encode']
out=ROOT/'output/m2-v1/connect-linux';out.mkdir(parents=True,exist_ok=True)
events=[];base='http://127.0.0.1:8096/api/v2';flow='m2_v1_linux_connect_20261028'
def call(method,path,body=None,status=200):
    req=urllib.request.Request(base+path,data=None if body is None else encode(body).encode(),headers={'Content-Type':'application/json'},method=method)
    try:r=urllib.request.urlopen(req,timeout=10)
    except urllib.error.HTTPError as error:r=error
    text=r.read().decode();events.append({'method':method,'path':path,'request':body,'response':text,'status':r.status});assert r.status==status,(r.status,path,text);return json.loads(text)
if __name__=='__main__':
    nginx='openrule-m2-v1-connect-20261028';control='openrule-m2-v1-netctl-20261028'
    ip=subprocess.check_output(['docker','inspect','-f','{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}',nginx],text=True).strip()
    provider=read(FIX/'valid/risk-profile.provider.json');provider.update(endpoint='http://'+ip+':19095/',connectTimeoutMillis=100,readTimeoutMillis=1500)
    call('POST','/admin/fact-providers',provider,201)
    for item in read(FIX/'catalog.json'):call('POST','/admin/facts',item['document'],201)
    doc=read(ROOT/'docs/contracts/studio-m1/fixtures/valid/order-admission.definition.json');doc['flowId']=flow
    call('POST','/admin/flows',{'flowId':flow,'flowName':'Owned Linux connect timeout'},201)
    path='/admin/flows/'+flow+'/versions/1';saved=call('PUT',path,{'expectedRevision':'1','document':doc})
    req=read(FIX/'valid/simulate-http.request.json');req['expectedRevision']=saved['revision']
    command=['docker','exec',control,'iptables','-w'];rule=['-p','tcp','--dport','19095','--tcp-flags','SYN','SYN','-j','DROP']
    subprocess.run(command+['-I','INPUT',*rule],check=True)
    try:
        result=call('POST',path+'/fact-simulations',req);c=result['factResolution']['calls'][0]
        counters=subprocess.check_output(command+['-L','INPUT','-v','-n','-x'],text=True)
        (out/'packet-counters.txt').write_text(counters)
        assert c['status']=='TIMED_OUT' and c['attempted'] and c['error']['code']=='OR-FACT-TIMEOUT'
        assert c['elapsedMillis']<900,c
        assert result['execution']['decision']['terminalNodeId']=='data_review'
        summary={'id':'MT-04-connect-timeout-HTTP','status':'PASS','connectBudgetMillis':100,'readBudgetMillis':1500,'actualCallMillis':c['elapsedMillis'],'sharedRequestBudgetMillis':req['timeoutMillis'],'terminal':'data_review','controlledCondition':'Linux INPUT TCP SYN DROP, port19095 only','javaContainer':'openrule-m2-v1-java-connect-20261028','packetCounters':counters}
        (out/'result.json').write_text(encode(summary));print(encode(summary))
    finally:
        subprocess.run(command+['-D','INPUT',*rule],check=True)
        (out/'requests-responses.json').write_text(encode(events))
        (out/'rules-after.txt').write_text(subprocess.check_output(command+['-S','INPUT'],text=True))
