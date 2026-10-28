#!/usr/bin/env python3
"""Extra HTTP integrity probes; use only the owned M2-V1 database/host from m2-v1-http.py."""
import copy
from decimal import Decimal
import importlib.util
from pathlib import Path
import socket
import time
import concurrent.futures

spec = importlib.util.spec_from_file_location('m2_http', Path(__file__).with_name('m2-v1-http.py'))
h = importlib.util.module_from_spec(spec); spec.loader.exec_module(h)

class Integrity(h.Acceptance):
    def record(self, name, **details):
        result = {'id':name,'status':'PASS',**details}
        self.results.append(result); print(h.encode(result), flush=True)
    def normal(self, sid, **transport):
        h.Upstream.samples[sid] = {'body':{'data':{'ageDays':365,'blacklisted':False,'refundRate':Decimal('0.08')}},**transport}
        return f'http://127.0.0.1:{self.args.upstream_port}/{sid}'
    def base(self, sid):
        self.clear_config()
        provider = h.read(h.FIX/'valid/risk-profile.provider.json')
        provider['endpoint'] = self.normal(sid)
        provider['readTimeoutMillis'] = 3000
        self.catalog('catalog.json', provider)
        path, published = self.flow(sid,h.read(h.FIX/'../../studio-m1/fixtures/valid/order-admission.definition.json'))
        request = h.read(h.FIX/'valid/simulate-http.request.json')
        request['expectedRevision'] = published['revision']
        return provider, path, request
    def put_provider(self, provider):
        existing = self.call('GET','/admin/fact-providers/'+provider['providerId'])
        return self.call('PUT','/admin/fact-providers/'+provider['providerId'],{'expectedRevision':existing['revision'],'document':provider})
    def run(self):
        try:
            self.dependencies()
            self.presence_and_groups()
            self.numbers_and_body()
            self.overload()
        finally:
            self.out = h.ROOT/'output/m2-v1/integrity';self.out.mkdir(parents=True,exist_ok=True)
            (self.out/'requests-responses.json').write_text(h.encode(self.events))
            (self.out/'provider-posts.json').write_text(h.encode(h.Upstream.posts))
            (self.out/'results.json').write_text(h.encode(self.results))
            (self.out/'database-precision.tsv').write_text(self.sql('SELECT provider_id,revision,document_json FROM or_studio_fact_provider ORDER BY provider_id;'))
            self.server.shutdown()
    def dependencies(self):
        doc=h.read(h.FIX/'../../studio-m1/fixtures/valid/order-admission.definition.json')
        path,_=self.flow('required',copy.deepcopy(doc))
        required=self.call('GET',path+'/required-facts')['requiredFacts']
        assert required==h.read(h.FIX/'valid/order-admission.required-facts.json')['requiredFacts']
        doc['stages'].reverse()
        for stage in doc['stages']:
            stage['nodes'].reverse()
            for node in stage['nodes']:
                config=node['config']
                if 'rules' in config:config['rules'].reverse()
                if 'characteristics' in config:
                    config['characteristics'].reverse()
                    for characteristic in config['characteristics']:characteristic['bins'].reverse()
        path,_=self.flow('reordered',doc)
        reordered=self.call('GET',path+'/required-facts')['requiredFacts']
        assert [f['path'] for f in reordered]==[f['path'] for f in required]
        for fact in reordered:
            for use in fact['uses']:
                value=doc
                tokens=use['pointer'].split('/')[1:]
                for token in tokens:value=value[int(token)] if isinstance(value,list) else value[token.replace('~1','/').replace('~0','~')]
                assert value==fact['path']
                stage=doc['stages'][int(tokens[1])];assert stage['stageId']==use['stageId']
                if use['nodeId']:
                    node=stage['nodes'][int(tokens[3])];assert node['nodeId']==use['nodeId']
                    ids=[r['id'] for r in node['config'].get('rules',[])]+[b['id'] for c in node['config'].get('characteristics',[]) for b in c['bins']]
                    if use['itemId']:assert use['itemId'] in ids
        self.record('MT-01-HTTP',paths=len(required),uses=sum(len(f['uses']) for f in required),reorderedPointersAndStableIDs=True)
    def minimal(self, condition):
        doc=h.read(h.FIX/'../../studio-m1/fixtures/valid/order-admission.definition.json')
        first=copy.deepcopy(doc['stages'][0]);first['nodes']=first['nodes'][:1];first['nodes'][0]['config']['condition']=condition
        terminal=copy.deepcopy(doc['stages'][-1]);terminal['nodes'][0]['config']['scoreRef']=None
        doc['stages']=[first,terminal]
        return doc
    def presence_and_groups(self):
        self.clear_config();sid='presence';provider=h.read(h.FIX/'valid/risk-profile.provider.json');provider['endpoint']=self.normal(sid)
        self.call('POST','/admin/fact-providers',provider,201)
        fact=h.read(h.FIX/'valid/buyer_age_days.fact.json');fact.update(factId='presence_probe',path='/presence',sensitive=False)
        fact['binding']={'providerId':'risk_profile','responsePointer':'/data/ageDays'};self.call('POST','/admin/facts',fact,201)
        condition={'kind':'is-present','ref':{'source':'FACT','pointer':'/presence'}}
        path,pub=self.flow(sid,self.minimal(condition));request=h.read(h.FIX/'valid/simulate-http.request.json');request['expectedRevision']=pub['revision']
        original=copy.deepcopy(request);original.pop('factMode')
        before=len(h.Upstream.posts);m1=self.call('POST',path+'/simulations',original)
        assert not m1['nodeResults'][0]['hit'];assert len(h.Upstream.posts)==before
        m2=self.call('POST',path+'/fact-simulations',request);assert m2['execution']['nodeResults'][0]['hit'];assert len(h.Upstream.posts)==before+1
        self.record('MT-02-presence-HTTP',m1Hit=False,m2Hit=True,posts=1)
        condition={'kind':'any','children':[{'kind':'is-present','ref':{'source':'FACT','pointer':''}}, {'kind':'all','children':[{'kind':'compare','ref':{'source':'FACT','pointer':'/mixed'},'op':'eq','value':1},{'kind':'compare','ref':{'source':'FACT','pointer':'/mixed'},'op':'eq','value':'one'},{'kind':'not','children':[{'kind':'is-null','ref':{'source':'FACT','pointer':'/a~1b/~0key/0'}}]}]}]}
        path,pub=self.flow('hints',self.minimal(condition));facts=self.call('GET',path+'/required-facts')['requiredFacts']
        mixed=next(f for f in facts if f['path']=='/mixed');assert mixed['typeConflict'];assert mixed['expectedTypes']==['number','string']
        request['expectedRevision']=pub['revision'];result=self.call('POST',path+'/fact-simulations',request)
        assert result['execution']['status']=='DECIDED';assert result['execution']['nodeResults'][0]['hit']
        self.record('MT-02-hints-short-circuit-HTTP',typeConflictDiagnostic=True,missingBranchNotEvaluated=True)
        self.clear_config();sid='groups';provider['providerId']='aaa';provider['endpoint']=self.normal(sid)
        self.call('POST','/admin/fact-providers',provider,201)
        z=copy.deepcopy(provider);z['providerId']='zzz';z['requestMap']={'dependency':{'source':'FACT','pointer':'/a'}};self.call('POST','/admin/fact-providers',z,201)
        for index,pointer in enumerate(['/a','/aa','/b']):
            f=copy.deepcopy(fact);f.update(factId='group_'+str(index),path=pointer);f['binding']['providerId']='zzz' if pointer=='/b' else 'aaa';self.call('POST','/admin/facts',f,201)
        condition={'kind':'all','children':[{'kind':'is-present','ref':{'source':'FACT','pointer':p}} for p in ['/a','/aa']]+[{'kind':'any','children':[{'kind':'is-present','ref':{'source':'FACT','pointer':''}},{'kind':'is-present','ref':{'source':'FACT','pointer':'/b'}},{'kind':'is-present','ref':{'source':'FACT','pointer':'/unbound'}}]}]}
        path,pub=self.flow(sid,self.minimal(condition));request['expectedRevision']=pub['revision'];result=self.call('POST',path+'/fact-simulations',request)
        calls=result['factResolution']['calls'];assert [c['providerId'] for c in calls]==['aaa','zzz'];assert calls[0]['requestedPaths']==['/a','/aa'];assert not calls[1]['attempted'];assert calls[1]['error']['code']=='OR-FACT-REQUEST-INPUT'
        assert len([p for p in h.Upstream.posts if p['id']==sid])==1
        assert next(f for f in result['factResolution']['fields'] if f['path']=='/unbound')['error']['code']=='OR-FACT-UNBOUND'
        self.record('MT-03-HTTP',providerOrder=['aaa','zzz'],actualPosts=1,mappingReadsOriginalOnly=True,unboundHasNoCall=True)
    def numbers_and_body(self):
        sid='precision';provider,path,request=self.base(sid)
        provider=h.read(h.FIX/'valid/precision.provider.json');provider['endpoint']=self.normal(sid);provider['secretRef']=None;provider['readTimeoutMillis']=3000
        saved=self.put_provider(provider)
        assert saved['document']['mock']==provider['mock'];assert saved['document']['requestMap']==provider['requestMap']
        raw=self.sql("SELECT document_json FROM or_studio_fact_provider WHERE provider_id='risk_profile';")
        assert '9007199254740995' in raw and '0.123456789012345678901234567890123456789' in raw
        (h.ROOT/'output/m2-v1/precision-config-original.tsv').write_text(raw)
        # Make this known numeric field explicitly nonsensitive to inspect exact wire summary.
        fact=self.call('GET','/admin/facts/buyer_age_days');fact['document']['sensitive']=False
        self.call('PUT','/admin/facts/buyer_age_days',{'expectedRevision':fact['revision'],'document':fact['document']})
        for value in ['9007199254740995','7'*128,'1e1000','7'*129,'1e1001']:
            h.Upstream.samples[sid]={'rawBody':'{"data":{"ageDays":'+value+',"blacklisted":false,"refundRate":0.123456789012345678901234567890123456789}}'}
            result=self.call('POST',path+'/fact-simulations',request);fields={f['path']:f for f in result['factResolution']['fields']}
            if len(value)==129 or value=='1e1001':assert fields['/buyer/ageDays']['error']['code']=='OR-FACT-RESPONSE'
            else:
                summary=fields['/buyer/ageDays']['valueSummary'];assert Decimal(summary)==Decimal(value),(summary,value)
                assert fields['/metrics/refundRate']['valueSummary']=='0.123456789012345678901234567890123456789'
                assert result['execution']['decision']['score']=='15'
        self.record('MT-08-numeric-HTTP-DB',decimal39=True,bigint=True,digits128Accepted=True,digits129Rejected=True,exponent1000Accepted=True,exponent1001Rejected=True)
        prefix='{"data":{"ageDays":365,"blacklisted":false,"refundRate":0.08},"padding":"'
        for size in [1048576,1048577]:
            h.Upstream.samples[sid]={'rawBody':prefix+'a'*(size-len(prefix)-2)+'"}'}
            result=self.call('POST',path+'/fact-simulations',request)
            assert result['execution']['decision']['decisionCode']==('APPROVE' if size==1048576 else 'REVIEW')
            if size>1048576:assert result['factResolution']['calls'][0]['error']['code']=='OR-FACT-RESPONSE'
        self.record('MT-08-body-HTTP',acceptedBytes=1048576,rejectedBytes=1048577)
        provider['readTimeoutMillis']=300;self.put_provider(provider);self.normal(sid,slowBody=True,delayMillis=800)
        result=self.call('POST',path+'/fact-simulations',request);frozen=h.encode(result)
        assert result['execution']['decision']['terminalNodeId']=='data_review';assert result['factResolution']['calls'][0]['status']=='TIMED_OUT'
        time.sleep(0.9);assert h.encode(result)==frozen
        self.record('MT-04-full-body-HTTP',providerTimeoutMillis=300,bodyDelayMillis=800,terminal='data_review',lateResponseDoesNotChangeWireResult=True)
    def overload(self):
        sid='overload';provider,path,request=self.base(sid);provider['readTimeoutMillis']=3000;self.put_provider(provider);self.normal(sid,delayMillis=800)
        request['timeoutMillis']=5000
        with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
            futures=[pool.submit(self.call,'POST',path+'/fact-simulations',request) for _ in range(2)]
            limit=time.monotonic()+3
            while len([p for p in h.Upstream.posts if p['id']==sid])<2 and time.monotonic()<limit:time.sleep(0.01)
            assert len([p for p in h.Upstream.posts if p['id']==sid])==2
            result=self.call('POST',path+'/fact-simulations',request,503)
            assert result['code']=='OR-FACT-OVERLOADED' and result['execution'] is None
            for future in futures:assert future.result()['execution']['decision']['score']=='15'
        self.normal(sid);assert self.call('POST',path+'/fact-simulations',request)['execution']['decision']['score']=='15'
        self.record('MT-04-overload-HTTP',configuredMaxInFlight=2,parallelAccepted=2,rejectedWithoutCall=1,recoveredNextRequest=True)

if __name__=='__main__':
    import argparse
    parser=argparse.ArgumentParser();parser.add_argument('--run-id',default='m2v1_extra_'+str(int(time.time())));parser.add_argument('--skip-connect-probe',action='store_true',help='Compatibility flag: connect timing is always tested by m2-v1-connect-http.py on Linux');parser.add_argument('--upstream-port',type=int,default=19094)
    args=parser.parse_args();args.base='http://127.0.0.1:8094/api/v2';args.container='openrule-m2-v1-20261028';args.database='studio_m2_v1'
    Integrity(args).run()
