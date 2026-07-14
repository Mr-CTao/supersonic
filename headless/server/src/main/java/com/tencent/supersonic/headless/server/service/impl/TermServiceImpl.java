package com.tencent.supersonic.headless.server.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.tencent.supersonic.common.pojo.User;
import com.tencent.supersonic.common.util.BeanMapper;
import com.tencent.supersonic.common.util.JsonUtil;
import com.tencent.supersonic.headless.api.pojo.request.MetaBatchReq;
import com.tencent.supersonic.headless.api.pojo.request.TermReq;
import com.tencent.supersonic.headless.api.pojo.response.TermResp;
import com.tencent.supersonic.headless.server.persistence.dataobject.TermDO;
import com.tencent.supersonic.headless.server.persistence.mapper.TermMapper;
import com.tencent.supersonic.headless.server.service.TermService;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 正式语义术语管理服务实现。
 *
 * <p>
 * 职责说明：通过 MyBatis-Plus 既有业务链路创建、更新、删除与查询域级术语，并统一转换 维度/指标关联 JSON。阶段 5 仅复用本服务获得正式术语 ID，不直接访问术语元数据表。
 * 并发说明：实现不保存共享可变状态；单条写入原子性由数据库主键约束和 MyBatis-Plus 保证。
 * </p>
 */
@Service
public class TermServiceImpl extends ServiceImpl<TermMapper, TermDO> implements TermService {

    /**
     * 创建或按请求 ID 更新一个术语。
     *
     * @param termReq 术语字段及关联对象 ID。
     * @param user 当前操作者。
     * @return 持久化后的术语响应，包含数据库生成的正式 ID。
     * @throws RuntimeException 持久化失败时由现有数据访问层抛出。
     */
    @Override
    public TermResp saveOrUpdate(TermReq termReq, User user) {
        QueryWrapper<TermDO> queryWrapper = new QueryWrapper<>();
        queryWrapper.lambda().eq(TermDO::getId, termReq.getId());
        TermDO termSetDO = getOne(queryWrapper);
        if (termSetDO == null) {
            termReq.createdBy(user.getName());
            termSetDO = new TermDO();
        }
        termReq.updatedBy(user.getName());
        convert(termReq, termSetDO);
        saveOrUpdate(termSetDO);
        return convert(termSetDO);
    }

    /**
     * 按 ID 删除术语。
     *
     * @param id 术语 ID。
     */
    @Override
    public void delete(Long id) {
        removeById(id);
    }

    /**
     * 批量删除术语。
     *
     * @param metaBatchReq 待删除术语 ID 集合。
     * @throws RuntimeException ID 为空或批量删除失败。
     */
    @Override
    public void deleteBatch(MetaBatchReq metaBatchReq) {
        if (CollectionUtils.isEmpty(metaBatchReq.getIds())) {
            throw new RuntimeException("术语ID不可为空");
        }
        removeBatchByIds(metaBatchReq.getIds());
    }

    /**
     * 查询主题域内匹配关键词的术语。
     *
     * @param domainId 主题域 ID。
     * @param queryKey 可选名称、描述或别名关键词。
     * @return 匹配的术语响应列表。
     */
    @Override
    public List<TermResp> getTerms(Long domainId, String queryKey) {
        QueryWrapper<TermDO> queryWrapper = new QueryWrapper<>();
        queryWrapper.lambda().eq(TermDO::getDomainId, domainId);
        if (StringUtils.isNotBlank(queryKey)) {
            queryWrapper.lambda().and(i -> i.like(TermDO::getName, queryKey).or()
                    .like(TermDO::getDescription, queryKey).or().like(TermDO::getAlias, queryKey));
        }
        List<TermDO> termDOS = list(queryWrapper);
        return termDOS.stream().map(this::convert).collect(Collectors.toList());
    }

    /**
     * 批量查询并按主题域分组术语。
     *
     * @param domainIds 主题域 ID 集合。
     * @return 主题域 ID 到术语列表的映射。
     */
    @Override
    public Map<Long, List<TermResp>> getTermSets(Set<Long> domainIds) {
        if (CollectionUtils.isEmpty(domainIds)) {
            return new HashMap<>();
        }
        QueryWrapper<TermDO> queryWrapper = new QueryWrapper<>();
        queryWrapper.lambda().in(TermDO::getDomainId, domainIds);
        List<TermDO> list = list(queryWrapper);
        return list.stream().map(this::convert)
                .collect(Collectors.groupingBy(TermResp::getDomainId));
    }

    /** 把持久化对象转换为 API 响应并解析 JSON 关联字段。 */
    private TermResp convert(TermDO termDO) {
        TermResp termSetResp = new TermResp();
        BeanMapper.mapper(termDO, termSetResp);
        termSetResp.setAlias(JsonUtil.toList(termDO.getAlias(), String.class));
        termSetResp.setRelatedMetrics(JsonUtil.toList(termDO.getRelatedMetrics(), Long.class));
        termSetResp.setRelateDimensions(JsonUtil.toList(termDO.getRelatedDimensions(), Long.class));
        return termSetResp;
    }

    /** 把 API 请求复制到持久化对象并序列化关联字段。 */
    private void convert(TermReq termReq, TermDO termDO) {
        BeanMapper.mapper(termReq, termDO);
        termDO.setAlias(JsonUtil.toString(termReq.getAlias()));
        termDO.setRelatedDimensions(JsonUtil.toString(termReq.getRelateDimensions()));
        termDO.setRelatedMetrics(JsonUtil.toString(termReq.getRelatedMetrics()));
    }
}
