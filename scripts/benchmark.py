#!/usr/bin/env python3
"""Synthetic 10k ingestion + detection benchmark. Creates records; requires admin env password."""
import argparse,json,os,time,uuid,statistics
from datetime import datetime,timedelta,timezone
from pathlib import Path
from demo import Client,require,timestamp

p=argparse.ArgumentParser(description=__doc__)
p.add_argument('--base-url',default=os.environ.get('SENTINEL_BASE_URL','http://localhost:8080'))
p.add_argument('--output',default='docs/benchmark-result.json')
p.add_argument('--count',type=int,default=10000)
a=p.parse_args()
require(a.count>=100 and a.count%100==0,'Count must be a positive multiple of 100')
password=os.environ.get('SENTINEL_ADMIN_PASSWORD','');require(password,'Set SENTINEL_ADMIN_PASSWORD')
c=Client(a.base_url,'admin',password)
prefix='BENCH-'+uuid.uuid4().hex[:8]
customers=[{'customerId':f'{prefix}-C{i}','firstName':'Synthetic','lastName':'Benchmark'} for i in range(100)]
accounts=[{'accountId':f'{prefix}-A{i}','customerId':customers[i]['customerId'],'currency':'INR'} for i in range(100)]
for kind,rows in [('customers',customers),('accounts',accounts)]:
 result=c.import_batch(f'/ingestion/{kind}/batch',rows)
 require(result['failed']==0,f'{kind} setup failed')
anchor=(datetime.now(timezone.utc)+timedelta(days=1)).replace(hour=0,minute=0,second=0,microsecond=0)
start=time.perf_counter()
accepted=0
for batch in range(a.count//100):
 rows=[{'transactionRef':f'{prefix}-T{batch*100+i}','accountId':accounts[i]['accountId'],
        'amount':101+i%37,'currency':'INR','direction':'IN','transactionTime':timestamp(anchor+timedelta(seconds=batch)),
        'jurisdiction':'IR' if batch%10==0 else 'IN'} for i in range(100)]
 result=c.import_batch('/transactions/batch',rows)
 require(result['failed']==0,f'Batch {batch}: {result["failed"]} failures')
 accepted+=result['succeeded']
 if (batch+1)%10==0: print(f'{accepted} transactions ingested and evaluated',flush=True)
elapsed=time.perf_counter()-start
latencies=[]
for i in range(10):
 row={'transactionRef':f'{prefix}-STREAM{i}','accountId':accounts[i]['accountId'],'amount':123,'currency':'INR','direction':'IN','transactionTime':timestamp(anchor+timedelta(hours=1,seconds=i))}
 before=time.perf_counter();c.request('POST','/transactions/stream',row,expected=(201,));latencies.append((time.perf_counter()-before)*1000)
alert_count=sum(any(ref.startswith(prefix+'-') for ref in alert.get('transactionRefs',[])) for alert in c.pages('/alerts'))
require(alert_count>=100,'Expected high-risk alerts for all benchmark customers')
result={'transactions':accepted,'customers':100,'accounts':100,'transport':'100-record JSON batches over HTTP Basic','database':'PostgreSQL','detection':'all six rules enabled; 10% jurisdiction hits',
 'bulk_seconds':round(elapsed,3),'target_under_120_seconds':elapsed<120,'stream_samples':len(latencies),'stream_median_ms':round(statistics.median(latencies),3),'stream_max_ms':round(max(latencies),3),'persisted_alerts':alert_count,'run_prefix':prefix,'measured_at':timestamp(datetime.now(timezone.utc))}
Path(a.output).write_text(json.dumps(result,indent=2)+'\n')
print(json.dumps(result,indent=2))
require(elapsed<120,'Bulk target exceeded; see measured result')
require(max(latencies)<1000,'Streaming sample exceeded one second')
