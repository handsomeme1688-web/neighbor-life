package com.hmdp.sms;

import com.aliyun.dysmsapi20170525.Client;
import com.aliyun.dysmsapi20170525.models.QuerySendDetailsRequest;
import com.aliyun.dysmsapi20170525.models.QuerySendDetailsResponse;
import com.aliyun.dysmsapi20170525.models.SendSmsRequest;
import com.aliyun.dysmsapi20170525.models.SendSmsResponse;
import com.aliyun.teaopenapi.models.Config;
import com.hmdp.exception.BusinessException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * Alibaba Cloud SMS adapter with bounded network timeouts and explicit receipts.
 * @author NeighborLife
 */
@Component
@ConditionalOnProperty(name = "app.sms.mode", havingValue = "aliyun")
public class AliyunSmsSender implements SmsSender {
    private static final String PROVIDER_SUCCESS = "OK";
    private static final long DELIVERED_STATUS = 3L;

    private final Client client;
    private final String signName;
    private final String templateCode;

    @org.springframework.beans.factory.annotation.Autowired
    public AliyunSmsSender(@Value("${app.sms.access-key-id:}") String accessKeyId,
            @Value("${app.sms.access-key-secret:}") String accessKeySecret,
            @Value("${app.sms.sign-name:}") String signName,
            @Value("${app.sms.template-code:}") String templateCode) throws Exception {
        if (accessKeyId.isEmpty() || accessKeySecret.isEmpty() || signName.isEmpty() || templateCode.isEmpty()) {
            throw new IllegalArgumentException("阿里云短信需要完整的RAM凭据、签名与模板配置");
        }
        this.client = new Client(new Config().setAccessKeyId(accessKeyId).setAccessKeySecret(accessKeySecret)
                .setEndpoint("dysmsapi.aliyuncs.com").setConnectTimeout(3000).setReadTimeout(5000));
        this.signName = signName;
        this.templateCode = templateCode;
    }

    AliyunSmsSender(Client client, String signName, String templateCode) {
        this.client = client;
        this.signName = signName;
        this.templateCode = templateCode;
    }

    @Override
    public String send(String phone, String code) {
        try {
            SendSmsResponse response = client.sendSms(new SendSmsRequest().setPhoneNumbers(phone)
                    .setSignName(signName).setTemplateCode(templateCode)
                    .setTemplateParam("{\"code\":\"" + code + "\"}"));
            if (response.getBody() == null || !PROVIDER_SUCCESS.equals(response.getBody().getCode())) {
                throw new BusinessException("SMS_REJECTED", "短信发送暂未完成，请稍后重试", HttpStatus.BAD_GATEWAY);
            }
            return response.getBody().getBizId();
        } catch (BusinessException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new BusinessException("SMS_UNAVAILABLE", "短信通道暂不可用", HttpStatus.SERVICE_UNAVAILABLE);
        }
    }

    @Override
    public String receipt(String phone, String bizId, String sendDate) {
        try {
            QuerySendDetailsResponse response = client.querySendDetails(new QuerySendDetailsRequest()
                    .setPhoneNumber(phone).setBizId(bizId).setSendDate(sendDate).setCurrentPage(1L).setPageSize(1L));
            if (response.getBody() == null || !PROVIDER_SUCCESS.equals(response.getBody().getCode())
                    || response.getBody().getSmsSendDetailDTOs() == null
                    || response.getBody().getSmsSendDetailDTOs().getSmsSendDetailDTO().isEmpty()) {
                return "PENDING";
            }
            Long status = response.getBody().getSmsSendDetailDTOs().getSmsSendDetailDTO().get(0).getSendStatus();
            if (Long.valueOf(DELIVERED_STATUS).equals(status)) {
                return "DELIVERED";
            }
            return Long.valueOf(2L).equals(status) ? "FAILED" : "PENDING";
        } catch (Exception exception) {
            throw new BusinessException("SMS_UNAVAILABLE", "短信回执查询暂不可用", HttpStatus.SERVICE_UNAVAILABLE);
        }
    }
}
