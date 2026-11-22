"""Two real JVMs and a local embedding provider; disposable data only."""
import argparse
from concurrent.futures import ThreadPoolExecutor
import json
from pathlib import Path
import sys
import time
import traceback
from verify_assistant_cluster import Acceptance, request, wait_for, ROOT


class KnowledgeAcceptance(Acceptance):
    def __init__(self,output,jar=None,expected_count=None):
        super().__init__(output,jar)
        self.expected_count=expected_count if expected_count is not None else json.loads((ROOT/'backend/src/main/resources/knowledge/diet_guides_metadata.json').read_text(encoding='utf-8'))['chunkCount']
        self.project=self.project.replace('assistant-cluster','knowledge-cluster')
        self.compose[self.compose.index('-p')+1]=self.project
        settings={'mealcheck.knowledge.embedding-api-key':'cluster-test-only',
                  'mealcheck.knowledge.embedding-base-url':'http://model-stub:9082/v1',
                  'mealcheck.knowledge.embedding-model':'cluster-embedding',
                  'mealcheck.knowledge.source-sync-lease-seconds':6,
                  'mealcheck.ai-budget.min-start-interval-millis':0,
                  'mealcheck.ai-budget.max-starts-per-minute':10000}
        overlay=self.output/'fixture.json'
        overlay.write_text(json.dumps({'services':{name:{'environment':{'SPRING_APPLICATION_JSON':json.dumps(settings)}}
                                                   for name in ('backend-a','backend-b')}},indent=2),encoding='utf-8')
        self.compose+=['-f',str(overlay)]

    def embedding_calls(self):
        return request(self.stub,'/__stats')[1]['callsByKind'].get('embedding',0)

    def snapshot(self):
        return json.loads(self.sql("SELECT row_to_json(s) FROM (SELECT data_revision,completed_fingerprint,completed_at "
                                   "FROM knowledge_source_sync WHERE source='diet_guides.md') s"))

    def successful_logs(self,service):
        return self.command(self.compose+['logs','--no-color',service]).stdout.count('Knowledge index synchronized.')

    def restart(self,*services):
        before={service:self.successful_logs(service) for service in services}
        self.command(self.compose+['restart',*services])
        for service in services:
            base=self.a if service=='backend-a' else self.b
            wait_for(lambda:request(base,'/actuator/health',timeout=3)[0]==200,150)
            wait_for(lambda:self.successful_logs(service)>before[service],30)

    def dirty(self):
        self.sql("UPDATE knowledge_chunks SET content='manual editor change',content_hash=repeat('f',64) "
                 "WHERE id=(SELECT min(id) FROM knowledge_chunks WHERE source='diet_guides.md')")

    def interrupted_sync(self,mode,admin):
        self.dirty();self.delay(2)
        previous_logs=self.successful_logs('backend-a')
        with ThreadPoolExecutor(max_workers=1) as executor:
            original=executor.submit(request,self.a,'/api/knowledge/reindex',admin,{},'POST')
            wait_for(lambda:request(self.stub,'/__stats')[1]['activeCalls']>0)
            self.command(self.compose+(['pause','backend-a'] if mode=='pause' else ['kill','-s','SIGKILL','backend-a']))
            try:
                if mode=='pause':
                    code,_=request(self.b,'/api/knowledge/reindex',admin,{},'POST')
                    self.record('competing-admin-reindex-is-rejected-while-owner-lease-is-live',code==409)
                wait_for(lambda:self.sql("SELECT lease_until<=clock_timestamp() FROM knowledge_source_sync WHERE source='diet_guides.md'")=='t',20)
                self.delay(.05)
                code,_=request(self.b,'/api/knowledge/reindex',admin,{},'POST')
                assert code==200,f'Replacement sync status: {code}'
                committed=self.snapshot()
            finally:
                if mode=='pause':self.command(self.compose+['unpause','backend-a'])
            if mode=='pause':
                code,_=original.result(timeout=20)
                assert code==409,f'Stale sync status: {code}'
            else:
                try:original.result(timeout=10)
                except (OSError,ValueError):pass
                self.command(self.compose+['up','-d','backend-a'])
                wait_for(lambda:request(self.a,'/actuator/health',timeout=3)[0]==200,150)
                wait_for(lambda:self.successful_logs('backend-a')>previous_logs,30)
            assert self.snapshot()==committed
            self.record(mode+'-owner-takeover-preserves-replacement-and-fences-old-execution')

    def run(self):
        self.start()
        wait_for(lambda:self.sql("SELECT count(*) FROM knowledge_source_sync WHERE source='diet_guides.md' AND completed_fingerprint IS NOT NULL AND execution_token IS NULL")=='1',180)
        for service in ('backend-a','backend-b'):wait_for(lambda:self.successful_logs(service)>=1,40)
        self.record(f'cold-start-computes-exactly-one-set-of-{self.expected_count}-embeddings',self.embedding_calls()==self.expected_count,
                    embeddingCalls=self.embedding_calls())
        self.record(f'source-has-{self.expected_count}-unique-chunks',self.sql(f"SELECT count(*)={self.expected_count} AND count(DISTINCT chunk_key)={self.expected_count} FROM knowledge_chunks WHERE source='diet_guides.md'")=='t')
        initial=self.snapshot()
        self.restart('backend-a','backend-b')
        self.record('unchanged-restarts-skip-embeddings-and-publication',self.embedding_calls()==self.expected_count and self.snapshot()==initial)
        name,user=self.account()
        self.record('sync-status-is-admin-only',request(self.a,'/api/knowledge/sync/status',user)[0]==403)
        self.sql("UPDATE users SET role='ADMIN' WHERE username='"+name+"'")
        code,status=request(self.b,'/api/knowledge/sync/status',user)
        self.record('admin-can-inspect-shared-source-status',code==200 and any(row['state']=='SUCCEEDED' for row in status))
        self.dirty();edited=self.snapshot();self.restart('backend-b')
        self.record('automatic-restart-preserves-manual-edits',self.embedding_calls()==self.expected_count and self.snapshot()==edited
                    and self.sql("SELECT count(*) FROM knowledge_chunks WHERE content='manual editor change'")=='1')
        code,_=request(self.a,'/api/knowledge/reindex',user,{},'POST')
        self.record('explicit-admin-reindex-restores-source-and-only-embeds-changed-chunk',code==200 and self.embedding_calls()==self.expected_count+1
                    and self.sql("SELECT count(*) FROM knowledge_chunks WHERE content='manual editor change'")=='0')
        self.interrupted_sync('pause',user)
        self.interrupted_sync('kill',user)
        code,answer=request(self.b,'/api/assistant/ask',user,{'question':'主食应该怎样搭配？'})
        refs=answer.get('references',[])
        self.record('assistant-uses-recovered-knowledge-and-still-completes',code==200 and bool(refs)
                    and self.complete_count(answer['requestId'])==1
                    and all(self.sql(f"SELECT count(*) FROM knowledge_chunks WHERE id={int(ref['id'])} AND source='diet_guides.md'")=='1' for ref in refs))
        logs=self.command(self.compose+['logs','--no-color']).stdout
        self.record('no-duplicate-key-or-source-sync-failure-logs','duplicate key value' not in logs and 'Knowledge index synchronization failed' not in logs)
        self.record('no-active-source-leases-left',self.sql("SELECT count(*) FROM knowledge_source_sync WHERE execution_token IS NOT NULL")=='0')

    def finish(self,passed,error=None):
        result=super().finish(passed,error)
        result['scope']='two-actual-backend-processes-local-embedding-stub'
        (self.output/'verification.json').write_text(json.dumps(result,ensure_ascii=False,indent=2),encoding='utf-8')
        return result


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--output',type=Path,required=True)
    parser.add_argument('--jar',type=Path)
    parser.add_argument('--expected-count',type=int,default=json.loads((ROOT/'backend/src/main/resources/knowledge/diet_guides_metadata.json').read_text(encoding='utf-8'))['chunkCount'])
    args=parser.parse_args()
    if args.expected_count<1:parser.error('--expected-count must be positive')
    run=KnowledgeAcceptance(args.output,args.jar,args.expected_count);passed,error=False,None
    try:run.run();passed=True
    except Exception as failure:
        error=type(failure).__name__+': '+str(failure);traceback.print_exc(file=sys.stdout)
    finally:result=run.finish(passed,error)
    if result['status']!='PASSED':raise SystemExit(1)
