import assert from 'node:assert/strict'
import {execFileSync} from 'node:child_process'
import {readFileSync,writeFileSync} from 'node:fs'
const mode=process.argv[2],base=process.env.STUDIO_API_BASE??'http://127.0.0.1:8081/api/v2'
const extra=JSON.parse(readFileSync('output/v1/extra-manifest.json','utf8'))
const manifest=JSON.parse(readFileSync('output/v1/http-manifest.json','utf8'))
const evidence=[]
async function call(path,method='GET',body,status=200){
  const r=await fetch(base+path,{method,headers:{'Content-Type':'application/json'},body:body===undefined?undefined:JSON.stringify(body)})
  const raw=await r.text();evidence.push({path,method,status:r.status,raw});assert.equal(r.status,status,raw);return {data:JSON.parse(raw),raw}
}
const vpath=(id,v='1')=>`/admin/flows/${id}/versions/${v}`
const high=JSON.parse(readFileSync('docs/contracts/studio-m1/fixtures/valid/order-admission.cases.json','utf8')).find(c=>c.id==='OA-02').facts
try{
  if(mode==='corruption'){
    assert.match(extra.checksum,/^v1_extra_[a-z0-9_]+_checksum$/)
    const original=(await call(vpath(extra.checksum))).data.definitionChecksum
    const sql=checksum=>execFileSync('docker',['exec','-e','MYSQL_PWD=openrule','openrule-f3-mysql','mysql','-uopenrule','-Dopenrule','-e',`UPDATE or_studio_version SET definition_checksum='${checksum}' WHERE flow_id='${extra.checksum}' AND version=1`],{encoding:'utf8'})
    assert.match(original,/^sha256:[0-9a-f]{64}$/)
    try{
      sql('sha256:'+'0'.repeat(64))
      assert.equal((await call(vpath(extra.checksum),'GET',undefined,500)).data.code,'OR-DEF-CHECKSUM')
      assert.equal((await call(`/flows/${extra.checksum}/versions/1/executions`,'POST',{requestId:'v1-corruption',bizId:'v1',facts:high,timeoutMillis:3000},500)).data.code,'OR-DEF-CHECKSUM')
    }finally{sql(original)}
    assert.equal((await call(vpath(extra.checksum))).data.definitionChecksum,original)
    console.log(JSON.stringify({status:'PASS',test:'real MySQL isolated checksum corruption rejects read and LIVE; restored',flowId:extra.checksum}))
  }else if(mode==='before'||mode==='after'){
    // The replay suite may have produced a newer manifest since the restart checkpoint.
    const before=mode==='after'?JSON.parse(readFileSync('output/v1/restart-before.json','utf8')):null
    const ids=before?[...new Set(before.resources.map(r=>r.flowId))]:['order_admission_f3_20261001','order_admission_v1_ui_20261001',extra.incomplete,extra.precision,...manifest.resources.map(r=>r[1].id)]
    const snapshot={resources:[],executions:[]}
    for(const id of ids){
      const versions=(await call(`/admin/flows/${id}/versions?page=1&pageSize=100`)).data.items
      for(const v of versions)snapshot.resources.push({flowId:id,version:v.version,raw:(await call(vpath(id,v.version))).raw})
    }
    for(const id of ids.slice(0,2))for(const [version,score] of [['1','75'],['2','35']]){
      const r=(await call(`/flows/${id}/versions/${version}/executions`,'POST',{requestId:crypto.randomUUID(),bizId:'v1-restart',facts:high,timeoutMillis:3000})).data
      assert.equal(r.decision.score,score)
      snapshot.executions.push({flowId:id,version,status:r.status,revision:r.revision,checksum:r.definitionChecksum,decision:r.decision,variables:r.variables,nodes:r.nodeResults.map(n=>({id:n.nodeId,status:n.status,skip:n.skipReason,outputs:n.outputs,details:n.details,failure:n.failure}))})
    }
    if(mode==='before')writeFileSync('output/v1/restart-before.json',JSON.stringify(snapshot,null,2))
    else {assert.deepEqual(snapshot,before);writeFileSync('output/v1/restart-after.json',JSON.stringify(snapshot,null,2))}
    console.log(JSON.stringify({status:'PASS',mode,resources:snapshot.resources.length,executions:snapshot.executions.length,comparison:mode==='after'?'exact resource raw JSON and normalized execution identical':'checkpoint saved'}))
  }else throw new Error('Usage: node scripts/v1-persistence-check.mjs corruption|before|after')
}finally{writeFileSync(`output/v1/persistence-${mode}-evidence.json`,JSON.stringify(evidence,null,2))}
