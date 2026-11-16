"""Verify shop and coupon invalidation on two local simulated application instances."""
import json
import time
import uuid
from datetime import datetime, timedelta
from zoneinfo import ZoneInfo
import httpx
import local


def run():
    env = local.env_values()
    redis = local.redis_client(env)
    with local.database(env) as database, database.cursor() as cursor:
        cursor.execute("SELECT id FROM tb_user WHERE phone=%s", ("19900009991",))
        admin = cursor.fetchone()[0]
    token = uuid.uuid4().hex
    redis.hset("login:token:"+token, mapping={"id":admin,"nickName":"双实例验收"})
    redis.expire("login:token:"+token,600)
    with httpx.Client(base_url="http://127.0.0.1:8081",headers={"Authorization":token},trust_env=False,timeout=20) as first, \
         httpx.Client(base_url="http://127.0.0.1:8082",headers={"Authorization":token},trust_env=False,timeout=20) as second:
        try:
            def api(client,method,path,**kwargs):
                response=client.request(method,path,**kwargs)
                assert response.status_code==200,response.text[:200]
                data=response.json()
                assert data['success'],data
                return data.get('data')
            for client in (first,second):
                assert client.get('/actuator/info').json()['app']['payment-mode']=='mock'
            now=datetime.now(ZoneInfo('Asia/Shanghai'))
            shop=api(first,'POST','/shop',json={'name':'双实例验收','typeId':1,'images':'/imgs/demo.png','address':'本机','x':120.1,'y':30.2})
            coupon=api(first,'POST','/voucher/seckill',json={'shopId':shop,'title':'双实例原始券','payValue':100,'actualValue':200,'stock':1,
                'beginTime':(now-timedelta(minutes=1)).replace(tzinfo=None).isoformat(timespec='seconds'),
                'endTime':(now+timedelta(hours=1)).replace(tzinfo=None).isoformat(timespec='seconds')})
            for client in (first,second):
                assert api(client,'GET',f'/shop/{shop}')['name']=='双实例验收'
                assert api(client,'GET',f'/voucher/{coupon}')['title']=='双实例原始券'
            api(first,'PUT','/shop',json={'id':shop,'name':'双实例更新商铺'})
            api(first,'PUT','/voucher',json={'id':coupon,'title':'双实例更新券','status':2})
            deadline=time.monotonic()+20
            while True:
                converged=all(api(client,'GET',f'/shop/{shop}')['name']=='双实例更新商铺'
                    and api(client,'GET',f'/voucher/{coupon}')['title']=='双实例更新券' for client in (first,second))
                if converged:break
                assert time.monotonic()<deadline,'Cross-instance invalidation timed out'
                time.sleep(.2)
            print('Two-instance shop and coupon invalidation: PASS')
            (local.STACK/'cache-instance-verification.json').write_text(json.dumps({'instances':2,'shopId':shop,'voucherId':coupon,'passed':True},indent=2))
        finally:
            redis.delete('login:token:'+token)
            redis.close()


if __name__=='__main__':
    run()
