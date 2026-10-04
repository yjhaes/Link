#!/usr/bin/env python3
"""Disposable four-service HTTP evidence; Python 3.10+, Docker Compose 2.24.4+."""
import argparse
from collections import Counter
from concurrent.futures import ThreadPoolExecutor
import http.client
import json
import math
import os
from pathlib import Path
import platform
import subprocess
import threading
import time
import urllib.parse
import uuid

ROOT = Path(__file__).resolve().parents[2]

def summarize(samples, seconds):
    statuses = Counter(str(status) for status, _ in samples)
    distribution = {}
    for status in sorted(statuses):
        times = sorted(ms for code, ms in samples if str(code) == status)
        distribution[status] = {
            'samples': len(times), 'min': times[0], 'p50': times[math.ceil(len(times) * .50) - 1],
            'p95': times[math.ceil(len(times) * .95) - 1], 'max': times[-1]}
    return {'statuses': {key: statuses[key] for key in sorted(set(statuses) | {'302', '429', '503'})}, 'elapsed_seconds': seconds,
            'successful_redirects_per_second': statuses['302'] / seconds,
            'latency_ms_by_status': distribution}

class Evidence:
    def __init__(self, samples, rate):
        self.samples, self.rate = samples, rate
        self.run_id = uuid.uuid4().hex[:12]
        self.project = 'link-evidence-' + self.run_id
        self.report = ROOT / 'target' / 'observations' / self.run_id
        self.report.mkdir(parents=True)
        self.secret_file = self.report / '.env.local'
        self.secrets, self.checks, self.scenarios = [], [], {}
        self.started = False
        self.compose = []
        self.env = {key: value for key, value in os.environ.items()
                    if not key.upper().startswith(('DB_', 'MYSQL_', 'REDIS_', 'RABBIT', 'SHORT_LINK_',
                                                   'SPRING_', 'APP_', 'SERVER_', 'MANAGEMENT_', 'COMPOSE_'))
                    and key.upper() not in ('JAVA_TOOL_OPTIONS', 'JDK_JAVA_OPTIONS', '_JAVA_OPTIONS')}

    def safe(self, text):
        for secret in self.secrets:
            text = text.replace(secret, '[REDACTED]')
        return text

    def command(self, argv, label, timeout=120, required=True, input=None):
        reply = subprocess.run(argv, cwd=ROOT, env=self.env, input=input, text=True,
                               stdout=subprocess.PIPE, stderr=subprocess.STDOUT, errors='replace', timeout=timeout)
        (self.report / (label + '.log')).write_text(self.safe(reply.stdout), encoding='utf-8')
        if required and reply.returncode:
            raise RuntimeError(label + ' failed; inspect redacted report')
        return reply.stdout

    def dc(self, args, label, **kwargs):
        return self.command(self.compose + args, label, **kwargs)

    def check(self, condition, name):
        if not condition:
            raise RuntimeError('Acceptance failed: ' + name)
        self.checks.append(name)
        print('PASS ' + name, flush=True)

    def until(self, test, name, timeout=120):
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            try:
                value = test()
                if value:
                    return value
            except (OSError, ValueError, http.client.HTTPException):
                pass
            time.sleep(.5)
        raise RuntimeError('Timed out: ' + name)

    def ports(self):
        self.app_port = int(self.dc(['port', 'app', '8080'], 'app-port').strip().rsplit(':', 1)[1])
        self.ops_port = int(self.dc(['port', 'app', '8081'], 'ops-port').strip().rsplit(':', 1)[1])

    def http(self, method, path, payload=None, headers=None, operations=False):
        connection = http.client.HTTPConnection('127.0.0.1', self.ops_port if operations else self.app_port, timeout=15)
        body = json.dumps(payload) if payload is not None else None
        headers = dict(headers or {})
        if body is not None:
            headers['Content-Type'] = 'application/json'
        try:
            connection.request(method, path, body, headers)
            reply = connection.getresponse()
            data = reply.read()
            return reply.status, {k.lower(): v for k, v in reply.getheaders()}, json.loads(data) if data and 'json' in reply.getheader('Content-Type', '') else data
        finally:
            connection.close()

    def metric(self, meter_name, **tags):
        query = urllib.parse.urlencode([('tag', key + ':' + value) for key, value in tags.items()])
        status, _, data = self.http('GET', '/actuator/metrics/' + meter_name + ('?' + query if query else ''), operations=True)
        if status != 200:
            raise RuntimeError('Missing metric ' + meter_name)
        return next(item['value'] for item in data['measurements'] if item['statistic'] in ('VALUE', 'COUNT'))

    def sql(self, text, label):
        # Root credential stays inside the container environment, never argv or stored SQL output.
        return self.dc(['exec', '-T', 'mysql', 'sh', '-c',
                        'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -u root -N -B'], label, input=text + '\n')

    def query_count(self, label):
        # Digest counts statement executions, not connection count or HTTP request count.
        output = self.sql("SELECT COALESCE(SUM(COUNT_STAR),0) FROM performance_schema.events_statements_summary_by_digest WHERE SCHEMA_NAME='short_link' AND DIGEST_TEXT LIKE 'SELECT%FROM `short_link`%WHERE%';", label)
        return int(output.strip())

    def resources(self, label):
        ids = self.dc(['ps', '--quiet'], label + '-ids').split()
        values = self.command(['docker', 'stats', '--no-stream', '--format', '{{json .}}'] + ids, label)
        # Docker stats is sampled outside timed HTTP loops; no environment inspection is persisted.
        return [{key: item[key] for key in ('Name', 'CPUPerc', 'MemUsage', 'MemPerc', 'PIDs')}
                for line in values.splitlines() if line.strip() for item in [json.loads(line)]]

    def setup(self):
        initializer = ['pwsh', '-NoProfile', '-File', str(ROOT / 'ops/init-local-secrets.ps1'), '-Path'] if os.name == 'nt' else ['sh', str(ROOT / 'ops/init-local-secrets.sh')]
        self.command(initializer + [str(self.secret_file)], 'init')
        values = dict(line.split('=', 1) for line in self.secret_file.read_text().splitlines() if '=' in line)
        self.secrets = [v for k, v in values.items() if any(s in k for s in ('PASSWORD', 'TOKEN', 'HMAC_KEY'))]
        self.admin = {'X-Internal-Token': values['SHORT_LINK_INTERNAL_TOKEN']}
        self.check(len(set(self.secrets)) == 5, 'five independently generated disposable secrets')
        override = self.report / 'ports.yml'
        override.write_text('services:\n  app:\n    image: short-link-evidence:' + self.run_id + '\n    ports: !override ["127.0.0.1::8080", "127.0.0.1::8081"]\n  rabbitmq:\n    ports: !override ["127.0.0.1::15672"]\n', encoding='utf-8')
        self.compose = ['docker', 'compose', '--project-name', self.project, '--env-file', str(self.secret_file), '-f', str(ROOT / 'compose.yml'), '-f', str(override)]
        self.source = self.command(['git', 'rev-parse', 'HEAD'], 'source-version').strip()
        self.source_dirty = bool(self.command(['git', 'status', '--porcelain'], 'source-status').strip())
        self.versions = {
            'host': platform.platform(), 'logical_cpu': os.cpu_count(), 'python': platform.python_version(),
            'docker_server': self.command(['docker', 'version', '--format', '{{.Server.Version}}'], 'docker-version').strip(),
            'docker_engine': self.command(['docker', 'info', '--format', '{"cpus":{{.NCPU}},"memory_bytes":{{.MemTotal}},"os":"{{.OperatingSystem}}","kernel":"{{.KernelVersion}}"}'], 'engine-info').strip(),
            'compose': self.command(['docker', 'compose', 'version'], 'compose-version').strip()}
        if os.name == 'nt':
            self.versions['host_memory'] = self.command(['pwsh', '-NoProfile', '-Command', '(Get-CimInstance Win32_ComputerSystem).TotalPhysicalMemory'], 'host-memory').strip()
            self.versions['host_cpu'] = self.command(['pwsh', '-NoProfile', '-Command', '(Get-CimInstance Win32_Processor).Name'], 'host-cpu').strip()
        self.dc(['config', '--quiet'], 'validate')
        self.dc(['build', 'app'], 'build', timeout=1200)
        self.started = True
        self.dc(['up', '-d', '--wait', '--wait-timeout', '240'], 'up', timeout=300)
        self.ports()
        self.until(lambda: self.http('GET', '/readyz')[0] == 200, 'core readiness')
        self.versions['java'] = self.dc(['exec', '-T', 'app', 'java', '-version'], 'java-version').strip()
        self.versions['mysql'] = self.sql('SELECT VERSION();', 'mysql-version').strip()
        self.versions['redis'] = self.dc(['exec', '-T', 'redis', 'redis-server', '--version'], 'redis-version').strip()
        self.versions['rabbitmq'] = self.dc(['exec', '-T', 'rabbitmq', 'rabbitmq-diagnostics', '-q', 'server_version'], 'rabbit-version').strip()
        self.versions['images'] = self.dc(['images', '--format', 'json'], 'images').strip()
        rows = ','.join("('ObsA%04d','https://example.com/evidence',UTC_TIMESTAMP(3),NULL,TRUE)" % i for i in range(self.samples + 16))
        self.sql('USE short_link; INSERT INTO short_link(short_code,original_url,created_at,expires_at,enabled) VALUES ' + rows + ';', 'isolated-fixtures')
        self.check(self.query_count('initial-query-count') >= 0, 'actual performance_schema mapping SELECT execution counter available')

    def demo(self):
        status, _, created = self.http('POST', '/api/links', {'originalUrl': 'https://example.com/evidence-demo'})
        self.check(status == 201, 'actual anonymous create returns 201')
        self.code = created['shortCode']
        status, headers, _ = self.http('GET', '/s/' + self.code)
        self.cookie = headers.get('set-cookie', '').split(';', 1)[0]
        self.check(status == 302 and headers.get('location') == 'https://example.com/evidence-demo' and headers.get('cache-control') == 'no-store' and bool(self.cookie), 'actual GET 302 no-store and collection cookie')
        self.check(self.http('GET', '/s/' + self.code, headers={'Cookie': self.cookie})[0] == 302, 'second GET reuses same anonymous visitor')
        def pvuv():
            status, _, stats = self.http('GET', '/api/internal/links/' + self.code + '/stats', headers=self.admin)
            return status == 200 and stats['pv'] == 2 and stats['uv'] == 1
        self.until(pvuv, 'actual asynchronous PV2 UV1')
        self.check(True, 'asynchronously recorded PV2 UV1 with shared cookie')
        self.check(self.http('PUT', '/api/links/' + self.code + '/enabled', {'enabled': False})[0] == 401, 'missing management token returns 401')
        self.check(self.http('PUT', '/api/links/' + self.code + '/enabled', {'enabled': False}, self.admin)[0] == 200 and self.http('HEAD', '/s/' + self.code)[0] == 403, 'authorized disable changes actual redirect to 403')
        self.check(self.http('PUT', '/api/links/' + self.code + '/enabled', {'enabled': True}, self.admin)[0] == 200 and self.http('HEAD', '/s/' + self.code)[0] == 302, 'authorized enable restores actual redirect')
        outcomes = [self.http('POST', '/api/links', {'originalUrl': 'https://example.com/evidence-burst'}) for _ in range(5)]
        self.check(any(status == 429 and headers.get('cache-control') == 'no-store' and int(headers.get('retry-after', '0')) >= 1 and data['code'] == 'RATE_LIMIT_EXCEEDED' for status, headers, data in outcomes), 'creation burst visibly returns 429 Retry-After')
        self.check(self.http('GET', '/livez')[2] == {'status': 'UP'} and self.http('GET', '/readyz')[2] == {'status': 'UP'}, 'main liveness and core readiness have no details')
        self.check(self.http('GET', '/actuator/health/dependencies', operations=True)[0] == 200, 'initial dependency health UP')

    def observe(self, name, different=False):
        self.http('HEAD', '/s/ObsA0000')
        if different:
            # Fresh distinct fixture range has never been loaded; no runtime cache/version clearing.
            paths = ['/s/ObsA%04d' % i for i in range(1, self.samples + 1)]
        else:
            paths = ['/s/ObsA0000'] * self.samples
        count_before = self.query_count(name + '-query-before')
        resources_before = self.resources(name + '-resources-before')
        max_inflight, max_core, max_stats = 0, 0, 0
        stop = threading.Event()
        def sample():
            nonlocal max_inflight, max_core, max_stats
            while not stop.is_set():
                max_inflight = max(max_inflight, self.metric('shortlink.redirect.load.inflight'))
                max_core = max(max_core, self.metric('jdbc.connections.active', name='dataSource'))
                max_stats = max(max_stats, self.metric('hikaricp.connections.active', pool='visit-statistics'))
                stop.wait(.10)
        resources_during = []
        sample_errors = []
        def sample_resource():
            if not stop.wait((self.samples - 1) / self.rate / 2):
                try:
                    resources_during.extend(self.resources(name + '-resources-during'))
                except Exception as error:
                    sample_errors.append(error)
        resource_worker = threading.Thread(target=sample_resource)
        resource_worker.start()
        def checked_sample():
            try:
                sample()
            except Exception as error:
                sample_errors.append(error)
        worker = threading.Thread(target=checked_sample)
        worker.start()
        start = time.monotonic()
        def request(path):
            began = time.monotonic()
            status, _, _ = self.http('HEAD', path)
            return status, (time.monotonic() - began) * 1000
        try:
            with ThreadPoolExecutor(max_workers=4) as pool:
                jobs = []
                for i, path in enumerate(paths):
                    delay = start + i / self.rate - time.monotonic()
                    if delay > 0:
                        time.sleep(delay)
                    jobs.append(pool.submit(request, path))
                samples = [job.result() for job in jobs]
            elapsed = time.monotonic() - start
        finally:
            stop.set()
            worker.join(timeout=20)
            resource_worker.join(timeout=30)
        if sample_errors:
            raise RuntimeError('Metric sampler failed: ' + type(sample_errors[0]).__name__)
        result = summarize(samples, elapsed)
        result.update({'mapping_select_executions': self.query_count(name + '-query-after') - count_before,
                       'sampled_actual_load_inflight_max': max_inflight,
                       'sampled_core_pool_active_max': max_core,
                       'sampled_statistics_pool_active_max': max_stats,
                       'resources_before': resources_before, 'resources_during': resources_during, 'resources_after': self.resources(name + '-resources-after')})
        self.scenarios[name] = result
        self.check(sum(result['statuses'].values()) == self.samples and set(result['statuses']) <= {'302', '429', '503'}, name + ' all HTTP samples separately classified')
        self.check(result['statuses'].get('302', 0) > 0, name + ' contains successful business redirects')
        if name == 'healthy-hit':
            self.check(result['mapping_select_executions'] == 0, 'healthy cached HEADs perform zero mapping SELECTs')
        if name in ('healthy-distinct-miss', 'redis-off'):
            self.check(result['mapping_select_executions'] == result['statuses'].get('302', 0), name + ' actual SELECT count matches successful HEAD loads')
        (self.report / (name + '-samples.json')).write_text(json.dumps(samples), encoding='utf-8')
        print(name + ': ' + json.dumps({k:v for k,v in result.items() if not k.startswith('resources')}), flush=True)

    def burst(self):
        began = time.monotonic()
        def request(_):
            start = time.monotonic()
            status, headers, _ = self.http('HEAD', '/s/ObsA0000')
            if status == 429 and not (headers.get('cache-control') == 'no-store' and int(headers.get('retry-after', '0')) >= 1 and 'set-cookie' not in headers):
                raise RuntimeError('HEAD rejection contract failed')
            return status, (time.monotonic() - start) * 1000
        with ThreadPoolExecutor(max_workers=16) as pool:
            samples = list(pool.map(request, range(120)))
        result = summarize(samples, time.monotonic() - began)
        result.update({'concurrency_max': 16, 'scheduled_requests_per_second': None, 'note': 'unpaced finite cached HEAD burst; rejected responses are not business throughput'})
        self.scenarios['healthy-cached-burst'] = result
        self.check(result['statuses']['302'] > 0 and result['statuses']['429'] > 0 and result['statuses']['503'] == 0, 'finite default-bucket burst separates successful 302 from fast 429')
        time.sleep(6)  # At most 60 depleted tokens refill at ten/second before the next profile.
    def max4(self):
        # Real MySQL table lock freezes four actual SELECTs; no production instrumentation or sleeps added.
        lock = subprocess.Popen(self.compose + ['exec', '-T', 'mysql', 'sh', '-c',
            'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -u root -N -B -D short_link'],
            cwd=ROOT, env=self.env, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
        try:
            lock.stdin.write('LOCK TABLES short_link WRITE; SELECT "LOCKED";\n')
            lock.stdin.flush()
            # mysql CLI buffering avoids stdout handshake; verify lock via public MySQL metadata instead.
            self.until(lambda: int(self.sql("SELECT COUNT(*) FROM performance_schema.metadata_locks WHERE OBJECT_SCHEMA='short_link' AND OBJECT_NAME='short_link' AND LOCK_STATUS='GRANTED' AND LOCK_TYPE='SHARED_NO_READ_WRITE';", 'lock-established').strip()) >= 1, 'isolated table write lock', timeout=10)
            before = self.query_count('max4-query-before')
            barrier = threading.Barrier(4)
            def blocked(i):
                barrier.wait(timeout=10)
                return self.http('HEAD', '/s/ObsA%04d' % (self.samples + i + 1))[0]
            with ThreadPoolExecutor(max_workers=4) as pool:
                jobs = [pool.submit(blocked, i) for i in range(4)]
                self.until(lambda: self.metric('shortlink.redirect.load.inflight') == 4, 'four actual active mapping loads', timeout=10)
                waiting = int(self.sql("SELECT COUNT(*) FROM performance_schema.threads WHERE PROCESSLIST_DB='short_link' AND PROCESSLIST_INFO LIKE 'SELECT%FROM short_link%WHERE short_code%' AND PROCESSLIST_STATE='Waiting for table metadata lock';", 'max4-actual-waiting').strip())
                self.check(waiting == 4, 'real MySQL independently shows four blocked mapping SELECTs')
                rejection_started = time.monotonic()
                status, headers, body = self.http('GET', '/s/ObsA%04d' % (self.samples + 5))
                rejection_ms = (time.monotonic() - rejection_started) * 1000
                self.check(status == 503 and body['code'] == 'REDIRECT_LOAD_BUSY' and 'set-cookie' not in headers, 'fifth HTTP load immediately rejects 503 without event cookie')
                self.check(self.metric('shortlink.redirect.load.inflight') == 4, 'fifth rejection does not admit a fifth actual query')
                lock.stdin.write('UNLOCK TABLES;\nquit\n')
                lock.stdin.flush()
                self.check([job.result(timeout=15) for job in jobs] == [302] * 4, 'all four blocked loads finish after releasing database lock')
            actual_queries = self.query_count('max4-query-after') - before
            self.scenarios['blocked-database-max4'] = {'statuses': {'302': 4, '429': 0, '503': 1},
                'mapping_select_executions': actual_queries, 'independent_mysql_blocked_queries': waiting,
                'actual_load_inflight_observed': 4, 'fifth_rejection_latency_ms': rejection_ms,
                'note': 'fifth HTTP includes Redis failure waits before immediate no-queue load rejection'}
            self.check(actual_queries == 4, 'four admitted SELECT executions and no rejected fifth query')
            self.check(self.metric('shortlink.redirect.load.inflight') == 0 and self.http('HEAD', '/s/ObsA%04d' % (self.samples + 5))[0] == 302, 'real queries release all permits and next request succeeds')
        finally:
            if lock.poll() is None:
                lock.terminate()
            try:
                lock.communicate(timeout=10)
            except subprocess.TimeoutExpired:
                lock.kill()
                lock.communicate()

    def recover(self, name, mq=False):
        self.dc(['stop', 'app'], name + '-stop-all-writers')
        self.dc(['rm', '-s', '-f', 'redis'], name + '-remove-redis')
        self.dc(['up', '-d', '--wait', 'redis'] + (['rabbitmq'] if mq else []), name + '-empty-redis', timeout=180)
        self.check(self.dc(['exec', '-T', 'redis', 'redis-cli', 'DBSIZE'], name + '-empty').strip() == '0', name + ' controlled empty Redis with all writers stopped')
        self.dc(['up', '-d', 'app'], name + '-resume-app')
        self.ports()
        self.until(lambda: self.http('GET', '/readyz')[0] == 200, name + ' core ready')
        self.check(self.http('HEAD', '/s/' + self.code)[0] == 302, name + ' authoritative mapping survives controlled recovery')

    def memory_pressure(self):
        config = self.dc(['exec', '-T', 'redis', 'redis-cli', 'CONFIG', 'GET', 'maxmemory', 'maxmemory-policy'], 'memory-config').splitlines()
        self.check(dict(zip(config[::2], config[1::2])) == {'maxmemory': '134217728', 'maxmemory-policy': 'noeviction'}, 'real Redis pressure uses unchanged 128MiB noeviction starting point')
        script = "local value=string.rep('x',1048576); local n=0; for i=1,160 do local r=redis.pcall('SET','evidence:pressure:'..i,value); if type(r)=='table' and r.err then return {n,r.err} end; n=n+1 end; return {n,'NO_OOM'}"
        filled = self.dc(['exec', '-T', 'redis', 'redis-cli', 'EVAL', script, '0'], 'memory-fill').splitlines()
        self.check(len(filled) == 2 and 'OOM' in filled[1], 'bounded isolated-key allocation reaches real Redis OOM without eviction')
        # Controlled reconstruction above leaves no creation bucket; this tests its first write under pressure.
        business_before = self.sql('USE short_link; SELECT (SELECT COUNT(*) FROM short_link),(SELECT COUNT(*) FROM short_code_issuance);', 'pressure-business-before').strip()
        status, headers, body = self.http('POST', '/api/links', {'originalUrl': 'https://example.com/pressure-rejected'})
        self.check(status == 503 and body['code'] == 'RATE_LIMIT_UNAVAILABLE', 'real memory-pressure creation fails closed before business')
        self.check(self.sql('USE short_link; SELECT (SELECT COUNT(*) FROM short_link),(SELECT COUNT(*) FROM short_code_issuance);', 'pressure-business-after').strip() == business_before, 'memory-pressure refusal does not issue an ID or create a mapping')
        self.check(self.http('HEAD', '/s/ObsA%04d' % (self.samples + 8))[0] == 302, 'real Redis pressure redirect falls back successfully')
        self.scenarios['redis-memory-pressure'] = {'fill': filled, 'memory': self.dc(['exec', '-T', 'redis', 'redis-cli', 'INFO', 'memory'], 'redis-pressure-memory')}
        self.recover('pressure-recovery')

    def run(self):
        failure = None
        try:
            self.setup()
            self.demo()
            self.observe('healthy-hit')
            self.observe('healthy-distinct-miss', different=True)
            self.burst()
            self.dc(['stop', 'rabbitmq'], 'mq-stop')
            self.until(lambda: self.http('GET', '/actuator/health/dependencies', operations=True)[2]['components']['mqDependency']['status'] == 'DOWN', 'MQ actually down', timeout=40)
            self.check(self.http('GET', '/s/' + self.code, headers={'Cookie': self.cookie})[0] == 302 and self.http('GET', '/readyz')[0] == 200, 'MQ failure preserves actual GET redirect and core readiness')
            self.observe('mq-off')
            self.dc(['up', '-d', '--wait', 'rabbitmq'], 'mq-restart', timeout=180)
            self.until(lambda: self.http('GET', '/actuator/health/dependencies', operations=True)[0] == 200, 'MQ restored', timeout=60)
            self.dc(['stop', 'redis'], 'redis-stop')
            began = time.monotonic()
            status, _, body = self.http('POST', '/api/links', {'originalUrl': 'https://example.com/redis-rejected'})
            self.scenarios['redis-off-creation'] = {'status': status, 'error': body.get('code'), 'latency_ms': (time.monotonic() - began) * 1000}
            self.check(status == 503 and body['code'] == 'RATE_LIMIT_UNAVAILABLE', 'stopped Redis creation is independently fail-closed')
            self.check(self.http('GET', '/readyz')[0] == 200 and self.http('GET', '/livez')[0] == 200 and self.http('GET', '/actuator/health/dependencies', operations=True)[0] == 503, 'stopped Redis dependency health separated from healthy core')
            self.observe('redis-off')
            self.max4()
            self.recover('outage-recovery')
            self.memory_pressure()
            self.check(self.http('GET', '/actuator/health/dependencies', operations=True)[0] == 200, 'full dependency health restored')
            self.dc(['logs', '--no-color', 'app'], 'safe-app-logs')
        except Exception as error:
            failure = self.safe(type(error).__name__ + ': ' + str(error))
            print(failure, flush=True)
            if self.started:
                self.dc(['logs', '--no-color', '--tail', '100'], 'failure-logs', required=False)
        finally:
            if self.started:
                try:
                    self.dc(['down', '--volumes', '--remove-orphans'], 'cleanup', timeout=180)
                except Exception:
                    failure = failure or 'Isolated cleanup failed: ' + self.project
            self.secret_file.unlink(missing_ok=True)
            summary = {'project': self.project, 'source_sha': getattr(self, 'source', None), 'source_dirty': getattr(self, 'source_dirty', None),
                       'versions': getattr(self, 'versions', {}), 'conditions': {'method': 'HEAD', 'samples_each': self.samples,
                       'concurrency_max': 4, 'scheduled_requests_per_second': self.rate, 'warmup': 1, 'redirect_capacity': 60,
                       'redirect_refill_ms': 100, 'creation_capacity': 3, 'creation_refill_seconds': 6, 'actual_load_max': 4,
                       'fixtures': self.samples + 16, 'fixture_source': 'isolated direct SQL plus real demo HTTP creation',
                       'latency': 'new localhost HTTP connection per request; nearest-rank p50/p95; HEAD does not record events',
                       'sampling': '100ms sampled load/pool gauges, before/midpoint/after Docker stats, isolated MySQL digest execution deltas',
                       'limitations': 'finite scheduled closed-loop sample, no stable p99, no SLA or maximum-throughput claim; sampled resource values can miss peaks'},
                       'passed': self.checks, 'scenarios': self.scenarios, 'failure': failure}
            (self.report / 'summary.json').write_text(self.safe(json.dumps(summary, indent=2)), encoding='utf-8')
            print('Safe evidence: ' + str(self.report), flush=True)
        return 1 if failure else 0

if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--samples', type=int, default=240)
    parser.add_argument('--rate', type=float, default=8)
    args = parser.parse_args()
    if not 1 <= args.samples <= 1000 or not 0 < args.rate <= 100:
        parser.error('samples must be 1..1000 and rate >0..100')
    raise SystemExit(Evidence(args.samples, args.rate).run())
