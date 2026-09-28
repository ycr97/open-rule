#!/usr/bin/env python3
"""Reference openrule-decimal-c14n-v1 checksum for D0 fixtures, not business runtime."""
import hashlib,json,re,sys
from decimal import Decimal
from pathlib import Path

def strict_pairs(pairs):
    obj={}
    for k,v in pairs:
        if k in obj:raise ValueError(f'duplicate JSON key: {k}')
        obj[k]=v
    return obj

def read(text):
    return json.loads(text,parse_int=Decimal,parse_float=Decimal,parse_constant=lambda x: (_ for _ in ()).throw(ValueError(x)),object_pairs_hook=strict_pairs)

def decimal(v):
    if not v.is_finite():raise ValueError('nonfinite number')
    sign,digits,exponent=v.as_tuple()
    if len(digits)>128 or abs(exponent)>1000:raise ValueError('decimal limit exceeded')
    if v.is_zero():return '0'
    result=format(v,'f')
    if '.' in result:result=result.rstrip('0').rstrip('.')
    return result

def canonical(v):
    if v is None:return 'null'
    if isinstance(v,bool):return 'true' if v else 'false'
    if isinstance(v,Decimal):return decimal(v)
    if isinstance(v,str):return json.dumps(v,ensure_ascii=False,separators=(',',':'))
    if isinstance(v,list):return '['+','.join(canonical(x) for x in v)+']'
    if isinstance(v,dict):
        return '{'+','.join(canonical(k)+':'+canonical(v[k]) for k in sorted(v,key=lambda k:k.encode('utf-16-be','surrogatepass')))+'}'
    raise ValueError(type(v))

def checksum(text):
    return 'sha256:'+hashlib.sha256(canonical(read(text)).encode('utf-8')).hexdigest()

if __name__=='__main__':
    if len(sys.argv)!=2:raise SystemExit('usage: python3 scripts/studio_m1_checksum.py FILE')
    print(checksum(Path(sys.argv[1]).read_text()))
