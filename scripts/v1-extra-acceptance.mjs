import assert from 'node:assert/strict'
import {readFileSync,writeFileSync,mkdirSync} from 'node:fs'
const base=process.env.STUDIO_API_BASE??'http://127.0.0.1:8081/api/v2'
const run=process.env.V1_EXTRA_ID??`v1_extra_${Date.now()}`
assert.match(run,/^[a-z][a-z0-9_]{2,40}$/)
const source=JSON.parse(readFileSync('docs/contracts/studio-m1/fixtures/valid/order-admission.definition.json','utf8'))
const evidence=[];mkdirSync('output/v1',{recursive:true})
async function call(path,method='GET',body,status=200){
  const r=await fetch(base+path,{method,headers:{'Content-Type':'application/json'},body:body===undefined?undefined:typeof body==='string'?body:JSON.stringify(body)})
  const raw=await r.text(),data=JSON.parse(raw);evidence.push({path,method,request:body,status:r.status,raw});assert.equal(r.status,status,raw);return data
}
const path=id=>`/admin/flows/${id}/versions/1`
const doc=id=>({...structuredClone(source),flowId:id,version:'1'})
async function seed(id,document){await call('/admin/flows','POST',{flowId:id,flowName:'V1 isolated acceptance'},201);if(document)return call(path(id),'PUT',{expectedRevision:'1',document})}
try{
  const incomplete=`${run}_incomplete`;await seed(incomplete)
  const bin=`${run}_bin`,binDoc=doc(bin)
  binDoc.stages.reverse();for(const s of binDoc.stages)s.nodes.reverse()
  const score=binDoc.stages.find(s=>s.stageId==='s_score').nodes.find(n=>n.nodeId==='buyer_score')
  score.config.characteristics.reverse();for(const c of score.config.characteristics)c.bins.reverse()
  Object.assign(score.config.characteristics.find(c=>c.id==='c_refund').bins.find(b=>b.id==='b_refund_high').condition,{op:'between',value:0.3})
  const binSaved=await seed(bin,binDoc),issue=binSaved.validation.issues[0]
  assert.equal(issue.pointer,'/stages/3/nodes/1/config/characteristics/0/bins/1/condition/value');assert.equal(issue.itemId,'b_refund_high')
  const conflict=`${run}_conflict`,conflictDoc=doc(conflict)
  conflictDoc.stages.reverse();for(const s of conflictDoc.stages)s.nodes.reverse()
  conflictDoc.stages.find(s=>s.stageId==='s_score').nodes.find(n=>n.nodeId==='buyer_score').config.outputKey='segment'
  const conflictSaved=await seed(conflict,conflictDoc),collision=conflictSaved.validation.issues[0]
  assert.equal(collision.pointer,'/stages/3/nodes/0/config/outputKey');assert.equal(collision.nodeId,'order_segment')
  const precision=`${run}_precision`;await seed(precision)
  const preciseDoc=doc(precision),decimal='0.123456789012345678901234567890123456789'
  preciseDoc.stages[2].nodes[0].config.characteristics[0].bins[1].score='PRECISE_DECIMAL'
  preciseDoc.stages[2].nodes[1].config.rules[0].value='PRECISE_INTEGER'
  const raw=JSON.stringify(preciseDoc).replace('"PRECISE_DECIMAL"',decimal).replace('"PRECISE_INTEGER"','9007199254740993')
  await call(path(precision),'PUT',`{"expectedRevision":"1","document":${raw}}`)
  // Actual HTTP FIRST_MATCH vs ALL_MATCH when both restrictions match.
  const policies=[]
  for(const policy of ['FIRST_MATCH','ALL_MATCH']){
    const id=`${run}_${policy.toLowerCase()}`,d=doc(id);d.stages[0].nodes[1].config.matchPolicy=policy
    await seed(id,d);await call(path(id)+'/publish','POST',{expectedRevision:'2',changeNote:'V1 match policy'})
    const result=await call(`/flows/${id}/versions/1/executions`,'POST',{requestId:crypto.randomUUID(),bizId:'v1-policy',facts:{order:{amount:50000,country:'XX'},risk:{blacklisted:false}},timeoutMillis:3000})
    const node=result.nodeResults.find(n=>n.nodeId==='base_rules')
    assert.equal(node.details.length,policy==='FIRST_MATCH'?1:2);assert.equal(result.decision.decisionCode,'REJECT')
    policies.push({policy,details:node.details,outputs:node.outputs})
  }
  const checksum=`${run}_checksum`;await seed(checksum,doc(checksum));await call(path(checksum)+'/publish','POST',{expectedRevision:'2',changeNote:'isolated checksum corruption target'})
  const manifest={run,base,incomplete,bin,conflict,precision,checksum,decimal,policies,binIssue:issue,conflictIssue:collision}
  writeFileSync('output/v1/extra-manifest.json',JSON.stringify(manifest,null,2))
  console.log(JSON.stringify({status:'PASS',...manifest}))
}finally{writeFileSync('output/v1/extra-http-evidence.json',JSON.stringify(evidence,null,2))}
