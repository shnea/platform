"""Offline safety checks for downloadable Python clients; no platform credentials."""
import importlib.util
import json
import logging
import threading
import time
import unittest
from http.server import BaseHTTPRequestHandler,ThreadingHTTPServer
from pathlib import Path

def load(name):
    path=Path(__file__).resolve().parents[1]/'apps/admin-web/public/examples'/f'{name}-client.py'
    spec=importlib.util.spec_from_file_location(name,path);module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module);return module
jobs,logs=load('jobs'),load('logs')

class ClientChecks(unittest.TestCase):
    def setUp(self):
        self.requests=[];self.status=202;self.delay=0;self.location=None
        owner=self
        class Handler(BaseHTTPRequestHandler):
            def log_message(self,*args):pass
            def do_POST(self):
                body=self.rfile.read(int(self.headers.get('Content-Length','0')))
                owner.requests.append((self.path,body))
                time.sleep(owner.delay)
                self.send_response(owner.status)
                if owner.location:self.send_header('Location',owner.location)
                self.end_headers();self.wfile.write(b'{}')
        self.server=ThreadingHTTPServer(('127.0.0.1',0),Handler)
        self.thread=threading.Thread(target=self.server.serve_forever,daemon=True);self.thread.start()
        self.base=f'http://127.0.0.1:{self.server.server_port}'
    def tearDown(self):self.server.shutdown();self.server.server_close()
    def record(self,message):return logging.LogRecord('fixture',logging.INFO,'fixture',1,message,(),None)
    def test_log_masks_before_buffering_and_sends(self):
        handler=logs.PlatformLogs(self.base,'fixture-key','api')
        record=self.record('password=secret-pass Bearer secret-auth test@example.com')
        record.attributes={'nested':{'token':'never-store'},'count':2}
        handler.emit(record)
        deadline=time.monotonic()+3
        while not handler.stats()['sent'] and time.monotonic()<deadline:time.sleep(.02)
        handler.close();self.assertEqual(handler.stats()['sent'],1)
        content=self.requests[0][1].decode()
        for value in ['secret-pass','secret-auth','test@example.com','never-store']:self.assertNotIn(value,content)
        self.assertIn('REDACTED',content)
    def test_queue_does_not_block_and_has_size_limit(self):
        self.delay=.5;handler=logs.PlatformLogs(self.base,'fixture-key','api',capacity=2)
        start=time.monotonic()
        for _ in range(100):handler.emit(self.record('small'))
        self.assertLess(time.monotonic()-start,.5)
        self.assertLessEqual(handler.stats()['queued'],2);self.assertGreater(handler.stats()['dropped'],0)
        handler.emit(self.record('가'*8000));self.assertGreater(handler.stats()['dropped'],0);handler.close()
    def test_permanent_rejection_is_not_retried(self):
        self.status=403;handler=logs.PlatformLogs(self.base,'fixture-key','api');handler.emit(self.record('one'))
        deadline=time.monotonic()+3
        while not handler.stats()['failed_batches'] and time.monotonic()<deadline:time.sleep(.02)
        handler.close();self.assertEqual(len(self.requests),1);self.assertEqual(handler.stats()['dropped'],1)
    def test_job_redirect_never_forwards_key(self):
        self.status=302;self.location=self.base+'/stolen'
        client=jobs.Jobs(self.base,'fixture-key')
        with self.assertRaises(jobs.ApiError):client.request('POST','/claim',{})
        self.assertEqual(len(self.requests),1)
    def test_worker_reports_failure_without_exception_secrets(self):
        client=jobs.Jobs(self.base,'fixture-key');calls=[]
        def request(method,path,body=None,lease=None):
            calls.append((path,body))
            return {'job':{'id':'fixture-job'},'leaseToken':'fixture-lease'} if path=='/claim' else {}
        client.request=request
        def broken(job,lost):raise ValueError('secret-error')
        self.assertTrue(client.run_once('queue','worker',broken))
        self.assertEqual(calls[-1][0],'/fixture-job/fail')
        self.assertFalse(calls[-1][1]['retryable']);self.assertNotIn('secret-error',json.dumps(calls))
    def test_worker_does_not_report_after_ownership_loss(self):
        client=jobs.Jobs(self.base,'fixture-key');calls=[]
        def request(method,path,body=None,lease=None):
            calls.append(path);return {'job':{'id':'fixture-job'},'leaseToken':'fixture-lease'}
        client.request=request
        def lost(job,event):event.set();return {}
        with self.assertRaises(jobs.ApiError):client.run_once('queue','worker',lost)
        self.assertEqual(calls,['/claim'])

if __name__=='__main__':unittest.main()
