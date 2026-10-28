#!/usr/bin/env python3
"""Replay frozen M2 fixtures against an owned loopback Java/MySQL host; preserve wire evidence."""
import argparse
import copy
from decimal import Decimal
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
from pathlib import Path
import runpy
import socket
import subprocess
import threading
import time
import urllib.error
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
FIX = ROOT / 'docs/contracts/studio-m2/fixtures'
encode = runpy.run_path(str(ROOT / 'scripts/studio-m2-seed.py'))['encode']
def read(path):
    return json.loads(Path(path).read_text(), parse_float=Decimal)
def overlay(target, patch):
    for key, value in patch.items():
        if isinstance(target.get(key), dict) and isinstance(value, dict):
            overlay(target[key], value)
        else:
            target[key] = copy.deepcopy(value)
    return target

class Upstream(BaseHTTPRequestHandler):
    samples = {}
    posts = []
    lock = threading.Lock()
    def log_message(self, *_):
        pass
    def do_POST(self):
        with self.lock:
            sample_id = self.path.strip('/')
            transport = copy.deepcopy(self.samples[sample_id])
            raw = self.rfile.read(int(self.headers.get('Content-Length', '0'))).decode()
            self.posts.append({'id': sample_id, 'method': self.command, 'request': raw,
                               'authorizationPresent': bool(self.headers.get('Authorization'))})
        body = transport.get('rawBody')
        body = (body if body is not None else encode(transport.get('body', {}))).encode()
        delay = transport.get('delayMillis', 0) / 1000
        try:
            if transport.get('slowBody'):
                self.send_response(200)
                self.send_header('Content-Length', str(len(body)))
                self.end_headers()
                self.wfile.write(body[:1]); self.wfile.flush()
                time.sleep(delay)
                self.wfile.write(body[1:])
            else:
                time.sleep(delay)
                self.send_response(transport.get('status', 200))
                self.send_header('Content-Type', 'application/json')
                self.send_header('Content-Length', str(len(body)))
                self.end_headers()
                self.wfile.write(body)
        except (BrokenPipeError, ConnectionResetError):
            pass

class Acceptance:
    def __init__(self, args):
        self.args = args
        self.out = ROOT / 'output/m2-v1/http'
        self.out.mkdir(parents=True, exist_ok=True)
        self.events, self.results = [], []
        self.server = ThreadingHTTPServer(('127.0.0.1', args.upstream_port), Upstream)
        threading.Thread(target=self.server.serve_forever, daemon=True).start()
    def call(self, method, path, body=None, expected=200):
        raw = None if body is None else encode(body)
        req = urllib.request.Request(self.args.base + path, data=None if raw is None else raw.encode(),
                                     headers={'Content-Type': 'application/json'}, method=method)
        started = time.monotonic()
        try:
            response = urllib.request.urlopen(req, timeout=20)
        except urllib.error.HTTPError as error:
            response = error
        text = response.read().decode()
        self.events.append({'method': method, 'path': path, 'request': raw, 'status': response.status,
                            'response': text, 'elapsedMillis': int((time.monotonic()-started)*1000)})
        assert response.status == expected, (method, path, expected, response.status, text)
        return json.loads(text, parse_float=Decimal)
    def sql(self, sql):
        assert self.args.container == 'openrule-m2-v1-20261028'
        assert self.args.database == 'studio_m2_v1'
        return subprocess.check_output(['docker', 'exec', '-i', '-e', 'MYSQL_PWD=v1-local', self.args.container,
                                        'mysql', '-uopenrule_v1', '-D'+self.args.database, '-N', '-B'], input=sql, text=True)
    def clear_config(self):
        # This newly created database belongs exclusively to this runner. No Flow/history rows are deleted.
        self.sql('DELETE FROM or_studio_fact_catalog; DELETE FROM or_studio_fact_provider;')
    def catalog(self, catalog_file, provider):
        self.call('POST', '/admin/fact-providers', provider, 201)
        for item in read(FIX / catalog_file):
            self.call('POST', '/admin/facts', item['document'], 201)
    def flow(self, sample_id, definition):
        flow_id = self.args.run_id + '_' + sample_id.lower().replace('-', '_')
        definition['flowId'] = flow_id
        self.call('POST', '/admin/flows', {'flowId': flow_id, 'flowName': 'M2-V1 isolated'}, 201)
        path = '/admin/flows/'+flow_id+'/versions/1'
        saved = self.call('PUT', path, {'expectedRevision': '1', 'document': definition})
        published = self.call('POST', path+'/publish', {'expectedRevision': saved['revision'], 'changeNote': 'M2-V1 fixture'})
        return path, published
    def check_execution(self, result, expected):
        assert result['status'] == expected['status']
        decision = result.get('decision') or {}
        assert decision.get('decisionCode') == expected['decisionCode']
        for name in ['score', 'terminalNodeId']:
            if name in expected:
                assert decision.get(name) == expected[name], (name, decision, expected)
        if decision.get('terminalNodeId'):
            terminal = next(n for n in result['nodeResults'] if n['nodeId'] == decision['terminalNodeId'])
            assert terminal['status'] == 'SUCCEEDED'
    def mf(self, sample):
        self.clear_config()
        sid, expected = sample['id'], sample['expected']
        definition = read(FIX / sample['definitionFile'])
        path, published = self.flow(sid, definition)
        provider = overlay(read(FIX / sample['providerDocumentFile']), sample['providerPatch'])
        transport = sample['transport']
        Upstream.samples[sid] = transport
        if provider['type'] == 'HTTP':
            provider['endpoint'] = f'http://127.0.0.1:{self.args.upstream_port}/{sid}'
            if transport['outcome'] == 'CONNECT_ERROR':
                with socket.socket() as closed:
                    closed.bind(('127.0.0.1', 0)); closed_port = closed.getsockname()[1]
                provider['endpoint'] = f'http://127.0.0.1:{closed_port}/closed'
        self.catalog(sample['catalogFile'], provider)
        request = copy.deepcopy(sample['request'])
        if 'expectedRevision' in request:
            request['expectedRevision'] = published['revision']
        mode = sample['endpoint']
        endpoint = '/flows/'+published['flowId']+'/versions/1/fact-executions' if mode == 'LIVE' else path + ('/simulations' if mode.startswith('M1') else '/fact-simulations')
        body = self.call('POST', endpoint, request, expected['httpStatus'])
        execution = body.get('execution') if not mode.startswith('M1') or expected['httpStatus'] != 200 else body
        if expected['coreInvoked']:
            self.check_execution(execution, expected)
            assert execution['definitionChecksum'] == published['definitionChecksum']
        else:
            assert execution is None
        if 'errorCode' in expected:
            assert body['code'] == expected['errorCode']
        calls = []
        if not mode.startswith('M1'):
            fields = {f['path']: f for f in body['factResolution']['fields']}
            assert len(fields) == 5
            for pointer, status in expected.get('fieldStates', {}).items():
                assert fields[pointer]['status'] == status
            for pointer, code in expected.get('fieldErrors', {}).items():
                assert fields[pointer]['error']['code'] == code
            trace = execution['traceId'] if execution else body['traceId']
            calls = body['factResolution']['calls']
            for call in calls:
                assert call['traceId'] == trace
                assert call['providerRevision'] == '1'
            for field in fields.values():
                assert field['catalogRevision'] == '1'
                if field['source'] == 'CALLER':
                    assert field['providerRevision'] is None
            if 'requestedPaths' in expected:
                assert calls[0]['requestedPaths'] == expected['requestedPaths']
            assert 'buyer_1024' not in encode(body)
            assert 'v1-controlled-token' not in encode(body)
            if execution:
                assert all(value == '[REDACTED]' for value in execution['variables'].values())
                assert all(value == '[REDACTED]' for n in execution['nodeResults'] for value in n['outputs'].values())
        posts = [p for p in Upstream.posts if p['id'] == sid]
        assert len(posts) == (0 if transport['outcome'] == 'CONNECT_ERROR' else expected['httpCalls']), (sid, posts)
        for post in posts:
            assert json.loads(post['request']) == {'buyerId':'buyer_1024','region':'GLOBAL'}
        if transport['outcome'] == 'CONNECT_ERROR':
            assert calls[0]['attempted'] is True
        record = {'id':sid, 'status':'PASS', 'httpStatus':expected['httpStatus'], 'actualPosts':len(posts),
                  'clientAttempts':sum(c['attempted'] for c in calls), 'decision':execution.get('decision') if execution else None,
                  'flowId':published['flowId'], 'checksum':published['definitionChecksum']}
        self.results.append(record)
        print(encode(record), flush=True)
    def ms(self, sample):
        self.clear_config()
        self.catalog(sample['catalogFile'], read(FIX / sample['providerResourceFile'])['document'])
        before = self.call('GET', '/admin/facts/refund_rate')
        path = sample['operation']['path'].removeprefix('/api/v2')
        result = self.call(sample['operation']['method'], path, sample['document'], sample['expectedHttpStatus'])
        assert result['code'] == sample['expectedCode']
        assert self.call('GET', '/admin/facts/refund_rate') == before
        self.results.append({'id':sample['id'],'status':'PASS','httpStatus':sample['expectedHttpStatus'],'code':result['code'],'issues':result['issues']})
        print(sample['id'], 'PASS', flush=True)
    def run(self):
        try:
            for sample in read(FIX/'order-admission.cases.json')['cases']:
                self.mf(sample)
            for sample in read(FIX/'semantic.cases.json'):
                self.ms(sample)
        finally:
            (self.out/'requests-responses.json').write_text(encode(self.events))
            (self.out/'provider-posts.json').write_text(encode(Upstream.posts))
            (self.out/'results.json').write_text(encode(self.results))
            (self.out/'database-config-rows.tsv').write_text(self.sql('SELECT fact_id,path,provider_id,revision,document_json FROM or_studio_fact_catalog ORDER BY fact_id; SELECT provider_id,revision,document_json FROM or_studio_fact_provider ORDER BY provider_id;'))
            self.server.shutdown()

if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--base', default='http://127.0.0.1:8094/api/v2')
    parser.add_argument('--database', choices=['studio_m2_v1'], default='studio_m2_v1')
    parser.add_argument('--container', choices=['openrule-m2-v1-20261028'], default='openrule-m2-v1-20261028')
    parser.add_argument('--upstream-port', type=int, default=19094)
    parser.add_argument('--run-id', default='m2v1_'+str(int(time.time())))
    args = parser.parse_args()
    assert args.base.startswith('http://127.0.0.1:')
    Acceptance(args).run()
