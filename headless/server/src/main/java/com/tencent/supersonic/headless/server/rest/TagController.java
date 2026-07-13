package com.tencent.supersonic.headless.server.rest;

import com.github.pagehelper.PageInfo;
import com.tencent.supersonic.auth.api.authentication.utils.UserHolder;
import com.tencent.supersonic.common.pojo.User;
import com.tencent.supersonic.headless.api.pojo.request.ItemValueReq;
import com.tencent.supersonic.headless.api.pojo.request.TagDeleteReq;
import com.tencent.supersonic.headless.api.pojo.request.TagFilterPageReq;
import com.tencent.supersonic.headless.api.pojo.request.TagReq;
import com.tencent.supersonic.headless.api.pojo.response.ItemValueResp;
import com.tencent.supersonic.headless.api.pojo.response.TagResp;
import com.tencent.supersonic.headless.server.persistence.dataobject.TagDO;
import com.tencent.supersonic.headless.server.pojo.TagFilter;
import com.tencent.supersonic.headless.server.service.TagMetaService;
import com.tencent.supersonic.headless.server.service.TagQueryService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 标签元数据 REST 接口。
 *
 * <p>
 * 职责：
 * <ul>
 * <li>承接标签市场、标签详情和标签对象链路中的标签增删改查请求；</li>
 * <li>在 Controller 层解析当前用户，统一交由 Service 层处理权限和业务校验；</li>
 * <li>保持二开开启标签功能后，前端现有标签编辑调用具备对应后端入口。</li>
 * </ul>
 *
 * <p>
 * 并发说明：Controller 不保存共享可变状态，线程安全由 Spring 单例无状态约束保障； 具体写入一致性依赖 Service 与 Repository 的数据库写入语义。
 */
@RestController
@RequestMapping("/api/semantic/tag")
public class TagController {

    private final TagMetaService tagMetaService;
    private final TagQueryService tagQueryService;

    public TagController(TagMetaService tagMetaService, TagQueryService tagQueryService) {
        this.tagMetaService = tagMetaService;
        this.tagQueryService = tagQueryService;
    }

    /**
     * 新建标签
     *
     * @param tagReq
     * @param request
     * @param response
     * @return
     * @throws Exception
     */
    @PostMapping("/create")
    public TagResp create(@RequestBody TagReq tagReq, HttpServletRequest request,
            HttpServletResponse response) throws Exception {
        User user = UserHolder.findUser(request, response);
        return tagMetaService.create(tagReq, user);
    }

    /**
     * 编辑标签映射。
     *
     * <p>
     * 调用示例：
     *
     * <pre>
     * {@code
     * POST /api/semantic/tag/update
     * {
     *   "id": 10,
     *   "tagDefineType": "DIMENSION",
     *   "itemId": 1001
     * }
     * }
     * </pre>
     *
     * @param tagReq 标签更新请求，必须包含已有标签 ID、标签定义类型和关联项 ID。
     * @param request HTTP 请求，用于解析当前用户。
     * @param response HTTP 响应，用于兼容现有用户解析逻辑。
     * @return 更新后的标签详情。
     * @throws Exception 当用户解析、标签不存在或标签对象绑定校验失败时抛出。
     */
    @PostMapping("/update")
    public TagResp update(@RequestBody @Valid TagReq tagReq, HttpServletRequest request,
            HttpServletResponse response) throws Exception {
        User user = UserHolder.findUser(request, response);
        return tagMetaService.update(tagReq, user);
    }

    /**
     * 从现有维度/指标批量新建标签
     *
     * @param tagReqList
     * @param request
     * @param response
     * @return
     * @throws Exception
     */
    @PostMapping("/create/batch")
    public Integer createBatch(@RequestBody @Valid List<TagReq> tagReqList,
            HttpServletRequest request, HttpServletResponse response) throws Exception {
        User user = UserHolder.findUser(request, response);
        return tagMetaService.createBatch(tagReqList, user);
    }

    /**
     * 批量删除标签
     *
     * @param tagDeleteReqList
     * @param request
     * @param response
     * @return
     * @throws Exception
     */
    @PostMapping("/delete/batch")
    public Boolean deleteBatch(@RequestBody @Valid List<TagDeleteReq> tagDeleteReqList,
            HttpServletRequest request, HttpServletResponse response) throws Exception {
        User user = UserHolder.findUser(request, response);
        return tagMetaService.deleteBatch(tagDeleteReqList, user);
    }

    /**
     * 标签删除
     *
     * @param id
     * @param request
     * @param response
     * @return
     * @throws Exception
     */
    @DeleteMapping("delete/{id}")
    public Boolean delete(@PathVariable("id") Long id, HttpServletRequest request,
            HttpServletResponse response) {
        User user = UserHolder.findUser(request, response);
        tagMetaService.delete(id, user);
        return true;
    }

    /**
     * 标签详情获取
     *
     * @param id
     * @param request
     * @param response
     * @return
     */
    @GetMapping("getTag/{id}")
    public TagResp getTag(@PathVariable("id") Long id, HttpServletRequest request,
            HttpServletResponse response) {
        User user = UserHolder.findUser(request, response);
        return tagMetaService.getTag(id, user);
    }

    /**
     * 标签查询
     *
     * @param tagFilter
     * @return
     */
    @PostMapping("/queryTag")
    public List<TagDO> queryPage(@RequestBody TagFilter tagFilter) {
        return tagMetaService.getTagDOList(tagFilter);
    }

    /**
     * 获取标签值分布信息
     *
     * @param itemValueReq
     * @param request
     * @param response
     * @return
     * @throws Exception
     */
    @PostMapping("/value/distribution")
    public ItemValueResp queryTagValue(@RequestBody ItemValueReq itemValueReq,
            HttpServletRequest request, HttpServletResponse response) throws Exception {
        User user = UserHolder.findUser(request, response);
        return tagQueryService.queryTagValue(itemValueReq, user);
    }

    /**
     * 标签市场-分页查询
     *
     * @param tagMarketPageReq
     * @param request
     * @param response
     * @return
     * @throws Exception
     */
    @PostMapping("/queryTag/market")
    public PageInfo<TagResp> queryTagMarketPage(@RequestBody TagFilterPageReq tagMarketPageReq,
            HttpServletRequest request, HttpServletResponse response) throws Exception {
        User user = UserHolder.findUser(request, response);
        return tagMetaService.queryTagMarketPage(tagMarketPageReq, user);
    }
}
