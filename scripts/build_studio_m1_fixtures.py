#!/usr/bin/env python3
import copy, json
from pathlib import Path
R=Path(__file__).resolve().parents[1]/'docs/contracts/studio-m1/fixtures'
def write(path,obj): path.write_text(json.dumps(obj,ensure_ascii=False,indent=2)+'\n')
def ref(source,pointer): return {'source':source,'pointer':pointer}
def cmp(source,pointer,op,value): return {'kind':'compare','ref':ref(source,pointer),'op':op,'value':value}
def present(source,pointer):return {'kind':'is-present','ref':ref(source,pointer)}
def any_(*xs):return {'kind':'any','children':list(xs)}
def all_(*xs):return {'kind':'all','children':list(xs)}
def rule(id,cond,value,reason):return {'id':id,'condition':cond,'value':value,'reasonCode':reason}
def bin_(id,cond,score,reason):return {'id':id,'condition':cond,'score':score,'reasonCode':reason}
def node(id,name,order,typ,cfg,policy='CONTINUE',timeout=100):
    x={'nodeId':id,'nodeName':name,'order':order,'type':'openrule.'+typ,'configVersion':1,'timeoutMillis':timeout,'config':cfg}
    if typ!='terminal':x['failPolicy']=policy
    return x
def stage(id,name,order,mode,when,nodes,timeout=250):return {'stageId':id,'stageName':name,'order':order,'executionMode':mode,'when':when,'timeoutMillis':timeout,'nodes':nodes}
def terminal(id,name,decision,reason,score=None):return node(id,name,1,'terminal',{'decisionCode':decision,'reasonCodes':[reason],'scoreRef':score})
blacklist=node('blacklist','黑名单校验',1,'operator',{'condition':cmp('FACT','/risk/blacklisted','eq',True),'outputKey':'blacklisted','reasonCode':'BUYER_BLACKLISTED'})
base=node('base_rules','订单准入规则',2,'rule-set',{'outputKey':'restriction','matchPolicy':'FIRST_MATCH','rules':[rule('r_amount',cmp('FACT','/order/amount','gte',50000),'HIGH_AMOUNT','AMOUNT_EXCEEDS_LIMIT'),rule('r_region',cmp('FACT','/order/country','in',['XX','ZZ']),'REGION','REGION_RESTRICTED')]},'ABORT')
score=node('buyer_score','买家风险评分卡',1,'scorecard',{'outputKey':'riskScore','characteristics':[{'id':'c_age','name':'账户注册时长','bins':[bin_('b_age_new',cmp('FACT','/buyer/ageDays','lt',30),30,'NEW_ACCOUNT'),bin_('b_age_old',cmp('FACT','/buyer/ageDays','gte',30),5,'ESTABLISHED_ACCOUNT')]},{'id':'c_refund','name':'近90天退款率','bins':[bin_('b_refund_high',cmp('FACT','/metrics/refundRate','gte',0.3),45,'HIGH_REFUND_RATE'),bin_('b_refund_low',cmp('FACT','/metrics/refundRate','lt',0.3),10,'NORMAL_REFUND_RATE')]}]})
segment=node('order_segment','订单金额分层',2,'decision-table',{'outputKey':'segment','hitPolicy':'FIRST','rules':[rule('t_high',cmp('FACT','/order/amount','gte',5000),'HIGH','LARGE_ORDER'),rule('t_med',cmp('FACT','/order/amount','gte',1000),'MEDIUM','STANDARD_ORDER'),rule('t_low',cmp('FACT','/order/amount','lt',1000),'LOW','SMALL_ORDER')]})
doc={'schemaVersion':2,'flowId':'order_admission','flowName':'订单准入决策','version':'1','description':'调用方提供完整 facts 的订单准入验收定义','stages':[
 stage('s_base','基础准入',1,'SERIAL',None,[blacklist,base]),
 stage('s_reject','风险拦截',2,'SERIAL',any_(cmp('NODE','/blacklist/hit','eq',True),cmp('NODE','/base_rules/hit','eq',True)),[terminal('reject','拒绝交易','REJECT','ADMISSION_REJECTED')]),
 stage('s_score','并行风险评估',3,'PARALLEL',None,[score,segment]),
 stage('s_fallback','数据异常复核',4,'SERIAL',any_(*[cmp('NODE',f'/{n}/status','in',['FAILED','TIMED_OUT']) for n in ['blacklist','buyer_score','order_segment']]),[terminal('data_review','数据异常转复核','REVIEW','FACT_RESOLUTION_FAILED')]),
 stage('s_review','高风险复核',5,'SERIAL',all_(present('VARIABLE','/riskScore'),cmp('VARIABLE','/riskScore','gte',60)),[terminal('risk_review','人工复核','REVIEW','HIGH_RISK_SCORE','riskScore')]),
 stage('s_approve','默认决策',6,'SERIAL',None,[terminal('approve','通过交易','APPROVE','RISK_ACCEPTABLE','riskScore')])
]}
write(R/'valid/order-admission.definition.json',doc)
draft=copy.deepcopy(doc);draft['stages'][2]['nodes'][0]['config']['characteristics'][1]['bins'][0]['score']=None
write(R/'valid/incomplete.draft.json',draft)
invalid=copy.deepcopy(doc);invalid['stages'][1]['nodes'][0]['config']['decisionCode']=None
write(R/'invalid/terminal-missing.definition.json',invalid)
invalid=copy.deepcopy(doc);invalid['stages'][0]['nodes'][1]['config']['rules'][0]['condition']['ref']['pointer']='order.amount'
write(R/'invalid/bad-pointer.definition.json',invalid)
invalid=copy.deepcopy(doc);invalid['stages'][2]['nodes'][0]['type']='openrule.unregistered'
write(R/'invalid/unknown-node.definition.json',invalid)
invalid=copy.deepcopy(doc);invalid['stages'][2]['nodes'][0]['config']['characteristics'][0]['bins'][0]['score']='30'
write(R/'invalid/string-score.definition.json',invalid)
invalid=copy.deepcopy(doc);invalid['stages'][2]['nodes'][0]['config']['outputKey']='segment'
write(R/'invalid/parallel-output.definition.json',invalid)
invalid=copy.deepcopy(doc);invalid['stages'][2]['nodes'][1]['config']['rules'][0]['condition']=cmp('NODE','/risk_review/status','eq','SUCCEEDED')
write(R/'invalid/future-node.definition.json',invalid)
invalid=copy.deepcopy(doc);invalid['stages'][-1]['executionMode']='PARALLEL'
write(R/'invalid/parallel-terminal.definition.json',invalid)
basefacts={'order':{'amount':680,'country':'JP'},'buyer':{'id':'buyer_1024','ageDays':365},'metrics':{'refundRate':0.08},'risk':{'blacklisted':False},'channel':'web'}
def case(id,mutate=None,expect=None,definition=None):
    facts=copy.deepcopy(basefacts)
    if mutate:mutate(facts)
    return {'id':id,'definitionFixture':definition or 'order-admission.definition.json','facts':facts,'expected':expect}
cases=[
 case('OA-01',expect={'status':'DECIDED','decisionCode':'APPROVE','score':'15','segment':'LOW','terminalNodeId':'approve'}),
 case('OA-02',lambda f:(f['buyer'].update(id='buyer_2056',ageDays=12),f['metrics'].update(refundRate=0.42),f['order'].update(amount=1800)),{'status':'DECIDED','decisionCode':'REVIEW','score':'75','segment':'MEDIUM','terminalNodeId':'risk_review'}),
 case('OA-04',lambda f:f['risk'].update(blacklisted=True),{'status':'DECIDED','decisionCode':'REJECT','score':None,'segment':None,'terminalNodeId':'reject'}),
 case('OA-07',lambda f:f['metrics'].pop('refundRate'),{'status':'DECIDED','decisionCode':'REVIEW','score':None,'segment':'LOW','terminalNodeId':'data_review','nodeStatus':{'buyer_score':'FAILED'}}),
 case('OA-08',lambda f:f['metrics'].update(refundRate=None),{'status':'DECIDED','decisionCode':'REVIEW','score':None,'terminalNodeId':'data_review','nodeStatus':{'buyer_score':'FAILED'}}),
 case('OA-09',lambda f:f['order'].update(amount='680'),{'status':'FAILED','decisionCode':None,'terminalNodeId':None,'nodeStatus':{'base_rules':'FAILED'}})
]
def change(f,section,key,value):f[section][key]=value
v=copy.deepcopy(doc);v['stages'][2]['nodes'][0]['config']['characteristics'][1]['bins'][0]['score']=5
write(R/'valid/order-admission-score-35.definition.json',v)
v59=copy.deepcopy(doc);v59['stages'][2]['nodes'][0]['config']['characteristics'][1]['bins'][0]['score']=29
write(R/'valid/order-admission-score-59.definition.json',v59)
v60=copy.deepcopy(doc);v60['stages'][2]['nodes'][0]['config']['characteristics'][1]['bins'][0]['score']=30
write(R/'valid/order-admission-score-60.definition.json',v60)
cases += [
 case('OA-03',lambda f:(change(f,'buyer','ageDays',12),change(f,'metrics','refundRate',0.42),change(f,'order','amount',1800)),{'status':'DECIDED','decisionCode':'APPROVE','score':'35','segment':'MEDIUM','terminalNodeId':'approve'},'order-admission-score-35.definition.json'),
 case('OA-05a',lambda f:change(f,'order','amount',50000),{'status':'DECIDED','decisionCode':'REJECT','score':None,'terminalNodeId':'reject'}),
 case('OA-05b',lambda f:change(f,'order','amount',49999.99),{'status':'DECIDED','decisionCode':'APPROVE','score':'15','segment':'HIGH','terminalNodeId':'approve'}),
 case('OA-06a',lambda f:change(f,'order','country','XX'),{'status':'DECIDED','decisionCode':'REJECT','terminalNodeId':'reject'}),
 case('OA-06b',lambda f:change(f,'order','country','ZZ'),{'status':'DECIDED','decisionCode':'REJECT','terminalNodeId':'reject'}),
 case('OA-10a',lambda f:(change(f,'buyer','ageDays',30),change(f,'metrics','refundRate',0.3)),{'status':'DECIDED','decisionCode':'APPROVE','score':'50','terminalNodeId':'approve'}),
 case('OA-10b',lambda f:(change(f,'buyer','ageDays',29),change(f,'metrics','refundRate',0.3)),{'status':'DECIDED','decisionCode':'REVIEW','score':'75','terminalNodeId':'risk_review'}),
 case('OA-11a',lambda f:change(f,'order','amount',999),{'status':'DECIDED','decisionCode':'APPROVE','segment':'LOW','terminalNodeId':'approve'}),
 case('OA-11b',lambda f:change(f,'order','amount',1000),{'status':'DECIDED','decisionCode':'APPROVE','segment':'MEDIUM','terminalNodeId':'approve'}),
 case('OA-11c',lambda f:change(f,'order','amount',4999),{'status':'DECIDED','decisionCode':'APPROVE','segment':'MEDIUM','terminalNodeId':'approve'}),
 case('OA-11d',lambda f:change(f,'order','amount',5000),{'status':'DECIDED','decisionCode':'APPROVE','segment':'HIGH','terminalNodeId':'approve'}),
 case('OA-12a',lambda f:(change(f,'buyer','ageDays',12),change(f,'metrics','refundRate',0.42)),{'status':'DECIDED','decisionCode':'APPROVE','score':'59','terminalNodeId':'approve'},'order-admission-score-59.definition.json'),
 case('OA-12b',lambda f:(change(f,'buyer','ageDays',12),change(f,'metrics','refundRate',0.42)),{'status':'DECIDED','decisionCode':'REVIEW','score':'60','terminalNodeId':'risk_review'},'order-admission-score-60.definition.json')
]
for c in cases:
    terminal=c['expected'].get('terminalNodeId')
    c['expected']['reasonCodes']={'approve':['RISK_ACCEPTABLE'],'risk_review':['HIGH_RISK_SCORE'],'reject':['ADMISSION_REJECTED'],'data_review':['FACT_RESOLUTION_FAILED']}.get(terminal,[])
write(R/'valid/order-admission.cases.json',cases)
print('generated definition, draft, invalid variants and OA case inputs')
