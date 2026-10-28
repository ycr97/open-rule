#!/usr/bin/env python3
"""Generate the Studio M1 JSON Schema and OpenAPI artifacts without external packages."""
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1] / 'docs/contracts/studio-m1'
ID = r'^[a-z][a-z0-9_-]{2,63}$'
TYPE = r'^[a-z][a-z0-9]*(?:[.-][a-z0-9]+)*$'
POINTER = r'^(?:|/(?:[^~/]|~[01])*(?:/(?:[^~/]|~[01])*)*)$'
POS = r'^[1-9][0-9]*$'
DECIMAL = r'^(?:0|[1-9][0-9]*)(?:\.[0-9]+)?$'

def obj(props, required=(), **extra):
    return {'type':'object','properties':props,'required':list(required),'additionalProperties':False,**extra}
def ref(name): return {'$ref':f'#/$defs/{name}'}
def arr(item, min_items=0): return {'type':'array','items':item,'minItems':min_items}
def string(min_len=0): return {'type':'string','minLength':min_len}
def nullable(item): return {'oneOf':[item,{'type':'null'}]}

def build(executable):
    positive={'type':'integer','minimum':1,'maximum':2147483647}
    ident={'type':'string','pattern':ID}
    path={'type':'string','pattern':POINTER}
    value={'oneOf':[{'type':'null'},{'type':'boolean'},{'type':'string'}, {'type':'number'},arr(ref('Value')),{'type':'object','additionalProperties':ref('Value')} ]}
    reference=obj({'source':{'enum':['FACT','VARIABLE','NODE']},'pointer':path},['source','pointer'])
    compare=obj({'kind':{'const':'compare'},'ref':ref('Ref'),'op':{'enum':['eq','ne','gt','gte','lt','lte','between','in','not-in','contains','not-contains','starts-with','ends-with']},'value':ref('Value')},['kind','ref','op','value'])
    presence=obj({'kind':{'enum':['is-present','is-missing','is-null','not-null']},'ref':ref('Ref')},['kind','ref'])
    group=obj({'kind':{'enum':['all','any','not']},'children':arr(ref('Condition'),1)},['kind','children'])
    condition={'oneOf':[compare,presence,group]}
    rule=obj({'id':ident,'condition':nullable(ref('Condition')) if not executable else ref('Condition'),'value':ref('Value'),'reasonCode':nullable(string(1)) if not executable else string(1)},['id','condition','value','reasonCode'])
    characteristic=obj({'id':ident,'name':string(1),'bins':arr(ref('Bin'),0 if not executable else 1)},['id','name','bins'])
    bin_=obj({'id':ident,'condition':nullable(ref('Condition')) if not executable else ref('Condition'),'score':nullable({'type':'number'}) if not executable else {'type':'number'},'reasonCode':nullable(string(1)) if not executable else string(1)},['id','condition','score','reasonCode'])
    out_key={'type':'string','pattern':r'^[a-zA-Z][a-zA-Z0-9_]*$'}
    operator=obj({'condition':nullable(ref('Condition')) if not executable else ref('Condition'),'outputKey':out_key,'reasonCode':nullable(string(1)) if not executable else string(1)},['condition','outputKey','reasonCode'])
    ruleset=obj({'outputKey':out_key,'matchPolicy':{'enum':['FIRST_MATCH','ALL_MATCH']},'rules':arr(ref('Rule'),0 if not executable else 1)},['outputKey','matchPolicy','rules'])
    scorecard=obj({'outputKey':out_key,'characteristics':arr(ref('Characteristic'),0 if not executable else 1)},['outputKey','characteristics'])
    table=obj({'outputKey':out_key,'hitPolicy':{'const':'FIRST'},'rules':arr(ref('Rule'),0 if not executable else 1)},['outputKey','hitPolicy','rules'])
    terminal=obj({'decisionCode':nullable(string(1)) if not executable else string(1),'reasonCodes':arr(string(1),0 if not executable else 1),'scoreRef':nullable(out_key)},['decisionCode','reasonCodes','scoreRef'])
    node_common={'nodeId':ident,'nodeName':string(1),'order':positive,'configVersion':{'const':1},'timeoutMillis':positive,'failPolicy':{'enum':['ABORT','CONTINUE']}}
    types=[('openrule.operator','OperatorConfig'),('openrule.rule-set','RuleSetConfig'),('openrule.scorecard','ScorecardConfig'),('openrule.decision-table','DecisionTableConfig'),('openrule.terminal','TerminalConfig')]
    node_variants=[]
    for typ,cfg in types:
        props={**node_common,'type':{'const':typ},'config':ref(cfg)}
        if typ=='openrule.terminal':
            props.pop('failPolicy')
        node_variants.append(obj(props,props.keys()))
    node={'oneOf':node_variants}
    stage=obj({'stageId':ident,'stageName':string(1),'order':positive,'executionMode':{'enum':['SERIAL','PARALLEL']},'when':nullable(ref('Condition')),'timeoutMillis':positive,'nodes':arr(ref('Node'),0 if not executable else 1)},['stageId','stageName','order','executionMode','when','timeoutMillis','nodes'])
    top=obj({'schemaVersion':{'const':2},'flowId':ident,'flowName':string(1),'version':{'type':'string','pattern':POS},'description':string(),'stages':arr(ref('Stage'),0 if not executable else 1)},['schemaVersion','flowId','flowName','version','description','stages'])
    top.update({'$schema':'https://json-schema.org/draft/2020-12/schema','$id':f'https://openrule.local/schema/studio-m1/{"executable" if executable else "draft"}.schema.json','$defs':{'Value':value,'Ref':reference,'Condition':condition,'Rule':rule,'Bin':bin_,'Characteristic':characteristic,'OperatorConfig':operator,'RuleSetConfig':ruleset,'ScorecardConfig':scorecard,'DecisionTableConfig':table,'TerminalConfig':terminal,'Node':node,'Stage':stage}})
    return top

def error(): return obj({'code':string(1),'message':string(1),'requestId':nullable(string(1)),'traceId':nullable(string(1)),'issues':arr(refapi('ValidationIssue')),'execution':nullable(refapi('ExecutionResult'))},['code','message','requestId','traceId','issues','execution'])
def refapi(name): return {'$ref':f'#/components/schemas/{name}'}

draft=build(False); exe=build(True)
for name, schema in [('draft',draft),('definition',exe)]:
    (ROOT/'schema'/f'{name}.schema.json').write_text(json.dumps(schema,ensure_ascii=False,indent=2)+'\n')

positive={'type':'string','pattern':POS}
checksum={'type':'string','pattern':r'^sha256:[0-9a-f]{64}$'}
validation_issue=obj({'code':string(1),'severity':{'const':'ERROR'},'message':string(1),'pointer':{'type':'string','pattern':POINTER},'stageId':nullable(string()),'nodeId':nullable(string()),'itemId':nullable(string())},['code','severity','message','pointer','stageId','nodeId','itemId'])
report=obj({'valid':{'type':'boolean'},'issues':arr(refapi('ValidationIssue'))},['valid','issues'])
summary=obj({'flowId':string(1),'flowName':string(1),'version':positive,'status':{'enum':['DRAFT','PUBLISHED']},'revision':positive,'definitionChecksum':nullable(checksum)},['flowId','flowName','version','status','revision','definitionChecksum'])
resource=obj({**summary['properties'],'document':draft,'validation':refapi('ValidationReport'),'changeNote':nullable(string()),'updatedAt':{'type':'string','format':'date-time'}},[*summary['required'],'document','validation','changeNote','updatedAt'])
number_value={'oneOf':[{'type':'null'},{'type':'boolean'},{'type':'string'},{'type':'number'},{'type':'array','items':{}},{'type':'object','additionalProperties':{}}]}
rule_detail=obj({'kind':{'const':'RULE'},'ruleId':string(1),'reasonCode':string(1),'value':{}},['kind','ruleId','reasonCode','value'])
score_detail=obj({'kind':{'const':'SCORE_BIN'},'characteristicId':string(1),'binId':string(1),'score':{'type':'string','pattern':r'^-?(?:0|[1-9][0-9]*)(?:\.[0-9]+)?$'},'reasonCode':string(1)},['kind','characteristicId','binId','score','reasonCode'])
node_result=obj({'stageId':string(1),'nodeId':string(1),'type':string(1),'status':{'enum':['SUCCEEDED','FAILED','TIMED_OUT','CANCELLED','SKIPPED']},'skipReason':nullable({'enum':['WHEN_FALSE','TERMINATED','ABORTED']}),'hit':{'type':'boolean'},'reasonCodes':arr(string(1)),'outputs':{'type':'object','additionalProperties':{}},'details':arr({'oneOf':[refapi('RuleDetail'),refapi('ScoreDetail')]}),'failure':nullable(obj({'code':string(1),'message':string(1)},['code','message'])),'elapsedMillis':{'type':'integer','minimum':0}},['stageId','nodeId','type','status','skipReason','hit','reasonCodes','outputs','details','failure','elapsedMillis'])
stage_result=obj({'stageId':string(1),'status':{'enum':['SUCCEEDED','SKIPPED','FAILED']},'skipReason':nullable({'enum':['WHEN_FALSE','TERMINATED','ABORTED']})},['stageId','status','skipReason'])
execution=obj({'requestId':string(1),'traceId':string(1),'bizId':string(1),'purpose':{'enum':['SIMULATE','LIVE']},'flowId':string(1),'version':positive,'revision':positive,'definitionChecksum':checksum,'status':{'enum':['DECIDED','FAILED']},'decision':nullable(obj({'decisionCode':string(1),'reasonCodes':arr(string(1),1),'score':nullable({'type':'string','pattern':r'^-?(?:0|[1-9][0-9]*)(?:\.[0-9]+)?$'}),'terminalNodeId':string(1)},['decisionCode','reasonCodes','score','terminalNodeId'])),'variables':{'type':'object','additionalProperties':{}},'stageResults':arr(refapi('StageResult')),'nodeResults':arr(refapi('NodeResult')),'elapsedMillis':{'type':'integer','minimum':0}},['requestId','traceId','bizId','purpose','flowId','version','revision','definitionChecksum','status','decision','variables','stageResults','nodeResults','elapsedMillis'])
schemas={'DraftDocument':draft,'ExecutableDefinition':exe,'ValidationIssue':validation_issue,'ValidationReport':report,'FlowSummary':summary,'FlowResource':resource,'RuleDetail':rule_detail,'ScoreDetail':score_detail,'NodeResult':node_result,'StageResult':stage_result,'ExecutionResult':execution,'ErrorBody':error()}

def response(schema,desc): return {'description':desc,'content':{'application/json':{'schema':refapi(schema)}}}
def body(schema): return {'required':True,'content':{'application/json':{'schema':schema}}}
def op(method,request,response_name,status='200',query=None):
    o={'operationId':method,'responses':{status:response(response_name,'OK'),'400':response('ErrorBody','Invalid request or definition'),'404':response('ErrorBody','Not found'),'409':response('ErrorBody','Revision or lifecycle conflict'),'500':response('ErrorBody','Execution failure'),'503':response('ErrorBody','Overloaded'),'504':response('ErrorBody','Request deadline exceeded')}}
    if request: o['requestBody']=body(request)
    if query: o['parameters']=query
    return o

def field(name,schema,location='path',required=True):return {'name':name,'in':location,'required':required,'schema':schema}
flow=field('flowId',{'type':'string','pattern':ID}); version=field('version',positive)
page=[field('page',{'type':'integer','minimum':1,'default':1},'query',False),field('pageSize',{'type':'integer','minimum':1,'maximum':100,'default':20},'query',False)]
paths={
 '/api/v2/admin/flows':{'get':op('listFlows',None,'FlowPage',query=[field('q',{'type':'string'},'query',False),*page]),'post':op('createFlow',obj({'flowId':{'type':'string','pattern':ID},'flowName':string(1)},['flowId','flowName']),'FlowResource','201')},
 '/api/v2/admin/flows/{flowId}/versions':{'get':op('listVersions',None,'VersionPage',query=[flow,*page])},
 '/api/v2/admin/flows/{flowId}/versions/{version}':{'get':op('getVersion',None,'FlowResource',query=[flow,version]),'put':op('saveDraft',obj({'expectedRevision':positive,'document':draft},['expectedRevision','document']),'FlowResource',query=[flow,version])},
 '/api/v2/admin/flows/{flowId}/drafts':{'post':op('createDraft',obj({'sourceVersion':positive},['sourceVersion']),'FlowResource','201',[flow])},
 '/api/v2/admin/flows/{flowId}/versions/{version}/validations':{'post':op('validateDocument',obj({'document':draft},['document']),'ValidationReport',query=[flow,version])},
 '/api/v2/admin/flows/{flowId}/versions/{version}/simulations':{'post':op('simulateVersion',refapi('ExecutionRequestWithRevision'),'ExecutionResult',query=[flow,version])},
 '/api/v2/admin/flows/{flowId}/versions/{version}/publish':{'post':op('publishVersion',obj({'expectedRevision':positive,'changeNote':string(1)},['expectedRevision','changeNote']),'FlowResource',query=[flow,version])},
 '/api/v2/flows/{flowId}/versions/{version}/executions':{'post':op('executeVersion',refapi('ExecutionRequest'),'ExecutionResult',query=[flow,version])},
}
request_fields={'requestId':{'type':'string','minLength':1,'maxLength':128},'bizId':{'type':'string','minLength':1,'maxLength':128},'facts':{'type':'object','additionalProperties':{}},'timeoutMillis':{'type':'integer','minimum':1}}
schemas['ExecutionRequest']=obj(request_fields,request_fields.keys())
schemas['ExecutionRequestWithRevision']=obj({**request_fields,'expectedRevision':positive},[*request_fields.keys(),'expectedRevision'])
page_props={'items':arr(refapi('FlowSummary')),'page':{'type':'integer','minimum':1},'pageSize':{'type':'integer','minimum':1},'total':{'type':'integer','minimum':0}}
schemas['VersionPage']=obj(page_props,page_props.keys())
flow_item=obj({'flowId':string(1),'flowName':string(1),'currentDraft':nullable(refapi('FlowSummary')),'latestPublished':nullable(refapi('FlowSummary'))},['flowId','flowName','currentDraft','latestPublished'])
schemas['FlowListItem']=flow_item
schemas['FlowPage']=obj({**page_props,'items':arr(refapi('FlowListItem'))},page_props.keys())
api={'openapi':'3.1.0','info':{'title':'OpenRule Studio M1 API','version':'2.0.0-d0','description':'Target contract; not implemented by current /api/v1 controllers.'},'paths':paths,'components':{'schemas':schemas}}
# OpenAPI component schemas use document-root references, unlike the standalone files.
def embed(schema, name):
    if isinstance(schema, dict):
        return {k: ('#/components/schemas/'+name+'/\u0024defs/'+v[len('#/$defs/'):] if k=='$ref' and isinstance(v,str) and v.startswith('#/$defs/') else embed(v,name)) for k,v in schema.items()}
    if isinstance(schema,list): return [embed(x,name) for x in schema]
    return schema
api['components']['schemas']['DraftDocument']=embed(draft,'DraftDocument')
api['components']['schemas']['ExecutableDefinition']=embed(exe,'ExecutableDefinition')
def replace_embedded(value):
    if isinstance(value,dict):
        if value.get('$id')==draft['$id']: return refapi('DraftDocument')
        if value.get('$id')==exe['$id']: return refapi('ExecutableDefinition')
        return {k:replace_embedded(v) for k,v in value.items()}
    if isinstance(value,list):return [replace_embedded(x) for x in value]
    return value
api['paths']=replace_embedded(api['paths'])
for key in list(api['components']['schemas']):
    if key not in ('DraftDocument','ExecutableDefinition'):
        api['components']['schemas'][key]=replace_embedded(api['components']['schemas'][key])
(ROOT/'openapi.json').write_text(json.dumps(api,ensure_ascii=False,indent=2)+'\n')
print('generated draft.schema.json, definition.schema.json, openapi.json')
