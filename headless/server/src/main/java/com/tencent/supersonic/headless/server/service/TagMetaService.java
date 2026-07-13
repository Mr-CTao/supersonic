package com.tencent.supersonic.headless.server.service;

import com.github.pagehelper.PageInfo;
import com.tencent.supersonic.common.pojo.User;
import com.tencent.supersonic.headless.api.pojo.enums.TagDefineType;
import com.tencent.supersonic.headless.api.pojo.request.TagDeleteReq;
import com.tencent.supersonic.headless.api.pojo.request.TagFilterPageReq;
import com.tencent.supersonic.headless.api.pojo.request.TagReq;
import com.tencent.supersonic.headless.api.pojo.response.TagItem;
import com.tencent.supersonic.headless.api.pojo.response.TagResp;
import com.tencent.supersonic.headless.server.persistence.dataobject.TagDO;
import com.tencent.supersonic.headless.server.pojo.TagFilter;

import java.util.List;

/**
 * 标签元数据领域服务。
 *
 * <p>
 * 职责：
 * <ul>
 * <li>维护标签与维度、指标等语义元素之间的映射关系；</li>
 * <li>为标签市场提供分页、收藏、权限和标签对象信息补全；</li>
 * <li>为二开开启标签功能后提供完整的创建、编辑、删除和查询服务契约。</li>
 * </ul>
 *
 * <p>
 * 并发说明：接口实现不维护 JVM 内共享状态；多请求并发写入时以数据库主键、 唯一性校验和 Repository 写入结果作为一致性边界。
 */
public interface TagMetaService {

    /**
     * 创建标签映射。
     *
     * @param tagReq 标签创建请求。
     * @param user 当前操作用户。
     * @return 创建后的标签详情。
     */
    TagResp create(TagReq tagReq, User user);

    /**
     * 更新标签映射。
     *
     * @param tagReq 标签更新请求。
     * @param user 当前操作用户。
     * @return 更新后的标签详情。
     */
    TagResp update(TagReq tagReq, User user);

    Integer createBatch(List<TagReq> tagReqList, User user);

    Boolean delete(Long id, User user);

    Boolean deleteBatch(List<TagDeleteReq> tagDeleteReqList, User user);

    TagResp getTag(Long id, User user);

    List<TagResp> getTags(TagFilter tagFilter);

    List<TagDO> getTagDOList(TagFilter tagFilter);

    PageInfo<TagResp> queryTagMarketPage(TagFilterPageReq tagMarketPageReq, User user);

    List<TagItem> getTagItems(List<Long> itemIds, TagDefineType tagDefineType);
}
