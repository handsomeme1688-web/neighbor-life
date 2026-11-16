"""Start the locally built image with mock channels and verify readiness/authentication."""
import argparse
import os
from pathlib import Path
import subprocess
import tempfile
import time
import httpx
import local


def run():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--image',default='neighbor-life:verification')
    args=parser.parse_args()
    env=local.env_values()
    keys=('MYSQL_URL','MYSQL_USERNAME','MYSQL_PASSWORD','REDIS_HOST','REDIS_PORT','REDIS_PASSWORD',
          'KAFKA_BOOTSTRAP_SERVERS')
    config={key:env[key] for key in keys if key in env}
    config.update(SMS_MODE='mock',PAYMENT_MODE='mock',DEMO_PAYMENT_ENABLED='false',SPRING_PROFILES_ACTIVE='local',
        SERVER_PORT='8083',SERVER_ADDRESS='127.0.0.1',SPRING_REDIS_DATABASE='1',APP_INSTANCE_ID='container-verification',
        SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE='3',LOGGING_LEVEL_COM_HMDP='INFO',
        JAVA_TOOL_OPTIONS='-Xms64m -Xmx256m')
    def docker(*arguments):
        return subprocess.run(['docker',*arguments],check=True,capture_output=True,text=True).stdout.strip()
    identity=docker('image','inspect','--format','{{.Config.User}}',args.image)
    assert identity=='10001:10001',identity
    container=None
    with tempfile.TemporaryDirectory(prefix='neighbor-container-') as directory:
        file=Path(directory)/'runtime.env'
        fd=os.open(file,os.O_WRONLY|os.O_CREAT|os.O_EXCL,0o600)
        with os.fdopen(fd,'w') as stream:
            for key,value in config.items():
                if '\n' in value or '\r' in value:raise ValueError('Environment values must occupy one line')
                stream.write(key+'='+value+'\n')
        try:
            container=docker('run','--detach','--rm','--network','host','--read-only',
                '--tmpfs','/tmp:rw,noexec,nosuid,size=128m','--cap-drop','ALL','--security-opt','no-new-privileges:true',
                '--env-file',str(file),args.image)
            with httpx.Client(base_url='http://127.0.0.1:8083',trust_env=False,timeout=5) as client:
                deadline=time.monotonic()+100
                while True:
                    try:
                        if client.get('/actuator/health/readiness').status_code==200:break
                    except httpx.HTTPError:pass
                    assert time.monotonic()<deadline,'Container readiness timed out'
                    assert docker('inspect','--format','{{.State.Running}}',container)=='true','Container exited'
                    time.sleep(.5)
                assert client.get('/shop/1').json()['success']
                assert client.get('/actuator/prometheus').status_code==401
                assert client.get('/v3/api-docs').json()['openapi'].startswith('3.')
            print('Read-only non-root container, readiness, business API, OpenAPI and metrics authentication: PASS')
        finally:
            if container:
                subprocess.run(['docker','stop','--time','20',container],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL,timeout=30)
                subprocess.run(['docker','rm','--force',container],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)


if __name__=='__main__':
    run()
