package com.tencent.supersonic.headless.server.semantic.routing;

import com.tencent.supersonic.common.pojo.DataEvent;
import com.tencent.supersonic.common.pojo.User;
import com.tencent.supersonic.common.pojo.enums.EventType;
import com.tencent.supersonic.common.pojo.enums.TypeEnums;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * 已有语义资产复用校验协调器测试。
 *
 * <p>验证复用动作沿用正式资产事件链刷新知识，而不是创建空草稿或执行 SQL。</p>
 */
class SemanticAssetReuseValidationServiceTest {

    /** 确认前校验不得发布事件，CAS 胜者调用刷新后才发布单模型 UPDATE 事件。 */
    @Test
    void shouldValidateVersionAndPublishExistingRefreshEvent() {
        SemanticAssetRoutingPermissionService permissionService =
                mock(SemanticAssetRoutingPermissionService.class);
        ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
        SemanticAssetReuseValidationService service =
                new SemanticAssetReuseValidationService(permissionService, publisher);
        SemanticAssetCandidate candidate = SemanticAssetCandidate.builder()
                .candidateHandle("candidate_1").assetType("MODEL").assetId(7L)
                .assetVersion(99L).name("库存汇总").bizName("stock_summary")
                .domainId(5L).build();
        User user = User.getDefaultUser();

        service.validateCandidate(candidate, user);
        verifyNoInteractions(publisher);
        service.refreshConfirmed(9L, candidate, user);

        verify(permissionService, times(2)).requireReadableCandidateVersion(candidate, user);
        ArgumentCaptor<DataEvent> eventCaptor = ArgumentCaptor.forClass(DataEvent.class);
        verify(publisher).publishEvent(eventCaptor.capture());
        DataEvent event = eventCaptor.getValue();
        assertEquals(EventType.UPDATE, event.getEventType());
        assertEquals(user.getName(), event.getUserName());
        assertEquals(1, event.getDataItems().size());
        assertEquals(TypeEnums.MODEL, event.getDataItems().get(0).getType());
        assertEquals("7", event.getDataItems().get(0).getModelId());
    }

    /** LLM 或客户端无法用非模型候选触发任意知识刷新。 */
    @Test
    void shouldRejectUnsupportedCandidateType() {
        SemanticAssetReuseValidationService service = new SemanticAssetReuseValidationService(
                mock(SemanticAssetRoutingPermissionService.class),
                mock(ApplicationEventPublisher.class));
        SemanticAssetCandidate candidate = SemanticAssetCandidate.builder()
                .candidateHandle("candidate_1").assetType("METRIC").assetId(7L).build();

        SemanticAssetRoutingException exception = assertThrows(
                SemanticAssetRoutingException.class,
                () -> service.validateCandidate(candidate, User.getDefaultUser()));

        assertEquals("REUSE_TARGET_INVALID", exception.getErrorCode());
    }
}
