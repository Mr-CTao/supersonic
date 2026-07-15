package com.tencent.supersonic.common.pojo.exception;

/**
 * 可向统一异常处理器提供安全结构化数据的异常契约。
 *
 * <p>
 * 职责说明：common 层只定义通用协议，不依赖具体业务 DTO；实现方必须确保 data 已脱敏且不含堆栈、 凭证或完整敏感 SQL。异常按请求创建，不共享可变状态。
 * </p>
 */
public interface StructuredException {
    /** @return HTTP 兼容的业务错误码。 */
    int getStructuredCode();

    /** @return 可安全序列化的结构化数据。 */
    Object getStructuredData();
}
