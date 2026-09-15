package com.example.mealcheck.service;

import com.example.mealcheck.config.AppProperties;
import com.example.mealcheck.dto.assistant.AssistantChatMessage;
import com.example.mealcheck.entity.AssistantConversation;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class AssistantMemoryServiceTest {

    @Test
    void createsRollingSummaryStateAndStandaloneFollowUpQuestion() {
        AppProperties properties = new AppProperties();
        properties.getAssistantMemory().setRecentMessages(4);
        AssistantConversationService conversationService = mock(AssistantConversationService.class);
        AssistantMemoryService service = new AssistantMemoryService(
                properties, conversationService, mock(ApplicationObservability.class));
        AssistantConversation conversation = new AssistantConversation();
        List<AssistantChatMessage> history = List.of(
                message("user", "我最近想减脂"),
                message("assistant", "可以控制总热量"),
                message("user", "午餐经常吃米饭和鸡腿"),
                message("assistant", "注意搭配蔬菜"),
                message("user", "晚餐吃了炸鸡和奶茶"),
                message("assistant", "这一餐油脂和糖偏高"),
                message("user", "明天准备吃同样的晚餐"),
                message("assistant", "建议更换烹饪方式")
        );

        AssistantMemoryService.MemoryContext context = service.prepare(
                conversation, "那这个怎么调整？", "FAT_LOSS", history, history);

        assertThat(context.summary()).contains("我最近想减脂").contains("注意搭配蔬菜");
        assertThat(context.state()).contains("intent=FAT_LOSS").contains("明天准备吃同样的晚餐");
        assertThat(context.standaloneQuestion()).contains("当前对话主题：明天准备吃同样的晚餐");
        assertThat(context.workingMessages()).hasSizeLessThanOrEqualTo(8);
        verify(conversationService).updateMemory(
                eq(conversation), eq(context.summary()), eq(context.state()), eq(4));
    }

    @Test
    void deduplicatesMultipleHistorySourcesAndRespectsTokenBudget() {
        AppProperties properties = new AppProperties();
        properties.getAssistantMemory().setRecentMessages(6);
        properties.getAssistantMemory().setHistoryTokenBudget(200);
        AssistantMemoryService service = new AssistantMemoryService(
                properties, mock(AssistantConversationService.class), mock(ApplicationObservability.class));
        List<AssistantChatMessage> messages = new ArrayList<>();
        for (int index = 0; index < 10; index++) {
            messages.add(message(index % 2 == 0 ? "user" : "assistant",
                    "第" + index + "条消息" + "饮食建议".repeat(40)));
        }
        messages.add(message("user", messages.get(8).getText()));

        List<AssistantChatMessage> selected = service.selectWorkingMessages("饮食建议", messages);

        assertThat(service.estimateTokens(selected)).isLessThanOrEqualTo(200);
        assertThat(selected.stream().map(item -> item.getRole() + item.getText()).distinct().count())
                .isEqualTo(selected.size());
        assertThat(selected.get(selected.size() - 1).getText()).contains("第9条消息");
    }

    private AssistantChatMessage message(String role, String text) {
        return new AssistantChatMessage(role, text);
    }
}
