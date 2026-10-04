import assert from 'node:assert/strict'
import { readFileSync, mkdirSync, writeFileSync } from 'node:fs'
import { resolve } from 'node:path'
const base = process.env.STUDIO_API_BASE ?? 'http://127.0.0.1:8081/api/v2'
const run = process.env.V1_RUN_ID ?? `v1_${Date.now()}`
assert.match(run, /^[a-z][a-z0-9_]{2,40}$/)
const fixtures = resolve('docs/contracts/studio-m1/fixtures')
const output = resolve('output/v1')
mkdirSync(output, { recursive: true })
const evidence = []
async function call(path, method='GET', body, expected=200) {
  const response = await fetch(base + path, {method, headers:{'Content-Type':'application/json'},body:body===undefined?undefined:typeof body==='string'?body:JSON.stringify(body)})
  const raw = await response.text(); const data = JSON.parse(raw)
  evidence.push({path,method,request:body,status:response.status,raw})
  assert.equal(response.status,expected,`${method} ${path}: ${raw}`)
  return data
}
const fixture = name => JSON.parse(readFileSync(`${fixtures}/valid/${name}`,'utf8'))
const versionPath = (id,version='1')=>`/admin/flows/${id}/versions/${version}`
const identity = (document,id,version='1')=>({...document,flowId:id,version})
const execute = (id,version,facts,simulation=false,revision='2',status=200)=>call(simulation?`${versionPath(id,version)}/simulations`:`/flows/${id}/versions/${version}/executions`,'POST',{requestId:crypto.randomUUID(),bizId:`${run}-execution`,facts,timeoutMillis:3000,...(simulation?{expectedRevision:revision}:{})},status)
const cases = fixture('order-admission.cases.json')
const resources = new Map()
try {
  for (const name of [...new Set(cases.map(c=>c.definitionFixture))]) {
    const id = `${run}_${resources.size}`
    await call('/admin/flows','POST',{flowId:id,flowName:`V1 ${name}`},201)
    const document = identity(fixture(name),id)
    const saved = await call(versionPath(id),'PUT',{expectedRevision:'1',document})
    assert.equal(saved.validation.valid,true)
    assert.equal(saved.definitionChecksum,null)
    assert.equal((await call('/admin/flows?q='+id)).items[0].latestPublished,null)
    const published = await call(versionPath(id)+'/publish','POST',{expectedRevision:'2',changeNote:'V1 independent fixture'})
    resources.set(name,{id,published})
  }
  for (const sample of cases) {
    const {id,published}=resources.get(sample.definitionFixture)
    for(const simulation of [true,false]) {
      const e=sample.expected
      const payload=await execute(id,'1',sample.facts,simulation,'3',e.status==='FAILED'?500:200)
      const result=payload.execution??payload
      assert.equal(result.status,e.status,sample.id)
      assert.equal(result.purpose,simulation?'SIMULATE':'LIVE')
      assert.equal(result.version,'1');assert.equal(result.revision,published.revision);assert.equal(result.definitionChecksum,published.definitionChecksum)
      assert.equal(result.decision?.decisionCode??null,e.decisionCode)
      for(const key of ['score','terminalNodeId']) if(key in e) assert.equal(result.decision?.[key]??null,e[key],sample.id+' '+key)
      if('segment' in e)assert.equal(result.variables.segment??null,e.segment)
      if(e.reasonCodes)assert.deepEqual(result.decision?.reasonCodes??[],e.reasonCodes)
      for(const [id,status] of Object.entries(e.nodeStatus??{})) assert.equal(result.nodeResults.find(n=>n.nodeId===id)?.status,status)
      const score=result.nodeResults.find(n=>n.nodeId==='buyer_score')
      if(sample.id==='OA-07')assert.match(score.failure.message,/Missing value/)
      if(sample.id==='OA-08'){assert.match(score.failure.message,/Type mismatch/);assert.doesNotMatch(score.failure.message,/Missing/)}
      if(sample.id==='OA-04') for(const node of result.nodeResults.filter(n=>['buyer_score','order_segment','data_review','risk_review','approve'].includes(n.nodeId))) {assert.equal(node.status,'SKIPPED');assert.equal(node.skipReason,'TERMINATED')}
      console.log(`${sample.id} ${simulation?'SIMULATE':'LIVE'} PASS ${result.status} ${result.decision?.decisionCode??'none'} ${result.decision?.score??'null'}`)
    }
  }
  const main=resources.get('order-admission.definition.json')
  const draft=await call(`/admin/flows/${main.id}/drafts`,'POST',{sourceVersion:'1'},201)
  await execute(main.id,draft.version,cases[0].facts,false,draft.revision,409)
  await execute(main.id,'999',cases[0].facts,false,'1',404)
  const conflict=await call(versionPath(main.id),'PUT',{expectedRevision:'3',document:main.published.document},409)
  assert.equal(conflict.code,'OR-LIFECYCLE-CONFLICT')
  const valid=await call(versionPath(main.id,draft.version)+'/validations','POST',{document:draft.document});assert.equal(valid.valid,true)
  const incomplete=structuredClone(draft.document);incomplete.stages.at(-1).nodes[0].config.decisionCode=null
  const saved=await call(versionPath(main.id,draft.version),'PUT',{expectedRevision:'1',document:incomplete})
  assert.equal(saved.validation.valid,false)
  const actual=await call(versionPath(main.id,draft.version));assert.deepEqual(actual.document,incomplete)
  assert.equal((await call(versionPath(main.id,draft.version)+'/publish','POST',{expectedRevision:'1',changeNote:'stale valid revision'},409)).code,'OR-REVISION-CONFLICT')
  assert.equal((await call(versionPath(main.id,draft.version)+'/publish','POST',{expectedRevision:'2',changeNote:'actual invalid revision'},400)).code,'OR-DEF-VALIDATION')
  await execute(main.id,draft.version,cases[0].facts,true,'2',400)
  assert.equal((await call(versionPath(main.id,draft.version))).revision,'2')
  const restored=await call(versionPath(main.id,draft.version),'PUT',{expectedRevision:'2',document:draft.document})
  const race=await Promise.all([0,1].map(async i=>{
    const response=await fetch(base+versionPath(main.id,draft.version),{method:'PUT',headers:{'Content-Type':'application/json'},body:JSON.stringify({expectedRevision:restored.revision,document:{...draft.document,description:`CAS writer ${i}`}})})
    const raw=await response.text();evidence.push({scenario:'CAS race',writer:i,status:response.status,raw});return response.status
  }));assert.deepEqual(race.sort(),[200,409])
  const old=await execute(main.id,'1',cases.find(c=>c.id==='OA-02').facts)
  assert.equal(old.decision.score,'75');assert.equal(old.definitionChecksum,main.published.definitionChecksum)
  console.log('ST-01/03/04/06/10 HTTP lifecycle PASS')
  for(const name of ['parallel-terminal','terminal-missing','future-node','parallel-output','unknown-node','string-score','bad-pointer']){
    const document=identity(JSON.parse(readFileSync(`${fixtures}/invalid/${name}.definition.json`,'utf8')),main.id,draft.version)
    const structural=['unknown-node','string-score','bad-pointer'].includes(name)
    const report=await call(versionPath(main.id,draft.version)+'/validations','POST',{document},structural?400:200)
    if(structural)assert.equal(report.code,'OR-DEF-SCHEMA',name)
    else assert.equal(report.valid,false,name)
    assert.equal(report.issues[0].stageId!==null,true,name)
    console.log(`ST-07/08 ${name} PASS ${report.issues[0].pointer}`)
  }
  const incompleteFixture=identity(fixture('incomplete.draft.json'),main.id,draft.version)
  const currentIncomplete=await call(versionPath(main.id,draft.version))
  const frozenSaved=await call(versionPath(main.id,draft.version),'PUT',{expectedRevision:currentIncomplete.revision,document:incompleteFixture})
  assert.equal(frozenSaved.validation.valid,false)
  assert.deepEqual((await call(versionPath(main.id,draft.version))).document,incompleteFixture)
  assert.equal((await call(versionPath(main.id,draft.version)+'/publish','POST',{expectedRevision:frozenSaved.revision,changeNote:'frozen incomplete fixture'},400)).code,'OR-DEF-VALIDATION')
  await execute(main.id,draft.version,cases[0].facts,true,frozenSaved.revision,400)
  console.log('ST-01 frozen incomplete.draft.json save/read/reject publish and execute PASS')
  for(const scenario of ['overlap','gap','parallel-variable']){
    const document=structuredClone(draft.document),score=document.stages.find(s=>s.stageId==='s_score').nodes.find(n=>n.nodeId==='buyer_score')
    if(scenario==='overlap')score.config.characteristics[1].bins[1].condition.op='lte'
    if(scenario==='gap')score.config.characteristics[1].bins[0].condition.op='gt'
    if(scenario==='parallel-variable') { const condition=score.config.characteristics[0].bins[0].condition;condition.ref={source:'VARIABLE',pointer:'/segment'} }
    const report=await call(versionPath(main.id,draft.version)+'/validations','POST',{document});assert.equal(report.valid,false);assert.equal(report.issues[0].nodeId,'buyer_score')
    console.log(`ST-08/09 ${scenario} PASS ${report.issues[0].pointer}`)
  }
  // Replay complete original F3 requests on isolated V1 identity, without writing F3.
  const diagnostics=JSON.parse(readFileSync('../openrule-studio/output/f3-validation-diagnostics.json','utf8'))
  for(const diagnostic of diagnostics){
    const document=identity(diagnostic.request.body.document,main.id,draft.version)
    const report=await call(versionPath(main.id,draft.version)+'/validations','POST',{document});const issue=report.issues[0]
    assert.equal(issue.stageId,'s_score')
    if(diagnostic.scenario==='between-bin'){assert.equal(issue.itemId,'b_refund_high');assert.equal(issue.pointer,'/stages/2/nodes/0/config/characteristics/1/bins/0/condition/value')}
    else {assert.equal(issue.nodeId,'order_segment');assert.equal(issue.pointer,'/stages/2/nodes/1/config/outputKey')}
    console.log(`F3 ${diagnostic.scenario} PASS ${issue.pointer} ${issue.itemId}`)
  }
  // Raw literals survive the real HTTP/JDBC save, publish and read boundaries.
  const id=`${run}_precision`;await call('/admin/flows','POST',{flowId:id,flowName:'V1 precision'},201)
  const d=identity(fixture('order-admission.definition.json'),id)
  d.stages[2].nodes[0].config.characteristics[0].bins[1].score='V1_DECIMAL'
  d.stages[2].nodes[1].config.rules[0].value='V1_INTEGER'
  const decimal='0.123456789012345678901234567890123456789'
  const raw=JSON.stringify(d).replace('"V1_DECIMAL"',decimal).replace('"V1_INTEGER"','9007199254740993')
  await call(versionPath(id),'PUT',`{"expectedRevision":"1","document":${raw}}`)
  const pub=await call(versionPath(id)+'/publish','POST',{expectedRevision:'2',changeNote:'arbitrary precision'})
  const last=evidence.at(-1).raw;assert.ok(last.includes(decimal));assert.ok(last.includes('9007199254740993'))
  const precise=await execute(id,'1',cases[0].facts);assert.equal(precise.decision.score,'10.123456789012345678901234567890123456789')
  assert.equal(precise.definitionChecksum,pub.definitionChecksum)
  console.log('ST-11 long decimals / large integers / checksum HTTP/JDBC PASS')
  writeFileSync(`${output}/http-manifest.json`,JSON.stringify({run,base,resources:[...resources],precision:id},null,2))
  console.log('V1 HTTP suite PASS')
} finally {writeFileSync(`${output}/http-evidence.json`,JSON.stringify(evidence,null,2))}
