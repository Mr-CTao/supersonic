package com.tencent.supersonic.forecast.server.persistence.dataobject;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

/**
 * 跨 Worker 的源库、数据流或 Profile CAS 租约。
 *
 * <p>
 * leaseKey 是服务端生成的固定类型前缀与数字 ID，不接收客户端任意字符串。
 * </p>
 */
@Data
@TableName("s2_forecast_resource_lease")
public class ForecastResourceLeaseDO {
    @TableId
    private String leaseKey;
    private Long ownerJobId;
    private String workerId;
    private Date leaseExpiresAt;
    private Integer lockVersion;
    private Date updatedAt;
}
