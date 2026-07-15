package com.tencent.supersonic.headless.server.listener;

import com.tencent.supersonic.common.pojo.Constants;
import com.tencent.supersonic.common.pojo.DataEvent;
import com.tencent.supersonic.common.pojo.DataItem;
import com.tencent.supersonic.common.pojo.enums.DictWordType;
import com.tencent.supersonic.common.pojo.enums.EventType;
import com.tencent.supersonic.headless.api.pojo.response.RefreshStatus;
import com.tencent.supersonic.headless.chat.knowledge.DictWord;
import com.tencent.supersonic.headless.chat.knowledge.helper.HanlpHelper;
import com.tencent.supersonic.headless.server.semantic.diagnostic.ModelHealthService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;

import java.util.List;

/**
 * 将语义资产事件异步同步到 HanLP 自定义词典，并记录可观测刷新状态。
 */
@Component
@Slf4j
public class SchemaDictUpdateListener {

    private final ModelHealthService modelHealthService;

    public SchemaDictUpdateListener(ModelHealthService modelHealthService) {
        this.modelHealthService = modelHealthService;
    }

    /**
     * 应用词典增删改；任一失败会标记相关模型 FAILED 并保留完整服务端堆栈。
     *
     * @param dataEvent 正式资产变更事件。
     */
    @Async("eventExecutor")
    @EventListener
    @Order(Ordered.LOWEST_PRECEDENCE)
    public void onApplicationEvent(DataEvent dataEvent) {
        if (CollectionUtils.isEmpty(dataEvent.getDataItems())) {
            return;
        }
        List<Long> modelIds = ModelHealthService.toModelIds(dataEvent.getDataItems());
        modelHealthService.recordDictionary(modelIds, RefreshStatus.RUNNING);
        try {
            dataEvent.getDataItems().forEach(dataItem -> updateDictionary(dataEvent, dataItem));
            modelHealthService.recordDictionary(modelIds, RefreshStatus.SUCCEEDED);
        } catch (RuntimeException exception) {
            modelHealthService.recordDictionary(modelIds, RefreshStatus.FAILED);
            log.error("schema dictionary refresh failed, modelIds={}", modelIds, exception);
            throw exception;
        }
    }

    /** 根据事件类型更新单个词条。 */
    private void updateDictionary(DataEvent dataEvent, DataItem dataItem) {
        DictWord dictWord = new DictWord();
        dictWord.setWord(dataItem.getName());
        String sign = DictWordType.NATURE_SPILT;
        String suffixNature = DictWordType.getSuffixNature(dataItem.getType());
        String nature = sign + dataItem.getModelId() + sign + dataItem.getId() + suffixNature;
        String natureWithFrequency = nature + " " + Constants.DEFAULT_FREQUENCY;
        dictWord.setNature(nature);
        dictWord.setNatureWithFrequency(natureWithFrequency);
        if (EventType.ADD.equals(dataEvent.getEventType())) {
            HanlpHelper.addToCustomDictionary(dictWord);
        } else if (EventType.DELETE.equals(dataEvent.getEventType())) {
            HanlpHelper.removeFromCustomDictionary(dictWord);
        } else if (EventType.UPDATE.equals(dataEvent.getEventType())) {
            HanlpHelper.removeFromCustomDictionary(dictWord);
            dictWord.setWord(dataItem.getNewName());
            HanlpHelper.addToCustomDictionary(dictWord);
        }
    }
}
